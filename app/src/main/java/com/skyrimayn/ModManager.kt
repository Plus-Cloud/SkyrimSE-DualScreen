package com.skyrimayn

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.URLUtil
import app.gamenative.mods.FomodEnvironmentSnapshot
import app.gamenative.mods.FomodFactState
import app.gamenative.mods.FomodInstaller
import app.gamenative.mods.FomodInstallerDetector
import app.gamenative.mods.FomodParser
import app.gamenative.mods.FomodPlugin
import app.gamenative.mods.ModArchiveExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * SkyrimSE-DualScreen's mod manager.
 *
 * Everything lives inside the game folder, under SkyrimAYN/, so it survives reinstalling the app:
 *  - downloads/   archives as downloaded (reinstall any time)
 *  - staging/     scratch space for extraction (same filesystem as Data, so deploys are renames)
 *  - backup/<id>/ files a mod replaced, restored when the mod above it is deleted
 *  - mods.json    installed mods and, per file in Data, the stack of owners ("_base" = the game's own file)
 */
object ModManager {
    data class InstalledMod(
        val id: String, val name: String, val version: String, val archive: String,
        val files: List<String>, val plugins: List<String>, val sizeBytes: Long, val installedAt: Long,
        val nexusId: Int = 0,
        val fileId: Int = 0,
    )

    enum class DlState { DOWNLOADING, READY, FAILED }
    data class Download(
        val id: String, val name: String, val file: File, val bytes: Long, val total: Long,
        val state: DlState, val error: String? = null,
    ) {
        val progress get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else -1f
    }

    data class Busy(val title: String, val detail: String, val progress: Float = -1f)

    /** A FOMOD installer waiting for the player's choices (shown on the bottom screen). */
    data class FomodSession(
        val id: String, val modName: String, val version: String, val archive: File,
        val staging: File, val installer: FomodInstaller, val env: FomodEnvironmentSnapshot,
    )

    private val _mods = MutableStateFlow<List<InstalledMod>>(emptyList())
    val mods: StateFlow<List<InstalledMod>> = _mods
    private val _downloads = MutableStateFlow<List<Download>>(emptyList())
    val downloads: StateFlow<List<Download>> = _downloads
    private val _busy = MutableStateFlow<Busy?>(null)
    val busy: StateFlow<Busy?> = _busy
    private val _fomod = MutableStateFlow<FomodSession?>(null)
    val fomod: StateFlow<FomodSession?> = _fomod
    private val _notice = MutableStateFlow<String?>(null)
    /** One-line messages for the UI ("SkyUI installed"). Cleared by the UI after showing. */
    val notice: StateFlow<String?> = _notice
    fun clearNotice() { _notice.value = null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    /** Runs [block] when no install or removal is under way (a launch syncs the mods folder this way). */
    suspend fun <T> whileIdle(block: () -> T): T = lock.withLock { block() }
    private lateinit var appContext: Context
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
    }

    private var owners = HashMap<String, MutableList<String>>()   // lowercased Data path -> owner stack (bottom..top)

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        loadAccount()
    }

    /** The player's Skyrim folder (game files, and SkyrimAYN/ with mods.json and downloads). */
    private fun realGameDir(): File? = GameFolder.dir.value?.let(::File)
    /** The folder mods are installed into: the mirror in the app's storage (see ModOverlay), once it exists. */
    private fun gameDir(): File? = GameFolder.modded()?.let(::File)
    private fun root(game: File) = File(game, "SkyrimAYN")
    private fun dataDir(game: File) = File(game, "Data")
    private fun downloadsDir(game: File) = File(root(game), "downloads").apply { mkdirs() }

    fun gameRunning(): Boolean = app.gamenative.PluviaApp.xServerView != null

    // ---------------------------------------------------------------- index

    suspend fun load() = withContext(Dispatchers.IO) {
        val real = realGameDir() ?: return@withContext
        lock.withLock {
            val synced = runCatching { ModOverlay.sync(appContext.filesDir, real) }.onFailure { Timber.e(it, "SkyrimAYN: mods folder sync failed") }.isSuccess
            val game = gameDir() ?: return@withLock
            readIndex(game)
            if (synced) restoreMissing(game)
        }
        gameDir()?.let(::refreshDownloads)
    }

    /**
     * Mods whose files are gone (the app was uninstalled, which deletes its storage) are unpacked again from
     * their saved archives, so nothing needs downloading. Mods with an installer (FOMOD) ask their options again.
     */
    private fun restoreMissing(game: File) {
        val paths = ModOverlay.Paths(game)
        val gone = _mods.value.filter { m ->
            m.files.isNotEmpty() && m.files.none { rel -> target(paths, rel).let { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) } }
        }
        if (gone.isEmpty()) return
        gone.forEach { m -> m.files.forEach { rel -> owners[rel.lowercase()]?.let { st -> st.remove(m.id); if (st.isEmpty() || st == listOf("_base")) owners.remove(rel.lowercase()) } } }
        writeIndex(game, _mods.value.filter { it !in gone })
        val archives = gone.mapNotNull { m -> File(downloadsDir(game), m.archive).takeIf { it.isFile } }
        Timber.i("SkyrimAYN: ${gone.size} mods missing their files; reinstalling ${archives.size} from saved downloads")
        if (archives.isNotEmpty()) _notice.value = "Reinstalling ${archives.size} mod${if (archives.size == 1) "" else "s"} from your saved downloads"
        archives.forEach(::install)
    }

    private fun indexFile(game: File) = File(root(game), "mods.json")

    private fun readIndex(game: File) {
        val f = indexFile(game)
        val mods = ArrayList<InstalledMod>()
        owners = HashMap()
        if (f.isFile) runCatching {
            val j = JSONObject(f.readText())
            val arr = j.optJSONArray("mods") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                mods += InstalledMod(
                    m.getString("id"), m.getString("name"), m.optString("version"), m.optString("archive"),
                    m.optJSONArray("files").strings(), m.optJSONArray("plugins").strings(),
                    m.optLong("size"), m.optLong("installedAt"), m.optInt("nexusId"), m.optInt("fileId"),
                )
            }
            val o = j.optJSONObject("owners") ?: JSONObject()
            o.keys().forEach { k -> owners[k] = o.getJSONArray(k).strings().toMutableList() }
        }.onFailure { Timber.w(it, "SkyrimAYN: mods.json unreadable") }
        for ((i, m) in mods.withIndex()) {
            val (n, v, id) = parseNexusName(m.archive)
            if (m.name == m.archive.substringBeforeLast('.') && n != m.name) mods[i] = m.copy(name = n, version = m.version.ifBlank { v }, nexusId = id.takeIf { it > 0 } ?: m.nexusId)
        }
        _mods.value = mods
    }

    private fun writeIndex(game: File, mods: List<InstalledMod>) {
        val j = JSONObject()
        j.put("version", 1)
        j.put("mods", JSONArray().apply {
            mods.forEach { m ->
                put(JSONObject().put("id", m.id).put("name", m.name).put("version", m.version).put("archive", m.archive)
                    .put("files", JSONArray(m.files)).put("plugins", JSONArray(m.plugins))
                    .put("size", m.sizeBytes).put("installedAt", m.installedAt).put("nexusId", m.nexusId).put("fileId", m.fileId))
            }
        })
        j.put("owners", JSONObject().apply { owners.forEach { (k, v) -> put(k, JSONArray(v)) } })
        val f = indexFile(game).apply { parentFile?.mkdirs() }
        val tmp = File(f.parentFile, "mods.json.tmp")
        tmp.writeText(j.toString(1))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        _mods.value = mods
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }

    private fun refreshDownloads(game: File) {
        val active = _downloads.value.filter { it.state == DlState.DOWNLOADING }
        val onDisk = downloadsDir(game).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { Download(it.name, prettyName(it.name).first, it, it.length(), it.length(), DlState.READY) }
            .orEmpty()
        val failed = _downloads.value.filter { it.state == DlState.FAILED }
        _downloads.value = active + failed + onDisk.filter { d -> active.none { it.id == d.id } }
    }

    // ---------------------------------------------------------------- downloads

    /**
     * Downloads a mod archive into SkyrimAYN/downloads, then installs it.
     * [cookies]/[userAgent] come from the in-app Nexus browser, so the site's signed CDN link works.
     */
    fun download(url: String, contentDisposition: String?, mime: String?, cookies: String?, userAgent: String?, fileName: String? = null) {
        val game = gameDir() ?: run { _notice.value = "Choose your Skyrim folder first"; return }
        val name = (fileName ?: URLUtil.guessFileName(url, contentDisposition, mime)).replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val target = File(downloadsDir(game), name)
        val part = File(target.path + ".part")
        val id = name
        update(Download(id, prettyName(name).first, target, 0, -1, DlState.DOWNLOADING))
        _notice.value = "Downloading ${prettyName(name).first}"
        scope.launch {
            try {
                val req = Request.Builder().url(url).apply {
                    cookies?.let { header("Cookie", it) }
                    userAgent?.let { header("User-Agent", it) }
                }.build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty response")
                    val total = body.contentLength()
                    var done = 0L
                    var lastPost = 0L
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastPost > 250) { lastPost = now; update(Download(id, prettyName(name).first, target, done, total, DlState.DOWNLOADING)) }
                            }
                        }
                    }
                }
                target.delete()
                if (!part.renameTo(target)) throw IOException("Couldn't save $name")
                update(Download(id, prettyName(name).first, target, target.length(), target.length(), DlState.READY))
                install(target)
            } catch (e: Exception) {
                Timber.w(e, "SkyrimAYN: download failed")
                part.delete()
                update(Download(id, prettyName(name).first, target, 0, -1, DlState.FAILED, e.message))
                _notice.value = "Download failed: ${e.message}"
            }
        }
    }

    private fun update(d: Download) {
        _downloads.value = listOf(d) + _downloads.value.filter { it.id != d.id }
    }

    fun deleteDownload(d: Download) {
        if (d.state == DlState.DOWNLOADING) return
        d.file.delete()
        File(d.file.path + ".part").delete()
        _downloads.value = _downloads.value.filter { it.id != d.id }
    }

    /** Copies an archive picked with the system file picker into downloads, then installs it. */
    fun importArchive(uri: Uri) {
        val game = gameDir() ?: return
        scope.launch {
            try {
                val name = appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "mod.zip"
                _busy.value = Busy("COPYING", prettyName(name).first)
                val target = File(downloadsDir(game), name)
                appContext.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it, 1 shl 20) } }
                _busy.value = null
                refreshDownloads(game)
                install(target)
            } catch (e: Exception) {
                _busy.value = null
                _notice.value = "Couldn't read that file: ${e.message}"
            }
        }
    }

    // ---------------------------------------------------------------- install

    /** Nexus file names look like "SkyUI_5_2_SE-12604-5-2SE-1573000000.7z": name, mod id, version, timestamp. */
    fun prettyName(file: String): Pair<String, String> = parseNexusName(file).let { it.first to it.second }

    /**
     * Name, version and Nexus mod id from an archive name. Nexus uses two styles:
     * "SkyUI_5_2_SE-12604-5-2SE-1573000000.7z" and "Unofficial Skyrim Special Edition Patch 266 4.3.9c 2026-09-05T22-22Z lNkz9LChN.7z".
     */
    fun parseNexusName(file: String): Triple<String, String, Int> {
        val base = file.substringBeforeLast('.')
        Regex("^(.+?) (\\d+) ([0-9][\\w.]*) \\d{4}-\\d{2}-\\d{2}T\\S+( \\S+)?$").find(base)?.let { m ->
            return Triple(m.groupValues[1].trim(), m.groupValues[3], m.groupValues[2].toIntOrNull() ?: 0)
        }
        Regex("^(.*?)-(\\d+)-([0-9][0-9A-Za-z-]*?)(?:-(\\d{9,}))?$").find(base)?.let { m ->
            return Triple(m.groupValues[1].replace('_', ' ').trim(), m.groupValues[3].replace('-', '.'), m.groupValues[2].toIntOrNull() ?: 0)
        }
        return Triple(base.replace('_', ' ').trim(), "", 0)
    }

    private val installQueue = kotlinx.coroutines.channels.Channel<File>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var installerRunning = false

    /** Installs one at a time. A mod with an options installer holds the next ones back until the player has answered it. */
    fun install(archive: File) {
        synchronized(installQueue) {
            if (!installerRunning) {
                installerRunning = true
                scope.launch { for (next in installQueue) { installNow(next); _fomod.first { it == null } } }
            }
        }
        installQueue.trySend(archive)
    }

    private suspend fun installNow(archive: File) {
        run {
            lock.withLock {
                val game = gameDir() ?: return@withLock
                if (gameRunning()) { _notice.value = "Close Skyrim before installing mods"; return@withLock }
                readIndex(game)
                val (name, version) = prettyName(archive.name)
                val id = slug(name, _mods.value.map { it.id }.toSet())
                val staging = File(ModOverlay.stagingDir(appContext.filesDir), id)
                try {
                    _busy.value = Busy("UNPACKING", name, 0f)
                    ModArchiveExtractor.extract(archive, staging) { p ->
                        _busy.value = Busy("UNPACKING", name, if (p.totalBytes > 0) p.extractedBytes.toFloat() / p.totalBytes else -1f)
                    }
                    val cfg = FomodInstallerDetector.moduleConfigFile(staging)
                    if (cfg != null) {
                        val installer = FomodParser.parse(cfg, staging)
                        if (installer.steps.any { s -> s.groups.isNotEmpty() } || installer.requiredFiles.isNotEmpty()) {
                            _busy.value = null
                            _fomod.value = FomodSession(id, installer.moduleName.ifBlank { name }, version, archive, staging, installer, environment(game))
                            return@withLock
                        }
                    }
                    // The Script Extender itself (from its official Nexus page) installs its loader and DLL into the game folder.
                    // By the Nexus id in the file name too: an archive installed from a file, or reinstalled later, has no download record.
                    val isSkse = nexusMeta[archive.name]?.modId == SKSE_NEXUS_ID || archive.name.startsWith("skse64_", true) ||
                        parseNexusName(archive.name).third == SKSE_NEXUS_ID
                    val files = mapArchive(staging, isSkse)
                    if (files.isEmpty()) throw IOException("The archive is empty")
                    deploy(game, id, name, version, archive.name, files, move = true)
                } catch (e: Exception) {
                    Timber.w(e, "SkyrimAYN: install failed")
                    _notice.value = "Install failed: ${e.message}"
                } finally {
                    _busy.value = null
                    if (_fomod.value?.staging != staging) staging.deleteRecursively()
                }
            }
        }
    }

    fun cancelFomod() {
        _fomod.value?.staging?.deleteRecursively()
        _fomod.value = null
    }

    /** Installs what the player picked in the FOMOD wizard. */
    fun finishFomod(selected: List<FomodPlugin>) {
        val s = _fomod.value ?: return
        scope.launch {
            lock.withLock {
                val game = gameDir() ?: return@withLock
                try {
                    _busy.value = Busy("INSTALLING", s.modName)
                    val flags = HashMap<String, String>()
                    selected.forEach { flags.putAll(it.conditionFlags) }
                    val mappings = s.installer.requiredFiles + selected.flatMap { it.files } +
                        s.installer.conditionalFileInstalls.filter { it.dependencies.evaluate(flags, s.env) == FomodFactState.TRUE }.flatMap { it.files }
                    val base = s.installer.basePath.trim('/')
                    val lookup = s.staging.walkTopDown().associateBy { it.relativeTo(s.staging).invariantSeparatorsPath.lowercase() }
                    fun resolve(path: String): File? {
                        val clean = path.replace('\\', '/').trim('/')
                        val full = if (base.isEmpty()) clean else "$base/$clean"
                        return lookup[full.lowercase()]
                    }
                    val files = LinkedHashMap<String, File>()
                    for (m in mappings.withIndex().sortedWith(compareBy({ it.value.priority }, { it.index })).map { it.value }) {
                        val src = resolve(m.source) ?: continue
                        val dest = m.destination.replace('\\', '/').trim('/')
                        if (m.directory || src.isDirectory) {
                            src.walkTopDown().filter { it.isFile }.forEach { f ->
                                val rel = f.relativeTo(src).invariantSeparatorsPath
                                files[if (dest.isEmpty()) rel else "$dest/$rel"] = f
                            }
                        } else {
                            files[dest.ifEmpty { m.source.replace('\\', '/').trim('/') }] = src
                        }
                    }
                    // Only a readme means none of the real options were picked.
                    if (files.isEmpty() || files.keys.all { it.endsWith(".txt", true) }) throw IOException("Nothing selected to install")
                    _fomod.value = null
                    deploy(game, s.id, s.modName, s.version, s.archive.name, files, move = false)
                } catch (e: Exception) {
                    Timber.w(e, "SkyrimAYN: FOMOD install failed")
                    _notice.value = "Install failed: ${e.message}"
                    _fomod.value = null
                } finally {
                    _busy.value = null
                    s.staging.deleteRecursively()
                }
            }
        }
    }

    private fun environment(game: File): FomodEnvironmentSnapshot {
        val data = dataDir(game)
        val present = data.listFiles()?.filter { it.isFile }?.map { it.name.lowercase() }?.toSet().orEmpty()
        val plugins = present.filter { it.endsWith(".esp") || it.endsWith(".esm") || it.endsWith(".esl") }.toSet()
        val active = LoadOrder.plugins.value.filter { it.active }.map { it.name.lowercase() }.toSet().ifEmpty { plugins }
        return FomodEnvironmentSnapshot(
            gameName = "Skyrim Special Edition", gameVersion = "1.7.104",
            fileFacts = present.associateWith { true },
            pluginStateKnown = true, presentPlugins = plugins, activePlugins = active,
        )
    }

    private val dataFolders = setOf(
        "meshes", "textures", "scripts", "interface", "skse", "sound", "music", "seq", "strings", "shadersfx",
        "lodsettings", "video", "grass", "facegen", "materials", "source", "dialogueviews", "calientetools",
        "netscriptframework", "skyproc patchers", "nemesis_engine", "tools", "lightplacer", "distantlod", "terrain",
    )
    private val dataExts = setOf("esp", "esm", "esl", "bsa", "ini", "modgroups")

    private fun isDataish(f: File) =
        if (f.isDirectory) f.name.lowercase() in dataFolders else f.extension.lowercase() in dataExts

    /** Paths under this prefix go to the game's main folder (next to SkyrimSE.exe) instead of Data. */
    const val ROOT = "<root>/"
    /** Files a mod must never replace in the game folder: the game, SKSE, and our dinput8.dll (it loads the companion). */
    private val protectedRoot = Regex("^(skyrimse\\.exe|skyrimselauncher\\.exe|skse64_.*|dinput8\\.dll)$", RegexOption.IGNORE_CASE)

    /** Where [rel] goes, spelled like the folders and files already there (see ModOverlay.Paths). */
    private fun target(paths: ModOverlay.Paths, rel: String) = paths[if (rel.startsWith(ROOT)) rel.removePrefix(ROOT) else "Data/$rel"]
    /** Backup path for [rel] (no angle brackets: shared storage doesn't allow them). */
    private fun backupRel(rel: String) = if (rel.startsWith(ROOT)) "_root_/" + rel.removePrefix(ROOT) else rel

    /**
     * Maps an unpacked archive to install paths. Data content goes to Data; a "Root" folder (the MO2/Vortex
     * convention), or loose DLLs/EXEs in an archive meant to be "extracted to the game folder", go to the
     * game's main folder.
     */
    internal fun mapArchive(staging: File, isSkse: Boolean = false): Map<String, File> {
        var base = staging
        repeat(4) {
            val kids = base.listFiles()?.filter { !it.name.startsWith("__MACOSX") }.orEmpty()
            val dirs = kids.filter { it.isDirectory && !it.name.equals("fomod", true) }
            if (dirs.size == 1 && kids.none { it.isFile && !isJunk(it.name) } && !isDataish(dirs[0]) &&
                !dirs[0].name.equals("Data", true) && !dirs[0].name.equals("Root", true)) base = dirs[0] else return@repeat
        }
        val out = LinkedHashMap<String, File>()
        fun addTree(dir: File, prefix: String) = dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            if (!rel.startsWith("fomod/", true) && !isJunk(rel)) out[prefix + rel] = f
        }
        val kids = base.listFiles()?.filter { !it.name.startsWith("__MACOSX") && !it.name.equals("fomod", true) }.orEmpty()
        val rootDir = kids.firstOrNull { it.isDirectory && it.name.equals("Root", true) }
        val dataDir = kids.firstOrNull { it.isDirectory && it.name.equals("Data", true) }
        val looseBinaries = kids.any { it.isFile && it.extension.lowercase() in setOf("dll", "exe") }
        when {
            rootDir != null -> {
                addTree(rootDir, ROOT)
                kids.filter { it != rootDir }.forEach { k ->
                    if (k == dataDir) addTree(k, "") else if (k.isDirectory) addTree(k, k.name + "/") else if (!isJunk(k.name)) out[k.name] = k
                }
            }
            dataDir != null && looseBinaries -> {          // "extract to your Skyrim folder"
                addTree(dataDir, "")
                kids.filter { it != dataDir }.forEach { k -> if (k.isDirectory) addTree(k, ROOT + k.name + "/") else if (!isJunk(k.name)) out[ROOT + k.name] = k }
            }
            looseBinaries && kids.none(::isDataish) -> addTree(base, ROOT)   // e.g. a preloader: DLLs for the game folder
            else -> { val d = findDataRoot(staging); addTree(d, "") }
        }
        if (isSkse) return out.filterKeys { !it.startsWith(ROOT + "src/", true) && !it.startsWith("src/", true) }
        return out.filterKeys { !(it.startsWith(ROOT) && protectedRoot.matches(it.removePrefix(ROOT))) }
    }

    /** Where the Data-relative content starts: "Data/", the archive root, or one wrapper folder down. */
    private fun findDataRoot(staging: File): File {
        var d = staging
        repeat(4) {
            val kids = d.listFiles()?.filter { !it.name.startsWith("__MACOSX") }.orEmpty()
            kids.firstOrNull { it.isDirectory && it.name.equals("Data", true) }?.let { return it }
            if (kids.any(::isDataish)) return d
            val dirs = kids.filter { it.isDirectory && !it.name.equals("fomod", true) }
            if (dirs.size == 1 && kids.none { it.isFile && !isJunk(it.name) }) { d = dirs[0]; return@repeat }
            return d
        }
        return d
    }

    private fun isJunk(rel: String): Boolean {
        val n = rel.substringAfterLast('/').lowercase()
        return n == "thumbs.db" || n == ".ds_store" || n == "desktop.ini" ||
            (rel.indexOf('/') < 0 && (n.startsWith("readme") || n.endsWith(".txt") && n.contains("readme")))
    }

    private fun slug(name: String, taken: Set<String>): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "mod" }
        var s = base; var i = 2
        while (s in taken || s == "_base") s = "$base-${i++}"
        return s
    }

    /** Puts [files] (Data-relative path -> source) into Data, backing up whatever they replace. */
    private suspend fun deploy(game: File, id: String, name: String, version: String, archive: String, files: Map<String, File>, move: Boolean) {
        SaveGames.backup(appContext, game)
        val data = dataDir(game)
        val backup = ModOverlay.backupDir(appContext.filesDir)
        val paths = ModOverlay.Paths(game)
        val backups = ModOverlay.Paths(backup)
        var size = 0L
        var done = 0
        val placed = ArrayList<String>()
        for ((rel, src) in files) {
            val key = rel.lowercase()
            val dst = target(paths, rel)
            ModOverlay.materialize(game, dst)   // never write through a link into the player's folder
            val stack = owners.getOrPut(key) { if (dst.exists()) mutableListOf("_base") else mutableListOf() }
            if (dst.exists() && stack.isNotEmpty()) {
                val bak = backups["${stack.last()}/${backupRel(rel)}"]
                bak.parentFile?.mkdirs()
                bak.delete()
                moveFile(dst, bak)
            }
            dst.parentFile?.mkdirs()
            if (java.nio.file.Files.isSymbolicLink(dst.toPath())) java.nio.file.Files.delete(dst.toPath())
            size += src.length()
            if (move) moveFile(src, dst) else src.copyTo(dst, overwrite = true)
            stack.remove(id)
            stack += id
            placed += rel
            if (++done % 25 == 0) _busy.value = Busy("INSTALLING", name, done.toFloat() / files.size)
        }
        val plugins = placed.filter { it.indexOf('/') < 0 && it.substringAfterLast('.').lowercase() in setOf("esp", "esm", "esl") }
        val meta = nexusMeta[archive]
        val nexusId = meta?.modId ?: parseNexusName(archive).third
        val mod = InstalledMod(id, meta?.name ?: name, meta?.version ?: version, archive, placed, plugins, size, System.currentTimeMillis(), nexusId, meta?.fileId ?: 0)
        writeIndex(game, _mods.value.filter { it.id != id } + mod)
        LoadOrder.load(appContext, game.path)       // new plugins join the load order, enabled...
        LoadOrder.autoSort(game.path)                // ...then everything is placed after its masters
        LoadOrder.save(appContext)
        _notice.value = "${meta?.name ?: name} installed" + if (plugins.isNotEmpty()) " · ${plugins.size} plugin${if (plugins.size > 1) "s" else ""} enabled" else ""
        Timber.i("SkyrimAYN: installed $id (${placed.size} files, ${plugins.size} plugins)")
    }

    private fun moveFile(from: File, to: File) {
        if (from.renameTo(to)) return
        from.copyTo(to, overwrite = true)
        from.delete()
    }

    // ---------------------------------------------------------------- uninstall

    fun uninstall(mod: InstalledMod) {
        scope.launch {
            lock.withLock {
                val game = gameDir() ?: return@withLock
                if (gameRunning()) { _notice.value = "Close Skyrim before deleting mods"; return@withLock }
                readIndex(game)
                val data = dataDir(game)
                val backup = ModOverlay.backupDir(appContext.filesDir)
                val paths = ModOverlay.Paths(game)
                val backups = ModOverlay.Paths(backup)
                _busy.value = Busy("REMOVING", mod.name)
                SaveGames.backup(appContext, game)
                try {
                    val dirs = HashSet<File>()
                    for (rel in mod.files) {
                        val key = rel.lowercase()
                        val stack = owners[key] ?: continue
                        val at = stack.lastIndexOf(mod.id)
                        if (at < 0) continue
                        val dst = target(paths, rel)
                        if (at == stack.lastIndex) {
                            dst.delete()
                            stack.removeAt(at)
                            if (stack.isNotEmpty()) {
                                val bak = backups["${stack.last()}/${backupRel(rel)}"]
                                if (bak.exists()) moveFile(bak, dst)
                            }
                        } else {
                            backups["${mod.id}/${backupRel(rel)}"].delete()
                            stack.removeAt(at)
                        }
                        if (stack.isEmpty() || stack == listOf("_base")) owners.remove(key)
                        dst.parentFile?.let(dirs::add)
                    }
                    // Tidy folders the mod created and left empty.
                    dirs.sortedByDescending { it.path.length }.forEach { d ->
                        var cur: File? = d
                        while (cur != null && cur != data && cur != game && cur.path.startsWith(game.path) && (cur.list()?.isEmpty() == true)) {
                            cur.delete(); cur = cur.parentFile
                        }
                    }
                    File(backup, mod.id).deleteRecursively()
                    writeIndex(game, _mods.value.filter { it.id != mod.id })
                    LoadOrder.load(appContext, game.path)
                    LoadOrder.autoSort(game.path)
                    LoadOrder.save(appContext)
                    _notice.value = "${mod.name} removed"
                } catch (e: Exception) {
                    Timber.w(e, "SkyrimAYN: uninstall failed")
                    _notice.value = "Couldn't remove ${mod.name}: ${e.message}"
                } finally {
                    _busy.value = null
                }
            }
        }
    }

    // ---------------------------------------------------------------- Nexus account and downloads from the in-app browser

    data class NexusMeta(val modId: Int, val name: String, val version: String, val fileId: Int = 0)
    /** The file a player asked for, by mod: the nxm:// answer from Nexus names the file differently. */
    private val requestedByMod = HashMap<Int, NexusMeta>()
    /** Archive file name -> the Nexus mod it came from (so installs get the real name and id). */
    private val nexusMeta = HashMap<String, NexusMeta>()

    private val _account = MutableStateFlow<NexusApi.User?>(null)
    val account: StateFlow<NexusApi.User?> = _account

    /** A free member's file waiting for them to confirm on Nexus's download page. */
    data class FreeConfirm(val mod: NexusApi.Mod, val file: NexusApi.ModFile)
    private val _freeConfirm = MutableStateFlow<FreeConfirm?>(null)
    val freeConfirm: StateFlow<FreeConfirm?> = _freeConfirm
    fun cancelFreeConfirm() {
        _freeConfirm.value = null
        synchronized(queue) { queue.clear(); queuedIds.clear(); _queued.value = 0 }
    }

    fun loadAccount() {
        val p = appContext.getSharedPreferences(PREFS, 0)
        val name = p.getString("user", null) ?: return
        _account.value = NexusApi.User(name, p.getBoolean("premium", false), null)
    }

    /** Checks a personal API key with Nexus and keeps it. Returns an error message, or null when signed in. */
    suspend fun signIn(key: String): String? = try {
        val u = NexusApi.validate(key.trim())
        setApiKey(key.trim())
        appContext.getSharedPreferences(PREFS, 0).edit().putString("user", u.name).putBoolean("premium", u.premium).apply()
        _account.value = u
        null
    } catch (e: Exception) { e.message ?: "Couldn't sign in" }

    fun signOut() {
        setApiKey(null)
        appContext.getSharedPreferences(PREFS, 0).edit().remove("user").remove("premium").apply()
        _account.value = null
    }

    fun isInstalled(modId: Int, fileId: Int) = _mods.value.any { it.nexusId == modId && (it.fileId == fileId || it.fileId == 0) }

    /** What the player has for this file: installed, downloaded (archive kept), downloading, or nothing. */
    enum class FileState { NONE, DOWNLOADING, DOWNLOADED, INSTALLED }
    fun fileState(modId: Int, fileId: Int, fileName: String): FileState = when {
        _mods.value.any { it.nexusId == modId && it.fileId == fileId } -> FileState.INSTALLED
        _downloads.value.any { it.id == fileName && it.state == DlState.DOWNLOADING } -> FileState.DOWNLOADING
        _downloads.value.any { it.id == fileName && it.state == DlState.READY } -> FileState.DOWNLOADED
        else -> FileState.NONE
    }

    fun installedNexusIds(): Set<Int> = _mods.value.map { it.nexusId }.filter { it > 0 }.toSet() +
        (if (gameDir()?.let { SkyrimEngine.skseInstalled(it.path) } == true) setOf(SKSE_NEXUS_ID) else emptySet())
    /** The Script Extender's official Nexus page (its authors allow downloads only from there and their site). */
    const val SKSE_NEXUS_ID = 30379

    /**
     * Setup: fetch SKSE 2.3.1 (the build for Skyrim 1.7.104) from its official Nexus page and install it.
     * Premium accounts download straight away; free accounts get Nexus's one SLOW DOWNLOAD confirmation.
     */
    fun getSkse() {
        if (apiKey() == null) return
        scope.launch {
            try {
                val mod = NexusApi.mod(SKSE_NEXUS_ID).first
                val files = NexusApi.files(SKSE_NEXUS_ID).filter { it.current }
                val file = files.firstOrNull { it.fileName.contains("2_03_01") || it.version.trim() == "2.3.1" || it.version.contains("2.03.01") }
                    ?: files.firstOrNull { it.category == "MAIN" && !it.fileName.contains("gog", true) }
                    ?: run { _notice.value = "Couldn't find the Script Extender file on Nexus"; return@launch }
                withContext(Dispatchers.Main) { get(mod, file) }
            } catch (e: Exception) {
                _notice.value = "Couldn't reach Nexus Mods: ${e.message}"
            }
        }
    }

    /** A mod whose requirements aren't installed: the player chooses whether to get them first. */
    data class RequirementsPrompt(val mod: NexusApi.Mod, val file: NexusApi.ModFile, val missing: List<NexusApi.Requirement>)
    private val _reqPrompt = MutableStateFlow<RequirementsPrompt?>(null)
    val reqPrompt: StateFlow<RequirementsPrompt?> = _reqPrompt

    /** Files waiting their turn (free accounts confirm one at a time). */
    private val queue = ArrayDeque<Pair<NexusApi.Mod, NexusApi.ModFile>>()
    private val _queued = MutableStateFlow(0)
    val queued: StateFlow<Int> = _queued
    private val queuedIds = HashSet<Int>()

    /** Download a file, first asking about any requirements that aren't installed. */
    fun request(mod: NexusApi.Mod, file: NexusApi.ModFile) {
        if (isInstalled(mod.modId, file.fileId)) { _notice.value = "${mod.name} is already installed"; return }
        if (_downloads.value.any { it.state == DlState.DOWNLOADING && it.id == file.fileName }) return
        val have = installedNexusIds() + queuedIds
        val missing = mod.requirements.filter { it.gameId == NexusApi.GAME_ID && it.modId != mod.modId && it.modId !in have }
        if (missing.isNotEmpty()) _reqPrompt.value = RequirementsPrompt(mod, file, missing)
        else enqueue(mod, file)
    }

    /** The player's answer to [reqPrompt]: get the requirements first (their main files), or only this mod. */
    fun answerRequirements(getThemFirst: Boolean?) {
        val p = _reqPrompt.value ?: return
        _reqPrompt.value = null
        if (getThemFirst == null) return
        if (!getThemFirst) { enqueue(p.mod, p.file); return }
        scope.launch {
            for (r in p.missing) {
                try {
                    val (m, _) = NexusApi.mod(r.modId)
                    val files = NexusApi.files(r.modId)
                    val f = files.firstOrNull { it.category == "MAIN" && it.primary } ?: files.firstOrNull { it.category == "MAIN" } ?: files.firstOrNull { it.current }
                    if (f != null) enqueue(m, f) else _notice.value = "${r.name} has no file to download"
                } catch (e: Exception) {
                    _notice.value = "Couldn't get ${r.name}: ${e.message}"
                }
            }
            enqueue(p.mod, p.file)
        }
    }

    private fun enqueue(mod: NexusApi.Mod, file: NexusApi.ModFile) {
        synchronized(queue) { queue.addLast(mod to file); queuedIds += mod.modId; _queued.value = queue.size }
        pump()
    }

    /** Starts the next queued file unless a free-account confirmation is open. */
    private fun pump() {
        while (true) {
            if (_freeConfirm.value != null) return
            val next = synchronized(queue) { queue.removeFirstOrNull().also { _queued.value = queue.size } } ?: return
            get(next.first, next.second)
            if (_freeConfirm.value != null) return
        }
    }

    /** Download [file] of [mod] and install it. Premium: straight away. Free: one confirmation on Nexus first. */
    fun get(mod: NexusApi.Mod, file: NexusApi.ModFile) {
        val meta = NexusMeta(mod.modId, mod.name, file.version.ifBlank { mod.version }, file.fileId)
        nexusMeta[file.fileName] = meta
        requestedByMod[mod.modId] = meta
        // Already downloaded? Install from the copy we have instead of downloading again.
        gameDir()?.let { g -> File(downloadsDir(g), file.fileName).takeIf { it.isFile }?.let { install(it); return } }
        val key = apiKey() ?: run { _notice.value = "Sign in to Nexus Mods first"; return }
        if (_account.value?.premium != true) { _freeConfirm.value = FreeConfirm(mod, file); return }
        scope.launch {
            if (_account.value?.premium == true) {
                try {
                    download(NexusApi.premiumLink(key, mod.modId, file.fileId), null, null, null, null, fileName = file.fileName)
                    return@launch
                } catch (e: Exception) {
                    if (e.message != "PREMIUM_ONLY") { _notice.value = "Nexus download failed: ${e.message}"; return@launch }
                }
            }
            _freeConfirm.value = FreeConfirm(mod, file)
        }
    }

    // ---------------------------------------------------------------- mod lists

    /** Which Nexus mod a download belongs to. */
    fun downloadModId(d: Download): Int = nexusMeta[d.file.name]?.modId ?: parseNexusName(d.file.name).third

    /** Nexus mods waiting their turn, including the one waiting on a free-account confirmation. */
    fun queuedMods(): Set<Int> = synchronized(queue) { queue.map { it.first.modId }.toSet() } + listOfNotNull(_freeConfirm.value?.mod?.modId)

    fun postNotice(message: String) { _notice.value = message }

    /**
     * Queues mods from a list, skipping what's installed or already on its way. Premium accounts download them
     * by themselves; free accounts get Nexus's confirmation one file at a time, opening by itself.
     */
    fun requestFromList(entries: List<ModLists.Entry>, plugins: List<Pair<String, Boolean>>, openNexus: () -> Unit) {
        scope.launch {
            if (apiKey() == null) {
                _notice.value = "Sign in to Nexus Mods first"
                withContext(Dispatchers.Main) { openNexus() }
                return@launch
            }
            if (plugins.isNotEmpty()) LoadOrder.writeImported(appContext, plugins)
            val busyIds = _downloads.value.filter { it.state == DlState.DOWNLOADING }.map(::downloadModId).toSet() + queuedMods()
            val wanted = entries.filter { it.nexusId > 0 && it.nexusId !in installedNexusIds() && it.nexusId !in busyIds }
            var opened = false
            for (e in wanted) {
                try {
                    val mod = NexusApi.mod(e.nexusId).first
                    val files = NexusApi.files(e.nexusId)
                    // The exact file the list was made with; if the author removed it, their current main file.
                    val file = files.firstOrNull { it.fileId == e.fileId }
                        ?: files.firstOrNull { it.category == "MAIN" && it.primary } ?: files.firstOrNull { it.category == "MAIN" } ?: files.firstOrNull { it.current }
                    if (file == null) { _notice.value = "${e.name} has no file to download"; continue }
                    enqueue(mod, file)
                    if (!opened && _account.value?.premium != true) { opened = true; withContext(Dispatchers.Main) { openNexus() } }
                } catch (x: Exception) {
                    Timber.w(x, "SkyrimAYN: couldn't read %s from Nexus", e.name)
                    _notice.value = "Couldn't get ${e.name}: ${x.message}"
                }
            }
        }
    }

    /**
     * Copies a whole setup from a folder brought over from another device: a Mod Organizer 2 or Wabbajack
     * install (mods/, profiles/, overwrite/) in its own priority order, or just a folder of mod folders.
     */
    fun importFolder(path: String) {
        scope.launch {
            lock.withLock {
                val game = gameDir() ?: return@withLock
                if (gameRunning()) { _notice.value = "Close Skyrim before installing mods"; return@withLock }
                try {
                    readIndex(game)
                    val source = File(path)
                    val modsRoot = File(source, "mods").takeIf { it.isDirectory } ?: source
                    val profile = File(source, "profiles").listFiles { f -> f.isDirectory && File(f, "modlist.txt").isFile }?.maxByOrNull { File(it, "modlist.txt").lastModified() }
                    val names = profile?.let { File(it, "modlist.txt").readLines() }
                        // modlist.txt lists the highest priority first; installing lowest first gets conflicts right.
                        ?.filter { it.startsWith("+") }?.map { it.drop(1).trim() }?.filterNot { it.endsWith("_separator") }?.reversed()
                        ?: modsRoot.listFiles { f -> f.isDirectory }?.map { it.name }?.sortedBy { it.lowercase() }.orEmpty()
                    val folders = names.map { File(modsRoot, it) }.filter { it.isDirectory } + listOfNotNull(File(source, "overwrite").takeIf { d -> d.list()?.isNotEmpty() == true })
                    if (folders.isEmpty()) throw IOException("No mods found in that folder")
                    var imported = 0
                    for ((i, folder) in folders.withIndex()) {
                        if (_mods.value.any { it.name.equals(folder.name, true) }) continue
                        _busy.value = Busy("IMPORTING ${i + 1} OF ${folders.size}", folder.name, i.toFloat() / folders.size)
                        val files = mapArchive(folder)
                        if (files.isEmpty()) continue
                        val meta = File(folder, "meta.ini").takeIf { it.isFile }?.readLines().orEmpty()
                        fun field(key: String) = meta.firstOrNull { it.substringAfterLast('\\').startsWith("$key=", true) }?.substringAfter('=')?.trim().orEmpty()
                        val source2 = "imported:${folder.name}"
                        nexusMeta[source2] = NexusMeta(field("modid").toIntOrNull() ?: 0, folder.name, field("version"), field("fileid").toIntOrNull() ?: 0)
                        deploy(game, slug(folder.name, _mods.value.map { it.id }.toSet()), folder.name, field("version"), source2, files, move = false)
                        imported++
                    }
                    profile?.let { File(it, "plugins.txt") }?.takeIf { it.isFile }?.readLines()?.map { it.trim() }
                        ?.filter { it.isNotEmpty() && !it.startsWith("#") }
                        ?.map { it.removePrefix("*") to it.startsWith("*") }
                        ?.let { LoadOrder.writeImported(appContext, it) }
                    _notice.value = "$imported mods imported"
                } catch (e: Exception) {
                    Timber.w(e, "SkyrimAYN: folder import failed")
                    _notice.value = "Import failed: ${e.message}"
                } finally {
                    _busy.value = null
                }
            }
        }
    }

    // ---------------------------------------------------------------- Nexus "Mod Manager Download" (nxm://) links

    private const val PREFS = "skyrimayn_nexus"
    fun apiKey(): String? = appContext.getSharedPreferences(PREFS, 0).getString("api_key", null)?.takeIf { it.isNotBlank() }
    fun setApiKey(key: String?) = appContext.getSharedPreferences(PREFS, 0).edit().putString("api_key", key?.trim()).apply()

    /**
     * nxm://skyrimspecialedition/mods/{mod}/files/{file}?key=..&expires=.. -> download via the Nexus API.
     * Needs the player's personal API key (nexusmods.com > Site preferences > API keys).
     */
    fun handleNxm(url: String): Boolean {
        val m = Regex("^nxm://([^/]+)/mods/(\\d+)/files/(\\d+)", RegexOption.IGNORE_CASE).find(url) ?: return false
        val (game, modId, fileId) = m.destructured
        val q = Uri.parse(url)
        val key = apiKey()
        if (key == null) {
            _notice.value = "Sign in to Nexus Mods first"
            return true
        }
        scope.launch {
            try {
                fun get(path: String): String {
                    val req = Request.Builder().url("https://api.nexusmods.com/v1/games/$game/mods/$modId/$path")
                        .header("apikey", key).header("Application-Name", "SkyrimSE-DualScreen").header("Application-Version", "1.0").build()
                    http.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IOException(if (r.code == 401) "Nexus API key rejected" else "Nexus said ${r.code}")
                        return r.body!!.string()
                    }
                }
                val fileInfo = JSONObject(get("files/$fileId.json"))
                // Keep the mod's real name and ids for the archive Nexus hands us.
                val fname = fileInfo.optString("file_name")
                val meta = requestedByMod[modId.toInt()]?.copy(fileId = fileId.toInt())
                    ?: NexusMeta(modId.toInt(), fileInfo.optString("name").ifBlank { prettyName(fname).first }, fileInfo.optString("version"), fileId.toInt())
                if (fname.isNotBlank()) nexusMeta[fname] = meta
                val params = listOfNotNull(q.getQueryParameter("key")?.let { "key=$it" }, q.getQueryParameter("expires")?.let { "expires=$it" })
                val links = JSONArray(get("files/$fileId/download_link.json" + if (params.isEmpty()) "" else "?" + params.joinToString("&")))
                val link = links.getJSONObject(0).getString("URI")
                _freeConfirm.value = null
                download(link, null, null, null, null, fileName = fileInfo.optString("file_name").ifBlank { null })
                pump()
            } catch (e: Exception) {
                _notice.value = "Nexus download failed: ${e.message}"
            }
        }
        return true
    }
}

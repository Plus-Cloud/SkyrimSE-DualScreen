package com.skyrimayn

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The last thing that went wrong, as the app saw it (a crash, a stuck launch, mods hanging, SKSE missing...).
 * Kept across restarts, so a report sent after reopening the app still says what happened. A report fills in its
 * title and "what happened" from this, so the player doesn't have to describe it.
 */
object Problems {
    data class Problem(val title: String, val detail: String, val at: Long)
    private const val PREFS = "problems"
    /** Older than this, a problem isn't assumed to be what a report is about. */
    private const val FRESH_MS = 6 * 60 * 60 * 1000L

    private val _last = kotlinx.coroutines.flow.MutableStateFlow<Problem?>(null)
    val last: kotlinx.coroutines.flow.StateFlow<Problem?> = _last
    private var ctx: Context? = null

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        val p = context.getSharedPreferences(PREFS, 0)
        val title = p.getString("title", null) ?: return
        _last.value = Problem(title, p.getString("detail", "").orEmpty(), p.getLong("at", 0))
    }

    /** [title]: a short issue title ("Skyrim closed unexpectedly"). [detail]: what the player was told, plus facts. */
    fun record(title: String, detail: String) {
        val p = Problem(title, detail, System.currentTimeMillis())
        _last.value = p
        timber.log.Timber.w("SkyrimAYN: problem: $title — $detail")
        ctx?.getSharedPreferences(PREFS, 0)?.edit()?.putString("title", p.title)?.putString("detail", p.detail)?.putLong("at", p.at)?.apply()
    }

    /** The problem a report made now is most likely about, if any. */
    fun recent(): Problem? = _last.value?.takeIf { System.currentTimeMillis() - it.at < FRESH_MS }

    /** After a report was sent for it: the next report starts fresh. */
    fun clear() {
        _last.value = null
        ctx?.getSharedPreferences(PREFS, 0)?.edit()?.clear()?.apply()
    }
}

/** Bundles what's needed to diagnose a problem into a zip in Downloads and opens a GitHub issue for it. Sign-in secrets are removed. */
object BugReport {
    private const val MAX_LOG_BYTES = 2_000_000
    private const val REPO = "Plus-Cloud/SkyrimSE-DualScreen"
    /** Where the last report was saved, for the notice ("Download/....zip"). */
    var lastSaved: String? = null; private set

    private val secrets = listOf(
        Regex("(?i)(api[_-]?key|apikey|token|password|passwd|refresh|authorization|cookie)([\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',;&]+"),
        Regex("[A-Za-z0-9+/_-]{32,}={0,3}"),
    )

    private fun clean(text: String): String {
        var out = text
        out = secrets[0].replace(out) { it.groupValues[1] + it.groupValues[2] + "[removed]" }
        return secrets[1].replace(out, "[removed]")
    }

    private fun appLog(): String = runCatching {
        Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "6000", "--pid=${android.os.Process.myPid()}")).inputStream.bufferedReader().readText()
    }.getOrDefault("(log unavailable)")

    private fun info(ctx: Context, game: File?): String = buildString {
        appendLine("App ${Updater.currentVersion}, plugin ${SkyrimEngine.PLUGIN_VERSION}")
        appendLine("Device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        val memory = android.app.ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
        appendLine("RAM ${memory.totalMem / (1024 * 1024)} MB")
        appendLine("SKSE installed: ${GameFolder.modded()?.let { SkyrimEngine.skseInstalled(it) } == true}")
        GameFolder.modded()?.let(SkyrimEngine::skseProblem)?.let { appendLine("SKSE problem: $it") }
        appendLine()
        LauncherSettings.values.value.toSortedMap().forEach { (k, v) -> appendLine("$k = $v") }
        appendLine()
        appendLine("Mods:")
        ModManager.mods.value.forEach { appendLine("  ${it.name} ${it.version} (Nexus ${it.nexusId})") }
        appendLine("Plugins:")
        LoadOrder.plugins.value.forEach { appendLine("  ${if (it.active) "*" else " "} ${it.name}") }
        appendLine()
        appendLine("Saves (newest first):")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        SaveGames.dir(ctx).listFiles()?.sortedByDescending { it.lastModified() }?.take(25)?.forEach {
            appendLine("  ${fmt.format(java.util.Date(it.lastModified()))}  ${it.length()}  ${if (it.canWrite()) "rw" else "READ-ONLY"}  ${it.name}")
        } ?: appendLine("  (none)")
    }

    /** Opens the share sheet with the report. Returns false if it couldn't be built. */
    /**
     * What the Report buttons call: asks the player what happened (a text box with the bottom screen's keyboard;
     * Enter sends), then builds the report and opens the filled-in issue. [onResult] gets the line to show after.
     * Without a bottom screen (single-screen), goes straight on without asking.
     */
    fun askAndShare(ctx: Context, scope: kotlinx.coroutines.CoroutineScope, onResult: (String) -> Unit) {
        var typed = ""
        fun send() = scope.launch { onResult(if (share(ctx, typed.trim())) attachHint() else "Couldn't build the report") }
        val label = Problems.recent()?.let { "What were you doing when it happened? (optional, Enter sends)" }
            ?: "What went wrong? (Enter sends)"
        val asked = BottomScreenHost.editText(label = label, initial = "", onChange = { typed = it }, onDone = { send() })
        if (!asked) send()
    }

    /** Builds the report (with [note], the player's own words) and opens a pre-filled GitHub issue. */
    suspend fun share(ctx: Context, note: String = ""): Boolean {
        val zip = withContext(Dispatchers.IO) {
            runCatching {
                val game = GameFolder.dir.value?.let(::File)
                val skse = File(ContainerRootLocator.root(ctx), ".wine/drive_c/users/xuser/Documents/My Games/Skyrim Special Edition/SKSE")
                val out = File(ctx.cacheDir, "skyrimse-dualscreen-report.zip")
                ZipOutputStream(out.outputStream()).use { z ->
                    fun add(name: String, text: String) {
                        z.putNextEntry(ZipEntry(name)); z.write(clean(text).toByteArray()); z.closeEntry()
                    }
                    add("setup.txt", info(ctx, game))
                    add("app-log.txt", appLog())
                    listOf("SkyrimAYN.log", "skse64.log", "skse64_loader.log", "skse64_steam_loader.log").forEach { n ->
                        File(skse, n).takeIf { it.isFile }?.let { add(n, it.readBytes().takeLast(MAX_LOG_BYTES).toByteArray().decodeToString()) }
                    }
                }
                // A copy beside the mods, so a report can be fetched from the Thor without the share sheet.
                game?.let { runCatching { out.copyTo(File(it, "SkyrimAYN/last-report.zip"), overwrite = true) } }
                out
            }.getOrNull()
        } ?: return false
        // Saved to Downloads (easy to find and attach), then a pre-filled GitHub issue opens in the browser.
        val name = "SkyrimSE-DualScreen-report-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".zip"
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                File(dir, name).also { zip.copyTo(it, overwrite = true) }
            }.getOrNull()
        } ?: return false
        lastSaved = "Download/${saved.name}"
        val problem = Problems.recent()
        val url = android.net.Uri.parse("https://github.com/$REPO/issues/new").buildUpon()
            .appendQueryParameter("labels", "bug")
            .appendQueryParameter("title", title(problem, note))
            .appendQueryParameter("body", body(problem, saved.name, note)).build()
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        problem?.let { Problems.clear() }
        return true
    }

    /** What the launcher says after building a report: the one step left for the player. */
    fun attachHint(): String = "Report saved. On the GitHub page, tap \"Paste, drop, or click to add files\" under the text box " +
        "and pick ${lastSaved?.substringAfterLast('/') ?: "the report"} from Downloads, then Submit."

    internal fun title(problem: Problems.Problem?, note: String = ""): String =
        "[${Updater.currentVersion}] " + (problem?.title ?: note.trim().take(60).ifEmpty { "Problem report" })

    /** The issue text, filled in as far as the app can: what went wrong, the setup that matters, and the file to attach. */
    internal fun body(problem: Problems.Problem?, fileName: String, note: String = ""): String = buildString {
        appendLine("**What happened**")
        if (problem != null) {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            appendLine("_Detected by the app at ${fmt.format(java.util.Date(problem.at))}:_ ${problem.detail}")
            appendLine()
            appendLine()
            appendLine(if (note.isNotBlank()) "**In the player's words:** ${note.take(1000)}" else "Anything else you noticed (optional):")
        } else {
            appendLine(if (note.isNotBlank()) note.take(1000) else "(Describe what went wrong and what you were doing.)")
        }
        appendLine()
        appendLine()
        appendLine("**Setup**")
        appendLine("- App ${Updater.currentVersion} · plugin ${SkyrimEngine.PLUGIN_VERSION} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        val s = LauncherSettings.values.value
        appendLine("- Mods: ${s["mods"] ?: "?"} (${ModManager.mods.value.size} installed) · Frame gen: ${s["frame_gen"] ?: "?"} · Power: ${s["power"] ?: "?"} · Translator: ${s["translator"] ?: "?"}")
        val modded = GameFolder.modded()
        appendLine("- SKSE: " + when {
            modded == null -> "?"
            SkyrimEngine.skseInstalled(modded) -> "installed"
            else -> SkyrimEngine.skseProblem(modded) ?: "not installed"
        })
        appendLine()
        appendLine("**Report file** (logs, no passwords): tap \"Paste, drop, or click to add files\" below and pick `$fileName` from Downloads.")
    }
}

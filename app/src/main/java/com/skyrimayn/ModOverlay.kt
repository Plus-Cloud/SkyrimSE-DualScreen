package com.skyrimayn

import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.file.Files

/**
 * Where mods live: the app's own storage (1.0.3).
 *
 * Skyrim reads the player's game folder through Android's shared-storage layer, which is slow for a big mod list
 * (26 mods: 225 s to the main menu; from the app's own storage: 38 s). So the app keeps a mirror of the game
 * folder in its own storage, files/modlink/<folder name>:
 *  - links to everything in the player's Skyrim folder (the game itself stays there, untouched);
 *  - the mods' real files. The mod manager installs into and removes from this mirror.
 * A modded launch points Wine at the mirror; "without mods" uses the player's folder, which is now plain Skyrim.
 *
 * mods.json, the downloaded archives and the mod lists stay in the player's folder (SkyrimAYN/), so they survive
 * uninstalling the app; mods whose files are gone are unpacked again from their saved archives (ModManager.load).
 * Builds before 1.0.3 put mods into the game's Data folder: [sync] moves them here once and puts back any game
 * file they had replaced.
 *
 * Folders in the mirror start as links to the player's folders. Before a file is written below one,
 * [materialize] turns it into a real folder of links, so nothing is ever written into the player's folder.
 * Clean-ups delete links themselves and never follow them.
 */
object ModOverlay {
    /** The one-time move of an older install's mods, while it runs: files moved so far and in all. */
    val moving = kotlinx.coroutines.flow.MutableStateFlow<Pair<Int, Int>?>(null)

    @Volatile private var active: String? = null

    /** The folder Wine should use as the game folder, or null for the player's folder. Read by WineUtils. */
    @JvmStatic fun activeDir(): String? = active

    fun rootFor(filesDir: File, game: File) = File(filesDir, "modlink/${game.name}")
    fun backupDir(filesDir: File) = File(filesDir, "modbackup")
    fun stagingDir(filesDir: File) = File(filesDir, "modstaging")
    private fun migratedMarker(filesDir: File, game: File) = File(filesDir, "modlink/.moved-${game.name}")

    private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())

    /** Deletes a file or folder in the mirror. A link is removed itself, never followed into the player's files. */
    fun remove(f: File) {
        if (isLink(f)) { Files.delete(f.toPath()); return }
        if (f.isDirectory) f.listFiles()?.forEach(::remove)
        f.delete()
    }

    private fun link(target: File, at: File) {
        if (isLink(at) && Files.readSymbolicLink(at.toPath()).toString() == target.path) return
        if (isLink(at) || at.exists()) remove(at)
        Files.createSymbolicLink(at.toPath(), target.toPath())
    }

    /** Makes every folder from [root] down to [file]'s parent a real folder (linked folders become folders of links). */
    @Synchronized fun materialize(root: File, file: File) {
        val chain = ArrayList<File>()
        var p = file.parentFile
        while (p != null && p != root) { chain += p; p = p.parentFile }
        if (p == null) return   // not inside the mirror
        for (dir in chain.asReversed()) {
            if (isLink(dir)) {
                val target = File(Files.readSymbolicLink(dir.toPath()).toString())
                Files.delete(dir.toPath())
                dir.mkdirs()
                target.listFiles()?.forEach { link(it, File(dir, it.name)) }
            } else if (!dir.exists()) dir.mkdirs()
        }
    }

    /** Lowercased mod-owned paths (Data-relative; "<root>/x" for the game's main folder) to their spelling in mods.json. */
    private fun ownedPaths(game: File): Pair<Map<String, String>, Map<String, List<String>>> {
        val index = File(game, "SkyrimAYN/mods.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: return emptyMap<String, String>() to emptyMap()
        val spelling = HashMap<String, String>()
        index.optJSONArray("mods")?.let { mods ->
            for (i in 0 until mods.length()) mods.getJSONObject(i).optJSONArray("files")?.let { f -> for (j in 0 until f.length()) f.getString(j).let { spelling[it.lowercase()] = it } }
        }
        val owners = HashMap<String, List<String>>()
        index.optJSONObject("owners")?.let { o -> o.keys().forEach { k -> owners[k] = o.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } } } }
        return spelling to owners
    }

    private fun place(base: File, rel: String) = if (rel.startsWith(ModManager.ROOT)) File(base, rel.removePrefix(ModManager.ROOT)) else File(base, "Data/$rel")

    /**
     * Brings the mirror up to date with the player's folder (new or removed game files) and, once, moves mods
     * installed by older builds out of the player's folder. Returns the mirror. Cheap when nothing changed.
     */
    @Synchronized fun sync(filesDir: File, game: File): File {
        val root = rootFor(filesDir, game)
        root.mkdirs()
        val (spelling, owners) = ownedPaths(game)
        val ownedTop = owners.filterValues { it.isNotEmpty() && it.last() != "_base" }.keys
        if (!migratedMarker(filesDir, game).isFile) migrate(filesDir, game, root, spelling, ownedTop)
        mirror(game, root, "", ownedTop)
        return root
    }

    private fun mirror(src: File, dst: File, rel: String, owned: Set<String>) {
        val existing = dst.listFiles()?.associateBy { it.name.lowercase() }.orEmpty()
        src.listFiles()?.forEach { child ->
            val r = if (rel.isEmpty()) child.name.lowercase() else "$rel/${child.name.lowercase()}"
            val key = if (rel.isEmpty() && !child.name.equals("Data", true)) ModManager.ROOT + r else r.removePrefix("data/").removePrefix("data")
            val at = existing[child.name.lowercase()] ?: File(dst, child.name)
            when {
                isLink(at) -> if (Files.readSymbolicLink(at.toPath()).toString() != child.path) link(child, at)
                !at.exists() -> link(child, at)
                at.isDirectory && child.isDirectory -> mirror(child, at, r, owned)
                // A real file the mods don't own (e.g. left by the engine's DRM step): back to a link.
                at.isFile && key !in owned -> link(child, at)
            }
        }
        // Links to files that are gone from the player's folder.
        dst.listFiles()?.forEach { if (isLink(it) && !it.exists()) Files.delete(it.toPath()) }
    }

    /** One-time move of mods from the player's folder into the mirror; the game files they replaced go back. */
    private fun migrate(filesDir: File, game: File, root: File, spelling: Map<String, String>, ownedTop: Set<String>) {
        val t0 = System.currentTimeMillis()
        var moved = 0
        var bytes = 0L
        val emptied = HashSet<File>()
        val todo = ownedTop.count { key -> place(game, spelling[key] ?: key).let { it.isFile && !isLink(it) } }
        if (todo > 0) moving.value = 0 to todo
        try {
        for (key in ownedTop) {
            val rel = spelling[key] ?: key
            val from = place(game, rel)
            if (!from.isFile || isLink(from)) continue
            val to = place(root, rel)
            materialize(root, to)
            if (isLink(to)) Files.delete(to.toPath())
            if (!(to.isFile && to.length() == from.length())) {
                val tmp = File(to.path + ".moving")
                from.copyTo(tmp, overwrite = true)
                tmp.setLastModified(from.lastModified())
                if (!tmp.renameTo(to)) { tmp.copyTo(to, overwrite = true); tmp.delete() }
            }
            bytes += from.length()
            from.delete()
            from.parentFile?.let(emptied::add)
            moved++
            if (todo > 0) moving.value = minOf(moved, todo) to todo
        }
        // Backups: the game's own files ("_base") go back into the player's folder; other mods' backups move here.
        val oldBackup = File(game, "SkyrimAYN/backup")
        val newBackup = backupDir(filesDir)
        oldBackup.listFiles()?.forEach { ownerDir ->
            ownerDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val bRel = f.relativeTo(ownerDir).invariantSeparatorsPath
                val rel = if (bRel.startsWith("_root_/")) ModManager.ROOT + bRel.removePrefix("_root_/") else bRel
                val dest = File(newBackup, "${ownerDir.name}/$bRel")
                dest.parentFile?.mkdirs()
                if (ownerDir.name == "_base") {
                    val original = place(game, rel)
                    if (!original.exists()) { original.parentFile?.mkdirs(); if (!f.renameTo(original)) { f.copyTo(original, overwrite = true); f.delete() } }
                    else f.delete()
                    link(original, dest)
                } else {
                    f.copyTo(dest, overwrite = true); f.delete()
                }
            }
        }
        // Tidy folders in the player's Data that only held mod files.
        val data = File(game, "Data")
        emptied.sortedByDescending { it.path.length }.forEach { d ->
            var cur: File? = d
            while (cur != null && cur != data && cur != game && cur.path.startsWith(game.path) && cur.list()?.isEmpty() == true) { cur.delete(); cur = cur.parentFile }
        }
        oldBackup.walkBottomUp().forEach { if (it.isDirectory && it.list()?.isEmpty() == true) it.delete() }
        migratedMarker(filesDir, game).apply { parentFile?.mkdirs() }.writeText("${System.currentTimeMillis()}")
        } finally { moving.value = null }
        Timber.i("SkyrimAYN: moved $moved mod files (${bytes / 1_000_000} MB) into the app's storage in ${System.currentTimeMillis() - t0} ms")
    }

    /** Before a launch: the mirror is used when mods are on. SkyrimAYN/.nofastmods turns it off (troubleshooting). */
    fun prepare(filesDir: File, game: File) {
        active = null
        if (LauncherSettings.get(LauncherSettings.mods) != "Enabled" || File(game, "SkyrimAYN/.nofastmods").isFile) return
        runCatching {
            val t0 = System.currentTimeMillis()
            active = sync(filesDir, game).path
            Timber.i("SkyrimAYN: mods folder ready in ${System.currentTimeMillis() - t0} ms at $active")
        }.onFailure { Timber.e(it, "SkyrimAYN: mods folder failed; launching from the game folder") }
    }
}

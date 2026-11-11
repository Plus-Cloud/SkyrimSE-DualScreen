package com.skyrimayn

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    suspend fun share(ctx: Context): Boolean {
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
        val body = buildString {
            appendLine("**What happened?**")
            appendLine()
            appendLine()
            appendLine("**Steps to reproduce**")
            appendLine()
            appendLine()
            appendLine("**Report file:** please attach `${lastSaved}` from the Thor (drag it into this box).")
            appendLine()
            appendLine("App ${Updater.currentVersion} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        }
        val url = android.net.Uri.parse("https://github.com/$REPO/issues/new").buildUpon()
            .appendQueryParameter("labels", "bug")
            .appendQueryParameter("body", body).build()
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }
}

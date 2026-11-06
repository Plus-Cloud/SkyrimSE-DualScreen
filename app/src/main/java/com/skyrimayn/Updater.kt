package com.skyrimayn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Updates from GitHub Releases: checks the latest release once a day, and on request downloads its
 * APK and hands it to Android's installer. Set [REPO] to the project's GitHub "owner/name".
 */
object Updater {
    /** GitHub repository that publishes SkyrimSE-DualScreen releases (tag "v1.2.3", one .apk asset). */
    const val REPO = "plus-cloud/SkyrimSE-DualScreen"

    val currentVersion: String get() = app.gamenative.BuildConfig.VERSION_NAME

    data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        /** Android needs "install unknown apps" allowed for us first; the install continues on return. */
        data class NeedsPermission(val release: Release) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    /** Checks GitHub (at most once a day unless [force]). */
    fun check(context: Context, force: Boolean = false) {
        val prefs = context.getSharedPreferences("updater", 0)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last_check", 0) < 24 * 3600_000L) {
            prefs.getString("cached", null)?.let { cached -> parse(JSONObject(cached))?.let { if (newer(it.version, currentVersion)) _state.value = State.Available(it) } }
            return
        }
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        scope.launch {
            _state.value = try {
                val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "SkyrimSE-DualScreen/$currentVersion").build()
                http.newCall(req).execute().use { r ->
                    if (r.code == 404) return@use State.UpToDate          // no releases yet
                    if (!r.isSuccessful) throw IOException("GitHub said ${r.code}")
                    val j = JSONObject(r.body!!.string())
                    prefs.edit().putLong("last_check", now).putString("cached", j.toString()).apply()
                    val rel = parse(j) ?: return@use State.UpToDate
                    if (newer(rel.version, currentVersion)) State.Available(rel) else State.UpToDate
                }
            } catch (e: Exception) {
                Timber.w(e, "SkyrimAYN: update check failed")
                State.Failed(e.message ?: "Couldn't check for updates")
            }
        }
    }

    private fun parse(j: JSONObject): Release? {
        val tag = j.optString("tag_name").removePrefix("v").ifBlank { return null }
        val assets = j.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", true)) {
                return Release(tag, j.optString("body").trim(), a.getString("browser_download_url"), a.optLong("size"))
            }
        }
        return null
    }

    /** For tests and previews. */
    internal fun injectForTest(s: State) { _state.value = s }

    /** Back from Android's "install unknown apps" screen: carry on with the update if it's now allowed. */
    fun resumeIfPermitted(context: Context) {
        val s = _state.value as? State.NeedsPermission ?: return
        if (context.packageManager.canRequestPackageInstalls()) install(context, s.release) else _state.value = State.Available(s.release)
    }

    /** Release notes as plain text (GitHub bodies are Markdown). */
    fun plainNotes(md: String): String = md.lines().joinToString("\n") { line ->
        line.trim().replace(Regex("""^#+\s*"""), "").replace(Regex("""^[-*]\s+"""), "• ")
            .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1").replace(Regex("""[*_`]+"""), "")
    }.replace(Regex("""\n{3,}"""), "\n\n").trim()

    /** True if [a] is a newer version than [b] ("1.10.0" > "1.9.2"). */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.split('.', '-', '+').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Downloads the release APK and opens Android's installer (asking for install permission first if needed). */
    fun install(context: Context, release: Release) {
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(release)
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(release, 0f)
        scope.launch {
            try {
                val dir = File(app.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }   // no stale APKs
                val apk = File(dir, "SkyrimSE-DualScreen-${release.version}.apk")
                http.newCall(Request.Builder().url(release.apkUrl).header("User-Agent", "SkyrimSE-DualScreen/$currentVersion").build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
                    val body = r.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                    var done = 0L
                    body.byteStream().use { input ->
                        apk.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                if (total > 0) _state.value = State.Downloading(release, done.toFloat() / total)
                            }
                        }
                    }
                    // A dropped connection must not reach the installer as a broken APK.
                    if (total > 0 && done != total) throw IOException("Download incomplete, check Wi-Fi and try again")
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apk)
                app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                _state.value = State.Available(release)
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "Update failed")
            }
        }
    }
}

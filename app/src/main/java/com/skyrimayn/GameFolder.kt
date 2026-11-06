package com.skyrimayn

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** What we found in a folder the user picked. */
data class GameCheck(
    val path: String,
    val ok: Boolean,
    val problem: String? = null,
    val exeVersion: String? = null,
    val pluginCount: Int = 0,
)

/** The user's Skyrim SE folder, chosen on first boot and saved. */
object GameFolder {
    private const val PREFS = "game"
    private const val KEY = "dir"

    private val _dir = MutableStateFlow<String?>(null)
    val dir: StateFlow<String?> = _dir
    private var ctx: Context? = null

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        _dir.value = ctx!!.getSharedPreferences(PREFS, 0).getString(KEY, null)
    }

    fun save(path: String) {
        _dir.value = path
        ctx?.getSharedPreferences(PREFS, 0)?.edit()?.putString(KEY, path)?.apply()
    }

    fun clear() {
        _dir.value = null
        ctx?.getSharedPreferences(PREFS, 0)?.edit()?.remove(KEY)?.apply()
    }

    /** Turns a folder-picker tree URI into a file path ("primary:Games/Skyrim" → /storage/emulated/0/Games/Skyrim). */
    fun pathFromTreeUri(uri: Uri): String? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val root = if (volume.equals("primary", true)) "/storage/emulated/0" else "/storage/$volume"
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    const val SUPPORTED_VERSION = "1.7.104"

    suspend fun check(path: String): GameCheck = withContext(Dispatchers.IO) {
        val dir = File(path)
        val exe = File(dir, "SkyrimSE.exe")
        val data = File(dir, "Data")
        when {
            !dir.isDirectory -> GameCheck(path, false, "That folder can't be opened.")
            !exe.isFile -> GameCheck(path, false, "No SkyrimSE.exe here. Pick the folder that contains SkyrimSE.exe and Data.")
            !File(data, "Skyrim.esm").isFile -> GameCheck(path, false, "SkyrimSE.exe found, but Data\\Skyrim.esm is missing.")
            else -> {
                val plugins = data.listFiles()?.count {
                    it.name.endsWith(".esm", true) || it.name.endsWith(".esp", true) || it.name.endsWith(".esl", true)
                } ?: 0
                val version = readExeVersion(exe)
                // Everything (the companion's plugin, SKSE support, mod compatibility) is built for 1.7.104.
                if (version != null && !version.startsWith("$SUPPORTED_VERSION."))
                    GameCheck(path, false, "This is Skyrim SE $version. SkyrimSE-DualScreen needs $SUPPORTED_VERSION, the current Steam version: " +
                        "update Skyrim in Steam on your PC, then copy the folder over again.")
                else GameCheck(path, true, exeVersion = version, pluginCount = plugins)
            }
        }
    }

    /** Reads the file version from the exe's VS_FIXEDFILEINFO (in the resources near the end of the file). */
    private fun readExeVersion(exe: File): String? = runCatching {
        RandomAccessFile(exe, "r").use { raf ->
            val window = minOf(raf.length(), 4L * 1024 * 1024)
            val buf = ByteArray(window.toInt())
            raf.seek(raf.length() - window)
            raf.readFully(buf)
            // Signature 0xFEEF04BD, little-endian.
            var i = buf.size - 52
            while (i >= 0) {
                if (buf[i] == 0xBD.toByte() && buf[i + 1] == 0x04.toByte() &&
                    buf[i + 2] == 0xEF.toByte() && buf[i + 3] == 0xFE.toByte()
                ) {
                    fun u32(o: Int) = (buf[o].toLong() and 0xFF) or ((buf[o + 1].toLong() and 0xFF) shl 8) or
                        ((buf[o + 2].toLong() and 0xFF) shl 16) or ((buf[o + 3].toLong() and 0xFF) shl 24)
                    val ms = u32(i + 8); val ls = u32(i + 12)
                    return@use "${ms ushr 16}.${ms and 0xFFFF}.${ls ushr 16}.${ls and 0xFFFF}"
                }
                i--
            }
            null
        }
    }.getOrNull()
}

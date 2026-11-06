package com.skyrimayn

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the header of Skyrim SE saves (.ess): character name, level, location, and the save's
 * screenshot, for the title screen's "Continue" card. Saves live in the game's prefix (and sync with
 * Steam Cloud there).
 */
object SaveGames {
    data class Save(
        val file: File, val name: String, val level: Int, val location: String, val race: String,
        val shot: Bitmap?, val modified: Long,
    )

    fun dir(ctx: Context) = File(ContainerRootLocator.root(ctx), ".wine/drive_c/users/xuser/Documents/My Games/Skyrim Special Edition/Saves")

    /**
     * Copies the newest saves (with their SKSE co-saves) into <game>/SkyrimAYN/save-backups/<time>/
     * before mods change. Keeps the last 5 backups; at most one every 10 minutes.
     */
    fun backup(ctx: Context, game: File) {
        runCatching {
            val root = File(game, "SkyrimAYN/save-backups").apply { mkdirs() }
            val existing = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
            if (existing.lastOrNull()?.let { System.currentTimeMillis() - it.lastModified() < 10 * 60_000 } == true) return
            val saves = dir(ctx).listFiles { f -> f.name.endsWith(".ess", true) }?.sortedByDescending { it.lastModified() }?.take(3).orEmpty()
            if (saves.isEmpty()) return
            val out = File(root, java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.US).format(java.util.Date())).apply { mkdirs() }
            for (s in saves) {
                s.copyTo(File(out, s.name), overwrite = true)
                File(s.parentFile, s.nameWithoutExtension + ".skse").takeIf { it.isFile }?.copyTo(File(out, s.nameWithoutExtension + ".skse"), overwrite = true)
            }
            (existing + out).sortedBy { it.name }.dropLast(5).forEach { it.deleteRecursively() }
        }
    }

    suspend fun latest(ctx: Context): Save? = withContext(Dispatchers.IO) {
        val f = dir(ctx).listFiles { x -> x.isFile && x.name.endsWith(".ess", true) }?.maxByOrNull { it.lastModified() } ?: return@withContext null
        runCatching { read(f) }.getOrNull()
    }

    private fun read(f: File): Save? = RandomAccessFile(f, "r").use { raf ->
        val magic = ByteArray(13); raf.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "TESV_SAVEGAME") return null
        val sizeBuf = ByteArray(4); raf.readFully(sizeBuf)
        val headerSize = ByteBuffer.wrap(sizeBuf).order(ByteOrder.LITTLE_ENDIAN).int
        if (headerSize !in 16..4096) return null
        val h = ByteArray(headerSize); raf.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        fun wstr(): String { val n = b.short.toInt() and 0xFFFF; val a = ByteArray(n); b.get(a); return String(a, Charsets.ISO_8859_1) }
        val version = b.int
        b.int                                   // save number
        val name = wstr()
        val level = b.int
        val location = wstr()
        wstr()                                  // play time
        val race = wstr()
        b.short; b.float; b.float; b.long       // sex, xp, xp needed, file time
        val w = b.int; val hgt = b.int
        val compression = if (version >= 12 && b.remaining() >= 2) b.short.toInt() else 0
        var shot: Bitmap? = null
        val bpp = if (version >= 11) 4 else 3
        if (w in 1..2048 && hgt in 1..2048) {
            val px = ByteArray(w * hgt * bpp)
            raf.readFully(px)
            val argb = IntArray(w * hgt) { i ->
                val o = i * bpp
                (0xFF shl 24) or ((px[o].toInt() and 0xFF) shl 16) or ((px[o + 1].toInt() and 0xFF) shl 8) or (px[o + 2].toInt() and 0xFF)
            }
            // A save made from outside the game's own menus can carry a blank (black) screenshot: show none then.
            val lit = argb.count { ((it shr 16) and 0xFF) + ((it shr 8) and 0xFF) + (it and 0xFF) > 24 }
            if (lit > argb.size / 50) shot = Bitmap.createBitmap(argb, w, hgt, Bitmap.Config.ARGB_8888)
        }
        if (compression < 0) return null
        Save(f, name, level, location, race.removeSuffix("Race").replace(Regex("(?<=[a-z])(?=[A-Z])"), " "), shot, f.lastModified())
    }
}

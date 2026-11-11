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

    /** Why the newest save may not load. */
    sealed interface Problem {
        val save: File
        data class Damaged(override val save: File) : Problem
        data class MissingMods(override val save: File, val mods: List<String>) : Problem
    }

    /** Checks the newest save against the plugins that will be active. Null when it's fine or already waved through. */
    suspend fun check(ctx: Context, active: Set<String>): Problem? = withContext(Dispatchers.IO) {
        val f = dir(ctx).listFiles { x -> x.isFile && x.name.endsWith(".ess", true) }?.maxByOrNull { it.lastModified() } ?: return@withContext null
        if (prefs(ctx).getString(KEY_ACK, null) == ackId(f)) return@withContext null
        val plugins = try { pluginsIn(f) ?: return@withContext null } catch (e: Exception) { return@withContext Problem.Damaged(f) }
        // Creation Club files and the game's resource pack load by themselves, whatever the mod list says.
        val missing = plugins.filter { p -> p.lowercase().let { it !in active && !it.startsWith("cc") && it != "_resourcepack.esl" } }
        if (missing.isEmpty()) null else Problem.MissingMods(f, missing)
    }

    /** The player chose to play anyway: don't ask again about this save. */
    fun acknowledge(ctx: Context, p: Problem) { prefs(ctx).edit().putString(KEY_ACK, ackId(p.save)).apply() }

    private const val KEY_ACK = "save_warning_ack"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", 0)
    private fun ackId(f: File) = "${f.name}:${f.lastModified()}"

    /** The plugins a save was made with. Null for a format we don't read; throws when the file is damaged. */
    private fun pluginsIn(f: File): List<String>? = RandomAccessFile(f, "r").use { raf ->
        val magic = ByteArray(13); raf.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "TESV_SAVEGAME") throw java.io.IOException("not a save")
        val head = ByteArray(4); raf.readFully(head)
        val headerSize = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).int
        if (headerSize !in 16..4096) throw java.io.IOException("bad header")
        val h = ByteBuffer.wrap(ByteArray(headerSize).also(raf::readFully)).order(ByteOrder.LITTLE_ENDIAN)
        fun skipString() { h.position(h.position() + (h.short.toInt() and 0xFFFF)) }
        val version = h.int
        if (version < 12) return null
        h.int; skipString(); h.int; skipString(); skipString(); skipString()    // number, name, level, location, time, race
        h.short; h.float; h.float; h.long                                       // sex, xp, xp needed, file time
        val width = h.int; val height = h.int
        val compression = h.short.toInt()
        if (compression != 1 && compression != 2) return null
        val bodyAt = 17L + headerSize + width.toLong() * height * 4
        raf.seek(bodyAt)
        val sizes = ByteBuffer.wrap(ByteArray(8).also(raf::readFully)).order(ByteOrder.LITTLE_ENDIAN)
        sizes.int
        val compressed = sizes.int
        if (raf.length() != bodyAt + 8 + compressed) throw java.io.IOException("cut short")
        val input = ByteArray(minOf(compressed, 1 shl 18)).also(raf::readFully)
        val out = ByteArray(1 shl 17)
        if (compression == 2) lz4(input, out) else java.util.zip.Inflater().run { setInput(input); inflate(out); end() }
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        fun string(): String { val n = b.short.toInt() and 0xFFFF; return ByteArray(n).also(b::get).toString(Charsets.ISO_8859_1) }
        val formVersion = b.get().toInt() and 0xFF
        b.int                                                                   // plugin info size
        val plugins = List(b.get().toInt() and 0xFF) { string() }
        plugins + if (formVersion >= 78) List(b.short.toInt() and 0xFFFF) { string() } else emptyList()
    }

    /** LZ4 block decoder that stops once [out] is full: only the start of a save is needed. */
    private fun lz4(src: ByteArray, out: ByteArray) {
        var i = 0; var o = 0
        fun extra(first: Int): Int {
            var n = first
            if (first == 15) while (true) { val b = src[i++].toInt() and 0xFF; n += b; if (b != 255) break }
            return n
        }
        while (i < src.size && o < out.size) {
            val token = src[i++].toInt() and 0xFF
            val literals = extra(token ushr 4)
            val copy = minOf(literals, out.size - o, src.size - i)
            System.arraycopy(src, i, out, o, copy)
            i += literals; o += copy
            if (i >= src.size || o >= out.size) return
            val offset = (src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8)
            i += 2
            if (offset == 0 || offset > o) throw java.io.IOException("bad LZ4 offset")
            val length = minOf(extra(token and 15) + 4, out.size - o)
            for (k in 0 until length) { out[o] = out[o - offset]; o++ }
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

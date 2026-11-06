package com.skyrimayn

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The game's own menu sounds, read from the player's "Skyrim - Sounds.bsa" (nothing is shipped):
 * focus, OK and cancel, played on the companion's taps. Silent if they can't be found.
 */
object GameSounds {
    enum class Cue(vararg val names: String) {
        FOCUS("ui_menu_focus", "ui_focus", "ui_menu_prevnext"),
        OK("ui_menu_ok", "ui_select", "ui_menu_select"),
        CANCEL("ui_menu_cancel", "ui_cancel"),
        TAB("ui_menu_prevnext", "ui_menu_tab", "ui_menu_focus"),
    }

    private var pool: SoundPool? = null
    private val ids = HashMap<Cue, Int>()
    @Volatile private var loading = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (pool != null || loading) return
        val game = GameFolder.dir.value ?: return
        loading = true
        val app = context.applicationContext
        scope.launch {
            try {
                val cache = File(app.cacheDir, "game_sounds").apply { mkdirs() }
                val wanted = Cue.entries.flatMap { it.names.toList() }.toSet()
                if (Cue.entries.none { c -> c.names.any { File(cache, "$it.wav").isFile } }) {
                    extract(File(game, "Data/Skyrim - Sounds.bsa"), wanted, cache)
                }
                val p = SoundPool.Builder().setMaxStreams(3)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .build()
                for (cue in Cue.entries) {
                    val f = cue.names.map { File(cache, "$it.wav") }.firstOrNull { it.isFile && it.length() > 44 } ?: continue
                    ids[cue] = p.load(f.path, 1)
                }
                pool = p
                Timber.i("SkyrimAYN: menu sounds ${ids.keys}")
            } catch (e: Exception) {
                Timber.w(e, "SkyrimAYN: menu sounds unavailable")
            } finally {
                loading = false
            }
        }
    }

    fun play(cue: Cue) {
        if (LauncherSettings.get(LauncherSettings.menuSounds) != "On") return
        val id = ids[cue] ?: return
        pool?.play(id, 0.55f, 0.55f, 1, 0, 1f)
    }

    /** Pulls the named .wav files out of a Skyrim SE (v105) BSA. Compressed entries are skipped. */
    private fun extract(bsa: File, wanted: Set<String>, out: File) {
        if (!bsa.isFile) return
        RandomAccessFile(bsa, "r").use { raf ->
            fun buf(n: Int): ByteBuffer { val b = ByteArray(n); raf.readFully(b); return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN) }
            val h = buf(36)
            if (h.getInt(0) != 0x00415342 || h.getInt(4) != 105) return            // "BSA\0", Skyrim SE
            val archiveFlags = h.getInt(12)
            val folders = h.getInt(16)
            val files = h.getInt(20)
            val totalFileNames = h.getInt(28)
            val compressedDefault = archiveFlags and 0x4 != 0
            val embedNames = archiveFlags and 0x100 != 0
            val counts = IntArray(folders)
            val fr = buf(folders * 24)
            for (i in 0 until folders) counts[i] = fr.getInt(i * 24 + 8)
            data class Entry(val folder: String, val size: Int, val offset: Long)
            val entries = ArrayList<Entry>(files)
            for (i in 0 until folders) {
                var folder = ""
                if (archiveFlags and 0x1 != 0) {
                    val len = raf.readUnsignedByte()
                    val nb = ByteArray(len); raf.readFully(nb)
                    folder = String(nb, 0, (len - 1).coerceAtLeast(0), Charsets.ISO_8859_1)
                }
                val rec = buf(counts[i] * 16)
                for (k in 0 until counts[i]) entries += Entry(folder, rec.getInt(k * 16 + 8), rec.getInt(k * 16 + 12).toLong() and 0xFFFFFFFFL)
            }
            if (archiveFlags and 0x2 == 0) return
            val names = ByteArray(totalFileNames); raf.readFully(names)
            var p = 0
            for (e in entries) {
                var end = p; while (end < names.size && names[end].toInt() != 0) end++
                val name = String(names, p, end - p, Charsets.ISO_8859_1).lowercase()
                p = end + 1
                val base = name.removeSuffix(".wav")
                if (!name.endsWith(".wav") || base !in wanted || !e.folder.contains("\\ui")) continue
                val compressed = compressedDefault xor (e.size and 0x40000000 != 0)
                if (compressed) continue
                var size = e.size and 0x3FFFFFFF
                raf.seek(e.offset)
                if (embedNames) { val l = raf.readUnsignedByte(); raf.skipBytes(l); size -= l + 1 }
                if (size <= 0 || size > 4_000_000) continue
                val data = ByteArray(size); raf.readFully(data)
                File(out, "$base.wav").writeBytes(data)
            }
        }
    }
}

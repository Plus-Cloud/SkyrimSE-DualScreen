package com.skyrimayn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * The game's own compass/map icons, drawn from the player's copy of Skyrim (Interface.bsa →
 * hudmenu.gfx, sprite "Compass Marker"), once, and cached as a sprite sheet. Nothing from the game
 * ships with the app. Frames: 1 quest, 2 quest door, 3 custom marker, 4 enemy, 4 + type = a place,
 * 71 + type = a place not yet discovered (type = the map marker type).
 */
object GameIcons {
    const val CELL = 96
    private const val COLS = 16
    private const val VERSION = 2   // 2: real gradient fills (the enemy dot is a radial gradient)

    class Sheet(val image: ImageBitmap, val frames: Int)

    private val _sheet = MutableStateFlow<Sheet?>(null)
    val sheet: StateFlow<Sheet?> = _sheet
    private var started = false

    fun locationFrame(type: Int, discovered: Boolean) = if (discovered) 4 + type else 71 + type
    const val QUEST = 1
    const val QUEST_DOOR = 2
    const val PLAYER_SET = 3

    fun load(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { _sheet.value = loadOrBuild(app) }.onFailure { Timber.w(it, "SkyrimAYN: game icons unavailable") }
        }
    }

    private fun loadOrBuild(context: Context): Sheet? {
        val dir = File(context.filesDir, "skyrimayn/icons").apply { mkdirs() }
        val png = File(dir, "compass_v$VERSION.png")
        val meta = File(dir, "compass_v$VERSION.txt")
        if (png.isFile && meta.isFile) {
            BitmapFactory.decodeFile(png.path)?.let { return Sheet(it.asImageBitmap(), meta.readText().trim().toInt()) }
        }
        val game = GameFolder.dir.value ?: return null
        val bsa = File(game, "Data/Skyrim - Interface.bsa")
        if (!bsa.isFile) return null
        val gfx = Bsa.read(bsa, "interface\\exported\\hudmenu.gfx") ?: return null
        val (bmp, frames) = Swf(swfBytes(gfx)).renderSprite("Compass Marker", CELL, COLS)
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        meta.writeText(frames.toString())
        Timber.i("SkyrimAYN: rendered $frames game icons")
        return Sheet(bmp.asImageBitmap(), frames)
    }

    /** For tests: render the icon sheet straight from an Interface.bsa. */
    internal fun renderFromBsa(bsa: File): Pair<Bitmap, Int> {
        val gfx = Bsa.read(bsa, "interface\\exported\\hudmenu.gfx") ?: error("hudmenu.gfx not found")
        return Swf(swfBytes(gfx)).renderSprite("Compass Marker", CELL, COLS)
    }

    /** Source rectangle of a frame (1-based) in the sheet. */
    fun cellOf(frame: Int): Pair<Int, Int> = ((frame - 1) % COLS) * CELL to ((frame - 1) / COLS) * CELL

    /** GFX/CFX (Scaleform) → plain SWF bytes. */
    private fun swfBytes(b: ByteArray): ByteArray {
        val sig = String(b, 0, 3, Charsets.ISO_8859_1)
        if (sig != "CFX" && sig != "CWS") return b
        val len = ByteBuffer.wrap(b, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val out = ByteArray(len)
        System.arraycopy(b, 0, out, 0, 8)
        Inflater().run { setInput(b, 8, b.size - 8); inflate(out, 8, len - 8); end() }
        return out
    }
}

/** Minimal Skyrim SE archive (BSA v105) reader: find one file, LZ4-frame decompress it. */
internal object Bsa {
    fun read(file: File, path: String): ByteArray? = RandomAccessFile(file, "r").use { raf ->
        fun buf(pos: Long, n: Int): ByteBuffer { val a = ByteArray(n); raf.seek(pos); raf.readFully(a); return ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN) }
        val h = buf(0, 36)
        val folderOffset = h.getInt(8).toLong(); val archiveFlags = h.getInt(12)
        val folders = h.getInt(16); val totalFileNames = h.getInt(28)
        val compressedDefault = archiveFlags and 4 != 0
        val embedNames = archiveFlags and 0x100 != 0
        val fr = buf(folderOffset, folders * 24)
        val counts = IntArray(folders) { fr.getInt(it * 24 + 8) }
        // File record blocks: [bstring folder name][16-byte records]…, then the file-name table.
        var pos = folderOffset + folders * 24L
        data class Rec(val folder: String, val size: Int, val offset: Int)
        val recs = ArrayList<Rec>()
        for (i in 0 until folders) {
            val n = buf(pos, 1).get(0).toInt() and 0xFF
            val name = String(buf(pos + 1, n).array(), 0, n - 1, Charsets.ISO_8859_1)
            pos += 1 + n
            val rb = buf(pos, counts[i] * 16)
            for (k in 0 until counts[i]) recs += Rec(name, rb.getInt(k * 16 + 8), rb.getInt(k * 16 + 12))
            pos += counts[i] * 16L
        }
        val namesLen = (raf.length() - pos).coerceAtMost(4L shl 20).toInt()
        val names = String(buf(pos, namesLen).array(), Charsets.ISO_8859_1).split('\u0000')
        val want = path.lowercase()
        for ((i, r) in recs.withIndex()) {
            if (i >= totalFileNames || "${r.folder}\\${names[i]}".lowercase() != want) continue
            val compressed = compressedDefault xor (r.size and 0x40000000 != 0)
            var size = r.size and 0x3FFFFFFF
            var off = r.offset.toLong() and 0xFFFFFFFFL
            if (embedNames) { val n = buf(off, 1).get(0).toInt() and 0xFF; off += 1 + n; size -= 1 + n }
            val data = buf(off, size).array()
            if (!compressed) return data
            val outLen = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            return Lz4.frame(data, 4, outLen)
        }
        null
    }
}

/** LZ4 frame decompression (what Skyrim SE archives use), enough for Bethesda's archives. */
internal object Lz4 {
    fun frame(src: ByteArray, start: Int, outLen: Int): ByteArray {
        val out = ByteArray(outLen)
        var p = start
        val magic = ByteBuffer.wrap(src, p, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(magic == 0x184D2204) { "not an LZ4 frame" }
        p += 4
        val flg = src[p].toInt() and 0xFF
        p += 2                                   // FLG, BD
        if (flg and 0x08 != 0) p += 8            // content size
        if (flg and 0x01 != 0) p += 4            // dictionary id
        p += 1                                   // header checksum
        val blockChecksum = flg and 0x10 != 0
        var o = 0
        while (p + 4 <= src.size) {
            val raw = ByteBuffer.wrap(src, p, 4).order(ByteOrder.LITTLE_ENDIAN).int; p += 4
            if (raw == 0) break
            val size = raw and 0x7FFFFFFF
            if (raw and 0x80000000.toInt() != 0) { System.arraycopy(src, p, out, o, size); o += size }
            else o = block(src, p, p + size, out, o)
            p += size
            if (blockChecksum) p += 4
        }
        return out
    }

    private fun block(src: ByteArray, start: Int, end: Int, out: ByteArray, outStart: Int): Int {
        var p = start; var o = outStart
        while (p < end) {
            val token = src[p++].toInt() and 0xFF
            var lit = token ushr 4
            if (lit == 15) { while (true) { val b = src[p++].toInt() and 0xFF; lit += b; if (b != 255) break } }
            System.arraycopy(src, p, out, o, lit); p += lit; o += lit
            if (p >= end) break
            val offset = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8); p += 2
            var len = token and 15
            if (len == 15) { while (true) { val b = src[p++].toInt() and 0xFF; len += b; if (b != 255) break } }
            len += 4
            var m = o - offset
            repeat(len) { out[o++] = out[m++] }
        }
        return o
    }
}

/** Just enough of SWF to draw vector sprites: shapes (solid fills, lines), sprites, matrices. */
internal class Swf(private val d: ByteArray) {
    private class Bits(val d: ByteArray, start: Int) {
        var p = start * 8
        fun u(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or ((d[p ushr 3].toInt() ushr (7 - (p and 7))) and 1); p++ }; return v }
        fun s(n: Int): Int { if (n == 0) return 0; val v = u(n); return if (v and (1 shl (n - 1)) != 0) v - (1 shl n) else v }
        fun align() { p = (p + 7) and 7.inv() }
        val byte get() = p ushr 3
        fun u8(): Int { align(); val v = d[byte].toInt() and 0xFF; p += 8; return v }
        fun u16(): Int { align(); val v = (d[byte].toInt() and 0xFF) or ((d[byte + 1].toInt() and 0xFF) shl 8); p += 16; return v }
    }

    private data class Tag(val type: Int, val pos: Int, val len: Int)
    private fun tags(start: Int, end: Int): List<Tag> {
        val out = ArrayList<Tag>(); var o = start
        while (o < end) {
            val h = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8); o += 2
            val t = h ushr 6; var ln = h and 63
            if (ln == 63) { ln = ByteBuffer.wrap(d, o, 4).order(ByteOrder.LITTLE_ENDIAN).int; o += 4 }
            out += Tag(t, o, ln); o += ln
            if (t == 0) break
        }
        return out
    }

    private val chars = HashMap<Int, Tag>()
    private val exports = HashMap<String, Int>()
    init {
        val nbits = (d[8].toInt() and 0xFF) ushr 3
        val start = 8 + (5 + nbits * 4 + 7) / 8 + 4
        for (t in tags(start, d.size)) {
            if (t.type == 56) {
                var q = t.pos
                val n = u16(q); q += 2
                repeat(n) {
                    val id = u16(q); q += 2
                    var e = q; while (d[e].toInt() != 0) e++
                    exports[String(d, q, e - q, Charsets.ISO_8859_1)] = id; q = e + 1
                }
            }
            if (t.type in setOf(2, 22, 32, 83, 39)) chars[u16(t.pos)] = t
        }
    }
    private fun u16(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

    private fun readMatrix(b: Bits): Matrix {
        b.align()
        var sx = 1f; var sy = 1f; var r0 = 0f; var r1 = 0f
        if (b.u(1) == 1) { val n = b.u(5); sx = b.s(n) / 65536f; sy = b.s(n) / 65536f }
        if (b.u(1) == 1) { val n = b.u(5); r0 = b.s(n) / 65536f; r1 = b.s(n) / 65536f }
        val n = b.u(5); val tx = b.s(n).toFloat(); val ty = b.s(n).toFloat()
        b.align()
        return Matrix().apply { setValues(floatArrayOf(sx, r1, tx, r0, sy, ty, 0f, 0f, 1f)) }
    }

    private fun skipRect(b: Bits) { b.align(); val n = b.u(5); repeat(4) { b.u(n) }; b.align() }

    private fun color(b: Bits, alpha: Boolean): Int {
        val r = b.u8(); val g = b.u8(); val bl = b.u8(); val a = if (alpha) b.u8() else 255
        return (a shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** A fill: a solid colour, or a gradient in SWF's gradient square (-16384..16384 twips) placed by [matrix]. */
    private class Fill(val color: Int, val radial: Boolean = false, val matrix: Matrix? = null,
                       val colors: IntArray? = null, val stops: FloatArray? = null)

    private fun readStyles(b: Bits, shapeType: Int, fills: MutableList<Fill>, lines: MutableList<Pair<Float, Int>>) {
        var n = b.u8(); if (n == 0xFF && shapeType >= 22) n = b.u16()
        repeat(n) { fills += readFill(b, shapeType) }
        var m = b.u8(); if (m == 0xFF) m = b.u16()
        repeat(m) {
            val w = b.u16().toFloat()
            if (shapeType == 83) {
                val f1 = b.u8(); b.u8()
                if ((f1 ushr 4) and 3 == 2) b.u16()
                if (f1 and 8 != 0) lines += w to readFill(b, shapeType).color else lines += w to color(b, true)
            } else lines += w to color(b, shapeType >= 32)
        }
    }

    private fun readFill(b: Bits, shapeType: Int): Fill {
        val ft = b.u8()
        return when (ft) {
            0 -> Fill(color(b, shapeType >= 32))
            0x10, 0x12, 0x13 -> {
                val gm = readMatrix(b)
                val cnt = b.u8() and 15
                val stops = FloatArray(cnt)
                val cols = IntArray(cnt) { i -> stops[i] = b.u8() / 255f; color(b, shapeType >= 32) }
                if (ft == 0x13) b.u16()
                if (cols.size < 2) Fill(cols.firstOrNull() ?: 0xFFC8C8C8.toInt())
                else Fill(cols[cols.size / 2], radial = ft != 0x10, matrix = gm, colors = cols, stops = stops)
            }
            0x40, 0x41, 0x42, 0x43 -> { b.u16(); readMatrix(b); Fill(0xFFC8C8C8.toInt()) }
            else -> error("fill type $ft")
        }
    }

    private class Edge(val f0: Int, val f1: Int, val line: Int, val pts: FloatArray)

    /** Draws one character (shape or sprite's first frame) with [m] onto [c]. */
    private fun draw(id: Int, m: Matrix, c: Canvas) {
        val t = chars[id] ?: return
        if (t.type == 39) {
            val display = sortedMapOf<Int, Pair<Int?, Matrix>>()
            for (tt in tags(t.pos + 4, t.pos + t.len)) {
                if (tt.type == 26) {
                    val b = Bits(d, tt.pos)
                    val flags = b.u8(); val depth = b.u16()
                    var ch: Int? = null; var mat: Matrix? = null
                    if (flags and 2 != 0) ch = b.u16()
                    if (flags and 4 != 0) mat = readMatrix(b)
                    val prev = display[depth]
                    display[depth] = (ch ?: prev?.first) to (mat ?: prev?.second ?: Matrix())
                }
                if (tt.type == 1) break
            }
            for ((_, v) in display) v.first?.let { ch -> draw(ch, Matrix(m).apply { preConcat(v.second) }, c) }
            return
        }
        val b = Bits(d, t.pos + 2)
        skipRect(b)
        if (t.type == 83) { skipRect(b); b.u8() }
        val fills = ArrayList<Fill>(); val lines = ArrayList<Pair<Float, Int>>()
        readStyles(b, t.type, fills, lines)
        b.align(); var nf = b.u(4); var nl = b.u(4)
        var x = 0f; var y = 0f; var f0 = 0; var f1 = 0; var ls = 0
        var base = 0; var lbase = 0
        val edges = ArrayList<Edge>()
        while (true) {
            if (b.u(1) == 0) {
                val flags = b.u(5)
                if (flags == 0) break
                if (flags and 1 != 0) { val n = b.u(5); x = b.s(n).toFloat(); y = b.s(n).toFloat() }
                if (flags and 2 != 0) f0 = b.u(nf).let { if (it == 0) 0 else it + base }
                if (flags and 4 != 0) f1 = b.u(nf).let { if (it == 0) 0 else it + base }
                if (flags and 8 != 0) ls = b.u(nl).let { if (it == 0) 0 else it + lbase }
                if (flags and 16 != 0) {
                    base = fills.size; lbase = lines.size
                    readStyles(b, t.type, fills, lines)
                    b.align(); nf = b.u(4); nl = b.u(4)
                }
                continue
            }
            val pts: FloatArray
            if (b.u(1) == 1) {
                val n = b.u(4) + 2
                var dx = 0; var dy = 0
                if (b.u(1) == 1) { dx = b.s(n); dy = b.s(n) } else if (b.u(1) == 1) dy = b.s(n) else dx = b.s(n)
                pts = floatArrayOf(x, y, x + dx, y + dy)
            } else {
                val n = b.u(4) + 2
                val cx = x + b.s(n); val cy = y + b.s(n); val ax = cx + b.s(n); val ay = cy + b.s(n)
                pts = FloatArray(2 + 8 * 2)
                pts[0] = x; pts[1] = y
                for (k in 1..8) {
                    val tt = k / 8f
                    pts[k * 2] = (1 - tt) * (1 - tt) * x + 2 * (1 - tt) * tt * cx + tt * tt * ax
                    pts[k * 2 + 1] = (1 - tt) * (1 - tt) * y + 2 * (1 - tt) * tt * cy + tt * tt * ay
                }
            }
            edges += Edge(f0, f1, ls, pts)
            x = pts[pts.size - 2]; y = pts[pts.size - 1]
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((i, fill) in fills.withIndex()) {
            val style = i + 1
            val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
            val segs = ArrayList<FloatArray>()
            for (e in edges) {
                if (e.f1 == style) segs += e.pts
                if (e.f0 == style) segs += reversed(e.pts)
            }
            contours(segs, path)
            path.transform(m)
            paint.style = Paint.Style.FILL; paint.color = fill.color; paint.shader = null
            if (fill.colors != null && fill.stops != null && fill.matrix != null) {
                val sh = if (fill.radial) android.graphics.RadialGradient(0f, 0f, 16384f, fill.colors, fill.stops, android.graphics.Shader.TileMode.CLAMP)
                    else android.graphics.LinearGradient(-16384f, 0f, 16384f, 0f, fill.colors, fill.stops, android.graphics.Shader.TileMode.CLAMP)
                sh.setLocalMatrix(Matrix(fill.matrix).apply { postConcat(m) })
                paint.color = 0xFFFFFFFF.toInt(); paint.shader = sh
            }
            c.drawPath(path, paint)
            paint.shader = null
        }
        for ((i, l) in lines.withIndex()) {
            val style = i + 1
            val path = Path()
            for (e in edges) if (e.line == style) {
                path.moveTo(e.pts[0], e.pts[1]); var k = 2; while (k < e.pts.size) { path.lineTo(e.pts[k], e.pts[k + 1]); k += 2 }
            }
            path.transform(m)
            val v = FloatArray(9); m.getValues(v)
            paint.style = Paint.Style.STROKE; paint.color = l.second; paint.strokeWidth = (l.first * kotlin.math.abs(v[0])).coerceAtLeast(1f)
            c.drawPath(path, paint)
        }
    }

    private fun reversed(pts: FloatArray) = FloatArray(pts.size).also { r ->
        var k = 0; while (k < pts.size) { r[k] = pts[pts.size - 2 - k]; r[k + 1] = pts[pts.size - 1 - k]; k += 2 }
    }

    /** Edges of one fill come unordered: chain them into closed contours (even-odd then gives holes) and add to [path]. */
    private fun contours(segs: MutableList<FloatArray>, path: Path) {
        while (segs.isNotEmpty()) {
            var cur = segs.removeAt(0).toMutableList()
            var changed = true
            fun closed() = kotlin.math.abs(cur[0] - cur[cur.size - 2]) <= 1f && kotlin.math.abs(cur[1] - cur[cur.size - 1]) <= 1f
            while (changed && !closed()) {
                changed = false
                val i = segs.indexOfFirst { kotlin.math.abs(it[0] - cur[cur.size - 2]) <= 1f && kotlin.math.abs(it[1] - cur[cur.size - 1]) <= 1f }
                if (i >= 0) { val s = segs.removeAt(i); for (k in 2 until s.size) cur.add(s[k]); changed = true }
            }
            path.moveTo(cur[0], cur[1]); var k = 2; while (k < cur.size) { path.lineTo(cur[k], cur[k + 1]); k += 2 }; path.close()
        }
    }

    /** Renders every frame of an exported sprite into a sheet of [cell]-pixel squares, each fitted and centred. */
    fun renderSprite(name: String, cell: Int, cols: Int): Pair<Bitmap, Int> {
        val id = exports[name] ?: error("no $name")
        val t = chars[id]!!
        val frames = ArrayList<Int?>(); var cur: Int? = null
        for (tt in tags(t.pos + 4, t.pos + t.len)) {
            if (tt.type == 26) { val flags = d[tt.pos].toInt() and 0xFF; if (flags and 2 != 0) cur = u16(tt.pos + 3) }
            if (tt.type == 1) frames += cur
        }
        val rows = (frames.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        for ((i, ch) in frames.withIndex()) {
            if (ch == null) continue
            // Measure: draw once into a big scratch bitmap to find the bounds, then draw fitted into the cell.
            val probe = Bitmap.createBitmap(512, 512, Bitmap.Config.ALPHA_8)
            val pc = Canvas(probe)
            val center = Matrix().apply { setScale(0.05f, 0.05f); postTranslate(256f, 256f) }   // twips → px, centred
            runCatching { drawShapes(ch, center, pc) }
            val bounds = alphaBounds(probe) ?: continue
            val scale = (cell - 8f) / maxOf(bounds.width(), bounds.height())
            val m = Matrix(center).apply {
                postTranslate(-bounds.centerX(), -bounds.centerY())
                postScale(scale, scale)
                postTranslate((i % cols) * cell + cell / 2f, (i / cols) * cell + cell / 2f)
            }
            c.save(); c.clipRect((i % cols) * cell, (i / cols) * cell, (i % cols + 1) * cell, (i / cols + 1) * cell)
            runCatching { drawShapes(ch, m, c) }
            c.restore()
            probe.recycle()
        }
        return sheet to frames.size
    }

    private fun drawShapes(id: Int, m: Matrix, c: Canvas) = draw(id, m, c)

    private fun alphaBounds(b: Bitmap): android.graphics.RectF? {
        val w = b.width; val h = b.height
        val px = java.nio.ByteBuffer.allocate(w * h); b.copyPixelsToBuffer(px)
        val a = px.array()
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (a[y * w + x].toInt() != 0) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        return if (x1 < 0) null else android.graphics.RectF(x0.toFloat(), y0.toFloat(), (x1 + 1).toFloat(), (y1 + 1).toFloat())
    }
}

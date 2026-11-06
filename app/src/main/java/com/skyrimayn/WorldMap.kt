package com.skyrimayn

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ================================================================ map image generation

/**
 * Builds a shaded-relief map of a worldspace from the terrain (LAND heights) in the player's own
 * master file, once, and caches it. Nothing from the game is shipped with the app.
 */
object WorldMaps {
    /** Image pixels per exterior cell: the game's full terrain resolution (33 samples per 4096 units). */
    const val PX = 32
    private const val SAMPLES = PX + 1
    /** Map units per cell. Zoom and positions are in these units; the image underneath is [PX]/[UNIT] times sharper. */
    const val UNIT = 16

    data class Info(val minCellX: Int, val maxCellY: Int, val cellsW: Int, val cellsH: Int) {
        val width get() = cellsW * UNIT
        val height get() = cellsH * UNIT
        fun toMap(x: Float, y: Float) = Offset((x / 4096f - minCellX) * UNIT, (maxCellY + 1 - y / 4096f) * UNIT)
    }

    sealed interface State {
        data object Unavailable : State
        data class Building(val progress: Float) : State
        data class Ready(val image: ImageBitmap, val info: Info) : State
        data class Failed(val reason: String) : State
    }

    private data class Source(val key: String, val plugin: String, val edid: String)

    private fun sourceFor(worldspaceId: Int): Source? = when {
        worldspaceId == 0x3C -> Source("tamriel", "Skyrim.esm", "Tamriel")
        worldspaceId and 0xFFFFFF == 0x000800 && worldspaceId ushr 24 != 0 -> Source("solstheim", "Dragonborn.esm", "DLC2SolstheimWorld")
        else -> null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = HashMap<String, MutableStateFlow<State>>()

    /** For tests and previews: use this map for every worldspace. */
    internal fun injectForTest(state: State) { synchronized(states) { testState = MutableStateFlow(state) } }
    private var testState: MutableStateFlow<State>? = null

    fun state(context: Context, worldspaceId: Int): StateFlow<State> {
        testState?.let { return it }
        val src = sourceFor(worldspaceId) ?: return MutableStateFlow(State.Unavailable)
        synchronized(states) {
            states[src.key]?.let { return it }
            val flow = MutableStateFlow<State>(State.Building(0f))
            states[src.key] = flow
            scope.launch { flow.value = load(context.applicationContext, src) { flow.value = State.Building(it) } }
            return flow
        }
    }

    private fun load(context: Context, src: Source, progress: (Float) -> Unit): State {
        val dir = File(context.filesDir, "skyrimayn/maps").apply { mkdirs() }
        val png = File(dir, "${src.key}.png")
        val meta = File(dir, "${src.key}.json")
        runCatching {
            if (png.exists() && meta.exists()) {
                val j = JSONObject(meta.readText())
                if (j.optInt("v") == VERSION) {
                    val info = Info(j.getInt("minCellX"), j.getInt("maxCellY"), j.getInt("cellsW"), j.getInt("cellsH"))
                    BitmapFactory.decodeFile(png.absolutePath)?.let { return State.Ready(it.asImageBitmap(), info) }
                }
            }
        }
        val game = GameFolder.dir.value ?: return State.Failed("No game folder")
        val esm = File(game, "Data/${src.plugin}")
        if (!esm.exists()) return State.Failed("${src.plugin} not found")
        return try {
            val (bmp, info) = build(esm, src.edid, progress)
            png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            meta.writeText(JSONObject().put("v", VERSION).put("minCellX", info.minCellX).put("maxCellY", info.maxCellY)
                .put("cellsW", info.cellsW).put("cellsH", info.cellsH).toString())
            State.Ready(bmp.asImageBitmap(), info)
        } catch (e: Throwable) {
            State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private const val VERSION = 8

    // ---------------------------------------------------------------- ESM terrain reader

    private class Cell(val x: Int, val y: Int, val water: Float?)

    private class Terrain {
        var defaultLand = -2048f
        var defaultWater = 0f
        var bounds: IntArray? = null                       // nwX, nwY, seX, seY from MNAM
        val heights = HashMap<Long, FloatArray>()          // cell -> SAMPLES² heights (row 0 = south)
        val water = HashMap<Long, Float>()
        val ground = HashMap<Long, IntArray>()             // cell -> 33² ground colours from the game's textures
        val ltex = HashMap<Int, Int>()                     // LTEX form id -> map colour
        val trailTex = HashMap<Int, Float>()               // LTEX id -> trail strength (paths 1, bare dirt 0.45)
        val trails = HashMap<Long, ByteArray>()            // cell -> 33² trail coverage 0..255
        val roadBase = HashMap<Int, Float>()               // road piece STAT -> length in units
        val roads = ArrayList<FloatArray>()                // x, y, heading, length
    }

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    /** Windowed reader: sequential walks over a 250 MB file without a syscall per record. */
    private class Reader(file: File) : AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        val length = raf.length()
        private val buf = ByteArray(8 shl 20)
        private var start = 0L
        private var len = 0
        fun at(pos: Long, n: Int): ByteBuffer {
            if (n > buf.size) {
                val big = ByteArray(n); raf.seek(pos); raf.readFully(big)
                return ByteBuffer.wrap(big).order(ByteOrder.LITTLE_ENDIAN)
            }
            if (pos < start || pos + n > start + len) {
                raf.seek(pos)
                start = pos
                len = raf.read(buf, 0, buf.size).coerceAtLeast(0)
                if (len < n) throw IllegalStateException("Unexpected end of file")
            }
            return ByteBuffer.wrap(buf, (pos - start).toInt(), n).slice().order(ByteOrder.LITTLE_ENDIAN)
        }
        override fun close() = raf.close()
    }

    private fun tag(b: ByteBuffer, o: Int) = String(CharArray(4) { (b.get(o + it).toInt() and 0xFF).toChar() })

    /** For tests: builds the map image from an ESM file without caching. */
    internal fun buildForTest(esm: File, edid: String): Pair<Bitmap, Info> = build(esm, edid) {}

    private fun build(esm: File, edid: String, progress: (Float) -> Unit): Pair<Bitmap, Info> {
        val t = Terrain()
        Reader(esm).use { r ->
            // Read values out of the reader's window right away: the next read reuses the buffer.
            val first = 24L + r.at(0, 24).getInt(4)
            var pos = first
            while (pos < r.length) {
                val g = r.at(pos, 24)
                val size = g.getInt(4).toLong() and 0xFFFFFFFFL
                val label = tag(g, 8)
                if (tag(g, 0) == "GRUP" && (label == "LTEX" || label == "STAT")) {
                    var p = pos + 24
                    while (p < pos + size) {
                        val h = r.at(p, 24)
                        val fid = h.getInt(12)
                        val recSize = h.getInt(4).toLong() and 0xFFFFFFFFL
                        val d = recordData(r, p, h)
                        var edid = ""
                        forSub(d) { st, b, o, n -> if (st == "EDID") edid = cstr(b, o, n).lowercase() }
                        if (label == "LTEX") {
                            t.ltex[fid] = groundColour(edid)
                            if ("dirtpath" in edid || "snowpath" in edid || "cobble" in edid) t.trailTex[fid] = 1f
                            else if (edid == "ldirt01" || edid == "ldirt02") t.trailTex[fid] = 0.45f
                        }
                        else if (edid.startsWith("road") && "sign" !in edid && "marker" !in edid && "cave" !in edid)
                            t.roadBase[fid] = if ("long" in edid) 1100f else if ("chunk" in edid) 300f else 600f
                        p += 24 + recSize
                    }
                }
                pos += size
            }
            pos = first
            while (pos < r.length) {
                val g = r.at(pos, 24)
                val size = g.getInt(4).toLong() and 0xFFFFFFFFL
                if (tag(g, 0) == "GRUP" && tag(g, 8) == "WRLD") {
                    walkWorlds(r, pos + 24, pos + size, edid, t) { progress(it * 0.85f) }
                    break
                }
                pos += size
            }
        }
        if (t.heights.isEmpty()) throw IllegalStateException("No terrain found for $edid")
        return render(t) { progress(0.85f + it * 0.15f) }
    }

    private fun walkWorlds(r: Reader, start: Long, end: Long, edid: String, t: Terrain, progress: (Float) -> Unit) {
        var pos = start
        var matched = false
        while (pos < end) {
            val h = r.at(pos, 24)
            val type = tag(h, 0)
            val size = h.getInt(4).toLong() and 0xFFFFFFFFL
            if (type == "GRUP") {
                if (matched && h.getInt(12) == 1) {
                    val cur = Cursor(t)
                    walk(r, pos + 24, pos + size, cur) { p -> progress(((p - pos).toFloat() / size).coerceIn(0f, 1f)) }
                    return
                }
                pos += size
            } else {
                val data = recordData(r, pos, h)
                if (type == "WRLD") {
                    matched = false
                    forSub(data) { st, b, o, n ->
                        when (st) {
                            "EDID" -> matched = cstr(b, o, n) == edid
                            "DNAM" -> if (n >= 8) { t.defaultLand = b.getFloat(o); t.defaultWater = b.getFloat(o + 4) }
                            "MNAM" -> if (n >= 16) t.bounds = intArrayOf(b.getShort(o + 8).toInt(), b.getShort(o + 10).toInt(), b.getShort(o + 12).toInt(), b.getShort(o + 14).toInt())
                        }
                    }
                }
                pos += 24 + size
            }
        }
    }

    private class Cursor(val t: Terrain) { var cell: Cell? = null; var lastReport = 0L }

    private fun walk(r: Reader, start: Long, end: Long, cur: Cursor, progress: (Long) -> Unit) {
        var pos = start
        while (pos < end) {
            val h = r.at(pos, 24)
            val type = tag(h, 0)
            val size = h.getInt(4).toLong() and 0xFFFFFFFFL
            if (type == "GRUP") {
                when (h.getInt(12)) {
                    4, 5, 6, 8, 9 -> walk(r, pos + 24, pos + size, cur, progress)   // blocks, sub-blocks, cell children
                }
                pos += size
                if (pos - cur.lastReport > (4 shl 20)) { cur.lastReport = pos; progress(pos) }
            } else {
                when (type) {
                    "CELL" -> cur.cell = parseCell(recordData(r, pos, h), cur.t)
                    "LAND" -> cur.cell?.let { c -> parseLand(recordData(r, pos, h), c, cur.t) }
                    "REFR" -> if (cur.t.roadBase.isNotEmpty()) parseRoad(recordData(r, pos, h), cur.t)
                }
                pos += 24 + size
            }
        }
    }

    private fun recordData(r: Reader, pos: Long, h: ByteBuffer): ByteBuffer {
        val size = h.getInt(4)
        val flags = h.getInt(8)
        val raw = r.at(pos + 24, size)
        if (flags and 0x00040000 == 0) return raw
        val outLen = raw.getInt(0)
        val src = ByteArray(size - 4).also { raw.position(4); raw.get(it) }
        val out = ByteArray(outLen)
        Inflater().run { setInput(src); inflate(out); end() }
        return ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    }

    private inline fun forSub(b: ByteBuffer, fn: (String, ByteBuffer, Int, Int) -> Unit) {
        var o = 0
        var big = -1
        while (o + 6 <= b.limit()) {
            val st = tag(b, o)
            var n = b.getShort(o + 4).toInt() and 0xFFFF
            if (big >= 0) { n = big; big = -1 }
            if (st == "XXXX") { big = b.getInt(o + 6); o += 6 + 4; continue }
            if (o + 6 + n > b.limit()) break
            fn(st, b, o + 6, n)
            o += 6 + n
        }
    }

    private fun cstr(b: ByteBuffer, o: Int, n: Int): String {
        val sb = StringBuilder()
        for (i in 0 until n) { val c = b.get(o + i).toInt(); if (c == 0) break; sb.append(c.toChar()) }
        return sb.toString()
    }

    private fun parseCell(b: ByteBuffer, t: Terrain): Cell? {
        var x: Int? = null; var y = 0; var water: Float? = null; var interior = false
        forSub(b) { st, bb, o, n ->
            when (st) {
                "DATA" -> if (n >= 1) interior = bb.get(o).toInt() and 1 != 0
                "XCLC" -> if (n >= 8) { x = bb.getInt(o); y = bb.getInt(o + 4) }
                "XCLW" -> if (n >= 4) water = bb.getFloat(o)
            }
        }
        val cx = x ?: return null
        if (interior) return null
        val w = water?.takeIf { it > -1e6f && it < 1e6f }
        if (w != null) t.water[key(cx, y)] = w
        return Cell(cx, y, w)
    }

    /** Road pieces are meshes the game lays over the terrain; keep where they are and which way they run. */
    private fun parseRoad(b: ByteBuffer, t: Terrain) {
        var len = 0f; var pos: FloatArray? = null
        forSub(b) { st, bb, o, n ->
            if (st == "NAME" && n >= 4) len = t.roadBase[bb.getInt(o)] ?: 0f
            if (st == "DATA" && n >= 24) pos = floatArrayOf(bb.getFloat(o), bb.getFloat(o + 4), bb.getFloat(o + 20))
        }
        val p = pos ?: return
        if (len > 0f) t.roads.add(floatArrayOf(p[0], p[1], p[2], len))
    }

    /** Map colour for a ground texture, by its editor name: the same grass, snow, tundra and dirt you walk on. */
    private val groundPalette = listOf(
        "dirtsnowpath" to 0xB2AFA6, "snowcobble" to 0xA8A49A, "dirtpath" to 0x9A8664,
        "frozenmarshice" to 0xC4CDD2, "snowrocks" to 0x9A9D9B, "grasssnow" to 0x9FA597, "snow" to 0xBBC0C3,
        "fallforestleaves" to 0x9A7238, "fallforestgrass" to 0x8E7A3E, "fallforestdirt" to 0x6E5A40, "fallforestrocks" to 0x6E675A,
        "pineforest" to 0x4E5A3E, "fielddirtgrass" to 0x7E7650, "fieldgrass" to 0x7A7E4A,
        "volcanictundramineralpool" to 0x9AA09A, "volcanictundraminerals" to 0x8E8C80, "volcanictundragravel" to 0x6E685E,
        "volcanictundrarocks" to 0x5C5751, "volcanictundra" to 0x5E554B,
        "tundrarocks" to 0x77705F, "tundra" to 0x847A54,
        "reachmossyrocks" to 0x6D6A58, "reachmoss" to 0x66704A, "reachgrass" to 0x6F7448, "reachdirt" to 0x7C6A55,
        "frozenmarshdirt" to 0x6D665A, "frozenmarsh" to 0x8A8B76,
        "coastbeachgrass" to 0x777A5A, "coastbeach" to 0x948A70, "coastoceanfloor" to 0x5E5C4E,
        "riverbedge" to 0x6E6650, "riverbottom" to 0x5C5746, "rivermud" to 0x5A5240,
        "ash" to 0x6A6660, "rocks" to 0x7A7872, "scrub" to 0x6F6A4F, "dirt" to 0x6E5F4A, "cave" to 0x5F5547, "mine" to 0x5F5547,
    )
    private const val DEFAULT_GROUND = 0x6E6858
    private fun groundColour(edid: String): Int = groundPalette.firstOrNull { it.first in edid }?.second ?: DEFAULT_GROUND

    private fun parseLand(b: ByteBuffer, c: Cell, t: Terrain) {
        // Ground textures: a base texture per quadrant with alpha-blended layers on top (BTXT / ATXT + VTXT).
        val base = IntArray(4) { -1 }
        val layers = ArrayList<IntArray>()           // quadrant, layer, form id, vtxt offset, vtxt length
        var cur: IntArray? = null
        forSub(b) { st, bb, o, n ->
            when (st) {
                "BTXT" -> if (n >= 5) base[bb.get(o + 4).toInt() and 3] = bb.getInt(o)
                "ATXT" -> if (n >= 8) { cur = intArrayOf(bb.get(o + 4).toInt() and 3, bb.getShort(o + 6).toInt(), bb.getInt(o), 0, 0); layers.add(cur!!) }
                "VTXT" -> cur?.let { it[3] = o; it[4] = n }
            }
        }
        val r = FloatArray(33 * 33); val g = FloatArray(33 * 33); val bl = FloatArray(33 * 33); val tr = FloatArray(33 * 33)
        val alpha = FloatArray(17 * 17)
        for (q in 0 until 4) {
            val qx = (q and 1) * 16; val qy = (q shr 1) * 16
            fun paint(colour: Int, a: FloatArray?, trail: Float) {
                val cr = (colour shr 16 and 255).toFloat(); val cg = (colour shr 8 and 255).toFloat(); val cb = (colour and 255).toFloat()
                for (yy in 0 until 17) for (xx in 0 until 17) {
                    val i = (qy + yy) * 33 + qx + xx
                    val f = a?.get(yy * 17 + xx) ?: 1f
                    if (f <= 0f) continue
                    r[i] += (cr - r[i]) * f; g[i] += (cg - g[i]) * f; bl[i] += (cb - bl[i]) * f
                    tr[i] += (trail - tr[i]) * f
                }
            }
            paint(t.ltex[base[q]] ?: DEFAULT_GROUND, null, t.trailTex[base[q]] ?: 0f)
            for (l in layers.filter { it[0] == q }.sortedBy { it[1] }) {
                if (l[4] < 8) continue
                java.util.Arrays.fill(alpha, 0f)
                for (k in 0 until l[4] / 8) {
                    val pos = b.getShort(l[3] + k * 8).toInt() and 0xFFFF
                    if (pos < 289) alpha[pos] = b.getFloat(l[3] + k * 8 + 4)
                }
                paint(t.ltex[l[2]] ?: DEFAULT_GROUND, alpha, t.trailTex[l[2]] ?: 0f)
            }
        }
        t.ground[key(c.x, c.y)] = IntArray(33 * 33) { i -> (r[i].toInt() shl 16) or (g[i].toInt() shl 8) or bl[i].toInt() }
        if (tr.any { it > 0.05f }) t.trails[key(c.x, c.y)] = ByteArray(33 * 33) { i -> (tr[i].coerceIn(0f, 1f) * 255).toInt().toByte() }
        forSub(b) { st, bb, o, n ->
            if (st != "VHGT" || n < 4 + 33 * 33) return@forSub
            val full = FloatArray(33 * 33)
            var rowStart = bb.getFloat(o) * 8f
            for (y in 0 until 33) {
                rowStart += bb.get(o + 4 + y * 33) * 8f
                var hgt = rowStart
                full[y * 33] = hgt
                for (x in 1 until 33) { hgt += bb.get(o + 4 + y * 33 + x) * 8f; full[y * 33 + x] = hgt }
            }
            val step = 32 / PX
            t.heights[key(c.x, c.y)] = FloatArray(SAMPLES * SAMPLES) { i -> full[(i / SAMPLES) * step * 33 + (i % SAMPLES) * step] }
        }
    }

    // ---------------------------------------------------------------- relief rendering

    private fun render(t: Terrain, progress: (Float) -> Unit): Pair<Bitmap, Info> {
        // Frame by the terrain itself (the worldspace's MNAM bounds are the game's camera window, not the land).
        val xs = t.heights.keys.map { (it shr 32).toInt() }; val ys = t.heights.keys.map { it.toInt() }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val info = Info(minX, maxY, maxX - minX + 1, maxY - minY + 1)
        val w = info.cellsW * PX; val h = info.cellsH * PX
        // Heights at full terrain resolution (one sample per 128 units). Water levels stay per cell.
        val hgt = FloatArray(w * h)
        val ground = IntArray(w * h)
        val trail = ByteArray(w * h)
        val water = FloatArray(info.cellsW * info.cellsH)
        for (cy in 0 until info.cellsH) {
            val cellY = maxY - cy
            for (cx in 0 until info.cellsW) {
                val k = key(minX + cx, cellY)
                val cell = t.heights[k]
                val gc = t.ground[k]
                water[cy * info.cellsW + cx] = t.water[k] ?: t.defaultWater
                for (py in 0 until PX) {
                    val row = (cy * PX + py) * w + cx * PX
                    if (cell == null) java.util.Arrays.fill(hgt, row, row + PX, t.defaultLand)
                    else for (px in 0 until PX) hgt[row + px] = cell[(PX - 1 - py) * SAMPLES + px]
                    if (gc == null) java.util.Arrays.fill(ground, row, row + PX, DEFAULT_GROUND)
                    else for (px in 0 until PX) ground[row + px] = gc[(32 - py) * 33 + px]
                    t.trails[k]?.let { tc -> for (px in 0 until PX) trail[row + px] = tc[(32 - py) * 33 + px] }
                }
            }
            if (cy % 8 == 0) progress(cy * 0.3f / info.cellsH)
        }
        fun wl(x: Int, y: Int) = water[(y / PX) * info.cellsW + (x / PX)]
        // The game blends its ground textures smoothly; soften the texture-grid edges (snow patches) to match.
        blurRgb(ground, w, h, 1)
        // Trails (dirt paths through grass and snow) go on after the softening so they stay crisp, like the game's map.
        for (i in ground.indices) {
            val a = (trail[i].toInt() and 0xFF) / 255f
            if (a > 0.15f) ground[i] = mix(ground[i], 0x8E7C5E, smooth(((a - 0.15f) / 0.5f).coerceIn(0f, 1f)) * 0.75f * a)
        }

        // Local relief (height above the neighbourhood average) at quarter resolution: valleys sit in
        // shadow and ridges catch light, which gives the map its depth.
        val bw = w / 4 + 1; val bh = h / 4 + 1
        val small = FloatArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) small[y * bw + x] = hgt[min(y * 4, h - 1) * w + min(x * 4, w - 1)]
        val blur = boxBlur(boxBlur(small, bw, bh, 6), bw, bh, 6)
        // Broad slope (512-unit spacing) for where rock shows and snow settles: smooth, no streaks.
        val broad = FloatArray(bw * bh)
        val zs = 1.5f / (2 * 512f)
        for (y in 0 until bh) for (x in 0 until bw) {
            val sx = (blur[y * bw + min(x + 1, bw - 1)] - blur[y * bw + max(x - 1, 0)]) * zs
            val sy = (blur[min(y + 1, bh - 1) * bw + x] - blur[max(y - 1, 0) * bw + x]) * zs
            broad[y * bw + x] = sqrt(sx * sx + sy * sy)
        }
        /** Smoothly samples a quarter-resolution layer at full-resolution pixel (x, y). */
        fun quarter(layer: FloatArray, x: Int, y: Int): Float {
            val fx = (x / 4f).coerceAtMost(bw - 1.001f); val fy = (y / 4f).coerceAtMost(bh - 1.001f)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val tx = fx - x0; val ty = fy - y0
            val a = layer[y0 * bw + x0]; val b2 = layer[y0 * bw + x0 + 1]; val c2 = layer[(y0 + 1) * bw + x0]; val d = layer[(y0 + 1) * bw + x0 + 1]
            return (a * (1 - tx) + b2 * tx) * (1 - ty) + (c2 * (1 - tx) + d * tx) * ty
        }
        fun broadAt(x: Int, y: Int) = quarter(broad, x, y)

        // Roads: each piece is a short pale stroke along its heading, softened slightly.
        val roadMask = ByteArray(w * h)
        run {
            val m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val cv = android.graphics.Canvas(m)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 2.6f * PX / 32f; strokeCap = android.graphics.Paint.Cap.ROUND; alpha = 255
                maskFilter = android.graphics.BlurMaskFilter(0.7f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            val k = PX / 4096f
            for (rd in t.roads) {
                val cx = (rd[0] / 4096f - minX) * PX; val cy = (maxY + 1 - rd[1] / 4096f) * PX
                val hx = kotlin.math.sin(rd[2]) * rd[3] * 0.6f * k; val hy = -kotlin.math.cos(rd[2]) * rd[3] * 0.6f * k
                cv.drawLine(cx - hx, cy - hy, cx + hx, cy + hy, paint)
            }
            m.copyPixelsToBuffer(ByteBuffer.wrap(roadMask))
            m.recycle()
        }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        val zScale = 1.5f / (4 * 4096f / 32)          // gentle exaggeration, measured over 2 samples each way
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val e = hgt[i]
                val wlev = wl(x, y)
                val dx = (hgt[y * w + min(x + 2, w - 1)] - hgt[y * w + max(x - 2, 0)]) * zScale
                val dy = (hgt[min(y + 2, h - 1) * w + x] - hgt[max(y - 2, 0) * w + x]) * zScale
                val nl = sqrt(dx * dx + dy * dy + 1f)
                // Key light from the north-west, a soft fill from the north, and ambient.
                val key = ((-dx * -0.55f + dy * 0.55f + 0.63f) / nl).coerceIn(0f, 1f)
                val fill = ((dy * 0.7f + 0.7f) / nl).coerceIn(0f, 1f)
                val relief = ((e - quarter(blur, x, y)) / 900f).coerceIn(-1f, 1f)
                val depth = wlev - e
                var waterC = 0
                if (depth > -80f) {
                    var wc = mix(0x56696F, 0x223039, smooth((depth / 4500f).coerceIn(0f, 1f)))
                    if (depth < 140f) wc = mix(wc, 0x8A9C9C, (1f - depth.coerceAtLeast(0f) / 140f) * 0.35f)        // shoreline
                    waterC = scale(wc, 0.86f + 0.16f * key)
                    if (depth > 80f) { row[x] = rgb(waterC); continue }
                }
                // The ground's own colour (grass, tundra, snow, dirt paths), lit by the relief.
                var c = ground[i]
                // Steep faces show rock, as they do in game.
                c = mix(c, 0x76736B, smooth(((broadAt(x, y) - 0.35f) / 0.6f).coerceIn(0f, 1f)) * 0.45f)
                val bright = ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 765f
                val snow = ((bright - 0.6f) / 0.2f).coerceIn(0f, 1f)
                // Snowfields keep the full relief light so peaks read as mountains, tinted a cool blue-grey in shadow.
                if (snow > 0f) c = mix(c, 0xAEB5BB, snow * 0.5f)
                // Bright ground (snow) is lit a little less so it keeps its shape instead of burning out to white.
                val shade = ((0.52f + 0.58f * key + 0.10f * fill) * (1f + 0.2f * relief) * (1f - snow * 0.12f)).coerceAtMost(1.3f - snow * 0.2f)
                c = scale(c, shade)
                val road = (roadMask[i].toInt() and 0xFF) / 255f
                if (road > 0f) c = mix(c, scale(0xC4B696, shade), road * 0.6f)
                // Smooth shorelines: blend land into water across the waterline instead of a hard pixel step.
                if (depth > -80f) c = mix(c, waterC, smooth(((depth + 80f) / 160f).coerceIn(0f, 1f)))
                row[x] = rgb(c)
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
            if (y % 96 == 0) progress(0.3f + y * 0.6f / h)
        }
        return bmp to info
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)

    /** In-place box blur of packed RGB, one row buffer at a time (keeps memory flat on the big map). */
    private fun blurRgb(px: IntArray, w: Int, h: Int, r: Int) {
        fun pass(len: Int, lines: Int, at: (line: Int, i: Int) -> Int) {
            val buf = IntArray(len)
            for (line in 0 until lines) {
                for (i in 0 until len) buf[i] = px[at(line, i)]
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                for (i in 0..min(r, len - 1)) { val c = buf[i]; sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255; n++ }
                for (i in 0 until len) {
                    px[at(line, i)] = ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                    val add = i + r + 1; if (add < len) { val c = buf[add]; sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255; n++ }
                    val rem = i - r; if (rem >= 0) { val c = buf[rem]; sr -= c shr 16 and 255; sg -= c shr 8 and 255; sb -= c and 255; n-- }
                }
            }
        }
        pass(w, h) { y, x -> y * w + x }
        pass(h, w) { x, y -> y * w + x }
    }

    /** Cheap value noise for texture variation (forests, snow line). */
    private fun noise(x: Float, y: Float): Float {
        fun hash(i: Int, j: Int): Float { var n = i * 374761393 + j * 668265263; n = (n xor (n ushr 13)) * 1274126177; return ((n xor (n ushr 16)) and 0xFFFF) / 65535f }
        val xi = kotlin.math.floor(x).toInt(); val yi = kotlin.math.floor(y).toInt()
        val tx = smooth(x - xi); val ty = smooth(y - yi)
        val a = hash(xi, yi); val b = hash(xi + 1, yi); val c = hash(xi, yi + 1); val d = hash(xi + 1, yi + 1)
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
    }

    /** Separable box blur (sliding window, edges averaged over what's inside). */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size); val out = FloatArray(src.size)
        for (y in 0 until h) {
            var acc = 0f; var n = 0
            for (x in 0..min(r, w - 1)) { acc += src[y * w + x]; n++ }
            for (x in 0 until w) {
                tmp[y * w + x] = acc / n
                val add = x + r + 1; if (add < w) { acc += src[y * w + add]; n++ }
                val rem = x - r; if (rem >= 0) { acc -= src[y * w + rem]; n-- }
            }
        }
        for (x in 0 until w) {
            var acc = 0f; var n = 0
            for (y in 0..min(r, h - 1)) { acc += tmp[y * w + x]; n++ }
            for (y in 0 until h) {
                out[y * w + x] = acc / n
                val add = y + r + 1; if (add < h) { acc += tmp[add * w + x]; n++ }
                val rem = y - r; if (rem >= 0) { acc -= tmp[rem * w + x]; n-- }
            }
        }
        return out
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - f) + ((b shr s) and 0xFF) * f).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    private fun scale(c: Int, f: Float): Int {
        fun ch(s: Int) = (((c shr s) and 0xFF) * f).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    private fun rgb(c: Int) = c or (0xFF shl 24)

    /** Soft cloud cover for the world map (tileable value noise), like the clouds over the game's map. */
    val clouds: ImageBitmap by lazy {
        val n = 256
        val rnd = java.util.Random(1704)
        fun lattice(g: Int) = FloatArray(g * g) { rnd.nextFloat() }
        val octaves = listOf(8 to 0.55f, 16 to 0.28f, 32 to 0.12f, 64 to 0.05f).map { (g, a) -> Triple(g, a, lattice(g)) }
        fun smooth(t: Float) = t * t * (3 - 2 * t)
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            var v = 0f
            for ((g, a, l) in octaves) {
                val fx = x * g / n.toFloat(); val fy = y * g / n.toFloat()
                val x0 = fx.toInt() % g; val y0 = fy.toInt() % g
                val x1 = (x0 + 1) % g; val y1 = (y0 + 1) % g
                val tx = smooth(fx - fx.toInt()); val ty = smooth(fy - fy.toInt())
                val top = l[y0 * g + x0] * (1 - tx) + l[y0 * g + x1] * tx
                val bot = l[y1 * g + x0] * (1 - tx) + l[y1 * g + x1] * tx
                v += (top * (1 - ty) + bot * ty) * a
            }
            val c = ((v - 0.52f) / 0.3f).coerceIn(0f, 1f)
            val alpha = (smooth(c) * 200).toInt()
            px[y * n + x] = (alpha shl 24) or 0xF2F4F6
        }
        Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Fine ground grain (tileable, mid-grey) laid over the map when zoomed in close, so it reads as terrain, not blur. */
    val grain: ImageBitmap by lazy {
        val n = 128
        val rnd = java.util.Random(489830)
        fun lattice(g: Int) = FloatArray(g * g) { rnd.nextFloat() }
        val octaves = listOf(16 to 0.5f, 32 to 0.3f, 64 to 0.2f).map { (g, a) -> Triple(g, a, lattice(g)) }
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            var v = 0f
            for ((g, a, l) in octaves) {
                val fx = x * g / n.toFloat(); val fy = y * g / n.toFloat()
                val x0 = fx.toInt() % g; val y0 = fy.toInt() % g; val x1 = (x0 + 1) % g; val y1 = (y0 + 1) % g
                val tx = fx - fx.toInt(); val ty = fy - fy.toInt()
                v += ((l[y0 * g + x0] * (1 - tx) + l[y0 * g + x1] * tx) * (1 - ty) + (l[y1 * g + x0] * (1 - tx) + l[y1 * g + x1] * tx) * ty) * a
            }
            val c = (v * 255).toInt().coerceIn(0, 255)
            px[y * n + x] = (0xFF shl 24) or (c shl 16) or (c shl 8) or c
        }
        Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Last exterior position, so the map can still show where you are from inside a dungeon. */
    @Volatile var lastExterior: Offset? = null
}

// ================================================================ marker glyphs (map + compass), drawn like the game's

internal object MarkerStyle {
    val Quest = Color(0xFFE6E6E6)          // the game's quest marker is silver-white
    val QuestEdge = Color(0xFF6E6E6E)
    val Hostile = Color(0xFFC0281E)
    val Ally = Color(0xFF8DB580)
    val Location = Color(0xFFEDEDED)
    val Undiscovered = Color(0x99D0D0D0)
}

private enum class Glyph { CITY, TOWN, DUNGEON, CAMP, LANDMARK, SHRINE }

/** Cities and the hold capitals (which have their own icon types in the 30s-50s). */
internal fun isCity(type: Int) = type == 1 || type in 34..53

/** Groups Skyrim's map-marker icon types into a few readable glyphs. */
private fun glyphFor(type: Int): Glyph = when {
    isCity(type) -> Glyph.CITY
    type in setOf(2, 3, 13, 14, 18, 19, 20, 29, 31, 32) -> Glyph.TOWN
    type in setOf(4, 6, 7, 8, 12, 15, 21, 30) -> Glyph.DUNGEON
    type in setOf(5, 16, 27, 28) -> Glyph.CAMP
    type in setOf(17, 24, 33) -> Glyph.SHRINE
    else -> Glyph.LANDMARK
}

/** Draws one of the game's own icons (see [GameIcons]) centred on [c], [size] px tall. False if they aren't loaded. */
internal fun DrawScope.gameIcon(frame: Int, c: Offset, size: Float, alpha: Float = 1f): Boolean {
    val sheet = GameIcons.sheet.value ?: return false
    if (frame < 1 || frame > sheet.frames) return false
    val (sx, sy) = GameIcons.cellOf(frame)
    val s = size.roundToInt().coerceAtLeast(2)
    drawImage(sheet.image, srcOffset = IntOffset(sx, sy), srcSize = IntSize(GameIcons.CELL, GameIcons.CELL),
        dstOffset = IntOffset((c.x - s / 2f).roundToInt(), (c.y - s / 2f).roundToInt()), dstSize = IntSize(s, s),
        alpha = alpha, filterQuality = FilterQuality.Medium)
    return true
}

internal fun DrawScope.locationGlyph(type: Int, c: Offset, r: Float, discovered: Boolean) {
    // The game's own icon for this kind of place, when it could be read from the player's game.
    if (type > 0 && gameIcon(GameIcons.locationFrame(type, discovered), c, r * 2.6f)) return
    val color = if (discovered) MarkerStyle.Location else MarkerStyle.Undiscovered
    val shadow = Color(0xAA000000)
    val path = Path()
    when (glyphFor(type)) {
        Glyph.CITY -> {   // keep with battlements
            val w = r * 1.1f; val top = c.y - r; val bot = c.y + r
            path.moveTo(c.x - w, bot); path.lineTo(c.x - w, top)
            path.lineTo(c.x - w * 0.5f, top); path.lineTo(c.x - w * 0.5f, top + r * 0.45f)
            path.lineTo(c.x - w * 0.15f, top + r * 0.45f); path.lineTo(c.x - w * 0.15f, top)
            path.lineTo(c.x + w * 0.15f, top); path.lineTo(c.x + w * 0.15f, top + r * 0.45f)
            path.lineTo(c.x + w * 0.5f, top + r * 0.45f); path.lineTo(c.x + w * 0.5f, top)
            path.lineTo(c.x + w, top); path.lineTo(c.x + w, bot); path.close()
        }
        Glyph.TOWN -> {   // house
            path.moveTo(c.x, c.y - r); path.lineTo(c.x + r, c.y - r * 0.1f); path.lineTo(c.x + r * 0.75f, c.y - r * 0.1f)
            path.lineTo(c.x + r * 0.75f, c.y + r); path.lineTo(c.x - r * 0.75f, c.y + r); path.lineTo(c.x - r * 0.75f, c.y - r * 0.1f)
            path.lineTo(c.x - r, c.y - r * 0.1f); path.close()
        }
        Glyph.DUNGEON -> { // arch
            path.moveTo(c.x - r, c.y + r); path.lineTo(c.x - r, c.y - r * 0.2f)
            path.quadraticTo(c.x - r, c.y - r, c.x, c.y - r); path.quadraticTo(c.x + r, c.y - r, c.x + r, c.y - r * 0.2f)
            path.lineTo(c.x + r, c.y + r); path.lineTo(c.x + r * 0.4f, c.y + r); path.lineTo(c.x + r * 0.4f, c.y)
            path.lineTo(c.x - r * 0.4f, c.y); path.lineTo(c.x - r * 0.4f, c.y + r); path.close()
        }
        Glyph.CAMP -> {   // tent
            path.moveTo(c.x, c.y - r); path.lineTo(c.x + r, c.y + r); path.lineTo(c.x + r * 0.25f, c.y + r)
            path.lineTo(c.x, c.y + r * 0.35f); path.lineTo(c.x - r * 0.25f, c.y + r); path.lineTo(c.x - r, c.y + r); path.close()
        }
        Glyph.SHRINE -> { // standing stone
            path.moveTo(c.x - r * 0.45f, c.y + r); path.lineTo(c.x - r * 0.55f, c.y - r * 0.6f)
            path.quadraticTo(c.x, c.y - r * 1.3f, c.x + r * 0.55f, c.y - r * 0.6f); path.lineTo(c.x + r * 0.45f, c.y + r); path.close()
        }
        Glyph.LANDMARK -> {
            path.moveTo(c.x, c.y - r); path.lineTo(c.x + r * 0.8f, c.y); path.lineTo(c.x, c.y + r); path.lineTo(c.x - r * 0.8f, c.y); path.close()
        }
    }
    drawPath(path, shadow, style = Stroke(width = r * 0.55f))
    if (discovered) drawPath(path, color) else drawPath(path, color, style = Stroke(width = r * 0.22f))
}

/** The game's quest marker: a silver V with a small diamond above it. [c] is the V's centre. */
internal fun DrawScope.questGlyph(c: Offset, r: Float) {
    if (gameIcon(GameIcons.QUEST, c, r * 2.3f)) return
    val v = Path().apply {
        moveTo(c.x - r * 0.85f, c.y - r * 0.75f); lineTo(c.x - r * 0.28f, c.y - r * 0.75f); lineTo(c.x, c.y + r * 0.15f)
        lineTo(c.x + r * 0.28f, c.y - r * 0.75f); lineTo(c.x + r * 0.85f, c.y - r * 0.75f); lineTo(c.x, c.y + r); close()
    }
    val d = Path().apply {
        val cy = c.y - r * 1.25f; val dr = r * 0.38f
        moveTo(c.x, cy - dr * 1.3f); lineTo(c.x + dr, cy); lineTo(c.x, cy + dr * 1.3f); lineTo(c.x - dr, cy); close()
    }
    val edge = Stroke(width = r * 0.28f)
    drawPath(v, Color(0xCC000000), style = edge); drawPath(d, Color(0xCC000000), style = edge)
    drawPath(v, Brush.verticalGradient(listOf(Color.White, MarkerStyle.Quest, Color(0xFFB8B8B8)), c.y - r, c.y + r))
    drawPath(d, MarkerStyle.Quest)
}

/** A glossy sphere, like the game's enemy dots. */
internal fun DrawScope.dotGlyph(c: Offset, r: Float, color: Color) {
    drawCircle(Color(0xCC000000), r * 1.3f, c)
    drawCircle(Brush.radialGradient(listOf(color.copy(red = (color.red + 0.35f).coerceAtMost(1f)), color, color.copy(alpha = 1f).let {
        Color(it.red * 0.45f, it.green * 0.45f, it.blue * 0.45f)
    }), center = c - Offset(r * 0.35f, r * 0.35f), radius = r * 1.4f), r, c)
    drawCircle(Color(0x88FFFFFF), r * 0.28f, c - Offset(r * 0.35f, r * 0.4f))
}

private fun DrawScope.playerGlyph(c: Offset, r: Float, heading: Float) {
    rotate((heading * 180 / PI).toFloat(), c) {
        val p = Path().apply {
            moveTo(c.x, c.y - r * 1.3f); lineTo(c.x + r, c.y + r); lineTo(c.x, c.y + r * 0.45f); lineTo(c.x - r, c.y + r); close()
        }
        drawPath(p, Color(0xDD000000), style = Stroke(width = r * 0.5f))
        drawPath(p, Color.White)
    }
}

// ================================================================ shared renderer

/** Draws the relief map, markers and player, with map pixel [c] at the centre of the view. */
private val MapFog = Color(0xFF1E2427)

internal fun DrawScope.drawWorld(
    s: WorldMaps.State.Ready, c: Offset, zoom: Float, markers: List<MapMarker>, me: Offset?, heading: Float,
    selected: MapMarker?, names: Boolean, scale: Float = 1f, clouds: Boolean = false,
    cloudDrift: Float = 0f, pulse: Float = -1f, enemies: Boolean = true,
) {
    val info = s.info
    val view = Offset(size.width / 2, size.height / 2)
    fun screen(x: Float, y: Float) = view + (info.toMap(x, y) - c) * zoom
    val topLeft = view - c * zoom
    // Beyond the edge of the land there's only mist, as in the game: no hard square under the clouds.
    drawRect(MapFog)
    drawImage(
        s.image, srcOffset = IntOffset.Zero, srcSize = IntSize(s.image.width, s.image.height),
        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
        dstSize = IntSize((info.width * zoom).roundToInt(), (info.height * zoom).roundToInt()),
        // High quality (smooth, not blocky) when zoomed in past the map's own pixel size.
        filterQuality = if (zoom > 2f) FilterQuality.High else FilterQuality.Medium,
    )
    // Close in, past the map's own detail: a fine ground grain (overlay) so it reads as terrain rather than blur.
    val grainAlpha = ((zoom - 3f) / 3f).coerceIn(0f, 0.22f)
    if (grainAlpha > 0f) {
        val tile = 4f * zoom   // a quarter of a cell per tile
        val ox = ((topLeft.x % tile) + tile) % tile - tile
        val oy = ((topLeft.y % tile) + tile) % tile - tile
        clipRect(topLeft.x, topLeft.y, topLeft.x + info.width * zoom, topLeft.y + info.height * zoom) {
            var ty = oy
            while (ty < size.height) {
                val y0 = ty.roundToInt(); val y1 = (ty + tile).roundToInt()
                var tx = ox
                while (tx < size.width) {
                    val x0 = tx.roundToInt(); val x1 = (tx + tile).roundToInt()
                    drawImage(WorldMaps.grain, srcOffset = IntOffset.Zero, srcSize = IntSize(128, 128), dstOffset = IntOffset(x0, y0),
                        dstSize = IntSize(x1 - x0, y1 - y0), alpha = grainAlpha, blendMode = androidx.compose.ui.graphics.BlendMode.Overlay,
                        filterQuality = FilterQuality.Medium)
                    tx += tile
                }
                ty += tile
            }
        }
    }
    // Clouds over the land on the world view only (the game's local map has none): large, soft, drifting,
    // kept inside the land so nothing tiles over the mist; gone by the time you zoom in.
    val cloudAlpha = (0.42f - (zoom - 0.6f) * 0.26f).coerceIn(0f, 0.42f)
    if (clouds && cloudAlpha > 0.01f) {
        val tile = 1600f * zoom
        val shift = (c * zoom) * 0.12f + Offset(cloudDrift * tile, cloudDrift * tile * 0.35f)
        val ox = ((topLeft.x + shift.x) % tile + tile) % tile - tile
        val oy = ((topLeft.y + shift.y) % tile + tile) % tile - tile
        clipRect(topLeft.x, topLeft.y, topLeft.x + info.width * zoom, topLeft.y + info.height * zoom) {
            // Tiles meet exactly (no overlap), so no brighter seams where two would double up.
            var ty = oy
            while (ty < size.height) {
                val y0 = ty.roundToInt(); val y1 = (ty + tile).roundToInt()
                var tx = ox
                while (tx < size.width) {
                    val x0 = tx.roundToInt(); val x1 = (tx + tile).roundToInt()
                    // A soft shadow on the land below each cloud, then the cloud: they read as floating above
                    // the map (plain white vanished over snow and only showed over the sea).
                    val sh = (tile * 0.035f).roundToInt()
                    drawImage(WorldMaps.clouds, srcOffset = IntOffset.Zero, srcSize = IntSize(256, 256),
                        dstOffset = IntOffset(x0 + sh, y0 + sh * 2), dstSize = IntSize(x1 - x0, y1 - y0),
                        alpha = cloudAlpha * 0.55f, filterQuality = FilterQuality.Medium,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFF101418)))
                    drawImage(WorldMaps.clouds, srcOffset = IntOffset.Zero, srcSize = IntSize(256, 256),
                        dstOffset = IntOffset(x0, y0), dstSize = IntSize(x1 - x0, y1 - y0),
                        alpha = (cloudAlpha * 1.25f).coerceAtMost(0.55f), filterQuality = FilterQuality.Medium)
                    tx += tile
                }
                ty += tile
            }
        }
    }
    run {
        val iw = info.width * zoom; val ih = info.height * zoom
        val fx = iw * 0.07f; val fy = ih * 0.07f
        val clear = MapFog.copy(alpha = 0f)
        drawRect(Brush.horizontalGradient(listOf(MapFog, clear), topLeft.x, topLeft.x + fx), topLeft, androidx.compose.ui.geometry.Size(fx, ih))
        drawRect(Brush.horizontalGradient(listOf(clear, MapFog), topLeft.x + iw - fx, topLeft.x + iw), Offset(topLeft.x + iw - fx, topLeft.y), androidx.compose.ui.geometry.Size(fx, ih))
        drawRect(Brush.verticalGradient(listOf(MapFog, clear), topLeft.y, topLeft.y + fy), topLeft, androidx.compose.ui.geometry.Size(iw, fy))
        drawRect(Brush.verticalGradient(listOf(clear, MapFog), topLeft.y + ih - fy, topLeft.y + ih), Offset(topLeft.x, topLeft.y + ih - fy), androidx.compose.ui.geometry.Size(iw, fy))
    }
    // Icons are smaller on the whole-world view and grow as you zoom in, but stay readable on a 6" screen.
    val r = 7.dp.toPx() * scale * (0.6f + 0.14f * zoom).coerceIn(0.8f, 1.35f)
    val text = android.graphics.Paint().apply {
        isAntiAlias = true; color = android.graphics.Color.argb(235, 240, 240, 240)
        // Smaller on the whole-world view, so every city's name fits; full size once you zoom in.
        textSize = 12.sp.toPx() * scale * (0.62f + 0.2f * zoom).coerceIn(0.72f, 1f)
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
        letterSpacing = 0.04f; textAlign = android.graphics.Paint.Align.CENTER
        setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK)
    }
    val onMap = markers.filter { !it.interior && !it.hidden }
    // Names never overlap: cities first, then the rest; a name that would collide is skipped.
    val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
    fun nameFits(r: androidx.compose.ui.geometry.Rect) = placed.none { it.overlaps(r) }.also { if (it) placed += r }
    for (m in onMap.sortedByDescending { isCity(it.type) }) if (m.kind == MarkerKind.LOCATION) {
        val sp = screen(m.x, m.y)
        if (sp.x < -40 || sp.y < -40 || sp.x > size.width + 40 || sp.y > size.height + 40) continue
        val rr = if (isCity(m.type)) r * 1.25f else r
        // A soft dark halo so icons read against snow, water and grass at any zoom.
        drawCircle(Brush.radialGradient(listOf(Color(0x8C000000), Color.Transparent), center = sp, radius = rr * 2.1f), rr * 2.1f, sp)
        locationGlyph(m.type, sp, rr, m.discovered)
        if (names && (isCity(m.type) || (m.discovered && zoom >= 2.2f && m.type in 2..3) || (m.discovered && zoom >= 5f))) {
            val label = m.name.uppercase()
            val w = text.measureText(label); val y = sp.y + r * 2.6f
            if (nameFits(androidx.compose.ui.geometry.Rect(sp.x - w / 2, y - text.textSize * 0.8f, sp.x + w / 2, y + 2f)))
                drawContext.canvas.nativeCanvas.drawText(label, sp.x, y, text)
        }
    }
    if (enemies) for (m in onMap) when (m.kind) {
        MarkerKind.HOSTILE -> dotGlyph(screen(m.x, m.y), 4.dp.toPx() * scale, MarkerStyle.Hostile)
        MarkerKind.ALLY -> dotGlyph(screen(m.x, m.y), 4.dp.toPx() * scale, MarkerStyle.Ally)
        else -> {}
    }
    // Quest markers a size up from places (like the game's map), with the same halo so they stand out.
    for (m in onMap) if (m.kind == MarkerKind.QUEST) {
        val c = screen(m.x, m.y) - Offset(0f, r * 1.4f)
        drawCircle(Brush.radialGradient(listOf(Color(0x99000000), Color.Transparent), center = c, radius = r * 2.6f), r * 2.6f, c)
        questGlyph(c, r * 1.7f)
    }
    me?.let {
        val sp = screen(it.x, it.y)
        // A soft pulse so you can find yourself at a glance.
        if (pulse >= 0f) drawCircle(Color.White.copy(alpha = 0.35f * (1f - pulse)), 8.dp.toPx() * scale * (1.2f + pulse * 1.6f), sp, style = Stroke(1.5.dp.toPx()))
        playerGlyph(sp, 8.dp.toPx() * scale * (0.7f + 0.1f * zoom).coerceIn(0.7f, 1f), heading)
    }
    // The selected place: a ring and its name in a tag beside it, like hovering on the game's map.
    selected?.let { m ->
        val sp = screen(m.x, m.y)
        drawCircle(Color.White, r * 2.1f, sp, style = Stroke(width = 1.5.dp.toPx()))
        val label = m.name.ifEmpty { "Unknown" }.uppercase()
        val tag = android.graphics.Paint(text).apply { textSize = 13.sp.toPx(); textAlign = android.graphics.Paint.Align.LEFT; clearShadowLayer() }
        val tw = tag.measureText(label)
        val pad = 6.dp.toPx()
        var lx = sp.x + r * 2.4f
        if (lx + tw + pad * 2 > size.width - 4) lx = sp.x - r * 2.4f - tw - pad * 2
        val ly = sp.y - 9.dp.toPx()
        drawRect(Color(0xE6000000), Offset(lx, ly), androidx.compose.ui.geometry.Size(tw + pad * 2, 18.dp.toPx()))
        drawRect(Color(0x66FFFFFF), Offset(lx, ly), androidx.compose.ui.geometry.Size(tw + pad * 2, 18.dp.toPx()), style = Stroke(1f))
        drawContext.canvas.nativeCanvas.drawText(label, lx + pad, ly + 13.5.dp.toPx(), tag)
    }
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x99000000)), center = view, radius = max(size.width, size.height) * 0.75f))
}

@Composable
private fun rememberMapState(worldId: Int): WorldMaps.State {
    val context = LocalContext.current
    val state by remember(worldId) { WorldMaps.state(context, worldId) }.collectAsState()
    return state
}

private fun mapWorldId(p: PlayerState, markers: Markers) =
    if (markers.worldspaceId != 0) markers.worldspaceId else p.worldspaceId.takeIf { it != 0 } ?: 0x3C

private fun myPosition(p: PlayerState): Offset? {
    val indoors = p.interior || p.worldspaceId == 0
    if (!indoors) WorldMaps.lastExterior = Offset(p.x, p.y)
    return if (indoors) WorldMaps.lastExterior else Offset(p.x, p.y)
}

// ================================================================ mini map (character page)

/** A small north-up local map around the player; tap to open the full map. */
@Composable
internal fun MiniMap(p: PlayerState, modifier: Modifier, onTap: () -> Unit) {
    val markers by Link.markers.collectAsState()
    val state = rememberMapState(mapWorldId(p, markers))
    val me = myPosition(p)
    val indoors = p.interior || p.worldspaceId == 0
    Box(modifier.clickable(onClick = onTap).background(Color(0xFF1C2226))) {
        when (val s = state) {
            is WorldMaps.State.Ready -> Canvas(Modifier.fillMaxSize().clipToBounds()) {
                val c = me?.let { s.info.toMap(it.x, it.y) } ?: Offset(s.info.width / 2f, s.info.height / 2f)
                drawWorld(s, c, 4.2f, markers.list, me, if (indoors) 0f else p.heading, null, names = false, scale = 0.8f)
            }
            is WorldMaps.State.Building -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SkyText("CHARTING…", 11.sp, Sky.Dim, 2.sp)
            }
            else -> {}
        }
        // Frame like the game's menus: thin light border with diamond corners.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Sky.Line, style = Stroke(1.5.dp.toPx()))
            listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height)).forEach { diamond(it, 5.dp.toPx(), Sky.Text) }
        }
        if (indoors) SkyText("INSIDE", 10.sp, Sky.Dim, 2.sp,
            modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

// ================================================================ live map

/** The full map. [focus] (world x/y), when set, is centred first (e.g. "show quest on map"). */
@Composable
internal fun LiveMap(p: PlayerState, focus: Offset? = null, onFastTravel: ((MapMarker) -> Unit)? = null) {
    val markers by Link.markers.collectAsState()
    val state = rememberMapState(mapWorldId(p, markers))
    val indoors = p.interior || p.worldspaceId == 0
    val me = myPosition(p)

    var zoom by remember { mutableFloatStateOf(3.2f) }          // screen px per map unit
    var center by remember(focus) { mutableStateOf<Offset?>(null) }   // map px at view centre; null = follow
    var selected by remember(focus) { mutableStateOf(focus?.let { f -> markers.list.firstOrNull { it.kind == MarkerKind.QUEST && it.x == f.x && it.y == f.y } }) }

    val scope = rememberCoroutineScope()
    // Cloud drift and your pulse are slow: 15 updates a second is plenty, and leaves the GPU to Skyrim
    // (a full-rate infinite animation redrew the whole map every vsync while the game was running).
    var tick by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(66); tick = android.os.SystemClock.uptimeMillis() } }
    val drift = (tick % 180_000L) / 180_000f
    val pulse = (tick % 2200L) / 2200f

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xFF1C2226))) {
            when (val s = state) {
                is WorldMaps.State.Ready -> {
                    val info = s.info
                    /** Glides to [toZoom] and [toCenter] (null = back to following you). */
                    fun glide(toZoom: Float, toCenter: Offset?, follow: Boolean) {
                        val fromZ = zoom
                        val fromC = center ?: me?.let { info.toMap(it.x, it.y) } ?: Offset(info.width / 2f, info.height / 2f)
                        val toC = toCenter ?: me?.let { info.toMap(it.x, it.y) } ?: fromC
                        scope.launch {
                            androidx.compose.animation.core.animate(0f, 1f, animationSpec = tween(320, easing = FastOutSlowInEasing)) { f, _ ->
                                zoom = fromZ + (toZoom - fromZ) * f
                                center = fromC + (toC - fromC) * f
                            }
                            if (follow) center = null
                        }
                    }
                    if (focus != null && center == null) center = info.toMap(focus.x, focus.y)
                    val follow = center == null
                    val c = center ?: me?.let { info.toMap(it.x, it.y) } ?: Offset(info.width / 2f, info.height / 2f)
                    Canvas(
                        Modifier.fillMaxSize()
                            .pointerInput(info) {
                                detectTransformGestures { _, pan, gz, _ ->
                                    val base = center ?: c
                                    val nz = (zoom * gz).coerceIn(0.35f, 8f)
                                    center = Offset(
                                        (base.x - pan.x / nz).coerceIn(0f, info.width.toFloat()),
                                        (base.y - pan.y / nz).coerceIn(0f, info.height.toFloat()),
                                    )
                                    zoom = nz
                                }
                            }
                            .pointerInput(info, markers) {
                                detectTapGestures(onDoubleTap = { tap ->
                                    val view = Offset(size.width / 2f, size.height / 2f)
                                    val cc = center ?: c
                                    val nz = (zoom * 1.8f).coerceAtMost(8f)
                                    val target = cc + (tap - view) / zoom
                                    center = Offset(target.x.coerceIn(0f, info.width.toFloat()), target.y.coerceIn(0f, info.height.toFloat()))
                                    zoom = nz
                                }) { tap ->
                                    val view = Offset(size.width / 2f, size.height / 2f)
                                    val cc = center ?: c
                                    fun dist(m: MapMarker): Float {
                                        val sp = view + (info.toMap(m.x, m.y) - cc) * zoom
                                        return hypot(sp.x - tap.x, sp.y - tap.y)
                                    }
                                    // Only what the map shows: places the game hasn't put on your map stay hidden.
                                    selected = markers.list.filter { !it.interior && !it.hidden && it.kind != MarkerKind.HOSTILE && it.kind != MarkerKind.ALLY }
                                        .minByOrNull(::dist)?.takeIf { dist(it) < 34.dp.toPx() }
                                }
                            },
                    ) {
                        // Like the game's world map: places, quests and you; enemies live on the compass and local map.
                        drawWorld(s, c, zoom, markers.list, me, if (indoors) 0f else p.heading, selected, names = true, clouds = true,
                            cloudDrift = drift, pulse = if (indoors) -1f else pulse, enemies = false)
                    }
                    // LOCAL zooms in around you; WORLD shows all of Skyrim.
                    val local = zoom >= 3f
                    Box(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                        MapButton(if (local) "WORLD" else "LOCAL") {
                            if (local) glide(size(info).coerceIn(0.35f, 1f), Offset(info.width / 2f, info.height / 2f), follow = false)
                            else glide(6f, null, follow = true)
                        }
                    }
                    if (!follow) Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                        MapButton("◎  ME") { glide(max(zoom, 2f), null, follow = true) }
                    }
                    Row(Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                        MapButton("−") { glide((zoom / 1.5f).coerceAtLeast(0.35f), center, follow = center == null) }
                        Spacer(Modifier.width(8.dp))
                        MapButton("+") { glide((zoom * 1.5f).coerceAtMost(8f), center, follow = center == null) }
                    }
                }
                is WorldMaps.State.Building -> Column(
                    Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SkyText("CHARTING SKYRIM", 20.sp, Sky.Text, 4.sp)
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(progress = { s.progress }, color = Sky.Text, trackColor = Sky.Faint, modifier = Modifier.width(320.dp))
                    Spacer(Modifier.height(10.dp))
                    SkyText("Drawing the map from your game's terrain. This happens once.", 14.sp, Sky.Dim, 0.5.sp, light = true,
                        align = TextAlign.Center, maxLines = 2)
                }
                is WorldMaps.State.Failed -> MapMessage("The map couldn't be drawn", s.reason)
                WorldMaps.State.Unavailable -> MapMessage("No map for this place", p.worldspace.ifEmpty { p.location })
            }
        }
        MapFooter(p, selected, indoors, onFastTravel)
    }
}

@Composable
private fun MapMessage(title: String, detail: String) {
    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        SkyText(title.uppercase(), 18.sp, Sky.Text, 3.sp)
        Spacer(Modifier.height(8.dp))
        SkyText(detail, 14.sp, Sky.Dim, 0.5.sp, light = true, align = TextAlign.Center, maxLines = 3)
    }
}

@Composable
private fun MapButton(label: String, onTap: () -> Unit) {
    Box(
        Modifier.background(Color(0xCC000000)).clickable(onClick = onTap).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { SkyText(label, 16.sp, Sky.Text, 2.sp) }
}

/** The strip under Skyrim's map: the selected marker's name, else where you are. */
@Composable
private fun MapFooter(p: PlayerState, selected: MapMarker?, indoors: Boolean, onFastTravel: ((MapMarker) -> Unit)?) {
    Column(Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 16.dp, vertical = 4.dp)) {
        Rule()
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val (title, sub) = when {
                selected != null -> selected.name.ifEmpty { "Unknown" } to when (selected.kind) {
                    MarkerKind.LOCATION -> if (selected.discovered) "DISCOVERED" else "UNDISCOVERED"
                    MarkerKind.QUEST -> "QUEST TARGET"
                    MarkerKind.HOSTILE -> "HOSTILE"
                    MarkerKind.ALLY -> "FOLLOWER"
                }
                indoors -> p.location.ifEmpty { "Interior" } to "INSIDE"
                else -> p.location.ifEmpty { "Wilderness" } to p.worldspace.uppercase()
            }
            Column(Modifier.weight(1f)) {
                SkyFitText(title.uppercase(), 18.sp, 12.sp, Sky.Text, 3.sp)
                SkyText(sub, 11.sp, Sky.Dim, 2.sp)
            }
            val travel = selected?.takeIf { it.kind == MarkerKind.LOCATION && it.discovered && !it.interior && !it.hidden }
            if (travel != null && onFastTravel != null) {
                SkyText("FAST TRAVEL ›", 14.sp, Sky.Text, 2.sp, modifier = Modifier.clickable { onFastTravel(travel) }
                    .border(1.dp, Sky.Line).padding(horizontal = 12.dp, vertical = 7.dp))
            } else Legend()
        }
    }
}

@Composable
private fun Legend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(16.dp)) { questGlyph(center + Offset(0f, size.height * 0.15f), size.minDimension / 2.6f) }
        SkyText(" QUEST", 11.sp, Sky.Dim, 1.sp)
    }
}

// ================================================================ compass helpers

/** Bearing from the player to a point, degrees clockwise from north. */
internal fun bearingTo(p: PlayerState, x: Float, y: Float): Float =
    ((atan2((x - p.x).toDouble(), (y - p.y).toDouble()) * 180 / PI).toFloat() + 360f) % 360f

internal fun distanceTo(p: PlayerState, x: Float, y: Float) = hypot(x - p.x, y - p.y)

/** Zoom that fits the whole map into a ~538×330 view. */
private fun size(info: WorldMaps.Info): Float = minOf(538f / info.width, 330f / info.height) * 1.05f

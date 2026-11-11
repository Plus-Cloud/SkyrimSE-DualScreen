package com.skyrimayn

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Offsets from docs/shared-memory.md (v1). */
object Layout {
    const val MAGIC = 0x4E594153
    const val TOTAL = 8192
    const val PAGE = 4096
    const val SEQ = 12; const val FRAME = 16; const val UPTIME = 24; const val PID = 32
    const val RUNTIME = 36; const val SKSE = 40; const val FLAGS = 44; const val TICK_HZ = 48; const val SEQ_END = 60
    const val ACK_SEQ = 3072; const val ACK_RESULT = 3076; const val ACK_UPTIME = 3080
    const val CMD = 4096
    const val CMD_PING = 1
    const val FLAG_MMAP = 1; const val FLAG_FILE = 2
}

enum class LinkStatus { NO_PERMISSION, NO_FILE, WAITING, LIVE, STALE, ERROR }

data class LinkSnapshot(
    val status: LinkStatus = LinkStatus.NO_FILE,
    val message: String = "",
    val path: String = "",
    val frame: Long = 0,
    val uptimeMs: Long = 0,
    val pid: Int = 0,
    val runtimeVersion: Int = 0,
    val skseVersion: Int = 0,
    val mode: String = "",
    val tickHz: Int = 0,
    val updatesPerSec: Float = 0f,
    val lastUpdateAgoMs: Long = -1,
    val lastPingRttMs: Long = -1,
    val pingsOk: Int = 0,
    val pingsLost: Int = 0,
    val autoPing: Boolean = true,
)

fun formatGameVersion(v: Int): String =
    if (v == 0) "?" else "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 4) and 0xFFF}"

/**
 * Reads the plugin's state.bin and sends commands.
 * Test phase: plain file reads and writes on shared storage, which works whichever mode the plugin uses.
 * Inside SkyrimSE-DualScreen's own runtime this becomes an mmap of a private file.
 */
object Link {
    private const val PREFS = "link"
    private const val KEY_PATH = "path"
    const val DEFAULT_PATH = "/storage/emulated/0/Download/SkyrimAYN/state.bin"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(LinkSnapshot())
    val state: StateFlow<LinkSnapshot> = _state

    /** Live player state (null until the plugin reports one, e.g. on the main menu). */
    private val _player = MutableStateFlow<PlayerState?>(null)
    val player: StateFlow<PlayerState?> = _player
    /** Inventory and spells; refreshed whenever the plugin's list counter changes. */
    private val _lists = MutableStateFlow(GameLists(emptyList(), emptyList()))
    val lists: StateFlow<GameLists> = _lists
    private var lastListSeq = -1
    private val _showing = MutableStateFlow(false)
    /** True once Skyrim is visibly running (see [Proto.SHOWING]); false again when the game closes. */
    val showing: StateFlow<Boolean> = _showing
    private val _compass = MutableStateFlow<GameCompass?>(null)
    /** The game's own compass, refreshed every tick (null until plugin 0.11 reports it). */
    val compass: StateFlow<GameCompass?> = _compass
    private val _markers = MutableStateFlow(Markers(0, emptyList()))
    val markers: StateFlow<Markers> = _markers
    private var lastMarkersAt = 0L
    private val _quests = MutableStateFlow<List<Quest>>(emptyList())
    val quests: StateFlow<List<Quest>> = _quests
    private var lastQuestsAt = 0L

    private data class Cmd(val id: Int, val args: IntArray, val onDone: ((Boolean) -> Unit)?, val onResult: ((Int) -> Unit)? = null)
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<Cmd>()
    private var pendingCmd: Cmd? = null

    /** Sends a command to the plugin (docs/shared-memory.md). [onDone] runs on the link thread with success/failure. */
    fun send(id: Int, vararg args: Int, onDone: ((Boolean) -> Unit)? = null) {
        queue.add(Cmd(id, args, onDone))
    }

    /** Like [send], with the plugin's result code (0 ok, -1 no answer). */
    fun sendForResult(id: Int, vararg args: Int, onResult: (Int) -> Unit) {
        queue.add(Cmd(id, args, null, onResult))
    }

    // Pause held by the app (its menus are open), refreshed so the plugin lets go if the app vanishes.
    @Volatile private var pauseWanted = false
    private var pauseWritten = false
    private var pauseBeat = 0
    private var lastPauseWrite = 0L
    fun setPause(on: Boolean) { pauseWanted = on }

    private fun writePause(f: File, now: Long) {
        if (!pauseWanted && !pauseWritten) return
        if (pauseWanted && now - lastPauseWrite < 400) return
        try {
            val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(if (pauseWanted) 1 else 0).put(0).putShort(0).putInt(++pauseBeat)
            RandomAccessFile(f, "rw").use { raf -> raf.seek(4120); raf.write(bb.array()) }
            pauseWritten = pauseWanted
            lastPauseWrite = now
        } catch (_: Exception) {}
    }

    @Volatile private var path = DEFAULT_PATH
    @Volatile private var customPath = false
    @Volatile private var autoPing = true
    private lateinit var appContext: Context

    // Ping bookkeeping (link thread only).
    private var nextCmdSeq = -1L
    private var pendingSeq = -1L
    private var pendingArg = 0
    private var pendingSentAt = 0L
    private var lastAutoPingAt = 0L
    @Volatile private var pingRequested = false
    private var rtt = -1L
    private var ok = 0
    private var lost = 0

    // Heartbeat bookkeeping.
    private var lastFrame = -1L
    private var lastFrameChangeAt = 0L
    private var windowStart = 0L
    private var windowFrames = 0L
    private var ups = 0f

    fun start(context: Context) {
        appContext = context.applicationContext
        val saved = appContext.getSharedPreferences(PREFS, 0).getString(KEY_PATH, null)
        customPath = saved != null
        path = saved ?: DEFAULT_PATH
        GameFolder.init(appContext)
        demo = app.gamenative.BuildConfig.DEBUG && appContext.getSharedPreferences(PREFS, 0).getBoolean("demo", false)
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                _state.value = tick()
                delay(33)
            }
        }
        // The compass on its own faster loop: the plugin mirrors it every game frame.
        scope.launch {
            while (isActive) {
                if (_state.value.status == LinkStatus.LIVE) readCompass(File(path))?.let { c ->
                    // The plugin rewrites the compass every game frame; only pass on real changes (plus a
                    // once-a-second freshness tick), so the companion doesn't redraw 60 times a second for nothing.
                    val old = _compass.value
                    if (old == null || c.copy(at = old.at) != old || c.at - old.at > 1000) _compass.value = c
                }
                delay(16)
            }
        }
    }

    fun setPath(p: String) {
        path = p.trim()
        customPath = true
        appContext.getSharedPreferences(PREFS, 0).edit().putString(KEY_PATH, path).apply()
        resetSession()
    }

    fun setAutoPing(on: Boolean) { autoPing = on }
    fun pingNow() { pingRequested = true }

    /** Looks for SkyrimAYN/state.bin anywhere on shared storage. */
    suspend fun find(): String? = withContext(Dispatchers.IO) {
        val root = File("/storage/emulated/0")
        root.walkTopDown()
            .onEnter { it.name != "Android" && !it.name.startsWith(".") }
            .maxDepth(9)
            .firstOrNull { it.name == "state.bin" && it.parentFile?.name == "SkyrimAYN" }
            ?.absolutePath
    }

    private fun resetSession() {
        nextCmdSeq = -1; pendingSeq = -1; rtt = -1; ok = 0; lost = 0
        lastFrame = -1; ups = 0f; windowFrames = 0
    }

    @Volatile private var demo = false

    /** For tests and previews: show this state without a game. */
    internal fun injectForTest(p: PlayerState?, lists: GameLists, markers: Markers, quests: List<Quest>) {
        _player.value = p; _lists.value = lists; _markers.value = markers; _quests.value = quests
    }

    private fun tick(): LinkSnapshot {
        if (demo) {
            _player.value = DemoData.player
            _lists.value = DemoData.lists
            _markers.value = DemoData.markers
            _quests.value = DemoData.quests
            return LinkSnapshot(status = LinkStatus.LIVE, message = "Demo data", path = path)
        }
        // The bundled plugin writes <game folder>\SkyrimAYN\state.bin unless a custom path was set.
        if (!customPath) GameFolder.dir.value?.let { path = "$it/SkyrimAYN/state.bin" }
        val now = SystemClock.elapsedRealtime()
        val base = LinkSnapshot(path = path, autoPing = autoPing, lastPingRttMs = rtt, pingsOk = ok, pingsLost = lost)

        if (!Environment.isExternalStorageManager()) {
            return base.copy(status = LinkStatus.NO_PERMISSION, message = "All-files access needed to read state.bin")
        }
        val f = File(path)
        if (!f.exists()) return base.copy(status = LinkStatus.NO_FILE, message = "state.bin not found. Start Skyrim with SKSE and the plugin.")

        val page = try { readPage0(f) } catch (e: Exception) {
            return base.copy(status = LinkStatus.ERROR, message = "Read failed: ${e.message}")
        } ?: return base.copy(status = LinkStatus.WAITING, message = "Plugin is mid-write, retrying")

        if (page.getInt(0) != Layout.MAGIC) {
            return base.copy(status = LinkStatus.WAITING, message = "File exists but has no SAYN header yet")
        }

        val frame = page.getLong(Layout.FRAME)
        if (frame != lastFrame) {
            if (lastFrame >= 0) windowFrames += (frame - lastFrame).coerceAtLeast(0)
            lastFrame = frame
            lastFrameChangeAt = now
        }
        if (now - windowStart >= 1000) {
            ups = windowFrames * 1000f / (now - windowStart).coerceAtLeast(1)
            windowFrames = 0
            windowStart = now
        }

        // Pings.
        val ackSeq = page.getInt(Layout.ACK_SEQ).toLong() and 0xFFFFFFFFL
        val ackResult = page.getInt(Layout.ACK_RESULT)
        if (nextCmdSeq < 0) nextCmdSeq = maxOf(ackSeq, readCmdSeq(f)) + 1
        if (pendingSeq >= 0) {
            val cmd = pendingCmd
            if (ackSeq == pendingSeq) {
                if (cmd != null) { cmd.onDone?.invoke(ackResult == 0); cmd.onResult?.invoke(ackResult) }
                else if (ackResult == pendingArg) { rtt = now - pendingSentAt; ok++ } else lost++
                pendingSeq = -1; pendingCmd = null
            } else if (now - pendingSentAt > 2000) {
                if (cmd != null) { cmd.onDone?.invoke(false); cmd.onResult?.invoke(-1) } else lost++
                pendingSeq = -1; pendingCmd = null
            }
        }
        val live = now - lastFrameChangeAt < 1000
        writePause(f, now)
        if (pendingSeq < 0 && live) queue.poll()?.let { cmd ->
            val a = IntArray(4) { cmd.args.getOrElse(it) { 0 } }
            if (writeCommand(f, nextCmdSeq, cmd.id, a[0], a[1], a[2], a[3])) {
                pendingSeq = nextCmdSeq; pendingCmd = cmd; pendingSentAt = now
                nextCmdSeq++
            }
        }
        val wantPing = pingRequested || (autoPing && live && now - lastAutoPingAt >= 1000)
        if (pendingSeq < 0 && wantPing) {
            pingRequested = false
            lastAutoPingAt = now
            val arg = (now and 0x7FFFFFFF).toInt()
            if (writeCommand(f, nextCmdSeq, Layout.CMD_PING, arg)) {
                pendingSeq = nextCmdSeq; pendingArg = arg; pendingSentAt = now
                nextCmdSeq++
            }
        }

        val flags = page.getInt(Layout.FLAGS)
        _showing.value = live && flags and Proto.SHOWING != 0
        // Only from a live heartbeat: state.bin keeps the last session's flags until the next plugin starts.
        if (live && flags and Proto.QUITTING != 0) SkyrimEngine.onGameQuitRequested()
        val v2 = (page.getShort(4).toInt() and 0xFFFF) >= 2
        // Rounded to what the companion shows (game minutes, whole seconds of effects): the game clock and
        // effect timers tick every frame, which would otherwise redraw the character page 30 times a second.
        _player.value = if (v2 && live && flags and Proto.PLAYER_VALID != 0) Proto.player(page).let { p ->
            p.copy(gameHour = kotlin.math.floor(p.gameHour * 60f) / 60f,
                effects = p.effects.map { it.copy(secondsLeft = if (it.secondsLeft < 0f) -1f else kotlin.math.ceil(it.secondsLeft)) })
        } else null
        if (v2 && flags and Proto.LISTS_VALID != 0) {
            val listSeq = page.getInt(Proto.LIST_SEQ_COPY)
            if (listSeq != lastListSeq) readLists(f)?.let { ItemAge.note(it.items); _lists.value = it; lastListSeq = listSeq }
        } else if (!live) {
            lastListSeq = -1
        }
        if (!live) _compass.value = null
        if (flags and Proto.MARKERS_VALID != 0 && live && now - lastMarkersAt >= 200) {
            readMarkers(f)?.let { _markers.value = it; lastMarkersAt = now }
        }
        if (flags and Proto.QUESTS_VALID != 0 && live && now - lastQuestsAt >= 1500) {
            readQuests(f)?.let { _quests.value = it; lastQuestsAt = now }
        }
        return base.copy(
            status = if (live) LinkStatus.LIVE else LinkStatus.STALE,
            message = if (live) "Plugin connected" else "No heartbeat for ${(now - lastFrameChangeAt) / 1000}s. Is the game paused or closed?",
            frame = frame,
            uptimeMs = page.getLong(Layout.UPTIME),
            pid = page.getInt(Layout.PID),
            runtimeVersion = page.getInt(Layout.RUNTIME),
            skseVersion = page.getInt(Layout.SKSE),
            mode = when { flags and Layout.FLAG_MMAP != 0 -> "mmap"; flags and Layout.FLAG_FILE != 0 -> "file"; else -> "?" },
            tickHz = page.getInt(Layout.TICK_HZ),
            updatesPerSec = ups,
            lastUpdateAgoMs = now - lastFrameChangeAt,
            lastPingRttMs = rtt, pingsOk = ok, pingsLost = lost,
        )
    }

    /** Returns page 0 once the seqlock reads consistent, or null after 3 torn reads. */
    private fun readPage0(f: File): ByteBuffer? {
        val buf = ByteArray(Layout.PAGE)
        repeat(3) {
            RandomAccessFile(f, "r").use { it.readFully(buf) }
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val seq = bb.getInt(Layout.SEQ)
            if (seq and 1 == 0 && seq == bb.getInt(Layout.SEQ_END)) return bb
        }
        return null
    }

    private fun readCmdSeq(f: File): Long = try {
        RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < Layout.CMD + 4) return 0
            raf.seek(Layout.CMD.toLong())
            val b = ByteArray(4); raf.readFully(b)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        }
    } catch (_: Exception) { 0 }

    private fun readLists(f: File): GameLists? = try {
        RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < Proto.V2_TOTAL) return null
            val buf = ByteArray(Proto.V2_TOTAL)
            raf.readFully(buf)
            Proto.lists(Proto.wrap(buf))
        }
    } catch (_: Exception) { null }

    private fun readCompass(f: File): GameCompass? = try {
        RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < Proto.COMPASS_ENTRIES + Proto.MAX_COMPASS * Proto.COMPASS_SIZE) return null
            val buf = ByteArray(64 + Proto.MAX_COMPASS * Proto.COMPASS_SIZE)
            repeat(3) {
                raf.seek(Proto.COMPASS.toLong()); raf.readFully(buf)
                val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                val seq = b.getInt(0)
                if (b.getInt(28) != 1) return null
                if (seq and 1 != 0) return@repeat
                val n = b.getInt(4).coerceIn(0, Proto.MAX_COMPASS)
                val marks = List(n) { i ->
                    val o = 64 + i * Proto.COMPASS_SIZE
                    CompassMark(b.getFloat(o), b.getFloat(o + 4), b.getShort(o + 8).toInt() and 0xFFFF, b.getFloat(o + 12))
                }
                fun fr(i: Int) = b.getShort(16 + i * 2).toInt() and 0xFFFF
                raf.seek(Proto.COMPASS.toLong())
                val again = ByteArray(4); raf.readFully(again)
                if (ByteBuffer.wrap(again).order(ByteOrder.LITTLE_ENDIAN).int != seq) return@repeat
                return GameCompass(b.getFloat(8), b.getFloat(12), marks, fr(0), fr(1), fr(2), fr(3), fr(4), fr(5), System.currentTimeMillis())
            }
            null
        }
    } catch (_: Exception) { null }

    private fun readMarkers(f: File): Markers? = try {
        RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < Proto.V3_TOTAL) return null
            val buf = ByteArray(Proto.V3_TOTAL - Proto.MARKERS)
            raf.seek(Proto.MARKERS.toLong())
            raf.readFully(buf)
            Proto.markers(Proto.wrap(buf))
        }
    } catch (_: Exception) { null }

    private fun readQuests(f: File): List<Quest>? = try {
        RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < Proto.V4_TOTAL) return null
            val buf = ByteArray(Proto.V4_TOTAL - Proto.QUESTS)
            raf.seek(Proto.QUESTS.toLong())
            raf.readFully(buf)
            Proto.quests(Proto.wrap(buf))
        }
    } catch (_: Exception) { null }

    private fun writeCommand(f: File, seq: Long, id: Int, arg0: Int, arg1: Int = 0, arg2: Int = 0, arg3: Int = 0): Boolean = try {
        val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(seq.toInt()).putInt(id).putInt(arg0).putInt(arg1).putInt(arg2).putInt(arg3)
        RandomAccessFile(f, "rw").use { raf ->
            raf.seek(Layout.CMD.toLong())
            raf.write(bb.array())
        }
        true
    } catch (_: Exception) { false }
}

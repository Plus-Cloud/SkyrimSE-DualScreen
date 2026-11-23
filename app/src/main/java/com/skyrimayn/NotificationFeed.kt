package com.skyrimayn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The game's top-screen notices ("Quest started", "Skill increased", "Location discovered", level ups),
 * mirrored on the bottom screen so they're still seen with the game's HUD hidden. Worked out by comparing
 * the live game state over time; the first reading after a save loads is the baseline, not news.
 */
object NotificationFeed {
    data class Note(val id: Long, val title: String, val text: String)

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var nextId = 0L

    private var who: String? = null
    private var level = -1
    private var skills: List<Int> = emptyList()
    private var quests: Map<Int, Quest>? = null
    private var discovered: Set<Int>? = null
    private var world = 0

    fun start(context: android.content.Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        // Once a save is loaded, report SKSE plugins that didn't load (usually: not updated for this game version).
        scope.launch {
            Link.player.collect { p -> if (p != null) reportSkse(app) }
        }
        // ...and as soon as the game is on screen: SKSE has finished loading plugins by then.
        scope.launch {
            SkyrimEngine.gameWindow.collect { shown -> if (shown) { delay(3000); reportSkse(app) } }
        }
        scope.launch {
            Link.player.collect { p ->
                if (p == null) return@collect
                if (p.name != who) { who = p.name; level = -1; skills = emptyList(); quests = null; discovered = null }
                if (level in 1 until p.level) post("LEVEL INCREASED", "You are now level ${p.level}")
                level = p.level
                val now = p.skillLevels.map { it.toInt() }
                if (skills.size == now.size) now.forEachIndexed { i, v ->
                    if (v > skills[i]) post("SKILL INCREASED", "${Skills.names[i]} ${v}")
                }
                skills = now
            }
        }
        scope.launch {
            Link.quests.collect { list ->
                val prev = quests
                val cur = list.filter { it.name.isNotEmpty() }.associateBy { it.formId }
                if (prev != null) for ((id, q) in cur) {
                    val before = prev[id]
                    when {
                        before == null && !q.completed -> post("QUEST STARTED", q.name)
                        before != null && !before.completed && q.completed -> post("QUEST COMPLETED", q.name)
                        before != null && !q.completed -> {
                            val known = before.objectives.map { it.index }.toSet()
                            q.objectives.firstOrNull { it.state == ObjectiveState.ACTIVE && it.index !in known }?.let { post(q.name.uppercase(), it.text) }
                        }
                    }
                }
                if (list.isNotEmpty()) quests = cur
            }
        }
        scope.launch {
            Link.markers.collect { m ->
                if (m.worldspaceId != world) { world = m.worldspaceId; discovered = null }
                val cur = m.list.filter { it.kind == MarkerKind.LOCATION && it.discovered }.map { it.formId }.toSet()
                val prev = discovered
                if (prev != null) m.list.filter { it.kind == MarkerKind.LOCATION && it.discovered && it.formId !in prev }
                    .forEach { post("LOCATION DISCOVERED", it.name) }
                if (m.list.isNotEmpty()) discovered = cur
            }
        }
    }

    private var skseChecked = 0L
    @Synchronized private fun reportSkse(ctx: android.content.Context) {
        val log = java.io.File(ContainerRootLocator.root(ctx), ".wine/drive_c/users/xuser/Documents/My Games/Skyrim Special Edition/SKSE/skse64.log")
        if (!log.isFile || log.lastModified() == skseChecked) return
        // Only this launch's log: the app can open with the last game's log still there (or before SKSE has
        // rewritten it), and that would report mods that have since been fixed or removed.
        val since = SkyrimEngine.lastLaunchAt
        if (since == 0L || log.lastModified() < since) return
        skseChecked = log.lastModified()
        val failed = skseFailures(runCatching { log.readLines() }.getOrDefault(emptyList()))
        if (failed.isEmpty()) return
        failed.take(3).forEach { post("MOD DIDN'T LOAD", it, showMs = 20000) }
        // SKSE asks "Exit game?" in a Windows box nobody can press on the Thor; our dinput8.dll answers "keep
        // playing", so this is where the player learns about it, and a report carries the reasons.
        Problems.record("Mods didn't load", failed.joinToString("\n"))
    }

    /**
     * Plain-language reasons for the SKSE plugins that failed, from skse64.log lines such as
     * `plugin EngineFixes.dll (00000001 EngineFixes 00000007) couldn't load plugin 126 (handle 0)`.
     */
    internal fun skseFailures(lines: List<String>): List<String> {
        val line = Regex("""^plugin (.+?\.dll) \([0-9A-Fa-f]{8} .*? [0-9A-Fa-f]{8}\) (.+?) (-?\d+) \(handle \d+\)""", RegexOption.IGNORE_CASE)
        return lines.mapNotNull { l ->
            val m = line.find(l.trim()) ?: return@mapNotNull null
            val (dll, state, codeText) = m.destructured
            if (dll.equals("SkyrimAYN.dll", true)) return@mapNotNull null
            val code = codeText.toLongOrNull() ?: 0L
            val s = state.lowercase()
            when {
                s == "no version data" -> null   // logged but not an error
                dll.equals("EngineFixes.dll", true) && code == 126L ->
                    "SSE Engine Fixes is missing its Part 2: the files that go next to SkyrimSE.exe"
                "address library" in s -> "$dll needs the newest Address Library for SKSE Plugins (All in one, Anniversary Edition)"
                "newer script extender" in s -> "$dll needs a newer Script Extender (SKSE)"
                "le plugin" in s -> "$dll is for the original 2011 Skyrim, not Special Edition"
                "incompatible" in s || "earlier than" in s || "version" in s ->
                    "$dll isn't made for Skyrim ${GameFolder.SUPPORTED_VERSION} yet"
                "couldn't load plugin" in s && code == 126L -> "$dll is missing another file or mod it needs"
                "couldn't load plugin" in s && code == 193L -> "$dll is damaged or isn't a 64-bit Windows plugin"
                "does not appear to be an skse plugin" in s -> "$dll in Data/SKSE/Plugins isn't an SKSE plugin"
                "crashed" in s || "fatal" in s -> "$dll crashed while loading"
                else -> "$dll didn't load ($state)"
            }
        }.distinct()
    }

    /** A Steam achievement unlocked in game (see [Achievements.onUnlocked]). */
    fun achievement(name: String) = post("ACHIEVEMENT UNLOCKED", name)

    private fun post(title: String, text: String, showMs: Long = 6000) {
        val n = Note(nextId++, title, text)
        _notes.value = (_notes.value + n).takeLast(3)
        scope.launch {
            delay(showMs)
            _notes.value = _notes.value.filter { it.id != n.id }
        }
    }
}

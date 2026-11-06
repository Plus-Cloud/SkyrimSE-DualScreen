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
    private fun reportSkse(ctx: android.content.Context) {
        val log = java.io.File(ContainerRootLocator.root(ctx), ".wine/drive_c/users/xuser/Documents/My Games/Skyrim Special Edition/SKSE/skse64.log")
        if (!log.isFile || log.lastModified() == skseChecked) return
        skseChecked = log.lastModified()
        val bad = runCatching { log.readLines() }.getOrDefault(emptyList()).filter { l ->
            val t = l.lowercase()
            t.contains("plugin") && (t.contains("incompatible") || t.contains("couldn't load") || t.contains("could not load") ||
                t.contains("disabled") || t.contains("not compatible") || t.contains("failed"))
        }.mapNotNull { Regex("([\\w .+-]+\\.dll)", RegexOption.IGNORE_CASE).find(it)?.value?.trim() }
            .filter { !it.equals("SkyrimAYN.dll", true) }.distinct()
        bad.take(3).forEach { post("MOD DIDN'T LOAD", "$it isn't made for your game version (1.7.104) yet") }
    }

    private fun post(title: String, text: String) {
        val n = Note(nextId++, title, text)
        _notes.value = (_notes.value + n).takeLast(3)
        scope.launch {
            delay(6000)
            _notes.value = _notes.value.filter { it.id != n.id }
        }
    }
}

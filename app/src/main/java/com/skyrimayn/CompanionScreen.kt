package com.skyrimayn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/** In-game companion, styled after Skyrim's own menus (see SkyUi.kt). */
private enum class Tab(val label: String) { CHARACTER("CHARACTER"), QUESTS("QUESTS"), ITEMS("ITEMS"), MAGIC("MAGIC"), MAP("MAP") }

@Composable
fun CompanionScreen(onLauncher: () -> Unit, startTab: String? = null, startSettings: Boolean = false) {
    var tab by remember { mutableStateOf(startTab?.let { n -> Tab.entries.firstOrNull { it.name == n } } ?: Tab.CHARACTER) }
    var toast by remember { mutableStateOf<String?>(null) }
    var mapFocus by remember { mutableStateOf<Offset?>(null) }
    var showSettings by remember { mutableStateOf(startSettings) }
    LaunchedEffect(toast) { if (toast != null) { delay(1800); toast = null } }
    val player by Link.player.collectAsState()
    val lists by Link.lists.collectAsState()
    val inGame = player != null
    // Keep the game's HUD as the player chose (the plugin forgets on restart).
    LaunchedEffect(inGame) { while (inGame) { SkyrimEngine.applyHud(); delay(5000) } }
    LaunchedEffect(Unit) { SkyrimEngine.menuRequests.collect { showSettings = true } }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { GameSounds.init(context); NotificationFeed.start(context); GameIcons.load(context) }
    val iconSheet by GameIcons.sheet.collectAsState()   // redraw once the game's icons are ready
    // Menus pause the game like the game's own (the Character page and quick bar don't, so they stay quick).
    val values by LauncherSettings.values.collectAsState()
    val pauseOn = (values[LauncherSettings.pauseMenus.key] ?: LauncherSettings.pauseMenus.default) == "On"
    val paused = inGame && pauseOn && (showSettings || tab in setOf(Tab.QUESTS, Tab.ITEMS, Tab.MAGIC, Tab.MAP))
    LaunchedEffect(paused) { Link.setPause(paused) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Link.setPause(false) } }
    var walkthrough by remember { mutableStateOf(!context.getSharedPreferences("companion", 0).getBoolean("walkthrough_done", false)) }

    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF161616), Sky.Bg), radius = 1400f))) {
        Column(Modifier.fillMaxSize()) {
            TabStrip(tab, player?.name.orEmpty(), paused, { if (it != Tab.MAP) mapFocus = null; if (it != tab) GameSounds.play(GameSounds.Cue.TAB); tab = it },
                onSettings = { GameSounds.play(GameSounds.Cue.OK); showSettings = true })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val p = player
                if (p == null) Waiting() else androidx.compose.animation.Crossfade(tab, animationSpec = androidx.compose.animation.core.tween(150), label = "tab") { t ->
                    when (t) {
                        Tab.CHARACTER -> CharacterTab(p, onMap = { mapFocus = null; tab = Tab.MAP })
                        Tab.QUESTS -> QuestsTab(onShowOnMap = { mapFocus = it; tab = Tab.MAP })
                        Tab.ITEMS -> ItemsTab(p, lists) { toast = it }
                        Tab.MAGIC -> MagicTab(p, lists) { toast = it }
                        Tab.MAP -> MapTab(p, mapFocus) { m ->
                            // Fast travel: unpause first, like closing the map, then travel.
                            Link.setPause(false)
                            GameSounds.play(GameSounds.Cue.OK)
                            Link.sendForResult(Proto.CMD_FAST_TRAVEL, m.formId) { r ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    toast = when (r) {
                                        0 -> "Travelling to ${m.name}"
                                        2 -> "You cannot fast travel when enemies are nearby"
                                        3 -> "You haven't discovered ${m.name} yet"
                                        else -> "Couldn't fast travel there"
                                    }
                                    if (r == 0) tab = Tab.CHARACTER
                                }
                            }
                        }
                    }
                }
            }
            if (player != null) QuickBar(lists) { toast = it }
        }
        toast?.let { Notice(it) }
        if (player != null) Feed(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = if (tab == Tab.MAP) 70.dp else 70.dp))
        if (showSettings) InGameSettings(onClose = { GameSounds.play(GameSounds.Cue.CANCEL); showSettings = false }) { toast = it }
        if (walkthrough && player != null) Walkthrough {
            walkthrough = false
            context.getSharedPreferences("companion", 0).edit().putBoolean("walkthrough_done", true).apply()
        }
    }
}

/** Skyrim-style corner notification ("Iron Sword equipped"). */
@Composable
private fun BoxScope.Notice(text: String) {
    SkyText(text, 16.sp, Sky.Text, 1.sp, modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 52.dp)
        .background(Color(0xCC000000)).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun TabStrip(current: Tab, name: String, paused: Boolean, onSelect: (Tab) -> Unit, onSettings: () -> Unit) {
    val haptic = rememberHaptics()
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { t ->
                val sel = t == current
                Box(
                    Modifier.weight(if (t == Tab.CHARACTER) 1.35f else 1f).clickable {
                        if (t != current) haptic(HapticFeedbackType.TextHandleMove)
                        onSelect(t)
                    }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // The first tab is the character, by name (long names shrink to fit).
                    val label = if (t == Tab.CHARACTER && name.isNotBlank()) name.trim().let { n -> if (n.length > 14) n.substringBefore(' ') else n }.uppercase() else t.label
                    SkyFitText(label, if (sel) 15.sp else 13.sp, 9.sp, if (sel) Sky.Text else Sky.Dim, if (sel) 2.sp else 1.5.sp, modifier = Modifier.padding(horizontal = 2.dp))
                }
            }
            if (paused) SkyText("❚❚", 10.sp, Sky.Dim, 0.sp, modifier = Modifier.padding(start = 2.dp))
            SkyText("⚙", 16.sp, Sky.Dim, 0.sp, modifier = Modifier.clickable(onClick = onSettings).padding(horizontal = 10.dp, vertical = 9.dp))
        }
        Rule(Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun Waiting() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        SkyText("THE ELDER SCROLLS V", 13.sp, Sky.Dim, 4.sp)
        Spacer(Modifier.height(4.dp))
        SkyText("SKYRIM", 36.sp, Sky.Text, 10.sp)
        Spacer(Modifier.height(12.dp))
        SkyText("Load a save to begin", 15.sp, Sky.Dim, 1.sp, light = true)
    }
}

// ================================================================ CHARACTER

@Composable
private fun CharacterTab(p: PlayerState, onMap: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 4.dp)) {
        CompassBar(p, Modifier.fillMaxWidth().height(40.dp))
        // Header like the Stats menu: name, level and race with the XP bar; where and when on the right.
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.weight(1f).padding(end = 18.dp)) {
                SkyFitText(p.name.ifEmpty { "Dragonborn" }.uppercase(), 21.sp, 11.sp, Sky.Text, 3.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkyText("LEVEL ${p.level}", 11.sp, Sky.Body, 2.sp)
                    if (p.race.isNotEmpty()) SkyText("   ·   ${p.race.uppercase()}", 11.sp, Sky.Dim, 2.sp)
                    if (p.inCombat) SkyText("   ·   IN COMBAT", 11.sp, Sky.Health, 2.sp)
                }
                HudBar(p.xpProgress.coerceIn(0f, 1f), Color(0xFFD8D8D8), Modifier.width(190.dp).height(9.dp).padding(top = 3.dp))
            }
            Column(Modifier.widthIn(max = 230.dp), horizontalAlignment = Alignment.End) {
                SkyFitText(p.location.ifEmpty { p.worldspace.ifEmpty { "Skyrim" } }.uppercase(), 15.sp, 10.sp, Sky.Text, 2.5.sp)
                SkyText(timeLabel(p), 10.sp, Sky.Body, 1.5.sp)
                SkyText(dateLabel(p), 10.sp, Sky.Dim, 1.5.sp)
            }
        }
        Rule(Modifier.padding(vertical = 5.dp), color = Sky.Faint)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(end = 12.dp)) {
                StatLine("HEALTH", p.health, p.healthMax, Sky.Health)
                StatLine("MAGICKA", p.magicka, p.magickaMax, Sky.Magicka)
                StatLine("STAMINA", p.stamina, p.staminaMax, Sky.Stamina)
                Spacer(Modifier.height(5.dp))
                Row {
                    // As you hold them: left hand on the left, right hand on the right, shout between.
                    EquipCell("LEFT HAND", p.leftHand, Modifier.weight(1f), align = Alignment.Start)
                    EquipCell("SHOUT / POWER", p.shout, Modifier.weight(1f), align = Alignment.CenterHorizontally)
                    EquipCell("RIGHT HAND", p.rightHand, Modifier.weight(1f), align = Alignment.End)
                }
                EffectsLine(p)
                SkillsPanel(p, Modifier.fillMaxWidth().weight(1f))
            }
            // The local map, right of the bars. Tap opens the full map.
            Column(Modifier.width(172.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                MiniMap(p, Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp), onTap = onMap)
                SkyText("LOCAL MAP  ›", 10.sp, Sky.Dim, 2.sp, modifier = Modifier.padding(top = 3.dp).clickable(onClick = onMap))
            }
        }
    }
}

@Composable
private fun EquipCell(label: String, value: String, modifier: Modifier, warn: Boolean = false, align: Alignment.Horizontal = Alignment.Start) {
    val ta = when (align) { Alignment.End -> TextAlign.End; Alignment.CenterHorizontally -> TextAlign.Center; else -> TextAlign.Start }
    Column(modifier.padding(vertical = 2.dp, horizontal = 2.dp), horizontalAlignment = align) {
        SkyText(label, 8.sp, Sky.Dim, 1.5.sp, align = ta)
        // Long names ("Daedric Warhammer of Debilitation") wrap to a second line, a little smaller, instead of clipping.
        val v = value.ifEmpty { "—" }
        SkyText(v, if (v.length > 18) 11.sp else 13.sp, when { warn -> Sky.Health; value.isEmpty() -> Sky.Faint; else -> Sky.Body }, 0.2.sp,
            modifier = Modifier.fillMaxWidth(), align = ta, maxLines = 2)
    }
}

/**
 * Active effects in one line, harmful ones in red, like the Active Effects menu. As many as fit are shown,
 * then "+N MORE"; tapping the line lists them all, so a long list never runs off the edge.
 */
@Composable
private fun EffectsLine(p: PlayerState) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        SkyText("EFFECTS", 8.sp, Sky.Dim, 1.5.sp, modifier = Modifier.padding(end = 8.dp))
        if (p.effects.isEmpty()) SkyText("None", 11.sp, Sky.Faint, 0.3.sp, light = true, modifier = Modifier.weight(1f))
        else FittingEffects(p.effects, Modifier.weight(1f).clickable { open = true })
        // Gold and carry weight, like the bottom of the game's inventory.
        SkyText("GOLD ", 8.sp, Sky.Dim, 1.5.sp, modifier = Modifier.padding(start = 8.dp)); SkyText("${p.gold}", 12.sp, Sky.Text, 0.3.sp)
        SkyText("   CARRY ", 8.sp, Sky.Dim, 1.5.sp)
        SkyText("${p.carryWeight.roundToInt()}/${p.carryMax.roundToInt()}", 12.sp, if (p.carryMax > 0 && p.carryWeight > p.carryMax) Sky.Health else Sky.Text, 0.3.sp)
    }
    if (open) androidx.compose.ui.window.Dialog(onDismissRequest = { open = false }) {
        Column(Modifier.widthIn(max = 520.dp).background(Color(0xF2000000)).border(1.dp, Sky.Frame)
            .clickable { open = false }.padding(20.dp)) {
            SkyText("ACTIVE EFFECTS", 14.sp, Sky.Text, 3.sp)
            Rule(Modifier.padding(vertical = 8.dp), color = Sky.Faint)
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for (e in p.effects) Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    SkyText(e.name, 14.sp, if (e.harmful) Color(0xFFD9675E) else Sky.Body, 0.3.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    SkyText(if (e.secondsLeft > 0) timeLeft(e.secondsLeft) else "", 12.sp, Sky.Dim, 0.5.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
            if (p.effects.isEmpty()) SkyText("None", 13.sp, Sky.Faint, 0.3.sp)
        }
    }
}

/** Lays out effects left to right until the space runs out, ending with "+N MORE" for the rest. */
@Composable
private fun FittingEffects(effects: List<ActiveEffect>, modifier: Modifier) {
    androidx.compose.ui.layout.SubcomposeLayout(modifier.clipToBounds()) { c ->
        val free = c.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity)
        @Composable fun Effect(i: Int, shrink: Boolean) {
            val e = effects[i]
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (i > 0) SkyText("  ·  ", 11.sp, Sky.Faint, 0.sp)
                SkyText(e.name, 11.sp, if (e.harmful) Color(0xFFD9675E) else Sky.Body, 0.3.sp, maxLines = 1,
                    modifier = if (shrink) Modifier.weight(1f, fill = false) else Modifier)
                if (e.secondsLeft > 0) SkyText(" ${timeLeft(e.secondsLeft)}", 9.sp, Sky.Dim, 0.5.sp)
            }
        }
        val items = ArrayList(effects.indices.map { i -> subcompose("e$i") { Effect(i, false) }.first().measure(free) })
        // How many fit, keeping room for the "+N MORE" label whenever some are left out.
        val labels = HashMap<Int, androidx.compose.ui.layout.Placeable>()
        fun more(n: Int) = labels.getOrPut(n) { subcompose("more$n") { SkyText("   +$n MORE", 9.sp, Sky.Dim, 1.5.sp) }.first().measure(free) }
        var shown = items.size
        var used = items.sumOf { it.width }
        var tail: androidx.compose.ui.layout.Placeable? = null
        if (used > c.maxWidth) {
            shown = 0; used = 0
            while (shown < items.size) {
                val label = more(items.size - shown - 1)
                if (used + items[shown].width + (if (shown + 1 < items.size) label.width else 0) > c.maxWidth) break
                used += items[shown].width; shown++
            }
            // Always show at least the first effect, shortened with "…" if it's long on its own.
            if (shown == 0) {
                val label = more(items.size - 1)
                items[0] = subcompose("e0fit") { Effect(0, true) }.first()
                    .measure(c.copy(minWidth = 0, maxWidth = (c.maxWidth - label.width).coerceAtLeast(0)))
                shown = 1
            }
            tail = more(items.size - shown)
        }
        val h = (items.take(shown) + listOfNotNull(tail)).maxOfOrNull { it.height } ?: 0
        layout(c.maxWidth, h) {
            var x = 0
            for (i in 0 until shown) { items[i].place(x, (h - items[i].height) / 2); x += items[i].width }
            tail?.place(x, (h - tail.height) / 2)
        }
    }
}

private fun timeLeft(s: Float): String = when {
    s < 60 -> "${s.toInt()}s"
    s < 3600 -> "${(s / 60).toInt()}m"
    else -> "${(s / 3600).toInt()}h"
}

/** The 18 skills in the Skills menu's three groups: level, and progress to the next as a hairline. */
@Composable
private fun SkillsPanel(p: PlayerState, modifier: Modifier) {
    if (p.skillLevels.size < 18) return
    Column(modifier) {
        Rule(color = Sky.Faint)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            listOf(Skills.warrior, Skills.thief, Skills.mage).forEachIndexed { col, group ->
                Column(Modifier.weight(1f).padding(start = if (col == 0) 0.dp else 8.dp)) {
                    group.forEach { i ->
                        val lvl = p.skillLevels[i].roundToInt()
                        Column(Modifier.padding(vertical = 0.5.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                SkyText(Skills.names[i].uppercase(), 7.5.sp, Sky.Dim, 0.8.sp, modifier = Modifier.weight(1f))
                                SkyText("$lvl", 9.5.sp, if (lvl >= 50) Sky.Text else Sky.Body, 0.sp)
                            }
                            Canvas(Modifier.fillMaxWidth().height(1.dp)) {
                                drawRect(Color(0x22FFFFFF))
                                drawRect(Color(0x99FFFFFF), size = androidx.compose.ui.geometry.Size(size.width * p.skillProgress[i], size.height))
                            }
                        }
                    }
                }
            }
        }
    }
}

private val months = listOf(
    "Morning Star", "Sun's Dawn", "First Seed", "Rain's Hand", "Second Seed", "Midyear",
    "Sun's Height", "Last Seed", "Hearthfire", "Frostfall", "Sun's Dusk", "Evening Star",
)

private fun ordinal(n: Int) = "$n" + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th"
}

private fun timeLabel(p: PlayerState): String {
    val h = p.gameHour.coerceIn(0f, 24f)
    val hh = h.toInt() % 24
    val mm = ((h - h.toInt()) * 60).toInt()
    return "%d:%02d %s".format(if (hh % 12 == 0) 12 else hh % 12, mm, if (hh < 12) "AM" else "PM")
}

/** "28th of Last Seed, 4E 201", as the Wait menu words it. */
private fun dateLabel(p: PlayerState): String =
    (if (p.gameDay in 1..31 && p.gameYear > 0) "${ordinal(p.gameDay)} of ${months.getOrElse(p.gameMonth) { "" }}, 4E ${p.gameYear}" else "Day ${p.gameDays + 1}").uppercase()

@Composable
private fun StatLine(label: String, cur: Float, max: Float, color: Color) {
    Column(Modifier.padding(vertical = 1.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            SkyText(label, 10.sp, Sky.Dim, 2.sp, modifier = Modifier.weight(1f))
            SkyText("${cur.roundToInt()}", 13.sp, Sky.Text, 0.5.sp)
            SkyText(" / ${max.roundToInt()}", 10.sp, Sky.Dim, 0.5.sp)
        }
        HudBar(if (max > 0) (cur / max).coerceIn(0f, 1f) else 0f, color, Modifier.fillMaxWidth().height(11.dp).padding(top = 2.dp))
    }
}

/**
 * The game's attribute bar: a slim silver frame with pointed ends; the fill shows exactly the current value.
 */
@Composable
private fun HudBar(fraction: Float, color: Color, modifier: Modifier) {
    val shown by androidx.compose.animation.core.animateFloatAsState(fraction, androidx.compose.animation.core.tween(220), label = "bar")
    Canvas(modifier) {
        val h = size.height
        val w = size.width
        val tip = h * 0.6f
        val frame = Path().apply {
            moveTo(0f, h / 2); lineTo(tip, 0f); lineTo(w - tip, 0f); lineTo(w, h / 2); lineTo(w - tip, h); lineTo(tip, h); close()
        }
        drawPath(frame, Color(0xE6060606))
        val inset = h * 0.24f
        val left = tip * 0.8f
        val inner = w - left * 2
        fun seg(from: Float, to: Float, brush: Brush) {
            if (to <= from) return
            drawRect(brush, topLeft = Offset(left + inner * from, inset), size = androidx.compose.ui.geometry.Size(inner * (to - from), h - inset * 2))
        }
        seg(0f, shown, Brush.verticalGradient(listOf(color.lighten(0.35f), color, color.darken(0.45f)), startY = inset, endY = h - inset))
        if (shown > 0f) drawLine(Color(0x55FFFFFF), Offset(left, inset + 0.8f), Offset(left + inner * shown, inset + 0.8f), strokeWidth = 1f)
        drawPath(frame, Brush.verticalGradient(listOf(Color(0xFFBDBDBD), Color(0xFF5A5A5A))), style = Stroke(1.3f))
    }
}

private fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)
private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)

@Composable
private fun EquipLine(slot: String, name: String) {
    Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        SkyText(slot, 11.sp, Sky.Dim, 2.sp, modifier = Modifier.width(54.dp))
        SkyText(name.ifEmpty { "—" }, 14.sp, if (name.isEmpty()) Sky.Faint else Sky.Body, 0.5.sp)
    }
}

/** The info line at the bottom of Skyrim's item menus. */
@Composable
private fun BottomInfo(p: PlayerState, extra: String? = null) {
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SkyText("GOLD", 12.sp, Sky.Dim, 2.sp); Spacer(Modifier.width(6.dp)); SkyText("${p.gold}", 15.sp, Sky.Text, 1.sp)
        Spacer(Modifier.width(20.dp))
        SkyText("WEIGHT", 12.sp, Sky.Dim, 2.sp); Spacer(Modifier.width(6.dp))
        SkyText("${p.carryWeight.roundToInt()}/${p.carryMax.roundToInt()}", 15.sp,
            if (p.carryMax > 0 && p.carryWeight > p.carryMax) Sky.Health else Sky.Text, 1.sp)
        Spacer(Modifier.weight(1f))
        extra?.let { SkyText(it, 12.sp, Sky.Dim, 2.sp) }
    }
}

// ================================================================ compass (drawn like the game's HUD compass)

/**
 * Skyrim's compass: a thin double-ruled bar with knotwork end caps; only N/E/S/W letters; location icons,
 * enemy spheres and the silver quest marker (which pokes out of the bar) slide along it.
 */
@Composable
private fun CompassBar(p: PlayerState, modifier: Modifier) {
    val markers by Link.markers.collectAsState()
    val game by Link.compass.collectAsState()
    // Prefer the game's own compass (plugin 0.11+): same markers, same moment, same fade.
    val gc = game?.takeIf { System.currentTimeMillis() - it.at < 1500 && it.half > 5f }
    val target = gc?.angle ?: (((p.heading * 180 / PI).toFloat() % 360 + 360) % 360)
    // Glide to each new reading every display frame (readings come at ~60 Hz), so the bar moves like the game's.
    val latest = androidx.compose.runtime.rememberUpdatedState(target)
    var deg by remember { mutableStateOf(target) }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { latest.value }.collect {
            // Glide to the new reading, frame by frame, and stop once there: no redraws while you stand still.
            var last = 0L
            while (abs(((latest.value - deg + 540f) % 360f) - 180f) > 0.05f) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                    last = now
                    val d = ((latest.value - deg + 540f) % 360f) - 180f
                    deg = if (abs(d) > 45f) latest.value else (deg + d * (1f - kotlin.math.exp(-dt / 0.025f)) + 360f) % 360f
                }
            }
        }
    }
    val indoors = p.interior || p.worldspaceId == 0
    val list = markers.list.filter { it.interior == indoors }
    Canvas(modifier) {
        val h = size.height
        val barH = h * 0.56f
        val top = (h - barH) / 2
        val bot = top + barH
        val mid = h / 2
        val cap = barH * 1.25f
        val x0 = cap
        val x1 = size.width - cap
        val fov = gc?.let { it.half * 2 } ?: 100f
        val pxPerDeg = (x1 - x0) / fov
        fun xOf(bearing: Float): Float? {
            val rel = ((bearing - deg + 540f) % 360f) - 180f
            return if (abs(rel) > fov / 2) null else (x0 + x1) / 2 + rel * pxPerDeg
        }
        val line = Color(0xFFC4C4C4)
        // Bar body.
        drawRect(Color(0x73000000), topLeft = Offset(x0, top), size = androidx.compose.ui.geometry.Size(x1 - x0, barH))
        drawLine(line, Offset(x0, top), Offset(x1, top), strokeWidth = 1.6f)
        drawLine(line, Offset(x0, bot), Offset(x1, bot), strokeWidth = 1.6f)
        drawLine(Color(0x55C4C4C4), Offset(x0, top + 3f), Offset(x1, top + 3f), strokeWidth = 0.8f)
        drawLine(Color(0x55C4C4C4), Offset(x0, bot - 3f), Offset(x1, bot - 3f), strokeWidth = 0.8f)
        knotCap(x0, top, bot, mid, cap, -1f, line)
        knotCap(x1, top, bot, mid, cap, 1f, line)

        // Clip only at the ends: like the game's compass, big markers (quest, your map marker) stand taller than the bar.
        clipRect(left = x0 + 2f, top = -h, right = x1 - 2f, bottom = h * 2) {
            if (gc != null) {
                gameMarks(gc, deg, x0, x1, mid, barH, pxPerDeg)
                return@clipRect
            }
            // Places: discovered ones within range; undiscovered ones (outlined) only when close, like the HUD.
            if (!indoors) for (m in list) {
                if (m.kind != MarkerKind.LOCATION) continue
                val d = distanceTo(p, m.x, m.y)
                if (d > (if (m.discovered) 14000f else 8000f)) continue
                val x = xOf(bearingTo(p, m.x, m.y)) ?: continue
                locationGlyph(m.type, Offset(x, mid), barH * 0.26f, m.discovered)
            }
            // Letters over the location icons, fading toward the ends.
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = barH * 0.66f; textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
            }
            for ((d, name) in listOf(0f to "N", 90f to "E", 180f to "S", 270f to "W")) {
                val x = xOf(d) ?: continue
                val rel = abs(x - (x0 + x1) / 2) / ((x1 - x0) / 2)
                paint.color = android.graphics.Color.argb((235 * (1f - rel * rel * 0.75f)).toInt(), 220, 220, 220)
                drawContext.canvas.nativeCanvas.drawText(name, x, mid + paint.textSize * 0.36f, paint)
            }
            for (m in list) {
                if (m.kind != MarkerKind.HOSTILE) continue
                val x = xOf(bearingTo(p, m.x, m.y)) ?: continue
                dotGlyph(Offset(x, mid), barH * 0.2f, MarkerStyle.Hostile)
            }
            for (m in list) {
                if (m.kind != MarkerKind.QUEST) continue
                val x = xOf(bearingTo(p, m.x, m.y)) ?: continue
                questGlyph(Offset(x, mid + barH * 0.05f), barH * 0.7f)
            }
        }
    }
}

/** Draws the game's own compass markers, in its order: places, letters, enemies and your marker, then quests on top. */
private fun DrawScope.gameMarks(gc: GameCompass, deg: Float, x0: Float, x1: Float, mid: Float, barH: Float, pxPerDeg: Float) {
    val centre = (x0 + x1) / 2
    fun rel(h: Float) = ((h - deg + 540f) % 360f) - 180f
    fun xOf(h: Float): Float? = rel(h).let { r -> if (abs(r) > gc.half) null else centre + r * pxPerDeg }
    for (m in gc.marks) {
        if (gc.isQuest(m.frame) || m.frame == gc.enemy || m.frame == gc.playerSet) continue
        val x = xOf(m.heading) ?: continue
        val a = (m.alpha / 100f).coerceIn(0f, 1f)
        if (a <= 0.02f) continue
        val sc = (m.scale / 100f).coerceIn(0.3f, 2f)
        // The very icon the game's compass shows (same frame), at its fade and size.
        if (gameIcon(m.frame, Offset(x, mid), barH * 0.72f * sc, a)) continue
        val loc = gc.location(m.frame) ?: continue
        drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(x - barH, 0f, x + barH, size.height), androidx.compose.ui.graphics.Paint().apply { alpha = a })
        locationGlyph(loc.first, Offset(x, mid), barH * 0.26f * sc, loc.second)
        drawContext.canvas.restore()
    }
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true; textSize = barH * 0.66f; textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
    }
    for ((d, name) in listOf(0f to "N", 90f to "E", 180f to "S", 270f to "W")) {
        val x = xOf(d) ?: continue
        val r = abs(x - centre) / ((x1 - x0) / 2)
        paint.color = android.graphics.Color.argb((235 * (1f - r * r * 0.75f)).toInt(), 220, 220, 220)
        drawContext.canvas.nativeCanvas.drawText(name, x, mid + paint.textSize * 0.36f, paint)
    }
    for (m in gc.marks) {
        val a = (m.alpha / 100f).coerceIn(0f, 1f)
        if (a <= 0.02f) continue
        val x = xOf(m.heading) ?: continue
        val sc = (m.scale / 100f).coerceIn(0.3f, 2f)
        when (m.frame) {
            gc.enemy -> if (!gameIcon(m.frame, Offset(x, mid), barH * 0.72f * sc, a)) dotGlyph(Offset(x, mid), barH * 0.2f * sc, MarkerStyle.Hostile.copy(alpha = a))
            gc.playerSet -> if (!gameIcon(m.frame, Offset(x, mid), barH * 1.25f * sc, a)) {   // your own map marker
                val r = barH * 0.34f * sc
                drawPath(Path().apply { moveTo(x, mid + r); lineTo(x - r * 0.7f, mid - r * 0.3f); lineTo(x + r * 0.7f, mid - r * 0.3f); close() }, Color.White.copy(alpha = a))
                drawCircle(Color.White.copy(alpha = a), r * 0.55f, Offset(x, mid - r * 0.45f))
            }
        }
    }
    // Quest markers: like the game, one beyond the edge of the arc waits at that end of the bar.
    for (m in gc.marks) {
        if (!gc.isQuest(m.frame)) continue
        val r = rel(m.heading)
        val x = if (abs(r) <= gc.half) centre + r * pxPerDeg else if (r > 0) x1 - 6f else x0 + 6f
        val a = (m.alpha / 100f).coerceIn(0f, 1f)
        if (a <= 0.02f) continue
        val sc = (m.scale / 100f).coerceIn(0.3f, 2f)
        if (!gameIcon(m.frame, Offset(x, mid), barH * 1.45f * sc, a))
            questGlyph(Offset(x, mid + barH * 0.05f), barH * 0.7f * sc)
    }
}

/** The knotwork end of the compass: an arrowhead outline with two interlaced loops inside. */
private fun DrawScope.knotCap(x: Float, top: Float, bot: Float, mid: Float, cap: Float, dir: Float, color: Color) {
    val tip = x + dir * cap
    val shoulder = x + dir * cap * 0.55f
    val outline = Path().apply {
        moveTo(x, top); lineTo(shoulder, top); lineTo(tip, mid); lineTo(shoulder, bot); lineTo(x, bot)
    }
    drawPath(outline, Color(0x73000000))
    drawPath(outline, color, style = Stroke(1.6f))
    val r = (bot - top) * 0.26f
    val cx = x + dir * cap * 0.42f
    for (dx in listOf(-r * 0.55f, r * 0.55f)) {
        val c = Offset(cx + dx, mid)
        drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close() }, color, style = Stroke(1.3f))
    }
}

// ================================================================ quick select (favorites) bar

/** The favorites, as a scrolling strip along the bottom: tap to equip or use, like the game's Q menu. */
@Composable
private fun QuickBar(lists: GameLists, onNotice: (String) -> Unit) {
    data class Fav(val key: String, val name: String, val kind: String, val equipped: Boolean, val act: () -> String)
    val favs = lists.spells.filter { it.favorite }.map { s ->
        Fav("s${s.formId}", s.name, if (s.kind == SpellKind.SPELL) s.school.label.uppercase() else s.kind.label.removeSuffix("s").uppercase(), s.equipped) { activate(s) }
    } + lists.items.filter { it.favorite }.map { i ->
        Fav("i${i.formId}", i.name, if (i.count > 1) "${i.category.label.uppercase()}  ×${i.count}" else i.category.label.uppercase().removeSuffix("S"), i.equipped) { activate(i) }
    }
    val haptic = rememberHaptics()
    Column(Modifier.fillMaxWidth().background(Color(0xE6000000))) {
        Rule(Modifier.padding(horizontal = 8.dp))
        if (favs.isEmpty()) {
            SkyText("FAVORITES  ·  mark items and spells as favorites in game to quick-select them here", 11.sp, Sky.Faint, 1.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), align = TextAlign.Center)
            return
        }
        // A hotbar: four equal slots per view, snapping as you swipe through the rest.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val slot = (maxWidth - 16.dp - 18.dp) / 4
        val state = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyRow(
            Modifier.fillMaxWidth().height(52.dp), state = state, contentPadding = PaddingValues(horizontal = 8.dp),
            flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            items(favs, key = { it.key }) { f ->
                Column(
                    Modifier.width(slot).height(44.dp)
                        .background(if (f.equipped) Color(0x26FFFFFF) else Color(0x10FFFFFF))
                        .border(1.dp, if (f.equipped) Sky.Line else Color(0x33FFFFFF))
                        .clickable { haptic(HapticFeedbackType.LongPress); GameSounds.play(GameSounds.Cue.OK); onNotice(f.act()) }
                        .padding(horizontal = 9.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (f.equipped) { DiamondMark(true, Sky.Text, Modifier.size(7.dp)); Spacer(Modifier.width(5.dp)) }
                        SkyFitText(f.name, 14.sp, 10.sp, if (f.equipped) Sky.Text else Sky.Body, 0.5.sp)
                    }
                    SkyText(f.kind, 9.sp, Sky.Dim, 1.5.sp)
                }
            }
        }
        }
    }
}

// ================================================================ notification feed + first-play walkthrough

/** The game's notices ("Quest started", "Skill increased"…) as a small stack above the quick bar. */
@Composable
private fun Feed(modifier: Modifier) {
    val notes by NotificationFeed.notes.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        notes.forEach { n ->
            androidx.compose.animation.AnimatedVisibility(true, enter = androidx.compose.animation.fadeIn()) {
                Column(Modifier.widthIn(max = 330.dp).background(Color(0xE6000000)).border(1.dp, Color(0x33FFFFFF)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                    SkyText(n.title, 9.sp, Sky.Dim, 2.sp)
                    SkyText(n.text, 14.sp, Sky.Text, 0.5.sp, maxLines = 2)
                }
            }
        }
    }
}

/** Three cards the first time a save is loaded: what the bottom screen does. */
@Composable
private fun Walkthrough(onDone: () -> Unit) {
    val pages = listOf(
        "YOUR CHARACTER" to "Health, magicka and stamina, your skills and active effects, and a local map. Tap the map to open the world map, where you can fast travel.",
        "MENUS ON THE BOTTOM SCREEN" to "Quests, Items and Magic work like the game's menus: equip, use and track right here while you play.",
        "QUICK SELECT" to "Favourites sit along the bottom: tap one to equip it. If you like, ⚙ can hide the game's compass and health bars, since this screen shows them too.",
    )
    var i by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp).border(1.dp, Sky.Frame).background(Color(0xFF0B0B0B)).padding(20.dp)) {
            SkyText("${i + 1} OF ${pages.size}", 10.sp, Sky.Dim, 2.sp)
            SkyText(pages[i].first, 19.sp, Sky.Text, 3.sp, modifier = Modifier.padding(top = 4.dp))
            Rule(Modifier.padding(vertical = 8.dp), color = Sky.Faint)
            SkyText(pages[i].second, 14.sp, Sky.Body, 0.3.sp, light = true, maxLines = 6)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                SkyText("SKIP", 13.sp, Sky.Dim, 2.sp, modifier = Modifier.clickable(onClick = onDone).padding(8.dp))
                Spacer(Modifier.weight(1f))
                SkyText(if (i == pages.lastIndex) "BEGIN ›" else "NEXT ›", 15.sp, Sky.Text, 3.sp,
                    modifier = Modifier.clickable { if (i == pages.lastIndex) onDone() else i++ }.border(1.dp, Sky.Line).padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

// ================================================================ QUESTS (the journal)

private fun questGroup(q: Quest): String = when {
    q.completed || q.failed -> "COMPLETED"
    q.type == 1 -> "MAIN QUESTS"
    q.type in setOf(2, 3, 4, 5, 7, 9, 10, 11) -> "FACTION & DAEDRIC"
    q.type == 6 || q.name.isEmpty() -> "MISCELLANEOUS"
    else -> "SIDE QUESTS"
}

private val groupOrder = listOf("MAIN QUESTS", "SIDE QUESTS", "FACTION & DAEDRIC", "MISCELLANEOUS", "COMPLETED")

@Composable
private fun QuestsTab(onShowOnMap: (Offset) -> Unit) {
    val raw by Link.quests.collectAsState()
    val markers by Link.markers.collectAsState()
    // Unnamed misc quests merge into one "Miscellaneous" entry, like the journal.
    val misc = raw.filter { it.name.isEmpty() && !it.completed }
    val quests = raw.filter { it.name.isNotEmpty() } +
        (if (misc.isNotEmpty()) listOf(Quest(-1, 6, false, false, false, 0, "Miscellaneous", misc.flatMap { it.objectives }.filter { it.state == ObjectiveState.ACTIVE })) else emptyList())
    val grouped = quests.groupBy(::questGroup).toList().sortedBy { groupOrder.indexOf(it.first) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val sel = quests.firstOrNull { it.formId == selected } ?: quests.firstOrNull { it.active } ?: quests.firstOrNull()

    if (quests.isEmpty()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            SkyText("NO ACTIVE QUESTS", 18.sp, Sky.Dim, 3.sp)
        }
        return
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp)) {
        LazyColumn(Modifier.width(200.dp).fillMaxHeight()) {
            grouped.forEach { (group, qs) ->
                item(key = "h$group") { SkyText(group, 10.sp, Sky.Dim, 2.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                items(qs.sortedWith(compareBy({ !it.active }, { it.name })), key = { "q${it.formId}" }) { q ->
                    val isSel = q.formId == sel?.formId
                    Row(Modifier.fillMaxWidth().clickable { selected = q.formId }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) { if (q.active) DiamondMark(true, Sky.Text, Modifier.size(7.dp)) }
                        SkyText(if (isSel) "[ ${q.name} ]" else q.name, 14.sp,
                            when { isSel -> Sky.Text; q.completed || q.failed -> Sky.Faint; else -> Sky.Body }, 0.5.sp, maxLines = 2)
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Sky.Faint))
        val q = sel ?: return@Row
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 14.dp)) {
            SkyText(q.name.uppercase(), 18.sp, Sky.Text, 2.sp, maxLines = 2)
            SkyText(questGroup(q).removeSuffix(" QUESTS") + if (q.active) "  ·  ACTIVE" else "", 10.sp, Sky.Dim, 2.sp)
            Rule(Modifier.padding(vertical = 6.dp), color = Sky.Faint)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                q.objectives.forEach { o ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Box(Modifier.width(16.dp).padding(top = 5.dp)) {
                            DiamondMark(o.state == ObjectiveState.ACTIVE, if (o.state == ObjectiveState.ACTIVE) Sky.Text else Sky.Faint, Modifier.size(7.dp))
                        }
                        SkyText(o.text.ifEmpty { "…" }, 14.sp, if (o.state == ObjectiveState.ACTIVE) Sky.Body else Sky.Faint, 0.3.sp,
                            light = o.state != ObjectiveState.ACTIVE, maxLines = 4)
                    }
                }
            }
            val target = markers.list.firstOrNull { it.kind == MarkerKind.QUEST && it.formId == q.formId && !it.interior }
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                // TRACK, like the journal: tracked quests show their markers on the compass and map.
                if (!q.completed && !q.failed) {
                    var tracked by remember(q.formId, q.active) { mutableStateOf(q.active) }
                    SkyText(if (tracked) "◆ TRACKED" else "TRACK", 13.sp, if (tracked) Sky.Bg else Sky.Text, 2.sp,
                        modifier = Modifier.clickable {
                            // Keep this quest open: the default selection follows the tracked quest.
                            selected = q.formId
                            tracked = !tracked; SkyrimEngine.trackQuest(q.formId, tracked)
                            GameSounds.play(GameSounds.Cue.OK)
                        }.background(if (tracked) Sky.Text else Color(0x14FFFFFF)).border(1.dp, Sky.Line).padding(horizontal = 12.dp, vertical = 6.dp))
                    Spacer(Modifier.width(18.dp))
                }
                if (target != null) {
                    Row(Modifier.clickable { onShowOnMap(Offset(target.x, target.y)) }, verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(16.dp)) { questGlyph(center + Offset(0f, size.height * 0.15f), size.minDimension / 2.6f) }
                        Spacer(Modifier.width(6.dp))
                        SkyText("SHOW ON MAP", 13.sp, Sky.Text, 2.sp)
                    }
                }
            }
        }
    }
}

// ================================================================ ITEMS (inventory menu layout)

private val itemCategories: List<Pair<String, (InvItem) -> Boolean>> = listOf(
    "FAVORITES" to { i: InvItem -> i.favorite },
    "EQUIPPED" to { i: InvItem -> i.equipped },
    "ALL" to { _: InvItem -> true },
    "WEAPONS" to { i: InvItem -> i.category == ItemCategory.WEAPON || i.category == ItemCategory.AMMO },
    "APPAREL" to { i: InvItem -> i.category == ItemCategory.APPAREL },
    "POTIONS" to { i: InvItem -> i.category == ItemCategory.POTION },
    "SCROLLS" to { i: InvItem -> i.category == ItemCategory.SCROLL },
    "FOOD" to { i: InvItem -> i.category == ItemCategory.FOOD },
    "INGREDIENTS" to { i: InvItem -> i.category == ItemCategory.INGREDIENT },
    "BOOKS" to { i: InvItem -> i.category == ItemCategory.BOOK },
    "KEYS" to { i: InvItem -> i.category == ItemCategory.KEY },
    "MISC" to { i: InvItem -> i.category in setOf(ItemCategory.MISC, ItemCategory.SOULGEM, ItemCategory.LIGHT, ItemCategory.OTHER) },
)

@Composable
private fun ItemsTab(p: PlayerState, lists: GameLists, onNotice: (String) -> Unit) {
    val cats = itemCategories.filter { (name, f) -> name == "ALL" || lists.items.any(f) }
    var cat by remember { mutableStateOf("ALL") }
    var selected by remember { mutableStateOf<Int?>(null) }
    val filter = cats.firstOrNull { it.first == cat }?.second ?: { true }
    val shown = lists.items.filter(filter).sortedBy { it.name.lowercase() }
    val sel = shown.firstOrNull { it.formId == selected }

    TwoColumnMenu(
        categories = cats.map { it.first }, current = cat, onCategory = { cat = it; selected = null },
        list = {
            items(shown, key = { it.formId }) { i ->
                ItemRow(i, selected = i.formId == selected) {
                    if (selected == i.formId) onNotice(activate(i)) else selected = i.formId
                }
            }
        },
        detail = sel?.let { i ->
            {
                if (oneHanded(i)) ItemDetail(i, null, onAction = {}, extra = { HandButtons(i.equippedLeft, i.equippedRight) { h ->
                    // Tapping the hand it's already in takes it off, like the game.
                    onNotice(if ((h == 2 && i.equippedLeft) || (h == 1 && i.equippedRight)) activate(i, 0) else activate(i, h)) } })
                else ItemDetail(i, actionFor(i), onAction = { onNotice(activate(i)) })
            }
        },
        footer = { BottomInfo(p) },
    )
}

private fun actionFor(i: InvItem) = when {
    i.consumable -> "USE"
    i.equipped -> "UNEQUIP"
    i.category in setOf(ItemCategory.WEAPON, ItemCategory.APPAREL, ItemCategory.AMMO, ItemCategory.LIGHT) -> "EQUIP"
    else -> null
}

private fun nameWithCount(i: InvItem) = if (i.count > 1) "${i.name} (${i.count})" else i.name

// ================================================================ MAGIC (magic menu layout)

@Composable
private fun MagicTab(p: PlayerState, lists: GameLists, onNotice: (String) -> Unit) {
    val groups: List<Pair<String, (Spell) -> Boolean>> = listOf<Pair<String, (Spell) -> Boolean>>(
        "FAVORITES" to { it.favorite },
        "ALL" to { true },
        "POWERS" to { it.kind == SpellKind.POWER || it.kind == SpellKind.LESSER_POWER },
        "SHOUTS" to { it.kind == SpellKind.SHOUT },
    ) + School.entries.filter { it != School.NONE }.map { s -> s.label.uppercase() to { sp: Spell -> sp.kind == SpellKind.SPELL && sp.school == s } }
    val cats = groups.filter { (name, f) -> name == "ALL" || lists.spells.any(f) }
    var cat by remember { mutableStateOf("ALL") }
    var selected by remember { mutableStateOf<Int?>(null) }
    val filter = cats.firstOrNull { it.first == cat }?.second ?: { true }
    val shown = lists.spells.filter(filter).sortedBy { it.name.lowercase() }
    val sel = shown.firstOrNull { it.formId == selected }

    TwoColumnMenu(
        categories = cats.map { it.first }, current = cat, onCategory = { cat = it; selected = null },
        list = {
            items(shown, key = { it.formId }) { s ->
                ListRow(s.name, selected = s.formId == selected, equipped = s.equipped) {
                    if (selected == s.formId) onNotice(activate(s)) else selected = s.formId
                }
            }
        },
        detail = sel?.let { s ->
            {
                val info = buildList {
                    add("TYPE" to (if (s.kind == SpellKind.SPELL) s.school.label else s.kind.label.removeSuffix("s")).uppercase())
                    if (s.cost > 0) add("COST" to "${s.cost.roundToInt()}")
                }
                if (s.kind == SpellKind.SPELL) ItemCard(s.name, info, null, onAction = {}, extra = {
                    HandButtons(s.equippedLeft, s.equippedRight) { h -> onNotice(activate(s, h)) }
                }) else ItemCard(s.name, info, if (s.equippedVoice) "EQUIPPED" else "EQUIP", onAction = { onNotice(activate(s)) })
            }
        },
        footer = {
            Rule()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SkyText("MAGICKA", 14.sp, Sky.Dim, 2.sp)
                Spacer(Modifier.width(10.dp))
                HudBar(if (p.magickaMax > 0) p.magicka / p.magickaMax else 0f, Sky.Magicka, Modifier.width(160.dp).height(12.dp))
                Spacer(Modifier.width(10.dp))
                SkyText("${p.magicka.roundToInt()}/${p.magickaMax.roundToInt()}", 16.sp, Sky.Text, 1.sp)
            }
        },
    )
}

// ================================================================ shared menu pieces

@Composable
private fun TwoColumnMenu(
    categories: List<String>, current: String, onCategory: (String) -> Unit,
    list: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
    detail: (@Composable () -> Unit)?,
    footer: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            // Category column, like the left side of the inventory menu.
            LazyColumn(Modifier.width(124.dp).fillMaxHeight()) {
                items(categories) { c ->
                    val sel = c == current
                    SkyText(c, if (sel) 14.sp else 13.sp, if (sel) Sky.Text else Sky.Dim, 1.5.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onCategory(c) }.padding(vertical = 7.dp, horizontal = 2.dp))
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Sky.Faint))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp), content = list)
                detail?.let { Rule(color = Sky.Faint); it() }
            }
        }
        footer()
    }
}

/** An inventory row: equipped diamond, the item's glyph, its name, like the game's list (with a type icon). */
@Composable
private fun ItemRow(i: InvItem, selected: Boolean, onTap: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) { if (i.equipped) DiamondMark(true, modifier = Modifier.size(7.dp)) }
        ItemGlyph(i, Modifier.size(18.dp), if (selected || i.equipped) Sky.Text else Sky.Dim)
        Spacer(Modifier.width(8.dp))
        SkyText(if (selected) "[ ${nameWithCount(i)} ]" else nameWithCount(i), 17.sp, if (selected || i.equipped) Sky.Text else Sky.Body, 0.5.sp)
    }
}

/** What kind of thing this is, in the game's words. */
private fun kindLabel(i: InvItem): String = when (i.category) {
    ItemCategory.WEAPON -> when (i.subtype) {
        1 -> "SWORD"; 2 -> "DAGGER"; 3 -> "WAR AXE"; 4 -> "MACE"; 5 -> "GREATSWORD"; 6 -> "BATTLEAXE / WARHAMMER"
        7 -> "BOW"; 8 -> "STAFF"; 9 -> "CROSSBOW"; else -> "WEAPON"
    }
    ItemCategory.APPAREL -> when (i.subtype) { 0 -> "LIGHT ARMOR"; 1 -> "HEAVY ARMOR"; else -> "CLOTHING" }
    else -> i.category.label.uppercase().removeSuffix("S")
}

/** The effect line as the game words it: "Restore Health 50", "Fortify Archery 20 for 60s". */
private fun effectLine(i: InvItem): String? {
    if (i.effect.isBlank()) return null
    val mag = if (i.magnitude > 0) " ${if (i.magnitude % 1f == 0f) i.magnitude.toInt().toString() else "%.1f".format(i.magnitude)}" else ""
    val dur = if (i.duration > 0) " for ${i.duration}s" else ""
    return i.effect + mag + dur
}

/** The item card under the list, like the game's inventory: icon, name, kind, the stats that matter, effect. */
@Composable
private fun ItemDetail(i: InvItem, action: String?, onAction: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).border(1.dp, Color(0x40FFFFFF)).background(Color(0x0FFFFFFF)), contentAlignment = Alignment.Center) {
            ItemGlyph(i, Modifier.size(34.dp), Sky.Text)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SkyText(i.name.uppercase(), 16.sp, Sky.Text, 2.sp)
            SkyText(kindLabel(i) + if (i.stolen) "  ·  STOLEN" else "", 9.sp, if (i.stolen) Sky.Health else Sky.Dim, 2.sp)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                val stats = buildList {
                    if (i.category == ItemCategory.WEAPON && i.stat > 0) add("DAMAGE" to "${i.stat.roundToInt()}")
                    if (i.category == ItemCategory.APPAREL && i.stat > 0) add("ARMOR" to "${i.stat.roundToInt()}")
                    add("WEIGHT" to if (i.weight % 1f == 0f) "${i.weight.toInt()}" else "%.1f".format(i.weight))
                    add("VALUE" to "${i.value}")
                }
                stats.forEach { (k, v) ->
                    SkyText(k, 10.sp, Sky.Dim, 1.5.sp); Spacer(Modifier.width(5.dp)); SkyText(v, 15.sp, Sky.Text, 0.5.sp)
                    Spacer(Modifier.width(14.dp))
                }
            }
            effectLine(i)?.let { SkyText(it, 12.sp, Sky.Body, 0.3.sp, light = true, maxLines = 2) }
        }
        action?.let {
            SkyText(it, 14.sp, Sky.Text, 3.sp, modifier = Modifier.clickable(onClick = onAction)
                .background(Color(0x14FFFFFF)).border(1.dp, Sky.Line).padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
    extra?.let { Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.End) { it() } }
}

/** A small silver silhouette for the item's type (sword, axe, bow, armor, potion…). */
@Composable
private fun ItemGlyph(i: InvItem, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension; val o = Offset((size.width - s) / 2, (size.height - s) / 2)
        fun pt(x: Float, y: Float) = Offset(o.x + x * s, o.y + y * s)
        fun poly(vararg p: Float, fill: Boolean = true) {
            val path = Path().apply { moveTo(pt(p[0], p[1]).x, pt(p[0], p[1]).y); var k = 2; while (k < p.size) { lineTo(pt(p[k], p[k + 1]).x, pt(p[k], p[k + 1]).y); k += 2 }; close() }
            if (fill) drawPath(path, color) else drawPath(path, color, style = Stroke(s * 0.07f))
        }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 0.08f) = drawLine(color, pt(x1, y1), pt(x2, y2), strokeWidth = s * w)
        when (i.category) {
            ItemCategory.WEAPON -> when (i.subtype) {
                3, 6 -> { line(0.3f, 0.92f, 0.62f, 0.12f); poly(0.55f, 0.1f, 0.88f, 0.22f, 0.8f, 0.5f, 0.6f, 0.36f) }                 // axe
                4 -> { line(0.3f, 0.92f, 0.6f, 0.22f); drawCircle(color, s * 0.16f, pt(0.64f, 0.2f)) }                                 // mace
                7, 9 -> { drawArc(color, -60f, 120f, false, topLeft = pt(0.05f, 0.1f), size = androidx.compose.ui.geometry.Size(s * 0.8f, s * 0.8f), style = Stroke(s * 0.08f)); line(0.6f, 0.15f, 0.6f, 0.85f, 0.03f) } // bow
                8 -> { line(0.3f, 0.92f, 0.66f, 0.2f); drawCircle(color, s * 0.1f, pt(0.7f, 0.14f)) }                                  // staff
                2 -> { poly(0.66f, 0.12f, 0.72f, 0.18f, 0.42f, 0.6f, 0.36f, 0.54f); line(0.32f, 0.5f, 0.48f, 0.66f, 0.07f); line(0.38f, 0.62f, 0.28f, 0.74f) } // dagger
                else -> { poly(0.8f, 0.08f, 0.88f, 0.16f, 0.36f, 0.68f, 0.28f, 0.6f); line(0.2f, 0.56f, 0.44f, 0.8f, 0.07f); line(0.3f, 0.7f, 0.14f, 0.86f) } // sword
            }
            ItemCategory.AMMO -> { line(0.2f, 0.8f, 0.78f, 0.22f, 0.05f); poly(0.86f, 0.14f, 0.7f, 0.2f, 0.8f, 0.3f) }
            ItemCategory.APPAREL -> {
                // By slot, from the name (as SkyUI does): boots, head, hands, shield, jewelry, else body.
                val n = i.name.lowercase()
                when {
                    listOf("boots", "shoes", "footwraps", "greaves").any { it in n } ->
                        poly(0.34f, 0.14f, 0.56f, 0.14f, 0.56f, 0.62f, 0.86f, 0.72f, 0.86f, 0.86f, 0.3f, 0.86f)
                    listOf("helmet", "hood", "hat", "cap", "circlet", "crown", "mask", "cowl").any { it in n } ->
                        poly(0.2f, 0.62f, 0.24f, 0.34f, 0.5f, 0.14f, 0.76f, 0.34f, 0.8f, 0.62f, 0.66f, 0.62f, 0.66f, 0.8f, 0.34f, 0.8f, 0.34f, 0.62f)
                    listOf("gauntlet", "gloves", "bracers", "vambraces").any { it in n } ->
                        poly(0.3f, 0.86f, 0.3f, 0.42f, 0.36f, 0.2f, 0.46f, 0.2f, 0.48f, 0.4f, 0.56f, 0.16f, 0.66f, 0.18f, 0.62f, 0.42f, 0.78f, 0.36f, 0.74f, 0.62f, 0.66f, 0.86f)
                    "shield" in n || "buckler" in n -> poly(0.2f, 0.16f, 0.8f, 0.16f, 0.78f, 0.52f, 0.5f, 0.88f, 0.22f, 0.52f)
                    listOf("ring", "amulet", "necklace", "torc").any { it in n } -> {
                        drawCircle(color, s * 0.24f, pt(0.5f, 0.56f), style = Stroke(s * 0.08f)); poly(0.5f, 0.12f, 0.6f, 0.26f, 0.5f, 0.36f, 0.4f, 0.26f)
                    }
                    else -> poly(0.3f, 0.14f, 0.42f, 0.2f, 0.58f, 0.2f, 0.7f, 0.14f, 0.86f, 0.3f, 0.76f, 0.44f, 0.72f, 0.86f, 0.28f, 0.86f, 0.24f, 0.44f, 0.14f, 0.3f,
                        fill = i.subtype != 2)   // body: filled for armor, outlined for clothing
                }
            }
            ItemCategory.POTION, ItemCategory.FOOD -> if (i.category == ItemCategory.POTION) {
                poly(0.42f, 0.12f, 0.58f, 0.12f, 0.58f, 0.36f, 0.78f, 0.6f, 0.72f, 0.88f, 0.28f, 0.88f, 0.22f, 0.6f, 0.42f, 0.36f)   // flask
            } else { drawCircle(color, s * 0.3f, pt(0.5f, 0.56f)); line(0.5f, 0.26f, 0.56f, 0.14f, 0.06f) }                      // apple
            ItemCategory.INGREDIENT -> poly(0.5f, 0.1f, 0.78f, 0.4f, 0.62f, 0.74f, 0.5f, 0.9f, 0.38f, 0.74f, 0.22f, 0.4f)               // leaf
            ItemCategory.SCROLL -> { poly(0.22f, 0.2f, 0.78f, 0.2f, 0.78f, 0.8f, 0.22f, 0.8f, fill = false); line(0.32f, 0.38f, 0.68f, 0.38f, 0.05f); line(0.32f, 0.52f, 0.68f, 0.52f, 0.05f) }
            ItemCategory.BOOK -> { poly(0.2f, 0.14f, 0.8f, 0.14f, 0.8f, 0.86f, 0.2f, 0.86f); line(0.3f, 0.14f, 0.3f, 0.86f, 0.04f) }
            ItemCategory.KEY -> { drawCircle(color, s * 0.15f, pt(0.32f, 0.36f), style = Stroke(s * 0.08f)); line(0.42f, 0.46f, 0.8f, 0.84f); line(0.68f, 0.72f, 0.78f, 0.62f) }
            ItemCategory.SOULGEM -> poly(0.5f, 0.1f, 0.78f, 0.4f, 0.5f, 0.9f, 0.22f, 0.4f, fill = false)
            ItemCategory.LIGHT -> { line(0.4f, 0.9f, 0.5f, 0.42f); poly(0.5f, 0.1f, 0.64f, 0.34f, 0.5f, 0.44f, 0.36f, 0.34f) }
            else -> poly(0.5f, 0.2f, 0.78f, 0.5f, 0.5f, 0.8f, 0.22f, 0.5f)
        }
    }
}

/** The item card under the list: name, stats, and the button prompt. */
@Composable
private fun ItemCard(name: String, stats: List<Pair<String, String>>, action: String?, onAction: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 10.dp)) {
        SkyText(name.uppercase(), 16.sp, Sky.Text, 2.sp)
        Spacer(Modifier.height(6.dp))
        Row {
            stats.forEach { (k, v) ->
                SkyText(k, 13.sp, Sky.Dim, 2.sp); Spacer(Modifier.width(6.dp)); SkyText(v, 16.sp, Sky.Text, 1.sp)
                Spacer(Modifier.width(18.dp))
            }
            Spacer(Modifier.weight(1f))
            // A touch button (not a controller prompt): the controller always stays with the game on the top screen.
            action?.let {
                SkyText(it, 14.sp, Sky.Text, 3.sp, modifier = Modifier.clickable(onClick = onAction)
                    .background(Color(0x14FFFFFF)).border(1.dp, Sky.Line).padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
        // Hand buttons get their own row, so they never squeeze (or get squeezed by) the stats.
        extra?.let { Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) { it() } }
    }
}

// ================================================================ MAP

@Composable
private fun MapTab(p: PlayerState, focus: Offset?, onFastTravel: (MapMarker) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        CompassBar(p, Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 14.dp))
        LiveMap(p, focus, onFastTravel)
    }
}

// ================================================================ in-game settings (the companion's gear button)

/** Haptics that respect the player's setting. */
@Composable
private fun rememberHaptics(): (HapticFeedbackType) -> Unit {
    val h = LocalHapticFeedback.current
    val values by LauncherSettings.values.collectAsState()
    val on = (values[LauncherSettings.haptics.key] ?: "On") == "On"
    return { t -> if (on) h.performHapticFeedback(t) }
}

/**
 * Settings that matter while playing. Launcher settings (resolution, drivers…) stay on the launcher.
 * The HUD options hide parts of the game's own HUD, for playing with the bottom screen as the HUD.
 */
@Composable
private fun InGameSettings(onClose: () -> Unit, onNotice: (String) -> Unit) {
    val values by LauncherSettings.values.collectAsState()
    val player by Link.player.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmQuit by remember { mutableStateOf(false) }
    LaunchedEffect(confirmQuit) { if (confirmQuit) { delay(4000); confirmQuit = false } }
    fun pick(c: Choice, v: String) { LauncherSettings.set(c, v) }
    val hud = listOf(LauncherSettings.hudCompass, LauncherSettings.hudBars)
    Box(Modifier.fillMaxSize().background(Color(0xFF070707)).clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkyText("‹", 26.sp, Sky.Text, 0.sp, modifier = Modifier.clickable(onClick = onClose).padding(end = 12.dp))
                SkyText("SETTINGS", 20.sp, Sky.Text, 4.sp, modifier = Modifier.weight(1f))
                SkyText(if ((values[LauncherSettings.pauseMenus.key] ?: LauncherSettings.pauseMenus.default) == "On") "RESUME" else "DONE", 13.sp, Sky.Text, 3.sp, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
            }
            Rule()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SectionHeader("GAME HUD")
                hud.forEach { c ->
                    SelectorRow(c.label.uppercase(), c.options, values[c.key] ?: c.default, { it.uppercase() }) { pick(c, it); SkyrimEngine.applyHud() }
                }
                SectionHeader("PERFORMANCE")
                SelectorRow("FPS LIMIT", LauncherSettings.fpsCap.options, values[LauncherSettings.fpsCap.key] ?: LauncherSettings.fpsCap.default, { it }) {
                    pick(LauncherSettings.fpsCap, it); SkyrimEngine.applyFrameLimit()
                }
                SelectorRow("PERFORMANCE STATS", LauncherSettings.perfHud.options, values[LauncherSettings.perfHud.key] ?: "Off", { it.uppercase() }) {
                    pick(LauncherSettings.perfHud, it); SkyrimEngine.applyPerfHud()
                }
                SectionHeader("COMPANION")
                SelectorRow("PAUSE IN MENUS", LauncherSettings.pauseMenus.options, values[LauncherSettings.pauseMenus.key] ?: LauncherSettings.pauseMenus.default, { it.uppercase() }) { pick(LauncherSettings.pauseMenus, it) }
                SelectorRow("MENU SOUNDS", LauncherSettings.menuSounds.options, values[LauncherSettings.menuSounds.key] ?: "On", { it.uppercase() }) { pick(LauncherSettings.menuSounds, it) }
                SelectorRow("HAPTICS", LauncherSettings.haptics.options, values[LauncherSettings.haptics.key] ?: "On", { it.uppercase() }) { pick(LauncherSettings.haptics, it) }
                SectionHeader("GAME")
                EntryRow("ON-SCREEN KEYBOARD", "for naming your character") { SkyrimEngine.showKeyboard(); onClose() }
                EntryRow("QUICKSAVE", null) { SkyrimEngine.quickSave(); onClose(); onNotice("Quicksaving…") }
                EntryRow("WAIT", null) { SkyrimEngine.openWait(); onClose() }
                EntryRow("SAVE AND QUIT TO LAUNCHER", null) {
                    SkyrimEngine.quickSave(); onNotice("Saving, then quitting…")
                    scope.launch { delay(3500); SkyrimEngine.quitGame() }
                }
                EntryRow("QUIT TO LAUNCHER", if (confirmQuit) "TAP AGAIN · UNSAVED PROGRESS IS LOST" else null) {
                    if (confirmQuit) SkyrimEngine.quitGame() else confirmQuit = true
                }
                // Proof your installs worked: what the game itself loaded. (Skyrim's own menu only lists Creations.)
                val loaded = player?.loadedMods.orEmpty()
                SectionHeader("MODS LOADED BY THE GAME (${loaded.size})")
                if (loaded.isEmpty()) SkyText("None. Install mods from the launcher's Mod Manager.", 13.sp, Sky.Faint, 0.5.sp, light = true,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp))
                loaded.forEach { SkyText(it, 14.sp, Sky.Body, 0.3.sp, modifier = Modifier.padding(start = 6.dp, top = 3.dp)) }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// ================================================================ actions

private fun activate(item: InvItem, hand: Int = 0): String = when {
    item.consumable -> { SkyrimEngine.useItem(item.formId); "${item.name} consumed" }
    item.equipped && hand == 0 -> { SkyrimEngine.unequipItem(item.formId); "${item.name} unequipped" }
    item.category in setOf(ItemCategory.WEAPON, ItemCategory.APPAREL, ItemCategory.AMMO, ItemCategory.LIGHT) -> {
        SkyrimEngine.equipItem(item.formId, hand)
        "${item.name} equipped" + when (hand) { 1 -> " (right hand)"; 2 -> " (left hand)"; else -> "" }
    }
    else -> item.name
}

/** Spells go in a hand (default right, like the game's right trigger); powers and shouts to the voice slot. */
private fun activate(spell: Spell, hand: Int = 1): String {
    SkyrimEngine.equipSpell(spell.formId, hand)
    return "${spell.name} equipped" + if (spell.kind == SpellKind.SPELL) (if (hand == 2) " (left hand)" else " (right hand)") else ""
}

/** One-handed weapons and staffs can go in either hand. */
private fun oneHanded(i: InvItem) = i.category == ItemCategory.WEAPON && i.subtype in setOf(1, 2, 3, 4, 8)

/** LEFT and RIGHT touch buttons, laid out as you hold them; the hand the thing is already in is lit. */
@Composable
private fun HandButtons(left: Boolean, right: Boolean, onHand: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for ((label, hand, on) in listOf(Triple("LEFT", 2, left), Triple("RIGHT", 1, right))) {
            SkyText(label, 13.sp, if (on) Sky.Bg else Sky.Text, 2.5.sp, modifier = Modifier.padding(start = 8.dp).clickable { onHand(hand) }
                .background(if (on) Sky.Text else Color(0x14FFFFFF)).border(1.dp, Sky.Line).padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

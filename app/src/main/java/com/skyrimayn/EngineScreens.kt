package com.skyrimayn

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Screens SkyrimSE-DualScreen shows inside the engine activity, in place of GameNative's UI.
 * The engine never shows its library, login or loading dialogs.
 */

@Composable
fun SkyrimLoadingScreen(message: String?, progress: Float = -1f) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF26252A), Color(0xFF0B0B0D)), radius = 1400f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SKYRIM", color = Color(0xFFEDEAE3), fontFamily = FontFamily.Serif, fontSize = 72.sp, letterSpacing = 16.sp)
            Box(
                Modifier.padding(top = 6.dp).width(340.dp).height(1.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Palette.Gold, Color.Transparent))),
            )
            Spacer(Modifier.size(10.dp))
            Text("SPECIAL EDITION", color = Palette.Gold, fontFamily = FontFamily.Serif, fontSize = 15.sp, letterSpacing = 9.sp)
            Spacer(Modifier.height(64.dp))
            if (progress in 0f..1f) {
                LinearProgressIndicator(
                    progress = { progress }, color = Palette.Gold, trackColor = Palette.SurfaceHigh,
                    modifier = Modifier.width(420.dp),
                )
            } else {
                LinearProgressIndicator(color = Palette.Gold, trackColor = Palette.SurfaceHigh, modifier = Modifier.width(420.dp))
            }
            Spacer(Modifier.height(18.dp))
            SkyText(
                friendly(message).uppercase(), 15.sp, Sky.Dim, 3.sp, align = TextAlign.Center,
                modifier = Modifier.width(760.dp), maxLines = 2,
            )
        }
    }
}

/** Full-screen overlay used where the engine would show its loading dialog. */
@Composable
fun SkyrimLoadingOverlay(visible: Boolean, progress: Float, message: String?) {
    if (visible) SkyrimLoadingScreen(message, progress)
}

/**
 * Shown on the engine's library/login routes. While a launch is being prepared it's a loading screen;
 * once nothing is happening it closes, returning to SkyrimSE-DualScreen's launcher.
 */
@Composable
fun EngineIdle(busy: () -> Boolean) {
    val activity = LocalContext.current as? Activity
    SkyrimLoadingScreen(null)
    LaunchedEffect(Unit) {
        var idleFor = 0
        while (true) {
            delay(500)
            idleFor = if (busy()) 0 else idleFor + 500
            if (idleFor >= 2500) {
                activity?.finish()
                break
            }
        }
    }
}

/** Turns the engine's technical progress messages into something calmer. */
private fun friendly(message: String?): String {
    val m = message?.trim().orEmpty()
    return when {
        m.isEmpty() -> "Loading…"
        m.contains("download", true) -> "First-time setup · $m"
        m.startsWith("Booting", true) ->
            if (SkyrimEngine.firstLaunch()) "First launch: setting up Windows for Skyrim. This only happens once." else "Starting Skyrim…"
        m.startsWith("Launching", true) -> "Starting Skyrim…"
        else -> m
    }
}

/**
 * Covers the game screen from launch until Skyrim is actually drawing (the plugin reports its main menu,
 * a loading screen or the world), so the player never sits on Skyrim's black start-up window, which on a
 * first launch can last minutes while shaders build. Fallbacks: the plugin never connects (lift 60 s after
 * Skyrim's window), or something else stalls (lift 2.5 minutes after the window).
 */
@Composable
fun LaunchCurtain() {
    val shown by SkyrimEngine.gameWindow.collectAsState()
    val showing by Link.showing.collectAsState()
    val first = remember { SkyrimEngine.firstLaunch() }
    var lifted by remember { mutableStateOf(false) }
    LaunchedEffect(showing) { if (showing) { delay(600); lifted = true } }
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        delay(60_000)
        if (Link.state.value.status != LinkStatus.LIVE) { lifted = true; return@LaunchedEffect }
        delay(90_000); lifted = true
    }
    androidx.compose.animation.AnimatedVisibility(!lifted, enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(500))) {
        // What's happening, so a long first boot never looks stuck; and a tip, like the game's loading screens.
        var seconds by remember { mutableStateOf(0) }
        var windowAt by remember { mutableStateOf(-1) }
        LaunchedEffect(Unit) { while (true) { delay(1000); seconds++ } }
        LaunchedEffect(shown) { if (shown && windowAt < 0) windowAt = seconds }
        val stage = when {
            windowAt >= 0 && seconds - windowAt > 40 ->
                if (first) "First launch: building shaders for your device. This takes a few minutes, only once."
                else "Still loading. The first launch after an update or a new mod takes longest."
            windowAt >= 0 -> "Loading Skyrim…"
            seconds < 6 -> "Starting Steam…"
            seconds < 20 -> "Preparing Skyrim…"
            first && seconds > 45 -> "First launch: setting up Windows for Skyrim. This only happens once."
            else -> "Starting Skyrim…"
        }
        Box(Modifier.fillMaxSize()) {
            SkyrimLoadingScreen(stage)
            val tip = LoadingTips.list[(seconds / 9) % LoadingTips.list.size]
            androidx.compose.animation.Crossfade(tip, label = "tip", modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)) { t ->
                SkyText(t, 15.sp, Sky.Dim, 0.5.sp, light = true, align = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2,
                    modifier = Modifier.widthIn(max = 900.dp))
            }
        }
    }
}

/** Short tips about the dual-screen setup, shown while Skyrim loads. */
internal object LoadingTips {
    val list = listOf(
        "Tap the minimap on the bottom screen to open the full map, then fast travel from there.",
        "Items, Magic, Quests and the Map on the bottom screen work while you play: the game keeps running.",
        "Favorites appear in the quick bar along the bottom screen: one tap to equip.",
        "Hide the game's compass or health bars from the bottom screen's settings if you'd rather use the companion.",
        "QUESTS on the bottom screen shows every objective. SHOW ON MAP takes you straight there.",
        "Mods you install from the launcher's Mod Manager are sorted into a working load order for you.",
        "Power › Cool in the launcher keeps the Thor coolest on long sessions.",
    )
}

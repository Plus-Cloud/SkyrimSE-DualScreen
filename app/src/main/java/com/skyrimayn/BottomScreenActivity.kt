package com.skyrimayn

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bottom screen: the launcher (settings, mod manager) and, while Skyrim runs, the companion.
 *
 * Focus: like OpenMW-DS, the window starts focusable (so it doesn't ANR) and then drops focus,
 * so the controller keeps driving the top screen. Touch still works.
 */
class BottomScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Link.start(this)
        LauncherSettings.init(this)
        GameFolder.init(this)
        SkyrimEngine.refreshDrivers(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { SkyrimAynTheme { BottomRoot() } }
        window.decorView.post { window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) }
    }
}

private enum class Page { LAUNCHER, MODS }

@Composable
private fun BottomRoot() {
    var page by remember { mutableStateOf(Page.LAUNCHER) }
    var forceLauncher by remember { mutableStateOf(false) }
    val gameDir by GameFolder.dir.collectAsState()
    val fomod by ModManager.fomod.collectAsState()
    val link by Link.state.collectAsState()
    val values by LauncherSettings.values.collectAsState()
    // Switch once: to the companion when Skyrim's window is up, back when the game exits. The plugin's
    // heartbeat comes and goes while the game loads, so it only counts once it's steady (LIVE).
    val gameWindow by SkyrimEngine.gameWindow.collectAsState()
    val gameRunning = gameWindow || link.status == LinkStatus.LIVE
    val companionOn = (values[LauncherSettings.companion.key] ?: "On") == "On"
    LaunchedEffect(gameRunning) { if (!gameRunning) forceLauncher = false }

    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF151515), Sky.Bg), radius = 1600f))) {
        when {
            gameDir == null -> SetupWaiting()
            gameRunning && companionOn && !forceLauncher -> CompanionScreen(onLauncher = { forceLauncher = true })
            fomod != null -> FomodWizard(fomod!!)
            page == Page.MODS -> ModManagerPage(gameDir!!, onBack = { page = Page.LAUNCHER })
            else -> LauncherPage(
                gameRunning = gameRunning && companionOn,
                onBackToGame = { forceLauncher = false },
                onMods = { page = Page.MODS },
            )
        }
    }
}

@Composable
private fun SetupWaiting() {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        SkyText("THE ELDER SCROLLS V", 14.sp, Sky.Dim, 4.sp)
        Spacer(Modifier.height(6.dp))
        SkyText("SKYRIM", 40.sp, Sky.Text, 10.sp)
        Spacer(Modifier.height(16.dp))
        SkyText("Finish setup on the top screen", 16.sp, Sky.Dim, 1.sp, light = true)
    }
}

// ================================================================ launcher

/** Short label: "Turnip Adreno Driver T30 (@Mr_Purple_666)" → "Purple Turnip T30". */
private fun driverLabel(name: String): String = when {
    name == SkyrimEngine.SYSTEM_DRIVER -> "System"
    name.contains("Mr_Purple", true) -> "Turnip " + (Regex("T\\d+\\w*").find(name)?.value ?: "") 
    else -> name.take(16)
}

/** A new version on GitHub: shown at the top of the launcher with its notes and one button. */
@Composable
private fun UpdateCard(update: Updater.State) {
    val context = LocalContext.current
    val release = when (update) {
        is Updater.State.Available -> update.release
        is Updater.State.Downloading -> update.release
        is Updater.State.NeedsPermission -> update.release
        else -> return
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).border(1.dp, Sky.Line).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SkyText("UPDATE AVAILABLE  ·  ${release.version}", 16.sp, Sky.Text, 3.sp)
                SkyText("You have ${Updater.currentVersion}. Your saves, settings and mods are kept.", 11.sp, Sky.Dim, 0.5.sp, light = true)
            }
            SkyText(
                when (update) {
                    is Updater.State.Downloading -> "DOWNLOADING ${(update.progress * 100).toInt()}%"
                    is Updater.State.NeedsPermission -> "ALLOW INSTALLS ›"
                    else -> "INSTALL ›"
                }, 15.sp, Sky.Text, 2.sp,
                modifier = Modifier.clickable(enabled = update !is Updater.State.Downloading) { Updater.install(context, release) }
                    .border(1.dp, Sky.Line).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        if (update is Updater.State.NeedsPermission)
            SkyText("Android asks once: allow SkyrimSE-DualScreen to install apps, then come back and it carries on.",
                11.sp, Sky.Faint, 0.5.sp, light = true, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        Updater.plainNotes(release.notes).takeIf { it.isNotBlank() }?.let {
            SkyText(it.take(500), 11.sp, Sky.Dim, 0.3.sp, light = true, maxLines = 6, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
internal fun LauncherPage(gameRunning: Boolean, onBackToGame: () -> Unit, onMods: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val values by LauncherSettings.values.collectAsState()
    val drivers by SkyrimEngine.drivers.collectAsState()
    val plugins by LoadOrder.plugins.collectAsState()
    val gameDir by GameFolder.dir.collectAsState()
    var notice by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    LaunchedEffect(notice) { if (notice != null) { delay(2500); notice = null } }
    LaunchedEffect(gameDir) { gameDir?.let { LoadOrder.load(context, it) } }

    var dllFound by remember { mutableStateOf(SkyrimEngine.losslessDllAvailable()) }
    val dllImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val ok = SkyrimEngine.importLosslessDll(context, uri)
            dllFound = SkyrimEngine.losslessDllAvailable()
            notice = if (ok) "Lossless.dll added" else "That isn't Lossless.dll"
        }
    }
    val update by Updater.state.collectAsState()
    LaunchedEffect(Unit) { Updater.check(context) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val name = SkyrimEngine.importDriver(context, uri)
            notice = if (name.isEmpty()) "Not a valid driver package" else "${driverLabel(name)} installed"
            if (name.isNotEmpty()) LauncherSettings.setDriver(name)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SkyText("SKYRIMSE-DUALSCREEN", 24.sp, Sky.Text, 3.sp, maxLines = 1)
                val steam by SkyrimEngine.steam.collectAsState()
                SkyText(
                    when {
                        gameRunning -> "Skyrim is running"
                        steam.name == "LOGGED_IN" -> "Ready  ·  Steam connected"
                        steam.name == "NEEDS_LOGIN" -> "Sign in to Steam on the top screen"
                        steam.name == "OFFLINE" -> "Ready  ·  offline (Steam reconnects when Wi-Fi is back)"
                        steam.name == "NO_INTERNET" -> "No internet: turn on Wi-Fi to sign in to Steam"
                        else -> "Connecting to Steam…"
                    }, 14.sp, Sky.Dim, 1.sp, light = true,
                )
            }
            if (gameRunning) SkyText("BACK TO GAME ›", 15.sp, Sky.Text, 2.sp, modifier = Modifier.clickable(onClick = onBackToGame).padding(8.dp))
        }
        Rule(Modifier.padding(top = 8.dp))
        UpdateCard(update)

        val mods by ModManager.mods.collectAsState()
        LaunchedEffect(gameDir) { ModManager.init(context); ModManager.load() }
        val activePlugins = plugins.count { !it.locked && it.active }
        EntryRow("MOD MANAGER", "${mods.size} mod${if (mods.size == 1) "" else "s"}  ·  $activePlugins plugin${if (activePlugins == 1) "" else "s"}", onMods)
        Selector(LauncherSettings.mods, values, label = "PLAY WITH MODS")
        if ((values[LauncherSettings.mods.key] ?: "Enabled") == "Disabled")
            SkyText("Skyrim starts without your mods' plugins. Your mods stay installed.", 11.sp, Sky.Faint, 0.5.sp, light = true,
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))

        SectionHeader("DISPLAY")
        Selector(LauncherSettings.resolution, values)
        Selector(LauncherSettings.quality, values)
        if ((values[LauncherSettings.quality.key] ?: LauncherSettings.quality.default) == "Thor")
            SkyText("Tuned for the Thor: blood and impact effects, weather and distant detail kept; the costly effects off.",
                11.sp, Sky.Faint, 0.5.sp, light = true, maxLines = 2, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        Selector(LauncherSettings.power, values, label = "POWER") { LauncherSettings.set(LauncherSettings.fpsCap, LauncherSettings.fpsFor(it)) }
        SkyText(
            when (values[LauncherSettings.power.key] ?: LauncherSettings.power.default) {
                "Cool" -> "30 FPS, prime core idle. Coolest and longest battery: good for long sessions."
                "Max" -> "60 FPS on every core: the full Skyrim experience. Runs warmest; best with the Thor's fan on."
                else -> "45 FPS on every core. Noticeably cooler than Max, for long sessions."
            } + "  FPS can still be changed in game from the bottom screen.",
            11.sp, Sky.Faint, 0.5.sp, light = true, maxLines = 2, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))

        SectionHeader("PERFORMANCE")
        Selector(LauncherSettings.perfHud, values, label = "PERFORMANCE STATS") { SkyrimEngine.applyPerfHud() }
        SkyText("FPS: just the frame rate. FULL: FPS, CPU and GPU load, power and temperatures. In the corner of the game screen; also in game from ⚙.",
            11.sp, Sky.Faint, 0.5.sp, light = true, maxLines = 2, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        Selector(LauncherSettings.frameGen, values, label = "FRAME GEN (EXPERIMENTAL)")
        if ((values[LauncherSettings.frameGen.key] ?: "Off") != "Off")
            SkyText("Doubles (or triples) the frames shown, using your own Lossless.dll. Adds a little input delay. If Skyrim ever fails to start with it, it switches itself off.",
                11.sp, Sky.Faint, 0.5.sp, light = true, maxLines = 2, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        EntryRow("LOSSLESS SCALING DLL", if (dllFound) "FOUND" else "ADD Lossless.dll") { dllImporter.launch(arrayOf("*/*")) }
        if ((values[LauncherSettings.frameGen.key] ?: "Off") != "Off" && !dllFound)
            SkyText("Frame generation needs Lossless.dll from your own Lossless Scaling (Steam › Lossless Scaling › Lossless.dll on your PC).",
                11.sp, Sky.Faint, 0.5.sp, light = true, maxLines = 3, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))

        SectionHeader("DUAL SCREEN")
        Selector(LauncherSettings.companion, values, label = "IN-GAME COMPANION")

        SectionHeader("ADVANCED")
        Selector(LauncherSettings.heatGuard, values, label = "OVERHEAT PROTECTION")
        SkyText(
            if ((values[LauncherSettings.heatGuard.key] ?: LauncherSettings.heatGuard.default) == "On")
                "If the Thor stays above ${SkyrimEngine.HEAT_LIMIT_C}°C, Skyrim saves (\"Overheat Save\") and closes so it can cool down."
            else "Off: Skyrim keeps running however hot the Thor gets. The Thor still slows itself down to stay safe.",
            11.sp, Sky.Faint, 0.5.sp, light = true, maxLines = 2, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        Selector(LauncherSettings.translator, values)
        val currentDriver = values["driver"] ?: SkyrimEngine.DEFAULT_DRIVER
        SelectorRow("GRAPHICS DRIVER", drivers, currentDriver.takeIf { it in drivers } ?: drivers.firstOrNull() ?: currentDriver, ::driverLabel, { it == SkyrimEngine.DEFAULT_DRIVER }) {
            LauncherSettings.setDriver(it)
        }
        EntryRow("IMPORT DRIVER", ".zip") { importer.launch(arrayOf("application/zip", "application/octet-stream")) }
        SkyText("Only change these if you know you need to: the recommended ones are what Skyrim is tested with.",
            11.sp, Sky.Faint, 0.5.sp, light = true, maxLines = 2, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))

        SectionHeader("UPDATES")
        EntryRow(
            "SKYRIMSE-DUALSCREEN ${Updater.currentVersion}",
            when (val u = update) {
                is Updater.State.Available -> "INSTALL ${u.release.version} ›"
                is Updater.State.Downloading -> "DOWNLOADING ${(u.progress * 100).toInt()}%"
                is Updater.State.NeedsPermission -> "ALLOW INSTALLS ›"
                Updater.State.Checking -> "CHECKING…"
                Updater.State.UpToDate -> "UP TO DATE"
                is Updater.State.Failed -> "CHECK AGAIN"
                Updater.State.Idle -> "CHECK FOR UPDATES"
            },
        ) {
            when (val u = update) {
                is Updater.State.Available -> Updater.install(context, u.release)
                is Updater.State.NeedsPermission -> Updater.install(context, u.release)
                is Updater.State.Downloading -> {}
                else -> Updater.check(context, force = true)
            }
        }
        (update as? Updater.State.Failed)?.let {
            SkyText("Couldn't check: ${it.reason}. Check Wi-Fi and tap again.", 11.sp, Sky.Faint, 0.5.sp, light = true, maxLines = 2,
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        }

        SectionHeader("GAME")
        var confirmFolder by remember { mutableStateOf(false) }
        LaunchedEffect(confirmFolder) { if (confirmFolder) { delay(3000); confirmFolder = false } }
        EntryRow("SKYRIM FOLDER", if (confirmFolder) "TAP AGAIN TO CHANGE" else gameDir?.substringAfterLast('/')) {
            if (confirmFolder) GameFolder.clear() else confirmFolder = true
        }

        Spacer(Modifier.height(20.dp))
        SkyText("SKYRIMSE-DUALSCREEN ${Updater.currentVersion}  ·  PLUGIN ${SkyrimEngine.PLUGIN_VERSION}  ·  FOR SKYRIM SE 1.7.104", 10.sp, Sky.Faint, 2.sp, modifier = Modifier.padding(4.dp))
        SkyText(
            if (showDiagnostics) "HIDE DIAGNOSTICS" else "DIAGNOSTICS", 13.sp, Sky.Faint, 2.sp,
            modifier = Modifier.clickable { showDiagnostics = !showDiagnostics }.padding(4.dp),
        )
        if (showDiagnostics) Diagnostics()
    }
    notice?.let {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SkyText(it, 16.sp, Sky.Text, 1.sp, modifier = Modifier.padding(20.dp).background(Color(0xDD000000)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun Selector(c: Choice, values: Map<String, String>, label: String = c.label.uppercase(), onPick: (String) -> Unit = {}) {
    SelectorRow(label, c.options, values[c.key] ?: c.default, { it }, { LauncherSettings.isRecommended(c, it) }) { LauncherSettings.set(c, it); onPick(it) }
}

@Composable
private fun Diagnostics() {
    val link by Link.state.collectAsState()
    Column(Modifier.fillMaxWidth().padding(4.dp)) {
        listOf(
            "LINK" to link.status.name.replace('_', ' '),
            "HEARTBEAT" to "${link.frame} · ${"%.0f".format(link.updatesPerSec)}/s",
            "GAME" to formatGameVersion(link.runtimeVersion),
            "PING" to if (link.lastPingRttMs < 0) "—" else "${link.lastPingRttMs} ms",
            "FILE" to link.path.substringAfter("/storage/emulated/0/"),
        ).forEach { (k, v) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                SkyText(k, 12.sp, Sky.Faint, 2.sp, modifier = Modifier.width(96.dp))
                SkyText(v, 12.sp, Sky.Dim, 0.5.sp)
            }
        }
    }
}

// ================================================================ mod manager: load order


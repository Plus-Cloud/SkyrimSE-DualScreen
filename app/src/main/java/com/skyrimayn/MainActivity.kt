package com.skyrimayn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Top screen. First boot: allow file access, then pick the Skyrim SE folder.
 * After that: title and Play only. The launcher lives on the bottom screen.
 */
class MainActivity : ComponentActivity() {

    private var hasFileAccess by mutableStateOf(false)
    private var playHint by mutableStateOf<String?>(null)
    /** Bumped on every resume, so the title re-reads the latest save after a game session. */
    private var resumes by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Link.start(this)
        SkyrimEngine.start(this)
        SkyrimEngine.prepareEngine(this)   // first install: download the engine while the player sets up
        LauncherSettings.init(this)
        GameFolder.init(this)
        ModManager.init(this)
        hasFileAccess = Environment.isExternalStorageManager()
        BottomScreenHost.init(application)
        hideSystemBars()
        // Back on the launcher leaves the app like any other: both screens go to the background together
        // (on its own, Back moved only this screen). An open warning closes first.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    modWarning != null -> modWarning = null
                    saveWarning != null -> saveWarning = null
                    launching -> Unit
                    else -> BottomScreenHost.leaveApp()
                }
            }
        })

        setContent {
            SkyrimAynTheme {
                val gameDir by GameFolder.dir.collectAsState()
                val steam by SkyrimEngine.steam.collectAsState()
                val setup = !hasFileAccess || gameDir == null || (steam != SteamState.LOGGED_IN && steam != SteamState.OFFLINE)
                Box(Modifier.fillMaxSize()) {
                when {
                    !hasFileAccess -> FileAccessStep(onAllow = ::requestAllFilesAccess)
                    gameDir == null -> FolderStep()
                    steam != SteamState.LOGGED_IN && steam != SteamState.OFFLINE -> SteamStep(steam)
                    else -> {
                        // Updating from 1.0.2 or older: installed mods move into the app's storage once (see ModOverlay).
                        val moving by ModOverlay.moving.collectAsState()
                        TitleScreen(onPlay = ::play, refresh = resumes, busy = launching || moving != null,
                            hint = moving?.let { (done, all) -> "Moving your mods into the app for faster loading (one time)… $done of $all" } ?: playHint)
                        modWarning?.let { broken ->
                            ModWarning(broken,
                                onPlayAnyway = { modWarning = null; play(checkMods = false, checkSave = true) },
                                onWithoutMods = { modWarning = null; LauncherSettings.set(LauncherSettings.mods, "Disabled"); play(checkMods = false, checkSave = true) },
                                onCancel = { modWarning = null })
                        }
                        saveWarning?.let { problem ->
                            SaveWarning(problem,
                                onPlayAnyway = { saveWarning = null; SaveGames.acknowledge(this@MainActivity, problem); play(checkMods = false) },
                                onCancel = { saveWarning = null })
                        }
                    }
                }
                // First run: the language can be changed before anything else (English until chosen).
                if (setup) SetupLanguage(Modifier.align(Alignment.TopEnd).padding(24.dp))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasFileAccess = Environment.isExternalStorageManager()
        SkyrimEngine.ensureSteam(this)
        resumes++
        Updater.resumeIfPermitted(this)    // back from "allow installs": continue the update
        SkyrimEngine.prepareEngine(this)   // resumes after a failed/offline attempt; no-op once prepared
        // Back from a game session: the launch is over.
        if (!launching && playHint == "Starting Skyrim…") playHint = null
        if (SkyrimEngine.autoRetry) { SkyrimEngine.autoRetry = false; window.decorView.postDelayed({ play(checkMods = false) }, 1500) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (GameFolder.dir.value != null && hasFileAccess &&
            (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_BUTTON_START)
        ) {
            play(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private var launching by mutableStateOf(false)

    /** Prepares the game folder and container, then hands over to the built-in engine. */
    /** Plugins that need masters you don't have: the game would crash on load. Shown before Play. */
    private var modWarning by mutableStateOf<List<PluginEntry>?>(null)

    /** The newest save may not load with the current mods: shown before Play. */
    private var saveWarning by mutableStateOf<SaveGames.Problem?>(null)

    private fun play() = play(checkMods = true)

    private fun play(checkMods: Boolean, checkSave: Boolean = checkMods) {
        SkyrimEngine.launchNotice.value = null
        if (launching) return
        val dir = GameFolder.modded() ?: return
        if (checkMods || checkSave) {
            lifecycleScope.launch {
                val withMods = LauncherSettings.get(LauncherSettings.mods) == "Enabled"
                LoadOrder.load(this@MainActivity, dir)
                if (checkMods && withMods) {
                    LoadOrder.autoSort(dir)
                    val broken = LoadOrder.plugins.value.filter { it.active && it.missingMasters.isNotEmpty() }
                    if (broken.isNotEmpty()) { modWarning = broken; return@launch }
                }
                if (checkSave) {
                    val active = LoadOrder.plugins.value.filter { it.locked || (withMods && it.active) }.map { it.name.lowercase() }.toSet()
                    SaveGames.check(this@MainActivity, active)?.let { saveWarning = it; return@launch }
                }
                play(checkMods = false)
            }
            return
        }
        launching = true
        lifecycleScope.launch {
            try {
                // Still preparing the engine (first install): wait, showing where it's at.
                val prepJob = SkyrimEngine.prepareEngine(this@MainActivity)
                if (!prepJob.isCompleted) {
                    val show = launch { SkyrimEngine.prep.collect { p -> if (p != null) playHint = p.step + "…" + if (p.progress >= 0f) " ${(p.progress * 100).toInt()}%" else "" } }
                    prepJob.await()
                    show.cancel()
                }
                playHint = "Preparing the companion…"
                SkyrimEngine.installSkse(this@MainActivity, dir)
                playHint = "Installing graphics drivers…"
                SkyrimEngine.installDrivers(this@MainActivity)
                playHint = "Linking your Skyrim to Steam…"
                if (!SkyrimEngine.registerGame(dir)) {
                    playHint = "Couldn't link this folder to Skyrim SE on your Steam account. Is the folder named \"Skyrim Special Edition\" and do you own the game?"
                    return@launch
                }
                playHint = "Preparing the game…"
                SkyrimEngine.configureContainer(this@MainActivity)
                playHint = "Starting Skyrim…"
                SkyrimEngine.launch(this@MainActivity)
            } catch (e: Exception) {
                timber.log.Timber.e(e, "SkyrimAYN: launch failed")
                playHint = "Couldn't start: ${e.message}"
            } finally {
                launching = false
            }
        }
    }

    private fun requestAllFilesAccess() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

}

// ---------------------------------------------------------------- shared look

@Composable
internal fun Backdrop(content: @Composable () -> Unit) {
    // Slow drifting mist behind the title, like the game's main menu smoke.
    val drift = androidx.compose.animation.core.rememberInfiniteTransition(label = "mist")
    val t by drift.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(90_000, easing = androidx.compose.animation.core.LinearEasing)), label = "t")
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF26252A), Color(0xFF0B0B0D)), radius = 1400f))
            .drawBehind {
                val tile = size.height * 1.6f
                listOf(0.07f to 1f, 0.05f to -0.6f).forEach { (alpha, speed) ->
                    val off = ((t * speed * tile) % tile + tile) % tile - tile
                    var x = off
                    while (x < size.width) {
                        drawImage(WorldMaps.clouds, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(256, 256),
                            dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), (size.height - tile).toInt() / 2),
                            dstSize = androidx.compose.ui.unit.IntSize(tile.toInt() + 1, tile.toInt()), alpha = alpha)
                        x += tile
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
        // First install: the engine downloads in the background while you set up. A quiet line at the bottom.
        val prep by SkyrimEngine.prep.collectAsState()
        // Only when there's real work: the quick re-check on later opens shouldn't flash a progress line.
        var showPrep by remember { mutableStateOf(false) }
        LaunchedEffect(prep != null) { showPrep = false; if (prep != null) { delay(1500); showPrep = true } }
        if (showPrep) prep?.let { p ->
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).width(420.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                SkyText(p.step.uppercase() + if (p.progress >= 0f) "  ·  ${(p.progress * 100).toInt()}%" else "…",
                    10.sp, Sky.Dim, 3.sp, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0x22FFFFFF))) {
                    if (p.progress >= 0f) Box(Modifier.fillMaxWidth(p.progress.coerceIn(0.02f, 1f)).height(2.dp).background(Palette.Gold.copy(alpha = 0.7f)))
                }
            }
        }
    }
}

@Composable
private fun GoldButton(label: String, onClick: () -> Unit, autoFocus: Boolean = true, wide: Boolean = false) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    if (autoFocus) LaunchedEffect(label) { delay(300); runCatching { focus.requestFocus() } }
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(2.dp),
        border = BorderStroke(if (focused) 2.dp else 1.dp, Palette.Gold.copy(alpha = if (focused) 1f else 0.6f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (focused) Palette.Gold.copy(alpha = 0.12f) else Color.Transparent,
            contentColor = Palette.Text,
        ),
        modifier = Modifier
            .width(if (wide) 420.dp else 280.dp)
            .height(64.dp)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused },
    ) {
        Text(label.uppercase(), fontFamily = FontFamily.Serif, fontSize = 22.sp, letterSpacing = 6.sp)
    }
}

@Composable
private fun SkyrimLogo(compact: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "SKYRIM",
            color = Color(0xFFEDEAE3), fontFamily = FontFamily.Serif,
            fontSize = if (compact) 56.sp else 96.sp, letterSpacing = if (compact) 12.sp else 18.sp,
        )
        Box(
            Modifier.padding(top = 6.dp).width(if (compact) 260.dp else 420.dp).height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Palette.Gold, Color.Transparent))),
        )
        Spacer(Modifier.size(if (compact) 8.dp else 12.dp))
        Text(
            "SPECIAL EDITION", color = Palette.Gold, fontFamily = FontFamily.Serif,
            fontSize = if (compact) 13.sp else 18.sp, letterSpacing = if (compact) 7.sp else 10.sp,
        )
    }
}

@Composable
private fun StepText(title: String, body: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 760.dp)) {
        Text(title, color = Palette.Text, fontFamily = FontFamily.Serif, fontSize = 30.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, color = Palette.Muted, fontSize = 17.sp, textAlign = TextAlign.Center, lineHeight = 24.sp)
    }
}

// ---------------------------------------------------------------- first boot

/** "STEP 2 OF 3  ·  YOUR GAME": where you are in first-time setup. */
@Composable
private fun StepLabel(n: Int, what: String) {
    SkyText("STEP $n OF 3  ·  $what", 12.sp, Sky.Dim, 4.sp, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
internal fun FileAccessStep(onAllow: () -> Unit) = Backdrop {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SkyrimLogo(compact = true)
        Spacer(Modifier.height(40.dp))
        StepLabel(1, "FILES")
        StepText(
            "Welcome",
            "To play, this app needs to read your copy of Skyrim from your device's storage. " +
                "Allow file access on the next screen. It's only used for your Skyrim folder, its saves and the mods you install.",
        )
        Spacer(Modifier.height(36.dp))
        GoldButton("Allow access", onAllow, wide = true)
    }
}

@Composable
internal fun FolderStep() = Backdrop {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = GameFolder.pathFromTreeUri(uri)
        if (path == null) { problem = "Couldn't read that location. Try a folder on internal storage or the SD card."; return@rememberLauncherForActivityResult }
        checking = true
        scope.launch {
            val result = GameFolder.check(path)
            checking = false
            if (result.ok) GameFolder.save(result.path) else problem = result.problem
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SkyrimLogo(compact = true)
        Spacer(Modifier.height(40.dp))
        StepLabel(2, "YOUR GAME")
        StepText(
            "Select your Skyrim folder",
            "Copy the \"Skyrim Special Edition\" folder from your PC's Steam library (steamapps/common) to the Thor, then choose it here. " +
                "It's the one with SkyrimSE.exe and the Data folder. Nothing is downloaded: you play the copy you own.",
        )
        Spacer(Modifier.height(36.dp))
        GoldButton(if (checking) "Checking…" else "Select folder", { if (!checking) { problem = null; picker.launch(null) } }, wide = true)
        Spacer(Modifier.height(20.dp))
        AnimatedVisibility(visible = problem != null, enter = fadeIn(), exit = fadeOut()) {
            Text(problem ?: "", color = Palette.Bad, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 760.dp))
        }
    }
}

// ---------------------------------------------------------------- title

@Composable
private fun SteamStep(state: SteamState) = Backdrop {
    if (state == SteamState.NO_INTERNET) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SkyrimLogo(compact = true)
            Spacer(Modifier.height(48.dp))
            StepText("No internet connection", "The first sign-in to Steam needs Wi-Fi. After that you can play offline too. Turn on Wi-Fi and this continues by itself.")
        }
        return@Backdrop
    }
    // With a saved session, a moment of "signed out" is just Steam reconnecting (after a game, or Wi-Fi back):
    // keep saying "Connecting…" for a while rather than flashing the sign-in form.
    var graceOver by remember { mutableStateOf(!SkyrimEngine.canPlayOffline()) }
    LaunchedEffect(Unit) { delay(20_000); graceOver = true }
    if (state == SteamState.STARTING || !graceOver) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SkyrimLogo(compact = true)
            Spacer(Modifier.height(48.dp))
            var waited by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) { while (true) { delay(1000); waited++ } }
            StepText("Connecting to Steam…", if (waited < 12) "Signing you in with your saved Steam session." else "This is taking longer than usual. Check that Wi-Fi is on. SkyrimSE-DualScreen keeps retrying.")
            if (waited >= 12) {
                Spacer(Modifier.height(24.dp))
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GoldButton("Try again", { waited = 0; SkyrimEngine.reconnectSteam(ctx) }, autoFocus = true)
                    if (SkyrimEngine.canPlayOffline()) {
                        Spacer(Modifier.width(20.dp))
                        GoldButton("Play offline", { SkyrimEngine.playOffline = true })
                    }
                }
            }
        }
        return@Backdrop
    }
    LaunchedEffect(Unit) { SteamLogin.startQr() }
    val ui by SteamLogin.ui.collectAsState()
    SteamSignInPage(ui)
}

/** The Steam sign-in page (split out so it can be rendered in tests). */
@Composable
internal fun SteamSignInPage(ui: LoginUi) {
    // Scrolls when it doesn't fit (the on-screen keyboard takes half the screen), and stays clear of the keyboard.
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()
        .imePadding().verticalScroll(androidx.compose.foundation.rememberScrollState())
        .padding(horizontal = 48.dp, vertical = 16.dp)) {
        StepLabel(3, "STEAM")
        Text("Sign in to Steam", color = Palette.Text, fontFamily = FontFamily.Serif, fontSize = 30.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Skyrim checks with Steam that you own it, just like on PC. Sign in once and it signs in by itself after that, even offline. " +
                "Your password goes straight to Steam and is never stored.",
            color = Palette.Muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 640.dp),
        )
        Spacer(Modifier.height(18.dp))
        when (val s = ui) {
            is LoginUi.Ready -> SignInForms(s)
            is LoginUi.Working -> StepText(s.message, "")
            is LoginUi.Approve -> StepText("Check your phone", "Approve this sign-in in the Steam Mobile app.")
            is LoginUi.NeedCode -> CodeForm(s)
        }
    }
}

@Composable
private fun SignInForms(s: LoginUi.Ready) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Top) {
        // QR: scan with the Steam Mobile app (Steam Guard tab → scan icon).
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(240.dp)) {
            Box(
                Modifier.size(200.dp).background(Color.White, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val url = s.qrUrl
                if (url == null) {
                    androidx.compose.material3.CircularProgressIndicator(color = Palette.Gold)
                } else {
                    val bmp = remember(url) { SteamLogin.qrBitmap(url, 480).asImageBitmap() }
                    androidx.compose.foundation.Image(bmp, contentDescription = "Steam sign-in QR code", modifier = Modifier.size(184.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Scan with the Steam app", color = Palette.Text, fontSize = 16.sp)
            Text("Steam Guard → scan icon", color = Palette.Muted, fontSize = 13.sp)
        }

        Box(Modifier.padding(horizontal = 28.dp).width(1.dp).height(200.dp).background(Palette.SurfaceHigh))

        Column(modifier = Modifier.width(360.dp)) {
            Text("Or use your account name", color = Palette.Muted, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            SkyrimField(user, { user = it }, "Steam account name")
            Spacer(Modifier.height(8.dp))
            SkyrimField(pass, { pass = it }, "Password", password = true)
            Spacer(Modifier.height(14.dp))
            GoldButton("Sign in", { SteamLogin.signIn(user, pass) }, autoFocus = false, wide = true)
            s.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Palette.Bad, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun CodeForm(s: LoginUi.NeedCode) {
    var code by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(520.dp)) {
        Text(
            if (s.email == null) "Enter the Steam Guard code from your Steam app" else "Enter the code Steam sent to ${s.email}",
            color = Palette.Text, fontSize = 18.sp, textAlign = TextAlign.Center,
        )
        if (s.wrongCode) {
            Spacer(Modifier.height(6.dp))
            Text("That code didn't work. Try the new one.", color = Palette.Bad, fontSize = 14.sp)
        }
        Spacer(Modifier.height(16.dp))
        SkyrimField(code, { code = it.take(8) }, "Code")
        Spacer(Modifier.height(20.dp))
        GoldButton("Continue", { SteamLogin.submitCode(code) }, autoFocus = false, wide = true)
    }
}

@Composable
internal fun SkyrimField(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (password) androidx.compose.ui.text.input.KeyboardType.Password
            else androidx.compose.ui.text.input.KeyboardType.Ascii,
            autoCorrectEnabled = false,
        ),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Gold,
            unfocusedBorderColor = Palette.Muted,
            focusedLabelColor = Palette.Gold,
            cursorColor = Palette.Gold,
            focusedTextColor = Palette.Text,
            unfocusedTextColor = Palette.Text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TitleScreen(onPlay: () -> Unit, hint: String?, busy: Boolean, refresh: Int = 0) = Backdrop {
    val context = androidx.compose.ui.platform.LocalContext.current
    var save by remember { mutableStateOf<SaveGames.Save?>(null) }
    // Re-read on every return to the launcher (a game session just saved), and once more a moment later
    // in case the game was still finishing the file.
    LaunchedEffect(refresh) { save = SaveGames.latest(context); kotlinx.coroutines.delay(4000); save = SaveGames.latest(context) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            SkyrimLogo()
            Spacer(Modifier.height(36.dp))
            GoldButton(if (busy) "Starting…" else "Play", onPlay)
            // The last save, right under Play, like the game's Continue: its screenshot, who, where.
            save?.let { s -> Spacer(Modifier.height(18.dp)); ContinueCard(s, Modifier) }
            Spacer(Modifier.height(12.dp))
            AnimatedVisibility(visible = hint != null, enter = fadeIn(), exit = fadeOut()) {
                Text(hint ?: "", color = Palette.Muted, fontSize = 14.sp)
            }
        }
        val update by Updater.state.collectAsState()
        LaunchedEffect(Unit) { Updater.check(context) }
        when (val u = update) {
            is Updater.State.Available -> SkyText("UPDATE ${u.release.version} AVAILABLE  ·  TAP TO INSTALL", 11.sp, Sky.Text, 3.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).clickable { Updater.install(context, u.release) }
                    .background(Color(0x99000000)).border(1.dp, Color(0x55FFFFFF)).padding(horizontal = 14.dp, vertical = 6.dp))
            is Updater.State.Downloading -> SkyText("DOWNLOADING UPDATE ${u.release.version}  ·  ${(u.progress * 100).toInt()}%", 11.sp, Sky.Dim, 3.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 6.dp))
            else -> {}
        }
        val notice by SkyrimEngine.launchNotice.collectAsState()
        notice?.let { n ->
            Column(Modifier.align(Alignment.TopCenter).padding(top = 28.dp).widthIn(max = 900.dp).background(Color(0xCC000000))
                .border(1.dp, Color(0x55FFFFFF)).clickable { SkyrimEngine.launchNotice.value = null }.padding(horizontal = 18.dp, vertical = 10.dp)) {
                SkyText(n, 14.sp, Sky.Text, 0.5.sp, maxLines = 2)
            }
        }
        val steamState by SkyrimEngine.steam.collectAsState()
        if (steamState == SteamState.OFFLINE)
            SkyText("OFFLINE  ·  STEAM RECONNECTS WHEN WI-FI IS BACK", 11.sp, Sky.Dim, 3.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 6.dp))
        val plugins by LoadOrder.plugins.collectAsState()
        val modsOn = LauncherSettings.get(LauncherSettings.mods) == "Enabled"
        val mods by ModManager.mods.collectAsState()
        LaunchedEffect(Unit) { ModManager.load(); GameFolder.modded()?.let { LoadOrder.load(context, it) } }
        // Mods, not plugins: SKSE plugins and frameworks have no plugin file but are just as active.
        val active = mods.size.takeIf { it > 0 } ?: plugins.count { !it.locked && it.active }
        Column(Modifier.align(Alignment.BottomEnd).padding(28.dp), horizontalAlignment = Alignment.End) {
            SkyText(if (!modsOn) "MODS OFF" else if (active == 0) "NO MODS" else "$active MOD${if (active == 1) "" else "S"} ACTIVE", 11.sp, Sky.Dim, 3.sp)
            SkyText("SKYRIM SE  ·  DUAL SCREEN", 11.sp, Sky.Faint, 4.sp)
        }
    }
}

@Composable
private fun ContinueCard(s: SaveGames.Save, modifier: Modifier) {
    // One quiet strip the width of the Play button: no box competing with it.
    // Sized to its content (no fill), so the title column centres it under Play.
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        s.shot?.let {
            androidx.compose.foundation.Image(it.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.width(78.dp).height(44.dp).border(1.dp, Color(0x44FFFFFF)))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.widthIn(max = 280.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkyFitText(s.name.uppercase(), 14.sp, 10.sp, Sky.Text, 2.5.sp, modifier = Modifier.weight(1f, fill = false))
                SkyText("  ·  LEVEL ${s.level}", 11.sp, Sky.Body, 1.5.sp)
            }
            SkyFitText(listOfNotNull(s.race.takeIf { it.isNotBlank() }, s.location.takeIf { it.isNotBlank() }).joinToString("  ·  ").uppercase(),
                10.sp, 8.sp, Sky.Dim, 1.5.sp, modifier = Modifier.widthIn(max = 280.dp))
            SkyText("LAST SAVED " + android.text.format.DateUtils.getRelativeTimeSpanString(s.modified).toString().uppercase(), 9.sp, Sky.Faint, 1.5.sp)
        }
    }
}

/** The newest save is damaged, or was made with mods that are now off: say so before it crashes on load. */
@Composable
private fun SaveWarning(problem: SaveGames.Problem, onPlayAnyway: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(620.dp).background(Color(0xFF0B0B0B)).border(1.dp, Color(0x55FFFFFF)).padding(24.dp)) {
            when (problem) {
                is SaveGames.Problem.Damaged -> {
                    SkyText("YOUR LATEST SAVE LOOKS DAMAGED", 20.sp, Sky.Text, 4.sp)
                    Rule(Modifier.padding(vertical = 10.dp), color = Sky.Faint)
                    SkyText("Loading ${problem.save.nameWithoutExtension.take(40)} will probably crash Skyrim. Load an older save from the game's menu instead.",
                        14.sp, Sky.Body, 0.3.sp, light = true, maxLines = 3)
                }
                is SaveGames.Problem.MissingMods -> {
                    SkyText("YOUR LATEST SAVE USES MODS THAT ARE OFF", 20.sp, Sky.Text, 3.sp)
                    Rule(Modifier.padding(vertical = 10.dp), color = Sky.Faint)
                    problem.mods.take(6).forEach { SkyText(it, 15.sp, Sky.Body, 0.3.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                    if (problem.mods.size > 6) SkyText("…and ${problem.mods.size - 6} more", 13.sp, Sky.Dim, 0.5.sp)
                    SkyText("Loading this save can crash Skyrim. Install or turn those mods back on, or start a new game or load another save from the game's menu.",
                        13.sp, Sky.Dim, 0.3.sp, light = true, maxLines = 3, modifier = Modifier.padding(top = 10.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                SkyText("PLAY ANYWAY ›", 15.sp, Sky.Text, 3.sp, modifier = Modifier.clickable(onClick = onPlayAnyway)
                    .border(1.dp, Color(0x99FFFFFF)).padding(horizontal = 14.dp, vertical = 9.dp))
                Spacer(Modifier.weight(1f))
                SkyText("BACK", 13.sp, Sky.Dim, 2.sp, modifier = Modifier.clickable(onClick = onCancel).padding(9.dp))
            }
        }
    }
}

/** "These mods need files you don't have": play anyway, play without mods, or go back. */
@Composable
private fun ModWarning(broken: List<PluginEntry>, onPlayAnyway: () -> Unit, onWithoutMods: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(620.dp).background(Color(0xFF0B0B0B)).border(1.dp, Color(0x55FFFFFF)).padding(24.dp)) {
            SkyText("SOME MODS ARE MISSING FILES", 20.sp, Sky.Text, 4.sp)
            Rule(Modifier.padding(vertical = 10.dp), color = Sky.Faint)
            broken.take(6).forEach { p ->
                SkyText("${p.name}  needs  ${p.missingMasters.joinToString(", ")}", 15.sp, Sky.Body, 0.3.sp, maxLines = 2,
                    modifier = Modifier.padding(vertical = 3.dp))
            }
            if (broken.size > 6) SkyText("…and ${broken.size - 6} more", 13.sp, Sky.Dim, 0.5.sp)
            SkyText("Skyrim usually crashes on load like this. Get the missing mods in the Mod Manager, or turn these plugins off.",
                13.sp, Sky.Dim, 0.3.sp, light = true, maxLines = 3, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                SkyText("PLAY WITHOUT MODS ›", 15.sp, Sky.Text, 3.sp, modifier = Modifier.clickable(onClick = onWithoutMods)
                    .border(1.dp, Color(0x99FFFFFF)).padding(horizontal = 14.dp, vertical = 9.dp))
                Spacer(Modifier.width(18.dp))
                SkyText("PLAY ANYWAY", 13.sp, Sky.Body, 2.sp, modifier = Modifier.clickable(onClick = onPlayAnyway).padding(9.dp))
                Spacer(Modifier.weight(1f))
                SkyText("BACK", 13.sp, Sky.Dim, 2.sp, modifier = Modifier.clickable(onClick = onCancel).padding(9.dp))
            }
        }
    }
}

/** First-run language choice, top right of the setup screens. */
@Composable
private fun SetupLanguage(modifier: Modifier) {
    val values by LauncherSettings.values.collectAsState()
    val current = values[LauncherSettings.language.key] ?: LauncherSettings.language.default
    var open by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.End) {
        SkyText("LANGUAGE  ·  ${current.uppercase()}  ${if (open) "▴" else "▾"}", 13.sp, Sky.Dim, 2.sp,
            modifier = Modifier.clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp))
        if (open) Column(
            Modifier.background(Color(0xEE141414)).border(1.dp, Sky.Line).padding(vertical = 6.dp)
                .height(360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalAlignment = Alignment.End,
        ) {
            LauncherSettings.language.options.filter { it != "System" }.forEach { name ->
                SkyText(name, 16.sp, if (name == current) Sky.Text else Sky.Body, 0.5.sp,
                    modifier = Modifier.clickable { LauncherSettings.set(LauncherSettings.language, name); open = false }
                        .padding(horizontal = 18.dp, vertical = 7.dp))
            }
        }
    }
}

package com.skyrimayn

import app.gamenative.NetworkMonitor
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Display
import app.gamenative.PrefManager
import app.gamenative.service.SteamService
import kotlinx.coroutines.async
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.Container
import com.winlator.contents.AdrenotoolsManager
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.zip.ZipInputStream

enum class SteamState {
    STARTING, NEEDS_LOGIN, LOGGED_IN,
    /** No internet, but signed in before: Skyrim starts in Steam's offline mode and Steam reconnects later. */
    OFFLINE,
    /** No internet and never signed in: nothing to do until Wi-Fi is on. */
    NO_INTERNET,
}

/**
 * SkyrimSE-DualScreen's bridge to the built-in engine (Wine + FEX/Box64 + DXVK + Turnip + Steam, from GameNative).
 * Our screens call this; nothing of GameNative's own UI is shown except its Steam sign-in.
 */
object SkyrimEngine {
    const val STEAM_APP_ID = 489830
    const val CONTAINER_ID = "STEAM_489830"
    const val SYSTEM_DRIVER = "System"
    /** Version of the bundled SKSE plugin (SkyrimAYN.dll). */
    const val PLUGIN_VERSION = "0.20"
    const val DEFAULT_DRIVER = "Turnip Adreno Driver T30 (@Mr_Purple_666)"
    /** Proton build Skyrim is tested with (arm64ec: the game's x86-64 code runs through FEX inside Wine). */
    const val WINE_VERSION = "proton-10.0-arm64ec-2"
    const val DXVK_VERSION = "2.6.1-gplasync"
    /** FEX build the release is tested on. */
    const val FEXCORE_VERSION = "2610-gn-5"
    /** Proxy/preloader DLLs mods drop next to SkyrimSE.exe; when present, Wine must load them instead of its own. */
    private val PRELOADER_DLLS = listOf("d3dx9_42", "version", "winhttp", "winmm", "dsound", "xinput1_3")

    /** [existing] overrides plus n,b for our dinput8 and any preloader DLL found in [gameDir] (replacing earlier entries). */
    internal fun dllOverrides(existing: String, gameDir: File?): String {
        val present = gameDir?.listFiles()?.map { it.name.lowercase() }?.toSet().orEmpty()
        val native = listOf("dinput8") + PRELOADER_DLLS.filter { "$it.dll" in present }
        return (existing.split(';').map { it.trim() }
            .filter { o -> o.isNotEmpty() && native.none { o.lowercase().startsWith("$it=") } } +
            native.map { "$it=n,b" }).joinToString(";")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _steam = MutableStateFlow(SteamState.STARTING)
    val steam: StateFlow<SteamState> = _steam
    private val _drivers = MutableStateFlow(listOf(SYSTEM_DRIVER))
    val drivers: StateFlow<List<String>> = _drivers
    private var started = false

    /** Starts the Steam service (it signs in by itself with a saved session) and watches the login state. */
    private var appCtx: Context? = null

    fun start(context: Context) {
        appCtx = context.applicationContext
        ensureSteam(context)
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            val t0 = System.currentTimeMillis()
            var online = NetworkMonitor.hasInternet.value
            var lastKick = 0L
            var lastEnsure = 0L
            while (true) {
                val waitedLong = System.currentTimeMillis() - t0 > 10_000
                val hasSession = PrefManager.refreshToken.isNotEmpty()
                val internet = NetworkMonitor.hasInternet.value
                val now = System.currentTimeMillis()
                // The engine stops the Steam service when a game session ends; bring it back for the launcher.
                if (!SteamService.isRunning && !gameScreenUp() && now - lastEnsure > 3_000) { lastEnsure = now; ensureSteam(app) }
                // Wi-Fi back (or a long stall with Wi-Fi on): connect now rather than wait out Steam's backoff.
                if (!SteamService.isLoggedIn && internet && (!online || now - lastKick > 45_000) &&
                    !isLaunching() && !gameScreenUp()) {
                    lastKick = now
                    SteamService.instance?.connectNow()
                }
                online = internet
                val state = when {
                    SteamService.isLoggedIn -> SteamState.LOGGED_IN
                    (!internet || playOffline) && hasSession && now - t0 > 3_000 -> SteamState.OFFLINE
                    !internet && !hasSession && now - t0 > 3_000 -> SteamState.NO_INTERNET
                    !SteamService.isConnected -> SteamState.STARTING
                    !hasSession || waitedLong -> SteamState.NEEDS_LOGIN
                    else -> SteamState.STARTING // saved session: signing in by itself
                }
                if (state == SteamState.LOGGED_IN && _steam.value != SteamState.LOGGED_IN) SteamLogin.stop()
                _steam.value = state
                delay(500)
            }
        }
        scope.launch { installDrivers(app) }
    }

    private fun gameScreenUp() = app.gamenative.PluviaApp.xServerView != null

    /** Something the launcher should tell the player after a launch went wrong (shown on the title screen). */
    val launchNotice = MutableStateFlow<String?>(null)

    /**
     * Wine exited. If Skyrim's window never appeared, the launch failed: with frame generation on, that
     * layer is the usual cause (it can crash Wine on start), so switch it off for the next try and say so.
     */
    /** Set when a first-ever launch ended early: the launcher presses Play once more by itself. */
    @Volatile var autoRetry = false
    /** Skyrim's process quit before its window appeared (see [launch]); [onGuestExited] relaunches it. */
    @Volatile private var steamRestart = false
    @Volatile private var quickRetries = 0
    @Volatile private var retrying = false

    /** True while a SkyrimSE.exe process runs in the engine (the app's own processes, read from /proc). */
    private fun skyrimProcessRunning(): Boolean = engineProcesses().first

    /** (Skyrim or its SKSE loader is running, the engine's launcher winhandler.exe is running), from /proc. */
    private fun engineProcesses(): Pair<Boolean, Boolean> {
        var game = false; var launcher = false
        for (d in File("/proc").listFiles() ?: emptyArray()) {
            if (!d.name.all(Char::isDigit)) continue
            val exe = runCatching { File(d, "cmdline").readBytes().toString(Charsets.UTF_8).split(0.toChar()).getOrNull(1) }.getOrNull() ?: continue
            if (exe.endsWith("SkyrimSE.exe", true) || exe.endsWith("skse64_loader.exe", true)) game = true
            else if (exe.equals("winhandler.exe", true)) launcher = true
        }
        return game to launcher
    }

    /** Set when the 5-minute watchdog closed a launch that never showed Skyrim. */
    @Volatile private var launchStuck = false
    /** The current launch (its start time); cleared when Wine exits, so an old watchdog never fires. */
    @Volatile private var launchToken = 0L
    /** No launch has reached Skyrim's window on this install yet. */
    fun firstLaunch() = !everStarted()
    private fun everStarted() = appCtx?.getSharedPreferences("launcher", 0)?.getBoolean("ever_started", false) == true

    fun onGuestExited(status: Int) {
        launchStartedAt = 0L
        launchToken = 0L
        val shown = _gameWindow.value
        _gameWindow.value = false
        if (shown) { appCtx?.getSharedPreferences("launcher", 0)?.edit()?.putBoolean("ever_started", true)?.apply(); return }
        if (steamRestart) {
            steamRestart = false
            if (quickRetries < 2) {
                quickRetries++
                retrying = true
                Timber.w("SkyrimAYN: relaunching after an early quit (try $quickRetries)")
                launchNotice.value = "Steam is still getting ready. Starting Skyrim again…"
                autoRetry = true
                return
            }
        }
        // First run on a new install: setup can still be settling (Wine prefix, Steam client), so try once more.
        if (!everStarted() && LauncherSettings.get(LauncherSettings.frameGen) == "Off" &&
            appCtx?.getSharedPreferences("launcher", 0)?.getBoolean("first_retry_done", false) != true) {
            appCtx?.getSharedPreferences("launcher", 0)?.edit()?.putBoolean("first_retry_done", true)?.apply()
            Timber.w("SkyrimAYN: first launch ended early (status $status); retrying once")
            launchNotice.value = "Finishing first-time setup…"
            autoRetry = true
            return
        }
        Timber.w("SkyrimAYN: launch ended before Skyrim's window (status $status)")
        if (LauncherSettings.get(LauncherSettings.frameGen) != "Off") {
            LauncherSettings.set(LauncherSettings.frameGen, "Off")
            launchNotice.value = "Skyrim didn't start with Frame Generation on, so it's been turned off. Press Play again."
        } else if (launchStuck) {
            launchStuck = false
            launchNotice.value = "Skyrim took too long to start, so it was closed. Press Play to try again; if it keeps happening, restart the Thor."
        } else if (status != 0) {
            launchNotice.value = "Skyrim didn't start. Press Play to try again; if it keeps happening, restart the Thor."
        }
    }

    /** Set when the player chooses to play without waiting for Steam (only possible after a first sign-in). */
    @Volatile var playOffline = false

    /** This launch can't reach Steam: no internet, or the player chose to play offline. */
    fun offlineLaunch(): Boolean = playOffline || !NetworkMonitor.hasInternet.value ||
        // Test builds: a ".force_offline" file in the game folder simulates no internet (adb needs Wi-Fi).
        (app.gamenative.BuildConfig.DEBUG && GameFolder.dir.value?.let { File(it, ".force_offline").isFile } == true)
    fun canPlayOffline() = PrefManager.refreshToken.isNotEmpty()

    /** Try to reach Steam right now. */
    fun reconnectSteam(context: Context) {
        ensureSteam(context)
        SteamService.instance?.connectNow()
    }

    /** (Re)starts the Steam service. The engine stops it when its own screen closes. */
    fun ensureSteam(context: Context) {
        if (SteamService.instance != null && SteamService.isRunning) return
        val app = context.applicationContext
        runCatching { app.startForegroundService(Intent(app, SteamService::class.java)) }
            .onFailure { Timber.e(it, "SkyrimAYN: couldn't start SteamService") }
    }

    // ------------------------------------------------------------ drivers

    /** Installs the bundled Purple Turnip packages into the engine's driver store (once). */
    suspend fun installDrivers(context: Context) = withContext(Dispatchers.IO) {
        val mgr = AdrenotoolsManager(context)
        val installed = mgr.enumarateInstalledDrivers().map { mgr.getDriverName(it) }.toSet() + mgr.enumarateInstalledDrivers()
        context.assets.list("skyrimayn/drivers")?.forEach { asset ->
            val name = runCatching { metaName(context.assets.open("skyrimayn/drivers/$asset")) }.getOrNull()
            if (name == null || name in installed) return@forEach
            val tmp = File(context.cacheDir, asset)
            context.assets.open("skyrimayn/drivers/$asset").use { i -> tmp.outputStream().use { i.copyTo(it) } }
            val result = mgr.installDriver(Uri.fromFile(tmp))
            Timber.i("SkyrimAYN: installed driver '$result' from $asset")
            tmp.delete()
        }
        refreshDrivers(context)
    }

    /** Installs a user-picked adrenotools .zip. Returns the driver name, or "" if it wasn't a valid driver. */
    suspend fun importDriver(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val name = AdrenotoolsManager(context).installDriver(uri)
        refreshDrivers(context)
        name
    }

    fun refreshDrivers(context: Context) {
        val mgr = AdrenotoolsManager(context)
        val names = mgr.enumarateInstalledDrivers().sortedDescending()
        _drivers.value = names + SYSTEM_DRIVER
    }

    private fun metaName(input: java.io.InputStream): String? = ZipInputStream(input).use { zis ->
        var e = zis.nextEntry
        while (e != null) {
            if (e.name == "meta.json") return JSONObject(zis.readBytes().decodeToString()).optString("name").ifEmpty { null }
            e = zis.nextEntry
        }
        null
    }

    // ------------------------------------------------------------ game folder

    /** SKSE's own files are in the game folder (optional: some mods need it; the companion doesn't). */
    fun skseInstalled(gameDir: String) = File(gameDir, "skse64_loader.exe").isFile && File(gameDir, "skse64_1_7_104.dll").isFile

    /**
     * Copies the companion into the game folder: SkyrimAYN.dll plus our dinput8.dll, which loads it when SKSE
     * isn't installed. With SKSE installed, SKSE loads the plugin and dinput8.dll only forwards input calls.
     */
    suspend fun installSkse(context: Context, gameDir: String) = withContext(Dispatchers.IO) {
        fun copyTree(assetPath: String, dest: File) {
            val children = context.assets.list(assetPath).orEmpty()
            if (children.isEmpty()) {
                val bytes = context.assets.open(assetPath).use { it.readBytes() }
                // Compare contents, not just sizes: two plugin builds can be the same length.
                if (!dest.exists() || dest.length() != bytes.size.toLong() || !dest.readBytes().contentEquals(bytes)) {
                    dest.parentFile?.mkdirs()
                    dest.writeBytes(bytes)
                }
            } else {
                children.forEach { copyTree("$assetPath/$it", File(dest, it)) }
            }
        }
        copyTree("skyrimayn/skse", File(gameDir))
    }

    /**
     * Registers the user's folder as their installed Steam copy of Skyrim SE (app 489830), without copying.
     * Needs Steam signed in: the engine checks the account owns the game.
     */
    suspend fun registerGame(gameDir: String): Boolean = withContext(Dispatchers.IO) {
        // An earlier attempt may have tagged the folder as a non-Steam game; that would hide it from Steam.
        File(gameDir, ".gamenative").delete()
        repeat(30) {
            val existing = SteamService.getInstalledApp(STEAM_APP_ID)
            if (existing != null && existing.customInstallPath == gameDir) return@withContext true

            val steamApp = SteamService.getAppInfoOf(STEAM_APP_ID)
            if (steamApp == null) {
                Timber.i("SkyrimAYN: Skyrim app info not synced yet, waiting")
            } else if (!SteamService.isAppLicensed(steamApp.packageId)) {
                Timber.w("SkyrimAYN: this Steam account doesn't own Skyrim Special Edition (package ${steamApp.packageId})")
                return@withContext false
            } else {
                // Register the user's folder as the installed copy (like the engine's "import as Steam game", by app id).
                val depots = SteamService.getMainAppDepots(STEAM_APP_ID, PrefManager.containerLanguage)
                    .filter { (_, d) -> d.dlcAppId == SteamService.INVALID_APP_ID }
                    .keys.sorted()
                SteamService.instance?.appInfoDao?.insert(
                    app.gamenative.data.AppInfo(
                        STEAM_APP_ID,
                        isDownloaded = true,
                        downloadedDepots = depots,
                        dlcDepots = emptyList(),
                        branch = "public",
                        customInstallPath = gameDir,
                    ),
                )
                app.gamenative.utils.MarkerUtils.addMarker(gameDir, app.gamenative.enums.Marker.DOWNLOAD_COMPLETE_MARKER)
                Timber.i("SkyrimAYN: registered Skyrim SE at $gameDir (depots $depots)")
                if (SteamService.getInstalledApp(STEAM_APP_ID)?.customInstallPath == gameDir) return@withContext true
            }
            delay(2000) // app info may still be syncing right after sign-in
        }
        false
    }

    // ------------------------------------------------------------ container + launch

    /**
     * Some downloaded engine files (Proton's lsteamclient bridge) hard-code GameNative's package path.
     * Our package name has the same length, so swap it in place. Idempotent.
     */
    fun patchEngineFiles(context: Context) {
        val from = "app.gamenative".toByteArray()
        val to = context.packageName.toByteArray()
        if (from.size != to.size || from.contentEquals(to)) return
        val files = context.filesDir
        val targets = File(files, "imagefs_shared/proton").listFiles().orEmpty().flatMap { proton ->
            File(proton, "lib/wine").listFiles().orEmpty().map { File(it, "lsteamclient.so") }
        } + listOf("libredirect.so", "libredirect-bionic.so", "libredirect-bionic-wx.so").map { File(files, "imagefs/usr/lib/$it") }
        for (f in targets) {
            if (!f.isFile) continue
            val data = f.readBytes()
            var n = 0
            var i = 0
            while (i <= data.size - from.size) {
                if (data[i] == from[0] && (from.indices).all { data[i + it] == from[it] }) {
                    System.arraycopy(to, 0, data, i, to.size); n++; i += from.size
                } else i++
            }
            if (n > 0) {
                f.writeBytes(data)
                Timber.i("SkyrimAYN: patched package path in ${f.path} (${n}x)")
            }
        }
    }

    /** Applies the launcher settings to Skyrim's container. */
    /**
     * The runtime Skyrim is tested on, set explicitly: a fresh install's container would otherwise get the
     * engine's generic defaults (glibc + Wine 9.2 + VirGL/Vortek), which can't run it.
     */
    private fun applyRuntime(c: Container) {
        c.setContainerVariant(Container.BIONIC)
        c.setWineVersion(WINE_VERSION)
        if (c.graphicsDriver != "Wrapper") {
            c.setGraphicsDriver("Wrapper")
            c.setGraphicsDriverConfig(Container.DEFAULT_GRAPHICSDRIVERCONFIG)
        }
        // Exact versions: a new container copies the engine's global defaults, which differ between installs.
        if (c.fexCoreVersion != FEXCORE_VERSION) c.setFEXCoreVersion(FEXCORE_VERSION)
        if (c.dxWrapper != "dxvk" || KeyValueSet(c.dxWrapperConfig).get("version") != DXVK_VERSION) {
            c.setDXWrapper("dxvk")
            c.setDXWrapperConfig(KeyValueSet(Container.DEFAULT_DXWRAPPERCONFIG)
                .put("version", DXVK_VERSION).put("async", "1").put("asyncCache", "1").toString())
        }
        c.setLaunchRealSteam(false)
        c.setLaunchBionicSteam(true)
        c.setSteamType(Container.STEAM_TYPE_HEADLESS)
    }

    // ------------------------------------------------------------ engine preparation (first install)

    /** What the background preparation is doing, for the setup and title screens; null when idle or done. */
    data class Prep(val step: String, val progress: Float)
    private val _prep = MutableStateFlow<Prep?>(null)
    val prep: StateFlow<Prep?> = _prep
    private var prepJob: kotlinx.coroutines.Deferred<Boolean>? = null
    @Volatile private var prepared = false

    /**
     * Downloads and installs everything the first Play needs (engine image, Proton, Steam client, graphics
     * pieces, DXVK) in the background from the moment the app opens, so setup hides the wait instead of an
     * endless loading screen. Same steps as the engine's own pre-launch, in an order that never wipes a
     * finished step. Safe to call repeatedly; Play waits for it.
     */
    fun prepareEngine(context: Context): kotlinx.coroutines.Deferred<Boolean> {
        prepJob?.takeIf { it.isActive || (it.isCompleted && prepared) }?.let { return it }
        val ctx = context.applicationContext
        return scope.async(Dispatchers.IO) {
            fun step(s: String, p: Float = -1f) { _prep.value = Prep(s, p) }
            try {
                step("Preparing the game engine")
                // The first install needs a few GB (engine, Proton, Wine prefix): say so instead of failing midway.
                val engineReady = com.winlator.xenvironment.ImageFs.find(ctx).isValid
                val free = android.os.StatFs(ctx.filesDir.path).availableBytes
                if (!engineReady && free < 3L * 1024 * 1024 * 1024) {
                    launchNotice.value = "Not enough free space on the Thor: the game engine needs about 3 GB the first time " +
                        "(${free / (1024 * 1024)} MB free). Free some space, then open the app again."
                    return@async false
                }
                patchEngineFiles(ctx)
                val c = ContainerUtils.getOrCreateContainer(ctx, CONTAINER_ID)
                applyRuntime(c); c.saveData()
                val pct: (Float) -> Unit = { p -> _prep.value = _prep.value?.copy(progress = p.coerceIn(0f, 1f)) }
                val sc = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
                if (!SteamService.isImageFsInstallable(ctx, c.containerVariant)) {
                    step("Downloading the game engine", 0f)
                    SteamService.downloadImageFs(onDownloadProgress = pct, parentScope = sc, variant = c.containerVariant, context = ctx).await()
                }
                for (f in listOf("steam.tzst", "steam-token.tzst", "experimental-drm-20260116.tzst", app.gamenative.ui.REAL_STEAM_CLIENT_ARCHIVE)) {
                    if (SteamService.isFileInstallable(ctx, f)) continue
                    step("Downloading Steam support", 0f)
                    if (f == "steam.tzst") SteamService.downloadSteam(onDownloadProgress = pct, parentScope = sc, context = ctx).await()
                    else SteamService.downloadFile(onDownloadProgress = pct, parentScope = sc, context = ctx, fileName = f).await()
                }
                step("Installing the game engine", 0f)
                val ok = com.winlator.xenvironment.ImageFsInstaller.installIfNeededFuture(ctx, ctx.assets, c) { p -> pct(p / 100f) }.get()
                if (!ok) error("engine image install failed")
                val config = kotlinx.serialization.json.Json.parseToJsonElement(c.containerJson)
                    .let { it as kotlinx.serialization.json.JsonObject }
                for (r in app.gamenative.utils.BestConfigService.resolveMissingManifestInstallRequests(ctx, config, "exact_gpu_match")) {
                    val n = r.entry.name.lowercase()
                    step(when { "proton" in n || "wine" in n -> "Downloading Proton"; "dxvk" in n || "vkd3d" in n -> "Downloading DXVK"; else -> "Downloading engine parts" }, 0f)
                    runCatching { app.gamenative.utils.ManifestInstaller.installManifestEntry(ctx, r.entry, r.isDriver, r.contentType, pct) }
                }
                app.gamenative.utils.LaunchDependencies().ensureLaunchDependencies(ctx, c, app.gamenative.data.GameSource.STEAM, STEAM_APP_ID,
                    { _ -> step("Downloading Steam support") }, pct)
                for (id in listOf("wrapper", "extra_libs", "zink_dlls")) {
                    step("Downloading graphics files", 0f)
                    app.gamenative.utils.downloader.GraphicsDriverDownloader.ensureGraphicsDriverAvailable(ctx, id) { p -> pct(p) }
                }
                installDrivers(ctx)
                prepared = true
                Timber.i("SkyrimAYN: engine prepared")
                true
            } catch (e: Exception) {
                Timber.w(e, "SkyrimAYN: engine preparation incomplete (Play will finish it)")
                false
            } finally {
                _prep.value = null
            }
        }.also { prepJob = it }
    }

    suspend fun configureContainer(context: Context) = withContext(Dispatchers.IO) {
        patchEngineFiles(context)
        val c = ContainerUtils.getOrCreateContainer(context, CONTAINER_ID)
        val (w, h) = when (LauncherSettings.get(LauncherSettings.resolution)) {
            "1080p" -> 1920 to 1080
            "900p" -> 1600 to 900
            else -> 1280 to 720
        }
        c.setScreenSize("${w}x$h")
        GameFolder.dir.value?.let { gameDir ->
            runCatching {
                SkyrimIni.applyLauncherSettings(c.rootDir, gameDir, w, h, LauncherSettings.get(LauncherSettings.quality))
            }.onFailure { Timber.e(it, "SkyrimAYN: couldn't write Skyrim INIs") }
            runCatching { LoadOrder.writeToPrefix(context, c.rootDir) }
                .onFailure { Timber.e(it, "SkyrimAYN: couldn't write load order") }
        }
        applyRuntime(c)
        val driver = LauncherSettings.driver()
        c.setGraphicsDriverConfig(KeyValueSet(c.graphicsDriverConfig).put("version", driver).toString())
        c.setEmulator(if (LauncherSettings.get(LauncherSettings.translator) == "Box64") "Box64" else "FEXCore")
        // Bionic Steam: Valve's native ARM64 steamclient runs in-app (like DroidDeck), and Proton's
        // lsteamclient bridges the game's steam_api to it. Much faster than booting Windows Steam in Wine.
        c.setLaunchRealSteam(false)
        c.setLaunchBionicSteam(true)
        c.setSteamType(Container.STEAM_TYPE_HEADLESS)
        c.setUseLegacyDRM(false)
        c.setUnpackFiles(false)
        // Through SKSE when the player installed it (mods need it); otherwise straight to the game.
        val skse = GameFolder.dir.value?.let(::skseInstalled) == true
        c.setExecutablePath(if (skse) "skse64_loader.exe" else "SkyrimSE.exe")
        if (offlineLaunch()) {
            // No internet: Steam can't sign in, and Skyrim's copy protection won't start without it. Use the
            // engine's DRM-free path instead: SkyrimSE.exe with Steam's protection removed (Steamless, once;
            // the original is kept as SkyrimSE.exe.original.exe) and the engine's stand-in steam_api64.dll.
            // Online launches switch back to the originals by themselves (restoreSteamApi).
            c.setLaunchBionicSteam(false)
            c.setUseLegacyDRM(true)
            val unpacked = GameFolder.dir.value?.let { File(it, "SkyrimSE.exe.unpacked.exe").isFile } == true
            if (!unpacked) {
                c.setNeedsUnpacking(true)
                // Steamless processes the launch exe; with SKSE that's the loader, so scan for SkyrimSE.exe too.
                c.setUnpackFiles(skse)
            }
            Timber.i("SkyrimAYN: offline launch (DRM-free path, unpacked copy ${if (unpacked) "ready" else "to be made"})")
        }
        c.setExternalDisplayMode(Container.EXTERNAL_DISPLAY_MODE_OFF) // the bottom screen is ours
        // Sleep / home: the game freezes (no CPU, no heat) and carries on by itself when you come back.
        c.setSuspendPolicy(Container.SUSPEND_POLICY_AUTO)
        // Lossless Scaling frame generation: armed only when chosen, supported, and Lossless.dll is present.
        val fg = LauncherSettings.get(LauncherSettings.frameGen)
        val lsfg = fg != "Off" && app.gamenative.utils.LsfgVkManager.isSupported(c) && app.gamenative.utils.LsfgVkManager.isDllAvailable()
        c.putExtra(app.gamenative.utils.LsfgVkManager.EXTRA_ARMED, lsfg.toString())
        if (lsfg) c.putExtra(app.gamenative.utils.LsfgVkManager.EXTRA_MULTIPLIER, fg.removeSuffix("X"))
        Timber.i("SkyrimAYN: frame generation $fg (armed=$lsfg, supported=${app.gamenative.utils.LsfgVkManager.isSupported(c)})")
        c.setCPUList(LauncherSettings.cpuListFor(LauncherSettings.get(LauncherSettings.power)))
        val env = EnvVars(c.envVars)
        // The engine strips DXVK_FRAME_RATE and paces frames itself: give its limiter our cap from the start.
        val cap = LauncherSettings.get(LauncherSettings.fpsCap).toIntOrNull() ?: 60
        c.putExtra("fpsLimiterEnabled", (cap > 0).toString())     // 0 = Unlimited
        c.putExtra("fpsLimiterTarget", (if (cap > 0) cap else 60).toString())
        // Compiled shaders are kept on disk and reused; 1 GB leaves room for a modded game (default 512 MB).
        env.put("MESA_SHADER_CACHE_MAX_SIZE", "1024MB")
        env.put("MESA_SHADER_CACHE_DISABLE", "false")
        // DLLs in the game folder that must win over Wine's own: our dinput8.dll (loads the companion plugin)
        // and mod preloaders, e.g. SSE Engine Fixes part 2's d3dx9_42.dll ("did not pre-load" otherwise).
        // Never d3d11/dxgi: those are DXVK's.
        env.put("WINEDLLOVERRIDES", dllOverrides(env.get("WINEDLLOVERRIDES"), GameFolder.dir.value?.let(::File)))
        Timber.i("SkyrimAYN: WINEDLLOVERRIDES=${env.get("WINEDLLOVERRIDES")}")
        c.setEnvVars(env.toString())
        c.saveData()
        // Debug builds: keep Wine's log (Android/data/com.skyrimayn/files/wine_logs) for diagnosing launches.
        PrefManager.enableWineDebug = app.gamenative.BuildConfig.DEBUG
        Timber.i("SkyrimAYN: container configured: driver=$driver emulator=${c.emulator} screen=${c.screenSize}")
    }

    private val _gameWindow = MutableStateFlow(false)
    /** True once Skyrim's own window is on screen (until then our loading screen covers the X display). */
    val gameWindow: StateFlow<Boolean> = _gameWindow
    fun onWindowMapped(name: String?, className: String?) {
        val n = name.orEmpty().trim(); val c = className.orEmpty().trim()
        val skyrim = n.equals("Skyrim Special Edition", true) || c.equals("Skyrim Special Edition", true) ||
            c.contains("skyrimse", true)
        if (skyrim && !n.contains("skse64_loader", true)) {
            _gameWindow.value = true
            // Remember right away: sessions usually end with the app closed, not a clean exit.
            if (!everStarted()) appCtx?.getSharedPreferences("launcher", 0)?.edit()?.putBoolean("ever_started", true)?.apply()
        }
    }

    /** True from Play until the game screen is up (or the launch fails). The engine never closes itself meanwhile. */
    @Volatile private var launchStartedAt = 0L
    fun isLaunching(): Boolean = launchStartedAt != 0L && System.currentTimeMillis() - launchStartedAt < 10 * 60_000
    fun launchSettled() {
        launchStartedAt = 0L
        applyFrameLimit()
        applyPerfHud()
        startHeatGuard()
    }

    // ------------------------------------------------------------ overheat protection

    /** Hotter than this (CPU or GPU, °C) for [HEAT_SECONDS] and Skyrim is quicksaved and closed. */
    const val HEAT_LIMIT_C = 95
    private const val HEAT_SECONDS = 20
    private var heatJob: kotlinx.coroutines.Job? = null

    /**
     * Watches the Thor's temperatures while Skyrim runs. The chip throttles itself long before real damage,
     * but sitting at its limit wears the battery and gets uncomfortably hot, so past [HEAT_LIMIT_C] for a
     * while we quicksave (F5), close the game and say why on the launcher. Off in Launcher › Advanced.
     */
    private fun startHeatGuard() {
        heatJob?.cancel()
        heatJob = scope.launch {
            val metrics = app.gamenative.powercontrol.metrics.SystemMetricsSources
            val cpu = runCatching { metrics.cpuTempPaths() }.getOrDefault(emptyList())
            val gpu = runCatching { metrics.gpuTempPaths() }.getOrDefault(emptyList())
            if (cpu.isEmpty() && gpu.isEmpty()) return@launch
            var hotFor = 0
            while (gameScreenUp()) {
                delay(2000)
                if (LauncherSettings.get(LauncherSettings.heatGuard) != "On") { hotFor = 0; continue }
                val t = maxOf(metrics.readTemperatureC(cpu) ?: 0, metrics.readTemperatureC(gpu) ?: 0)
                hotFor = if (t >= HEAT_LIMIT_C) hotFor + 2 else 0
                if (hotFor >= HEAT_SECONDS) {
                    Timber.w("SkyrimAYN: overheat protection at $t°C")
                    // Save through the game's own save manager, and wait (up to 30 s) until the file is written.
                    val saves = appCtx?.let { File(ContainerRootLocator.root(it), ".wine/drive_c/users/xuser/Documents/My Games/Skyrim Special Edition/Saves") }
                    val since = System.currentTimeMillis() - 2000
                    fun written() = saves != null && File(saves, "Overheat Save.ess").let { it.isFile && it.lastModified() >= since && it.length() > 0 } &&
                        !File(saves, "Overheat Save.ess.tmp").exists()
                    var saved = -1
                    Link.sendForResult(Proto.CMD_SAVE_GAME) { saved = it }
                    for (i in 0 until 60) { if (written()) break; delay(500) }
                    if (saved == 0 && !written()) saved = -2
                    delay(1000)
                    launchNotice.value = "Skyrim was closed to protect your Thor: it stayed at $t°C. " +
                        (if (saved == 0) "Your game was saved first (\"Overheat Save\" in Load). " else "") +
                        "Let it cool down; Power › Balanced runs cooler. (Launcher › Advanced › Overheat protection)"
                    quitGame()
                    return@launch
                }
            }
        }
    }

    // ------------------------------------------------------------ in-game actions (bottom screen)

    /** FPS cap from the launcher, via the engine's own frame limiter (env-var caps are stripped by the engine). */
    fun applyFrameLimit() {
        val fps = LauncherSettings.get(LauncherSettings.fpsCap).toIntOrNull() ?: return
        scope.launch {
            repeat(60) {
                val apply = engineFpsLimit
                if (apply != null) {
                    // The engine's own limiter: it paces the game's presents, so the game really renders at the cap.
                    withContext(Dispatchers.Main) { apply(fps) }
                    Timber.i("SkyrimAYN: frame limit $fps")
                    return@launch
                }
                delay(1000)
            }
        }
    }

    private val hotkeys = listOf(
        com.winlator.xserver.XKeycode.KEY_1, com.winlator.xserver.XKeycode.KEY_2, com.winlator.xserver.XKeycode.KEY_3,
        com.winlator.xserver.XKeycode.KEY_4, com.winlator.xserver.XKeycode.KEY_5, com.winlator.xserver.XKeycode.KEY_6,
        com.winlator.xserver.XKeycode.KEY_7, com.winlator.xserver.XKeycode.KEY_8,
    )

    // ------------------------------------------------------------ bridges to the engine's game screen

    /** Set by the engine's game screen while it's up. */
    @Volatile var engineKeyboard: (() -> Unit)? = null
    @Volatile var enginePerfHud: ((String) -> Unit)? = null
    /** Set by the engine's game screen: changes its FPS limiter live. */
    @Volatile var engineFpsLimit: ((Int) -> Unit)? = null

    private val _menuRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The engine's menu gesture/button (swipe from the left, etc.): the companion opens its settings. */
    val menuRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _menuRequests
    fun requestMenu() { _menuRequests.tryEmit(Unit) }

    fun showKeyboard() { engineKeyboard?.invoke() }

    /** FPS overlay on the top screen, as chosen in settings. */
    fun applyPerfHud() {
        val on = LauncherSettings.get(LauncherSettings.perfHud).let { if (it == "On") "Full" else it }
        scope.launch(Dispatchers.Main) {
            repeat(20) { if (enginePerfHud != null) { enginePerfHud?.invoke(on); delay(1500); enginePerfHud?.invoke(on); return@launch }; delay(1000) }
        }
    }

    /** Imports the player's Lossless.dll (from their PC's Lossless Scaling install) where the engine looks for it. */
    suspend fun importLosslessDll(context: Context, uri: android.net.Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(app.gamenative.service.SteamService.internalAppInstallPath, "Lossless Scaling").apply { mkdirs() }
            val tmp = File(dir, "Lossless.dll.part")
            context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val head = tmp.inputStream().use { val b = ByteArray(2); it.read(b); b }
            if (head[0] != 'M'.code.toByte() || head[1] != 'Z'.code.toByte() || tmp.length() < 100_000) { tmp.delete(); return@runCatching false }
            val dll = File(dir, "Lossless.dll"); dll.delete(); tmp.renameTo(dll)
        }.getOrDefault(false)
    }

    fun losslessDllAvailable(): Boolean = runCatching { app.gamenative.utils.LsfgVkManager.isDllAvailable() }.getOrDefault(false)

    /** Quick save, as F5 in game. */
    fun quickSave() = tapKey(com.winlator.xserver.XKeycode.KEY_F5)

    /** Opens the Wait menu (T). */
    fun openWait() = tapKey(com.winlator.xserver.XKeycode.KEY_T)

    /** Shows or hides parts of the game's HUD (the companion shows them instead). */
    fun applyHud() = Link.send(Proto.CMD_SET_HUD, LauncherSettings.hudMask())

    /** Closes Skyrim and returns to the launcher. */
    fun quitGame() {
        app.gamenative.PluviaApp.events.emit(app.gamenative.events.SteamEvent.ForceCloseApp)
    }

    /** Taps a key in the game (through the engine's X server). */
    fun tapKey(key: com.winlator.xserver.XKeycode) {
        val xs = app.gamenative.PluviaApp.xServerView?.getxServer() ?: return
        scope.launch {
            xs.injectKeyPress(key)
            delay(70)
            xs.injectKeyRelease(key)
        }
    }

    /**
     * Equips a spell (into [hand]: 1 right, 2 left), power or shout through the game's own equip
     * functions (plugin 0.12+): works while menus have the game paused, no key presses.
     */
    fun equipSpell(formId: Int, hand: Int = 1) = Link.send(Proto.CMD_EQUIP_ITEM, formId, hand)

    fun trackQuest(formId: Int, track: Boolean) = Link.send(Proto.CMD_TRACK_QUEST, formId, if (track) 1 else 0)

    fun equipItem(formId: Int, hand: Int = 0) = Link.send(Proto.CMD_EQUIP_ITEM, formId, hand)
    fun unequipItem(formId: Int) = Link.send(Proto.CMD_UNEQUIP_ITEM, formId)
    fun useItem(formId: Int) = Link.send(Proto.CMD_USE_ITEM, formId)

    /** Hands over to the engine, which starts Steam (headless) and then skse64_loader.exe. */
    fun launch(activity: Activity) {
        val started = System.currentTimeMillis()
        launchStartedAt = started
        launchToken = started
        launchStuck = false
        steamRestart = false
        if (!retrying) quickRetries = 0
        retrying = false
        _gameWindow.value = false
        // Safety nets: never leave the player on a black screen.
        //  - Skyrim's process started and then quit without a window: on a first boot its Steam check can
        //    ask Steam to relaunch it, which never happens inside the engine. Start it again straight away.
        //  - Nothing at all after 5 minutes: close it and explain on the launcher.
        scope.launch {
            var seen = false
            var goneAt = 0L
            var launcherAt = 0L
            while (launchToken == started && !_gameWindow.value) {
                val now = System.currentTimeMillis()
                if (now - started > 5 * 60_000L) {
                    Timber.w("SkyrimAYN: Skyrim's window never appeared; closing the stuck launch")
                    launchStuck = true
                    quitGame()
                    break
                }
                val (game, launcher) = engineProcesses()
                if (launcher && launcherAt == 0L) launcherAt = now
                if (game) { seen = true; goneAt = 0L }
                // Skyrim can start and quit within a second (between two checks): if the engine's launcher has
                // been up 15 s and Skyrim isn't running, it's the same early quit.
                else if (!seen && launcherAt > 0L && now - launcherAt > 15_000) {
                    Timber.w("SkyrimAYN: Skyrim isn't running 15 s after the engine started it; starting it again")
                    steamRestart = true
                    quitGame()
                    break
                }
                else if (seen) {
                    if (goneAt == 0L) goneAt = now
                    else if (now - goneAt > 6000) {
                        Timber.w("SkyrimAYN: Skyrim quit before its window appeared; starting it again")
                        steamRestart = true
                        quitGame()
                        break
                    }
                }
                delay(1000)
            }
        }
        val intent = Intent("app.gamenative.LAUNCH_GAME")
            .setClass(activity, app.gamenative.MainActivity::class.java)
            .putExtra("app_id", STEAM_APP_ID)
            .putExtra("game_source", "STEAM")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
    }
}

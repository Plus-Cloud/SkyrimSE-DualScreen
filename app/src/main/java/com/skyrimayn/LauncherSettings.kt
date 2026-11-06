package com.skyrimayn

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A launcher option: a fixed set of choices, saved in SharedPreferences. */
class Choice(val key: String, val label: String, val options: List<String>, val default: String)

/**
 * Launcher settings shared by both screens.
 * For now they're only saved. Phase 1 applies them to the INIs and the engine on Play.
 */
object LauncherSettings {
    val resolution = Choice("resolution", "Resolution", listOf("720p", "900p", "1080p"), "720p")
    /** Thor: Low plus the cheap things that look good, with the frame-eaters off (see SkyrimIni). */
    val quality = Choice("quality", "Graphics", listOf("Thor", "Low", "Medium", "High"), "Thor")
    val fpsCap = Choice("fps", "FPS cap", listOf("30", "45", "60"), "60")
    /**
     * How hard the Thor works. Cool: 30 FPS and the prime core left idle (the hottest core, mostly
     * heat for little gain at 30 FPS). Balanced: 45 FPS. Max: 60 FPS. Sets the FPS limit too.
     */
    val power = Choice("power", "Power", listOf("Cool", "Balanced", "Max"), "Max")
    fun fpsFor(power: String) = when (power) { "Cool" -> "30"; "Max" -> "60"; else -> "45" }
    /** Cores Wine may use: all eight, or all but the prime core (cpu 7 on the Snapdragon 8 Gen 2). */
    fun cpuListFor(power: String) = if (power == "Cool") "0,1,2,3,4,5,6" else "0,1,2,3,4,5,6,7"
    val translator = Choice("translator", "CPU translator", listOf("FEX", "Box64"), "FEX")
    val companion = Choice("companion", "Bottom-screen companion", listOf("On", "Off"), "On")
    /** Quicksave and close Skyrim if the Thor stays dangerously hot (Advanced; off for people pushing settings). */
    val heatGuard = Choice("heat_guard", "Overheat protection", listOf("On", "Off"), "On")

    // In-game settings (the companion's settings panel).
    val hudCompass = Choice("hud_compass", "Game compass", listOf("Shown", "Hidden"), "Shown")
    val hudBars = Choice("hud_bars", "Game health bars", listOf("Shown", "Hidden"), "Shown")
    val hudEnemy = Choice("hud_enemy", "Enemy health bar", listOf("Shown", "Hidden"), "Shown")
    val haptics = Choice("haptics", "Haptics", listOf("On", "Off"), "On")
    /** The engine's stats overlay on the top screen: FPS, CPU/GPU load and temperatures. */
    /** Off, FPS (just the frame rate) or Full (FPS, load, temperatures, power). "On" from older versions means Full. */
    val perfHud = Choice("perf_hud", "Performance stats", listOf("Off", "FPS", "Full"), "Off")
    /** Lossless Scaling frame generation (needs the player's own Lossless.dll). */
    val frameGen = Choice("frame_gen", "Frame generation", listOf("Off", "2X", "3X"), "Off")
    /** Bottom-screen menus pause the game, like the game's own menus. Off by default: play while you browse. */
    val pauseMenus = Choice("pause_menus", "Pause in menus", listOf("On", "Off"), "Off")
    val menuSounds = Choice("menu_sounds", "Menu sounds", listOf("On", "Off"), "On")
    /** Play with or without mods (also the escape hatch when a mod stops the game starting). */
    val mods = Choice("mods", "Mods", listOf("Enabled", "Disabled"), "Enabled")
    /** Steam achievement unlocks: a popup on the game screen (and a notice on the bottom screen), and a sound. */
    val achievementPopup = Choice("achievement_popup", "Unlock popup", listOf("On", "Off"), "On")
    val achievementSound = Choice("achievement_sound", "Unlock sound", listOf("On", "Off"), "On")

    val all = listOf(resolution, quality, fpsCap, power, translator, companion, heatGuard, hudCompass, hudBars, hudEnemy, haptics, perfHud, frameGen,
        pauseMenus, menuSounds, mods, achievementPopup, achievementSound)

    /** Which parts of the game's HUD to hide, as the plugin's mask (1 compass, 2 bars, 4 enemy health). */
    fun hudMask(): Int = (if (get(hudCompass) == "Hidden") 1 else 0) or (if (get(hudBars) == "Hidden") 2 else 0)

    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    val values: StateFlow<Map<String, String>> = _values
    private var ctx: Context? = null

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        val prefs = ctx!!.getSharedPreferences("launcher", 0)
        _values.value = all.associate { it.key to (prefs.getString(it.key, it.default) ?: it.default) } +
            listOfNotNull(prefs.getString(KEY_DRIVER, null)?.let { KEY_DRIVER to it })
        // Older builds saved the stats overlay as On/Off; On is now Full.
        if (_values.value[perfHud.key] == "On") set(perfHud, "Full")
        applyAchievementPrefs()
    }

    /** The engine shows the unlock popup and plays the sound; it reads these two from its own prefs. */
    private fun applyAchievementPrefs() = runCatching {
        app.gamenative.PrefManager.achievementShowNotification = get(achievementPopup) == "On"
        app.gamenative.PrefManager.achievementPlaySound = get(achievementSound) == "On"
        app.gamenative.PrefManager.achievementNotificationPosition = "bottom_right"
    }

    fun get(c: Choice): String = _values.value[c.key] ?: c.default

    /** The settings recommended for the AYN Thor (Snapdragon 8 Gen 2), marked in the launcher. */
    fun isRecommended(c: Choice, v: String): Boolean = when (c.key) {
        "resolution" -> v == "720p"
        "quality" -> v == "Thor"
        "power" -> v == "Max"
        "translator" -> v == "FEX"
        else -> false
    }

    /** Graphics driver: a name from the engine's driver store, or "System". Default Purple Turnip T30. */
    private const val KEY_DRIVER = "driver"
    fun driver(): String = _values.value[KEY_DRIVER] ?: SkyrimEngine.DEFAULT_DRIVER
    fun setDriver(name: String) {
        _values.value = _values.value + (KEY_DRIVER to name)
        ctx?.getSharedPreferences("launcher", 0)?.edit()?.putString(KEY_DRIVER, name)?.apply()
    }

    fun set(c: Choice, v: String) {
        _values.value = _values.value + (c.key to v)
        ctx?.getSharedPreferences("launcher", 0)?.edit()?.putString(c.key, v)?.apply()
        if (c === achievementPopup || c === achievementSound) applyAchievementPrefs()
    }
}

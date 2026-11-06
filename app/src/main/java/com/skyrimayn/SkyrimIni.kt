package com.skyrimayn

import timber.log.Timber
import java.io.File

/**
 * Edits Skyrim's INI files (SkyrimPrefs.ini / Skyrim.ini) in the game's Wine prefix.
 * Keeps everything the game wrote; only sets the keys we own.
 */
object SkyrimIni {

    /** <prefix>/drive_c/users/xuser/Documents/My Games/Skyrim Special Edition */
    fun myGamesDir(containerRoot: File) =
        File(containerRoot, ".wine/drive_c/users/xuser/Documents/My Games/Skyrim Special Edition")

    /**
     * Applies the launcher settings before a launch:
     * - graphics preset: when it changes, start from the game's own Low/Medium/High.ini
     * - borderless fullscreen at the launcher resolution, so no window frame or desktop shows
     */
    fun applyLauncherSettings(containerRoot: File, gameDir: String, width: Int, height: Int, preset: String) {
        val dir = myGamesDir(containerRoot).apply { mkdirs() }
        val prefs = File(dir, "SkyrimPrefs.ini")
        val marker = File(dir, ".skyrimayn_preset")

        if (!prefs.exists() || marker.takeIf { it.exists() }?.readText()?.trim() != preset) {
            val template = File(gameDir, (if (preset == "Thor") "Low" else preset) + ".ini")
            if (template.isFile) {
                val old = if (prefs.exists()) parse(prefs.readLines()) else emptyMap()
                val fresh = parse(template.readLines()).toMutableMap()
                // Keep the player's own non-graphics choices (controls, audio, gameplay, interface).
                for (section in listOf("Controls", "AudioMenu", "GamePlay", "Interface", "MAIN", "Clouds", "Imagespace")) {
                    old[section]?.let { fresh[section] = (fresh[section].orEmpty() + it) }
                }
                write(prefs, fresh)
                Timber.i("SkyrimAYN: graphics preset $preset applied from ${template.name}")
            }
            marker.writeText(preset)
        }

        set(prefs, "Display", mapOf(
            "iSize W" to width.toString(),
            "iSize H" to height.toString(),
            "bFull Screen" to "0",
            "bBorderless" to "1",
            "bTopMostWindow" to "1",
            "iLocation X" to "0",
            "iLocation Y" to "0",
        ))
        // Handheld tuning. Ambient occlusion and depth of field cost ~3 FPS and ~1.5 W on the Thor's GPU
        // (measured at 720p) for little visible difference on a 6" screen.
        if (preset == "Low" || preset == "Thor") {
            set(prefs, "Display", mapOf("bSAOEnable" to "0"))
            set(prefs, "ImageSpace", mapOf("bDoDepthOfField" to "0"))
        }
        if (preset == "Thor") applyThorPreset(prefs)
        // Faster boot: skip the Bethesda logo video before the main menu (a standard Skyrim.ini tweak).
        val ini = File(dir, "Skyrim.ini")
        if (!ini.exists()) File(gameDir, "Skyrim_Default.ini").takeIf { it.isFile }?.copyTo(ini)
        if (ini.exists()) set(ini, "General", mapOf("sIntroSequence" to ""))
    }

    /**
     * "Thor": Low as the base, plus what looks better for no measurable cost. Measured on the Thor at 720p:
     * it is GPU-bound, and the CPU (translating x86) is close behind, so these stay as Low:
     *  - snow/rock shaders (bEnableImprovedSnow, projected UV) and longer grass: -6 FPS together, GPU;
     *  - longer terrain/tree/large-object distances: turn it CPU-bound;
     *  - volumetric light, screen-space reflections, ambient occlusion, depth of field, 2048 shadows, TAA.
     */
    private fun applyThorPreset(prefs: File) {
        set(prefs, "Decals", mapOf(          // blood, scorch marks and footprints (Medium's budget)
            "bDecals" to "1", "bSkinnedDecals" to "1", "uMaxDecals" to "100", "uMaxSkinDecals" to "35",
            "iMaxDecalsPerFrame" to "10", "iMaxSkinDecalsPerFrame" to "3"))
        set(prefs, "Display", mapOf(
            "bUsePrecipitationOcclusion" to "1",  // no rain or snow under roofs
            "bToggleSparkles" to "1",             // snow glints
            "fMeshLODLevel1FadeDist" to "6000", "fMeshLODLevel2FadeDist" to "3000"))
        set(prefs, "ImageSpace", mapOf("bLensFlare" to "1"))
        set(prefs, "LOD", mapOf("fLODFadeOutMultObjects" to "7", "fLODFadeOutMultItems" to "3", "fLODFadeOutMultActors" to "7"))
    }

    /** Sets keys in one section, creating the file/section if needed. */
    fun set(file: File, section: String, values: Map<String, String>) {
        val data = if (file.exists()) parse(file.readLines()).toMutableMap() else mutableMapOf()
        data[section] = data[section].orEmpty() + values
        write(file, data)
    }

    // INI as ordered sections of ordered key=value (Skyrim's INIs have no duplicates that matter).
    private fun parse(lines: List<String>): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, String>>()
        var cur = out.getOrPut("") { LinkedHashMap() }
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                cur = out.getOrPut(line.substring(1, line.length - 1)) { LinkedHashMap() }
            } else if (line.contains('=') && !line.startsWith(";")) {
                cur[line.substringBefore('=').trim()] = line.substringAfter('=').trim()
            }
        }
        return out
    }

    private fun write(file: File, data: Map<String, Map<String, String>>) {
        val sb = StringBuilder()
        for ((section, kv) in data) {
            if (section.isEmpty() && kv.isEmpty()) continue
            if (section.isNotEmpty()) sb.append('[').append(section).append("]\r\n")
            for ((k, v) in kv) sb.append(k).append('=').append(v).append("\r\n")
            sb.append("\r\n")
        }
        file.writeText(sb.toString())
    }
}

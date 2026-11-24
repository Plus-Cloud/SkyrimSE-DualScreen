package com.skyrimayn

import java.io.File
import java.io.RandomAccessFile

/**
 * SKSE plugins built for an older Skyrim, found by reading the DLL itself (what SKSE reads), whatever the Nexus file
 * or installer option was called:
 *  - no `SKSEPlugin_Version` export: a build for Skyrim 1.5.97 or older. SKSE skips it ("no version data").
 *  - SSE Engine Fixes built for 1.6.1170 (7.0.20 and older): SKSE loads it, but Skyrim 1.7.99+ changed the Address
 *    Library format and it quits Skyrim with "failed to find offset for address library id". 1.7 needs 7.0.21+.
 * Engine Fixes is the dangerous one either way: its Part 2 preloader loads EngineFixes.dll by itself, before SKSE
 * can skip it, so an old build stops Skyrim from starting (issue #15).
 */
object SksePlugins {
    const val ENGINE_FIXES = "EngineFixes.dll"
    /** Skyrim 1.7.99: the first runtime with the new Address Library format (RUNTIME_VERSION packing). */
    private const val RUNTIME_1_7_99 = (1 shl 24) or (7 shl 16) or (99 shl 4)
    private const val ADDRESS_LIBRARY_POST_AE = 1          // SKSEPluginVersionData::kVersionIndependent_AddressLibraryPostAE
    private const val ADDRESS_LIBRARY_V5 = 1 shl 1          // ...::kVersionIndependentEx_AddressLibraryV5 (the 1.7.99+ format)

    /** What a DLL tells SKSE about itself. [version] is null when it has no SKSEPlugin_Version export. */
    class Info(val exports: Set<String>, val version: Version?)
    class Version(val name: String, val independence: Int, val independenceEx: Int, val compatible: List<Int>)

    /** The plugin DLLs in Data/SKSE/Plugins that are builds for an older Skyrim (not ours). */
    fun oldBuilds(gameDir: File): List<String> {
        val dir = ModOverlay.Paths(gameDir)["Data/SKSE/Plugins"]
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".dll", true) && !it.name.equals("SkyrimAYN.dll", true) }
            .filter { isOldBuild(it) }
            .map { it.name }
            .sorted()
    }

    /** Engine Fixes' plugin is a build for an older Skyrim (or switched off as one): its Part 2 preloader would stop Skyrim at start. */
    fun engineFixesOld(gameDir: File): Boolean {
        val paths = ModOverlay.Paths(gameDir)
        val f = paths["Data/SKSE/Plugins/$ENGINE_FIXES"]
        return (f.isFile && isOldBuild(f)) || paths["Data/SKSE/Plugins/$ENGINE_FIXES$OFF"].isFile
    }

    /**
     * Hides these plugins from SKSE for one launch (it only loads *.dll). Engine Fixes 7.0.20's AE build passes
     * SKSE's own checks and quits Skyrim at start, with or without its preloader; switched off, Skyrim starts.
     * Undone by [restore] before anything else looks at the mods (next launch, installs, uninstalls), so the
     * mod list's files stay where it expects them.
     */
    fun switchOff(gameDir: File, names: List<String>) {
        val dir = ModOverlay.Paths(gameDir)["Data/SKSE/Plugins"]
        names.forEach { n ->
            val f = File(dir, n)
            if (f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath())) f.renameTo(File(dir, n + OFF))
        }
    }

    fun restore(gameDir: File) {
        val dir = ModOverlay.Paths(gameDir)["Data/SKSE/Plugins"]
        dir.listFiles().orEmpty().filter { it.name.endsWith(OFF) }.forEach { off ->
            val back = File(dir, off.name.removeSuffix(OFF))
            if (back.exists()) off.delete() else off.renameTo(back)
        }
    }

    private const val OFF = ".off-older-skyrim"

    /** Unreadable or non-DLL files aren't judged (false). */
    fun isOldBuild(dll: File): Boolean {
        val info = read(dll) ?: return false
        val v = info.version ?: return true
        if (dll.name.equals(ENGINE_FIXES, true) || v.name.equals("EngineFixes", true)) {
            // Listed for nothing newer than 1.6.1170 and not marked for the 1.7.99+ Address Library: 7.0.20 and
            // older. (The 7.0.21 beta lists just 1.7.99, without the flag, and works on 1.7.104.)
            return v.compatible.none { it >= RUNTIME_1_7_99 } && v.independenceEx and ADDRESS_LIBRARY_V5 == 0 &&
                v.independence and ADDRESS_LIBRARY_POST_AE != 0
        }
        return false
    }

    internal fun exports(file: File): Set<String>? = read(file)?.exports

    /** Reads a 64-bit PE's exports and SKSE version data, or null if it isn't one. */
    internal fun read(file: File): Info? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            fun u16(at: Long): Int { f.seek(at); return f.read() or (f.read() shl 8) }
            fun u32(at: Long): Long { f.seek(at); return (0..3).fold(0L) { acc, i -> acc or (f.read().toLong() shl (8 * i)) } }
            fun cstr(at: Long, max: Int): String {
                f.seek(at)
                val sb = StringBuilder()
                while (sb.length < max) { val c = f.read(); if (c <= 0) break; sb.append(c.toChar()) }
                return sb.toString()
            }
            if (u16(0) != 0x5A4D) return null                       // "MZ"
            val pe = u32(0x3C)
            if (u32(pe) != 0x4550L) return null                     // "PE\0\0"
            val sections = u16(pe + 6)
            val optSize = u16(pe + 20)
            val opt = pe + 24
            if (u16(opt) != 0x20B) return null                      // PE32+ (64-bit) only: what SKSE loads
            val exportRva = u32(opt + 112)
            if (exportRva == 0L) return Info(emptySet(), null)
            val table = opt + optSize
            fun offset(rva: Long): Long? {
                for (i in 0 until sections) {
                    val s = table + i * 40L
                    val va = u32(s + 12); val rawSize = u32(s + 16); val raw = u32(s + 20)
                    val vSize = maxOf(u32(s + 8), rawSize)
                    if (rva >= va && rva < va + vSize) return raw + (rva - va)
                }
                return null
            }
            val dir = offset(exportRva) ?: return Info(emptySet(), null)
            val count = u32(dir + 24).coerceAtMost(65536)
            val functions = offset(u32(dir + 28))
            val names = offset(u32(dir + 32)) ?: return Info(emptySet(), null)
            val ordinals = offset(u32(dir + 36))
            val list = (0 until count).map { i -> offset(u32(names + i * 4))?.let { cstr(it, 256) }.orEmpty() }
            val i = list.indexOf("SKSEPlugin_Version")
            val version = if (i < 0 || functions == null || ordinals == null) null else offset(u32(functions + u16(ordinals + i * 2L) * 4L))?.let { at ->
                // SKSEPluginVersionData: dataVersion, pluginVersion, name[256], author[256], supportEmail[252],
                // versionIndependenceEx, versionIndependence, compatibleVersions[16], seVersionRequired
                val flags = at + 8 + 256 + 256 + 252
                Version(
                    name = cstr(at + 8, 256),
                    independenceEx = u32(flags).toInt(),
                    independence = u32(flags + 4).toInt(),
                    compatible = (0 until 16).map { u32(flags + 8 + it * 4L).toInt() }.takeWhile { it != 0 },
                )
            }
            Info(list.filter { it.isNotEmpty() }.toSet(), version)
        }
    }.getOrNull()

    /** What to tell the player about plugins for an older Skyrim, or null when there are none. */
    fun problem(old: List<String>): String? = when {
        old.isEmpty() -> null
        old.any { it.equals(ENGINE_FIXES, true) } -> "SSE Engine Fixes is made for an older Skyrim, so it's switched off and Skyrim still starts. $FIX"
        else -> "Made for an older Skyrim, so they can't load: ${old.joinToString(", ")}. $FIX"
    }

    private const val FIX = "In Mods, uninstall it and install the file marked RECOMMENDED FOR YOUR GAME."
}

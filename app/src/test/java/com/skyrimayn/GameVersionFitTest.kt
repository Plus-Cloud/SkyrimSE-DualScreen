package com.skyrimayn

import com.skyrimayn.GameVersionFit.Fit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Automatic downloads must take the file for Skyrim 1.7.104, never the 1.5.97 / VR / GOG one Nexus marks primary. */
class GameVersionFitTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun f(id: Int, name: String, primary: Boolean = false, file: String = "$name.7z", desc: String = "", cat: String = "MAIN", date: Long = id.toLong()) =
        NexusApi.ModFile(id, name, "1.0", cat, primary, 1, file, date, desc)

    @Test fun addressLibrary_picksAnniversaryEvenWhenSeIsPrimary() {
        val files = listOf(
            f(1, "All in one (Special Edition)", primary = true),
            f(2, "All in one (Anniversary Edition)"),
        )
        assertEquals(Fit.OTHER_GAME, GameVersionFit.of(files[0]))
        assertEquals(Fit.FITS, GameVersionFit.of(files[1]))
        assertEquals(2, GameVersionFit.bestFile(files)?.fileId)
    }

    @Test fun skse_currentAeBuild_notSeOrGog() {
        val files = listOf(
            f(1, "Current SE build 2.0.20 (game version 1.5.97)", primary = true, file = "skse64_2_00_20.7z"),
            f(2, "Current GOG build 2.3.1", file = "skse64_gog_2_03_01.7z"),
            f(3, "Current AE build 2.3.1 (game version 1.7.104)", file = "skse64_2_03_01.7z"),
        )
        assertEquals(3, ModManager.skseFile(files)?.fileId)
        assertEquals(3, GameVersionFit.bestFile(files)?.fileId)
    }

    @Test fun engineFixes_part1ForNewGame_part2Separately() {
        val files = listOf(
            f(1, "SSE Engine Fixes for 1.5.39 - 1.5.97", primary = true),
            f(2, "SSE Engine Fixes for 1.6.1170 and later"),
            f(3, "Part 2 - Engine Fixes skse64 Preloader and TBB Lib"),
        )
        assertEquals(2, GameVersionFit.bestFile(files)?.fileId)
        assertEquals(3, GameVersionFit.bestFile(files.filter { "part 2" in it.name.lowercase() })?.fileId)
    }

    /** Engine Fixes' real files (#15): the main file stops at 1.6.1170; Skyrim 1.7 needs the 7.0.21 beta. */
    @Test fun engineFixes_betaFor1_7_beatsMainFile() {
        val files = listOf(
            f(1, "Engine Fixes - Main File", primary = true, desc = "Supports SE 1.5.97 and AE 1.6.1170+"),
            f(2, "Engine Fixes 7.0.21 beta for Skyrim AE 1.7.99", cat = "OPTIONAL"),
            f(3, "Part 2 - Engine Fixes skse64 Preloader and TBB Lib"),
        )
        assertEquals(2, GameVersionFit.bestFile(files)?.fileId)
        assertEquals(2, GameVersionFit.sorted(files).first().fileId)
        assertEquals(3, GameVersionFit.bestFile(files.filter { "part 2" in it.name.lowercase() })?.fileId)
        // The main file says "AE", but only up to 1.6.1170: marked NOT FOR YOUR GAME next to the 1.7 file. Part 2 isn't.
        assertEquals(Fit.OTHER_GAME, GameVersionFit.of(files[0], files))
        assertEquals(Fit.FITS, GameVersionFit.of(files[1], files))
        assertEquals(Fit.UNKNOWN, GameVersionFit.of(f(4, "(Part 2) Engine Fixes - skse64 Preloader ONLY", desc = "For 1.5.97 and 1.6.1170"), files))
        // Without a 1.7 file on the page, "1.6.1170 and later" still fits (most mods built for it work on 1.7).
        assertEquals(Fit.FITS, GameVersionFit.of(f(5, "SSE Engine Fixes for 1.6.1170 and later"), listOf(f(6, "for 1.5.97"))))
    }

    @Test fun noVersionInfo_keepsNexusPrimary() {
        val files = listOf(f(1, "Main File"), f(2, "Main File (recommended)", primary = true), f(3, "Patch", cat = "OPTIONAL"))
        assertEquals(2, GameVersionFit.bestFile(files)?.fileId)
    }

    @Test fun vrOnlyNames_areOtherGame_andSortLast() {
        val files = listOf(f(1, "SkyUI VR"), f(2, "SkyUI"))
        assertEquals(Fit.OTHER_GAME, GameVersionFit.of(files[0]))
        assertEquals(2, GameVersionFit.sorted(files).first().fileId)
        assertEquals(1, GameVersionFit.sorted(files).last().fileId)
    }

    @Test fun ordinaryWords_dontLookLikeVersions() {
        assertEquals(Fit.UNKNOWN, GameVersionFit.of(f(1, "Immersive Armors", desc = "Adds 50 new armor sets. Lore friendly.")))
        assertEquals(Fit.UNKNOWN, GameVersionFit.of(f(2, "Seamless Travel")))
    }

    @Test fun engineFixesPart2Missing_detection() {
        val game = tmp.newFolder()
        File(game, "Data/SKSE/Plugins").mkdirs()
        File(game, "Data/SKSE/Plugins/EngineFixes.dll").writeText("x")
        assertTrue(ModManager.engineFixesPart2Missing(game))
        File(game, "D3DX9_42.dll").writeText("x")   // spelled as some archives ship it
        assertFalse(ModManager.engineFixesPart2Missing(game))
    }
}

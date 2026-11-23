package com.skyrimayn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Mods that ship a preloader DLL for the game folder (SSE Engine Fixes part 2: d3dx9_42.dll) must be installed
 * next to SkyrimSE.exe, and Wine must then prefer them (n,b) without touching DXVK's d3d11/dxgi.
 */
class ModPreloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun archive(vararg files: String): File {
        val root = tmp.newFolder()
        files.forEach { File(root, it).apply { parentFile.mkdirs(); writeText("x") } }
        return root
    }

    @Test fun engineFixesPart2_looseDlls_goToGameFolder() {
        val m = ModManager.mapArchive(archive("d3dx9_42.dll", "tbb.dll", "tbbmalloc.dll"))
        assertEquals(setOf("<root>/d3dx9_42.dll", "<root>/tbb.dll", "<root>/tbbmalloc.dll"), m.keys)
    }

    @Test fun engineFixesPart2_inWrapperFolder_goesToGameFolder() {
        val m = ModManager.mapArchive(archive("Engine Fixes - Part 2/d3dx9_42.dll", "Engine Fixes - Part 2/tbbmalloc.dll"))
        assertEquals(setOf("<root>/d3dx9_42.dll", "<root>/tbbmalloc.dll"), m.keys)
    }

    @Test fun engineFixesPart1_skseFolder_goesToData() {
        val m = ModManager.mapArchive(archive("SKSE/Plugins/EngineFixes.dll", "SKSE/Plugins/EngineFixes.toml"))
        assertEquals(setOf("SKSE/Plugins/EngineFixes.dll", "SKSE/Plugins/EngineFixes.toml"), m.keys)
    }

    @Test fun combinedArchive_dataAndRootDlls() {
        val m = ModManager.mapArchive(archive("Data/SKSE/Plugins/EngineFixes.dll", "d3dx9_42.dll"))
        assertTrue("<root>/d3dx9_42.dll" in m.keys)
        assertTrue("SKSE/Plugins/EngineFixes.dll" in m.keys)
    }

    @Test fun modsCannotReplaceOurLoader() {
        val m = ModManager.mapArchive(archive("dinput8.dll", "d3dx9_42.dll"))
        assertFalse("<root>/dinput8.dll" in m.keys)
        assertTrue("<root>/d3dx9_42.dll" in m.keys)
    }

    @Test fun skseArchive_wrapperWithData_goesToGameFolder() {
        val m = ModManager.mapArchive(archive(
            "skse64_2_03_01/skse64_1_7_104.dll", "skse64_2_03_01/skse64_loader.exe", "skse64_2_03_01/skse64_readme.txt",
            "skse64_2_03_01/Data/Scripts/Actor.pex", "skse64_2_03_01/src/skse64/main.cpp"), isSkse = true)
        assertTrue("<root>/skse64_loader.exe" in m.keys)
        assertTrue("<root>/skse64_1_7_104.dll" in m.keys)
    }

    @Test fun skseArchive_noData_goesToGameFolder() {
        val m = ModManager.mapArchive(archive(
            "skse64_2_03_01/skse64_1_7_104.dll", "skse64_2_03_01/skse64_loader.exe", "skse64_2_03_01/src/skse64/main.cpp"), isSkse = true)
        assertTrue("<root>/skse64_loader.exe" in m.keys)
    }

    @Test fun skseArchive_recognisedByContents() {
        val staging = archive("skse64_2_03_01/skse64_1_7_104.dll", "skse64_2_03_01/skse64_loader.exe", "skse64_2_03_01/Data/Scripts/Actor.pex")
        assertTrue(ModManager.isSkseArchive(staging))
        assertTrue("<root>/skse64_loader.exe" in ModManager.mapArchive(staging, ModManager.isSkseArchive(staging)).keys)
        assertFalse(ModManager.isSkseArchive(archive("SKSE/Plugins/EngineFixes.dll")))
    }

    @Test fun skseProblem_flagsGithubBuildAndMissingSkse() {
        val ok = archive("skse64_loader.exe", "skse64_1_7_104.dll", "Data/SKSE/Plugins/EngineFixes.dll")
        assertEquals(null, SkyrimEngine.skseProblem(ok.path))
        val github = archive("skse64_loader.exe", "skse64_1_6_1170.dll")
        assertTrue(SkyrimEngine.skseProblem(github.path)!!.contains("1.6.1170"))
        val missing = archive("Data/SKSE/Plugins/EngineFixes.dll")
        assertTrue(SkyrimEngine.skseProblem(missing.path)!!.contains("isn't installed"))
        assertEquals(null, SkyrimEngine.skseProblem(archive("Data/SKSE/Plugins/SkyrimAYN.dll").path))
    }

    @Test fun skseFile_picksTheBuildFor1_7_104_notPrimary() {
        fun f(id: Int, name: String, ver: String, primary: Boolean, file: String) =
            NexusApi.ModFile(id, name, ver, "MAIN", primary, 1, file, 0, "")
        val files = listOf(
            f(1, "SKSE64 for 1.5.97", "2.0.20", true, "skse64_2_00_20.7z"),
            f(2, "SKSE64 GOG", "2.3.1", false, "skse64_gog_2_03_01.7z").copy(name = "SKSE64 GOG"),
            f(3, "SKSE64 Current", "2.3.1", false, "skse64_2_03_01.7z"),
        )
        // GOG also says 2.3.1 and the 1.5.97 build is "primary": the Steam 1.7.104 build must still win.
        assertEquals(3, ModManager.skseFile(files)?.fileId)
        assertEquals(3, ModManager.skseFile(files.reversed())?.fileId)
    }

    @Test fun overrides_onlyOurLoader_whenNoPreloader() {
        val game = tmp.newFolder().apply { File(this, "SkyrimSE.exe").writeText("x") }
        assertEquals("dinput8=n,b", SkyrimEngine.dllOverrides("", game))
    }

    @Test fun overrides_addEngineFixesPreloader_keepOthers_neverDxvk() {
        val game = tmp.newFolder().apply {
            File(this, "D3DX9_42.dll").writeText("x")      // case as some archives ship it
            File(this, "d3d11.dll").writeText("x")          // an ENB: must stay DXVK's
        }
        val o = SkyrimEngine.dllOverrides("mf=n;dinput8=b;d3dx9_42=b", game).split(';')
        assertTrue("mf=n" in o)
        assertTrue("dinput8=n,b" in o)
        assertTrue("d3dx9_42=n,b" in o)
        assertFalse(o.any { it.startsWith("dinput8=b") || it.startsWith("d3dx9_42=b") })
        assertFalse(o.any { it.startsWith("d3d11") || it.startsWith("dxgi") })
    }
}

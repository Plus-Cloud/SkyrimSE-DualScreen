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

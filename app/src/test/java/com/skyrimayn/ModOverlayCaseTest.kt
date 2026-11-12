package com.skyrimayn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Folders that differ only in case in the mods mirror (#11). Plain folders only: Windows can't make the links. */
class ModOverlayCaseTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(f: File, text: String) { f.parentFile.mkdirs(); f.writeText(text) }

    // Windows disks ignore case, so real case pairs can't sit side by side here; the merge is checked with a
    // differently named stand-in folder instead.
    @Test fun pathsReuseExistingSpelling() {
        val root = tmp.newFolder("mirror")
        write(File(root, "Data/Meshes/Actors/a.nif"), "a")
        val p = ModOverlay.Paths(root)
        val f = p["Data/meshes/actors/character/b.hkx"]
        assertEquals(File(root, "Data/Meshes/Actors/character/b.hkx").path, f.path)
        // A folder created during the same operation keeps the first spelling.
        assertEquals(File(root, "Data/Meshes/Actors/character/c.hkx").path, p["data/MESHES/actors/CHARACTER/c.hkx"].path)
    }

    @Test fun mergeIntoMovesFilesAndPicksTheTopOwnersCopy() {
        val root = tmp.newFolder("mirror")
        val keep = File(root, "Data/Meshes")
        val other = File(root, "Data/meshes2")
        write(File(keep, "x/shared.nif"), "old")
        write(File(other, "x/shared.nif"), "new")
        write(File(other, "y/only.nif"), "only")
        File(keep, "x/shared.nif").setLastModified(System.currentTimeMillis() + 60_000)  // newer, but not the top owner's
        val spelling = mapOf("meshes2/x/shared.nif" to "meshes2/x/shared.nif")
        val m = ModOverlay::class.java.getDeclaredMethod("mergeInto", File::class.java, File::class.java, File::class.java, Map::class.java)
        m.isAccessible = true
        m.invoke(ModOverlay, other, keep, root, spelling)
        assertFalse(other.exists())
        assertEquals("new", File(keep, "x/shared.nif").readText())
        assertTrue(File(keep, "y/only.nif").isFile)
    }
}

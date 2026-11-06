package com.skyrimayn

import android.graphics.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Builds the Tamriel map from a real Skyrim.esm (set SKYRIM_ESM) and writes it out for a look. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class WorldMapsTest {
    @Test
    fun buildsTamriel() {
        val esm = File(System.getenv("SKYRIM_ESM") ?: "")
        assumeTrue("set SKYRIM_ESM", esm.isFile)
        val (bmp, info) = WorldMaps.buildForTest(esm, "Tamriel")
        println("map ${bmp.width}x${bmp.height} cells ${info.cellsW}x${info.cellsH}")
        assertTrue(info.cellsW > 50 && info.cellsH > 50)
        System.getenv("MAP_OUT")?.let { out -> File(out).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}

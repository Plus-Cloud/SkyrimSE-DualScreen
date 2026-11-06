package com.skyrimayn

import android.graphics.Bitmap
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class GameIconsTest {
    @Test
    fun rendersIcons() {
        val bsa = File(System.getenv("SKYRIM_BSA") ?: "")
        val out = System.getenv("MAP_DIR")?.let(::File)
        assumeTrue(bsa.isFile && out != null)
        val (sheet, frames) = GameIcons.renderFromBsa(bsa)
        println("icons: $frames frames")
        File(out!!, "kicons.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

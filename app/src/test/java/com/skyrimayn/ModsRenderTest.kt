package com.skyrimayn

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import java.util.concurrent.TimeUnit

/** Renders the bottom-screen Mod Manager with a fake game folder holding two installed mods. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w538dp-h468dp-land-369dpi")
class ModsRenderTest {
    @Test
    fun mods() {
        val out = System.getenv("MAP_DIR")?.let(::File)
        assumeTrue(out != null)
        val game = File(out, "fakegame").apply { mkdirs() }
        File(game, "Data").mkdirs()
        File(game, "SkyrimAYN").mkdirs()
        File(game, "SkyrimAYN/mods.json").writeText("""{"mods":[
            {"id":"a","name":"Unofficial Skyrim Special Edition Patch","version":"4.3.9c","archive":"u.7z","files":["Data/unofficial skyrim special edition patch.esp"],"plugins":["unofficial skyrim special edition patch.esp"],"size":95000000,"installedAt":1,"nexusId":266,"fileId":1},
            {"id":"b","name":"SkyUI","version":"5.2SE","archive":"s.7z","files":["Data/SkyUI_SE.esp"],"plugins":["SkyUI_SE.esp"],"size":3000000,"installedAt":2,"nexusId":12604,"fileId":2}
        ],"owners":{}}""")
        val act = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        GameFolder.init(act); GameFolder.save(game.path)
        act.setContent { SkyrimAynTheme { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            ModManagerPage(game.path, onBack = {}) } } }
        repeat(20) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS) }
        val root = act.window.decorView
        val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b))
        File(out, "mods.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

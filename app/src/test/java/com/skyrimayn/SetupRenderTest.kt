package com.skyrimayn

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/** The first-run setup steps at the Thor's top-screen size, for the setup guide. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w832dp-h468dp-land-369dpi")
class SetupRenderTest {
    @Test fun step1() = render("setup_1_files") { FileAccessStep(onAllow = {}) }
    @Test fun step2() = render("setup_2_folder") { FolderStep() }
    @Test fun step3() = render("setup_3_steam") {
        Backdrop { SteamSignInPage(LoginUi.Ready("https://s.team/q/1/1234567890123456789")) }
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        val dir = System.getenv("MAP_DIR")?.let(::File)
        assumeTrue(dir != null)
        val act = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        act.setContent { SkyrimAynTheme { content() } }
        repeat(10) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS) }
        val root = act.window.decorView
        val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b))
        File(dir!!, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

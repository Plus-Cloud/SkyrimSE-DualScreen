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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w538dp-h468dp-land-369dpi")
class LauncherRenderTest {
    @Test
    fun launcher() {
        val dir = System.getenv("MAP_DIR")?.let(::File)
        assumeTrue(dir != null)
        val act = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        runCatching { LauncherSettings.init(act) }
        act.setContent { SkyrimAynTheme { LauncherPage(gameRunning = false, onBackToGame = {}, onMods = {}) } }
        repeat(10) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS) }
        val root = act.window.decorView
        // Draw the whole scrolling column by making the window tall.
        val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b))
        File(dir!!, "launcher.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun launcherWithUpdate() {
        val dir = System.getenv("MAP_DIR")?.let(::File)
        assumeTrue(dir != null)
        val act = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        runCatching { LauncherSettings.init(act) }
        Updater.injectForTest(Updater.State.Available(Updater.Release("1.0.1",
            "## 1.0.1\n- Compass smoother in cities\n- Fixed map icons on Solstheim\n- **Faster** first launch", "https://example.com/a.apk", 1)))
        act.setContent { SkyrimAynTheme { LauncherPage(gameRunning = false, onBackToGame = {}, onMods = {}) } }
        repeat(10) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS) }
        val root = act.window.decorView
        val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b))
        File(dir!!, "launcher_update.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Updater.injectForTest(Updater.State.Idle)
    }
}

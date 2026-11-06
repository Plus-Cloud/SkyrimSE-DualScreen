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

/** The achievements list at the Thor's bottom-screen size, with long names and hidden ones. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w538dp-h468dp-land-369dpi")
class AchievementsRenderTest {
    private val sample = listOf(
        Achievements.Item("A1", "Unbound", "Complete \"Unbound\".", true, 1762400000, false, "", null),
        Achievements.Item("A2", "Bleak Falls Barrow", "Retrieve the Dragonstone from Bleak Falls Barrow, a very long description that wraps.", true, 1762450000, false, "", null),
        Achievements.Item("A3", "Dragonslayer", "Complete \"Dragonslayer\".", false, 0, false, "", null),
        Achievements.Item("A4", "Secret", "Spoiler", false, 0, true, "", null),
        Achievements.Item("A5", "Oblivion Walker", "Collect 15 Daedric artifacts.", false, 0, false, "", null),
    )

    @Test fun page() = render("achievements") { AchievementsPage(onBack = {}) }
    @Test fun launcher() = render("launcher_ach") { LauncherPage(gameRunning = false, onBackToGame = {}, onMods = {}) }

    private fun render(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val dir = System.getenv("MAP_DIR")?.let(::File)
        assumeTrue(dir != null)
        val act = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        runCatching { LauncherSettings.init(act) }
        Achievements.injectForTest(sample)
        act.setContent { SkyrimAynTheme { content() } }
        repeat(10) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS) }
        val root = act.window.decorView
        val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b))
        File(dir!!, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

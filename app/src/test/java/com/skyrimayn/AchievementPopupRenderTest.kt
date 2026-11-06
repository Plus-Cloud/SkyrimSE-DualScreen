package com.skyrimayn

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.gamenative.ui.component.AchievementNotificationContent
import app.gamenative.ui.util.AchievementNotification
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

/** The achievement popup on the top screen (1920x1080 at 369 dpi), bottom-right like in game. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w832dp-h468dp-land-369dpi")
class AchievementPopupRenderTest {
    @Test fun popup() {
        val dir = System.getenv("MAP_DIR")?.let(::File)
        assumeTrue(dir != null)
        val act = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        act.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF55606A)), contentAlignment = Alignment.BottomEnd) {
                Box(Modifier.padding(16.dp)) { AchievementNotificationContent(AchievementNotification("Bleak Falls Barrow", null)) }
            }
        }
        repeat(10) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS) }
        val root = act.window.decorView
        val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b))
        File(dir!!, "popup.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

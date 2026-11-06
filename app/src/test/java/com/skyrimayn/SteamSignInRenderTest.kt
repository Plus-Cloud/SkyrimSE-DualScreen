package com.skyrimayn

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** The Steam sign-in page at the Thor's top-screen size (1920x1080 at 369 dpi), to spot clipping and padding. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w832dp-h468dp-land-369dpi")
class SteamSignInRenderTest {
    @Test fun ready() = render("ready", LoginUi.Ready("https://s.team/q/1/1234567890123456789", "Wrong account name or password."))
    @Test fun code() = render("code", LoginUi.NeedCode("t***@gmail.com", wrongCode = true))

    private fun render(name: String, ui: LoginUi) {
        val dir = System.getenv("MAP_DIR")?.let(::File)
        assumeTrue(dir != null)
        val act = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        act.setContent {
            SkyrimAynTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF151518)), contentAlignment = Alignment.Center) { SteamSignInPage(ui) }
            }
        }
        repeat(10) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS) }
        val root = act.window.decorView
        val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b))
        File(dir!!, "steam_$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

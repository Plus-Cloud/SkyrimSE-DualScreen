package com.skyrimayn

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asImageBitmap
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

/** Renders the companion at the Thor's bottom-screen size with a worst-case character, to spot clipping. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w538dp-h468dp-land-369dpi")
class CompanionRenderTest {
    @Test fun character() = render("CHARACTER")
    @Test fun quests() = render("QUESTS")
    @Test fun items() = render("ITEMS")
    @Test fun magic() = render("MAGIC")
    @Test fun waiting() = render("CHARACTER", noGame = true)
    @Test fun settings() = render("CHARACTER", settings = true)


    private fun render(tab: String, settings: Boolean = false, noGame: Boolean = false) {
        val dir = System.getenv("MAP_DIR")?.let(::File)
        assumeTrue(dir != null)
        val act = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        act.getSharedPreferences("companion", 0).edit().putBoolean("walkthrough_done", true).commit()
        val p = DemoData.player.copy(
            name = "Brynjolf Stormcloak-Ravenheart the Unyielding", race = "Breton",
            rightHand = "Daedric Warhammer of Debilitation", leftHand = "Ebony Shield of Eminent Blocking", shout = "Unrelenting Force",
            location = "Bleak Falls Barrow Sanctum",
            // Worst case: a long list of effects and debuffs.
            effects = listOf(
                ActiveEffect("Fortify Two-handed & Stamina", 540f, false), ActiveEffect("Ataxia", -1f, true),
                ActiveEffect("Resist Frost", 95f, false), ActiveEffect("Lingering Damage Health", 12f, true),
                ActiveEffect("Well Rested", 28000f, false), ActiveEffect("Blessing of Talos", 28000f, false),
                ActiveEffect("Rockjoint", -1f, true), ActiveEffect("Fortify Restoration", 60f, false),
                ActiveEffect("Waterbreathing", 120f, false), ActiveEffect("Vampirism", -1f, true),
            ),
        )
        File(dir!!, "kmap.png").takeIf { it.isFile }?.let { f ->
            val bmp = android.graphics.BitmapFactory.decodeFile(f.path)
            WorldMaps.injectForTest(WorldMaps.State.Ready(bmp.asImageBitmap(),
                WorldMaps.Info(-57, 50, bmp.width / WorldMaps.PX, bmp.height / WorldMaps.PX)))
        }
        // Stand on the road near Riverwood (real game coordinates), with markers around.
        val pos = p.copy(x = 19000f, y = -46500f)
        Link.injectForTest(if (noGame) null else pos, DemoData.lists, DemoData.markers, DemoData.quests)
        act.setContent { CompanionScreen(onLauncher = {}, startTab = tab, startSettings = settings) }
        fun shot(name: String) {
            repeat(10) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS) }
            val root: View = act.window.decorView
            val w = root.width.takeIf { it > 0 } ?: 1240; val h = root.height.takeIf { it > 0 } ?: 1080
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(b))
            File(dir!!, "comp_$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        shot(if (noGame) "waiting" else if (settings) "settings" else tab.lowercase())
    }
}

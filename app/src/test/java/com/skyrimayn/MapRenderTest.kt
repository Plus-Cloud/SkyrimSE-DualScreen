package com.skyrimayn

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws the world map and minimap exactly as the companion does, from a pre-built map image, to PNGs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class MapRenderTest {
    @Test
    fun renderViews() {
        val dir = System.getenv("MAP_DIR")?.let(::File)
        assumeTrue("set MAP_DIR", dir != null && File(dir, "kmap.png").isFile)
        dir!!
        val map = BitmapFactory.decodeFile(File(dir, "kmap.png").path)
        val info = WorldMaps.Info(-57, 50, map.width / WorldMaps.PX, map.height / WorldMaps.PX)
        val ready = WorldMaps.State.Ready(map.asImageBitmap(), info)
        val raw = JSONArray(File(dir, "markers.json").readText())
        val markers = (0 until raw.length()).map { i ->
            val a = raw.getJSONArray(i)
            MapMarker(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), 0f, MarkerKind.LOCATION, i % 3 != 0, false, a.getInt(2), i, "Place $i")
        }
        // Stand near a town: the marker of type 2 closest to the middle of Skyrim.
        val town = markers.filter { it.type == 2 }.minBy { kotlin.math.hypot(it.x - 20000f, it.y + 30000f) }
        val me = System.getenv("PLAYER_XY")?.split(",")?.let { Offset(it[0].toFloat(), it[1].toFloat()) } ?: Offset(town.x + 2500f, town.y - 1800f)
        val extra = listOf(
            MapMarker(town.x + 9000f, town.y + 6000f, 0f, MarkerKind.QUEST, false, true, 0, 9001, "Quest"),
            MapMarker(me.x + 1200f, me.y + 900f, 0f, MarkerKind.HOSTILE, false, false, 0, 9002, "Bandit"),
        )
        val all = markers + extra
        fun shot(name: String, w: Int, h: Int, zoom: Float, centre: Offset, clouds: Boolean, names: Boolean, scale: Float) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = androidx.compose.ui.graphics.Canvas(android.graphics.Canvas(bmp))
            CanvasDrawScope().draw(Density(2.30625f), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
                drawWorld(ready, centre, zoom, all, me, 0.8f, null, names = names, scale = scale, clouds = clouds, cloudDrift = 0.2f, pulse = 0.3f)
            }
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val c = info.toMap(me.x, me.y)
        shot("view_world", 1240, 760, 760f / info.height, Offset(info.width / 2f, info.height / 2f), true, true, 1f)
        shot("view_local", 1240, 760, 3.2f, c, true, true, 1f)
        shot("view_close", 1240, 760, 7f, c, true, true, 1f)
        shot("view_mini", 396, 396, 4.2f, c, false, false, 0.8f)
    }
}

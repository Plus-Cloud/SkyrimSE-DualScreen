package com.skyrimayn

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawWithContent
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Skyrim's menu look: condensed caps, white on black, thin rules with diamond ends. */
internal object Sky {
    val Font = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
    val FontLight = FontFamily(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL))
    val Bg = Color(0xFF000000)
    val Text = Color(0xFFF2F2F2)
    val Body = Color(0xFFC8C8C8)
    val Dim = Color(0xFF8C8C8C)
    val Faint = Color(0xFF4A4A4A)
    val Line = Color(0x99FFFFFF)
    val Health = Color(0xFFB22B23)
    val Magicka = Color(0xFF2F6FC8)
    val Stamina = Color(0xFF4E9C3A)
    val Frame = Color(0xFF6E6E6E)
}

@Composable
internal fun SkyText(
    text: String, size: TextUnit = 18.sp, color: Color = Sky.Text, spacing: TextUnit = 2.sp,
    light: Boolean = false, modifier: Modifier = Modifier, align: TextAlign? = null, maxLines: Int = 1,
) = Text(
    text, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align,
    style = TextStyle(fontFamily = if (light) Sky.FontLight else Sky.Font, fontSize = size, color = color, letterSpacing = spacing),
)

/** Thin horizontal rule with small diamonds at each end, as under Skyrim menu headers. */
@Composable
internal fun Rule(modifier: Modifier = Modifier, color: Color = Sky.Line) {
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val y = size.height / 2
        drawLine(color, Offset(10f, y), Offset(size.width - 10f, y), strokeWidth = 1.5f)
        diamond(Offset(5f, y), 5f, color)
        diamond(Offset(size.width - 5f, y), 5f, color)
    }
}

internal fun DrawScope.diamond(c: Offset, r: Float, color: Color) {
    drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close() }, color)
}

/** Small filled (on) or hollow (off) diamond, used as a marker / checkbox. */
@Composable
internal fun DiamondMark(on: Boolean, color: Color = Sky.Text, modifier: Modifier = Modifier.size(10.dp)) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        if (on) diamond(c, r, color)
        else drawPath(
            Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close() },
            color, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f),
        )
    }
}

/** A menu row. Selected rows get Skyrim's [ brackets ]; marked rows (equipped/active) get a diamond. */
@Composable
internal fun ListRow(text: String, selected: Boolean, equipped: Boolean, center: Boolean = false, dim: Boolean = false, onTap: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start,
    ) {
        Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) { if (equipped) DiamondMark(true, modifier = Modifier.size(8.dp)) }
        if (selected) SkyText("[", 20.sp, Sky.Text, 0.sp)
        SkyText(
            text, 18.sp, when { dim -> Sky.Dim; selected || equipped -> Sky.Text; else -> Sky.Body }, 0.5.sp,
            modifier = Modifier.padding(horizontal = 6.dp).let { if (center) it else it.weight(1f, fill = false) },
        )
        if (selected) SkyText("]", 20.sp, Sky.Text, 0.sp)
    }
}

/** Section header: caps title over a rule. */
@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp)) {
        SkyText(title, 15.sp, Sky.Dim, 3.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        Rule(color = Sky.Faint)
    }
}

/** Skyrim settings-menu row: LABEL ........ ‹ VALUE ›. Tap the value or arrows to cycle. */
@Composable
internal fun <T> SelectorRow(label: String, options: List<T>, current: T, show: (T) -> String, recommended: (T) -> Boolean = { false }, onPick: (T) -> Unit) {
    val i = options.indexOf(current).coerceAtLeast(0)
    fun step(d: Int) { if (options.isNotEmpty()) onPick(options[(i + d + options.size) % options.size]) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SkyText(label, 17.sp, Sky.Body, 1.5.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
        SkyText("‹", 26.sp, Sky.Dim, 0.sp, modifier = Modifier.clickable { step(-1) }.padding(horizontal = 12.dp, vertical = 2.dp))
        Column(Modifier.width(170.dp).clickable { step(1) }, horizontalAlignment = Alignment.CenterHorizontally) {
            SkyFitText(if (options.isEmpty()) "—" else show(options[i]).uppercase(), 17.sp, 11.sp, Sky.Text, 2.sp, Modifier.fillMaxWidth(), align = TextAlign.Center)
            if (options.isNotEmpty() && recommended(options[i])) SkyText("RECOMMENDED", 9.sp, Sky.Dim, 2.sp)
        }
        SkyText("›", 26.sp, Sky.Dim, 0.sp, modifier = Modifier.clickable { step(1) }.padding(horizontal = 12.dp, vertical = 2.dp))
    }
}

/** A tappable menu entry with a trailing chevron. */
@Composable
internal fun EntryRow(label: String, value: String? = null, onTap: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SkyText(label, 17.sp, Sky.Body, 1.5.sp, modifier = Modifier.weight(1f))
        value?.let { SkyText(it, 15.sp, Sky.Dim, 0.5.sp, modifier = Modifier.padding(end = 8.dp)) }
        SkyText("›", 24.sp, Sky.Dim, 0.sp)
    }
}

/**
 * One line of menu text that shrinks (down to [minSize]) instead of clipping, for names of any length.
 * Falls back to an ellipsis only if it still doesn't fit at the smallest size.
 */
@androidx.compose.runtime.Composable
internal fun SkyFitText(
    text: String, maxSize: TextUnit, minSize: TextUnit, color: Color = Sky.Text, spacing: TextUnit = 2.sp,
    modifier: Modifier = Modifier, align: TextAlign? = null,
) {
    // Measure first, then draw once at the largest size that fits: never blank, never a visible shrink.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val maxW = constraints.maxWidth
        fun style(sz: Float) = TextStyle(fontFamily = Sky.Font, fontSize = sz.sp, color = color, letterSpacing = (spacing.value * sz / maxSize.value).sp)
        val size = androidx.compose.runtime.remember(text, maxSize, minSize, maxW) {
            var sz = maxSize.value
            if (maxW != androidx.compose.ui.unit.Constraints.Infinity)
                while (sz > minSize.value && measurer.measure(text, style(sz), maxLines = 1, softWrap = false).size.width > maxW) sz -= 0.5f
            sz.coerceAtLeast(minSize.value)
        }
        Text(
            text, maxLines = 1, softWrap = false, textAlign = align, overflow = TextOverflow.Ellipsis, style = style(size),
            modifier = if (align != null) Modifier.fillMaxWidth() else Modifier,
        )
    }
}

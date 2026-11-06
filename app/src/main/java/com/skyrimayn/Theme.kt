package com.skyrimayn

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Background = Color(0xFF101012)
    val Surface = Color(0xFF1B1B1F)
    val SurfaceHigh = Color(0xFF26262B)
    val Gold = Color(0xFFC8A75A)
    val Text = Color(0xFFE8E2D4)
    val Muted = Color(0xFF9A958A)
    val Good = Color(0xFF6FBF73)
    val Warn = Color(0xFFE0B04F)
    val Bad = Color(0xFFD9645A)
}

@Composable
fun SkyrimAynTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Gold,
            onPrimary = Color(0xFF1A1405),
            background = Palette.Background,
            surface = Palette.Surface,
            surfaceVariant = Palette.SurfaceHigh,
            onSurface = Palette.Text,
            onBackground = Palette.Text,
            onSurfaceVariant = Palette.Muted,
        ),
        content = content,
    )
}

@Composable
fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(title.uppercase(), color = Palette.Gold, fontSize = 12.sp, letterSpacing = 2.sp)
        Spacer(Modifier.size(10.dp))
        content()
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Palette.Muted, fontSize = 14.sp, modifier = Modifier.width(150.dp))
        Text(value, color = Palette.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

fun statusColor(s: LinkStatus): Color = when (s) {
    LinkStatus.LIVE -> Palette.Good
    LinkStatus.STALE, LinkStatus.WAITING -> Palette.Warn
    LinkStatus.NO_FILE, LinkStatus.NO_PERMISSION -> Palette.Muted
    LinkStatus.ERROR -> Palette.Bad
}

@Composable
fun StatusDot(s: LinkStatus, big: Boolean = false) {
    Box(
        Modifier.size(if (big) 18.dp else 12.dp).background(statusColor(s), CircleShape),
        contentAlignment = Alignment.Center,
    ) {}
}

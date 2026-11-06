package app.gamenative.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.AchievementNotification
import app.gamenative.ui.util.AchievementNotificationManager
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.delay
import app.gamenative.PrefManager


internal val ACHIEVEMENT_NOTIFICATION_POSITION: Map<String, Int> = mapOf(
    "top_left" to R.string.achievement_position_top_left,
    "top_right" to R.string.achievement_position_top_right,
    "bottom_left" to R.string.achievement_position_bottom_left,
    "bottom_right" to R.string.achievement_position_bottom_right,
)

@Composable
fun BoxScope.AchievementOverlay() {
    var current by remember { mutableStateOf<AchievementNotification?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        AchievementNotificationManager.notifications.collect { notification ->
            current = notification
            visible = true
            delay(4000)
            visible = false
            delay(500)
            current = null
        }
    }

    val isLeftAligned = PrefManager.achievementNotificationPosition in setOf("top_left", "bottom_left")

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(
                when (PrefManager.achievementNotificationPosition) {
                    "top_left" -> Alignment.TopStart
                    "top_right" -> Alignment.TopEnd
                    "bottom_left" -> Alignment.BottomStart
                    "bottom_right" -> Alignment.BottomEnd
                    else -> Alignment.BottomEnd
                }
            )
            .padding(16.dp),
        enter = slideInHorizontally(initialOffsetX = { if (isLeftAligned) -it else it }) + fadeIn(), // Slide in from left if left aligned, otherwise from right
        exit = slideOutHorizontally(targetOffsetX = { if (isLeftAligned) -it else it }) + fadeOut(),
    ) {
        current?.let { notification ->
            AchievementNotificationContent(notification)
        }
    }
}

@Composable
internal fun AchievementNotificationContent(notification: AchievementNotification) {
    // Styled like Steam's own achievement toast: blue-grey gradient, soft corners, icon on the left.
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier = Modifier
            .width(300.dp)
            .shadow(10.dp, shape)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(androidx.compose.ui.graphics.Color(0xFF2E3D50), androidx.compose.ui.graphics.Color(0xFF1B2533)),
                ),
                shape,
            )
            .border(1.dp, androidx.compose.ui.graphics.Color(0xFF3E5068), shape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(2.dp)).background(androidx.compose.ui.graphics.Color(0xFF101822)),
            contentAlignment = Alignment.Center,
        ) {
            if (notification.iconUrl != null) {
                CoilImage(
                    modifier = Modifier.size(56.dp),
                    imageModel = { notification.iconUrl },
                    imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                    previewPlaceholder = painterResource(R.drawable.ic_logo_color),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Achievement Unlocked",
                color = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = notification.name,
                color = androidx.compose.ui.graphics.Color(0xFFB5C2CE),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview_AchievementOverlay() {
    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            AchievementNotificationContent(
                AchievementNotification(
                    name = "Real boy : They all lived happily ever after",
                    iconUrl = "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/12345/abc.jpg",
                )
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview_AchievementOverlay_NoIcon() {
    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            AchievementNotificationContent(
                AchievementNotification(
                    name = "First Blood",
                    iconUrl = null,
                )
            )
        }
    }
}

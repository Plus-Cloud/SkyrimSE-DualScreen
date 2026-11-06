package app.gamenative.ui.component.dialog

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.delay

@Composable
fun SteamAgreementDialog(
    title: String,
    url: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val hasPage = url.isNotBlank()
    val webView = remember { mutableStateOf<WebView?>(null) }
    Dialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        // Styled like the rest of SkyrimSE-DualScreen: dark panel, thin frame, spaced capitals.
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .then(if (hasPage) Modifier.fillMaxHeight(0.9f) else Modifier.wrapContentHeight())
                .background(androidx.compose.ui.graphics.Color(0xFF0B0B0B))
                .border(1.dp, com.skyrimayn.Sky.Frame)
                .padding(24.dp),
        ) {
            com.skyrimayn.SkyText(title.uppercase(), 20.sp, com.skyrimayn.Sky.Text, 4.sp, maxLines = 2)
            com.skyrimayn.Rule(Modifier.padding(vertical = 10.dp), color = com.skyrimayn.Sky.Faint)
            com.skyrimayn.SkyText(stringResource(R.string.steam_agreement_message), 14.sp, com.skyrimayn.Sky.Body, 0.5.sp,
                light = true, maxLines = 4, modifier = Modifier.padding(bottom = 14.dp))
            if (hasPage) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .border(1.dp, com.skyrimayn.Sky.Faint),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = {
                            WebView(it).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                isFocusable = false
                                setBackgroundColor(android.graphics.Color.rgb(11, 11, 11))
                                settings.setNeedInitialFocus(false)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                        view?.scrollTo(0, 0)
                                    }
                                }
                                loadUrl(url)
                                webView.value = this
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                }
            }

            val acceptFocusRequester = remember { FocusRequester() }
            val isWindowFocused = LocalWindowInfo.current.isWindowFocused
            LaunchedEffect(isWindowFocused) {
                if (isWindowFocused) {
                    delay(100)
                    runCatching { acceptFocusRequester.requestFocus() }
                    webView.value?.isFocusable = true
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                com.skyrimayn.SkyText(stringResource(R.string.steam_agreement_decline).uppercase(), 14.sp, com.skyrimayn.Sky.Dim, 3.sp,
                    modifier = Modifier.clickable(onClick = onDecline).padding(horizontal = 14.dp, vertical = 10.dp))
                com.skyrimayn.SkyText(stringResource(R.string.steam_agreement_accept).uppercase() + " ›", 15.sp, com.skyrimayn.Sky.Text, 3.sp,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .focusRequester(acceptFocusRequester)
                        .clickable(onClick = onAccept)
                        .border(1.dp, com.skyrimayn.Sky.Line)
                        .padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
    }
}

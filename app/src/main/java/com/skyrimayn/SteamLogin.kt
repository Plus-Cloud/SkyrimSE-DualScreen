package com.skyrimayn

import android.graphics.Bitmap
import android.graphics.Color
import app.gamenative.PluviaApp
import app.gamenative.enums.LoginResult
import app.gamenative.events.SteamEvent
import app.gamenative.service.SteamService
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.CompletableFuture

/** What SkyrimSE-DualScreen's sign-in screen shows. */
sealed interface LoginUi {
    /** QR code (null while Steam is generating it) next to the username/password form. */
    data class Ready(val qrUrl: String?, val error: String? = null) : LoginUi
    data class Working(val message: String) : LoginUi
    /** Steam Guard: a code from the Steam app (email == null) or from an email. */
    data class NeedCode(val email: String?, val wrongCode: Boolean) : LoginUi
    /** Steam Guard: approve the sign-in in the Steam Mobile app. */
    data object Approve : LoginUi
}

/**
 * SkyrimSE-DualScreen's own Steam sign-in, on top of the engine's SteamService (JavaSteam).
 * The session is remembered, so this is needed once per device.
 */
object SteamLogin {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _ui = MutableStateFlow<LoginUi>(LoginUi.Ready(null))
    val ui: StateFlow<LoginUi> = _ui
    private val codes = Channel<String>(Channel.CONFLATED)
    private var registered = false
    private var qrRunning = false

    private val authenticator = object : IAuthenticator {
        override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
            _ui.value = LoginUi.Approve
            return CompletableFuture.completedFuture(true)
        }

        override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            _ui.value = LoginUi.NeedCode(null, previousCodeWasIncorrect)
            return awaitCode()
        }

        override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            _ui.value = LoginUi.NeedCode(email ?: "your email", previousCodeWasIncorrect)
            return awaitCode()
        }
    }

    private fun awaitCode() = CompletableFuture<String>().also { f -> scope.launch { f.complete(codes.receive()) } }

    private val onLogonEnded: (SteamEvent.LogonEnded) -> Unit = {
        Timber.i("SkyrimAYN: Steam logon ended: ${it.loginResult} ${it.message ?: ""}")
        if (it.loginResult == LoginResult.Failed) {
            qrRunning = false
            _ui.value = LoginUi.Ready(null, it.message ?: "Sign-in failed. Check your username and password.")
            startQr()
        }
    }
    private val onQrChallenge: (SteamEvent.QrChallengeReceived) -> Unit = {
        if (_ui.value is LoginUi.Ready) _ui.value = LoginUi.Ready(it.challengeUrl, (_ui.value as LoginUi.Ready).error)
    }
    private val onQrEnded: (SteamEvent.QrAuthEnded) -> Unit = {
        qrRunning = false
        if (it.success) _ui.value = LoginUi.Working("Signing in…")
        else if (_ui.value is LoginUi.Ready) startQr() // code expired: get a fresh one
    }

    private fun register() {
        if (registered) return
        registered = true
        PluviaApp.events.on<SteamEvent.LogonEnded, Unit>(onLogonEnded)
        PluviaApp.events.on<SteamEvent.QrChallengeReceived, Unit>(onQrChallenge)
        PluviaApp.events.on<SteamEvent.QrAuthEnded, Unit>(onQrEnded)
    }

    /** Called when the sign-in screen is visible and Steam is connected. */
    fun startQr() {
        register()
        if (qrRunning || SteamService.instance == null || !SteamService.isConnected) return
        qrRunning = true
        scope.launch {
            runCatching { SteamService.startLoginWithQr() }.onFailure {
                qrRunning = false
                Timber.e(it, "SkyrimAYN: QR login failed to start")
            }
        }
    }

    fun signIn(username: String, password: String) {
        register()
        if (username.isBlank() || password.isEmpty() || SteamService.instance == null) return
        SteamService.stopLoginWithQr()
        qrRunning = false
        _ui.value = LoginUi.Working("Signing in as ${username.trim()}…")
        scope.launch {
            runCatching {
                SteamService.startLoginWithCredentials(username, password, rememberSession = true, authenticator = authenticator)
            }.onFailure {
                _ui.value = LoginUi.Ready(null, "Couldn't reach Steam: ${it.message}")
                startQr()
            }
        }
    }

    fun submitCode(code: String) {
        if (code.isBlank()) return
        _ui.value = LoginUi.Working("Checking code…")
        codes.trySend(code.trim().uppercase())
    }

    fun stop() {
        SteamService.stopLoginWithQr()
        qrRunning = false
    }

    /** QR bitmap: dark modules on white, with a quiet zone, as Steam's scanner expects. */
    fun qrBitmap(text: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}

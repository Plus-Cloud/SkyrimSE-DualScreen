package com.skyrimayn

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Application
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns the bottom screen. The companion is part of the app's top-screen window, not a second app window: it's
 * drawn as an Android Presentation (a window on the other screen that belongs to the top screen's activity).
 * Android, and launchers like Cocoon, see one app with one task, so Back, Home, pause and resume treat both
 * screens as one game.
 *
 *  - Any of our screens comes up on top (launcher, game, Nexus): it shows the companion on the bottom screen.
 *    One companion view moves between them, so switching screens keeps its state.
 *  - The player leaves (Home, Back, another app): the top screen stops and the companion goes with it, so the
 *    shell gets the bottom screen back. It returns by itself with the game.
 */
object BottomScreenHost : Application.ActivityLifecycleCallbacks {
    private const val FOCUS_DELAY_MS = 50L

    private val main = Handler(Looper.getMainLooper())
    private var registered = false

    /** Our top-screen activities that are started, most recent last. */
    private val started = ArrayList<Activity>()
    private val topScreen: Activity? get() = started.lastOrNull()
    private val shownBy = HashMap<Activity, Presentation>()

    fun init(app: Application) {
        if (registered) return
        registered = true
        appContext = app
        app.registerActivityLifecycleCallbacks(this)
    }

    /** Closes the whole app: both screens, Steam's background service, and the process. */
    @Volatile var quitting = false

    /** The keyboard is open on the bottom screen. */
    @Volatile var keyboardHere = false
        private set

    /** Leaves the app the way Back leaves any other app: the game goes to the background and freezes until you return. */
    fun leaveApp() {
        main.post { topScreen?.takeIf { !it.isDestroyed }?.moveTaskToBack(true) }
    }

    fun quitApp(context: Context) {
        quitting = true
        main.post { hideAll() }
        runCatching { context.stopService(Intent(context, app.gamenative.service.SteamService::class.java)) }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { task -> runCatching { task.finishAndRemoveTask() } }
        // Steam's service restarts itself if the process dies while it's still running, which brought the app
        // straight back: end the process only once the service has stopped (or after 3 s).
        val deadline = System.currentTimeMillis() + 3000
        val end = object : Runnable {
            override fun run() {
                @Suppress("DEPRECATION")
                val steamUp = runCatching {
                    am.getRunningServices(50).any { it.service.className == app.gamenative.service.SteamService::class.java.name }
                }.getOrDefault(false)
                if (steamUp && System.currentTimeMillis() < deadline) main.postDelayed(this, 100)
                else android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
        main.postDelayed(end, 300)
    }

    private fun isTopScreen(a: Activity) = a !is BottomScreenActivity && a !is BottomPickerActivity && a !is FocusTrampolineActivity

    // ------------------------------------------------------------------ the companion view

    /** Lives as long as the app: the companion's state survives moving between screens. */
    private object Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()
        // Nothing dispatches here: the companion never takes keys (Back belongs to the game).
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
        private var ready = false
        fun ready() {
            if (ready) return
            ready = true
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    private var view: ComposeView? = null
    private val host = mutableStateOf<ComponentActivity?>(null)

    private fun companionView(context: Context): ComposeView {
        view?.let { return it }
        Owner.ready()
        // Its own recomposer: the view outlives each window it's shown in.
        val recomposer = Recomposer(AndroidUiDispatcher.Main)
        CoroutineScope(AndroidUiDispatcher.Main).launch { recomposer.runRecomposeAndApplyChanges() }
        return ComposeView(context).apply {
            id = android.view.View.generateViewId()
            setViewTreeLifecycleOwner(Owner)
            setViewTreeSavedStateRegistryOwner(Owner)
            setViewTreeViewModelStoreOwner(Owner)
            setParentCompositionContext(recomposer)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(Owner))
            setContent {
                val activity = host.value
                if (activity != null) {
                    CompositionLocalProvider(
                        LocalActivityResultRegistryOwner provides BottomPickerActivity.Owner,
                        LocalOnBackPressedDispatcherOwner provides Owner,
                    ) { SkyrimAynTheme { BottomRoot() } }
                }
            }
        }.also { view = it }
    }

    private fun bottomDisplay(a: Activity): Display? {
        val manager = a.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return manager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    /** Shows the companion on the bottom screen as part of [a]'s window. */
    private fun show(a: Activity) {
        if (quitting || a !is ComponentActivity || a.isFinishing || a.isDestroyed) return
        // Running on the bottom screen alone (single-screen): there's no other screen for the companion.
        if ((a.display?.displayId ?: Display.DEFAULT_DISPLAY) != Display.DEFAULT_DISPLAY) return
        val target = bottomDisplay(a) ?: return
        if (view == null) {
            Link.start(a.applicationContext)
            LauncherSettings.init(a.applicationContext)
            GameFolder.init(a.applicationContext)
            SkyrimEngine.refreshDrivers(a.applicationContext)
        }
        val v = companionView(a.applicationContext.createDisplayContext(target))
        val p = shownBy[a]?.takeIf { it.display.displayId == target.displayId } ?: run {
            shownBy.remove(a)?.let { runCatching { it.dismiss() } }
            Presentation(a, target, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                setCancelable(false)
                window?.apply {
                    // Touch only: the controller keeps driving the top screen.
                    addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
                    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            }.also { shownBy[a] = it }
        }
        host.value = a
        if (v.parent !== p.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)) {
            (v.parent as? ViewGroup)?.removeView(v)
            p.setContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if (!p.isShowing) runCatching { p.show() }.onFailure { shownBy.remove(a) }
    }

    private fun hide(a: Activity) {
        val p = shownBy.remove(a) ?: return
        val content = p.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
        view?.takeIf { it.parent === content }?.let { content?.removeView(it) }
        runCatching { p.dismiss() }
    }

    private fun hideAll() { shownBy.keys.toList().forEach(::hide) }

    // ------------------------------------------------------------------ keyboard and file pickers

    @Volatile var appContext: Context? = null
        private set

    /** Starts [intent] (one of our invisible helpers) on the bottom screen; false when the companion isn't up there. */
    fun openOnBottom(intent: Intent): Boolean {
        val a = topScreen ?: return false
        val p = shownBy[a]?.takeIf { it.isShowing } ?: return false
        val displayId = p.display.displayId
        main.post {
            runCatching {
                a.startActivity(
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
                )
            }.onFailure { if (intent.component?.className == BottomScreenActivity::class.java.name) keyboardHere = false }
        }
        return true
    }

    /** A text box on the top screen being typed into from the bottom screen. */
    class TextRequest(
        val label: String,
        val initial: String,
        val password: Boolean,
        val action: Int,
        val onChange: (String) -> Unit,
        val onDone: () -> Unit,
    )
    @Volatile var textRequest: TextRequest? = null
        private set

    /** True when the bottom screen is showing the companion (dual-screen), so typing can happen down there. */
    // (The companion counts even while it has stepped aside for the keyboard.)
    val typesOnBottom: Boolean get() = topScreen?.let { shownBy[it] } != null

    /**
     * Types into a text box on the top screen with the keyboard on the bottom screen. False when there's no bottom
     * screen (single-screen): the box then takes the keyboard itself.
     */
    fun editText(
        label: String, initial: String, password: Boolean = false,
        action: Int = android.view.inputmethod.EditorInfo.IME_ACTION_DONE,
        onChange: (String) -> Unit, onDone: () -> Unit = {},
    ): Boolean {
        val a = topScreen ?: return false
        // Typing just closed (its Enter/Search key landing on the top screen): don't reopen it.
        if (keyboardHere || android.os.SystemClock.uptimeMillis() - keyboardClosedAt < 1000) return true
        textRequest = TextRequest(label, initial, password, action, onChange, onDone)
        keyboardHere = true
        return openOnBottom(Intent(a, BottomScreenActivity::class.java)).also { if (!it) { keyboardHere = false; textRequest = null } }
    }

    /** Opens the game's keyboard on the bottom screen; false when the companion isn't up there. */
    fun openKeyboard(): Boolean {
        val a = topScreen ?: return false
        textRequest = null
        keyboardHere = true
        return openOnBottom(Intent(a, BottomScreenActivity::class.java)).also { if (!it) keyboardHere = false }
    }

    /** The keyboard or a file picker is open on the bottom screen: the companion steps aside, then comes back with focus on the game. */
    private var pickers = 0
    fun helperOpen(open: Boolean) {
        pickers = (pickers + if (open) 1 else -1).coerceAtLeast(0)
        val p = topScreen?.let { shownBy[it] } ?: return
        if (pickers > 0) runCatching { p.hide() }
        else {
            runCatching { p.show() }
            main.removeCallbacks(returnFocus)
            main.postDelayed(returnFocus, FOCUS_DELAY_MS)
        }
    }

    /** The keyboard closed: focus goes back to the game on the top screen. */
    private var keyboardClosedAt = 0L
    fun keyboardClosed() {
        keyboardClosedAt = android.os.SystemClock.uptimeMillis()
        keyboardHere = false
        textRequest = null
    }

    /** An invisible activity opened (and closed at once) on the top screen makes it Android's focused screen again. */
    private val returnFocus = Runnable {
        val top = topScreen?.takeIf { !it.isDestroyed } ?: return@Runnable
        top.startActivity(
            Intent(top, FocusTrampolineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
        )
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onActivityStarted(a: Activity) {
        if (!isTopScreen(a)) return
        started.remove(a)
        started.add(a)
        show(a)
    }

    override fun onActivityStopped(a: Activity) {
        if (!isTopScreen(a)) return
        started.remove(a)
        hide(a)
        // Another of our screens is still up (e.g. back from Nexus): the companion stays with it.
        topScreen?.let(::show)
    }

    override fun onActivityDestroyed(a: Activity) {
        started.remove(a)
        hide(a)
        if (host.value === a) host.value = topScreen as? ComponentActivity
    }

    override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(a: Activity) = Unit
    override fun onActivityPaused(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
}

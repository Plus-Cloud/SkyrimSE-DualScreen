package com.skyrimayn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat

/**
 * Runs the companion's file pickers (mod files, mod lists, drivers, DLLs) on the bottom screen, where you
 * tapped. The companion is part of the top screen's window, so a picker started from it would open on top
 * and send the game to the background; this invisible activity opens it down here instead and hands the
 * result back. The companion steps aside while the picker is up.
 */
class BottomPickerActivity : Activity() {
    private var requestCode = -1
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCode = intent.getIntExtra(EXTRA_CODE, -1)
        BottomScreenHost.helperOpen(true)
        if (savedInstanceState != null) return
        @Suppress("DEPRECATION")
        val target = intent.getParcelableExtra<Intent>(EXTRA_INTENT)
        if (target == null || runCatching { startActivityForResult(target, 1) }.isFailure) finishWith(RESULT_CANCELED, null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(code: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(code, resultCode, data)
        finishWith(resultCode, data)
    }

    private fun finishWith(resultCode: Int, data: Intent?) {
        if (done) return
        done = true
        Registry.dispatchResult(requestCode, resultCode, data)
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!done && isFinishing) { done = true; Registry.dispatchResult(requestCode, RESULT_CANCELED, null) }
        BottomScreenHost.helperOpen(false)
        super.onDestroy()
    }

    /** What the companion launches pickers through. */
    object Registry : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            val app = BottomScreenHost.appContext ?: return
            contract.getSynchronousResult(app, input)?.let { dispatchResult(requestCode, it.value); return }
            val target = contract.createIntent(app, input)
            if (!BottomScreenHost.openOnBottom(Intent(app, BottomPickerActivity::class.java)
                    .putExtra(EXTRA_INTENT, target).putExtra(EXTRA_CODE, requestCode))) {
                dispatchResult(requestCode, RESULT_CANCELED, null)
            }
        }
    }

    object Owner : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = Registry
    }

    private companion object {
        const val EXTRA_INTENT = "target"
        const val EXTRA_CODE = "code"
    }
}

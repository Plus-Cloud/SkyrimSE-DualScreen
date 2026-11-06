package com.skyrimayn

import android.app.Activity

/**
 * Invisible, finishes as soon as it resumes. Starting it on the top display makes that display
 * the "top focused display" again after the bottom-screen companion launched, so controller
 * keys go to the top screen. (Taps on the non-focusable companion don't steal focus.)
 */
class FocusTrampolineActivity : Activity() {
    override fun onResume() {
        super.onResume()
        finish()
        overridePendingTransition(0, 0)
    }
}

package com.clearcmos.kata.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Pads a screen out from under the status and navigation bars.
 *
 * From targetSdk 35 the platform draws every app edge-to-edge and stops insetting content for
 * the system bars, so without this the top of a layout sits behind the status bar. Splitting
 * the top and bottom targets lets a scrolling list keep its content clear of the gesture bar
 * while still scrolling underneath it.
 */
object Insets {
    fun applySystemBars(top: View, bottom: View = top) {
        val bottomBasePadding = bottom.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(top) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top)
            bottom.updatePadding(bottom = bottomBasePadding + bars.bottom)
            windowInsets
        }
    }
}

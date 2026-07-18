package com.household.manager.tabletapp

import android.app.Activity
import android.view.View
import android.view.WindowManager

/**
 * Soft-Off für das Wandtablet: schwarzes Overlay + Bildschirmhelligkeit 0.
 * Das Display bleibt technisch an (FLAG_KEEP_SCREEN_ON in der Activity),
 * damit Kamera und App durchgehend weiterlaufen.
 */
class DisplayController(private val activity: Activity, private val overlay: View) {

    fun turnOn() {
        overlay.visibility = View.GONE
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    fun turnOff() {
        overlay.visibility = View.VISIBLE
        setBrightness(0f)
    }

    private fun setBrightness(value: Float) {
        val params = activity.window.attributes
        params.screenBrightness = value
        activity.window.attributes = params
    }
}

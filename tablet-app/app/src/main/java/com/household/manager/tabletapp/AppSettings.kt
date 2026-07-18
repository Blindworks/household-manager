package com.household.manager.tabletapp

import android.content.Context

/**
 * Persistente App-Einstellungen (SharedPreferences). Server-URLs zeigen auf
 * den Household-Manager im LAN und sind über den Settings-Screen anpassbar.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("tablet_app", Context.MODE_PRIVATE)

    var dashboardUrl: String
        get() = prefs.getString(KEY_DASHBOARD_URL, "http://192.168.178.2:4200")!!
        set(value) = prefs.edit().putString(KEY_DASHBOARD_URL, value.trim()).apply()

    /** Basis-URL des Backends inklusive Context-Path, z. B. http://192.168.178.2:8080/api */
    var backendBaseUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, "http://192.168.178.2:8080/api")!!
        set(value) = prefs.edit().putString(KEY_BACKEND_URL, value.trim().trimEnd('/')).apply()

    var tabletId: String
        get() = prefs.getString(KEY_TABLET_ID, "wandtablet")!!
        set(value) = prefs.edit().putString(KEY_TABLET_ID, value.trim()).apply()

    var displayTimeoutSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT_SECONDS, 60)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT_SECONDS, value.coerceAtLeast(5)).apply()

    /** Schwellwert der Bewegungserkennung (Helligkeitsstufen); höher = unempfindlicher. */
    var motionPixelThreshold: Int
        get() = prefs.getInt(KEY_MOTION_THRESHOLD, 25)
        set(value) = prefs.edit().putInt(KEY_MOTION_THRESHOLD, value.coerceIn(5, 100)).apply()

    private companion object {
        const val KEY_DASHBOARD_URL = "dashboardUrl"
        const val KEY_BACKEND_URL = "backendBaseUrl"
        const val KEY_TABLET_ID = "tabletId"
        const val KEY_TIMEOUT_SECONDS = "displayTimeoutSeconds"
        const val KEY_MOTION_THRESHOLD = "motionPixelThreshold"
    }
}

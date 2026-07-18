package com.household.manager.tabletapp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Meldet Präsenz-Wechsel an das Household-Manager-Backend im LAN und sendet
 * zusätzlich einen periodischen Heartbeat mit dem letzten Zustand. Fehler
 * werden nur geloggt — die Display-Logik funktioniert vollständig offline.
 */
class PresenceReporter(
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val heartbeatIntervalMs: Long = 60_000
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var lastPresent: Boolean? = null

    fun reportPresence(present: Boolean) {
        lastPresent = present
        scope.launch(Dispatchers.IO) { post(present) }
    }

    fun startHeartbeat() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(heartbeatIntervalMs)
                lastPresent?.let { post(it) }
            }
        }
    }

    private fun post(present: Boolean) {
        val url = "${settings.backendBaseUrl}/v1/tablet-presence/${settings.tabletId}"
        val body = """{"present":$present}""".toRequestBody("application/json".toMediaType())
        try {
            client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Backend antwortete mit ${response.code} auf $url")
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Präsenz-Meldung fehlgeschlagen: ${ex.message}")
        }
    }

    private companion object {
        const val TAG = "PresenceReporter"
    }
}

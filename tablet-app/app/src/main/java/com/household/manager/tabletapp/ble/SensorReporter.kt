package com.household.manager.tabletapp.ble

import android.os.SystemClock
import android.util.Log
import com.household.manager.tabletapp.AppSettings
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
 * Schickt einmal pro Minute EINE Sammelmeldung aller frisch gesehenen Sensoren an
 * POST /v1/ble-sensors/readings (Service-Token wie die Praesenz). Fehler werden nur geloggt,
 * keine Wiederholung — die naechste Minute bringt frische Werte.
 */
class SensorReporter(
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val readings: () -> List<SeenReading>,
    private val intervalMs: Long = 60_000,
    private val maxAgeMs: Long = 300_000
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(intervalMs)
                val payload = buildPayload(settings.tabletId, readings(), SystemClock.elapsedRealtime(), maxAgeMs)
                    ?: continue
                post(payload)
            }
        }
    }

    private fun post(payload: String) {
        val url = "${settings.backendBaseUrl}/v1/ble-sensors/readings"
        try {
            val builder = Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType()))
            if (settings.apiToken.isNotBlank()) {
                builder.addHeader("X-API-Token", settings.apiToken)
            }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Backend antwortete mit ${response.code} auf $url")
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "BLE-Meldung fehlgeschlagen: ${ex.message}")
        }
    }

    companion object {
        private const val TAG = "SensorReporter"

        /** Backend lehnt Sammelmeldungen mit mehr Eintraegen komplett ab (400). */
        const val MAX_READINGS = 50

        /** Reine Funktion fuer den Unit-Test: null, wenn nichts Frisches zu melden ist. */
        fun buildPayload(tabletId: String, seen: List<SeenReading>, nowElapsedMs: Long, maxAgeMs: Long): String? {
            val fresh = seen.filter { nowElapsedMs - it.seenAtElapsedMs <= maxAgeMs }
                .sortedByDescending { it.rssi }
                .take(MAX_READINGS)
            if (fresh.isEmpty()) return null
            val items = fresh.joinToString(",") {
                val age = (nowElapsedMs - it.seenAtElapsedMs) / 1000
                """{"mac":"${it.reading.mac}","temperature":${it.reading.temperature},"humidity":${it.reading.humidity},""" +
                    """"batteryMillivolts":${it.reading.batteryMillivolts},"rssi":${it.rssi},"ageSeconds":$age}"""
            }
            return """{"tabletId":"${escape(tabletId)}","readings":[$items]}"""
        }

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}

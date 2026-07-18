package com.household.manager.tabletapp

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/** Einstellungs-Formular; erreichbar nur über die versteckte Geste im Kiosk. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val settings = AppSettings(this)

        val dashboardUrl = findViewById<EditText>(R.id.input_dashboard_url)
        val backendUrl = findViewById<EditText>(R.id.input_backend_url)
        val tabletId = findViewById<EditText>(R.id.input_tablet_id)
        val timeout = findViewById<EditText>(R.id.input_timeout)
        val motionThreshold = findViewById<EditText>(R.id.input_motion_threshold)

        dashboardUrl.setText(settings.dashboardUrl)
        backendUrl.setText(settings.backendBaseUrl)
        tabletId.setText(settings.tabletId)
        timeout.setText(settings.displayTimeoutSeconds.toString())
        motionThreshold.setText(settings.motionPixelThreshold.toString())

        findViewById<Button>(R.id.button_save).setOnClickListener {
            settings.dashboardUrl = dashboardUrl.text.toString().ifBlank { settings.dashboardUrl }
            settings.backendBaseUrl = backendUrl.text.toString().ifBlank { settings.backendBaseUrl }
            settings.tabletId = tabletId.text.toString().ifBlank { settings.tabletId }
            settings.displayTimeoutSeconds = timeout.text.toString().toIntOrNull() ?: 60
            settings.motionPixelThreshold = motionThreshold.text.toString().toIntOrNull() ?: 25
            finish()
        }
    }
}

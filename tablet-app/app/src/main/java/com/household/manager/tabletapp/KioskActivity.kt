package com.household.manager.tabletapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.household.manager.tabletapp.presence.FacePresenceDetector
import com.household.manager.tabletapp.presence.MotionDetector
import com.household.manager.tabletapp.presence.PresenceAnalyzer
import com.household.manager.tabletapp.presence.PresenceCamera
import com.household.manager.tabletapp.presence.PresenceListener
import com.household.manager.tabletapp.presence.PresenceStateMachine
import com.household.manager.tabletapp.presence.PresenceStateMachine.DisplayState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * Vollbild-Kiosk: zeigt das Dashboard im WebView und steuert das Display über
 * die Präsenzerkennung. Fail-safe: ohne Kamera(-Berechtigung) oder bei
 * Kamerafehlern bleibt das Display dauerhaft an.
 */
class KioskActivity : AppCompatActivity(), PresenceListener {

    private lateinit var settings: AppSettings
    private lateinit var webView: WebView
    private lateinit var displayController: DisplayController
    private lateinit var reporter: PresenceReporter
    private var stateMachine: PresenceStateMachine? = null

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { loadDashboard() }
    private var loadedDashboardUrl: String? = null
    private var lastAppliedState: DisplayState? = null
    private val presenceCamera = PresenceCamera()
    private var cameraStarted = false
    private var startedMotionThreshold = 0

    /** Fail-safe: bei Kameraproblemen wird die Abschalt-Logik deaktiviert. */
    private var cameraFailed = false

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startPresenceDetection()
            } else {
                onCameraFailure("Kamera-Berechtigung verweigert")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kiosk)
        settings = AppSettings(this)
        webView = findViewById(R.id.webview)
        val overlay = findViewById<View>(R.id.overlay)
        displayController = DisplayController(this, overlay)
        reporter = PresenceReporter(settings, scope)

        // Android darf das Gerät nie selbst sperren — hell/dunkel entscheidet die App
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupWebView()
        overlay.setOnClickListener { onMotion() }
        findViewById<View>(R.id.settings_corner).setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        reporter.startHeartbeat()
        cameraPermission.launch(Manifest.permission.CAMERA)
        handler.post(tickRunnable)
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        // Einstellungen können sich im SettingsScreen geändert haben
        if (loadedDashboardUrl != settings.dashboardUrl) {
            loadDashboard()
        }
        stateMachine = PresenceStateMachine(
            settings.displayTimeoutSeconds * 1000L,
            SystemClock.elapsedRealtime()
        )
        applyDisplayState(DisplayState.ON)
        if (cameraStarted && settings.motionPixelThreshold != startedMotionThreshold) {
            startPresenceDetection()
        }
    }

    override fun onPause() {
        super.onPause()
        // Cookies sofort persistieren, damit der Login einen Neustart überlebt
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(retryRunnable)
        scope.cancel()
        webView.destroy()
        super.onDestroy()
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!cameraFailed) {
                stateMachine?.let { applyDisplayState(it.tick(SystemClock.elapsedRealtime())) }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onMotion() {
        runOnUiThread {
            stateMachine?.let { applyDisplayState(it.onMotion(SystemClock.elapsedRealtime())) }
        }
    }

    override fun onFace() {
        runOnUiThread {
            stateMachine?.let { applyDisplayState(it.onFace(SystemClock.elapsedRealtime())) }
        }
    }

    private fun applyDisplayState(state: DisplayState) {
        if (state == lastAppliedState) {
            return
        }
        lastAppliedState = state
        when (state) {
            DisplayState.ON -> {
                displayController.turnOn()
                reporter.reportPresence(true)
            }
            DisplayState.OFF -> {
                displayController.turnOff()
                reporter.reportPresence(false)
            }
        }
    }

    private fun startPresenceDetection() {
        cameraStarted = true
        startedMotionThreshold = settings.motionPixelThreshold
        val motionDetector = MotionDetector(pixelThreshold = settings.motionPixelThreshold)
        val analyzer = PresenceAnalyzer(motionDetector, FacePresenceDetector(), this)
        presenceCamera.start(this, this, analyzer) { ex ->
            runOnUiThread { onCameraFailure(ex.message ?: "unbekannter Kamera-Fehler") }
        }
    }

    /** Idempotent: kann mehrfach gerufen werden (Kamera-Fehler können sich wiederholen). */
    private fun onCameraFailure(reason: String) {
        if (!cameraFailed) {
            Log.w(TAG, "Kamera nicht nutzbar — Display bleibt dauerhaft an (Fail-safe): $reason")
        }
        cameraFailed = true
        applyDisplayState(DisplayState.ON)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // Session-/Remember-Me-Cookie muss App- und Geräteneustarts überleben —
        // der einmalige Login am Tablet läuft über die Login-Seite im WebView.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    Log.w(TAG, "Dashboard nicht erreichbar, neuer Versuch in 10 s")
                    view.loadDataWithBaseURL(null, ERROR_PAGE, "text/html", "utf-8", null)
                    handler.removeCallbacks(retryRunnable)
                    handler.postDelayed(retryRunnable, 10_000)
                }
            }
        }
        loadDashboard()
    }

    private fun loadDashboard() {
        handler.removeCallbacks(retryRunnable)
        loadedDashboardUrl = settings.dashboardUrl
        webView.loadUrl(settings.dashboardUrl)
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, webView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val TAG = "KioskActivity"
        const val ERROR_PAGE = """
            <html><body style="background:#111;color:#ccc;font-family:sans-serif;
            display:flex;align-items:center;justify-content:center;height:100vh">
            <div><h2>Dashboard nicht erreichbar</h2>
            <p>Neuer Verbindungsversuch in 10&nbsp;Sekunden &hellip;</p></div>
            </body></html>"""
    }
}

package com.household.manager.tabletapp.presence

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * Startet die Frontkamera mit einem ImageAnalysis-Use-Case in niedriger
 * Auflösung. Fehler führen nie zum Absturz — bei Kameraproblemen bleibt das
 * Display dauerhaft an (Fail-safe, siehe Aufrufer).
 */
class PresenceCamera {

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    fun start(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        analyzer: ImageAnalysis.Analyzer,
        onError: (Exception) -> Unit
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, analyzer)
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                Log.i(TAG, "Frontkamera für Präsenzerkennung gestartet")
            } catch (ex: Exception) {
                onError(ex)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private companion object {
        const val TAG = "PresenceCamera"
    }
}

package com.household.manager.tabletapp.presence

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/** Callbacks der Kamera-Pipeline; werden auf Hintergrund-Threads aufgerufen. */
interface PresenceListener {
    fun onMotion()
    fun onFace()
}

/**
 * Verbindet CameraX-Frames mit der Erkennung: jedes analysierte Frame läuft
 * durch den [MotionDetector], jedes [faceEveryNthFrame]-te zusätzlich durch
 * die ML-Kit-Gesichtserkennung. [minIntervalMs] drosselt die Analyse,
 * damit CPU-Last und Wärmeentwicklung gering bleiben.
 */
class PresenceAnalyzer(
    private val motionDetector: MotionDetector,
    private val faceDetector: FacePresenceDetector,
    private val listener: PresenceListener,
    private val faceEveryNthFrame: Int = 5,
    private val minIntervalMs: Long = 300
) : ImageAnalysis.Analyzer {

    private var lastAnalyzedMs = 0L
    private var frameCount = 0L

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzedMs < minIntervalMs) {
            image.close()
            return
        }
        lastAnalyzedMs = now
        frameCount++

        if (motionDetector.detect(extractLuma(image))) {
            listener.onMotion()
        }

        if (frameCount % faceEveryNthFrame == 0L) {
            faceDetector.process(image) { hasFace ->
                if (hasFace) {
                    listener.onFace()
                }
                image.close()
            }
        } else {
            image.close()
        }
    }

    /** Tastet die Y-Ebene (Luma) grob ab — für Bewegungserkennung reicht ein Raster. */
    private fun extractLuma(image: ImageProxy, step: Int = 4): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val out = ByteArray((height / step) * (width / step))
        var i = 0
        for (y in 0 until height - (height % step) step step) {
            for (x in 0 until width - (width % step) step step) {
                out[i++] = buffer.get(y * plane.rowStride + x * plane.pixelStride)
            }
        }
        return out
    }
}

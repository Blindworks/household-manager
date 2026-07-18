package com.household.manager.tabletapp.presence

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Kapselt ML Kit Face Detection (Fast-Modus, vollständig lokal).
 * Liefert nur die Information "mindestens ein Gesicht sichtbar".
 */
class FacePresenceDetector {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.1f)
            .build()
    )

    /** [onComplete] wird auf einem ML-Kit-Thread aufgerufen; der Aufrufer schließt das ImageProxy. */
    @OptIn(ExperimentalGetImage::class)
    fun process(imageProxy: ImageProxy, onComplete: (Boolean) -> Unit) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            onComplete(false)
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces -> onComplete(faces.isNotEmpty()) }
            .addOnFailureListener { onComplete(false) }
    }
}

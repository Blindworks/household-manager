package com.household.manager.tabletapp.presence

import kotlin.math.abs

/**
 * Bewegungserkennung per Luma-Frame-Differenz: meldet Bewegung, wenn sich
 * mindestens [motionFraction] der Pixel um mehr als [pixelThreshold]
 * Helligkeitsstufen gegenüber dem letzten Frame geändert haben.
 */
class MotionDetector(
    private val pixelThreshold: Int = 25,
    private val motionFraction: Double = 0.02
) {

    private var previousFrame: ByteArray? = null

    fun detect(luma: ByteArray): Boolean {
        val previous = previousFrame
        previousFrame = luma.copyOf()
        if (previous == null || previous.size != luma.size) {
            return false
        }
        var changedPixels = 0
        for (i in luma.indices) {
            val diff = (luma[i].toInt() and 0xFF) - (previous[i].toInt() and 0xFF)
            if (abs(diff) > pixelThreshold) {
                changedPixels++
            }
        }
        return changedPixels.toDouble() / luma.size >= motionFraction
    }
}

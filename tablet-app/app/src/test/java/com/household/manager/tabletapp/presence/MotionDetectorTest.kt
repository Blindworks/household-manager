package com.household.manager.tabletapp.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {

    private val detector = MotionDetector(pixelThreshold = 25, motionFraction = 0.02)

    private fun frame(value: Byte, size: Int = 100) = ByteArray(size) { value }

    @Test
    fun `first frame never reports motion`() {
        assertFalse(detector.detect(frame(10)))
    }

    @Test
    fun `identical frames report no motion`() {
        detector.detect(frame(10))
        assertFalse(detector.detect(frame(10)))
    }

    @Test
    fun `large change reports motion`() {
        detector.detect(frame(10))
        assertTrue(detector.detect(frame(120)))
    }

    @Test
    fun `small noise below pixel threshold reports no motion`() {
        detector.detect(frame(10))
        assertFalse(detector.detect(frame(20))) // Differenz 10 < pixelThreshold 25
    }

    @Test
    fun `change in tiny area reports no motion`() {
        detector.detect(frame(10, size = 1000))
        val next = frame(10, size = 1000)
        next[0] = 120 // 1 von 1000 Pixeln = 0.1 % < 2 %
        assertFalse(detector.detect(next))
    }

    @Test
    fun `frame size change resets comparison`() {
        detector.detect(frame(10, size = 100))
        assertFalse(detector.detect(frame(120, size = 200)))
    }
}

package org.futo.voiceinput.parakeet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class InputLevelTest {
    private fun tone(n: Int, amplitude: Float) = FloatArray(n) { if (it % 2 == 0) amplitude else -amplitude }
    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size).toFloat()

    @Test
    fun quietRecordingIsRaisedToTheTargetLevel() {
        val quiet = tone(16_000, 0.014f) // about -37 dBFS, like the failed recordings
        val out = InputLevel.normalize(quiet)
        assertEquals(InputLevel.TARGET_RMS, rms(out), 0.002f)
    }

    @Test
    fun normalRecordingIsLeftUntouched() {
        val normal = tone(16_000, 0.1f)
        assertSame(normal, InputLevel.normalize(normal))
    }

    @Test
    fun loudPeaksLimitTheGain() {
        // Quiet average but a long loud part: the loud part must not be pushed past the peak target.
        val x = tone(16_000, 0.005f)
        for (i in 0 until 1_000) x[i] = if (i % 2 == 0) 0.5f else -0.5f
        val out = InputLevel.normalize(x)
        assertTrue(out.maxOf { kotlin.math.abs(it) } <= InputLevel.PEAK_TARGET + 0.01f)
    }

    @Test
    fun gainIsCappedForNearSilence() {
        assertEquals(InputLevel.MAX_GAIN, InputLevel.gain(tone(16_000, 0.0005f)), 0.0f)
    }

    @Test
    fun digitalSilenceAndEmptyInputStayUnchanged() {
        val silence = FloatArray(1_000)
        assertSame(silence, InputLevel.normalize(silence))
        assertEquals(0, InputLevel.normalize(FloatArray(0)).size)
    }

    @Test
    fun outputStaysInRange() {
        val x = tone(16_000, 0.01f)
        x[5] = 0.99f // a single click is clipped rather than limiting the gain
        assertTrue(InputLevel.normalize(x).all { it in -1f..1f })
    }
}

package org.futo.voiceinput.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchSegmentsTest {
    private fun tone(n: Int, amplitude: Float) = FloatArray(n) { if (it % 2 == 0) amplitude else -amplitude }

    @Test
    fun cutsAtThePauseBeforeTheTap() {
        // 2 s of speech, a 100 ms pause at 1.5 s, speech again until the tap.
        val audio = tone(32_000, 0.3f)
        for (i in 24_000 until 25_600) audio[i] = 0f
        val cut = SwitchSegments.quietCut(audio, 0, audio.size)
        assertTrue("cut $cut should be inside the pause", cut in 24_000..25_600)
    }

    @Test
    fun cutIsAbsoluteWhenTheSegmentDoesNotStartAtZero() {
        val from = 48_000
        val audio = tone(20_000, 0.3f)
        for (i in 10_000 until 11_000) audio[i] = 0f
        val cut = SwitchSegments.quietCut(audio, from, from + audio.size)
        assertTrue(cut in from + 10_000..from + 11_000)
    }

    @Test
    fun withoutAPauseTheCutStaysNearTheTap() {
        val audio = tone(32_000, 0.3f)
        val cut = SwitchSegments.quietCut(audio, 0, audio.size)
        assertTrue(cut in 31_000..32_000)
    }

    @Test
    fun shortSegmentStaysInRange() {
        val audio = tone(100, 0.3f)
        val cut = SwitchSegments.quietCut(audio, 500, 600)
        assertTrue(cut in 501..600)
    }

    @Test
    fun joinKeepsOrderAndSkipsEmptyParts() {
        assertEquals("Hello there. Dobrý deň.", SwitchSegments.join(listOf(" Hello there. ", "", "Dobrý deň.")))
        assertEquals("", SwitchSegments.join(listOf("", " ")))
    }

    @Test
    fun anySlovakPartTurnsEnglishCleanupOff() {
        assertEquals("sk", SwitchSegments.cleanupLanguage(listOf("sk"), "en"))
        assertEquals("sk", SwitchSegments.cleanupLanguage(listOf("en"), "sk"))
        assertEquals("en", SwitchSegments.cleanupLanguage(listOf("en"), "en"))
        assertNull(SwitchSegments.cleanupLanguage(listOf(null), null))
    }
}

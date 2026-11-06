package org.futo.voiceinput.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseChunkerTest {
    private val rate = 16_000

    /** Builds audio from (seconds, amplitude) parts; a tiny noise floor stands in for the room. */
    private fun audio(vararg parts: Pair<Double, Float>): FloatArray {
        val out = ArrayList<Float>()
        for ((seconds, amplitude) in parts) {
            val n = (seconds * rate).toInt()
            for (i in 0 until n) {
                val level = if (amplitude == 0f) 0.0005f else amplitude
                out += if (i % 2 == 0) level else -level
            }
        }
        return out.toFloatArray()
    }

    @Test
    fun cutsInsideAPauseAfterSpeech() {
        val samples = audio(2.0 to 0.1f, 0.8 to 0f, 1.0 to 0.1f)
        val cut = PauseChunker.findCut(samples, 0, samples.size)
        assertNotNull(cut)
        assertTrue("cut $cut should be inside the pause", cut!! in 2 * rate..(2.8 * rate).toInt())
        // Up to 300 ms into the pause, so the piece ends with a short silence.
        assertTrue(cut - 2 * rate <= (0.3 * rate).toInt() + 320)
    }

    @Test
    fun waitsWhileThePauseIsShorterThanHalfASecond() {
        val samples = audio(2.0 to 0.1f, 0.3 to 0f, 1.0 to 0.1f)
        assertNull(PauseChunker.findCut(samples, 0, samples.size))
    }

    @Test
    fun cutsAtAPauseThatIsStillGoingOn() {
        val samples = audio(2.0 to 0.1f, 0.6 to 0f)
        val cut = PauseChunker.findCut(samples, 0, samples.size)
        assertNotNull(cut)
        assertTrue(cut!! >= 2 * rate)
    }

    @Test
    fun picksTheLatestPause() {
        val samples = audio(2.0 to 0.1f, 0.6 to 0f, 2.0 to 0.1f, 0.6 to 0f, 0.5 to 0.1f)
        val cut = PauseChunker.findCut(samples, 0, samples.size)!!
        assertTrue("cut $cut should be in the second pause", cut >= (4.6 * rate).toInt())
    }

    @Test
    fun ignoresAPauseBeforeTheFirstOneAndAHalfSeconds() {
        // Long enough to be searched, but the only pause starts before 1.5 s.
        val samples = audio(0.5 to 0.1f, 0.6 to 0f, 1.5 to 0.1f)
        assertNull(PauseChunker.findCut(samples, 0, samples.size))
    }

    @Test
    fun ignoresLeadingSilenceWithoutSpeechBeforeIt() {
        val samples = audio(2.0 to 0f, 1.0 to 0.1f)
        assertNull(PauseChunker.findCut(samples, 0, samples.size))
    }

    @Test
    fun quietRecordingIsCutTheSameWay() {
        // About -37 dBFS speech, the level that once gave empty results.
        val samples = audio(2.0 to 0.014f, 0.8 to 0f, 1.0 to 0.014f)
        assertNotNull(PauseChunker.findCut(samples, 0, samples.size))
    }

    @Test
    fun silenceIsNeverCut() {
        val samples = audio(20.0 to 0f)
        assertNull(PauseChunker.findCut(samples, 0, samples.size))
    }

    @Test
    fun longSpeechWithoutPauseIsCutAfterFifteenSeconds() {
        val short = audio(14.0 to 0.1f)
        assertNull(PauseChunker.findCut(short, 0, short.size))
        val long = audio(16.0 to 0.1f)
        val cut = PauseChunker.findCut(long, 0, long.size)!!
        assertTrue(cut in 14 * rate until long.size)
    }

    @Test
    fun cutIsAbsolute() {
        val from = 100_000
        val samples = audio(2.0 to 0.1f, 0.8 to 0f, 1.0 to 0.1f)
        val cut = PauseChunker.findCut(samples, from, from + samples.size)!!
        assertTrue(cut in from + 2 * rate..from + (2.8 * rate).toInt())
    }

    @Test
    fun offlineRuntimesExcludeStreamingModels() {
        assertEquals(setOf("parakeet", "orukeet", "parakeet_ultra", "parakeet_sk"), PauseChunker.OFFLINE_PARAKEET_RUNTIMES)
    }
}

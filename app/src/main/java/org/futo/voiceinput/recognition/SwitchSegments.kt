package org.futo.voiceinput.recognition

/**
 * Splits a recording when the SK | EN button is tapped while speaking, so no audio is lost:
 * the audio before the tap is transcribed by the model that was active then, and only the
 * rest goes to the newly selected model.
 */
object SwitchSegments {
    /** Shorter leftovers are not decoded on their own; they stay with the next model. */
    const val MIN_SEGMENT_SAMPLES = 16_000 * 3 / 10
    private const val FRAME_SAMPLES = 320 // 20 ms at 16 kHz
    private const val SEARCH_SAMPLES = 16_000 // look back up to 1 s for a pause

    /**
     * Picks where to split: the quietest 20 ms frame in the second before the tap, so a word
     * spoken across the tap is less likely to be cut. [samples] holds the audio of [from, to).
     * Returns an absolute sample index in (from, to].
     */
    fun quietCut(samples: FloatArray, from: Int, to: Int): Int {
        val searchStart = maxOf(from, to - SEARCH_SAMPLES)
        var best = to
        var bestEnergy = Double.MAX_VALUE
        var frameStart = to - FRAME_SAMPLES
        while (frameStart >= searchStart) {
            var energy = 0.0
            for (i in frameStart until frameStart + FRAME_SAMPLES) {
                val s = samples[i - from].toDouble()
                energy += s * s
            }
            // Strictly lower keeps the frame closest to the tap among equally quiet ones.
            if (energy < bestEnergy) {
                bestEnergy = energy
                best = frameStart + FRAME_SAMPLES / 2
            }
            frameStart -= FRAME_SAMPLES
        }
        return best.coerceIn(from + 1, to)
    }

    /** Joins the per-model transcripts in recording order, skipping empty parts. */
    fun join(parts: List<String>): String = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * Language for the English cleanup gates when a recording mixes models: any Slovak part
     * keeps English-only cleanup off, so it cannot rewrite the Slovak text.
     */
    fun cleanupLanguage(segmentLanguages: List<String?>, finalLanguage: String?): String? {
        val all = segmentLanguages + finalLanguage
        return if (all.any { it == DictationLanguage.Slovak.tag }) DictationLanguage.Slovak.tag else finalLanguage
    }
}

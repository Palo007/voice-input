package org.futo.voiceinput.recognition

import kotlin.math.sqrt

/**
 * Live text at pauses for offline models (issue #52, idea 2).
 *
 * While the user speaks, the recording is cut at pauses and each finished piece is decoded by
 * the offline model, so its text can be shown before Stop. After Stop only the last piece is
 * left to decode. This object only picks where to cut; it does not decode.
 *
 * Pauses are found from the level of 20 ms frames relative to this piece's own speech level,
 * so quiet recordings (about -37 dBFS) are cut the same way as loud ones.
 */
object PauseChunker {
    private const val FRAME_SAMPLES = 320 // 20 ms at 16 kHz
    /** A pause must be at least this long (500 ms) to cut there. */
    const val MIN_PAUSE_FRAMES = 25
    /** The cut is placed up to 300 ms into the pause, so the piece keeps a short silent tail. */
    private const val MAX_CUT_OFFSET_FRAMES = 15
    /** Pieces shorter than 1.5 s are not decoded alone; short pieces lose context. */
    const val MIN_CHUNK_SAMPLES = 16_000 * 3 / 2
    /** Without a pause, a piece is cut anyway after 15 s, at the quietest point near its end. */
    const val MAX_CHUNK_SAMPLES = 16_000 * 15
    /** Below about -54 dBFS RMS the piece has no speech worth decoding early. */
    private const val MIN_SPEECH_RMS = 0.002
    /** Speech must be at least this many times louder than the background to tell pauses apart. */
    private const val MIN_CONTRAST = 4.0

    /**
     * Returns an absolute sample index in (from, to) where the piece [from, to) can be cut and its
     * first part decoded, or null when there is no good cut yet. [samples] holds [from, to).
     */
    fun findCut(samples: FloatArray, from: Int, to: Int): Int? {
        val length = to - from
        if (length < MIN_CHUNK_SAMPLES) return null
        val frames = length / FRAME_SAMPLES
        val rms = DoubleArray(frames) { frame ->
            var sum = 0.0
            val start = frame * FRAME_SAMPLES
            for (i in start until start + FRAME_SAMPLES) {
                val s = samples[i].toDouble()
                sum += s * s
            }
            sqrt(sum / FRAME_SAMPLES)
        }
        val sorted = rms.sortedArray()
        val floor = sorted[frames / 10]
        val speech = sorted[frames * 9 / 10]
        if (speech < MIN_SPEECH_RMS) return null

        if (speech >= floor * MIN_CONTRAST) {
            // Halfway between background and speech on a log scale, but well below speech.
            val quiet = minOf(sqrt(maxOf(floor, 1e-6) * speech), speech / 4)
            val minCutFrame = MIN_CHUNK_SAMPLES / FRAME_SAMPLES
            // The latest pause wins, so as much text as possible is shown at once.
            var runEnd = frames
            var frame = frames - 1
            while (frame >= 0) {
                if (rms[frame] >= quiet) { runEnd = frame; frame--; continue }
                var runStart = frame
                while (runStart > 0 && rms[runStart - 1] < quiet) runStart--
                val runLength = runEnd - runStart
                if (runLength >= MIN_PAUSE_FRAMES) {
                    val cutFrame = runStart + minOf(runLength / 2, MAX_CUT_OFFSET_FRAMES)
                    val spokeBefore = (0 until runStart).any { rms[it] >= quiet }
                    if (cutFrame >= minCutFrame && spokeBefore) return from + cutFrame * FRAME_SAMPLES
                }
                runEnd = runStart
                frame = runStart - 1
            }
        }

        if (length < MAX_CHUNK_SAMPLES) return null
        // No pause for 15 s: cut at the quietest point in the second before the last 200 ms.
        return SwitchSegments.quietCut(samples, from, to - 10 * FRAME_SAMPLES).takeIf { it > from }
    }

    /** Runtimes whose offline engine can decode pieces while recording. */
    val OFFLINE_PARAKEET_RUNTIMES = setOf("parakeet", "orukeet", "parakeet_ultra", "parakeet_sk")
}

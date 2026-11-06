package org.futo.voiceinput.parakeet

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Raises quiet recordings before Parakeet decodes them.
 *
 * The microphone source has no automatic gain, so a phone held away from the mouth can record
 * at about -37 dBFS. Parakeet TDT models (Ultra and the Slovak fine-tune alike) then return no
 * text at all for longer recordings. Measured on such recordings, a gain of 11-13 dB brought the
 * full transcript back, and already normal recordings decoded the same.
 * See docs/ISSUE-52-language-routing-notes.md.
 */
internal object InputLevel {
    /** -25 dBFS average level. */
    const val TARGET_RMS = 0.056f
    /** The loudest 0.1 % of samples may reach this; rarer clicks are clipped. */
    const val PEAK_TARGET = 0.9f
    /** +20 dB at most, so near-silence is not raised into noise. */
    const val MAX_GAIN = 10f
    private const val MIN_GAIN = 1.05f
    private const val SILENCE_RMS = 1e-5

    fun gain(samples: FloatArray): Float {
        if (samples.isEmpty()) return 1f
        var sumSquares = 0.0
        val histogram = IntArray(1000)
        for (sample in samples) {
            sumSquares += sample.toDouble() * sample
            histogram[min(999, (abs(sample) * 1000).toInt())]++
        }
        val rms = sqrt(sumSquares / samples.size)
        if (rms < SILENCE_RMS) return 1f
        val limit = samples.size * 0.999
        var seen = 0L
        var peak = 1f
        for (bin in histogram.indices) {
            seen += histogram[bin]
            if (seen >= limit) { peak = (bin + 1) / 1000f; break }
        }
        val gain = minOf((TARGET_RMS / rms).toFloat(), PEAK_TARGET / peak, MAX_GAIN)
        return if (gain <= MIN_GAIN) 1f else gain
    }

    /** Returns [samples] unchanged when the level is already fine (never lowers it). */
    fun normalize(samples: FloatArray): FloatArray {
        val gain = gain(samples)
        if (gain == 1f) return samples
        return FloatArray(samples.size) { (samples[it] * gain).coerceIn(-1f, 1f) }
    }
}

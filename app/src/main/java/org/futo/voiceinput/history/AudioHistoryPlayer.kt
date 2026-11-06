package org.futo.voiceinput.history

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream

private const val SAMPLE_RATE = 16000

/**
 * Plays a saved recording (16 kHz mono 16-bit PCM) through the media stream. Returns when playback
 * ends; cancel the calling coroutine to stop early.
 */
suspend fun playAudioHistory(input: InputStream) = withContext(Dispatchers.IO) { input.use { stream ->
    val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT)
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build())
        .setAudioFormat(AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build())
        .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE)) // At least half a second.
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    try {
        check(track.state == AudioTrack.STATE_INITIALIZED) { "Audio output is not available" }
        track.play()
        var frames = 0L
        run {
            // About a quarter of a second, so Stop reacts quickly.
            val buffer = ByteArray(8192)
            var carry = 0
            while (true) {
                ensureActive()
                val read = stream.read(buffer, carry, buffer.size - carry)
                if (read < 0) break
                val available = carry + read
                // AudioTrack takes whole 16-bit frames only.
                val whole = available - available % 2
                var offset = 0
                while (offset < whole) {
                    ensureActive()
                    val written = track.write(buffer, offset, whole - offset)
                    check(written >= 0) { "Audio output failed ($written)" }
                    offset += written
                }
                frames += whole / 2
                carry = available - whole
                if (carry > 0) buffer[0] = buffer[whole]
            }
        }
        // Wait for the buffered tail before stopping; a stopped track reports position 0.
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL < frames &&
            SystemClock.elapsedRealtime() < deadline) {
            delay(50)
        }
        track.stop()
    } finally {
        runCatching { track.pause(); track.flush() }
        track.release()
    }
} }

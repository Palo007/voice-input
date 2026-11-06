package org.futo.voiceinput.parakeet

import kotlinx.coroutines.runBlocking
import org.futo.voiceinput.backend.SpeechBackend
import org.futo.voiceinput.backend.StreamingSpeechBackend
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.futo.voiceinput.recognition.PerformanceClass
import org.futo.voiceinput.recognition.TranscriptionBehavior

class ParakeetBackendTest {
    @Test
    fun transcribesThroughSpeechBackendAndReleasesDecoder() = runBlocking {
        val decoder = FakeParakeetDecoder()
        val backend: SpeechBackend = ParakeetBackend(
            decoderFactory = { decoder },
            decoder = decoder
        )
        val samples = floatArrayOf(0.1f, 0.2f)

        assertEquals("known words", backend.transcribe(samples))
        assertArrayEquals(samples, decoder.samples, 0.0f)
        assertTrue(backend !is StreamingSpeechBackend)

        backend.close()
        assertTrue(decoder.closed)
    }

    @Test
    fun orukeetPackageIncludesPinnedWeightsAndNotices() {
        val model = OrukeetModel.recognitionModel
        assertEquals("orukeet", model.runtimeId)
        assertEquals(486_807_585L, model.transferBytes)
        assertEquals(1_158_427_385L, model.requiredFreeSpaceBytes)
        assertEquals(model.directoryName, model.archiveRoot)
        assertEquals(
            "f9191f30178cc9122ce2f023bf9fefafc822028307b0efa4caff645ba3fe8d0a",
            model.archive?.sha256
        )
        assertEquals(
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx",
                "tokens.txt", "bpe.vocab", "LICENSE-WEIGHTS", "NOTICE.md"),
            model.artifacts.map { it.name }
        )
        assertTrue(model.artifacts.all { it.url == model.archive?.url })
        assertTrue(requireNotNull(model.archive).url.contains("/resolve/${model.version}/"))
        assertTrue(model.source.contains("CC BY-SA 4.0"))
        assertEquals(TranscriptionBehavior.FINAL_ONLY, model.transcription)
    }

    @Test
    fun modelPackageIsPinnedForSherpaAndAttributed() {
        val model = ParakeetModel.recognitionModel

        assertEquals(
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
            model.artifacts.map { it.name }
        )
        assertTrue(model.artifacts.all { it.url.contains("/resolve/${model.version}/") })
        assertEquals("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", model.directoryName)
        assertTrue(model.source.contains("CC BY 4.0"))
        assertEquals(TranscriptionBehavior.FINAL_ONLY, model.transcription)
        assertEquals(PerformanceClass.DEMANDING, model.performanceClass)
    }

    @Test
    fun parakeetUltraPackageIsPinnedSeparatelyAndAttributed() {
        val model = ParakeetUltraModel.recognitionModel

        assertEquals("parakeet_ultra", model.runtimeId)
        assertEquals(
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx",
                "tokens.txt", "LICENSE", "NOTICE"),
            model.artifacts.map { it.name }
        )
        assertEquals(40, model.version.length)
        assertTrue(model.artifacts.all {
            it.url.startsWith("https://huggingface.co/erponce/sherpa-onnx-parakeet-ultra-tdt-0.6b-v3-int8/resolve/${model.version}/${it.name}")
        })
        assertTrue(model.artifacts.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && it.sizeBytes > 0 })
        assertEquals(684_643_416L, model.artifacts.sumOf { it.sizeBytes })
        assertEquals(null, model.archive)
        // Must not share storage with Parakeet V3 or Orukeet: deleting one must not remove another.
        assertTrue(model.directoryName !in setOf(
            ParakeetModel.recognitionModel.directoryName, OrukeetModel.recognitionModel.directoryName))
        assertTrue(model.source.contains("CC BY 4.0"))
        assertTrue(model.source.contains("Moondream"))
        assertEquals(TranscriptionBehavior.FINAL_ONLY, model.transcription)
        assertEquals(PerformanceClass.DEMANDING, model.performanceClass)
    }

    @Test
    fun parakeetSlovakPackageIsPinnedSeparatelyAndAttributed() {
        val model = ParakeetSlovakModel.recognitionModel

        assertEquals("parakeet_sk", model.runtimeId)
        assertEquals(
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "README.md"),
            model.artifacts.map { it.name }
        )
        assertEquals("656db267f4e0d5b1335c78bc6aec1d37ca37c6ec", model.version)
        assertTrue(model.artifacts.all {
            it.url.startsWith("https://huggingface.co/vibeide-dev/parakeet-tdt-0.6b-v3-sk-sherpa-onnx-int8/resolve/${model.version}/${it.name}")
        })
        assertTrue(model.artifacts.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && it.sizeBytes > 0 })
        assertEquals(670_580_036L, model.artifacts.sumOf { it.sizeBytes })
        assertEquals(null, model.archive)
        // Same vocabulary as Parakeet V3.
        assertEquals(
            ParakeetModel.recognitionModel.artifacts.single { it.name == "tokens.txt" }.sha256,
            model.artifacts.single { it.name == "tokens.txt" }.sha256
        )
        // Must not share storage with another Parakeet model: deleting one must not remove another.
        assertTrue(model.directoryName !in setOf(
            ParakeetModel.recognitionModel.directoryName, OrukeetModel.recognitionModel.directoryName,
            ParakeetUltraModel.recognitionModel.directoryName))
        assertTrue(model.source.contains("CC BY 4.0"))
        assertTrue(model.source.contains("KInIT"))
        // The UI must say plainly that it is tuned for Slovak and may be worse in English.
        assertTrue(model.recognitionLanguages.startsWith("Slovak"))
        assertTrue(model.recognitionLanguages.contains("English"))
        assertTrue(model.description.contains("Slovak"))
        assertTrue(model.description.contains("English"))
        assertEquals(TranscriptionBehavior.FINAL_ONLY, model.transcription)
        assertEquals(PerformanceClass.DEMANDING, model.performanceClass)
    }

    @Test
    fun parakeetDerivativesKeptWarmShareOneEngineSlot() {
        assertEquals(setOf("parakeet", "parakeet_ultra", "parakeet_sk"), WARM_PARAKEET_RUNTIMES)
    }
}

private class FakeParakeetDecoder : ParakeetDecoder {
    var samples = floatArrayOf()
    var closed = false

    override fun transcribe(samples: FloatArray): String {
        this.samples = samples
        return "known words"
    }

    override fun close() {
        closed = true
    }
}

package org.futo.voiceinput.parakeet

import org.futo.voiceinput.recognition.PerformanceClass
import org.futo.voiceinput.recognition.RecognitionModel
import org.futo.voiceinput.recognition.RecognitionModelArtifact
import org.futo.voiceinput.recognition.TranscriptionBehavior

/**
 * KInIT's Slovak fine-tune of Parakeet TDT 0.6B V3, loaded through the shared Sherpa-ONNX
 * `nemo_transducer` decoder (128 mel bins, like V3).
 *
 * The weights come from VibeiDE's community Sherpa-ONNX export (standard dynamic int8, the same
 * layout as the twmht V3 export), pinned by revision and SHA-256. The export ships no separate
 * license file, so its README (CC BY 4.0 statement and attribution) is downloaded with the model.
 * See docs/ISSUE-52-slovak-finetune-notes.md.
 */
object ParakeetSlovakModel {
    private const val revision = "656db267f4e0d5b1335c78bc6aec1d37ca37c6ec"
    private const val repository = "vibeide-dev/parakeet-tdt-0.6b-v3-sk-sherpa-onnx-int8"

    val recognitionModel = RecognitionModel(
        id = "parakeet-tdt-0.6b-v3-sk",
        version = revision,
        runtimeId = "parakeet_sk",
        variantId = null,
        directoryName = "parakeet-tdt-0.6b-v3-sk-sherpa-onnx-int8",
        source = "KInIT Parakeet TDT 0.6B V3 Slovak fine-tune (CC BY 4.0), based on NVIDIA Parakeet TDT 0.6B V3 " +
            "(CC BY 4.0), Sherpa-ONNX export by VibeiDE",
        licenseAttribution = "CC BY 4.0",
        displayName = "Parakeet TDT V3 Slovak",
        description = "Turns Slovak speech into text after you stop recording. " +
            "This Parakeet TDT V3 was fine-tuned by KInIT on Slovak speech only. " +
            "Quality in other languages, including English, may be lower than with Parakeet TDT V3. " +
            "It needs substantial memory and processing.",
        transcription = TranscriptionBehavior.FINAL_ONLY,
        recognitionLanguages = "Slovak (other languages, including English, may be less accurate)",
        performanceClass = PerformanceClass.DEMANDING,
        artifacts = listOf(
            artifact("encoder.int8.onnx", 652_282_294, "d487c474a6de1a783c7198303088bcff0b200f31f953fdd5192fffd4a0ce0039"),
            artifact("decoder.int8.onnx", 11_845_275, "3a3dbd37acd24dad2f1a712743dcdcb0d60c013033273847c194d7d0464a6bf5"),
            artifact("joiner.int8.onnx", 6_355_277, "0d999f3ab9c46a64f35ed81eef45086d7bd14f408d73296fa1a424a7fa5ca2e0"),
            artifact("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
            artifact("README.md", 3_251, "b5b99b9adb5b7668fb8c06e9afe854f79467980ef67f21f2b070150e1b86c8c7")
        )
    )

    private fun artifact(name: String, size: Long, hash: String) =
        RecognitionModelArtifact(
            name = name,
            url = "https://huggingface.co/$repository/resolve/$revision/$name?download=true",
            sizeBytes = size,
            sha256 = hash
        )
}

package org.futo.voiceinput.parakeet

import org.futo.voiceinput.recognition.PerformanceClass
import org.futo.voiceinput.recognition.RecognitionModel
import org.futo.voiceinput.recognition.RecognitionModelArtifact
import org.futo.voiceinput.recognition.TranscriptionBehavior

/**
 * Moondream Parakeet Ultra: a post-trained Parakeet TDT 0.6B V3 with the same 25 languages,
 * loaded through the shared Sherpa-ONNX `nemo_transducer` decoder.
 *
 * The weights come from SideKick's community Sherpa-ONNX export (int8, weight-only
 * `MatMulNBits`), pinned by revision and SHA-256. See docs/ISSUE-52-parakeet-ultra-notes.md.
 */
object ParakeetUltraModel {
    private const val revision = "a2d3be97d0b5d3f01a5cf5829d25bcf690aa1be9"
    private const val repository = "erponce/sherpa-onnx-parakeet-ultra-tdt-0.6b-v3-int8"

    val recognitionModel = RecognitionModel(
        id = "parakeet-ultra-tdt-0.6b-v3",
        version = revision,
        runtimeId = "parakeet_ultra",
        variantId = null,
        directoryName = "sherpa-onnx-parakeet-ultra-tdt-0.6b-v3-int8",
        source = "Moondream Parakeet Ultra (CC BY 4.0), based on NVIDIA Parakeet TDT 0.6B V3 (CC BY 4.0), " +
            "Sherpa-ONNX export by SideKick",
        licenseAttribution = "CC BY 4.0",
        displayName = "Parakeet Ultra",
        description = "Turns speech in 25 European languages into text after you stop recording. " +
            "This post-trained Parakeet TDT V3 made fewer errors than V3 in the publisher's FLEURS results for the original weights. It " +
            "detects the spoken language automatically and needs substantial memory and processing.",
        transcription = TranscriptionBehavior.FINAL_ONLY,
        recognitionLanguages = "25 European languages",
        performanceClass = PerformanceClass.DEMANDING,
        artifacts = listOf(
            artifact("encoder.int8.onnx", 630_748_061, "677ec8e965d3319f7094548231a56efe4c08b16f90fddd064db2de277dcb6325"),
            artifact("decoder.int8.onnx", 47_658_545, "bd806df64c71fce9269769d825da80542c2761b946be4fafa30e0dabdb85025a"),
            artifact("joiner.int8.onnx", 6_123_465, "d90a4d2a41a081c6255b19a5b102fd7289feb648374a1e745392380781e1578f"),
            artifact("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
            artifact("LICENSE", 18_657, "9ba9550ad48438d0836ddab3da480b3b69ffa0aac7b7878b5a0039e7ab429411"),
            artifact("NOTICE", 749, "e5a6cb422d0590f1f7a4530c532a2182e0fc28ac412d0e689dd4c2f93851b8d9")
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

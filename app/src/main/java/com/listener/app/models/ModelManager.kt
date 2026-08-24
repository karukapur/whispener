package com.listener.app.models

import java.io.File
import java.security.MessageDigest
import java.nio.file.Files

data class ModelVariant(
    val id: String,
    val approximateMb: Int,
    val speed: String,
    val memory: String,
    val accuracy: String,
    val url: String,
    val sha256: String,
    val version: String,
    val files: List<ModelFile> = emptyList(),
    val archiveFormat: String? = null,
)

data class ModelFile(
    val name: String,
    val url: String,
    val sha256: String = "",
    val bytes: Long = 0L,
)

val ConservativeModels = listOf(
    ModelVariant(
        "tiny", 75, "Fastest", "~250 MB", "Good for initial testing",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny.bin",
        "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
        "whisper.cpp-v1.9.2",
    ),
    ModelVariant(
        "base", 142, "Balanced", "~400 MB", "Better accuracy",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin",
        "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        "whisper.cpp-v1.9.2",
    ),
    ModelVariant(
        "small-q5_1", 182, "Slower", "~700 MB", "Best local accuracy",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small-q5_1.bin",
        "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        "whisper.cpp-v1.9.2",
    ),
)

val SherpaOnnxModels = listOf(
    ModelVariant(
        id = ModelManager.STREAMING_PARAFORMER_BILINGUAL_ID,
        approximateMb = 226,
        speed = "Fast",
        memory = "~450 MB",
        accuracy = "Streaming Mandarin + English, dialect tolerant",
        url = "",
        sha256 = "",
        version = "8e40c43",
        files = listOf(
            ModelFile(
                name = "encoder.int8.onnx",
                url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/encoder.int8.onnx",
                sha256 = "81a70226a8934e6ed92aa1d4fc486b428b5398e2f2619ed4897b7294cab90e9a",
                bytes = 165_000_000L,
            ),
            ModelFile(
                name = "decoder.int8.onnx",
                url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/decoder.int8.onnx",
                sha256 = "f3cca9f77bb9d93c8fcbfb63ae617b6b1ee96818df3aa3b151c40658fe38594f",
                bytes = 71_700_000L,
            ),
            ModelFile(
                name = "tokens.txt",
                url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/tokens.txt",
                bytes = 75_800L,
            ),
        ),
    ),
)

val DownloadableModels = ConservativeModels + SherpaOnnxModels

val LocalModelFallbackOrder = listOf("base", "small-q5_1", "tiny")

fun resolveLocalModelSelection(selectedId: String?, installedIds: Set<String>, legacyInstall: Boolean = false): String? {
    if (selectedId in installedIds) return selectedId
    val order = if (legacyInstall && selectedId == null && "tiny" in installedIds) {
        listOf("tiny", "base", "small-q5_1")
    } else LocalModelFallbackOrder
    return order.firstOrNull(installedIds::contains)
}
data class InstalledModel(val id: String, val version: String, val binary: File, val manifest: File, val sha256: String)

class ModelManager(private val root: File) {
    @Volatile var loadedModelId: String? = null; private set
    fun markLoaded(id: String?) { loadedModelId = id }
    fun verify(file: File, expected: String): Boolean = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(128 * 1024)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)
    }
    fun delete(model: InstalledModel): Long {
        check(loadedModelId != model.id) { "Unload the active model before deletion" }
        val recovered = model.binary.walkTopDown().filter(File::isFile).sumOf(File::length) + model.manifest.length()
        if (model.binary.isDirectory) model.binary.deleteRecursively() else Files.deleteIfExists(model.binary.toPath())
        Files.deleteIfExists(model.manifest.toPath())
        return recovered
    }
    fun paths(id: String): Pair<File, File> = root.resolve("binaries/$id.bin") to root.resolve("manifests/$id.json")
    fun installed(id: String): File? = paths(id).first.takeIf(File::isFile) ?: sherpaModelDir(id).takeIf(::isSherpaModelInstalled)
    fun paraformerDir(): File = root.resolve(STREAMING_PARAFORMER_BILINGUAL_ID)
    fun senseVoiceDir(): File = root.resolve(SENSE_VOICE_ID)
    fun sherpaModelDir(id: String): File = root.resolve(id)
    fun installedSherpaOnnx(): Pair<String, File>? =
        installedSherpaModel(paraformerDir())?.let { STREAMING_PARAFORMER_BILINGUAL_ID to it }
            ?: installedSherpaModel(senseVoiceDir())?.let { SENSE_VOICE_ID to it }

    fun installedSenseVoice(): File? = senseVoiceDir().takeIf {
        it.resolve("model.int8.onnx").isFile && it.resolve("tokens.txt").isFile
    }

    private fun installedSherpaModel(dir: File): File? = dir.takeIf {
        isSherpaModelInstalled(it)
    }

    private fun isSherpaModelInstalled(dir: File): Boolean =
        dir.resolve("tokens.txt").isFile &&
            ((dir.resolve("encoder.int8.onnx").isFile && dir.resolve("decoder.int8.onnx").isFile) || dir.resolve("model.int8.onnx").isFile)

    companion object {
        const val STREAMING_PARAFORMER_BILINGUAL_ID = "sherpa-onnx-streaming-paraformer-bilingual-zh-en"
        const val SENSE_VOICE_ID = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
    }
}

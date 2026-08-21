package com.listener.app.models

import java.io.File
import java.security.MessageDigest

data class ModelVariant(val id: String, val approximateMb: Int, val speed: String, val memory: String, val accuracy: String)
val ConservativeModels = listOf(
    ModelVariant("tiny", 75, "Fastest", "~250 MB", "Basic"),
    ModelVariant("base", 142, "Balanced", "~400 MB", "Better"),
    ModelVariant("small", 466, "Slower; benchmark first", "~1 GB", "Highest offered"),
)
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
        val recovered = model.binary.length() + model.manifest.length()
        model.binary.delete(); model.manifest.delete(); return recovered
    }
    fun paths(id: String): Pair<File, File> = root.resolve("binaries/$id.bin") to root.resolve("manifests/$id.json")
}

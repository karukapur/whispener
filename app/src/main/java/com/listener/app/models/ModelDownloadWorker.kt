package com.listener.app.models

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.listener.app.MainActivity
import com.listener.app.R
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import com.listener.app.data.session.DatabaseProvider
import com.listener.app.data.session.ModelMetadataEntity
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** WorkManager retries interrupted downloads and HTTP Range resumes a retained .part file. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString("url") ?: return Result.failure()
        val destination = File(inputData.getString("destination") ?: return Result.failure())
        val expected = inputData.getString("sha256") ?: return Result.failure()
        val modelId = inputData.getString("modelId") ?: return Result.failure()
        val version = inputData.getString("version") ?: return Result.failure()
        setForeground(downloadForegroundInfo(modelId, null))
        val archiveFormat = inputData.getString("archiveFormat").orEmpty()
        val fileNames = inputData.getStringArray("fileNames").orEmpty()
        val fileUrls = inputData.getStringArray("fileUrls").orEmpty()
        val fileSha256 = inputData.getStringArray("fileSha256").orEmpty()
        val fileBytes = inputData.getLongArray("fileBytes") ?: LongArray(fileNames.size)
        if (archiveFormat == "tar.bz2") {
            return downloadArchive(destination, url, expected, modelId, version)
        }
        if (fileNames.isNotEmpty()) {
            return downloadFileSet(destination, modelId, version, fileNames.toList(), fileUrls.toList(), fileSha256.toList(), fileBytes.toList())
        }
        val partial = File(destination.path + ".part"); partial.parentFile?.mkdirs()
        val modelRoot = destination.parentFile?.parentFile ?: return Result.failure(workDataOf("error" to "invalid destination"))
        val manager = ModelManager(modelRoot)
        return try {
            val alreadyComplete = partial.isFile && manager.verify(partial, expected)
            if (!alreadyComplete) {
                val request = Request.Builder().url(url).apply { if (partial.exists()) header("Range", "bytes=${partial.length()}-") }.build()
                HTTP.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        return if (response.code == 408 || response.code == 429 || response.code >= 500) Result.retry()
                        else Result.failure(workDataOf("error" to "download HTTP ${response.code}"))
                    }
                    val existing = if (response.code == 206) partial.length() else 0L
                    val total = response.body?.contentLength()?.takeIf { it >= 0 }?.plus(existing) ?: -1
                    var downloaded = existing
                    response.body!!.byteStream().use { input -> FileOutputStream(partial, response.code == 206).buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (!isStopped) {
                            val read = input.read(buffer); if (read < 0) break
                            output.write(buffer, 0, read); downloaded += read
                            if (total > 0) publishProgress(modelId, downloaded, total)
                        }
                    } }
                    if (isStopped) return Result.failure(workDataOf("error" to "cancelled"))
                }
            }
            if (!manager.verify(partial, expected)) { partial.delete(); Result.failure(workDataOf("error" to "checksum")) }
            else if (installAtomically(partial, destination)) {
                val manifest = File(modelRoot, "manifests/$modelId.json")
                manifest.parentFile?.mkdirs()
                val manifestPartial = File(manifest.path + ".part")
                manifestPartial.writeText("""{"id":"$modelId","version":"$version","sha256":"$expected","bytes":${destination.length()}}""")
                if (!installAtomically(manifestPartial, manifest)) return Result.failure(workDataOf("error" to "manifest"))
                DatabaseProvider.get(applicationContext).sessions().upsertModel(
                    ModelMetadataEntity(modelId, version, manifest.path, destination.path, expected, destination.length(), System.currentTimeMillis())
                )
                Result.success(workDataOf("destination" to destination.path, "manifest" to manifest.path))
            } else Result.failure(workDataOf("error" to "install"))
        } catch (_: java.io.IOException) { Result.retry() }
    }

    private fun installAtomically(source: File, destination: File): Boolean = runCatching {
        destination.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }.isSuccess

    private suspend fun downloadFileSet(
        destination: File,
        modelId: String,
        version: String,
        fileNames: List<String>,
        fileUrls: List<String>,
        fileSha256: List<String>,
        fileBytes: List<Long>,
    ): Result {
        if (fileNames.size != fileUrls.size || fileNames.size != fileSha256.size || fileNames.size != fileBytes.size) {
            return Result.failure(workDataOf("error" to "invalid file set"))
        }
        val modelRoot = destination.parentFile ?: return Result.failure(workDataOf("error" to "invalid destination"))
        val manager = ModelManager(modelRoot)
        return try {
            val staging = File(destination.path + ".part")
            staging.mkdirs()
            val expectedTotal = fileBytes.sum().takeIf { it > 0L }
            var downloadedTotal = 0L
            fileNames.indices.forEach { index ->
                val target = File(staging, fileNames[index])
                val result = downloadOne(fileUrls[index], target, fileSha256[index]) { downloaded, total ->
                    val discoveredTotal = total.takeIf { it > 0 } ?: downloaded
                    val currentTotal = maxOf(expectedTotal ?: 0L, downloadedTotal + discoveredTotal, 1L)
                    publishProgress(modelId, downloadedTotal + downloaded, currentTotal)
                }
                if (!result) return Result.retry()
                downloadedTotal += target.length()
            }
            fileNames.indices.forEach { index ->
                val expected = fileSha256[index]
                if (expected.isNotBlank() && !manager.verify(File(staging, fileNames[index]), expected)) {
                    staging.deleteRecursively()
                    return Result.failure(workDataOf("error" to "checksum"))
                }
            }
            if (destination.exists()) destination.deleteRecursively()
            if (!installAtomically(staging, destination)) return Result.failure(workDataOf("error" to "install"))
            val manifest = File(modelRoot, "manifests/$modelId.json")
            manifest.parentFile?.mkdirs()
            val manifestPartial = File(manifest.path + ".part")
            val bytes = destination.walkTopDown().filter(File::isFile).sumOf(File::length)
            manifestPartial.writeText("""{"id":"$modelId","version":"$version","bytes":$bytes,"files":${fileNames.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")}}""")
            if (!installAtomically(manifestPartial, manifest)) return Result.failure(workDataOf("error" to "manifest"))
            DatabaseProvider.get(applicationContext).sessions().upsertModel(
                ModelMetadataEntity(modelId, version, manifest.path, destination.path, "", bytes, System.currentTimeMillis())
            )
            Result.success(workDataOf("destination" to destination.path, "manifest" to manifest.path))
        } catch (_: java.io.IOException) {
            Result.retry()
        }
    }

    private suspend fun downloadOne(url: String, destination: File, expected: String, progress: suspend (downloaded: Long, total: Long) -> Unit): Boolean {
        destination.parentFile?.mkdirs()
        val partial = File(destination.path + ".part")
        if (expected.isNotBlank() && destination.isFile) {
            val manager = ModelManager(destination.parentFile?.parentFile ?: destination.parentFile ?: return false)
            if (manager.verify(destination, expected)) return true
        }
        if (expected.isBlank() && destination.isFile && destination.length() > 0L) return true
        val request = Request.Builder().url(url).apply { if (partial.exists()) header("Range", "bytes=${partial.length()}-") }.build()
        HTTP.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) return false
            val existing = if (response.code == 206) partial.length() else 0L
            val total = response.body?.contentLength()?.takeIf { it >= 0 }?.plus(existing) ?: -1
            var downloaded = existing
            response.body!!.byteStream().use { input -> FileOutputStream(partial, response.code == 206).buffered().use { output ->
                val buffer = ByteArray(128 * 1024)
                while (!isStopped) {
                    val read = input.read(buffer); if (read < 0) break
                    output.write(buffer, 0, read); downloaded += read
                    progress(downloaded, total)
                }
            } }
            if (isStopped) return false
        }
        val manager = ModelManager(destination.parentFile?.parentFile ?: destination.parentFile ?: return false)
        if (expected.isNotBlank() && !manager.verify(partial, expected)) {
            partial.delete()
            return false
        }
        return installAtomically(partial, destination)
    }

    private suspend fun downloadArchive(destination: File, url: String, expected: String, modelId: String, version: String): Result {
        val modelRoot = destination.parentFile ?: return Result.failure(workDataOf("error" to "invalid destination"))
        val archive = File(modelRoot, "$modelId.tar.bz2")
        return try {
            val downloaded = downloadOne(url, archive, expected) { done, total ->
                if (total > 0) publishProgress(modelId, done, total)
            }
            if (!downloaded) return Result.retry()
            val staging = File(destination.path + ".part")
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
            publishProgress(modelId, archive.length(), archive.length())
            extractTarBz2(archive, staging, modelId)
            if (!isStreamingParaformerComplete(staging)) {
                staging.deleteRecursively()
                return Result.failure(workDataOf("error" to "archive contents"))
            }
            if (destination.exists()) destination.deleteRecursively()
            if (!installAtomically(staging, destination)) return Result.failure(workDataOf("error" to "install"))
            archive.delete()
            val bytes = destination.walkTopDown().filter(File::isFile).sumOf(File::length)
            val manifest = File(modelRoot, "manifests/$modelId.json")
            manifest.parentFile?.mkdirs()
            val manifestPartial = File(manifest.path + ".part")
            manifestPartial.writeText("""{"id":"$modelId","version":"$version","bytes":$bytes,"archive":"tar.bz2"}""")
            if (!installAtomically(manifestPartial, manifest)) return Result.failure(workDataOf("error" to "manifest"))
            DatabaseProvider.get(applicationContext).sessions().upsertModel(
                ModelMetadataEntity(modelId, version, manifest.path, destination.path, expected, bytes, System.currentTimeMillis())
            )
            Result.success(workDataOf("destination" to destination.path, "manifest" to manifest.path))
        } catch (_: java.io.IOException) {
            Result.retry()
        }
    }

    private fun extractTarBz2(archive: File, destination: File, rootName: String) {
        archive.inputStream().buffered().use { fileInput ->
            BZip2CompressorInputStream(fileInput).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        if (entry.isDirectory) continue
                        val relative = entry.name.removePrefix("$rootName/")
                        if (relative != "encoder.int8.onnx" && relative != "decoder.int8.onnx" && relative != "tokens.txt") continue
                        val output = destination.resolve(relative)
                        output.parentFile?.mkdirs()
                        output.outputStream().buffered().use { tar.copyTo(it) }
                    }
                }
            }
        }
    }

    private fun isStreamingParaformerComplete(dir: File): Boolean =
        dir.resolve("encoder.int8.onnx").isFile && dir.resolve("decoder.int8.onnx").isFile && dir.resolve("tokens.txt").isFile

    private suspend fun publishProgress(modelId: String, downloaded: Long, total: Long) {
        setProgress(workDataOf("downloaded" to downloaded, "total" to total))
        setForeground(downloadForegroundInfo(modelId, downloaded.toFloat() / total))
    }

    private fun downloadForegroundInfo(modelId: String, progress: Float?): ForegroundInfo {
        createChannel()
        val openIntent = PendingIntent.getActivity(
            applicationContext,
            31,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val percent = progress?.let { (it.coerceIn(0f, 1f) * 100).toInt() }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_models)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(if (percent == null) "Preparing model download" else "Downloading model: $percent%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setProgress(100, percent ?: 0, percent == null)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private val HTTP = OkHttpClient()
        private const val CHANNEL = "model-downloads"
        private const val NOTIFICATION_ID = 19
    }
}

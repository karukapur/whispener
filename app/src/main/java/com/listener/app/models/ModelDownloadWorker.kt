package com.listener.app.models

import android.content.Context
import androidx.work.*
import okhttp3.*
import java.io.File
import java.io.FileOutputStream

/** WorkManager retries interrupted downloads and HTTP Range resumes a retained .part file. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString("url") ?: return Result.failure()
        val destination = File(inputData.getString("destination") ?: return Result.failure())
        val expected = inputData.getString("sha256") ?: return Result.failure()
        val partial = File(destination.path + ".part"); partial.parentFile?.mkdirs()
        val request = Request.Builder().url(url).apply { if (partial.exists()) header("Range", "bytes=${partial.length()}-") }.build()
        return try {
            OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) return Result.retry()
                val total = response.body?.contentLength()?.plus(partial.length()) ?: -1
                response.body!!.byteStream().use { input -> FileOutputStream(partial, response.code == 206).buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (!isStopped) { val read = input.read(buffer); if (read < 0) break; output.write(buffer, 0, read) }
                } }
                if (isStopped) return Result.failure(workDataOf("error" to "cancelled"))
                if (total > 0) setProgress(workDataOf("downloaded" to partial.length(), "total" to total))
            }
            val manager = ModelManager(destination.parentFile.parentFile)
            if (!manager.verify(partial, expected)) { partial.delete(); Result.failure(workDataOf("error" to "checksum")) }
            else { partial.renameTo(destination); Result.success() }
        } catch (_: java.io.IOException) { Result.retry() }
    }
}

package com.listener.app.models

import android.content.Context
import androidx.work.*
import com.listener.app.data.session.SessionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

data class ModelDownloadState(
    val workId: UUID? = null,
    val modelId: String? = null,
    val progress: Float? = null,
    val running: Boolean = false,
    val error: String? = null,
)

class ModelRepository(
    context: Context,
    private val dao: SessionDao,
) {
    private val appContext = context.applicationContext
    private val work = WorkManager.getInstance(appContext)
    private val root = File(appContext.filesDir, "models")
    val manager = ModelManager(root)

    fun installed(id: String): File? = manager.installed(id)
    fun installedModels() = dao.models()

    fun download(model: ModelVariant): UUID {
        val destination = if (model.isDirectoryModel) manager.sherpaModelDir(model.id) else manager.paths(model.id).first
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(
                "url" to model.url,
                "destination" to destination.path,
                "sha256" to model.sha256,
                "modelId" to model.id,
                "version" to model.version,
                "fileNames" to model.files.map { it.name }.toTypedArray(),
                "fileUrls" to model.files.map { it.url }.toTypedArray(),
                "fileSha256" to model.files.map { it.sha256 }.toTypedArray(),
                "fileBytes" to model.files.map { it.bytes }.toLongArray(),
                "archiveFormat" to (model.archiveFormat ?: ""),
            ))
            .build()
        work.enqueueUniqueWork(uniqueName(model.id), ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    fun observe(id: UUID, model: ModelVariant): Flow<ModelDownloadState> = work.getWorkInfoByIdFlow(id).map { info ->
        if (info == null) ModelDownloadState() else info.toDownloadState(model)
    }

    fun observe(model: ModelVariant): Flow<ModelDownloadState> = work.getWorkInfosForUniqueWorkFlow(uniqueName(model.id)).map { infos ->
        val info = infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
        if (info == null) ModelDownloadState() else info.toDownloadState(model)
    }

    private fun WorkInfo.toDownloadState(model: ModelVariant): ModelDownloadState {
        val total = progress.getLong("total", 0)
        val downloaded = progress.getLong("downloaded", 0)
        return ModelDownloadState(
            workId = id,
            modelId = model.id,
            progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null,
            running = !state.isFinished,
            error = if (state == WorkInfo.State.FAILED) outputData.getString("error") ?: "Download failed" else null,
        )
    }

    private fun uniqueName(id: String) = "local-model-$id"

    fun cancel(id: UUID) = work.cancelWorkById(id)

    suspend fun delete(model: ModelVariant): Long {
        val (defaultBinary, manifest) = manager.paths(model.id)
        val binary = if (model.isDirectoryModel) manager.sherpaModelDir(model.id) else defaultBinary
        val recovered = manager.delete(InstalledModel(model.id, model.version, binary, manifest, model.sha256))
        dao.deleteModel(model.id)
        return recovered
    }
}

private val ModelVariant.isDirectoryModel: Boolean
    get() = files.isNotEmpty() || archiveFormat != null

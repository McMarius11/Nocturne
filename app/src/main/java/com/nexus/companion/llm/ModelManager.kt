package com.nexus.companion.llm

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class ModelManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    /** Download progress from the ForegroundService — survives screen-off. */
    val downloadProgress: StateFlow<DownloadState>
        get() = DownloadService.downloadProgress

    fun getModelPath(model: ModelInfo): File = File(modelsDir, model.fileName)

    fun isModelDownloaded(model: ModelInfo): Boolean {
        val file = getModelPath(model)
        // Check file exists and is at least 100MB (to catch corrupt/empty files)
        return file.exists() && file.length() > 100_000_000L
    }

    fun getDownloadedModels(): List<ModelInfo> =
        ModelInfo.ALL_MODELS.filter { isModelDownloaded(it) }

    fun hasEnoughStorage(model: ModelInfo): Boolean {
        val stat = StatFs(modelsDir.absolutePath)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        val requiredBytes = model.sizeBytes + 500_000_000L
        return availableBytes >= requiredBytes
    }

    fun getAvailableStorageGb(): Float {
        val stat = StatFs(modelsDir.absolutePath)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        return availableBytes / 1_000_000_000f
    }

    /**
     * Start a model download via ForegroundService.
     * The service acquires a WakeLock so downloads survive screen-off.
     * Returns false immediately if storage is insufficient.
     * Download progress is observed via [downloadProgress].
     */
    fun startDownload(model: ModelInfo): Boolean {
        if (isModelDownloaded(model)) return true

        if (!hasEnoughStorage(model)) {
            val available = String.format("%.1f", getAvailableStorageGb())
            DownloadService._downloadProgress.value = DownloadState.Error(
                "Nicht genug Speicher: ${available} GB frei, ${model.sizeGb} GB benötigt"
            )
            return false
        }

        DownloadService.startDownload(context, model)
        return true
    }

    fun deleteModel(model: ModelInfo) {
        getModelPath(model).delete()
        File(modelsDir, "${model.fileName}.tmp").delete()
    }

    fun cancelDownload() {
        DownloadService.cancelDownload(context)
    }

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(val modelId: String, val progress: Float) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }
}

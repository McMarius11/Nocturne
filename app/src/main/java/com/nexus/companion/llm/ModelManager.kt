package com.nexus.companion.llm

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class ModelManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    val downloadProgress: StateFlow<DownloadState>
        get() = DownloadService.downloadProgress

    fun getModelPath(model: ModelInfo): File = File(modelsDir, model.fileName)

    fun isModelDownloaded(model: ModelInfo): Boolean {
        val file = getModelPath(model)
        return file.exists() && file.length() > 100_000_000L
    }

    /** Check actual file size vs expected — returns ratio (1.0 = exact match) */
    fun getDownloadIntegrity(model: ModelInfo): Float {
        val file = getModelPath(model)
        if (!file.exists()) return 0f
        return file.length().toFloat() / model.sizeBytes.toFloat()
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

    /** Get available RAM in GB */
    fun getAvailableRamGb(): Float {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.availMem / 1_000_000_000f
    }

    /** Get total device RAM in GB */
    fun getTotalRamGb(): Float {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.totalMem / 1_000_000_000f
    }

    /** Estimate required RAM for a model (model size + ~2 GB for KV cache + overhead) */
    fun estimateRequiredRamGb(model: ModelInfo): Float {
        return model.sizeGb + 2.0f  // model + KV cache + Android overhead
    }

    /** Check if device has enough RAM to load this model */
    fun hasEnoughRam(model: ModelInfo): Boolean {
        return getAvailableRamGb() >= estimateRequiredRamGb(model)
    }

    /** Get total size of all downloaded models in GB */
    fun getUsedStorageGb(): Float {
        val totalBytes = ModelInfo.ALL_MODELS
            .map { getModelPath(it) }
            .filter { it.exists() }
            .sumOf { it.length() }
        return totalBytes / 1_000_000_000f
    }

    fun startDownload(model: ModelInfo): Boolean {
        if (isModelDownloaded(model)) return true

        if (!hasEnoughStorage(model)) {
            val available = String.format("%.1f", getAvailableStorageGb())
            DownloadService._downloadProgress.value = DownloadState.Error(
                "Nicht genug Speicher: ${available} GB frei, ${model.sizeGb} GB benötigt"
            )
            return false
        }

        // Set Downloading state BEFORE starting service to avoid race condition:
        // Without this, loadModel()'s flow.first{Idle||Error} returns immediately
        // because the service hasn't started yet and state is still Idle.
        DownloadService._downloadProgress.value = DownloadState.Downloading(model.id, 0f)

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

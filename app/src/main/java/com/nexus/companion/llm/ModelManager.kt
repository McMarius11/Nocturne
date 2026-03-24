package com.nexus.companion.llm

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    private val _downloadProgress = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadProgress: StateFlow<DownloadState> = _downloadProgress

    fun getModelPath(model: ModelInfo): File = File(modelsDir, model.fileName)

    fun isModelDownloaded(model: ModelInfo): Boolean {
        val file = getModelPath(model)
        // Check file exists and is at least 90% of expected size (to catch corrupt partial downloads)
        return file.exists() && file.length() > (model.sizeBytes * 0.9)
    }

    fun getDownloadedModels(): List<ModelInfo> =
        ModelInfo.ALL_MODELS.filter { isModelDownloaded(it) }

    /**
     * Check if device has enough free storage for the model.
     * Requires model size + 500MB buffer for temp file and system.
     */
    fun hasEnoughStorage(model: ModelInfo): Boolean {
        val stat = StatFs(modelsDir.absolutePath)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        val requiredBytes = model.sizeBytes + 500_000_000L // 500MB buffer
        return availableBytes >= requiredBytes
    }

    fun getAvailableStorageGb(): Float {
        val stat = StatFs(modelsDir.absolutePath)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        return availableBytes / 1_000_000_000f
    }

    suspend fun downloadModel(model: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        val targetFile = getModelPath(model)
        if (isModelDownloaded(model)) return@withContext true

        // Check storage before downloading
        if (!hasEnoughStorage(model)) {
            val available = String.format("%.1f", getAvailableStorageGb())
            _downloadProgress.value = DownloadState.Error(
                "Nicht genug Speicher: ${available} GB frei, ${model.sizeGb} GB benötigt"
            )
            return@withContext false
        }

        // Clean up any partial downloads
        val tempFile = File(modelsDir, "${model.fileName}.tmp")
        tempFile.delete()

        // Retry up to 3 times
        var lastError: String? = null
        for (attempt in 1..3) {
            try {
                _downloadProgress.value = DownloadState.Downloading(model.id, 0f)

                val request = Request.Builder()
                    .url(model.downloadUrl)
                    .header("User-Agent", "NexusCompanion/1.0")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    lastError = "HTTP ${response.code}: ${response.message}"
                    response.close()
                    if (attempt < 3) {
                        delay(attempt * 2000L)
                        continue
                    }
                    _downloadProgress.value = DownloadState.Error("Download fehlgeschlagen: $lastError")
                    return@withContext false
                }

                val body = response.body ?: run {
                    lastError = "Leere Antwort vom Server"
                    if (attempt < 3) {
                        delay(attempt * 2000L)
                        continue
                    }
                    _downloadProgress.value = DownloadState.Error(lastError!!)
                    return@withContext false
                }

                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(32768) // 32KB buffer for better throughput
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                _downloadProgress.value = DownloadState.Downloading(
                                    model.id,
                                    downloadedBytes.toFloat() / totalBytes
                                )
                            }
                        }
                    }
                }

                // Verify downloaded size
                if (totalBytes > 0 && tempFile.length() < totalBytes * 0.99) {
                    tempFile.delete()
                    lastError = "Download unvollständig (${tempFile.length()}/$totalBytes Bytes)"
                    if (attempt < 3) {
                        delay(attempt * 2000L)
                        continue
                    }
                    _downloadProgress.value = DownloadState.Error(lastError!!)
                    return@withContext false
                }

                // Atomic rename
                targetFile.delete()
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    tempFile.delete()
                    _downloadProgress.value = DownloadState.Error("Datei konnte nicht gespeichert werden")
                    return@withContext false
                }

                _downloadProgress.value = DownloadState.Idle
                return@withContext true

            } catch (e: Exception) {
                tempFile.delete()
                lastError = e.message ?: "Unbekannter Fehler"
                if (attempt < 3) {
                    delay(attempt * 2000L)
                    continue
                }
            }
        }

        _downloadProgress.value = DownloadState.Error("Download fehlgeschlagen nach 3 Versuchen: $lastError")
        false
    }

    fun deleteModel(model: ModelInfo) {
        getModelPath(model).delete()
        File(modelsDir, "${model.fileName}.tmp").delete()
    }

    fun cancelDownload() {
        // OkHttp calls can be cancelled by the coroutine cancellation
        _downloadProgress.value = DownloadState.Idle
    }

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(val modelId: String, val progress: Float) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }
}

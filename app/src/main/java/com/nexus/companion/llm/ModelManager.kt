package com.nexus.companion.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
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
        .build()

    private val modelsDir: File
        get() = File(context.cacheDir, "models").also { it.mkdirs() }

    private val _downloadProgress = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadProgress: StateFlow<DownloadState> = _downloadProgress

    fun getModelPath(model: ModelInfo): File = File(modelsDir, model.fileName)

    fun isModelDownloaded(model: ModelInfo): Boolean = getModelPath(model).exists()

    fun getDownloadedModels(): List<ModelInfo> =
        ModelInfo.ALL_MODELS.filter { isModelDownloaded(it) }

    suspend fun downloadModel(model: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        val targetFile = getModelPath(model)
        if (targetFile.exists()) return@withContext true

        val tempFile = File(modelsDir, "${model.fileName}.tmp")
        try {
            _downloadProgress.value = DownloadState.Downloading(model.id, 0f)

            val request = Request.Builder()
                .url(model.downloadUrl)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                _downloadProgress.value = DownloadState.Error("Download fehlgeschlagen: ${response.code}")
                return@withContext false
            }

            val body = response.body ?: run {
                _downloadProgress.value = DownloadState.Error("Leere Antwort")
                return@withContext false
            }

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
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

            tempFile.renameTo(targetFile)
            _downloadProgress.value = DownloadState.Idle
            true
        } catch (e: Exception) {
            tempFile.delete()
            _downloadProgress.value = DownloadState.Error(e.message ?: "Unbekannter Fehler")
            false
        }
    }

    fun deleteModel(model: ModelInfo) {
        getModelPath(model).delete()
    }

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(val modelId: String, val progress: Float) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }
}

package com.nexus.companion.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nexus.companion.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        const val CHANNEL_ID = "model_download"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.nexus.companion.DOWNLOAD_START"
        const val ACTION_CANCEL = "com.nexus.companion.DOWNLOAD_CANCEL"
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_MODEL_FILE = "model_file"
        const val EXTRA_MODEL_URL = "model_url"
        const val EXTRA_MODEL_SIZE = "model_size"
        const val EXTRA_IS_TTS_ARCHIVE = "is_tts_archive"

        // LLM download state
        internal val _downloadProgress = MutableStateFlow<ModelManager.DownloadState>(ModelManager.DownloadState.Idle)
        val downloadProgress: StateFlow<ModelManager.DownloadState> = _downloadProgress

        // TTS download state (separate so UI doesn't mix them up)
        internal val _ttsDownloadProgress = MutableStateFlow<ModelManager.DownloadState>(ModelManager.DownloadState.Idle)
        val ttsDownloadProgress: StateFlow<ModelManager.DownloadState> = _ttsDownloadProgress

        fun startDownload(context: Context, model: ModelInfo) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_ID, model.id)
                putExtra(EXTRA_MODEL_NAME, model.displayName)
                putExtra(EXTRA_MODEL_FILE, model.fileName)
                putExtra(EXTRA_MODEL_URL, model.downloadUrl)
                putExtra(EXTRA_MODEL_SIZE, model.sizeBytes)
            }
            context.startForegroundService(intent)
        }

        /** Start a TTS model download (tar.bz2 archive → extract to tts-models/) */
        fun startTtsDownload(context: Context, model: com.nexus.companion.tts.TtsModelInfo) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_ID, model.id)
                putExtra(EXTRA_MODEL_NAME, model.displayName)
                putExtra(EXTRA_MODEL_FILE, model.fileName)
                putExtra(EXTRA_MODEL_URL, model.downloadUrl)
                putExtra(EXTRA_MODEL_SIZE, model.sizeBytes)
                putExtra(EXTRA_IS_TTS_ARCHIVE, true)
            }
            context.startForegroundService(intent)
        }

        fun cancelDownload(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var isTtsDownload = false // tracks which state flow to update

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    private val modelsDir: File
        get() = File(filesDir, "models").also { it.mkdirs() }

    private val ttsModelsDir: File
        get() = File(filesDir, "tts-models").also { it.mkdirs() }

    private var isTtsArchiveDownload = false

    /** Update the correct download progress flow based on download type */
    private fun setProgress(state: ModelManager.DownloadState) {
        if (isTtsDownload) {
            _ttsDownloadProgress.value = state
        } else {
            _downloadProgress.value = state
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                // Try to find in known LLM models first, then reconstruct from extras
                val model = modelId?.let { id ->
                    ModelInfo.findById(id) ?: run {
                        // Reconstruct from intent extras (for TTS models etc.)
                        val name = intent.getStringExtra(EXTRA_MODEL_NAME) ?: return@run null
                        val file = intent.getStringExtra(EXTRA_MODEL_FILE) ?: return@run null
                        val url = intent.getStringExtra(EXTRA_MODEL_URL) ?: return@run null
                        val size = intent.getLongExtra(EXTRA_MODEL_SIZE, 0L)
                        ModelInfo(
                            id = id, displayName = name, fileName = file,
                            downloadUrl = url, sizeGb = size / 1_000_000_000f,
                            sizeBytes = size, batteryPerHour = 0, description = ""
                        )
                    }
                }
                if (model != null) {
                    isTtsArchiveDownload = intent.getBooleanExtra(EXTRA_IS_TTS_ARCHIVE, false)
                    isTtsDownload = isTtsArchiveDownload || ModelInfo.findById(model.id) == null
                    startForeground(NOTIFICATION_ID, buildNotification("Vorbereitung...", 0))
                    startModelDownload(model)
                } else {
                    Log.e(TAG, "Invalid model ID: $modelId")
                    stopSelf()
                }
            }
            ACTION_CANCEL -> {
                cancelCurrentDownload()
            }
        }
        return START_NOT_STICKY
    }

    private fun startModelDownload(model: ModelInfo) {
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            acquireWakeLock()
            try {
                val success = performDownload(model)
                if (success && isTtsArchiveDownload) {
                    // Extract tar.bz2 archive to tts-models/<model-id>/
                    updateNotification("${model.displayName} wird entpackt...", 99)
                    val archiveFile = File(modelsDir, model.fileName)
                    val extractDir = File(ttsModelsDir, model.id)
                    val extracted = extractTarBz2(archiveFile, extractDir)
                    archiveFile.delete() // Remove archive after extraction
                    if (extracted) {
                        // Copy espeak-ng-data to shared location if found
                        copyEspeakDataIfPresent(extractDir)
                        updateNotification("${model.displayName} bereit", 100)
                        DebugLog.tts("TTS model extracted: ${model.id} → ${extractDir.absolutePath}")
                    } else {
                        setProgress(ModelManager.DownloadState.Error("Entpacken fehlgeschlagen"))
                        updateNotification("Entpacken fehlgeschlagen", 0)
                    }
                    setProgress(ModelManager.DownloadState.Idle)
                } else if (success) {
                    updateNotification("${model.displayName} heruntergeladen", 100)
                    setProgress(ModelManager.DownloadState.Idle)
                }
            } finally {
                releaseWakeLock()
                // Small delay so user can see final notification/error
                delay(2000)
                // Always reset to Idle so UI can refresh and next download can start
                setProgress(ModelManager.DownloadState.Idle)
                stopSelf()
            }
        }
    }

    private suspend fun performDownload(model: ModelInfo): Boolean {
        val targetFile = File(modelsDir, model.fileName)

        // Already downloaded (99% threshold — consistent with verification below)
        if (targetFile.exists() && targetFile.length() >= (model.sizeBytes * 0.99)) {
            return true
        }

        // Check network connectivity (gracefully skip if permission missing)
        try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            if (cm?.activeNetwork == null) {
                setProgress(ModelManager.DownloadState.Error("Keine Internetverbindung"))
                updateNotification("Keine Internetverbindung", 0)
                return false
            }
        } catch (e: SecurityException) {
            // ACCESS_NETWORK_STATE not granted — skip check, let OkHttp handle it
            Log.w(TAG, "Cannot check network state: ${e.message}")
        }

        val tempFile = File(modelsDir, "${model.fileName}.tmp")
        tempFile.delete()

        var lastError: String? = null
        for (attempt in 1..3) {
            try {
                setProgress(ModelManager.DownloadState.Downloading(model.id, 0f))
                updateNotification("${model.displayName} wird heruntergeladen...", 0)

                val request = Request.Builder()
                    .url(model.downloadUrl)
                    .header("User-Agent", "NexusCompanion/1.0")
                    .build()

                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }

                if (!response.isSuccessful) {
                    lastError = "HTTP ${response.code}: ${response.message}"
                    response.close()

                    // Don't retry client errors (404, 403, etc.) — only retry server/network errors
                    if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                        setProgress(ModelManager.DownloadState.Error("Download fehlgeschlagen: $lastError"))
                        updateNotification("Download fehlgeschlagen", 0)
                        return false
                    }
                    if (attempt < 3) {
                        delay(attempt * 2000L)
                        continue
                    }
                    setProgress(ModelManager.DownloadState.Error("Download fehlgeschlagen: $lastError"))
                    updateNotification("Download fehlgeschlagen", 0)
                    return false
                }

                val body = response.body
                if (body == null) {
                    response.close()
                    lastError = "Leere Antwort vom Server"
                    if (attempt < 3) { delay(attempt * 2000L); continue }
                    setProgress(ModelManager.DownloadState.Error(lastError!!))
                    return false
                }

                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                var lastNotificationUpdate = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(32768)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val progress = downloadedBytes.toFloat() / totalBytes
                                setProgress(ModelManager.DownloadState.Downloading(model.id, progress))
                                val now = System.currentTimeMillis()
                                if (now - lastNotificationUpdate > 500) {
                                    lastNotificationUpdate = now
                                    val percent = (progress * 100).toInt()
                                    val downloadedMb = downloadedBytes / 1_000_000
                                    val totalMb = totalBytes / 1_000_000
                                    updateNotification(
                                        "${model.displayName}: ${downloadedMb}/${totalMb} MB",
                                        percent
                                    )
                                }
                            }
                        }
                    }
                }

                // Verify download completeness
                if (totalBytes > 0 && tempFile.length() < totalBytes * 0.99) {
                    tempFile.delete()
                    lastError = "Download unvollständig (${tempFile.length()}/$totalBytes Bytes)"
                    if (attempt < 3) { delay(attempt * 2000L); continue }
                    setProgress(ModelManager.DownloadState.Error(lastError!!))
                    updateNotification("Download unvollständig", 0)
                    return false
                }

                // Atomic rename
                targetFile.delete()
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    tempFile.delete()
                    setProgress(ModelManager.DownloadState.Error("Datei konnte nicht gespeichert werden"))
                    return false
                }
                return true

            } catch (e: java.io.IOException) {
                tempFile.delete()
                lastError = "Netzwerkfehler: ${e.message}"
                Log.w(TAG, "Download attempt $attempt failed (IO)", e)
                if (attempt < 3) { delay(attempt * 2000L) }
            } catch (e: Exception) {
                tempFile.delete()
                lastError = e.message ?: "Unbekannter Fehler"
                Log.w(TAG, "Download attempt $attempt failed", e)
                if (attempt < 3) { delay(attempt * 2000L) }
            }
        }

        setProgress(ModelManager.DownloadState.Error("Download fehlgeschlagen nach 3 Versuchen: $lastError"))
        updateNotification("Download fehlgeschlagen", 0)
        return false
    }

    /**
     * Extract a tar.bz2 archive to the target directory.
     * Strips the top-level directory from the archive (e.g., kokoro-en-v0_19/model.onnx → model.onnx).
     */
    private fun extractTarBz2(archiveFile: File, targetDir: File): Boolean {
        return try {
            targetDir.mkdirs()
            FileInputStream(archiveFile).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    BZip2CompressorInputStream(bis).use { bzis ->
                        TarArchiveInputStream(bzis).use { tar ->
                            var entry = tar.nextEntry
                            val topLevelDir = entry?.name?.split("/")?.firstOrNull() ?: ""

                            while (entry != null) {
                                var entryName = entry.name
                                if (topLevelDir.isNotEmpty() && entryName.startsWith("$topLevelDir/")) {
                                    entryName = entryName.removePrefix("$topLevelDir/")
                                }

                                if (entryName.isBlank()) {
                                    entry = tar.nextEntry
                                    continue
                                }

                                val outFile = File(targetDir, entryName)
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { fos ->
                                        tar.copyTo(fos)
                                    }
                                }
                                entry = tar.nextEntry
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "Extracted ${archiveFile.name} → ${targetDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract tar.bz2", e)
            DebugLog.tts("Extract failed: ${e.message}")
            false
        }
    }

    /**
     * If the extracted TTS model contains espeak-ng-data, copy it to the shared location.
     * sherpa-onnx needs this for phoneme processing.
     */
    private fun copyEspeakDataIfPresent(modelDir: File) {
        val espeakDir = modelDir.listFiles()?.find { it.isDirectory && it.name == "espeak-ng-data" }
            ?: return

        val sharedEspeakDir = File(filesDir, "espeak-ng-data")
        if (sharedEspeakDir.exists() && sharedEspeakDir.listFiles()?.isNotEmpty() == true) {
            Log.d(TAG, "espeak-ng-data already exists at shared location")
            return
        }

        try {
            espeakDir.copyRecursively(sharedEspeakDir, overwrite = true)
            Log.i(TAG, "Copied espeak-ng-data to ${sharedEspeakDir.absolutePath}")
            DebugLog.tts("espeak-ng-data installed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy espeak-ng-data", e)
        }
    }

    private fun cancelCurrentDownload() {
        downloadJob?.cancel()
        setProgress(ModelManager.DownloadState.Idle)
        releaseWakeLock()
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NexusCompanion::ModelDownload"
        ).apply {
            // Max 2 hours to prevent battery drain if something goes wrong
            acquire(2 * 60 * 60 * 1000L)
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Modell-Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Zeigt den Fortschritt beim Herunterladen von KI-Modellen"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = android.app.PendingIntent.getService(
            this, 0, cancelIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Nexus")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_delete, "Abbrechen", cancelPendingIntent)

        if (progress in 1..99) {
            builder.setProgress(100, progress, false)
        } else if (progress == 0) {
            builder.setProgress(100, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
    }
}

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
import java.io.File
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

        // Shared download state accessible from ModelManager
        internal val _downloadProgress = MutableStateFlow<ModelManager.DownloadState>(ModelManager.DownloadState.Idle)
        val downloadProgress: StateFlow<ModelManager.DownloadState> = _downloadProgress

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    private val modelsDir: File
        get() = File(filesDir, "models").also { it.mkdirs() }

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
                if (success) {
                    updateNotification("${model.displayName} heruntergeladen", 100)
                    _downloadProgress.value = ModelManager.DownloadState.Idle
                }
            } finally {
                releaseWakeLock()
                // Small delay so user can see final notification
                delay(1500)
                stopSelf()
            }
        }
    }

    private suspend fun performDownload(model: ModelInfo): Boolean {
        val targetFile = File(modelsDir, model.fileName)

        // Already downloaded
        if (targetFile.exists() && targetFile.length() > (model.sizeBytes * 0.9)) {
            return true
        }

        val tempFile = File(modelsDir, "${model.fileName}.tmp")
        tempFile.delete()

        var lastError: String? = null
        for (attempt in 1..3) {
            var shouldRetry = false
            try {
                _downloadProgress.value = ModelManager.DownloadState.Downloading(model.id, 0f)
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
                    if (attempt < 3) {
                        delay(attempt * 2000L)
                        shouldRetry = true
                    } else {
                        _downloadProgress.value = ModelManager.DownloadState.Error("Download fehlgeschlagen: $lastError")
                        updateNotification("Download fehlgeschlagen", 0)
                        return false
                    }
                }

                if (shouldRetry) { /* next attempt */ }
                else {
                    val body = response.body
                    if (body == null) {
                        lastError = "Leere Antwort vom Server"
                        if (attempt < 3) {
                            delay(attempt * 2000L)
                            shouldRetry = true
                        } else {
                            _downloadProgress.value = ModelManager.DownloadState.Error(lastError!!)
                            updateNotification("Download fehlgeschlagen", 0)
                            return false
                        }
                    }

                    if (!shouldRetry) {
                        val totalBytes = body!!.contentLength()
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
                                        _downloadProgress.value = ModelManager.DownloadState.Downloading(
                                            model.id, progress
                                        )
                                        // Throttle notification updates to every 500ms
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

                        // Verify size
                        if (totalBytes > 0 && tempFile.length() < totalBytes * 0.99) {
                            tempFile.delete()
                            lastError = "Download unvollständig (${tempFile.length()}/$totalBytes Bytes)"
                            if (attempt < 3) {
                                delay(attempt * 2000L)
                                shouldRetry = true
                            } else {
                                _downloadProgress.value = ModelManager.DownloadState.Error(lastError!!)
                                updateNotification("Download unvollständig", 0)
                                return false
                            }
                        }

                        if (!shouldRetry) {
                            // Atomic rename
                            targetFile.delete()
                            val renamed = tempFile.renameTo(targetFile)
                            if (!renamed) {
                                tempFile.delete()
                                _downloadProgress.value = ModelManager.DownloadState.Error("Datei konnte nicht gespeichert werden")
                                return false
                            }
                            return true
                        }
                    }
                }

            } catch (e: Exception) {
                tempFile.delete()
                lastError = e.message ?: "Unbekannter Fehler"
                if (attempt < 3) {
                    delay(attempt * 2000L)
                }
            }
        }

        _downloadProgress.value = ModelManager.DownloadState.Error("Download fehlgeschlagen nach 3 Versuchen: $lastError")
        updateNotification("Download fehlgeschlagen", 0)
        return false
    }

    private fun cancelCurrentDownload() {
        downloadJob?.cancel()
        _downloadProgress.value = ModelManager.DownloadState.Idle
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

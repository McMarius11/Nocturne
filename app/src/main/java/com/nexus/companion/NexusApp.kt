package com.nexus.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.nexus.companion.llm.DownloadService

class NexusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            DownloadService.CHANNEL_ID,
            "Modell-Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Zeigt den Fortschritt beim Herunterladen von KI-Modellen"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}

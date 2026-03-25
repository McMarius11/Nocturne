package com.nexus.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.nexus.companion.llm.DownloadService
import com.nexus.companion.phone.PhoneCallService

class NexusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                DownloadService.CHANNEL_ID,
                "Modell-Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt den Fortschritt beim Herunterladen von KI-Modellen"
                setShowBadge(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                PhoneCallService.CHANNEL_ID,
                "Sprachmodus",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Aktiv während des Sprachmodus"
                setShowBadge(false)
            }
        )
    }
}

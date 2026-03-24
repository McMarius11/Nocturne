package com.nexus.companion

import java.util.Locale

/**
 * Voice profiles for TTS output. Each profile has different language capabilities.
 * The LLM and memory system are always bilingual (DE + EN).
 */
data class VoiceProfile(
    val id: String,
    val displayName: String,
    val languages: List<String>,
    val sttLocale: String,
    val ttsLocale: Locale,
    val isNeural: Boolean,
    val description: String,
    val warning: String? = null
) {
    companion object {
        val ANDROID_DE = VoiceProfile(
            id = "android-de",
            displayName = "Android Deutsch",
            languages = listOf("de", "en"),
            sttLocale = "de-DE",
            ttsLocale = Locale.GERMAN,
            isNeural = false,
            description = "System-Stimme — Deutsch & Englisch"
        )

        val ANDROID_EN = VoiceProfile(
            id = "android-en",
            displayName = "Android English",
            languages = listOf("en", "de"),
            sttLocale = "en-US",
            ttsLocale = Locale.US,
            isNeural = false,
            description = "System voice — English & German"
        )

        val NEUTTS_AIR = VoiceProfile(
            id = "neutts-air",
            displayName = "NeuTTS Air",
            languages = listOf("en"),
            sttLocale = "en-US",
            ttsLocale = Locale.US,
            isNeural = true,
            description = "Neural voice — high quality",
            warning = "English only / Nur Englisch"
        )

        val ALL_PROFILES = listOf(ANDROID_DE, ANDROID_EN, NEUTTS_AIR)

        fun findById(id: String): VoiceProfile? = ALL_PROFILES.find { it.id == id }
    }
}

package com.nexus.companion

import java.util.Locale

/**
 * Voice profiles for STT language selection.
 * Determines which language the speech recognizer listens for.
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
            displayName = "Deutsch",
            languages = listOf("de", "en"),
            sttLocale = "de-DE",
            ttsLocale = Locale.GERMAN,
            isNeural = false,
            description = "Erkennt Deutsch — antwortet in deiner Sprache"
        )

        val ANDROID_EN = VoiceProfile(
            id = "android-en",
            displayName = "English",
            languages = listOf("en", "de"),
            sttLocale = "en-US",
            ttsLocale = Locale.US,
            isNeural = false,
            description = "Recognizes English — responds in your language"
        )

        val ALL_PROFILES = listOf(ANDROID_DE, ANDROID_EN)

        fun findById(id: String): VoiceProfile? = ALL_PROFILES.find { it.id == id }
    }
}

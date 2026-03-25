package com.nexus.companion.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app debug log for LLM activity. Ring buffer of last 200 entries.
 * Accessible from the ⋮ menu → "Debug Log".
 */
object DebugLog {
    private const val MAX_ENTRIES = 200

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $tag: $message"
        val current = _entries.value.toMutableList()
        current.add(entry)
        if (current.size > MAX_ENTRIES) {
            current.removeAt(0)
        }
        _entries.value = current
    }

    fun clear() {
        _entries.value = emptyList()
    }

    // Convenience methods
    fun llm(message: String) = log("LLM", message)
    fun tts(message: String) = log("TTS", message)
    fun stt(message: String) = log("STT", message)
    fun dl(message: String) = log("DL", message)
    fun mem(message: String) = log("MEM", message)
}

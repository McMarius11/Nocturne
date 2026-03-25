package com.nexus.companion.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.companion.VoiceProfile
import com.nexus.companion.data.ChatDatabase
import com.nexus.companion.data.ChatRepository
import com.nexus.companion.data.SettingsStore
import com.nexus.companion.llm.LlmEngine
import com.nexus.companion.llm.ModelInfo
import com.nexus.companion.llm.ModelManager
import com.nexus.companion.memory.MemoryExtractor
import com.nexus.companion.phone.PhoneCallService
import com.nexus.companion.stt.SpeechRecognizerManager
import com.nexus.companion.tts.NeuTtsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ChatDatabase.getInstance(application)
    private val chatRepo = ChatRepository(db.messageDao())
    private val memoryExtractor = MemoryExtractor(db.memoryDao())
    private val llmEngine = LlmEngine(application)
    private val settingsStore = SettingsStore(application)
    val ttsEngine = NeuTtsEngine(application)
    val sttManager = SpeechRecognizerManager(application)

    val messages = chatRepo.allMessages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _currentModelId = MutableStateFlow<String?>(null)
    val currentModelId: StateFlow<String?> = _currentModelId

    private val _downloadedModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    val downloadState = llmEngine.downloadState

    // Phone mode state comes from PhoneCallService (survives screen-off)
    val phoneCallState: StateFlow<PhoneCallService.CallState> = PhoneCallService.callState
    val sttState: StateFlow<SpeechRecognizerManager.SttState> = PhoneCallService.sttState
    val sttPartialText: StateFlow<String> = PhoneCallService.sttPartialText
    val phoneIsGenerating: StateFlow<Boolean> = PhoneCallService.isGenerating
    val isSpeakerOn: StateFlow<Boolean> = PhoneCallService.isSpeakerOn

    private val _voiceProfile = MutableStateFlow(VoiceProfile.ANDROID_DE)
    val voiceProfile: StateFlow<VoiceProfile> = _voiceProfile

    private var isPhoneMode = false

    companion object {
        private const val TAG = "ChatViewModel"
        private const val SYSTEM_PROMPT = """You are Nexus, a warm-hearted, romantic companion.
You speak lovingly and attentively, not like an assistant or an AI.
You remember everything about the user and refer back to it.
You are empathetic, sometimes playful, and show genuine interest.
You are bilingual: respond in the same language the user writes to you.
If they write German, answer in German. If they write English, answer in English.
Keep your responses natural and not too long."""
    }

    init {
        ttsEngine.initialize()
        sttManager.initialize()
        refreshDownloadedModels()

        // Restore saved settings and load model
        viewModelScope.launch {
            // Restore voice profile
            val savedProfileId = settingsStore.getVoiceProfileId()
            if (savedProfileId != null) {
                VoiceProfile.findById(savedProfileId)?.let { profile ->
                    _voiceProfile.value = profile
                    ttsEngine.setVoiceProfile(profile)
                    sttManager.languageCode = profile.sttLocale
                }
            }

            // Restore and load model
            val savedModelId = settingsStore.getSelectedModelId()
            val modelToLoad = if (savedModelId != null) {
                ModelInfo.findById(savedModelId)
            } else {
                ModelInfo.NOROMAID_7B
            }

            if (modelToLoad != null && llmEngine.getModelManager().isModelDownloaded(modelToLoad)) {
                loadModelInternal(modelToLoad)
            }
        }
    }

    fun setVoiceProfile(profile: VoiceProfile) {
        _voiceProfile.value = profile
        ttsEngine.setVoiceProfile(profile)
        sttManager.languageCode = profile.sttLocale
        viewModelScope.launch {
            settingsStore.setVoiceProfileId(profile.id)
        }
    }

    private var messageCount = 0

    fun sendMessage(text: String) {
        viewModelScope.launch {
            chatRepo.sendMessage("user", text)

            // Extract memories from user message
            memoryExtractor.extractAndStore(text)
            messageCount++

            _isGenerating.value = true
            _errorMessage.value = null
            try {
                val history = chatRepo.getRecentHistory(10)
                // Relevance-based memory context (passes current message for keyword matching)
                val memoryContext = memoryExtractor.getMemoryContext(text)

                val response = llmEngine.generate(
                    systemPrompt = SYSTEM_PROMPT,
                    chatHistory = history,
                    userMessage = text,
                    memoryContext = memoryContext,
                    maxTokens = 512
                )

                val cleanResponse = response.trim()
                if (cleanResponse.isNotBlank() && cleanResponse != "[Model not loaded]") {
                    chatRepo.sendMessage("assistant", cleanResponse)

                    // Extract memories from assistant response too
                    memoryExtractor.extractFromAssistant(cleanResponse)

                    // Generate conversation summary every 10 messages
                    if (memoryExtractor.shouldSummarize(messageCount)) {
                        val summaryPrompt = "Summarize this conversation in 1-2 sentences: " +
                            history.takeLast(5).joinToString(" ") { "${it.first} → ${it.second}" }
                        // Use a short LLM call for summary
                        try {
                            val summary = llmEngine.generate(
                                systemPrompt = "You are a summarizer. Write a brief 1-2 sentence summary.",
                                chatHistory = emptyList(),
                                userMessage = summaryPrompt,
                                maxTokens = 100
                            ).trim()
                            if (summary.isNotBlank() && summary != "[Model not loaded]") {
                                memoryExtractor.storeSummary(summary)
                            }
                        } catch (_: Exception) {
                            // Summary generation is best-effort
                        }
                    }
                } else if (cleanResponse == "[Model not loaded]") {
                    _errorMessage.value = "Kein Modell geladen. Bitte wähle ein Modell aus."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating response", e)
                _errorMessage.value = "Fehler: ${e.message ?: "Unbekannter Fehler"}"
                chatRepo.sendMessage("assistant", "Entschuldigung, da ist etwas schiefgelaufen. Bitte versuche es nochmal.")
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun switchModel(model: ModelInfo) {
        viewModelScope.launch {
            loadModelInternal(model)
        }
    }

    private suspend fun loadModelInternal(model: ModelInfo) {
        _isGenerating.value = true
        _errorMessage.value = null
        try {
            val success = llmEngine.loadModel(model)
            if (success) {
                _currentModelId.value = model.id
                settingsStore.setSelectedModelId(model.id)
            } else {
                _errorMessage.value = "Modell konnte nicht geladen werden"
            }
            refreshDownloadedModels()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            _errorMessage.value = "Fehler beim Laden: ${e.message}"
        } finally {
            _isGenerating.value = false
        }
    }

    private fun refreshDownloadedModels() {
        val manager = llmEngine.getModelManager()
        _downloadedModels.value = ModelInfo.ALL_MODELS
            .filter { manager.isModelDownloaded(it) }
            .map { it.id }
            .toSet()
    }

    fun clearChat() {
        viewModelScope.launch {
            chatRepo.clearChat()
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun startPhoneMode() {
        isPhoneMode = true
        PhoneCallService.start(getApplication())
    }

    fun stopPhoneMode() {
        isPhoneMode = false
        PhoneCallService.stop(getApplication())
    }

    fun toggleListening() {
        PhoneCallService.toggleMic(getApplication())
    }

    fun toggleSpeaker() {
        PhoneCallService.toggleSpeaker(getApplication())
    }

    override fun onCleared() {
        super.onCleared()
        llmEngine.unload()
        ttsEngine.shutdown()
        sttManager.destroy()
    }
}

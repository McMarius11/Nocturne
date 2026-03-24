package com.nexus.companion.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.companion.VoiceProfile
import com.nexus.companion.data.ChatDatabase
import com.nexus.companion.data.ChatRepository
import com.nexus.companion.llm.LlmEngine
import com.nexus.companion.llm.ModelInfo
import com.nexus.companion.llm.ModelManager
import com.nexus.companion.memory.MemoryExtractor
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
    val ttsEngine = NeuTtsEngine(application)
    val sttManager = SpeechRecognizerManager(application)

    val messages = chatRepo.allMessages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _currentModelId = MutableStateFlow<String?>(null)
    val currentModelId: StateFlow<String?> = _currentModelId

    private val _downloadedModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels

    val downloadState = llmEngine.downloadState

    private val _sttState = MutableStateFlow<SpeechRecognizerManager.SttState>(
        SpeechRecognizerManager.SttState.Idle
    )
    val sttState: StateFlow<SpeechRecognizerManager.SttState> = _sttState

    private val _sttPartialText = MutableStateFlow("")
    val sttPartialText: StateFlow<String> = _sttPartialText

    private val _voiceProfile = MutableStateFlow(VoiceProfile.ANDROID_DE)
    val voiceProfile: StateFlow<VoiceProfile> = _voiceProfile

    private var isPhoneMode = false

    companion object {
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

        viewModelScope.launch {
            sttManager.state.collect { state ->
                _sttState.value = state
                if (state is SpeechRecognizerManager.SttState.Done && state.text.isNotBlank()) {
                    if (isPhoneMode) {
                        sendMessage(state.text)
                    }
                }
            }
        }
        viewModelScope.launch {
            sttManager.result.collect { text ->
                _sttPartialText.value = text
            }
        }

        viewModelScope.launch {
            val defaultModel = ModelInfo.NOROMAID_7B
            if (llmEngine.getModelManager().isModelDownloaded(defaultModel)) {
                loadModelInternal(defaultModel)
            }
        }
    }

    fun setVoiceProfile(profile: VoiceProfile) {
        _voiceProfile.value = profile
        ttsEngine.setVoiceProfile(profile)
        sttManager.languageCode = profile.sttLocale
    }

    fun sendMessage(text: String) {
        viewModelScope.launch {
            chatRepo.sendMessage("user", text)

            memoryExtractor.extractAndStore(text)

            _isGenerating.value = true
            try {
                val history = chatRepo.getRecentHistory(10)
                val memoryContext = memoryExtractor.getMemoryContext()

                val response = llmEngine.generate(
                    systemPrompt = SYSTEM_PROMPT,
                    chatHistory = history,
                    userMessage = text,
                    memoryContext = memoryContext,
                    maxTokens = 512
                )

                val cleanResponse = response.trim()
                if (cleanResponse.isNotBlank()) {
                    chatRepo.sendMessage("assistant", cleanResponse)

                    if (isPhoneMode) {
                        ttsEngine.speak(cleanResponse)
                    }
                }
            } finally {
                _isGenerating.value = false

                if (isPhoneMode) {
                    sttManager.startListening()
                }
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
        try {
            val success = llmEngine.loadModel(model)
            if (success) {
                _currentModelId.value = model.id
            }
            refreshDownloadedModels()
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

    fun startPhoneMode() {
        isPhoneMode = true
    }

    fun stopPhoneMode() {
        isPhoneMode = false
        sttManager.stopListening()
        ttsEngine.stop()
    }

    fun toggleListening() {
        val currentState = _sttState.value
        if (currentState is SpeechRecognizerManager.SttState.Listening) {
            sttManager.stopListening()
        } else {
            sttManager.startListening()
        }
    }

    override fun onCleared() {
        super.onCleared()
        llmEngine.unload()
        ttsEngine.shutdown()
        sttManager.destroy()
    }
}

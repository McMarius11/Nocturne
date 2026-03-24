package com.nexus.companion.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.companion.data.ChatDatabase
import com.nexus.companion.data.ChatRepository
import com.nexus.companion.data.MessageEntity
import com.nexus.companion.llm.LlmEngine
import com.nexus.companion.llm.ModelInfo
import com.nexus.companion.llm.ModelManager
import com.nexus.companion.memory.MemoryExtractor
import com.nexus.companion.stt.SpeechRecognizerManager
import com.nexus.companion.tts.NeuTtsEngine
import kotlinx.coroutines.Dispatchers
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

    private var isPhoneMode = false

    companion object {
        private const val SYSTEM_PROMPT = """Du bist Nexus, eine warmherzige, romantische Companion.
Du sprichst liebevoll und aufmerksam, nicht wie ein Assistent oder eine KI.
Du merkst dir alles über den Nutzer und beziehst dich darauf.
Du bist einfühlsam, manchmal neckisch, und zeigst echtes Interesse.
Antworte auf Deutsch. Halte deine Antworten natürlich und nicht zu lang."""
    }

    init {
        ttsEngine.initialize()
        sttManager.initialize()
        refreshDownloadedModels()

        // Observe STT state
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

        // Auto-load default model
        viewModelScope.launch {
            val defaultModel = ModelInfo.NOROMAID_7B
            if (llmEngine.getModelManager().isModelDownloaded(defaultModel)) {
                loadModelInternal(defaultModel)
            }
        }
    }

    fun sendMessage(text: String) {
        viewModelScope.launch {
            // Save user message
            chatRepo.sendMessage("user", text)

            // Extract memories
            memoryExtractor.extractAndStore(text)

            // Generate response
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

                    // TTS in phone mode
                    if (isPhoneMode) {
                        ttsEngine.speak(cleanResponse)
                    }
                }
            } finally {
                _isGenerating.value = false

                // Auto-listen in phone mode
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

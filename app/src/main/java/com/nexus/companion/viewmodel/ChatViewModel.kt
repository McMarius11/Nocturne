package com.nexus.companion.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.companion.VoiceProfile
import com.nexus.companion.data.ChatDatabase
import com.nexus.companion.data.ChatRepository
import com.nexus.companion.data.SettingsStore
import com.nexus.companion.llm.DownloadService
import com.nexus.companion.llm.LlmEngine
import com.nexus.companion.llm.ModelInfo
import com.nexus.companion.llm.ModelManager
import com.nexus.companion.memory.MemoryExtractor
import com.nexus.companion.phone.PhoneCallService
import com.nexus.companion.stt.SpeechRecognizerManager
import com.nexus.companion.tts.NeuTtsEngine
import com.nexus.companion.tts.TtsModelInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ChatDatabase.getInstance(application)
    private val chatRepo = ChatRepository(db.messageDao())
    private val memoryExtractor = MemoryExtractor(db.memoryDao())
    private val llmEngine = LlmEngine.getInstance(application)
    private val settingsStore = SettingsStore(application)
    val ttsEngine = NeuTtsEngine(application)
    val sttManager = SpeechRecognizerManager(application)

    val messages = chatRepo.allMessages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    // Separate loading state (model loading) so Send button isn't blocked
    private val _isLoadingModel = MutableStateFlow(false)
    val isLoadingModel: StateFlow<Boolean> = _isLoadingModel

    private val _currentModelId = MutableStateFlow<String?>(null)
    val currentModelId: StateFlow<String?> = _currentModelId

    private val _downloadedModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    val downloadState = llmEngine.downloadState

    // LLM loaded state — for manual toggle
    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded

    // Phone mode state comes from PhoneCallService (survives screen-off)
    val phoneCallState: StateFlow<PhoneCallService.CallState> = PhoneCallService.callState
    val sttState: StateFlow<SpeechRecognizerManager.SttState> = PhoneCallService.sttState
    val sttPartialText: StateFlow<String> = PhoneCallService.sttPartialText
    val phoneIsGenerating: StateFlow<Boolean> = PhoneCallService.isGenerating
    val isSpeakerOn: StateFlow<Boolean> = PhoneCallService.isSpeakerOn
    val isSpeaking: StateFlow<Boolean> = PhoneCallService.isSpeaking

    // TTS model state
    private val _currentTtsModelId = MutableStateFlow<String?>(null)
    val currentTtsModelId: StateFlow<String?> = _currentTtsModelId

    private val _downloadedTtsModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadedTtsModels: StateFlow<Set<String>> = _downloadedTtsModels

    val ttsDownloadState: StateFlow<ModelManager.DownloadState> = DownloadService.ttsDownloadProgress

    private val _voiceProfile = MutableStateFlow(VoiceProfile.ANDROID_DE)
    val voiceProfile: StateFlow<VoiceProfile> = _voiceProfile

    private var isPhoneMode = false
    private var idleTimerJob: Job? = null
    private var messageCount = 0

    companion object {
        private const val TAG = "ChatViewModel"
        private const val IDLE_TIMEOUT_MS = 10L * 60 * 1000 // 10 minutes
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
        refreshDownloadedTtsModels()

        // Restore saved settings and load model
        viewModelScope.launch {
            val savedProfileId = settingsStore.getVoiceProfileId()
            if (savedProfileId != null) {
                VoiceProfile.findById(savedProfileId)?.let { profile ->
                    _voiceProfile.value = profile
                    ttsEngine.setVoiceProfile(profile)
                    sttManager.languageCode = profile.sttLocale
                }
            }

            // FIX #1: Use separate key for LLM model (not shared with TTS)
            val savedModelId = settingsStore.getSelectedModelId()
            val modelToLoad = if (savedModelId != null && !savedModelId.startsWith("tts_")) {
                ModelInfo.findById(savedModelId)
            } else {
                // No valid LLM model saved, try first downloaded model
                val downloaded = ModelInfo.ALL_MODELS.firstOrNull {
                    llmEngine.getModelManager().isModelDownloaded(it)
                }
                downloaded
            }

            if (modelToLoad != null && llmEngine.getModelManager().isModelDownloaded(modelToLoad)) {
                loadModelInternal(modelToLoad)
            }
        }
    }

    // ===== LLM Manual Toggle =====

    fun toggleModelLoaded() {
        if (_isModelLoaded.value) {
            unloadModel()
        } else {
            reloadModel()
        }
    }

    private fun unloadModel() {
        Log.i(TAG, "Manual unload — freeing LLM from RAM")
        llmEngine.unload()
        _isModelLoaded.value = false
        cancelIdleTimer()
    }

    private fun reloadModel() {
        val modelId = _currentModelId.value
        if (modelId == null) {
            // FIX #5: Give feedback when no model is selected
            _errorMessage.value = "Kein Modell ausgewählt. Bitte wähle zuerst ein Modell."
            return
        }
        val model = ModelInfo.findById(modelId)
        if (model == null || !llmEngine.getModelManager().isModelDownloaded(model)) {
            _errorMessage.value = "Modell nicht verfügbar. Bitte lade es zuerst herunter."
            return
        }

        Log.i(TAG, "Reloading model: $modelId")
        viewModelScope.launch {
            loadModelInternal(model)
        }
    }

    // ===== Idle Timer (auto-unload after 10 min) =====

    private fun resetIdleTimer() {
        cancelIdleTimer()
        if (!_isModelLoaded.value) return
        if (isPhoneMode) return

        idleTimerJob = viewModelScope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (!_isGenerating.value && !isPhoneMode && _isModelLoaded.value) {
                Log.i(TAG, "Idle timeout (${IDLE_TIMEOUT_MS / 60000} min) — unloading LLM")
                llmEngine.unload()
                _isModelLoaded.value = false
                // FIX #6: _currentModelId stays set so user sees "(aus)" in TopBar
                // and can re-enable with toggle
            }
        }
    }

    private fun cancelIdleTimer() {
        idleTimerJob?.cancel()
        idleTimerJob = null
    }

    // ===== Voice Profile =====

    fun setVoiceProfile(profile: VoiceProfile) {
        _voiceProfile.value = profile
        ttsEngine.setVoiceProfile(profile)
        sttManager.languageCode = profile.sttLocale
        viewModelScope.launch {
            settingsStore.setVoiceProfileId(profile.id)
        }
    }

    // ===== Chat =====

    fun sendMessage(text: String) {
        if (!llmEngine.isReady()) {
            if (_currentModelId.value == null) {
                _errorMessage.value = "Kein Modell geladen. Tippe auf \uD83E\uDDE0 um ein Modell herunterzuladen."
            } else {
                _errorMessage.value = "LLM ist ausgeschaltet. Bitte schalte es ein (⋮ Menü → LLM einschalten)."
            }
            return
        }

        resetIdleTimer()

        viewModelScope.launch {
            chatRepo.sendMessage("user", text)
            memoryExtractor.extractAndStore(text)
            messageCount++

            _isGenerating.value = true
            _errorMessage.value = null
            try {
                val history = chatRepo.getRecentHistory(10)
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
                    memoryExtractor.extractFromAssistant(cleanResponse)

                    if (memoryExtractor.shouldSummarize(messageCount)) {
                        val summaryPrompt = "Summarize this conversation in 1-2 sentences: " +
                            history.takeLast(5).joinToString(" ") { "${it.first} → ${it.second}" }
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
                        } catch (_: Exception) {}
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
                resetIdleTimer()
            }
        }
    }

    // ===== Model Management =====

    fun switchModel(model: ModelInfo) {
        viewModelScope.launch {
            loadModelInternal(model)
        }
    }

    private suspend fun loadModelInternal(model: ModelInfo) {
        _isLoadingModel.value = true
        _errorMessage.value = null
        try {
            val success = llmEngine.loadModel(model)
            if (success) {
                _currentModelId.value = model.id
                _isModelLoaded.value = true
                settingsStore.setSelectedModelId(model.id)
                resetIdleTimer()
            } else {
                _errorMessage.value = "Modell konnte nicht geladen werden"
            }
            refreshDownloadedModels()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            _errorMessage.value = "Fehler beim Laden: ${e.message}"
        } finally {
            _isLoadingModel.value = false
        }
    }

    private fun refreshDownloadedModels() {
        val manager = llmEngine.getModelManager()
        _downloadedModels.value = ModelInfo.ALL_MODELS
            .filter { manager.isModelDownloaded(it) }
            .map { it.id }
            .toSet()
    }

    // ===== TTS Model Management =====

    fun selectTtsModel(model: TtsModelInfo) {
        // "system" = revert to Android System TTS
        if (model.id == "system") {
            _currentTtsModelId.value = null
            return
        }

        val modelsDir = java.io.File(getApplication<Application>().filesDir, "models")
        val modelFile = java.io.File(modelsDir, model.fileName)

        if (modelFile.exists()) {
            _currentTtsModelId.value = model.id
            // FIX #1: Use separate settings key for TTS model
            viewModelScope.launch {
                settingsStore.setVoiceProfileId("tts:${model.id}")
            }
        } else {
            // Download the TTS model
            val modelInfo = ModelInfo(
                id = model.id,
                displayName = model.displayName,
                fileName = model.fileName,
                downloadUrl = model.downloadUrl,
                sizeGb = model.sizeBytes / 1_000_000_000f,
                sizeBytes = model.sizeBytes,
                batteryPerHour = 0,
                description = model.description
            )
            llmEngine.getModelManager().startDownload(modelInfo)
        }
    }

    private fun refreshDownloadedTtsModels() {
        val modelsDir = java.io.File(getApplication<Application>().filesDir, "models")
        _downloadedTtsModels.value = TtsModelInfo.ALL_TTS_MODELS
            .filter { java.io.File(modelsDir, it.fileName).exists() }
            .map { it.id }
            .toSet()
    }

    // ===== Chat Management =====

    fun clearChat() {
        viewModelScope.launch {
            chatRepo.clearChat()
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    // ===== Phone Mode =====

    fun startPhoneMode() {
        // FIX #4: Check if model is loaded before starting phone mode
        if (!llmEngine.isReady() && _currentModelId.value == null) {
            _errorMessage.value = "Kein Modell geladen. Bitte lade zuerst ein Modell."
            return
        }
        isPhoneMode = true
        cancelIdleTimer()
        PhoneCallService.start(getApplication(), _currentModelId.value)
    }

    fun stopPhoneMode() {
        isPhoneMode = false
        PhoneCallService.stop(getApplication())
        resetIdleTimer()
    }

    fun toggleListening() {
        PhoneCallService.toggleMic(getApplication())
    }

    fun toggleSpeaker() {
        PhoneCallService.toggleSpeaker(getApplication())
    }

    // ===== Lifecycle =====

    override fun onCleared() {
        super.onCleared()
        cancelIdleTimer()
        llmEngine.unload()
        ttsEngine.shutdown()
        sttManager.destroy()
    }
}

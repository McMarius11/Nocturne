package com.nexus.companion.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.companion.VoiceProfile
import com.nexus.companion.data.ChatDatabase
import com.nexus.companion.data.ChatRepository
import com.nexus.companion.data.SettingsStore
import com.nexus.companion.llm.DebugLog
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

    private val _isLoadingModel = MutableStateFlow(false)
    val isLoadingModel: StateFlow<Boolean> = _isLoadingModel

    private val _currentModelId = MutableStateFlow<String?>(null)
    val currentModelId: StateFlow<String?> = _currentModelId

    private val _downloadedModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    val downloadState = llmEngine.downloadState

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded

    // Phone mode state
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

    /** Streaming partial response — updated token by token during generation */
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText

    private var isPhoneMode = false
    private var idleTimerJob: Job? = null
    private var errorDismissJob: Job? = null
    private var messageCount = 0

    companion object {
        private const val TAG = "ChatViewModel"
        private const val IDLE_TIMEOUT_MS = 10L * 60 * 1000
        private const val ERROR_AUTO_DISMISS_MS = 8000L
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

        // Auto-refresh on download state changes
        viewModelScope.launch {
            llmEngine.downloadState.collect { state ->
                if (state is ModelManager.DownloadState.Idle) {
                    refreshDownloadedModels()
                    refreshDownloadedTtsModels()
                }
            }
        }

        // Collect streaming tokens
        viewModelScope.launch {
            llmEngine.tokenStream.collect { token ->
                _streamingText.value += token
            }
        }

        // Restore saved settings
        viewModelScope.launch {
            val savedProfileId = settingsStore.getVoiceProfileId()
            if (savedProfileId != null) {
                VoiceProfile.findById(savedProfileId)?.let { profile ->
                    _voiceProfile.value = profile
                    ttsEngine.setVoiceProfile(profile)
                    sttManager.languageCode = profile.sttLocale
                }
            }

            val savedModelId = settingsStore.getSelectedModelId()
            var modelToLoad: ModelInfo? = null

            if (savedModelId != null && !savedModelId.startsWith("tts_")) {
                modelToLoad = ModelInfo.findById(savedModelId)
                if (modelToLoad == null) {
                    // Old model ID (e.g. gemma3-4b-heretic, gemma-2b) — migrate
                    Log.i(TAG, "Saved model '$savedModelId' no longer exists, migrating...")
                    DebugLog.llm("Migration: Altes Modell '$savedModelId' existiert nicht mehr")
                }
            }

            // Fallback: first downloaded model
            if (modelToLoad == null) {
                modelToLoad = ModelInfo.ALL_MODELS.firstOrNull {
                    llmEngine.getModelManager().isModelDownloaded(it)
                }
            }

            if (modelToLoad != null && llmEngine.getModelManager().isModelDownloaded(modelToLoad)) {
                loadModelInternal(modelToLoad)
            }

            val savedTtsId = settingsStore.getTtsModelId()
            if (savedTtsId != null) {
                _currentTtsModelId.value = savedTtsId
                ttsEngine.setTtsModel(savedTtsId)
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
        Log.i(TAG, "Manual unload")
        llmEngine.unload()
        _isModelLoaded.value = false
        cancelIdleTimer()
    }

    private fun reloadModel() {
        val modelId = _currentModelId.value
        if (modelId == null) {
            showError("Kein Modell ausgewählt. Bitte wähle zuerst ein Modell.")
            return
        }
        val model = ModelInfo.findById(modelId)
        if (model == null || !llmEngine.getModelManager().isModelDownloaded(model)) {
            showError("Modell nicht verfügbar. Bitte lade es zuerst herunter.")
            return
        }
        viewModelScope.launch { loadModelInternal(model) }
    }

    // ===== Idle Timer =====

    private fun resetIdleTimer() {
        cancelIdleTimer()
        if (!_isModelLoaded.value || isPhoneMode) return

        idleTimerJob = viewModelScope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (!_isGenerating.value && !isPhoneMode && _isModelLoaded.value) {
                Log.i(TAG, "Idle timeout — unloading LLM")
                llmEngine.unload()
                _isModelLoaded.value = false
                showError("LLM wurde nach 10 Min Inaktivität entladen. Tippe auf Senden zum Neuladen.")
            }
        }
    }

    private fun cancelIdleTimer() {
        idleTimerJob?.cancel()
        idleTimerJob = null
    }

    // ===== Error Management =====

    private fun showError(message: String) {
        _errorMessage.value = message
        // Auto-dismiss after timeout
        errorDismissJob?.cancel()
        errorDismissJob = viewModelScope.launch {
            delay(ERROR_AUTO_DISMISS_MS)
            _errorMessage.value = null
        }
    }

    fun clearError() {
        errorDismissJob?.cancel()
        _errorMessage.value = null
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
        // Guard: prevent double-send while generating
        if (_isGenerating.value) return

        if (!llmEngine.isReady()) {
            val modelId = _currentModelId.value
            if (modelId == null) {
                showError("Kein Modell geladen. Tippe auf \uD83E\uDDE0 um ein Modell herunterzuladen.")
                return
            }
            val model = ModelInfo.findById(modelId)
            if (model != null && llmEngine.getModelManager().isModelDownloaded(model)) {
                showError("Modell wird neu geladen...")
                viewModelScope.launch {
                    loadModelInternal(model)
                    if (llmEngine.isReady()) {
                        clearError()
                        executeSendMessage(text)
                    } else {
                        showError("Modell konnte nicht geladen werden.")
                    }
                }
                return
            }
            showError("Modell nicht verfügbar. Bitte lade es erneut herunter.")
            return
        }

        resetIdleTimer()
        viewModelScope.launch { executeSendMessage(text) }
    }

    private suspend fun executeSendMessage(text: String) {
        memoryExtractor.extractAndStore(text)
        messageCount++

        _isGenerating.value = true
        _streamingText.value = "" // Reset streaming buffer
        clearError()

        try {
            val history = chatRepo.getRecentHistory(10)
            val memoryContext = memoryExtractor.getMemoryContext(text)

            chatRepo.sendMessage("user", text)

            val response = llmEngine.generate(
                systemPrompt = SYSTEM_PROMPT,
                chatHistory = history,
                userMessage = text,
                memoryContext = memoryContext,
                maxTokens = 512
            )

            val cleanResponse = response.trim()
            _streamingText.value = "" // Clear streaming after complete

            if (cleanResponse.isNotBlank() && !cleanResponse.startsWith("[Error:") && cleanResponse != "[Model not loaded]") {
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
                        if (summary.isNotBlank() && !summary.startsWith("[Error:")) {
                            memoryExtractor.storeSummary(summary)
                        }
                    } catch (_: Exception) {}
                }
            } else if (cleanResponse.startsWith("[Error:")) {
                showError("Generierung fehlgeschlagen. Debug Log prüfen.")
                DebugLog.llm("Generation returned error: $cleanResponse")
            } else if (cleanResponse == "[Model not loaded]") {
                showError("Kein Modell geladen. Bitte wähle ein Modell aus.")
            } else if (cleanResponse.isBlank()) {
                showError("Leere Antwort. Versuche es nochmal oder wechsle das Modell.")
                DebugLog.llm("WARNING: Empty response from generation")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating response", e)
            showError("Fehler: ${e.message ?: "Unbekannter Fehler"}")
            chatRepo.sendMessage("assistant", "Entschuldigung, da ist etwas schiefgelaufen. Bitte versuche es nochmal.")
        } finally {
            _isGenerating.value = false
            _streamingText.value = ""
            resetIdleTimer()
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
        clearError()
        try {
            val success = llmEngine.loadModel(model)
            if (success) {
                _currentModelId.value = model.id
                _isModelLoaded.value = true
                settingsStore.setSelectedModelId(model.id)
                resetIdleTimer()
            } else {
                showError("Modell konnte nicht geladen werden. Prüfe Debug Log.")
            }
            refreshDownloadedModels()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            showError("Fehler beim Laden: ${e.message}")
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

    fun deleteModel(model: ModelInfo) {
        if (model.id == _currentModelId.value) {
            llmEngine.unload()
            _isModelLoaded.value = false
            _currentModelId.value = null
        }
        llmEngine.getModelManager().deleteModel(model)
        refreshDownloadedModels()
    }

    // ===== TTS Model Management =====

    fun selectTtsModel(model: TtsModelInfo) {
        if (model.id == "system") {
            _currentTtsModelId.value = null
            ttsEngine.setTtsModel(null)
            viewModelScope.launch { settingsStore.setTtsModelId(null) }
            return
        }

        val modelsDir = java.io.File(getApplication<Application>().filesDir, "models")
        val modelFile = java.io.File(modelsDir, model.fileName)

        if (modelFile.exists()) {
            _currentTtsModelId.value = model.id
            ttsEngine.setTtsModel(model.id)
            viewModelScope.launch { settingsStore.setTtsModelId(model.id) }
        } else {
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

    // ===== System Info =====

    data class StorageInfo(
        val availableStorageGb: Float,
        val availableRamGb: Float,
        val totalRamGb: Float
    )

    fun getStorageInfo(): StorageInfo {
        val manager = llmEngine.getModelManager()
        return StorageInfo(
            availableStorageGb = manager.getAvailableStorageGb(),
            availableRamGb = manager.getAvailableRamGb(),
            totalRamGb = manager.getTotalRamGb()
        )
    }

    /** Get debug info for troubleshooting (shown in DebugLogScreen) */
    fun getDebugInfo(): String {
        val manager = llmEngine.getModelManager()
        val model = llmEngine.getCurrentModel()
        val sb = StringBuilder()
        sb.appendLine("=== System Info ===")
        sb.appendLine("Model: ${model?.displayName ?: "None"} (${model?.id ?: "-"})")
        sb.appendLine("Model loaded: ${llmEngine.isReady()}")
        sb.appendLine("Format: ${model?.promptFormat ?: "-"}")
        sb.appendLine("Context: ${model?.contextLength ?: "-"}")
        sb.appendLine("RAM: ${String.format("%.1f", manager.getAvailableRamGb())} / ${String.format("%.0f", manager.getTotalRamGb())} GB")
        sb.appendLine("Storage free: ${String.format("%.1f", manager.getAvailableStorageGb())} GB")
        sb.appendLine("Models stored: ${String.format("%.1f", manager.getUsedStorageGb())} GB")
        sb.appendLine("Downloaded: ${_downloadedModels.value.joinToString(", ")}")
        sb.appendLine("llama.cpp: b8648")
        return sb.toString()
    }

    // ===== Chat Management =====

    fun clearChat() {
        viewModelScope.launch {
            chatRepo.clearChat()
        }
    }

    // ===== Phone Mode =====

    fun startPhoneMode() {
        if (!llmEngine.isReady() && _currentModelId.value == null) {
            showError("Kein Modell geladen. Bitte lade zuerst ein Modell.")
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
        errorDismissJob?.cancel()
        llmEngine.unload()
        ttsEngine.shutdown()
        sttManager.destroy()
    }
}

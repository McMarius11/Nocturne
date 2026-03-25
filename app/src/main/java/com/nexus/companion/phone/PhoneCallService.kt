package com.nexus.companion.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nexus.companion.MainActivity
import com.nexus.companion.VoiceProfile
import com.nexus.companion.data.ChatDatabase
import com.nexus.companion.data.ChatRepository
import com.nexus.companion.llm.LlmEngine
import com.nexus.companion.llm.ModelInfo
import com.nexus.companion.memory.MemoryExtractor
import com.nexus.companion.stt.SpeechRecognizerManager
import com.nexus.companion.tts.NeuTtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ForegroundService that keeps the voice conversation alive when the screen is off.
 * Manages: STT -> LLM -> TTS loop, WakeLock, Proximity sensor, Audio focus.
 */
class PhoneCallService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "PhoneCallService"
        const val CHANNEL_ID = "phone_call"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.nexus.companion.PHONE_START"
        const val ACTION_STOP = "com.nexus.companion.PHONE_STOP"
        const val ACTION_TOGGLE_MIC = "com.nexus.companion.PHONE_TOGGLE_MIC"
        const val ACTION_TOGGLE_SPEAKER = "com.nexus.companion.PHONE_TOGGLE_SPEAKER"
        const val EXTRA_MODEL_ID = "model_id"

        private val _callState = MutableStateFlow<CallState>(CallState.Idle)
        val callState: StateFlow<CallState> = _callState

        private val _sttState = MutableStateFlow<SpeechRecognizerManager.SttState>(
            SpeechRecognizerManager.SttState.Idle
        )
        val sttState: StateFlow<SpeechRecognizerManager.SttState> = _sttState

        private val _sttPartialText = MutableStateFlow("")
        val sttPartialText: StateFlow<String> = _sttPartialText

        private val _isGenerating = MutableStateFlow(false)
        val isGenerating: StateFlow<Boolean> = _isGenerating

        private val _isSpeakerOn = MutableStateFlow(true) // Speaker on by default
        val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn

        fun start(context: Context, modelId: String? = null) {
            val intent = Intent(context, PhoneCallService::class.java).apply {
                action = ACTION_START
                modelId?.let { putExtra(EXTRA_MODEL_ID, it) }
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PhoneCallService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun toggleMic(context: Context) {
            val intent = Intent(context, PhoneCallService::class.java).apply {
                action = ACTION_TOGGLE_MIC
            }
            context.startService(intent)
        }

        fun toggleSpeaker(context: Context) {
            val intent = Intent(context, PhoneCallService::class.java).apply {
                action = ACTION_TOGGLE_SPEAKER
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var conversationJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var audioManager: AudioManager? = null

    // Core components — created in onCreate, cleaned up in onDestroy
    private lateinit var sttManager: SpeechRecognizerManager
    private lateinit var ttsEngine: NeuTtsEngine
    private lateinit var chatRepo: ChatRepository
    private lateinit var memoryExtractor: MemoryExtractor

    // Shared LlmEngine singleton — same instance as ChatViewModel
    // The JNI uses global statics, so we MUST share one instance
    private val llmEngine: LlmEngine by lazy { LlmEngine.getInstance(this) }

    private val systemPrompt = """You are Nexus, a warm-hearted, romantic companion.
You speak lovingly and attentively, not like an assistant or an AI.
You remember everything about the user and refer back to it.
You are empathetic, sometimes playful, and show genuine interest.
You are bilingual: respond in the same language the user writes to you.
If they write German, answer in German. If they write English, answer in English.
Keep your responses natural and not too long."""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val db = ChatDatabase.getInstance(this)
        chatRepo = ChatRepository(db.messageDao())
        memoryExtractor = MemoryExtractor(db.memoryDao())
        ttsEngine = NeuTtsEngine(this)
        sttManager = SpeechRecognizerManager(this)

        ttsEngine.initialize()
        sttManager.initialize()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
                startCall(modelId)
            }
            ACTION_STOP -> stopCall()
            ACTION_TOGGLE_MIC -> toggleMicrophone()
            ACTION_TOGGLE_SPEAKER -> toggleSpeaker()
        }
        return START_NOT_STICKY
    }

    private fun startCall(modelId: String?) {
        if (_callState.value is CallState.Active) return

        startForeground(NOTIFICATION_ID, buildNotification("Modell wird geladen..."))
        acquireWakeLocks()
        registerProximitySensor()
        requestAudioFocus()

        _callState.value = CallState.Active

        // Check if LLM is ready (should be loaded by ChatViewModel already)
        // If not, try to load it
        serviceScope.launch {
            if (!llmEngine.isReady()) {
                val model = modelId?.let { ModelInfo.findById(it) }
                if (model != null) {
                    Log.i(TAG, "Model not loaded, loading: ${model.id}")
                    updateNotification("Modell wird geladen...")
                    val success = llmEngine.loadModel(model)
                    if (!success) {
                        Log.e(TAG, "Failed to load model: ${model.id}")
                        updateNotification("Modell konnte nicht geladen werden")
                        stopCall()
                        return@launch
                    }
                } else {
                    Log.e(TAG, "No model selected for phone mode")
                    updateNotification("Kein Modell ausgewählt")
                    stopCall()
                    return@launch
                }
            }

            Log.i(TAG, "LLM ready, starting conversation loop")
            updateNotification("Sprachmodus aktiv")
            startConversationLoop()
        }

        Log.d(TAG, "Phone call started")
    }

    private fun stopCall() {
        conversationJob?.cancel()
        conversationJob = null

        sttManager.stopListening()
        ttsEngine.stop()

        releaseAudioFocus()
        unregisterProximitySensor()
        releaseWakeLocks()

        _callState.value = CallState.Idle
        _sttState.value = SpeechRecognizerManager.SttState.Idle
        _sttPartialText.value = ""
        _isGenerating.value = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.d(TAG, "Phone call stopped")
    }

    private fun toggleMicrophone() {
        val current = sttManager.state.value
        if (current is SpeechRecognizerManager.SttState.Listening) {
            sttManager.stopListening()
        } else {
            sttManager.startListening()
        }
    }

    private fun toggleSpeaker() {
        val newState = !_isSpeakerOn.value
        _isSpeakerOn.value = newState
        audioManager?.isSpeakerphoneOn = newState
        if (newState) {
            audioManager?.mode = AudioManager.MODE_NORMAL
        } else {
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        }
    }

    private fun startConversationLoop() {
        conversationJob?.cancel()
        conversationJob = serviceScope.launch {
            // Collect STT state changes
            launch {
                sttManager.state.collect { state ->
                    _sttState.value = state

                    when (state) {
                        is SpeechRecognizerManager.SttState.Done -> {
                            if (state.text.isNotBlank()) {
                                handleUserSpeech(state.text)
                            }
                        }
                        is SpeechRecognizerManager.SttState.Error -> {
                            Log.w(TAG, "STT error: ${state.message}")
                            delay(1500)
                            _sttState.value = SpeechRecognizerManager.SttState.Idle
                            // Auto-restart listening after error
                            if (_callState.value is CallState.Active) {
                                sttManager.startListening()
                            }
                        }
                        else -> {}
                    }
                }
            }

            // Collect partial STT results
            launch {
                sttManager.result.collect { text ->
                    _sttPartialText.value = text
                }
            }

            // Start listening
            sttManager.startListening()
        }
    }

    private fun handleUserSpeech(text: String) {
        serviceScope.launch {
            _isGenerating.value = true
            updateNotification("Nexus denkt nach...")

            try {
                // Save user message
                chatRepo.sendMessage("user", text)
                memoryExtractor.extractAndStore(text)

                // Generate response with relevance-based memory
                val history = chatRepo.getRecentHistory(10)
                val memoryContext = memoryExtractor.getMemoryContext(text)

                val response = llmEngine.generate(
                    systemPrompt = systemPrompt,
                    chatHistory = history,
                    userMessage = text,
                    memoryContext = memoryContext,
                    maxTokens = 512
                )

                val cleanResponse = response.trim()
                if (cleanResponse.isNotBlank() && cleanResponse != "[Model not loaded]") {
                    chatRepo.sendMessage("assistant", cleanResponse)
                    memoryExtractor.extractFromAssistant(cleanResponse)

                    // Speak the response
                    updateNotification("Nexus spricht...")
                    ttsEngine.speak(cleanResponse)

                    // Wait a moment for TTS to finish, then resume listening
                    delay(500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in conversation loop", e)
            } finally {
                _isGenerating.value = false
                updateNotification("Sprachmodus aktiv")

                // Resume listening after response
                if (_callState.value is CallState.Active) {
                    sttManager.startListening()
                }
            }
        }
    }

    // --- WakeLock management ---

    private fun acquireWakeLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Partial wake lock — keeps CPU alive
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NexusCompanion::PhoneCall"
        ).apply {
            acquire(60 * 60 * 1000L) // Max 1 hour
        }

        // Proximity wake lock — turns off screen when near ear (like a real phone call)
        @Suppress("DEPRECATION")
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "NexusCompanion::Proximity"
            ).apply {
                acquire(60 * 60 * 1000L)
            }
        }

        Log.d(TAG, "WakeLocks acquired")
    }

    private fun releaseWakeLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        proximityWakeLock?.let { if (it.isHeld) it.release() }
        proximityWakeLock = null
        Log.d(TAG, "WakeLocks released")
    }

    // --- Proximity sensor ---

    private fun registerProximitySensor() {
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        sensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun unregisterProximitySensor() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_PROXIMITY) return
        val distance = event.values[0]
        val isNear = distance < (event.sensor.maximumRange / 2)

        // Auto-switch audio routing based on proximity
        // Only auto-switch if user hasn't manually set speaker off
        if (isNear) {
            _isSpeakerOn.value = false
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isSpeakerphoneOn = false
        } else {
            _isSpeakerOn.value = true
            audioManager?.mode = AudioManager.MODE_NORMAL
            audioManager?.isSpeakerphoneOn = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- Audio focus ---

    private val audioFocusRequest by lazy {
        android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Log.w(TAG, "Audio focus lost — pausing STT")
                        sttManager.stopListening()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        sttManager.stopListening()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (_callState.value is CallState.Active && !_isGenerating.value) {
                            sttManager.startListening()
                        }
                    }
                }
            }
            .build()
    }

    private fun requestAudioFocus() {
        audioManager?.requestAudioFocus(audioFocusRequest)
    }

    private fun releaseAudioFocus() {
        audioManager?.abandonAudioFocusRequest(audioFocusRequest)
        audioManager?.mode = AudioManager.MODE_NORMAL
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sprachmodus",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Aktiv während des Sprachmodus"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hangUpIntent = Intent(this, PhoneCallService::class.java).apply {
            action = ACTION_STOP
        }
        val hangUpPendingIntent = PendingIntent.getService(
            this, 0, hangUpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Nexus Sprachmodus")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Auflegen", hangUpPendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        conversationJob?.cancel()
        sttManager.destroy()
        ttsEngine.shutdown()
        releaseAudioFocus()
        unregisterProximitySensor()
        releaseWakeLocks()
        _callState.value = CallState.Idle
        serviceScope.cancel()
    }

    sealed class CallState {
        data object Idle : CallState()
        data object Active : CallState()
    }
}

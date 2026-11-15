package com.stark.jarvis.service

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.service.quicksettings.TileService
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.stark.jarvis.MainActivity
import com.stark.jarvis.accessibility.AccessibilityActionResult
import com.stark.jarvis.accessibility.JarvisAccessibilityService
import com.stark.jarvis.accessibility.ScreenCaptureResult
import com.stark.jarvis.accessibility.ScreenReadResult
import com.stark.jarvis.controllers.AgentDecisionResult
import com.stark.jarvis.controllers.AgentNotificationHelper
import com.stark.jarvis.controllers.AgentStepCommand
import com.stark.jarvis.controllers.AppLaunchLookupResult
import com.stark.jarvis.controllers.CommandDispatcher
import com.stark.jarvis.controllers.ContactLookupResult
import com.stark.jarvis.controllers.DeviceAssistantActions
import com.stark.jarvis.controllers.DispatchResult
import com.stark.jarvis.controllers.GeminiClient
import com.stark.jarvis.controllers.GeminiResult
import com.stark.jarvis.controllers.HapticController
import com.stark.jarvis.controllers.PhoneActions
import com.stark.jarvis.controllers.SpeechController
import com.stark.jarvis.controllers.SpeechListener
import com.stark.jarvis.controllers.TorchController
import com.stark.jarvis.controllers.VoiceEngine
import com.stark.jarvis.controllers.VoskModelManager
import com.stark.jarvis.controllers.VoskPrepareResult
import com.stark.jarvis.controllers.VoskWakeController
import com.stark.jarvis.controllers.VoskWakeStartResult
import com.stark.jarvis.controllers.WakeNotificationHelper
import com.stark.jarvis.data.AssistantSwitchState
import com.stark.jarvis.data.ConfirmationPromptUi
import com.stark.jarvis.data.ContactItem
import com.stark.jarvis.data.JarvisRuntimeStore
import com.stark.jarvis.data.VoiceState
import com.stark.jarvis.notifications.JarvisNotificationListenerService
import com.stark.jarvis.tile.JarvisQuickTileService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class JarvisVoiceAssistantService : Service(), SpeechListener {

    companion object {
        private const val TAG = "JarvisVoiceService"

        const val ACTION_START_STANDBY = "com.stark.jarvis.service.ACTION_START_STANDBY"
        const val ACTION_ACTIVATE_ASSISTANT = "com.stark.jarvis.service.ACTION_ACTIVATE_ASSISTANT"
        const val ACTION_RETURN_STANDBY = "com.stark.jarvis.service.ACTION_RETURN_STANDBY"
        const val ACTION_DISABLE_SERVICE = "com.stark.jarvis.service.ACTION_DISABLE_SERVICE"
        const val ACTION_RELOAD_WAKE_ENGINE = "com.stark.jarvis.service.ACTION_RELOAD_WAKE_ENGINE"
        const val ACTION_CONFIRM_YES = "com.stark.jarvis.service.ACTION_CONFIRM_YES"
        const val ACTION_CONFIRM_NO = "com.stark.jarvis.service.ACTION_CONFIRM_NO"
        const val ACTION_STOP_AGENT = "com.stark.jarvis.service.ACTION_STOP_AGENT"
        const val ACTION_RUN_TEXT_COMMAND = "com.stark.jarvis.service.ACTION_RUN_TEXT_COMMAND"

        const val EXTRA_FROM_BOOT = "extra_from_boot"
        const val EXTRA_TEXT_COMMAND = "extra_text_command"

        private const val CONFIRMATION_LISTEN_TIMEOUT_MS = 8000L
        private const val CONFIRMATION_MIN_WINDOW_MS = 6000L
        private const val TTS_TO_MIC_DELAY_MS = 450L
        private const val MAX_AGENT_STEPS = 20
        private const val MIN_AGENT_GEMINI_INTERVAL_MS = 4000L
        private const val MAX_CONSECUTIVE_SILENT_TURNS = 6
        private const val MAX_CONSECUTIVE_AUDIO_ERRORS = 3

        fun createStartIntent(context: Context, action: String = ACTION_START_STANDBY, fromBoot: Boolean = false): Intent {
            return Intent(context, JarvisVoiceAssistantService::class.java).apply {
                this.action = action
                putExtra(EXTRA_FROM_BOOT, fromBoot)
            }
        }
    }

    private sealed class ServiceConfirmation {
        data class Call(val displayName: String, val phoneNumber: String) : ServiceConfirmation()
        data class Sms(val displayName: String, val phoneNumber: String, val message: String) : ServiceConfirmation()
        data class FontScale(val scale: Float, val label: String) : ServiceConfirmation()
        data class Brightness(val targetPercent: Int) : ServiceConfirmation()
        data class OpenDeviceSettings(val label: String, val settingsAction: String) : ServiceConfirmation()
        data class AgentSensitive(
            val description: String,
            val deferred: CompletableDeferred<Boolean>
        ) : ServiceConfirmation()
        data class AgentContinue(
            val goal: String,
            val stepsExecuted: Int,
            val deferred: CompletableDeferred<Boolean>
        ) : ServiceConfirmation()
    }

    private data class ServiceSmsDraft(
        val displayName: String,
        val phoneNumber: String
    )

    private val contactsFallback = listOf(
        ContactItem("1", "Mickaël Hopes", "Propriétaire", "0612345678", "M"),
        ContactItem("2", "Alexandre Stark", "Ingénieur R&D", "0698765432", "A"),
        ContactItem("3", "Sarah Connor", "Sécurité", "0755443322", "S"),
        ContactItem("4", "Centre Commande", "Serveur", "0188990011", "C")
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var voskWakeController: VoskWakeController
    private lateinit var speechController: SpeechController
    private lateinit var torchController: TorchController
    private lateinit var hapticController: HapticController
    private lateinit var agentNotificationHelper: AgentNotificationHelper
    private var voiceEngine: VoiceEngine? = null
    private val commandDispatcher = CommandDispatcher(contactsFallback)
    private val geminiClient = GeminiClient()

    private var activeSessionLoop = false
    private var isShuttingDown = false
    private var currentSwitchState = AssistantSwitchState.DISABLED
    private var consecutiveSilentTurns = 0
    private var consecutiveAudioErrors = 0
    private var pendingConfirmation: ServiceConfirmation? = null
    private var pendingSmsDraft: ServiceSmsDraft? = null
    private var confirmationListenStartMs = 0L
    private var confirmationTimeoutRetries = 0
    private var pendingDelayedMicRunnable: Runnable? = null
    private var standbyStartJob: Job? = null
    private var agentJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        voskWakeController = VoskWakeController()
        speechController = SpeechController(this, this)
        torchController = TorchController(this)
        hapticController = HapticController(this)
        agentNotificationHelper = AgentNotificationHelper(this)
        voiceEngine = VoiceEngine(this) {
            Log.d(TAG, "TextToSpeech initialisé dans JarvisVoiceAssistantService")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) ?: false

        // Si le système redémarre un service STICKY alors que l'utilisateur l'a désactivé, stopper aussitôt.
        if (action == null && !JarvisRuntimeStore.isPermanentWakeEnabled(this)) {
            stopEverythingAndFinish(persistDisabled = false)
            return START_NOT_STICKY
        }

        if (action == ACTION_DISABLE_SERVICE) {
            stopEverythingAndFinish(persistDisabled = true)
            return START_NOT_STICKY
        }

        // Vérifier l'autorisation microphone obligatoire avant de promouvoir en FGS microphone
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            val msg = "Autorisation microphone requise pour la veille vocale permanente."
            JarvisRuntimeStore.appendLog(msg, isJarvis = true, isError = true)
            JarvisRuntimeStore.updateRuntime {
                it.copy(
                    isServiceRunning = false,
                    switchState = AssistantSwitchState.DISABLED,
                    isMicrophoneInUse = false,
                    lastErrorMessage = msg
                )
            }
            if (fromBoot) {
                WakeNotificationHelper.showBootRestoreNotification(this, msg)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (!promoteToForegroundSafely(fromBoot)) {
            return START_NOT_STICKY
        }

        JarvisRuntimeStore.setBootRestorePending(this, false)
        WakeNotificationHelper.dismissBootRestoreNotification(this)

        when (action) {
            ACTION_ACTIVATE_ASSISTANT -> {
                JarvisRuntimeStore.setPermanentWakeEnabled(this, true)
                activateAssistantMode(speakAck = true)
            }
            ACTION_RETURN_STANDBY -> {
                JarvisRuntimeStore.setPermanentWakeEnabled(this, true)
                returnToStandbyMode(spokenReply = "Retour en mode veille.")
            }
            ACTION_RELOAD_WAKE_ENGINE -> {
                if (currentSwitchState == AssistantSwitchState.STANDBY || currentSwitchState == AssistantSwitchState.DISABLED) {
                    enterStandbyMode(announce = false)
                }
            }
            ACTION_CONFIRM_YES -> {
                handleUiConfirmationDecision(confirm = true)
            }
            ACTION_CONFIRM_NO -> {
                handleUiConfirmationDecision(confirm = false)
            }
            ACTION_STOP_AGENT -> {
                stopAgentImmediately("Agent arrêté.")
            }
            ACTION_RUN_TEXT_COMMAND -> {
                val cmd = intent.getStringExtra(EXTRA_TEXT_COMMAND).orEmpty()
                if (cmd.isNotBlank()) {
                    standbyStartJob?.cancel()
                    voskWakeController.stop()
                    speechController.cancel()
                    activeSessionLoop = true
                    handleDispatchedCommand(cmd)
                }
            }
            ACTION_START_STANDBY, null -> {
                JarvisRuntimeStore.setPermanentWakeEnabled(this, true)
                enterStandbyMode(announce = false, fromBoot = fromBoot)
            }
        }

        return START_STICKY
    }

    private fun promoteToForegroundSafely(fromBoot: Boolean): Boolean {
        val initialDetail = "Initialisation de l'interrupteur vocal permanent..."
        val notification = WakeNotificationHelper.buildForegroundNotification(
            context = this,
            switchState = if (currentSwitchState == AssistantSwitchState.DISABLED) AssistantSwitchState.STANDBY else currentSwitchState,
            detailText = initialDetail,
            isMicActive = false
        )

        return try {
            startForeground(
                WakeNotificationHelper.WAKE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Démarrage FGS microphone bloqué par Android 12+/ColorOS", e)
            handleForegroundStartBlocked(fromBoot)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException FGS microphone", e)
            handleForegroundStartBlocked(fromBoot)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Erreur startForeground", e)
            handleForegroundStartBlocked(fromBoot)
            false
        }
    }

    private fun handleForegroundStartBlocked(fromBoot: Boolean) {
        val notice = "Android / OPPO a restreint le microphone après démarrage. Touchez pour réarmer J.A.R.V.I.S."
        JarvisRuntimeStore.setBootRestorePending(this, true)
        JarvisRuntimeStore.updateRuntime {
            it.copy(
                isServiceRunning = false,
                switchState = AssistantSwitchState.DISABLED,
                isMicrophoneInUse = false,
                bootRestoreNotice = if (fromBoot) notice else null,
                lastErrorMessage = notice
            )
        }
        WakeNotificationHelper.showBootRestoreNotification(this, notice)
        stopSelf()
    }

    // --- Modes Veille (Vosk hors ligne sans clé) & Actif (SpeechController) ---

    private fun enterStandbyMode(announce: Boolean, fromBoot: Boolean = false) {
        if (isShuttingDown) return
        standbyStartJob?.cancel()
        cancelPendingDelayedMic()
        clearConfirmationState(completeDeferredFalse = true)
        pendingSmsDraft = null
        activeSessionLoop = false
        consecutiveSilentTurns = 0
        consecutiveAudioErrors = 0
        speechController.cancel()

        currentSwitchState = AssistantSwitchState.STANDBY
        JarvisRuntimeStore.updateRuntime {
            it.copy(
                isServiceRunning = true,
                switchState = AssistantSwitchState.STANDBY,
                voiceState = VoiceState.IDLE,
                isMicrophoneInUse = false,
                wakeWordModeLabel = "Préparation du modèle vocal FR Vosk...",
                lastErrorMessage = null
            )
        }
        WakeNotificationHelper.updateForegroundNotification(
            context = this,
            switchState = AssistantSwitchState.STANDBY,
            detailText = "Préparation de la veille vocale hors ligne Vosk...",
            isMicActive = false
        )

        standbyStartJob = serviceScope.launch {
            val prepResult = VoskModelManager.ensureModelReady(this@JarvisVoiceAssistantService)
            if (isShuttingDown || activeSessionLoop) return@launch

            val modelPath = when (prepResult) {
                is VoskPrepareResult.Ready -> prepResult.modelPath
                is VoskPrepareResult.Failed -> {
                    val reason = prepResult.errorMessage
                    if (fromBoot) {
                        JarvisRuntimeStore.setBootRestorePending(this@JarvisVoiceAssistantService, true)
                        WakeNotificationHelper.showBootRestoreNotification(this@JarvisVoiceAssistantService, reason)
                    }
                    JarvisRuntimeStore.updateRuntime {
                        it.copy(
                            isServiceRunning = false,
                            switchState = AssistantSwitchState.DISABLED,
                            voiceState = VoiceState.ERROR,
                            isMicrophoneInUse = false,
                            bootRestoreNotice = if (fromBoot) reason else null,
                            lastErrorMessage = reason,
                            wakeWordModeLabel = "Modèle vocal FR manquant"
                        )
                    }
                    stopSelf()
                    return@launch
                }
            }

            val startResult = voskWakeController.start(
                modelPath = modelPath,
                onWakeWordDetected = {
                    mainHandler.post {
                        if (!isShuttingDown && currentSwitchState == AssistantSwitchState.STANDBY) {
                            hapticController.playStarkPulse()
                            JarvisRuntimeStore.appendLog("Mot d'activation « jarvis » détecté (Vosk hors ligne).", isJarvis = true)
                            // Laisser ~150 ms au HAL audio Android pour libérer complètement AudioRecord avant SpeechRecognizer
                            mainHandler.postDelayed({
                                if (!isShuttingDown && currentSwitchState == AssistantSwitchState.STANDBY) {
                                    activateAssistantMode(speakAck = true)
                                }
                            }, 150L)
                        }
                    }
                },
                onEngineError = { errorMsg ->
                    mainHandler.post {
                        JarvisRuntimeStore.appendLog(errorMsg, isJarvis = true, isError = true)
                        JarvisRuntimeStore.updateRuntime {
                            it.copy(
                                voiceState = VoiceState.ERROR,
                                switchState = AssistantSwitchState.DISABLED,
                                isMicrophoneInUse = false,
                                lastErrorMessage = errorMsg,
                                wakeWordModeLabel = "Moteur Vosk arrêté"
                            )
                        }
                        stopEverythingAndFinish(persistDisabled = false)
                    }
                }
            )

            when (startResult) {
                is VoskWakeStartResult.Started -> {
                    currentSwitchState = AssistantSwitchState.STANDBY
                    val detail = "${startResult.modeLabel} (sans clé, zéro réseau)"
                    JarvisRuntimeStore.updateRuntime {
                        it.copy(
                            isServiceRunning = true,
                            switchState = AssistantSwitchState.STANDBY,
                            voiceState = VoiceState.IDLE,
                            isMicrophoneInUse = true,
                            wakeWordModeLabel = startResult.modeLabel,
                            lastErrorMessage = null,
                            bootRestoreNotice = null
                        )
                    }
                    WakeNotificationHelper.updateForegroundNotification(
                        context = this@JarvisVoiceAssistantService,
                        switchState = AssistantSwitchState.STANDBY,
                        detailText = detail,
                        isMicActive = true
                    )
                    if (announce) {
                        JarvisRuntimeStore.appendLog("Veille locale armée : ${startResult.modeLabel}", isJarvis = true)
                    }
                    refreshQuickTile()
                }
                is VoskWakeStartResult.ModelMissing -> {
                    handleStandbyStartFailure(startResult.reason, fromBoot)
                }
                is VoskWakeStartResult.Failed -> {
                    handleStandbyStartFailure(startResult.reason, fromBoot)
                }
            }
        }
    }

    private fun handleStandbyStartFailure(reason: String, fromBoot: Boolean) {
        if (fromBoot) {
            JarvisRuntimeStore.setBootRestorePending(this, true)
            WakeNotificationHelper.showBootRestoreNotification(this, reason)
        }
        JarvisRuntimeStore.appendLog(reason, isJarvis = true, isError = true)
        JarvisRuntimeStore.updateRuntime {
            it.copy(
                isServiceRunning = false,
                switchState = AssistantSwitchState.DISABLED,
                voiceState = VoiceState.ERROR,
                isMicrophoneInUse = false,
                bootRestoreNotice = if (fromBoot) reason else null,
                lastErrorMessage = reason,
                wakeWordModeLabel = "Erreur veille Vosk"
            )
        }
        refreshQuickTile()
        stopSelf()
    }

    private fun refreshQuickTile() {
        try {
            TileService.requestListeningState(
                this,
                ComponentName(this, JarvisQuickTileService::class.java)
            )
        } catch (_: Exception) {
            // Ignorer si la tuile n'est pas encore épinglée dans les réglages rapides
        }
    }

    private fun activateAssistantMode(speakAck: Boolean) {
        if (isShuttingDown) return
        standbyStartJob?.cancel()
        cancelPendingDelayedMic()
        voskWakeController.stop() // Libère immédiatement le microphone de Vosk avant SpeechRecognizer
        activeSessionLoop = true
        consecutiveSilentTurns = 0
        consecutiveAudioErrors = 0
        currentSwitchState = AssistantSwitchState.ACTIVATED

        JarvisRuntimeStore.updateRuntime {
            it.copy(
                isServiceRunning = true,
                switchState = AssistantSwitchState.ACTIVATED,
                voiceState = if (speakAck) VoiceState.SPEAKING else VoiceState.LISTENING,
                isMicrophoneInUse = !speakAck,
                lastErrorMessage = null
            )
        }
        WakeNotificationHelper.updateForegroundNotification(
            context = this,
            switchState = AssistantSwitchState.ACTIVATED,
            detailText = "Mode actif — Dites votre commande ou « JARVIS DÉSACTIVE-TOI »",
            isMicActive = !speakAck
        )

        if (speakAck) {
            speakResponse("Je vous écoute.", restartLoopAfterDone = true)
        } else {
            startCommandListeningTurn()
        }
    }

    private fun returnToStandbyMode(spokenReply: String? = "Passage en mode veille.") {
        if (isShuttingDown) return
        standbyStartJob?.cancel()
        activeSessionLoop = false
        cancelPendingDelayedMic()
        clearConfirmationState(completeDeferredFalse = true)
        pendingSmsDraft = null
        agentJob?.cancel()
        agentJob = null
        agentNotificationHelper.dismiss()
        speechController.cancel()

        if (!spokenReply.isNullOrBlank()) {
            JarvisRuntimeStore.appendLog(spokenReply, isJarvis = true)
            currentSwitchState = AssistantSwitchState.STANDBY
            JarvisRuntimeStore.updateRuntime {
                it.copy(
                    switchState = AssistantSwitchState.STANDBY,
                    voiceState = VoiceState.SPEAKING,
                    isMicrophoneInUse = false,
                    isAgentRunning = false,
                    agentStatusText = ""
                )
            }
            speakResponse(spokenReply, restartLoopAfterDone = false) {
                enterStandbyMode(announce = false)
            }
        } else {
            enterStandbyMode(announce = false)
        }
    }

    private fun startCommandListeningTurn(extendedTimeoutMs: Long? = null) {
        if (!activeSessionLoop || isShuttingDown) return
        voskWakeController.stop()
        currentSwitchState = AssistantSwitchState.LISTENING
        JarvisRuntimeStore.updateRuntime {
            it.copy(
                isServiceRunning = true,
                switchState = AssistantSwitchState.LISTENING,
                voiceState = VoiceState.LISTENING,
                isMicrophoneInUse = true
            )
        }
        WakeNotificationHelper.updateForegroundNotification(
            context = this,
            switchState = AssistantSwitchState.LISTENING,
            detailText = "Micro actif (fr-FR) — Dites « JARVIS DÉSACTIVE-TOI » pour mettre en veille",
            isMicActive = true
        )
        speechController.startListening(extendedSilenceTimeoutMs = extendedTimeoutMs)
    }

    private fun scheduleNextListeningTurn(delayMs: Long = TTS_TO_MIC_DELAY_MS, extendedTimeoutMs: Long? = null) {
        cancelPendingDelayedMic()
        val runnable = Runnable {
            pendingDelayedMicRunnable = null
            if (!isShuttingDown && activeSessionLoop) {
                if ((pendingConfirmation != null || pendingSmsDraft != null) && confirmationListenStartMs == 0L) {
                    confirmationListenStartMs = SystemClock.elapsedRealtime()
                }
                startCommandListeningTurn(extendedTimeoutMs)
            }
        }
        pendingDelayedMicRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingDelayedMic() {
        pendingDelayedMicRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingDelayedMicRunnable = null
        }
    }

    private fun stopEverythingAndFinish(persistDisabled: Boolean) {
        isShuttingDown = true
        activeSessionLoop = false
        standbyStartJob?.cancel()
        cancelPendingDelayedMic()
        clearConfirmationState(completeDeferredFalse = true)
        pendingSmsDraft = null
        agentJob?.cancel()
        agentJob = null
        agentNotificationHelper.dismiss()
        voskWakeController.release()
        speechController.destroy()
        voiceEngine?.stop()

        if (persistDisabled) {
            JarvisRuntimeStore.setPermanentWakeEnabled(this, false)
            JarvisRuntimeStore.setBootRestorePending(this, false)
            WakeNotificationHelper.dismissBootRestoreNotification(this)
            JarvisRuntimeStore.appendLog("Interrupteur vocal permanent désactivé manuellement.", isJarvis = true)
        }

        currentSwitchState = AssistantSwitchState.DISABLED
        JarvisRuntimeStore.updateRuntime {
            it.copy(
                isServiceRunning = false,
                switchState = AssistantSwitchState.DISABLED,
                voiceState = VoiceState.IDLE,
                isMicrophoneInUse = false,
                isAgentRunning = false,
                agentStatusText = "",
                quotaStatusMessage = null
            )
        }

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // Ignorer si pas encore en foreground
        }
        stopSelf()
    }

    // --- SpeechListener Callbacks (Mode Actif uniquement) ---

    override fun onStateChanged(state: VoiceState) {
        if (isShuttingDown) return
        val nextSwitch = when {
            !activeSessionLoop -> currentSwitchState
            state == VoiceState.LISTENING -> AssistantSwitchState.LISTENING
            else -> AssistantSwitchState.ACTIVATED
        }
        currentSwitchState = nextSwitch
        JarvisRuntimeStore.updateRuntime {
            it.copy(
                voiceState = state,
                switchState = nextSwitch,
                isMicrophoneInUse = state == VoiceState.LISTENING || nextSwitch == AssistantSwitchState.STANDBY
            )
        }
    }

    override fun onSpeechPartial(partialText: String) {
        JarvisRuntimeStore.updateRuntime {
            it.copy(
                lastRecognizedText = partialText,
                voiceState = VoiceState.LISTENING,
                switchState = AssistantSwitchState.LISTENING,
                isMicrophoneInUse = true
            )
        }
    }

    override fun onSpeechResult(resultText: String) {
        cancelPendingDelayedMic()
        hapticController.playStarkPulse()
        consecutiveSilentTurns = 0
        consecutiveAudioErrors = 0

        JarvisRuntimeStore.updateRuntime {
            it.copy(
                lastRecognizedText = resultText,
                lastErrorMessage = null,
                voiceState = VoiceState.ANALYZING,
                switchState = AssistantSwitchState.ACTIVATED,
                isMicrophoneInUse = false
            )
        }
        JarvisRuntimeStore.appendLog("« $resultText »", isJarvis = false)

        val normInput = DeviceAssistantActions.normalizeForSearch(resultText)
        if (agentJob?.isActive == true && (normInput == "stop" || normInput == "arrete" || normInput == "stoppe")) {
            stopAgentImmediately("Agent arrêté.")
            return
        }

        // Priorité 0 : commande vocale de retour en veille même pendant une confirmation
        if (normInput == "jarvis desactive toi" || normInput == "desactive toi") {
            returnToStandbyMode("J.A.R.V.I.S. repasse en mode veille.")
            return
        }

        val activeConf = pendingConfirmation
        if (activeConf != null) {
            handleConfirmationResponse(resultText, activeConf)
            return
        }

        val draft = pendingSmsDraft
        if (draft != null) {
            handlePendingSmsBody(resultText, draft)
            return
        }

        handleDispatchedCommand(resultText)
    }

    override fun onError(errorMessage: String, errorCode: Int) {
        if (!activeSessionLoop || isShuttingDown) return

        val isNoSpeechOrTimeout = errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
            errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

        val activeConf = pendingConfirmation
        if (activeConf != null && isNoSpeechOrTimeout) {
            val elapsedMs = if (confirmationListenStartMs > 0L) {
                SystemClock.elapsedRealtime() - confirmationListenStartMs
            } else {
                CONFIRMATION_MIN_WINDOW_MS
            }

            if (errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                elapsedMs < CONFIRMATION_MIN_WINDOW_MS &&
                confirmationTimeoutRetries < 1
            ) {
                confirmationTimeoutRetries++
                scheduleNextListeningTurn(delayMs = 250L, extendedTimeoutMs = CONFIRMATION_LISTEN_TIMEOUT_MS)
                return
            }

            clearConfirmationState(completeDeferredFalse = true)
            JarvisRuntimeStore.updateRuntime {
                it.copy(voiceState = VoiceState.IDLE, lastErrorMessage = null, isMicrophoneInUse = false)
            }
            if (activeConf is ServiceConfirmation.AgentSensitive ||
                activeConf is ServiceConfirmation.AgentContinue
            ) return

            val cancelMsg = when (activeConf) {
                is ServiceConfirmation.Call -> "Je n'ai pas entendu de réponse, appel annulé."
                is ServiceConfirmation.Sms -> "Je n'ai pas entendu de réponse, SMS annulé."
                is ServiceConfirmation.FontScale -> "Je n'ai pas entendu de réponse, changement de police annulé."
                is ServiceConfirmation.Brightness -> "Je n'ai pas entendu de réponse, réglage de luminosité annulé."
                is ServiceConfirmation.OpenDeviceSettings -> "Je n'ai pas entendu de réponse, ouverture des paramètres annulée."
                is ServiceConfirmation.AgentSensitive,
                is ServiceConfirmation.AgentContinue -> "Je n'ai pas entendu de réponse, action annulée."
            }
            JarvisRuntimeStore.appendLog(cancelMsg, isJarvis = true)
            speakResponse(cancelMsg, restartLoopAfterDone = true)
            return
        }

        if (pendingSmsDraft != null && isNoSpeechOrTimeout) {
            pendingSmsDraft = null
            val cancelMsg = "Je n'ai pas entendu de réponse, SMS annulé."
            JarvisRuntimeStore.appendLog(cancelMsg, isJarvis = true)
            speakResponse(cancelMsg, restartLoopAfterDone = true)
            return
        }

        if (isNoSpeechOrTimeout) {
            consecutiveSilentTurns++
            if (consecutiveSilentTurns < MAX_CONSECUTIVE_SILENT_TURNS) {
                JarvisRuntimeStore.updateRuntime {
                    it.copy(
                        switchState = AssistantSwitchState.ACTIVATED,
                        voiceState = VoiceState.IDLE,
                        isMicrophoneInUse = false
                    )
                }
                scheduleNextListeningTurn(delayMs = 700L)
            } else {
                returnToStandbyMode("Aucune commande détectée, retour en mode veille.")
            }
            return
        }

        if (errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            val msg = "Permission microphone révoquée. Arrêt de la veille vocale."
            JarvisRuntimeStore.appendLog(msg, isJarvis = true, isError = true)
            stopEverythingAndFinish(persistDisabled = true)
            return
        }

        consecutiveAudioErrors++
        JarvisRuntimeStore.appendLog("Signal vocal interrompu : $errorMessage", isJarvis = true, isError = true)
        JarvisRuntimeStore.updateRuntime {
            it.copy(
                voiceState = VoiceState.ERROR,
                lastErrorMessage = errorMessage,
                isMicrophoneInUse = false
            )
        }
        if (consecutiveAudioErrors >= MAX_CONSECUTIVE_AUDIO_ERRORS) {
            returnToStandbyMode("Erreur audio répétée, retour en mode veille locale.")
        } else {
            scheduleNextListeningTurn(delayMs = 1100L)
        }
    }

    override fun onRmsChanged(rmsdB: Float) {
        // Garde l'état léger sans spammer StateFlow
    }

    // --- Routage CommandDispatcher en Mode Actif ---

    private fun handleDispatchedCommand(rawInput: String) {
        val batteryPct = readBatteryPercent()
        when (val result = commandDispatcher.dispatch(rawInput, batteryPct)) {
            is DispatchResult.ActivateAssistantAction -> {
                val msg = "J.A.R.V.I.S. est déjà activé et à votre écoute."
                JarvisRuntimeStore.appendLog(msg, isJarvis = true)
                speakResponse(msg, restartLoopAfterDone = true)
            }
            is DispatchResult.DeactivateAssistantAction -> {
                returnToStandbyMode("J.A.R.V.I.S. repasse en mode veille.")
            }
            is DispatchResult.StopAgentAction -> {
                if (agentJob?.isActive == true) {
                    stopAgentImmediately("Agent arrêté.")
                } else {
                    val reply = "Aucun agent actif en ce moment."
                    JarvisRuntimeStore.appendLog(reply, isJarvis = true)
                    speakResponse(reply, restartLoopAfterDone = true)
                }
            }
            is DispatchResult.TorchAction -> {
                val newState = torchController.setTorch(result.enable)
                JarvisRuntimeStore.updateRuntime { it.copy(torchActive = newState) }
                JarvisRuntimeStore.appendLog(result.spokenReply, isJarvis = true)
                speakResponse(result.spokenReply, restartLoopAfterDone = true)
            }
            is DispatchResult.ActionExecuted -> {
                if (result.isAlert != null) {
                    JarvisRuntimeStore.updateRuntime { it.copy(isAlertMode = result.isAlert) }
                }
                JarvisRuntimeStore.appendLog(result.logText, isJarvis = true)
                speakResponse(result.spokenReply, restartLoopAfterDone = true)
            }
            is DispatchResult.AccessibilityHome -> performGlobalA11y("Accueil", { it.performHome() }, "Retour à l'écran d'accueil.")
            is DispatchResult.AccessibilityBack -> performGlobalA11y("Retour", { it.performBack() }, "Retour en arrière.")
            is DispatchResult.AccessibilityNotifications -> performGlobalA11y("Notifications", { it.performNotifications() }, "Ouverture du volet de notifications.")
            is DispatchResult.AccessibilityQuickSettings -> performGlobalA11y("Réglages rapides", { it.performQuickSettings() }, "Ouverture des réglages rapides.")
            is DispatchResult.AccessibilityRecents -> performGlobalA11y("Applications récentes", { it.performRecents() }, "Affichage des applications récentes.")
            is DispatchResult.AccessibilityReadScreen -> handleReadScreen()
            is DispatchResult.AccessibilityClick -> handleA11yResult(requireA11y()?.clickByTextOrDescription(result.targetText))
            is DispatchResult.AccessibilityLongPress -> handleA11yResult(requireA11y()?.longPressByTextOrDescription(result.targetText))
            is DispatchResult.AccessibilitySwipe -> handleA11yResult(requireA11y()?.swipeGesture(result.direction))
            is DispatchResult.AccessibilityScroll -> {
                val s = requireA11y()
                handleA11yResult(if (result.down) s?.scrollDown() else s?.scrollUp())
            }
            is DispatchResult.AccessibilityType -> handleA11yResult(requireA11y()?.typeText(result.textToType))
            is DispatchResult.ReadNotificationsAction -> handleReadRecentNotifications()
            is DispatchResult.SetVolumeAction -> applyVolumeCommand(result)
            is DispatchResult.SetFontScaleAction -> promptFontScaleConfirmation(result.scale, result.label)
            is DispatchResult.SetBrightnessAction -> promptBrightnessConfirmation(result.percent, result.deltaPercent)
            is DispatchResult.OpenDeviceSettingsAction -> promptOpenSettingsConfirmation(result.label, result.settingsAction)
            is DispatchResult.CallLookupAction -> handleCallLookup(result.targetQuery)
            is DispatchResult.SmsComposeAction -> handleSmsCompose(result.explicitRecipient, result.explicitMessage, result.rawAfterTo)
            is DispatchResult.OpenAppAction -> handleOpenApp(result.appQuery)
            is DispatchResult.AgentGoal -> startMultiStepAgent(result.goal)
            is DispatchResult.GeminiQuery -> handleGeminiQuery(result.rawQuery)
        }
    }

    private fun handleGeminiQuery(query: String) {
        val apiKey = JarvisRuntimeStore.getGeminiApiKey(this)
        JarvisRuntimeStore.updateRuntime {
            it.copy(
                voiceState = VoiceState.ANALYZING,
                lastErrorMessage = null,
                quotaStatusMessage = null
            )
        }
        serviceScope.launch {
            val aiResult = geminiClient.generateReply(
                prompt = query,
                apiKey = apiKey,
                onRetryStatus = { quotaMsg ->
                    JarvisRuntimeStore.updateRuntime { it.copy(quotaStatusMessage = quotaMsg) }
                }
            )
            JarvisRuntimeStore.updateRuntime { it.copy(quotaStatusMessage = null) }
            when (aiResult) {
                is GeminiResult.Success -> {
                    JarvisRuntimeStore.appendLog(aiResult.text, isJarvis = true)
                    speakResponse(aiResult.text, restartLoopAfterDone = true)
                }
                is GeminiResult.Error -> {
                    JarvisRuntimeStore.updateRuntime {
                        it.copy(voiceState = VoiceState.ERROR, lastErrorMessage = aiResult.userMessage)
                    }
                    JarvisRuntimeStore.appendLog(aiResult.userMessage, isJarvis = true, isError = true)
                    speakResponse(aiResult.userMessage, restartLoopAfterDone = true)
                }
            }
        }
    }

    // --- Accessibilité & Agent multi-étapes ---

    private fun requireA11y(): JarvisAccessibilityService? {
        val s = JarvisAccessibilityService.instance
        if (s == null) {
            val msg = "Le service d'accessibilité J.A.R.V.I.S. n'est pas activé."
            JarvisRuntimeStore.appendLog(msg, isJarvis = true, isError = true)
            speakResponse(msg, restartLoopAfterDone = true)
        }
        return s
    }

    private fun performGlobalA11y(name: String, perform: (JarvisAccessibilityService) -> Boolean, spoken: String) {
        val s = requireA11y() ?: return
        if (perform(s)) {
            JarvisRuntimeStore.appendLog(spoken, isJarvis = true)
            speakResponse(spoken, restartLoopAfterDone = true)
        } else {
            val err = "Action système « $name » refusée par Android."
            JarvisRuntimeStore.appendLog(err, isJarvis = true, isError = true)
            speakResponse(err, restartLoopAfterDone = true)
        }
    }

    private fun handleReadScreen() {
        val s = requireA11y() ?: return
        val (readResult, summary) = s.buildSpokenScreenSummary()
        JarvisRuntimeStore.appendLog(summary, isJarvis = true, isError = readResult is ScreenReadResult.Blacklisted)
        speakResponse(summary, restartLoopAfterDone = true)
    }

    private fun handleReadRecentNotifications() {
        val (enabled, summary) = JarvisNotificationListenerService.buildSpokenNotificationsSummary(this)
        JarvisRuntimeStore.appendLog(summary, isJarvis = true, isError = !enabled)
        speakResponse(summary, restartLoopAfterDone = true)
    }

    private fun handleA11yResult(res: AccessibilityActionResult?) {
        if (res == null) return
        when (res) {
            is AccessibilityActionResult.Success -> {
                JarvisRuntimeStore.appendLog(res.message, isJarvis = true)
                speakResponse(res.message, restartLoopAfterDone = true)
            }
            is AccessibilityActionResult.Blacklisted -> {
                JarvisRuntimeStore.appendLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isJarvis = true, isError = true)
                speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, restartLoopAfterDone = true)
            }
            is AccessibilityActionResult.NotFound -> {
                JarvisRuntimeStore.appendLog(res.detail, isJarvis = true)
                speakResponse(res.detail, restartLoopAfterDone = true)
            }
            is AccessibilityActionResult.Failed -> {
                JarvisRuntimeStore.appendLog(res.detail, isJarvis = true, isError = true)
                speakResponse(res.detail, restartLoopAfterDone = true)
            }
        }
    }

    private fun startMultiStepAgent(goal: String) {
        val service = requireA11y() ?: return
        val apiKey = JarvisRuntimeStore.getGeminiApiKey(this)
        if (apiKey.isBlank()) {
            val err = "Aucune clé API Gemini configurée pour l'agent. Ouvrez Paramètres (⚙️)."
            JarvisRuntimeStore.appendLog(err, isJarvis = true, isError = true)
            speakResponse(err, restartLoopAfterDone = true)
            return
        }

        agentJob?.cancel()
        val history = mutableListOf<String>()

        agentJob = serviceScope.launch {
            var lastGeminiCallEndedMs = 0L
            JarvisRuntimeStore.updateRuntime {
                it.copy(
                    isAgentRunning = true,
                    agentStatusText = "Initialisation : $goal",
                    voiceState = VoiceState.ANALYZING,
                    lastErrorMessage = null,
                    quotaStatusMessage = null
                )
            }
            JarvisRuntimeStore.appendLog("Agent multi-étapes démarré : « $goal »", isJarvis = true)
            agentNotificationHelper.showAgentRunning(1, MAX_AGENT_STEPS, "Objectif : $goal")

            try {
                var batchStart = 0
                while (true) {
                    for (stepInBatch in 1..MAX_AGENT_STEPS) {
                        val step = batchStart + stepInBatch
                        ensureActive()
                        val screenRead = service.readScreenForAgent()
                        val (activePkg, rawCompactText) = when (screenRead) {
                            is ScreenReadResult.Blacklisted -> {
                                JarvisRuntimeStore.appendLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isJarvis = true, isError = true)
                                speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, restartLoopAfterDone = true)
                                return@launch
                            }
                            is ScreenReadResult.Unavailable -> JarvisAccessibilityService.currentForegroundPackage to "(vide)"
                            is ScreenReadResult.Success -> screenRead.packageName to screenRead.compactText
                        }

                        var compactText = rawCompactText
                        var screenshotBase64: String? = null
                        if (service.isTreePoorOrEmpty(screenRead)) {
                            when (val capture = service.captureReducedScreenForAgent()) {
                                is ScreenCaptureResult.Blacklisted -> {
                                    JarvisRuntimeStore.appendLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isJarvis = true, isError = true)
                                    speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, restartLoopAfterDone = true)
                                    return@launch
                                }
                                is ScreenCaptureResult.FlagSecureBlocked -> {
                                    compactText = "$rawCompactText\n[VISION REFUSÉE : ${capture.reason}]"
                                    JarvisRuntimeStore.appendLog(capture.reason, isJarvis = true)
                                }
                                is ScreenCaptureResult.Failed -> {
                                    compactText = "$rawCompactText\n[VISION INDISPONIBLE]"
                                }
                                is ScreenCaptureResult.Success -> {
                                    screenshotBase64 = capture.base64Jpeg
                                }
                            }
                        }

                        if (lastGeminiCallEndedMs > 0L) {
                            val remaining = MIN_AGENT_GEMINI_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastGeminiCallEndedMs)
                            if (remaining > 0L) {
                                delay(remaining)
                            }
                        }
                        ensureActive()

                        val decision = geminiClient.generateAgentStep(
                            goal = goal,
                            step = stepInBatch,
                            maxSteps = MAX_AGENT_STEPS,
                            activePackage = activePkg,
                            screenCompactText = compactText,
                            history = history,
                            apiKey = apiKey,
                            screenshotBase64 = screenshotBase64,
                            onRetryStatus = { quotaMsg ->
                                JarvisRuntimeStore.updateRuntime {
                                    it.copy(quotaStatusMessage = quotaMsg, agentStatusText = quotaMsg)
                                }
                            }
                        )
                        lastGeminiCallEndedMs = SystemClock.elapsedRealtime()
                        JarvisRuntimeStore.updateRuntime { it.copy(quotaStatusMessage = null) }
                        ensureActive()

                        val command = when (decision) {
                            is AgentDecisionResult.Error -> {
                                JarvisRuntimeStore.appendLog("Agent interrompu : ${decision.userMessage}", isJarvis = true, isError = true)
                                speakResponse(decision.userMessage, restartLoopAfterDone = true)
                                return@launch
                            }
                            is AgentDecisionResult.Step -> decision.command
                        }

                        val actionLabel = when (command) {
                            is AgentStepCommand.OpenApp -> "Ouvrir ${command.appName}"
                            is AgentStepCommand.ClickText -> "Toucher « ${command.targetText} »"
                            is AgentStepCommand.TapXY -> "Toucher (${command.xNorm.toInt()}, ${command.yNorm.toInt()})"
                            is AgentStepCommand.LongPress -> if (command.targetText.isNotBlank()) {
                                "Appui long sur « ${command.targetText} »"
                            } else {
                                "Appui long (${command.xNorm?.toInt() ?: 0}, ${command.yNorm?.toInt() ?: 0})"
                            }
                            is AgentStepCommand.Swipe -> "Glisser (${command.direction.ifBlank { "geste" }})"
                            is AgentStepCommand.Scroll -> if (command.down) "Défiler vers le bas" else "Défiler vers le haut"
                            is AgentStepCommand.TypeText -> "Saisir « ${command.text} »"
                            is AgentStepCommand.Back -> "Retour arrière"
                            is AgentStepCommand.Home -> "Retour accueil"
                            is AgentStepCommand.Wait -> "Attente"
                            is AgentStepCommand.Done -> command.message
                            is AgentStepCommand.AskUser -> command.question
                        }
                        agentNotificationHelper.showAgentRunning(stepInBatch, MAX_AGENT_STEPS, actionLabel)

                        if (isSensitiveAgentCommand(command)) {
                            val confirmed = requestAgentSensitiveConfirmation(actionLabel)
                            if (!confirmed) {
                                val cancelledMsg = "Action sensible annulée par sécurité."
                                JarvisRuntimeStore.appendLog(cancelledMsg, isJarvis = true)
                                speakResponse(cancelledMsg, restartLoopAfterDone = true)
                                return@launch
                            }
                        }

                        ensureActive()
                        when (command) {
                            is AgentStepCommand.Done -> {
                                JarvisRuntimeStore.appendLog("Agent terminé : ${command.message}", isJarvis = true)
                                speakResponse(command.message, restartLoopAfterDone = true)
                                return@launch
                            }
                            is AgentStepCommand.AskUser -> {
                                JarvisRuntimeStore.appendLog("Agent demande : ${command.question}", isJarvis = true)
                                speakResponse(command.question, restartLoopAfterDone = true)
                                return@launch
                            }
                            is AgentStepCommand.OpenApp -> {
                                when (val appLookup = DeviceAssistantActions.findLaunchableApp(this@JarvisVoiceAssistantService, command.appName)) {
                                    is AppLaunchLookupResult.Found -> {
                                        if (JarvisAccessibilityService.isPackageBlacklisted(appLookup.packageName)) {
                                            JarvisRuntimeStore.appendLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isJarvis = true, isError = true)
                                            speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, restartLoopAfterDone = true)
                                            return@launch
                                        }
                                        startActivity(appLookup.launchIntent)
                                        history.add("Étape $step : ouverture ${appLookup.appName}")
                                    }
                                    is AppLaunchLookupResult.Ambiguous -> {
                                        val msg = "Plusieurs applications correspondent : ${appLookup.candidates.joinToString(", ")}."
                                        JarvisRuntimeStore.appendLog(msg, isJarvis = true)
                                        speakResponse(msg, restartLoopAfterDone = true)
                                        return@launch
                                    }
                                    is AppLaunchLookupResult.NotFound -> history.add("Étape $step : ${command.appName} introuvable")
                                }
                            }
                            is AgentStepCommand.ClickText -> {
                                val r = service.clickByTextOrDescription(command.targetText)
                                if (r is AccessibilityActionResult.Blacklisted) {
                                    speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, restartLoopAfterDone = true)
                                    return@launch
                                }
                                history.add("Étape $step : $actionLabel")
                            }
                            is AgentStepCommand.TapXY -> {
                                val r = service.tapByNormalized(command.xNorm, command.yNorm)
                                if (r is AccessibilityActionResult.Blacklisted) {
                                    speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, restartLoopAfterDone = true)
                                    return@launch
                                }
                                history.add("Étape $step : $actionLabel")
                            }
                            is AgentStepCommand.LongPress -> {
                                val r = if (command.xNorm != null && command.yNorm != null) {
                                    service.longPressByNormalized(command.xNorm, command.yNorm)
                                } else {
                                    service.longPressByTextOrDescription(command.targetText)
                                }
                                if (r is AccessibilityActionResult.Blacklisted) {
                                    speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, restartLoopAfterDone = true)
                                    return@launch
                                }
                                history.add("Étape $step : $actionLabel")
                            }
                            is AgentStepCommand.Swipe -> {
                                val r = service.swipeGesture(
                                    direction = command.direction,
                                    x1Norm = command.x1Norm,
                                    y1Norm = command.y1Norm,
                                    x2Norm = command.x2Norm,
                                    y2Norm = command.y2Norm
                                )
                                if (r is AccessibilityActionResult.Blacklisted) {
                                    speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, restartLoopAfterDone = true)
                                    return@launch
                                }
                                history.add("Étape $step : $actionLabel")
                            }
                            is AgentStepCommand.Scroll -> {
                                if (command.down) service.scrollDown() else service.scrollUp()
                                history.add("Étape $step : $actionLabel")
                            }
                            is AgentStepCommand.TypeText -> {
                                service.typeText(command.text)
                                history.add("Étape $step : $actionLabel")
                            }
                            is AgentStepCommand.Back -> {
                                service.performBack()
                                history.add("Étape $step : retour arrière")
                            }
                            is AgentStepCommand.Home -> {
                                service.performHome()
                                history.add("Étape $step : retour accueil")
                            }
                            is AgentStepCommand.Wait -> delay(command.durationMs)
                        }
                        delay(1100L)
                    }

                    batchStart += MAX_AGENT_STEPS
                    val shouldContinue = requestAgentContinueConfirmation(goal, batchStart)
                    if (!shouldContinue) {
                        val stopMsg = "Mission arrêtée après $batchStart étapes."
                        JarvisRuntimeStore.appendLog(stopMsg, isJarvis = true)
                        speakResponse(stopMsg, restartLoopAfterDone = true)
                        return@launch
                    }
                    JarvisRuntimeStore.appendLog("Poursuite de la mission au-delà de $batchStart étapes.", isJarvis = true)
                }
            } catch (_: CancellationException) {
                // Arrêt volontaire
            } finally {
                agentNotificationHelper.dismiss()
                JarvisRuntimeStore.updateRuntime {
                    it.copy(isAgentRunning = false, agentStatusText = "", quotaStatusMessage = null)
                }
            }
        }
    }

    private fun stopAgentImmediately(reason: String?) {
        cancelPendingDelayedMic()
        clearConfirmationState(completeDeferredFalse = true)
        agentJob?.cancel()
        agentJob = null
        agentNotificationHelper.dismiss()
        JarvisRuntimeStore.updateRuntime {
            it.copy(isAgentRunning = false, agentStatusText = "", quotaStatusMessage = null)
        }
        if (!reason.isNullOrBlank()) {
            JarvisRuntimeStore.appendLog(reason, isJarvis = true)
            speakResponse(reason, restartLoopAfterDone = activeSessionLoop)
        }
    }

    private suspend fun requestAgentSensitiveConfirmation(description: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirmation = ServiceConfirmation.AgentSensitive(description, deferred)
        confirmationListenStartMs = 0L
        confirmationTimeoutRetries = 0
        JarvisRuntimeStore.updateRuntime {
            it.copy(
                lastErrorMessage = null,
                activeConfirmation = ConfirmationPromptUi(
                    title = "Action sensible détectée",
                    details = "$description • Dites « oui » / « non » ou touchez un bouton."
                )
            )
        }
        val prompt = "Action sensible détectée : $description. Confirmez ?"
        JarvisRuntimeStore.appendLog(prompt, isJarvis = true)
        speakResponse(prompt, restartLoopAfterDone = false) {
            scheduleNextListeningTurn(TTS_TO_MIC_DELAY_MS, CONFIRMATION_LISTEN_TIMEOUT_MS)
        }
        return try {
            deferred.await()
        } finally {
            if (pendingConfirmation is ServiceConfirmation.AgentSensitive) {
                clearConfirmationState(completeDeferredFalse = false)
            }
        }
    }

    private suspend fun requestAgentContinueConfirmation(goal: String, stepsExecuted: Int): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirmation = ServiceConfirmation.AgentContinue(goal, stepsExecuted, deferred)
        confirmationListenStartMs = 0L
        confirmationTimeoutRetries = 0
        JarvisRuntimeStore.updateRuntime {
            it.copy(
                lastErrorMessage = null,
                activeConfirmation = ConfirmationPromptUi(
                    title = "Mission longue ($stepsExecuted étapes)",
                    details = "Je continue ? • Dites « oui » / « non » ou touchez un bouton."
                )
            )
        }
        val prompt = "$stepsExecuted étapes effectuées. Je continue ?"
        JarvisRuntimeStore.appendLog(prompt, isJarvis = true)
        speakResponse(prompt, restartLoopAfterDone = false) {
            scheduleNextListeningTurn(TTS_TO_MIC_DELAY_MS, CONFIRMATION_LISTEN_TIMEOUT_MS)
        }
        return try {
            deferred.await()
        } finally {
            if (pendingConfirmation is ServiceConfirmation.AgentContinue) {
                clearConfirmationState(completeDeferredFalse = false)
            }
        }
    }

    private fun isSensitiveAgentCommand(command: AgentStepCommand): Boolean {
        if (command is AgentStepCommand.Done ||
            command is AgentStepCommand.AskUser ||
            command is AgentStepCommand.Wait ||
            command is AgentStepCommand.Scroll ||
            command is AgentStepCommand.Back ||
            command is AgentStepCommand.Home
        ) return false
        if (command.isSensitive) return true
        val payload = when (command) {
            is AgentStepCommand.ClickText -> "${command.targetText} ${command.reason}"
            is AgentStepCommand.LongPress -> "${command.targetText} ${command.reason}"
            is AgentStepCommand.TapXY -> command.reason
            is AgentStepCommand.Swipe -> "${command.direction} ${command.reason}"
            is AgentStepCommand.TypeText -> "${command.text} ${command.reason}"
            is AgentStepCommand.OpenApp -> "${command.appName} ${command.reason}"
            else -> command.reason
        }
        val norm = DeviceAssistantActions.normalizeForSearch(payload)
        return listOf(
            "envoyer", "envoi", "send",
            "payer", "paiement", "pay", "acheter", "achat", "virement",
            "supprimer", "effacer", "delete", "remove",
            "desinstaller", "installer", "install",
            "securite", "mot de passe", "password", "empreinte",
            "verrouillage", "code pin", "reinitialiser", "format",
            "administrateur"
        ).any { norm.contains(it) }
    }

    // --- Appels, SMS, Apps & Commandes locales directes ---

    private fun handleCallLookup(targetQuery: String) {
        val directNumber = DeviceAssistantActions.extractPhoneNumber(targetQuery)
        if (directNumber != null) {
            promptCallConfirmation(directNumber, directNumber, isRawNumber = true)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionInUi("Autorisation d'accès aux contacts requise dans l'application J.A.R.V.I.S.")
            return
        }
        when (val lookup = DeviceAssistantActions.searchContactByName(this, targetQuery)) {
            is ContactLookupResult.Found -> promptCallConfirmation(lookup.contact.name, lookup.contact.phoneNumber, isRawNumber = false)
            is ContactLookupResult.Ambiguous -> {
                val msg = "Plusieurs contacts correspondent : ${lookup.candidates.joinToString(", ") { it.name }}. Précisez."
                JarvisRuntimeStore.appendLog(msg, isJarvis = true)
                speakResponse(msg, restartLoopAfterDone = true)
            }
            is ContactLookupResult.NotFound -> {
                val msg = "Contact introuvable pour « $targetQuery »."
                JarvisRuntimeStore.appendLog(msg, isJarvis = true)
                speakResponse(msg, restartLoopAfterDone = true)
            }
        }
    }

    private fun handleSmsCompose(explicitRecipient: String, explicitMessage: String, rawAfterTo: String) {
        val hasDirectNumber = if (explicitRecipient.isNotBlank()) {
            DeviceAssistantActions.extractPhoneNumber(explicitRecipient) != null
        } else {
            DeviceAssistantActions.extractLeadingPhoneNumberAndRemainder(rawAfterTo) != null
        }
        if (!hasDirectNumber &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionInUi("Autorisation Contacts requise dans l'application J.A.R.V.I.S.")
            return
        }

        val resolved = DeviceAssistantActions.resolveSmsRecipientAndMessage(
            context = this,
            explicitRecipient = explicitRecipient,
            explicitMessage = explicitMessage,
            rawAfterRecipientPrep = rawAfterTo
        )
        when (val lookup = resolved.lookup) {
            is ContactLookupResult.Found -> {
                val msg = resolved.resolvedMessage.trim()
                if (msg.isBlank()) {
                    pendingSmsDraft = ServiceSmsDraft(lookup.contact.name, lookup.contact.phoneNumber)
                    val askMsg = "Quel message souhaitez-vous envoyer à ${lookup.contact.name} ?"
                    JarvisRuntimeStore.appendLog(askMsg, isJarvis = true)
                    speakResponse(askMsg, restartLoopAfterDone = false) {
                        scheduleNextListeningTurn(TTS_TO_MIC_DELAY_MS, CONFIRMATION_LISTEN_TIMEOUT_MS)
                    }
                } else {
                    promptSmsConfirmation(lookup.contact.name, lookup.contact.phoneNumber, msg)
                }
            }
            is ContactLookupResult.Ambiguous -> {
                val msg = "Plusieurs contacts correspondent : ${lookup.candidates.joinToString(", ") { it.name }}."
                JarvisRuntimeStore.appendLog(msg, isJarvis = true)
                speakResponse(msg, restartLoopAfterDone = true)
            }
            is ContactLookupResult.NotFound -> {
                val msg = "Destinataire introuvable dans vos contacts."
                JarvisRuntimeStore.appendLog(msg, isJarvis = true)
                speakResponse(msg, restartLoopAfterDone = true)
            }
        }
    }

    private fun handlePendingSmsBody(spokenBody: String, draft: ServiceSmsDraft) {
        pendingSmsDraft = null
        val norm = DeviceAssistantActions.normalizeForSearch(spokenBody)
        if (isNegativeConfirmation(norm)) {
            val reply = "SMS annulé."
            JarvisRuntimeStore.appendLog(reply, isJarvis = true)
            speakResponse(reply, restartLoopAfterDone = true)
            return
        }
        promptSmsConfirmation(draft.displayName, draft.phoneNumber, spokenBody.trim())
    }

    private fun handleOpenApp(appQuery: String) {
        when (val lookup = DeviceAssistantActions.findLaunchableApp(this, appQuery)) {
            is AppLaunchLookupResult.Found -> {
                if (JarvisAccessibilityService.isPackageBlacklisted(lookup.packageName)) {
                    JarvisRuntimeStore.appendLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isJarvis = true, isError = true)
                    speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, restartLoopAfterDone = true)
                    return
                }
                try {
                    startActivity(lookup.launchIntent)
                    val msg = "Ouverture de ${lookup.appName}."
                    JarvisRuntimeStore.appendLog(msg, isJarvis = true)
                    speakResponse(msg, restartLoopAfterDone = true)
                } catch (e: Exception) {
                    val err = "Impossible d'ouvrir ${lookup.appName}."
                    JarvisRuntimeStore.appendLog(err, isJarvis = true, isError = true)
                    speakResponse(err, restartLoopAfterDone = true)
                }
            }
            is AppLaunchLookupResult.Ambiguous -> {
                val msg = "Plusieurs applications correspondent : ${lookup.candidates.joinToString(", ")}."
                JarvisRuntimeStore.appendLog(msg, isJarvis = true)
                speakResponse(msg, restartLoopAfterDone = true)
            }
            is AppLaunchLookupResult.NotFound -> {
                val msg = "Application introuvable : $appQuery."
                JarvisRuntimeStore.appendLog(msg, isJarvis = true)
                speakResponse(msg, restartLoopAfterDone = true)
            }
        }
    }

    private fun applyVolumeCommand(action: DispatchResult.SetVolumeAction) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = if (action.isRingStream) AudioManager.STREAM_RING else AudioManager.STREAM_MUSIC
        val maxVol = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
        val currentPct = ((audioManager.getStreamVolume(stream).toFloat() / maxVol) * 100f).roundToInt()
        val targetPct = when {
            action.percent != null -> action.percent.coerceIn(0, 100)
            action.deltaPercent != null -> (currentPct + action.deltaPercent).coerceIn(0, 100)
            else -> currentPct
        }
        val targetIndex = ((targetPct / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
        try {
            audioManager.setStreamVolume(stream, targetIndex, AudioManager.FLAG_SHOW_UI)
            val streamName = if (action.isRingStream) "sonnerie" else "média"
            val msg = if (targetPct == 0) "Volume $streamName coupé." else "Volume $streamName réglé à $targetPct pour cent."
            JarvisRuntimeStore.appendLog(msg, isJarvis = true)
            speakResponse(msg, restartLoopAfterDone = true)
        } catch (_: SecurityException) {
            val err = "Impossible de modifier le volume (mode Ne pas déranger actif)."
            JarvisRuntimeStore.appendLog(err, isJarvis = true, isError = true)
            speakResponse(err, restartLoopAfterDone = true)
        }
    }

    // --- Confirmations OUI/NON ---

    private fun promptCallConfirmation(displayName: String, phoneNumber: String, isRawNumber: Boolean) {
        beginConfirmation(
            confirmation = ServiceConfirmation.Call(displayName, phoneNumber),
            title = "Appeler $displayName ?",
            details = "Numéro : $phoneNumber • Dites « oui » / « non » ou touchez un bouton.",
            spokenPrompt = if (isRawNumber) "J'appelle le $phoneNumber, confirmez ?" else "J'appelle $displayName, confirmez ?"
        )
    }

    private fun promptSmsConfirmation(displayName: String, phoneNumber: String, message: String) {
        beginConfirmation(
            confirmation = ServiceConfirmation.Sms(displayName, phoneNumber, message),
            title = "Envoyer un SMS à $displayName ?",
            details = "« $message » • Dites « oui » / « non » ou touchez un bouton.",
            spokenPrompt = "J'envoie un SMS à $displayName : $message. Confirmez ?"
        )
    }

    private fun promptFontScaleConfirmation(scale: Float, label: String) {
        beginConfirmation(
            confirmation = ServiceConfirmation.FontScale(scale, label),
            title = "Modifier la taille de police ?",
            details = "Nouvelle taille : $label • Dites « oui » / « non » ou touchez un bouton.",
            spokenPrompt = "Je règle la taille de police sur $label, confirmez ?"
        )
    }

    private fun promptBrightnessConfirmation(explicitPercent: Int?, deltaPercent: Int?) {
        val currentRaw = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (_: Exception) {
            128
        }
        val currentPct = ((currentRaw / 255f) * 100f).roundToInt().coerceIn(5, 100)
        val targetPercent = when {
            explicitPercent != null -> explicitPercent.coerceIn(1, 100)
            deltaPercent != null -> (currentPct + deltaPercent).coerceIn(5, 100)
            else -> currentPct
        }
        beginConfirmation(
            confirmation = ServiceConfirmation.Brightness(targetPercent),
            title = "Modifier la luminosité ?",
            details = "Luminosité cible : $targetPercent% • Dites « oui » / « non » ou touchez un bouton.",
            spokenPrompt = "Je règle la luminosité à $targetPercent pour cent, confirmez ?"
        )
    }

    private fun promptOpenSettingsConfirmation(label: String, settingsAction: String) {
        beginConfirmation(
            confirmation = ServiceConfirmation.OpenDeviceSettings(label, settingsAction),
            title = "Ouvrir les paramètres ?",
            details = "Écran : $label • Dites « oui » / « non » ou touchez un bouton.",
            spokenPrompt = "J'ouvre les $label, confirmez ?"
        )
    }

    private fun beginConfirmation(
        confirmation: ServiceConfirmation,
        title: String,
        details: String,
        spokenPrompt: String
    ) {
        cancelPendingDelayedMic()
        confirmationListenStartMs = 0L
        confirmationTimeoutRetries = 0
        pendingConfirmation = confirmation
        JarvisRuntimeStore.updateRuntime {
            it.copy(
                lastErrorMessage = null,
                activeConfirmation = ConfirmationPromptUi(title = title, details = details)
            )
        }
        JarvisRuntimeStore.appendLog(spokenPrompt, isJarvis = true)
        speakResponse(spokenPrompt, restartLoopAfterDone = false) {
            scheduleNextListeningTurn(TTS_TO_MIC_DELAY_MS, CONFIRMATION_LISTEN_TIMEOUT_MS)
        }
    }

    private fun handleUiConfirmationDecision(confirm: Boolean) {
        val action = pendingConfirmation ?: return
        clearConfirmationState(completeDeferredFalse = false)
        voiceEngine?.stop()
        speechController.cancel()
        if (confirm) {
            JarvisRuntimeStore.appendLog("Confirmation manuelle : OUI", isJarvis = true)
            executeConfirmedAction(action)
        } else {
            cancelConfirmedAction(action, unrecognized = false)
        }
    }

    private fun handleConfirmationResponse(spokenText: String, action: ServiceConfirmation) {
        clearConfirmationState(completeDeferredFalse = false)
        val norm = DeviceAssistantActions.normalizeForSearch(spokenText)
        when {
            isNegativeConfirmation(norm) -> cancelConfirmedAction(action, unrecognized = false)
            isAffirmativeConfirmation(norm) -> executeConfirmedAction(action)
            else -> cancelConfirmedAction(action, unrecognized = true)
        }
    }

    private fun cancelConfirmedAction(action: ServiceConfirmation, unrecognized: Boolean) {
        val prefix = if (unrecognized) "Confirmation non reconnue, " else ""
        val msg = when (action) {
            is ServiceConfirmation.Call -> "${prefix}appel annulé."
            is ServiceConfirmation.Sms -> "${prefix}SMS annulé."
            is ServiceConfirmation.FontScale -> "${prefix}changement de police annulé."
            is ServiceConfirmation.Brightness -> "${prefix}réglage de luminosité annulé."
            is ServiceConfirmation.OpenDeviceSettings -> "${prefix}ouverture des paramètres annulée."
            is ServiceConfirmation.AgentSensitive -> {
                if (!action.deferred.isCompleted) action.deferred.complete(false)
                return
            }
            is ServiceConfirmation.AgentContinue -> {
                if (!action.deferred.isCompleted) action.deferred.complete(false)
                return
            }
        }
        JarvisRuntimeStore.appendLog(msg, isJarvis = true)
        speakResponse(msg, restartLoopAfterDone = true)
    }

    private fun executeConfirmedAction(action: ServiceConfirmation) {
        when (action) {
            is ServiceConfirmation.Call -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissionInUi("Autorisation Appel requise dans l'application J.A.R.V.I.S.")
                    return
                }
                val ok = PhoneActions.makeCallStrict(this, action.phoneNumber)
                val msg = if (ok) "Appel vers ${action.displayName} en cours." else "Impossible de lancer l'appel."
                JarvisRuntimeStore.appendLog(msg, isJarvis = true, isError = !ok)
                speakResponse(msg, restartLoopAfterDone = true)
            }
            is ServiceConfirmation.Sms -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissionInUi("Autorisation SMS requise dans l'application J.A.R.V.I.S.")
                    return
                }
                val ok = PhoneActions.sendSmsStrict(this, action.phoneNumber, action.message)
                val msg = if (ok) "SMS envoyé à ${action.displayName}." else "Échec de l'envoi du SMS."
                JarvisRuntimeStore.appendLog(msg, isJarvis = true, isError = !ok)
                speakResponse(msg, restartLoopAfterDone = true)
            }
            is ServiceConfirmation.FontScale -> {
                if (!Settings.System.canWrite(this)) {
                    requestPermissionInUi("Autorisez la modification des paramètres système dans J.A.R.V.I.S.")
                } else {
                    val ok = try {
                        Settings.System.putFloat(contentResolver, Settings.System.FONT_SCALE, action.scale)
                    } catch (_: Exception) {
                        false
                    }
                    val msg = if (ok) "Taille de police réglée sur ${action.label}." else "Impossible de modifier la taille de police."
                    JarvisRuntimeStore.appendLog(msg, isJarvis = true, isError = !ok)
                    speakResponse(msg, restartLoopAfterDone = true)
                }
            }
            is ServiceConfirmation.Brightness -> {
                if (!Settings.System.canWrite(this)) {
                    requestPermissionInUi("Autorisez la modification des paramètres système dans J.A.R.V.I.S.")
                } else {
                    val ok = try {
                        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        val brightnessVal = ((action.targetPercent / 100f) * 255f).roundToInt().coerceIn(3, 255)
                        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightnessVal)
                    } catch (_: Exception) {
                        false
                    }
                    val msg = if (ok) "Luminosité réglée à ${action.targetPercent} pour cent." else "Impossible de modifier la luminosité."
                    JarvisRuntimeStore.appendLog(msg, isJarvis = true, isError = !ok)
                    speakResponse(msg, restartLoopAfterDone = true)
                }
            }
            is ServiceConfirmation.OpenDeviceSettings -> {
                val candidates = if (action.settingsAction == Intent.ACTION_POWER_USAGE_SUMMARY) {
                    listOf(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_BATTERY_SAVER_SETTINGS, Settings.ACTION_SETTINGS)
                } else {
                    listOf(action.settingsAction, Settings.ACTION_SETTINGS)
                }
                var launched = false
                for (act in candidates) {
                    try {
                        startActivity(Intent(act).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        launched = true
                        break
                    } catch (_: Exception) {
                        // Fallback suivant
                    }
                }
                val msg = if (launched) "Ouverture des ${action.label}." else "Impossible d'ouvrir les ${action.label}."
                JarvisRuntimeStore.appendLog(msg, isJarvis = true, isError = !launched)
                speakResponse(msg, restartLoopAfterDone = true)
            }
            is ServiceConfirmation.AgentSensitive -> {
                if (!action.deferred.isCompleted) action.deferred.complete(true)
            }
            is ServiceConfirmation.AgentContinue -> {
                if (!action.deferred.isCompleted) action.deferred.complete(true)
            }
        }
    }

    private fun requestPermissionInUi(reason: String) {
        JarvisRuntimeStore.appendLog(reason, isJarvis = true, isError = true)
        JarvisRuntimeStore.updateRuntime { it.copy(lastErrorMessage = reason) }
        speakResponse(reason, restartLoopAfterDone = false) {
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            } catch (_: Exception) {
                // En arrière-plan sur Android 12+, l'utilisateur peut ouvrir depuis la notification
            }
            enterStandbyMode(announce = false)
        }
    }

    private fun clearConfirmationState(completeDeferredFalse: Boolean) {
        val cur = pendingConfirmation
        if (completeDeferredFalse && cur is ServiceConfirmation.AgentSensitive && !cur.deferred.isCompleted) {
            cur.deferred.complete(false)
        }
        if (completeDeferredFalse && cur is ServiceConfirmation.AgentContinue && !cur.deferred.isCompleted) {
            cur.deferred.complete(false)
        }
        pendingConfirmation = null
        confirmationListenStartMs = 0L
        confirmationTimeoutRetries = 0
        JarvisRuntimeStore.updateRuntime { it.copy(activeConfirmation = null) }
    }

    private fun isAffirmativeConfirmation(normText: String): Boolean {
        if (isNegativeConfirmation(normText)) return false
        val tokens = setOf(
            "oui", "ouais", "ok", "okay", "d accord", "daccord",
            "confirme", "je confirme", "vas y", "vasy", "affirmatif", "exact", "c est ca", "envoie", "appelle"
        )
        return normText in tokens ||
            normText.startsWith("oui ") ||
            normText.startsWith("ouais ") ||
            normText.startsWith("vas y") ||
            normText.startsWith("confirme") ||
            normText.startsWith("ok ")
    }

    private fun isNegativeConfirmation(normText: String): Boolean {
        val tokens = setOf("non", "nan", "annule", "annuler", "stop", "arrete", "pas question", "negatif", "refuse", "laisse tomber")
        return normText in tokens ||
            normText.startsWith("non ") ||
            normText.startsWith("annul") ||
            normText.startsWith("stop")
    }

    private fun speakResponse(
        text: String,
        restartLoopAfterDone: Boolean,
        customOnDone: (() -> Unit)? = null
    ) {
        JarvisRuntimeStore.updateRuntime {
            it.copy(
                voiceState = VoiceState.SPEAKING,
                isMicrophoneInUse = false
            )
        }
        val engine = voiceEngine
        if (engine == null) {
            customOnDone?.invoke()
            if (restartLoopAfterDone && activeSessionLoop && !isShuttingDown) {
                scheduleNextListeningTurn()
            }
            return
        }
        engine.speak(
            text = text,
            onStart = {
                JarvisRuntimeStore.updateRuntime {
                    it.copy(voiceState = VoiceState.SPEAKING, isMicrophoneInUse = false)
                }
            },
            onDone = {
                JarvisRuntimeStore.updateRuntime {
                    it.copy(voiceState = VoiceState.IDLE)
                }
                customOnDone?.invoke()
                if (restartLoopAfterDone && activeSessionLoop && !isShuttingDown) {
                    scheduleNextListeningTurn()
                }
            }
        )
    }

    private fun readBatteryPercent(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) return (level * 100) / scale
        }
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val cap = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return if (cap in 0..100) cap else 88
    }

    override fun onDestroy() {
        isShuttingDown = true
        activeSessionLoop = false
        standbyStartJob?.cancel()
        cancelPendingDelayedMic()
        clearConfirmationState(completeDeferredFalse = true)
        agentJob?.cancel()
        agentNotificationHelper.dismiss()
        voskWakeController.release()
        speechController.destroy()
        voiceEngine?.shutdown()
        voiceEngine = null
        serviceScope.cancel()

        JarvisRuntimeStore.updateRuntime {
            it.copy(
                isServiceRunning = false,
                switchState = AssistantSwitchState.DISABLED,
                voiceState = VoiceState.IDLE,
                isMicrophoneInUse = false,
                isAgentRunning = false,
                agentStatusText = ""
            )
        }
        super.onDestroy()
    }
}

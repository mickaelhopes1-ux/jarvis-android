package com.stark.jarvis

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
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
import com.stark.jarvis.controllers.OppoPowerHelper
import com.stark.jarvis.controllers.PhoneActions
import com.stark.jarvis.controllers.SpeechController
import com.stark.jarvis.controllers.SpeechListener
import com.stark.jarvis.controllers.TorchController
import com.stark.jarvis.controllers.VoiceEngine
import com.stark.jarvis.controllers.VoskModelManager
import com.stark.jarvis.controllers.VoskPrepareResult
import com.stark.jarvis.controllers.WakeNotificationHelper
import com.stark.jarvis.data.AssistantSwitchState
import com.stark.jarvis.data.ConfirmationPromptUi
import com.stark.jarvis.data.ContactItem
import com.stark.jarvis.data.JarvisLog
import com.stark.jarvis.data.JarvisRuntimeStore
import com.stark.jarvis.data.JarvisTelemetry
import com.stark.jarvis.data.VoiceState
import com.stark.jarvis.data.VoskDownloadStage
import com.stark.jarvis.data.VoskModelState
import com.stark.jarvis.notifications.JarvisNotificationListenerService
import com.stark.jarvis.screens.MainScaffold
import com.stark.jarvis.service.JarvisVoiceAssistantService
import com.stark.jarvis.theme.JarvisTheme
import com.stark.jarvis.tile.JarvisQuickTileService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), SpeechListener {

    private companion object {
        const val CONFIRMATION_LISTEN_TIMEOUT_MS = 8000L
        const val CONFIRMATION_MIN_WINDOW_MS = 6000L
        const val TTS_TO_MIC_DELAY_MS = 450L
        const val MAX_AGENT_STEPS = 20
        const val MIN_AGENT_GEMINI_INTERVAL_MS = 4000L
    }

    private sealed class PendingConfirmation {
        data class Call(val displayName: String, val phoneNumber: String) : PendingConfirmation()
        data class Sms(val displayName: String, val phoneNumber: String, val message: String) : PendingConfirmation()
        data class FontScale(val scale: Float, val label: String) : PendingConfirmation()
        data class Brightness(val targetPercent: Int) : PendingConfirmation()
        data class OpenDeviceSettings(val label: String, val settingsAction: String) : PendingConfirmation()
        data class AgentSensitive(
            val description: String,
            val deferred: CompletableDeferred<Boolean>
        ) : PendingConfirmation()
        data class AgentContinue(
            val goal: String,
            val stepsExecuted: Int,
            val deferred: CompletableDeferred<Boolean>
        ) : PendingConfirmation()
    }

    private data class PendingSmsMessageInput(
        val displayName: String,
        val phoneNumber: String
    )

    private sealed class PendingPermissionResume {
        data class ResolveCallAfterContacts(val targetQuery: String) : PendingPermissionResume()
        data class ResolveSmsAfterContacts(
            val explicitRecipient: String,
            val explicitMessage: String,
            val rawAfterTo: String
        ) : PendingPermissionResume()
        data class ExecuteConfirmedCall(val displayName: String, val phoneNumber: String) : PendingPermissionResume()
        data class ExecuteConfirmedSms(val displayName: String, val phoneNumber: String, val message: String) : PendingPermissionResume()
    }

    private sealed class PendingWriteSettingsResume {
        data class FontScale(val scale: Float, val label: String) : PendingWriteSettingsResume()
        data class Brightness(val targetPercent: Int) : PendingWriteSettingsResume()
    }

    private lateinit var torchController: TorchController
    private lateinit var hapticController: HapticController
    private lateinit var agentNotificationHelper: AgentNotificationHelper
    private var voiceEngine: VoiceEngine? = null
    private var speechController: SpeechController? = null

    private var batteryReceiver: BroadcastReceiver? = null
    private var stopAgentReceiver: BroadcastReceiver? = null
    private val confirmationHandler = Handler(Looper.getMainLooper())
    private var pendingDelayedMicRunnable: Runnable? = null

    // Données de contacts prioritaires pour les onglets Appels / SMS manuels
    private val contacts = listOf(
        ContactItem("1", "Mickaël Hopes", "Propriétaire", "0612345678", "M"),
        ContactItem("2", "Alexandre Stark", "Ingénieur R&D", "0698765432", "A"),
        ContactItem("3", "Sarah Connor", "Sécurité", "0755443322", "S"),
        ContactItem("4", "Centre Commande", "Serveur", "0188990011", "C")
    )

    private val commandDispatcher = CommandDispatcher(contacts)
    private val geminiClient = GeminiClient()
    private lateinit var prefs: SharedPreferences

    // États de sécurité, agent & reprise après permission
    private var pendingConfirmation: PendingConfirmation? = null
    private var pendingSmsMessageInput: PendingSmsMessageInput? = null
    private var pendingPermissionResume: PendingPermissionResume? = null
    private var pendingWriteSettingsResume: PendingWriteSettingsResume? = null
    private var confirmationListenStartMs: Long = 0L
    private var confirmationTimeoutRetries: Int = 0
    private var agentJob: Job? = null

    // États Compose découplés de l'interface
    private val telemetryState = mutableStateOf(JarvisTelemetry())
    private val logsState = mutableStateListOf<JarvisLog>()
    private val apiKeyState = mutableStateOf("")
    private val voskModelState = mutableStateOf(VoskModelState())
    private val isBatteryOptimizationIgnoredState = mutableStateOf(false)
    private val cameraPermissionState = mutableStateOf(false)

    private var pendingEnableWakeAfterMicPermission = false
    private var pendingActivateAfterMicPermission = false
    private var pendingTileListenAfterMicPermission = false

    // Permission Microphone (au moment du besoin)
    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val enableWake = pendingEnableWakeAfterMicPermission
        val activateNow = pendingActivateAfterMicPermission
        val listenFromTile = pendingTileListenAfterMicPermission
        pendingEnableWakeAfterMicPermission = false
        pendingActivateAfterMicPermission = false
        pendingTileListenAfterMicPermission = false

        if (isGranted) {
            telemetryState.value = telemetryState.value.copy(lastErrorMessage = null)
            if (activateNow) {
                startPermanentWakeService(JarvisVoiceAssistantService.ACTION_ACTIVATE_ASSISTANT)
            } else if (enableWake) {
                requestNotificationPermissionIfNeeded()
                startPermanentWakeService(JarvisVoiceAssistantService.ACTION_START_STANDBY)
            } else if (listenFromTile) {
                triggerQuickTileListening()
            } else {
                speechController?.startListening(
                    extendedSilenceTimeoutMs = if (pendingConfirmation != null) CONFIRMATION_LISTEN_TIMEOUT_MS else null
                )
            }
        } else {
            if (enableWake || activateNow) {
                JarvisRuntimeStore.setPermanentWakeEnabled(this, false)
                telemetryState.value = telemetryState.value.copy(
                    isPermanentWakeEnabled = false,
                    switchState = AssistantSwitchState.DISABLED,
                    isMicrophoneInUse = false
                )
            }
            clearConfirmationState(completeDeferredFalse = true)
            val msg = "Permission microphone refusée. Autorisez le micro pour parler à J.A.R.V.I.S."
            reportPermissionDenied(msg)
        }
    }

    // Permission Lecture des Contacts (au moment du besoin)
    private val requestContactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val resume = pendingPermissionResume
        pendingPermissionResume = null
        if (isGranted) {
            when (resume) {
                is PendingPermissionResume.ResolveCallAfterContacts -> handleCallLookup(resume.targetQuery)
                is PendingPermissionResume.ResolveSmsAfterContacts -> handleSmsCompose(
                    resume.explicitRecipient,
                    resume.explicitMessage,
                    resume.rawAfterTo
                )
                else -> Unit
            }
        } else {
            val msg = "Permission d'accès aux contacts refusée. Je ne peux pas rechercher ce contact dans votre répertoire."
            reportPermissionDenied(msg)
        }
    }

    // Permission Appel Téléphonique (au moment du besoin, après confirmation "oui")
    private val requestCallPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val resume = pendingPermissionResume
        pendingPermissionResume = null
        if (isGranted) {
            if (resume is PendingPermissionResume.ExecuteConfirmedCall) {
                executeConfirmedCall(resume.displayName, resume.phoneNumber)
            }
        } else {
            val msg = "Permission d'appel téléphonique refusée. L'appel n'a pas été lancé."
            reportPermissionDenied(msg)
        }
    }

    // Permission Envoi SMS (au moment du besoin, après confirmation "oui")
    private val requestSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val resume = pendingPermissionResume
        pendingPermissionResume = null
        if (isGranted) {
            if (resume is PendingPermissionResume.ExecuteConfirmedSms) {
                executeConfirmedSms(resume.displayName, resume.phoneNumber, resume.message)
            }
        } else {
            val msg = "Permission d'envoi de SMS refusée. Le SMS n'a pas été envoyé."
            reportPermissionDenied(msg)
        }
    }

    // Permission Caméra (au moment d'ouvrir l'onglet Caméra)
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        cameraPermissionState.value = isGranted
        if (!isGranted) {
            val msg = "Permission caméra refusée. Le scanner optique ne peut pas démarrer."
            reportPermissionDenied(msg)
        }
    }

    // Permission Notifications (Android 13+ uniquement)
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && isWakeServiceActive()) {
            val runtime = JarvisRuntimeStore.runtimeState.value
            WakeNotificationHelper.updateForegroundNotification(
                context = this,
                switchState = runtime.switchState,
                detailText = runtime.wakeWordModeLabel,
                isMicActive = runtime.isMicrophoneInUse
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val savedApiKey = JarvisRuntimeStore.getGeminiApiKey(this)
        val permanentEnabled = JarvisRuntimeStore.isPermanentWakeEnabled(this)
        val launchedFromTile = intent?.getBooleanExtra(JarvisQuickTileService.EXTRA_START_LISTENING_FROM_TILE, false) == true

        apiKeyState.value = savedApiKey
        VoskModelManager.refreshPublishedStatus(this)
        voskModelState.value = JarvisRuntimeStore.voskState.value
        isBatteryOptimizationIgnoredState.value = OppoPowerHelper.isIgnoringBatteryOptimizations(this)
        cameraPermissionState.value = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        torchController = TorchController(this)
        hapticController = HapticController(this)
        agentNotificationHelper = AgentNotificationHelper(this)
        WakeNotificationHelper.ensureChannels(this)

        telemetryState.value = telemetryState.value.copy(
            hasApiKey = savedApiKey.isNotBlank(),
            isVoskModelReady = voskModelState.value.stage == VoskDownloadStage.READY,
            voskModelStatusText = voskModelState.value.statusText,
            isPermanentWakeEnabled = permanentEnabled,
            switchState = if (permanentEnabled) {
                JarvisRuntimeStore.runtimeState.value.switchState.let {
                    if (it == AssistantSwitchState.DISABLED) AssistantSwitchState.STANDBY else it
                }
            } else {
                AssistantSwitchState.DISABLED
            },
            isAccessibilityEnabled = JarvisAccessibilityService.isServiceEnabled(this),
            isNotificationListenerEnabled = JarvisNotificationListenerService.isServiceEnabled(this)
        )

        // Initialisation de SpeechController avec l'interface SpeechListener implémentée par l'Activity
        speechController = SpeechController(this, this)

        // Initialisation de la synthèse vocale TextToSpeech
        voiceEngine = VoiceEngine(this) {
            if (!launchedFromTile && !JarvisRuntimeStore.isPermanentWakeEnabled(this) && !isWakeServiceActive()) {
                voiceEngine?.speak("Bonjour Mickaël. Systèmes J.A.R.V.I.S. en ligne sur votre appareil. Je suis à votre écoute.")
            }
        }

        // Synchronisation du journal partagé Service <-> Interface
        logsState.clear()
        logsState.addAll(JarvisRuntimeStore.logsState.value)

        lifecycleScope.launch {
            JarvisRuntimeStore.logsState.collectLatest { sharedLogs ->
                logsState.clear()
                logsState.addAll(sharedLogs)
            }
        }

        lifecycleScope.launch {
            JarvisRuntimeStore.voskState.collectLatest { vs ->
                voskModelState.value = vs
                telemetryState.value = telemetryState.value.copy(
                    isVoskModelReady = vs.stage == VoskDownloadStage.READY,
                    voskModelStatusText = vs.statusText
                )
            }
        }

        // Téléchargement automatique du petit modèle français Vosk au premier lancement (sans compte ni clé)
        if (!VoskModelManager.isModelReady(this)) {
            lifecycleScope.launch {
                val res = VoskModelManager.ensureModelReady(this@MainActivity, forceRetry = false)
                if (res is VoskPrepareResult.Ready && isWakeServiceActive()) {
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        JarvisVoiceAssistantService.createStartIntent(
                            this@MainActivity,
                            JarvisVoiceAssistantService.ACTION_RELOAD_WAKE_ENGINE
                        )
                    )
                }
            }
        }

        lifecycleScope.launch {
            JarvisRuntimeStore.runtimeState.collectLatest { snapshot ->
                val persistedEnabled = JarvisRuntimeStore.isPermanentWakeEnabled(this@MainActivity)
                if (snapshot.isServiceRunning) {
                    telemetryState.value = telemetryState.value.copy(
                        switchState = snapshot.switchState,
                        isPermanentWakeEnabled = persistedEnabled,
                        voiceState = snapshot.voiceState,
                        isMicrophoneInUse = snapshot.isMicrophoneInUse,
                        wakeWordModeLabel = snapshot.wakeWordModeLabel,
                        lastRecognizedText = snapshot.lastRecognizedText.ifBlank { telemetryState.value.lastRecognizedText },
                        lastErrorMessage = snapshot.lastErrorMessage,
                        quotaStatusMessage = snapshot.quotaStatusMessage,
                        torchActive = snapshot.torchActive,
                        isAlertMode = snapshot.isAlertMode,
                        activeConfirmation = snapshot.activeConfirmation,
                        isAgentRunning = snapshot.isAgentRunning,
                        agentStatusText = snapshot.agentStatusText,
                        bootRestoreNotice = snapshot.bootRestoreNotice
                    )
                } else {
                    telemetryState.value = telemetryState.value.copy(
                        switchState = AssistantSwitchState.DISABLED,
                        isPermanentWakeEnabled = persistedEnabled,
                        isMicrophoneInUse = telemetryState.value.voiceState == VoiceState.LISTENING,
                        wakeWordModeLabel = snapshot.wakeWordModeLabel,
                        bootRestoreNotice = snapshot.bootRestoreNotice,
                        lastErrorMessage = snapshot.lastErrorMessage ?: telemetryState.value.lastErrorMessage
                    )
                }
            }
        }

        // Surveillance de la batterie de l'appareil
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                    val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 88

                    telemetryState.value = telemetryState.value.copy(
                        batteryPercent = percent,
                        isCharging = isCharging
                    )
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // Récepteur du bouton STOP dans la notification permanente de l'agent
        stopAgentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AgentNotificationHelper.ACTION_STOP_AGENT) {
                    stopAgentImmediately("Agent arrêté depuis la notification.")
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            stopAgentReceiver,
            IntentFilter(AgentNotificationHelper.ACTION_STOP_AGENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val hadResumeExtra = intent?.hasExtra(WakeNotificationHelper.EXTRA_RESUME_WAKE_SERVICE) == true
        handleBootResumeIntent(intent)

        // Si l'utilisateur avait laissé l'interrupteur permanent ON et que le service n'est pas encore lancé, réarmer proprement
        if (JarvisRuntimeStore.isPermanentWakeEnabled(this) && !isWakeServiceActive() && !hadResumeExtra) {
            if (hasRecordAudioPermission()) {
                startPermanentWakeService(JarvisVoiceAssistantService.ACTION_START_STANDBY)
            }
        }

        handleQuickTileLaunchIntent(intent)

        // Liaison de l'interface Jetpack Compose
        setContent {
            val telemetry by telemetryState
            val logs = logsState
            val currentApiKey by apiKeyState
            val voskState by voskModelState
            val isBatteryOptimizationIgnored by isBatteryOptimizationIgnoredState
            val hasCameraPermission by cameraPermissionState

            DisposableEffect(Unit) {
                onDispose {
                    // Nettoyage au démontage Compose si nécessaire
                }
            }

            JarvisTheme {
                MainScaffold(
                    context = this@MainActivity,
                    telemetry = telemetry,
                    logs = logs,
                    contacts = contacts,
                    isListening = telemetry.voiceState == VoiceState.LISTENING,
                    isSpeaking = telemetry.voiceState == VoiceState.SPEAKING,
                    onToggleMic = { handleMicToggle() },
                    onToggleTorch = { handleToggleTorch() },
                    onTriggerHaptic = { hapticController.playStarkPulse() },
                    onToggleAlert = { handleToggleAlert() },
                    onToggleDayNight = { handleToggleDayNight() },
                    onQuickAction = { action -> handleQuickAction(action) },
                    onClearError = { handleClearError() },
                    currentApiKey = currentApiKey,
                    onSaveApiKey = { newKey -> handleSaveApiKey(newKey) },
                    hasCameraPermission = hasCameraPermission,
                    onRequestCameraPermission = { requestCameraPermissionIfNeeded() },
                    onConfirmAction = { handleUiConfirmAction() },
                    onCancelAction = { handleUiCancelAction() },
                    onOpenAccessibilitySettings = { handleOpenAccessibilitySettings() },
                    onOpenNotificationListenerSettings = { handleOpenNotificationListenerSettings() },
                    onStopAgent = { stopAgentImmediately("Agent arrêté par l'utilisateur.") },
                    voskModelState = voskState,
                    isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                    onRetryVoskDownload = { handleRetryVoskDownload() },
                    onTogglePermanentWake = { enabled -> handleTogglePermanentWake(enabled) },
                    onToggleActiveStandby = { handleToggleActiveStandby() },
                    onOpenBatteryOptimization = { handleOpenBatteryOptimization() },
                    onOpenOppoAutoStart = { handleOpenOppoAutoStart() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleBootResumeIntent(intent)
        handleQuickTileLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        VoskModelManager.refreshPublishedStatus(this)
        isBatteryOptimizationIgnoredState.value = OppoPowerHelper.isIgnoringBatteryOptimizations(this)
        telemetryState.value = telemetryState.value.copy(
            isAccessibilityEnabled = JarvisAccessibilityService.isServiceEnabled(this),
            isNotificationListenerEnabled = JarvisNotificationListenerService.isServiceEnabled(this),
            isPermanentWakeEnabled = JarvisRuntimeStore.isPermanentWakeEnabled(this)
        )
        val writeResume = pendingWriteSettingsResume
        if (writeResume != null) {
            pendingWriteSettingsResume = null
            if (Settings.System.canWrite(this)) {
                when (writeResume) {
                    is PendingWriteSettingsResume.FontScale -> applyFontScale(writeResume.scale, writeResume.label)
                    is PendingWriteSettingsResume.Brightness -> applyBrightnessPercent(writeResume.targetPercent)
                }
            } else {
                val msg = "Autorisation de modification des paramètres système non accordée."
                appendJarvisLog(msg, isError = true)
                speakResponse(msg)
            }
        }
    }

    // --- Implémentation de SpeechListener (RecognitionListener callbacks) ---

    override fun onStateChanged(state: VoiceState) {
        if (isWakeServiceActive()) return
        telemetryState.value = telemetryState.value.copy(
            voiceState = state,
            isMicrophoneInUse = state == VoiceState.LISTENING
        )
    }

    override fun onSpeechPartial(partialText: String) {
        if (isWakeServiceActive()) return
        telemetryState.value = telemetryState.value.copy(
            lastRecognizedText = partialText,
            voiceState = VoiceState.LISTENING,
            isMicrophoneInUse = true
        )
    }

    override fun onSpeechResult(resultText: String) {
        if (isWakeServiceActive()) return
        cancelPendingDelayedMic()
        hapticController.playStarkPulse()
        telemetryState.value = telemetryState.value.copy(
            lastRecognizedText = resultText,
            lastErrorMessage = null,
            voiceState = VoiceState.ANALYZING,
            isMicrophoneInUse = false
        )

        JarvisRuntimeStore.appendLog("« $resultText »", isJarvis = false)

        val normInput = DeviceAssistantActions.normalizeForSearch(resultText)
        if (agentJob?.isActive == true && (normInput == "stop" || normInput == "arrete" || normInput == "stoppe")) {
            stopAgentImmediately("Agent arrêté.")
            return
        }

        // 1. Priorité absolue : si une confirmation de sécurité ("oui" / "non") est attendue
        val activeConfirmation = pendingConfirmation
        if (activeConfirmation != null) {
            handleConfirmationResponse(resultText, activeConfirmation)
            return
        }

        // 2. Si on attendait le corps du message pour un SMS déjà adressé
        val activeSmsDraft = pendingSmsMessageInput
        if (activeSmsDraft != null) {
            handlePendingSmsBody(resultText, activeSmsDraft)
            return
        }

        // 3. Routage standard : Interrupteur vocal -> Locales -> Accessibilité directe -> Appel/SMS/App -> Agent multi-étapes -> Gemini
        val result = commandDispatcher.dispatch(resultText, telemetryState.value.batteryPercent)
        when (result) {
            is DispatchResult.ActivateAssistantAction -> {
                handleToggleActiveStandby()
            }
            is DispatchResult.DeactivateAssistantAction -> {
                if (JarvisRuntimeStore.isPermanentWakeEnabled(this)) {
                    startPermanentWakeService(JarvisVoiceAssistantService.ACTION_RETURN_STANDBY)
                } else {
                    val reply = "Mode veille déjà désactivé. Activez l'interrupteur permanent pour l'écoute hors ligne."
                    appendJarvisLog(reply)
                    speakResponse(reply)
                }
            }
            is DispatchResult.StopAgentAction -> {
                if (agentJob?.isActive == true) {
                    stopAgentImmediately("Agent arrêté.")
                } else {
                    val reply = "Aucun agent actif en ce moment."
                    telemetryState.value = telemetryState.value.copy(voiceState = VoiceState.IDLE)
                    appendJarvisLog(reply)
                    speakResponse(reply)
                }
            }
            is DispatchResult.TorchAction -> {
                val newState = torchController.setTorch(result.enable)
                telemetryState.value = telemetryState.value.copy(torchActive = newState)
                appendJarvisLog(result.spokenReply)
                speakResponse(result.spokenReply)
            }
            is DispatchResult.ActionExecuted -> {
                if (result.isAlert != null) {
                    telemetryState.value = telemetryState.value.copy(isAlertMode = result.isAlert)
                }
                appendJarvisLog(result.logText)
                speakResponse(result.spokenReply)
            }
            is DispatchResult.AccessibilityHome -> handleAccessibilityGlobalAction(
                actionName = "Accueil",
                perform = { it.performHome() },
                spokenReply = "Retour à l'écran d'accueil."
            )
            is DispatchResult.AccessibilityBack -> handleAccessibilityGlobalAction(
                actionName = "Retour",
                perform = { it.performBack() },
                spokenReply = "Retour en arrière."
            )
            is DispatchResult.AccessibilityNotifications -> handleAccessibilityGlobalAction(
                actionName = "Notifications",
                perform = { it.performNotifications() },
                spokenReply = "Ouverture du volet de notifications."
            )
            is DispatchResult.AccessibilityQuickSettings -> handleAccessibilityGlobalAction(
                actionName = "Réglages rapides",
                perform = { it.performQuickSettings() },
                spokenReply = "Ouverture des réglages rapides."
            )
            is DispatchResult.AccessibilityRecents -> handleAccessibilityGlobalAction(
                actionName = "Applications récentes",
                perform = { it.performRecents() },
                spokenReply = "Affichage des applications récentes."
            )
            is DispatchResult.AccessibilityReadScreen -> handleAccessibilityReadScreen()
            is DispatchResult.AccessibilityClick -> handleAccessibilityClick(result.targetText)
            is DispatchResult.AccessibilityLongPress -> handleAccessibilityLongPress(result.targetText)
            is DispatchResult.AccessibilitySwipe -> handleAccessibilitySwipe(result.direction)
            is DispatchResult.AccessibilityScroll -> handleAccessibilityScroll(result.down)
            is DispatchResult.AccessibilityType -> handleAccessibilityType(result.textToType)
            is DispatchResult.ReadNotificationsAction -> handleReadNotifications()
            is DispatchResult.CallLookupAction -> {
                handleCallLookup(result.targetQuery)
            }
            is DispatchResult.SmsComposeAction -> {
                handleSmsCompose(
                    explicitRecipient = result.explicitRecipient,
                    explicitMessage = result.explicitMessage,
                    rawAfterTo = result.rawAfterTo
                )
            }
            is DispatchResult.SetVolumeAction -> {
                applyVolumeCommand(result)
            }
            is DispatchResult.SetFontScaleAction -> {
                promptFontScaleConfirmation(result.scale, result.label)
            }
            is DispatchResult.SetBrightnessAction -> {
                promptBrightnessConfirmation(result.percent, result.deltaPercent)
            }
            is DispatchResult.OpenDeviceSettingsAction -> {
                promptOpenDeviceSettingsConfirmation(result.label, result.settingsAction)
            }
            is DispatchResult.OpenAppAction -> {
                handleOpenApp(result.appQuery)
            }
            is DispatchResult.AgentGoal -> {
                startMultiStepAgent(result.goal)
            }
            is DispatchResult.GeminiQuery -> {
                telemetryState.value = telemetryState.value.copy(
                    voiceState = VoiceState.ANALYZING,
                    lastErrorMessage = null,
                    quotaStatusMessage = null
                )
                lifecycleScope.launch {
                    val aiResult = geminiClient.generateReply(
                        prompt = result.rawQuery,
                        apiKey = apiKeyState.value,
                        onRetryStatus = { quotaMsg ->
                            telemetryState.value = telemetryState.value.copy(
                                quotaStatusMessage = quotaMsg
                            )
                        }
                    )
                    telemetryState.value = telemetryState.value.copy(quotaStatusMessage = null)
                    when (aiResult) {
                        is GeminiResult.Success -> {
                            appendJarvisLog(aiResult.text)
                            speakResponse(aiResult.text)
                        }
                        is GeminiResult.Error -> {
                            telemetryState.value = telemetryState.value.copy(
                                voiceState = VoiceState.ERROR,
                                lastErrorMessage = aiResult.userMessage
                            )
                            appendJarvisLog(aiResult.userMessage, isError = true)
                        }
                    }
                }
            }
        }
    }

    override fun onError(errorMessage: String, errorCode: Int) {
        if (isWakeServiceActive()) return
        val isNoSpeechOrTimeout = errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
            errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

        // Pendant une confirmation (appel, SMS ou action sensible), ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT n'est PAS une erreur système
        val activeConfirmation = pendingConfirmation
        if (activeConfirmation != null && isNoSpeechOrTimeout) {
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
                startListeningAfterPrompt(extendedSilenceTimeoutMs = CONFIRMATION_LISTEN_TIMEOUT_MS)
                return
            }

            clearConfirmationState(completeDeferredFalse = true)
            telemetryState.value = telemetryState.value.copy(
                voiceState = VoiceState.IDLE,
                isMicrophoneInUse = false,
                lastErrorMessage = null
            )

            if (activeConfirmation is PendingConfirmation.AgentSensitive ||
                activeConfirmation is PendingConfirmation.AgentContinue
            ) {
                // La boucle de l'agent va recevoir `false` sur le Deferred et annoncer l'annulation
                return
            }

            val cancelMsg = when (activeConfirmation) {
                is PendingConfirmation.Call -> "Je n'ai pas entendu de réponse, appel annulé."
                is PendingConfirmation.Sms -> "Je n'ai pas entendu de réponse, SMS annulé."
                is PendingConfirmation.FontScale -> "Je n'ai pas entendu de réponse, changement de police annulé."
                is PendingConfirmation.Brightness -> "Je n'ai pas entendu de réponse, réglage de luminosité annulé."
                is PendingConfirmation.OpenDeviceSettings -> "Je n'ai pas entendu de réponse, ouverture des paramètres annulée."
                is PendingConfirmation.AgentSensitive,
                is PendingConfirmation.AgentContinue -> "Je n'ai pas entendu de réponse, action annulée."
            }
            appendJarvisLog(cancelMsg, isError = false)
            speakResponse(cancelMsg)
            return
        }

        if (pendingSmsMessageInput != null && isNoSpeechOrTimeout) {
            pendingSmsMessageInput = null
            val cancelMsg = "Je n'ai pas entendu de réponse, SMS annulé."
            telemetryState.value = telemetryState.value.copy(
                voiceState = VoiceState.IDLE,
                isMicrophoneInUse = false,
                lastErrorMessage = null
            )
            appendJarvisLog(cancelMsg, isError = false)
            speakResponse(cancelMsg)
            return
        }

        hapticController.playStarkPulse()
        telemetryState.value = telemetryState.value.copy(
            voiceState = VoiceState.ERROR,
            isMicrophoneInUse = false,
            lastErrorMessage = errorMessage
        )
        appendJarvisLog("Signal vocal interrompu : $errorMessage", isError = true)
    }

    override fun onRmsChanged(rmsdB: Float) {
        if (isWakeServiceActive()) return
        telemetryState.value = telemetryState.value.copy(voiceRmsDb = rmsdB)
    }

    // --- PARTIE A & B : Accessibilité Directe ---

    private fun handleOpenAccessibilitySettings() {
        hapticController.playStarkPulse()
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible d'ouvrir les paramètres d'accessibilité.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleOpenNotificationListenerSettings() {
        hapticController.playStarkPulse()
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            appendJarvisLog("Ouverture de l'accès aux notifications pour J.A.R.V.I.S.")
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible d'ouvrir l'accès aux notifications.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleReadNotifications() {
        val (enabled, summary) = JarvisNotificationListenerService.buildSpokenNotificationsSummary(this)
        telemetryState.value = telemetryState.value.copy(
            isNotificationListenerEnabled = enabled
        )
        appendJarvisLog(summary, isError = !enabled)
        speakResponse(summary)
    }

    private fun requireAccessibilityService(): JarvisAccessibilityService? {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            val msg = "Le service d'accessibilité J.A.R.V.I.S. n'est pas activé. Appuyez sur « Activer le service d'accessibilité »."
            telemetryState.value = telemetryState.value.copy(
                voiceState = VoiceState.IDLE,
                isAccessibilityEnabled = false
            )
            appendJarvisLog(msg)
            speakResponse(msg)
        } else {
            telemetryState.value = telemetryState.value.copy(isAccessibilityEnabled = true)
        }
        return service
    }

    private fun handleAccessibilityGlobalAction(
        actionName: String,
        perform: (JarvisAccessibilityService) -> Boolean,
        spokenReply: String
    ) {
        val service = requireAccessibilityService() ?: return
        val ok = perform(service)
        if (ok) {
            appendJarvisLog(spokenReply)
            speakResponse(spokenReply)
        } else {
            val err = "Action système « $actionName » refusée par Android."
            appendJarvisLog(err, isError = true)
            speakResponse(err)
        }
    }

    private fun handleAccessibilityReadScreen() {
        val service = requireAccessibilityService() ?: return
        val (readResult, summary) = service.buildSpokenScreenSummary()
        when (readResult) {
            is ScreenReadResult.Blacklisted -> {
                appendJarvisLog(summary, isError = true)
                speakResponse(summary)
            }
            else -> {
                appendJarvisLog(summary)
                speakResponse(summary)
            }
        }
    }

    private fun handleAccessibilityClick(targetText: String) {
        val service = requireAccessibilityService() ?: return
        handleAccessibilityActionResult(service.clickByTextOrDescription(targetText))
    }

    private fun handleAccessibilityLongPress(targetText: String) {
        val service = requireAccessibilityService() ?: return
        handleAccessibilityActionResult(service.longPressByTextOrDescription(targetText))
    }

    private fun handleAccessibilitySwipe(direction: String) {
        val service = requireAccessibilityService() ?: return
        handleAccessibilityActionResult(service.swipeGesture(direction))
    }

    private fun handleAccessibilityScroll(down: Boolean) {
        val service = requireAccessibilityService() ?: return
        val res = if (down) service.scrollDown() else service.scrollUp()
        handleAccessibilityActionResult(res)
    }

    private fun handleAccessibilityType(textToType: String) {
        val service = requireAccessibilityService() ?: return
        handleAccessibilityActionResult(service.typeText(textToType))
    }

    private fun handleAccessibilityActionResult(result: AccessibilityActionResult) {
        when (result) {
            is AccessibilityActionResult.Success -> {
                appendJarvisLog(result.message)
                speakResponse(result.message)
            }
            is AccessibilityActionResult.Blacklisted -> {
                appendJarvisLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isError = true)
                speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE)
            }
            is AccessibilityActionResult.NotFound -> {
                appendJarvisLog(result.detail)
                speakResponse(result.detail)
            }
            is AccessibilityActionResult.Failed -> {
                appendJarvisLog(result.detail, isError = true)
                speakResponse(result.detail)
            }
        }
    }

    // --- PARTIE C : Agent Multi-Étapes (20 étapes + "Je continue ?", Vision API 30+, JSON strict, gestes, confirmation sensible) ---

    private fun startMultiStepAgent(goal: String) {
        val service = requireAccessibilityService() ?: return

        if (apiKeyState.value.isBlank()) {
            val err = "Aucune clé API Gemini configurée pour piloter l'agent. Ouvrez Paramètres (⚙️)."
            telemetryState.value = telemetryState.value.copy(
                voiceState = VoiceState.ERROR,
                lastErrorMessage = err
            )
            appendJarvisLog(err, isError = true)
            speakResponse(err)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        agentJob?.cancel()
        val history = mutableListOf<String>()

        agentJob = lifecycleScope.launch {
            var lastGeminiCallEndedMs = 0L
            telemetryState.value = telemetryState.value.copy(
                isAgentRunning = true,
                agentStatusText = "Initialisation : $goal",
                voiceState = VoiceState.ANALYZING,
                lastErrorMessage = null,
                quotaStatusMessage = null
            )
            appendJarvisLog("Agent multi-étapes démarré : « $goal »")
            agentNotificationHelper.showAgentRunning(1, MAX_AGENT_STEPS, "Objectif : $goal")

            try {
                var batchStart = 0
                while (true) {
                    for (stepInBatch in 1..MAX_AGENT_STEPS) {
                        val step = batchStart + stepInBatch
                        ensureActive()

                        telemetryState.value = telemetryState.value.copy(
                            isAgentRunning = true,
                            agentStatusText = "Étape $stepInBatch/$MAX_AGENT_STEPS : lecture de l'écran...",
                            voiceState = VoiceState.ANALYZING
                        )
                        agentNotificationHelper.showAgentRunning(
                            stepInBatch,
                            MAX_AGENT_STEPS,
                            "Lecture de l'écran pour : $goal"
                        )

                        val screenRead = service.readScreenForAgent()
                        val (activePkg, rawCompactText) = when (screenRead) {
                            is ScreenReadResult.Blacklisted -> {
                                appendJarvisLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isError = true)
                                speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE)
                                return@launch
                            }
                            is ScreenReadResult.Unavailable -> {
                                JarvisAccessibilityService.currentForegroundPackage to "(vide)"
                            }
                            is ScreenReadResult.Success -> {
                                screenRead.packageName to screenRead.compactText
                            }
                        }

                        var compactText = rawCompactText
                        var screenshotBase64: String? = null
                        if (service.isTreePoorOrEmpty(screenRead)) {
                            telemetryState.value = telemetryState.value.copy(
                                agentStatusText = "Étape $stepInBatch/$MAX_AGENT_STEPS : capture visuelle..."
                            )
                            when (val capture = service.captureReducedScreenForAgent()) {
                                is ScreenCaptureResult.Blacklisted -> {
                                    appendJarvisLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isError = true)
                                    speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE)
                                    return@launch
                                }
                                is ScreenCaptureResult.FlagSecureBlocked -> {
                                    compactText = "$rawCompactText\n[VISION REFUSÉE : ${capture.reason}]"
                                    appendJarvisLog(capture.reason)
                                }
                                is ScreenCaptureResult.Failed -> {
                                    compactText = "$rawCompactText\n[VISION INDISPONIBLE]"
                                }
                                is ScreenCaptureResult.Success -> {
                                    screenshotBase64 = capture.base64Jpeg
                                }
                            }
                        }

                        // Respecter un délai minimal de 4 secondes entre deux appels Gemini consécutifs
                        if (lastGeminiCallEndedMs > 0L) {
                            val elapsed = SystemClock.elapsedRealtime() - lastGeminiCallEndedMs
                            val remaining = MIN_AGENT_GEMINI_INTERVAL_MS - elapsed
                            if (remaining > 0L) {
                                val waitSec = ((remaining + 999L) / 1000L).toInt()
                                telemetryState.value = telemetryState.value.copy(
                                    agentStatusText = "Étape $stepInBatch/$MAX_AGENT_STEPS : pause quota (${waitSec}s)..."
                                )
                                delay(remaining)
                            }
                        }
                        ensureActive()

                        telemetryState.value = telemetryState.value.copy(
                            agentStatusText = if (screenshotBase64 != null) {
                                "Étape $stepInBatch/$MAX_AGENT_STEPS : analyse Vision Gemini..."
                            } else {
                                "Étape $stepInBatch/$MAX_AGENT_STEPS : analyse Gemini..."
                            }
                        )
                        val decision = geminiClient.generateAgentStep(
                            goal = goal,
                            step = stepInBatch,
                            maxSteps = MAX_AGENT_STEPS,
                            activePackage = activePkg,
                            screenCompactText = compactText,
                            history = history,
                            apiKey = apiKeyState.value,
                            screenshotBase64 = screenshotBase64,
                            onRetryStatus = { quotaMsg ->
                                telemetryState.value = telemetryState.value.copy(
                                    quotaStatusMessage = quotaMsg,
                                    agentStatusText = quotaMsg
                                )
                            }
                        )
                        lastGeminiCallEndedMs = SystemClock.elapsedRealtime()
                        telemetryState.value = telemetryState.value.copy(quotaStatusMessage = null)

                        ensureActive()

                        val command = when (decision) {
                            is AgentDecisionResult.Error -> {
                                appendJarvisLog("Agent interrompu : ${decision.userMessage}", isError = true)
                                speakResponse(decision.userMessage)
                                return@launch
                            }
                            is AgentDecisionResult.Step -> decision.command
                        }

                        val actionLabel = describeAgentCommand(command)
                        telemetryState.value = telemetryState.value.copy(
                            agentStatusText = "Étape $stepInBatch/$MAX_AGENT_STEPS : $actionLabel"
                        )
                        agentNotificationHelper.showAgentRunning(stepInBatch, MAX_AGENT_STEPS, actionLabel)

                        // Vérification de sensibilité AVANT exécution
                        if (isSensitiveAgentCommand(command)) {
                            val confirmed = requestAgentSensitiveConfirmation(actionLabel)
                            if (!confirmed) {
                                val cancelledMsg = "Action sensible annulée par sécurité."
                                appendJarvisLog(cancelledMsg)
                                speakResponse(cancelledMsg)
                                return@launch
                            }
                        }

                        ensureActive()

                        when (command) {
                            is AgentStepCommand.Done -> {
                                appendJarvisLog("Agent terminé : ${command.message}")
                                speakResponse(command.message)
                                return@launch
                            }
                            is AgentStepCommand.AskUser -> {
                                appendJarvisLog("Agent demande : ${command.question}")
                                speakResponse(command.question)
                                return@launch
                            }
                            is AgentStepCommand.OpenApp -> {
                                when (val appLookup = DeviceAssistantActions.findLaunchableApp(this@MainActivity, command.appName)) {
                                    is AppLaunchLookupResult.Found -> {
                                        if (JarvisAccessibilityService.isPackageBlacklisted(appLookup.packageName)) {
                                            appendJarvisLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isError = true)
                                            speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE)
                                            return@launch
                                        }
                                        startActivity(appLookup.launchIntent)
                                        val entry = "Étape $step : ouverture de ${appLookup.appName}"
                                        history.add(entry)
                                        appendJarvisLog(entry)
                                    }
                                    is AppLaunchLookupResult.Ambiguous -> {
                                        val msg = "Plusieurs applications correspondent à ${command.appName} : ${appLookup.candidates.joinToString(", ")}."
                                        appendJarvisLog(msg)
                                        speakResponse(msg)
                                        return@launch
                                    }
                                    is AppLaunchLookupResult.NotFound -> {
                                        history.add("Étape $step : application « ${command.appName} » introuvable")
                                    }
                                }
                            }
                            is AgentStepCommand.ClickText -> {
                                when (val clickRes = service.clickByTextOrDescription(command.targetText)) {
                                    is AccessibilityActionResult.Blacklisted -> {
                                        appendJarvisLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isError = true)
                                        speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE)
                                        return@launch
                                    }
                                    is AccessibilityActionResult.Success -> {
                                        val entry = "Étape $step : ${clickRes.message}"
                                        history.add(entry)
                                        appendJarvisLog(entry)
                                    }
                                    is AccessibilityActionResult.NotFound -> {
                                        history.add("Étape $step : ${clickRes.detail}")
                                    }
                                    is AccessibilityActionResult.Failed -> {
                                        history.add("Étape $step : ${clickRes.detail}")
                                    }
                                }
                            }
                            is AgentStepCommand.TapXY -> {
                                when (val tapRes = service.tapByNormalized(command.xNorm, command.yNorm)) {
                                    is AccessibilityActionResult.Blacklisted -> {
                                        appendJarvisLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isError = true)
                                        speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE)
                                        return@launch
                                    }
                                    is AccessibilityActionResult.Success -> {
                                        val entry = "Étape $step : ${tapRes.message}"
                                        history.add(entry)
                                        appendJarvisLog(entry)
                                    }
                                    is AccessibilityActionResult.NotFound,
                                    is AccessibilityActionResult.Failed -> {
                                        history.add("Étape $step : tap_xy échoué")
                                    }
                                }
                            }
                            is AgentStepCommand.LongPress -> {
                                val lpRes = if (command.xNorm != null && command.yNorm != null) {
                                    service.longPressByNormalized(command.xNorm, command.yNorm)
                                } else {
                                    service.longPressByTextOrDescription(command.targetText)
                                }
                                when (lpRes) {
                                    is AccessibilityActionResult.Blacklisted -> {
                                        appendJarvisLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isError = true)
                                        speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE)
                                        return@launch
                                    }
                                    is AccessibilityActionResult.Success -> {
                                        val entry = "Étape $step : ${lpRes.message}"
                                        history.add(entry)
                                        appendJarvisLog(entry)
                                    }
                                    is AccessibilityActionResult.NotFound -> {
                                        history.add("Étape $step : ${lpRes.detail}")
                                    }
                                    is AccessibilityActionResult.Failed -> {
                                        history.add("Étape $step : ${lpRes.detail}")
                                    }
                                }
                            }
                            is AgentStepCommand.Swipe -> {
                                when (val swipeRes = service.swipeGesture(
                                    direction = command.direction,
                                    x1Norm = command.x1Norm,
                                    y1Norm = command.y1Norm,
                                    x2Norm = command.x2Norm,
                                    y2Norm = command.y2Norm
                                )) {
                                    is AccessibilityActionResult.Blacklisted -> {
                                        appendJarvisLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isError = true)
                                        speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE)
                                        return@launch
                                    }
                                    is AccessibilityActionResult.Success -> {
                                        val entry = "Étape $step : ${swipeRes.message}"
                                        history.add(entry)
                                        appendJarvisLog(entry)
                                    }
                                    is AccessibilityActionResult.NotFound,
                                    is AccessibilityActionResult.Failed -> {
                                        history.add("Étape $step : glissement impossible")
                                    }
                                }
                            }
                            is AgentStepCommand.Scroll -> {
                                when (val scrollRes = if (command.down) service.scrollDown() else service.scrollUp()) {
                                    is AccessibilityActionResult.Blacklisted -> {
                                        appendJarvisLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isError = true)
                                        speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE)
                                        return@launch
                                    }
                                    is AccessibilityActionResult.Success -> {
                                        val entry = "Étape $step : ${scrollRes.message}"
                                        history.add(entry)
                                        appendJarvisLog(entry)
                                    }
                                    is AccessibilityActionResult.NotFound,
                                    is AccessibilityActionResult.Failed -> {
                                        history.add("Étape $step : défilement impossible")
                                    }
                                }
                            }
                            is AgentStepCommand.TypeText -> {
                                when (val typeRes = service.typeText(command.text)) {
                                    is AccessibilityActionResult.Blacklisted -> {
                                        appendJarvisLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isError = true)
                                        speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE)
                                        return@launch
                                    }
                                    is AccessibilityActionResult.Success -> {
                                        val entry = "Étape $step : ${typeRes.message}"
                                        history.add(entry)
                                        appendJarvisLog(entry)
                                    }
                                    is AccessibilityActionResult.NotFound -> {
                                        history.add("Étape $step : ${typeRes.detail}")
                                    }
                                    is AccessibilityActionResult.Failed -> {
                                        history.add("Étape $step : ${typeRes.detail}")
                                    }
                                }
                            }
                            is AgentStepCommand.Back -> {
                                service.performBack()
                                val entry = "Étape $step : retour arrière"
                                history.add(entry)
                                appendJarvisLog(entry)
                            }
                            is AgentStepCommand.Home -> {
                                service.performHome()
                                val entry = "Étape $step : retour accueil"
                                history.add(entry)
                                appendJarvisLog(entry)
                            }
                            is AgentStepCommand.Wait -> {
                                history.add("Étape $step : attente ${command.durationMs}ms")
                                delay(command.durationMs)
                            }
                        }

                        delay(1100L)
                    }

                    batchStart += MAX_AGENT_STEPS
                    val shouldContinue = requestAgentContinueConfirmation(goal, batchStart)
                    if (!shouldContinue) {
                        val stopMsg = "Mission arrêtée après $batchStart étapes."
                        appendJarvisLog(stopMsg)
                        speakResponse(stopMsg)
                        return@launch
                    }
                    appendJarvisLog("Poursuite de la mission au-delà de $batchStart étapes.")
                }
            } catch (_: CancellationException) {
                // Arrêt volontaire via "stop"
            } finally {
                agentNotificationHelper.dismiss()
                telemetryState.value = telemetryState.value.copy(
                    isAgentRunning = false,
                    agentStatusText = "",
                    quotaStatusMessage = null,
                    voiceState = VoiceState.IDLE
                )
            }
        }
    }

    private fun stopAgentImmediately(spokenReason: String? = "Agent arrêté.") {
        if (isWakeServiceActive()) {
            startService(
                JarvisVoiceAssistantService.createStartIntent(
                    this,
                    JarvisVoiceAssistantService.ACTION_STOP_AGENT
                )
            )
            return
        }
        cancelPendingDelayedMic()
        clearConfirmationState(completeDeferredFalse = true)
        agentJob?.cancel()
        agentJob = null
        agentNotificationHelper.dismiss()
        telemetryState.value = telemetryState.value.copy(
            isAgentRunning = false,
            agentStatusText = "",
            quotaStatusMessage = null,
            voiceState = VoiceState.IDLE
        )
        if (!spokenReason.isNullOrBlank()) {
            appendJarvisLog(spokenReason)
            speakResponse(spokenReason)
        }
    }

    private suspend fun requestAgentSensitiveConfirmation(description: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirmation = PendingConfirmation.AgentSensitive(
            description = description,
            deferred = deferred
        )
        confirmationListenStartMs = 0L
        confirmationTimeoutRetries = 0
        telemetryState.value = telemetryState.value.copy(
            lastErrorMessage = null,
            activeConfirmation = ConfirmationPromptUi(
                title = "Action sensible détectée",
                details = "$description • Dites « oui » / « non » ou touchez un bouton."
            )
        )

        // Ramener J.A.R.V.I.S. au premier plan si besoin pour afficher les boutons OUI/NON et écouter
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val bringFrontIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            try {
                JarvisAccessibilityService.instance?.startActivity(bringFrontIntent) ?: startActivity(bringFrontIntent)
            } catch (_: Exception) {
                // Ignore if background launch restricted
            }
        }

        val prompt = "Action sensible détectée : $description. Confirmez ?"
        appendJarvisLog(prompt)
        speakResponse(prompt) {
            scheduleDelayedConfirmationListening()
        }

        return try {
            deferred.await()
        } finally {
            if (pendingConfirmation is PendingConfirmation.AgentSensitive) {
                clearConfirmationState(completeDeferredFalse = false)
            }
        }
    }

    private suspend fun requestAgentContinueConfirmation(goal: String, stepsExecuted: Int): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirmation = PendingConfirmation.AgentContinue(
            goal = goal,
            stepsExecuted = stepsExecuted,
            deferred = deferred
        )
        confirmationListenStartMs = 0L
        confirmationTimeoutRetries = 0
        telemetryState.value = telemetryState.value.copy(
            lastErrorMessage = null,
            activeConfirmation = ConfirmationPromptUi(
                title = "Mission longue ($stepsExecuted étapes)",
                details = "Je continue ? • Dites « oui » / « non » ou touchez un bouton."
            )
        )

        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val bringFrontIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            try {
                JarvisAccessibilityService.instance?.startActivity(bringFrontIntent) ?: startActivity(bringFrontIntent)
            } catch (_: Exception) {
                // Ignore if background launch restricted
            }
        }

        val prompt = "$stepsExecuted étapes effectuées. Je continue ?"
        appendJarvisLog(prompt)
        speakResponse(prompt) {
            scheduleDelayedConfirmationListening()
        }

        return try {
            deferred.await()
        } finally {
            if (pendingConfirmation is PendingConfirmation.AgentContinue) {
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
        ) {
            return false
        }

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
        val sensitiveKeywords = listOf(
            "envoyer", "envoi", "send",
            "payer", "paiement", "pay", "acheter", "achat", "virement",
            "supprimer", "effacer", "delete", "remove",
            "desinstaller", "installer", "install",
            "securite", "mot de passe", "password", "empreinte",
            "verrouillage", "code pin", "reinitialiser", "format",
            "administrateur"
        )
        return sensitiveKeywords.any { norm.contains(it) }
    }

    private fun describeAgentCommand(command: AgentStepCommand): String {
        return when (command) {
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
            is AgentStepCommand.Wait -> "Attente (${command.durationMs} ms)"
            is AgentStepCommand.Done -> command.message
            is AgentStepCommand.AskUser -> command.question
        }
    }

    // --- Traitement Sécurisé : Appels, SMS, Ouverture d'Applications ---

    private fun handleCallLookup(targetQuery: String) {
        val directNumber = DeviceAssistantActions.extractPhoneNumber(targetQuery)
        if (directNumber != null) {
            promptCallConfirmation(displayName = directNumber, phoneNumber = directNumber, isRawNumber = true)
            return
        }

        val hasContactsPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPermission) {
            pendingPermissionResume = PendingPermissionResume.ResolveCallAfterContacts(targetQuery)
            appendJarvisLog("Demande d'autorisation d'accès aux contacts pour rechercher « $targetQuery ».")
            requestContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            return
        }

        when (val lookup = DeviceAssistantActions.searchContactByName(this, targetQuery)) {
            is ContactLookupResult.Found -> {
                promptCallConfirmation(
                    displayName = lookup.contact.name,
                    phoneNumber = lookup.contact.phoneNumber,
                    isRawNumber = false
                )
            }
            is ContactLookupResult.Ambiguous -> {
                val names = lookup.candidates.joinToString(", ") { it.name }
                val reply = "Plusieurs contacts correspondent : $names. Veuillez préciser lequel appeler."
                appendJarvisLog(reply)
                speakResponse(reply)
            }
            is ContactLookupResult.NotFound -> {
                val reply = "Contact introuvable dans votre répertoire pour « $targetQuery ». Veuillez préciser le nom ou dicter le numéro."
                appendJarvisLog(reply)
                speakResponse(reply)
            }
        }
    }

    private fun handleSmsCompose(
        explicitRecipient: String,
        explicitMessage: String,
        rawAfterTo: String
    ) {
        val hasDirectNumber = if (explicitRecipient.isNotBlank()) {
            DeviceAssistantActions.extractPhoneNumber(explicitRecipient) != null
        } else {
            DeviceAssistantActions.extractLeadingPhoneNumberAndRemainder(rawAfterTo) != null
        }

        if (!hasDirectNumber) {
            val hasContactsPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasContactsPermission) {
                pendingPermissionResume = PendingPermissionResume.ResolveSmsAfterContacts(
                    explicitRecipient = explicitRecipient,
                    explicitMessage = explicitMessage,
                    rawAfterTo = rawAfterTo
                )
                appendJarvisLog("Demande d'autorisation d'accès aux contacts pour identifier le destinataire du SMS.")
                requestContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                return
            }
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
                    pendingSmsMessageInput = PendingSmsMessageInput(
                        displayName = lookup.contact.name,
                        phoneNumber = lookup.contact.phoneNumber
                    )
                    val askMsg = "Quel message souhaitez-vous envoyer à ${lookup.contact.name} ?"
                    appendJarvisLog(askMsg)
                    speakResponse(askMsg) {
                        scheduleDelayedMicroListening(extendedTimeoutMs = CONFIRMATION_LISTEN_TIMEOUT_MS)
                    }
                } else {
                    promptSmsConfirmation(
                        displayName = lookup.contact.name,
                        phoneNumber = lookup.contact.phoneNumber,
                        message = msg
                    )
                }
            }
            is ContactLookupResult.Ambiguous -> {
                val names = lookup.candidates.joinToString(", ") { it.name }
                val reply = "Plusieurs contacts correspondent : $names. Veuillez préciser le destinataire du SMS."
                appendJarvisLog(reply)
                speakResponse(reply)
            }
            is ContactLookupResult.NotFound -> {
                val reply = "Destinataire introuvable dans vos contacts. Précisez le nom complet ou le numéro avant le message."
                appendJarvisLog(reply)
                speakResponse(reply)
            }
        }
    }

    private fun handlePendingSmsBody(spokenBody: String, draft: PendingSmsMessageInput) {
        pendingSmsMessageInput = null
        val norm = DeviceAssistantActions.normalizeForSearch(spokenBody)
        if (isNegativeConfirmation(norm)) {
            val reply = "SMS annulé."
            appendJarvisLog(reply)
            speakResponse(reply)
            return
        }
        promptSmsConfirmation(
            displayName = draft.displayName,
            phoneNumber = draft.phoneNumber,
            message = spokenBody.trim()
        )
    }

    private fun promptCallConfirmation(displayName: String, phoneNumber: String, isRawNumber: Boolean) {
        cancelPendingDelayedMic()
        confirmationListenStartMs = 0L
        confirmationTimeoutRetries = 0
        pendingConfirmation = PendingConfirmation.Call(displayName = displayName, phoneNumber = phoneNumber)
        telemetryState.value = telemetryState.value.copy(
            lastErrorMessage = null,
            activeConfirmation = ConfirmationPromptUi(
                title = "Appeler $displayName ?",
                details = "Numéro : $phoneNumber • Dites « oui » / « non » ou touchez un bouton."
            )
        )
        val prompt = if (isRawNumber) {
            "J'appelle le $phoneNumber, confirmez ?"
        } else {
            "J'appelle $displayName, confirmez ?"
        }
        appendJarvisLog(prompt)
        speakResponse(prompt) {
            scheduleDelayedConfirmationListening()
        }
    }

    private fun promptSmsConfirmation(displayName: String, phoneNumber: String, message: String) {
        cancelPendingDelayedMic()
        confirmationListenStartMs = 0L
        confirmationTimeoutRetries = 0
        pendingConfirmation = PendingConfirmation.Sms(
            displayName = displayName,
            phoneNumber = phoneNumber,
            message = message
        )
        telemetryState.value = telemetryState.value.copy(
            lastErrorMessage = null,
            activeConfirmation = ConfirmationPromptUi(
                title = "Envoyer un SMS à $displayName ?",
                details = "« $message » • Dites « oui » / « non » ou touchez un bouton."
            )
        )
        val prompt = "J'envoie un SMS à $displayName : $message. Confirmez ?"
        appendJarvisLog(prompt)
        speakResponse(prompt) {
            scheduleDelayedConfirmationListening()
        }
    }

    private fun scheduleDelayedConfirmationListening() {
        scheduleDelayedMicroListening(extendedTimeoutMs = CONFIRMATION_LISTEN_TIMEOUT_MS)
    }

    private fun scheduleDelayedMicroListening(extendedTimeoutMs: Long) {
        cancelPendingDelayedMic()
        val runnable = Runnable {
            pendingDelayedMicRunnable = null
            if (pendingConfirmation != null || pendingSmsMessageInput != null) {
                if (confirmationListenStartMs == 0L) {
                    confirmationListenStartMs = SystemClock.elapsedRealtime()
                }
                startListeningAfterPrompt(extendedSilenceTimeoutMs = extendedTimeoutMs)
            }
        }
        pendingDelayedMicRunnable = runnable
        confirmationHandler.postDelayed(runnable, TTS_TO_MIC_DELAY_MS)
    }

    private fun cancelPendingDelayedMic() {
        pendingDelayedMicRunnable?.let {
            confirmationHandler.removeCallbacks(it)
            pendingDelayedMicRunnable = null
        }
    }

    private fun clearConfirmationState(completeDeferredFalse: Boolean = false) {
        cancelPendingDelayedMic()
        val current = pendingConfirmation
        if (completeDeferredFalse && current is PendingConfirmation.AgentSensitive && !current.deferred.isCompleted) {
            current.deferred.complete(false)
        }
        if (completeDeferredFalse && current is PendingConfirmation.AgentContinue && !current.deferred.isCompleted) {
            current.deferred.complete(false)
        }
        pendingConfirmation = null
        confirmationListenStartMs = 0L
        confirmationTimeoutRetries = 0
        telemetryState.value = telemetryState.value.copy(activeConfirmation = null)
    }

    private fun handleUiConfirmAction() {
        hapticController.playStarkPulse()
        if (isWakeServiceActive()) {
            startService(
                JarvisVoiceAssistantService.createStartIntent(
                    this,
                    JarvisVoiceAssistantService.ACTION_CONFIRM_YES
                )
            )
            return
        }
        val action = pendingConfirmation ?: return
        clearConfirmationState(completeDeferredFalse = false)
        voiceEngine?.stop()
        speechController?.cancel()
        appendJarvisLog("Confirmation manuelle : OUI")
        executeConfirmedAction(action)
    }

    private fun handleUiCancelAction() {
        hapticController.playStarkPulse()
        if (isWakeServiceActive()) {
            startService(
                JarvisVoiceAssistantService.createStartIntent(
                    this,
                    JarvisVoiceAssistantService.ACTION_CONFIRM_NO
                )
            )
            return
        }
        val action = pendingConfirmation ?: return
        clearConfirmationState(completeDeferredFalse = false)
        voiceEngine?.stop()
        speechController?.cancel()
        when (action) {
            is PendingConfirmation.Call -> {
                appendJarvisLog("Appel annulé.")
                speakResponse("Appel annulé.")
            }
            is PendingConfirmation.Sms -> {
                appendJarvisLog("SMS annulé.")
                speakResponse("SMS annulé.")
            }
            is PendingConfirmation.FontScale -> {
                appendJarvisLog("Changement de police annulé.")
                speakResponse("Changement de police annulé.")
            }
            is PendingConfirmation.Brightness -> {
                appendJarvisLog("Réglage de luminosité annulé.")
                speakResponse("Réglage de luminosité annulé.")
            }
            is PendingConfirmation.OpenDeviceSettings -> {
                appendJarvisLog("Ouverture des paramètres annulée.")
                speakResponse("Ouverture des paramètres annulée.")
            }
            is PendingConfirmation.AgentSensitive -> {
                if (!action.deferred.isCompleted) action.deferred.complete(false)
            }
            is PendingConfirmation.AgentContinue -> {
                if (!action.deferred.isCompleted) action.deferred.complete(false)
            }
        }
    }

    private fun handleConfirmationResponse(spokenText: String, action: PendingConfirmation) {
        clearConfirmationState(completeDeferredFalse = false)
        val norm = DeviceAssistantActions.normalizeForSearch(spokenText)

        when {
            isNegativeConfirmation(norm) -> {
                when (action) {
                    is PendingConfirmation.Call -> {
                        appendJarvisLog("Appel annulé.")
                        speakResponse("Appel annulé.")
                    }
                    is PendingConfirmation.Sms -> {
                        appendJarvisLog("SMS annulé.")
                        speakResponse("SMS annulé.")
                    }
                    is PendingConfirmation.FontScale -> {
                        appendJarvisLog("Changement de police annulé.")
                        speakResponse("Changement de police annulé.")
                    }
                    is PendingConfirmation.Brightness -> {
                        appendJarvisLog("Réglage de luminosité annulé.")
                        speakResponse("Réglage de luminosité annulé.")
                    }
                    is PendingConfirmation.OpenDeviceSettings -> {
                        appendJarvisLog("Ouverture des paramètres annulée.")
                        speakResponse("Ouverture des paramètres annulée.")
                    }
                    is PendingConfirmation.AgentSensitive -> {
                        if (!action.deferred.isCompleted) action.deferred.complete(false)
                    }
                    is PendingConfirmation.AgentContinue -> {
                        if (!action.deferred.isCompleted) action.deferred.complete(false)
                    }
                }
            }
            isAffirmativeConfirmation(norm) -> {
                executeConfirmedAction(action)
            }
            else -> {
                when (action) {
                    is PendingConfirmation.Call -> {
                        appendJarvisLog("Confirmation non reconnue, appel annulé.")
                        speakResponse("Confirmation non reconnue, appel annulé.")
                    }
                    is PendingConfirmation.Sms -> {
                        appendJarvisLog("Confirmation non reconnue, SMS annulé.")
                        speakResponse("Confirmation non reconnue, SMS annulé.")
                    }
                    is PendingConfirmation.FontScale -> {
                        appendJarvisLog("Confirmation non reconnue, changement de police annulé.")
                        speakResponse("Confirmation non reconnue, changement de police annulé.")
                    }
                    is PendingConfirmation.Brightness -> {
                        appendJarvisLog("Confirmation non reconnue, réglage de luminosité annulé.")
                        speakResponse("Confirmation non reconnue, réglage de luminosité annulé.")
                    }
                    is PendingConfirmation.OpenDeviceSettings -> {
                        appendJarvisLog("Confirmation non reconnue, ouverture des paramètres annulée.")
                        speakResponse("Confirmation non reconnue, ouverture des paramètres annulée.")
                    }
                    is PendingConfirmation.AgentSensitive -> {
                        if (!action.deferred.isCompleted) action.deferred.complete(false)
                    }
                    is PendingConfirmation.AgentContinue -> {
                        if (!action.deferred.isCompleted) action.deferred.complete(false)
                    }
                }
            }
        }
    }

    private fun executeConfirmedAction(action: PendingConfirmation) {
        when (action) {
            is PendingConfirmation.Call -> {
                val hasCallPermission = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasCallPermission) {
                    pendingPermissionResume = PendingPermissionResume.ExecuteConfirmedCall(
                        action.displayName,
                        action.phoneNumber
                    )
                    appendJarvisLog("Demande d'autorisation d'appel téléphonique...")
                    requestCallPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                } else {
                    executeConfirmedCall(action.displayName, action.phoneNumber)
                }
            }
            is PendingConfirmation.Sms -> {
                val hasSmsPermission = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.SEND_SMS
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasSmsPermission) {
                    pendingPermissionResume = PendingPermissionResume.ExecuteConfirmedSms(
                        action.displayName,
                        action.phoneNumber,
                        action.message
                    )
                    appendJarvisLog("Demande d'autorisation d'envoi de SMS...")
                    requestSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                } else {
                    executeConfirmedSms(action.displayName, action.phoneNumber, action.message)
                }
            }
            is PendingConfirmation.FontScale -> {
                if (!Settings.System.canWrite(this)) {
                    pendingWriteSettingsResume = PendingWriteSettingsResume.FontScale(action.scale, action.label)
                    launchWriteSettingsPermissionScreen()
                } else {
                    applyFontScale(action.scale, action.label)
                }
            }
            is PendingConfirmation.Brightness -> {
                if (!Settings.System.canWrite(this)) {
                    pendingWriteSettingsResume = PendingWriteSettingsResume.Brightness(action.targetPercent)
                    launchWriteSettingsPermissionScreen()
                } else {
                    applyBrightnessPercent(action.targetPercent)
                }
            }
            is PendingConfirmation.OpenDeviceSettings -> {
                openDeviceSettingsScreen(action.label, action.settingsAction)
            }
            is PendingConfirmation.AgentSensitive -> {
                if (!action.deferred.isCompleted) {
                    action.deferred.complete(true)
                }
            }
            is PendingConfirmation.AgentContinue -> {
                if (!action.deferred.isCompleted) {
                    action.deferred.complete(true)
                }
            }
        }
    }

    private fun executeConfirmedCall(displayName: String, phoneNumber: String) {
        val launched = PhoneActions.makeCallStrict(this, phoneNumber)
        if (launched) {
            val msg = "Appel vers $displayName ($phoneNumber) lancé."
            appendJarvisLog(msg)
            speakResponse("Appel vers $displayName en cours.")
        } else {
            val err = "Impossible de lancer l'appel vers $displayName. Vérifiez l'autorisation Téléphone."
            telemetryState.value = telemetryState.value.copy(
                voiceState = VoiceState.ERROR,
                lastErrorMessage = err
            )
            appendJarvisLog(err, isError = true)
            speakResponse(err)
        }
    }

    private fun executeConfirmedSms(displayName: String, phoneNumber: String, message: String) {
        val sent = PhoneActions.sendSmsStrict(this, phoneNumber, message)
        if (sent) {
            val msg = "SMS envoyé à $displayName ($phoneNumber) : « $message »"
            appendJarvisLog(msg)
            speakResponse("SMS envoyé à $displayName.")
        } else {
            val err = "Échec de l'envoi du SMS à $displayName. Vérifiez le réseau ou l'autorisation SMS."
            telemetryState.value = telemetryState.value.copy(
                voiceState = VoiceState.ERROR,
                lastErrorMessage = err
            )
            appendJarvisLog(err, isError = true)
            speakResponse(err)
        }
    }

    private fun handleOpenApp(appQuery: String) {
        when (val lookup = DeviceAssistantActions.findLaunchableApp(this, appQuery)) {
            is AppLaunchLookupResult.Found -> {
                if (JarvisAccessibilityService.isPackageBlacklisted(lookup.packageName)) {
                    appendJarvisLog(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE, isError = true)
                    speakResponse(JarvisAccessibilityService.BLACKLIST_SPOKEN_MESSAGE)
                    return
                }
                try {
                    startActivity(lookup.launchIntent)
                    val msg = "Ouverture de ${lookup.appName}."
                    appendJarvisLog(msg)
                    speakResponse(msg)
                } catch (e: Exception) {
                    val err = "Impossible d'ouvrir ${lookup.appName} : ${e.localizedMessage ?: "erreur système"}."
                    telemetryState.value = telemetryState.value.copy(
                        voiceState = VoiceState.ERROR,
                        lastErrorMessage = err
                    )
                    appendJarvisLog(err, isError = true)
                    speakResponse(err)
                }
            }
            is AppLaunchLookupResult.Ambiguous -> {
                val names = lookup.candidates.joinToString(", ")
                val msg = "Plusieurs applications correspondent : $names. Laquelle souhaitez-vous ouvrir ?"
                appendJarvisLog(msg)
                speakResponse(msg)
            }
            is AppLaunchLookupResult.NotFound -> {
                val msg = "Application introuvable sur cet appareil : $appQuery."
                appendJarvisLog(msg)
                speakResponse(msg)
            }
        }
    }

    private fun isAffirmativeConfirmation(normText: String): Boolean {
        if (isNegativeConfirmation(normText)) return false
        val affirmativeTokens = setOf(
            "oui", "ouais", "ok", "okay", "d accord", "daccord",
            "confirme", "je confirme", "vas y", "vasy", "affirmatif",
            "exact", "c est ca", "envoie", "appelle", "oui vas y",
            "oui confirme", "oui je confirme", "oui ok", "oui envoie", "oui appelle"
        )
        return normText in affirmativeTokens ||
            normText.startsWith("oui ") ||
            normText.startsWith("ouais ") ||
            normText.startsWith("vas y") ||
            normText.startsWith("confirme") ||
            normText.startsWith("je confirme") ||
            normText.startsWith("ok ")
    }

    private fun isNegativeConfirmation(normText: String): Boolean {
        val negativeTokens = setOf(
            "non", "nan", "annule", "annuler", "stop", "arrete",
            "pas question", "negatif", "refuse", "laisse tomber", "ne pas"
        )
        return normText in negativeTokens ||
            normText.startsWith("non ") ||
            normText.startsWith("annul") ||
            normText.startsWith("stop")
    }

    private fun startListeningAfterPrompt(extendedSilenceTimeoutMs: Long? = null) {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasAudioPermission) {
            speechController?.startListening(extendedSilenceTimeoutMs = extendedSilenceTimeoutMs)
        }
    }

    private fun reportPermissionDenied(message: String) {
        telemetryState.value = telemetryState.value.copy(
            voiceState = VoiceState.ERROR,
            lastErrorMessage = message
        )
        appendJarvisLog(message, isError = true)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        speakResponse(message)
    }

    private fun requestCameraPermissionIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        cameraPermissionState.value = granted
        if (!granted) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // --- Actions Matérielles & Commandes Rapides ---

    private fun handleMicToggle() {
        hapticController.playStarkPulse()
        if (isWakeServiceActive()) {
            handleToggleActiveStandby()
            return
        }
        cancelPendingDelayedMic()
        voiceEngine?.stop()
        if (!hasRecordAudioPermission()) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            telemetryState.value = telemetryState.value.copy(lastErrorMessage = null)
            if (speechController?.isListeningActive() == true) {
                speechController?.stopListening()
            } else {
                val timeoutMs = if (pendingConfirmation != null || pendingSmsMessageInput != null) {
                    if (confirmationListenStartMs == 0L) {
                        confirmationListenStartMs = SystemClock.elapsedRealtime()
                    }
                    CONFIRMATION_LISTEN_TIMEOUT_MS
                } else {
                    null
                }
                speechController?.startListening(extendedSilenceTimeoutMs = timeoutMs)
            }
        }
    }

    private fun handleToggleTorch() {
        hapticController.playStarkPulse()
        val newState = torchController.toggleTorch()
        telemetryState.value = telemetryState.value.copy(torchActive = newState)
        JarvisRuntimeStore.updateRuntime { it.copy(torchActive = newState) }
        val text = if (newState) "Torche activée." else "Torche désactivée."
        appendJarvisLog(text)
        if (!isWakeServiceActive()) {
            speakResponse(text)
        }
    }

    private fun handleToggleAlert() {
        hapticController.playStarkPulse()
        val newAlert = !telemetryState.value.isAlertMode
        telemetryState.value = telemetryState.value.copy(isAlertMode = newAlert)
        JarvisRuntimeStore.updateRuntime { it.copy(isAlertMode = newAlert) }
        val msg = if (newAlert) "Alerte de sécurité activée." else "Retour au protocole standard."
        appendJarvisLog(msg)
        if (!isWakeServiceActive()) {
            speakResponse(msg)
        }
    }

    private fun handleToggleDayNight() {
        hapticController.playStarkPulse()
        telemetryState.value = telemetryState.value.copy(isNightMode = !telemetryState.value.isNightMode)
    }

    private fun handleQuickAction(action: String) {
        hapticController.playStarkPulse()
        JarvisRuntimeStore.appendLog(action, isJarvis = false)
        when (action) {
            "Briefing Matin" -> {
                val reply = "Il est ${getCurrentTime()}. Votre batterie est à ${telemetryState.value.batteryPercent} pour cent. Aucun incident signalé."
                appendJarvisLog(reply)
                if (!isWakeServiceActive()) speakResponse(reply)
            }
            "Vérifie la batterie" -> {
                val reply = "Accus à ${telemetryState.value.batteryPercent} pour cent. Autonomie estimée optimale."
                appendJarvisLog(reply)
                if (!isWakeServiceActive()) speakResponse(reply)
            }
            "SMS pour Alexandre" -> {
                val reply = "Message pour Alexandre Stark préparé. Accès au module de messagerie."
                appendJarvisLog(reply)
                if (!isWakeServiceActive()) speakResponse(reply)
            }
            "Faire vibrer" -> {
                hapticController.playStarkPulse()
            }
            "Scanner la pièce" -> {
                val reply = "Capteurs optiques actifs. Visière opérationnelle."
                appendJarvisLog(reply)
                if (!isWakeServiceActive()) speakResponse(reply)
            }
        }
    }

    // --- Commandes Locales Directes : Volume, Police, Luminosité, Paramètres ---

    private fun applyVolumeCommand(action: DispatchResult.SetVolumeAction) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = if (action.isRingStream) AudioManager.STREAM_RING else AudioManager.STREAM_MUSIC
        val maxVol = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
        val currentVol = audioManager.getStreamVolume(stream)
        val currentPct = ((currentVol.toFloat() / maxVol.toFloat()) * 100f).roundToInt()

        val targetPct = when {
            action.percent != null -> action.percent.coerceIn(0, 100)
            action.deltaPercent != null -> (currentPct + action.deltaPercent).coerceIn(0, 100)
            else -> currentPct
        }
        val targetIndex = ((targetPct / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
        try {
            audioManager.setStreamVolume(stream, targetIndex, AudioManager.FLAG_SHOW_UI)
            val streamName = if (action.isRingStream) "sonnerie" else "média"
            val msg = if (targetPct == 0) {
                "Volume $streamName coupé."
            } else {
                "Volume $streamName réglé à $targetPct pour cent."
            }
            appendJarvisLog(msg)
            speakResponse(msg)
        } catch (e: SecurityException) {
            val err = "Impossible de modifier le volume (mode Ne pas déranger actif)."
            telemetryState.value = telemetryState.value.copy(
                voiceState = VoiceState.ERROR,
                lastErrorMessage = err
            )
            appendJarvisLog(err, isError = true)
            speakResponse(err)
        }
    }

    private fun promptFontScaleConfirmation(scale: Float, label: String) {
        cancelPendingDelayedMic()
        confirmationListenStartMs = 0L
        confirmationTimeoutRetries = 0
        pendingConfirmation = PendingConfirmation.FontScale(scale = scale, label = label)
        telemetryState.value = telemetryState.value.copy(
            lastErrorMessage = null,
            activeConfirmation = ConfirmationPromptUi(
                title = "Modifier la taille de police ?",
                details = "Nouvelle taille : $label • Dites « oui » / « non » ou touchez un bouton."
            )
        )
        val prompt = "Je règle la taille de police sur $label, confirmez ?"
        appendJarvisLog(prompt)
        speakResponse(prompt) {
            scheduleDelayedConfirmationListening()
        }
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

        cancelPendingDelayedMic()
        confirmationListenStartMs = 0L
        confirmationTimeoutRetries = 0
        pendingConfirmation = PendingConfirmation.Brightness(targetPercent = targetPercent)
        telemetryState.value = telemetryState.value.copy(
            lastErrorMessage = null,
            activeConfirmation = ConfirmationPromptUi(
                title = "Modifier la luminosité ?",
                details = "Luminosité cible : $targetPercent% • Dites « oui » / « non » ou touchez un bouton."
            )
        )
        val prompt = "Je règle la luminosité à $targetPercent pour cent, confirmez ?"
        appendJarvisLog(prompt)
        speakResponse(prompt) {
            scheduleDelayedConfirmationListening()
        }
    }

    private fun promptOpenDeviceSettingsConfirmation(label: String, settingsAction: String) {
        cancelPendingDelayedMic()
        confirmationListenStartMs = 0L
        confirmationTimeoutRetries = 0
        pendingConfirmation = PendingConfirmation.OpenDeviceSettings(
            label = label,
            settingsAction = settingsAction
        )
        telemetryState.value = telemetryState.value.copy(
            lastErrorMessage = null,
            activeConfirmation = ConfirmationPromptUi(
                title = "Ouvrir les paramètres ?",
                details = "Écran : $label • Dites « oui » / « non » ou touchez un bouton."
            )
        )
        val prompt = "J'ouvre les $label, confirmez ?"
        appendJarvisLog(prompt)
        speakResponse(prompt) {
            scheduleDelayedConfirmationListening()
        }
    }

    private fun launchWriteSettingsPermissionScreen() {
        val msg = "Autorisez la modification des paramètres système pour J.A.R.V.I.S., puis revenez."
        appendJarvisLog(msg)
        speakResponse(msg)
        try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            val err = "Impossible d'ouvrir l'écran d'autorisation WRITE_SETTINGS."
            appendJarvisLog(err, isError = true)
            speakResponse(err)
        }
    }

    private fun applyFontScale(scale: Float, label: String) {
        try {
            val ok = Settings.System.putFloat(contentResolver, Settings.System.FONT_SCALE, scale)
            if (ok) {
                val msg = "Taille de police réglée sur $label."
                appendJarvisLog(msg)
                speakResponse(msg)
            } else {
                val err = "Android n'a pas autorisé le changement de taille de police."
                appendJarvisLog(err, isError = true)
                speakResponse(err)
            }
        } catch (e: Exception) {
            val err = "Impossible de modifier la taille de police : ${e.localizedMessage ?: "erreur système"}."
            telemetryState.value = telemetryState.value.copy(
                voiceState = VoiceState.ERROR,
                lastErrorMessage = err
            )
            appendJarvisLog(err, isError = true)
            speakResponse(err)
        }
    }

    private fun applyBrightnessPercent(targetPercent: Int) {
        try {
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val brightnessValue = ((targetPercent / 100f) * 255f).roundToInt().coerceIn(3, 255)
            val ok = Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )
            val lp = window.attributes
            lp.screenBrightness = (targetPercent / 100f).coerceIn(0.02f, 1.0f)
            window.attributes = lp

            if (ok) {
                val msg = "Luminosité réglée à $targetPercent pour cent."
                appendJarvisLog(msg)
                speakResponse(msg)
            } else {
                val err = "Android n'a pas autorisé le changement de luminosité."
                appendJarvisLog(err, isError = true)
                speakResponse(err)
            }
        } catch (e: Exception) {
            val err = "Impossible de modifier la luminosité : ${e.localizedMessage ?: "erreur système"}."
            telemetryState.value = telemetryState.value.copy(
                voiceState = VoiceState.ERROR,
                lastErrorMessage = err
            )
            appendJarvisLog(err, isError = true)
            speakResponse(err)
        }
    }

    private fun openDeviceSettingsScreen(label: String, settingsAction: String) {
        val candidates = if (settingsAction == Intent.ACTION_POWER_USAGE_SUMMARY) {
            listOf(
                Intent.ACTION_POWER_USAGE_SUMMARY,
                Settings.ACTION_BATTERY_SAVER_SETTINGS,
                Settings.ACTION_SETTINGS
            )
        } else {
            listOf(settingsAction, Settings.ACTION_SETTINGS)
        }

        for (actionName in candidates) {
            try {
                val intent = Intent(actionName).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                val msg = "Ouverture des $label."
                appendJarvisLog(msg)
                speakResponse(msg)
                return
            } catch (_: Exception) {
                // Essayer le fallback suivant
            }
        }

        val err = "Impossible d'ouvrir les $label sur cet appareil."
        telemetryState.value = telemetryState.value.copy(
            voiceState = VoiceState.ERROR,
            lastErrorMessage = err
        )
        appendJarvisLog(err, isError = true)
        speakResponse(err)
    }

    private fun handleClearError() {
        telemetryState.value = telemetryState.value.copy(
            lastErrorMessage = null,
            quotaStatusMessage = null,
            voiceState = VoiceState.IDLE
        )
    }

    private fun handleSaveApiKey(newKey: String) {
        val cleanKey = newKey.trim()
        JarvisRuntimeStore.setGeminiApiKey(this, cleanKey)
        apiKeyState.value = cleanKey
        telemetryState.value = telemetryState.value.copy(
            hasApiKey = cleanKey.isNotBlank(),
            lastErrorMessage = null
        )
        val statusMsg = if (cleanKey.isNotBlank()) {
            "Clé API Gemini enregistrée dans l'appareil. Intelligence J.A.R.V.I.S. active."
        } else {
            "Clé API Gemini effacée de l'appareil."
        }
        appendJarvisLog(statusMsg)
    }

    // --- Interrupteur Vocal Permanent, Vosk Hors-Ligne, Tuile Rapide & OPPO ColorOS ---

    private fun isWakeServiceActive(): Boolean = JarvisRuntimeStore.runtimeState.value.isServiceRunning

    private fun hasRecordAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun handleQuickTileLaunchIntent(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(JarvisQuickTileService.EXTRA_START_LISTENING_FROM_TILE, false)) {
            return
        }
        intent.removeExtra(JarvisQuickTileService.EXTRA_START_LISTENING_FROM_TILE)
        triggerQuickTileListening()
    }

    private fun triggerQuickTileListening() {
        hapticController.playStarkPulse()
        cancelPendingDelayedMic()
        voiceEngine?.stop()

        if (!hasRecordAudioPermission()) {
            pendingTileListenAfterMicPermission = true
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (isWakeServiceActive()) {
            ContextCompat.startForegroundService(
                this,
                JarvisVoiceAssistantService.createStartIntent(
                    this,
                    JarvisVoiceAssistantService.ACTION_ACTIVATE_ASSISTANT
                )
            )
            appendJarvisLog("Écoute déclenchée depuis la tuile rapide « J.A.R.V.I.S. écoute ».")
        } else {
            telemetryState.value = telemetryState.value.copy(lastErrorMessage = null)
            speechController?.cancel()
            speechController?.startListening()
            appendJarvisLog("Écoute lancée depuis la tuile rapide.")
        }
    }

    private fun handleRetryVoskDownload() {
        hapticController.playStarkPulse()
        lifecycleScope.launch {
            appendJarvisLog("Téléchargement du modèle vocal français Vosk en cours...")
            when (val result = VoskModelManager.ensureModelReady(this@MainActivity, forceRetry = true)) {
                is VoskPrepareResult.Ready -> {
                    appendJarvisLog("Modèle vocal FR hors ligne prêt pour le mot-clé « jarvis ».")
                    if (isWakeServiceActive()) {
                        ContextCompat.startForegroundService(
                            this@MainActivity,
                            JarvisVoiceAssistantService.createStartIntent(
                                this@MainActivity,
                                JarvisVoiceAssistantService.ACTION_RELOAD_WAKE_ENGINE
                            )
                        )
                    }
                }
                is VoskPrepareResult.Failed -> {
                    appendJarvisLog(result.errorMessage, isError = true)
                    Toast.makeText(this@MainActivity, result.errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleBootResumeIntent(intent: Intent?) {
        if (intent == null || !intent.hasExtra(WakeNotificationHelper.EXTRA_RESUME_WAKE_SERVICE)) {
            return
        }
        val shouldResume = intent.getBooleanExtra(WakeNotificationHelper.EXTRA_RESUME_WAKE_SERVICE, false)
        intent.removeExtra(WakeNotificationHelper.EXTRA_RESUME_WAKE_SERVICE)

        if (shouldResume) {
            appendJarvisLog("Reprise de la veille vocale après démarrage demandée par l'utilisateur.")
            handleTogglePermanentWake(true)
        } else {
            WakeNotificationHelper.dismissBootRestoreNotification(this)
            JarvisRuntimeStore.setBootRestorePending(this, false)
            handleTogglePermanentWake(false)
        }
    }

    private fun handleTogglePermanentWake(enabled: Boolean) {
        hapticController.playStarkPulse()
        cancelPendingDelayedMic()

        if (enabled) {
            if (!hasRecordAudioPermission()) {
                pendingEnableWakeAfterMicPermission = true
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            requestNotificationPermissionIfNeeded()
            startPermanentWakeService(JarvisVoiceAssistantService.ACTION_START_STANDBY)
        } else {
            JarvisRuntimeStore.setPermanentWakeEnabled(this, false)
            JarvisRuntimeStore.setBootRestorePending(this, false)
            WakeNotificationHelper.dismissBootRestoreNotification(this)

            if (isWakeServiceActive()) {
                startService(
                    JarvisVoiceAssistantService.createStartIntent(
                        this,
                        JarvisVoiceAssistantService.ACTION_DISABLE_SERVICE
                    )
                )
            } else {
                stopService(Intent(this, JarvisVoiceAssistantService::class.java))
            }

            JarvisRuntimeStore.updateRuntime {
                it.copy(
                    isServiceRunning = false,
                    switchState = AssistantSwitchState.DISABLED,
                    isMicrophoneInUse = false,
                    bootRestoreNotice = null
                )
            }
            telemetryState.value = telemetryState.value.copy(
                isPermanentWakeEnabled = false,
                switchState = AssistantSwitchState.DISABLED,
                isMicrophoneInUse = false,
                bootRestoreNotice = null
            )
            appendJarvisLog("Interrupteur vocal permanent désactivé.")
        }
    }

    private fun startPermanentWakeService(action: String) {
        JarvisRuntimeStore.setPermanentWakeEnabled(this, true)
        JarvisRuntimeStore.setBootRestorePending(this, false)
        WakeNotificationHelper.dismissBootRestoreNotification(this)

        // Libérer immédiatement le SpeechRecognizer / TTS local de l'Activity pour éviter tout conflit micro
        speechController?.cancel()
        voiceEngine?.stop()
        clearConfirmationState(completeDeferredFalse = true)

        JarvisRuntimeStore.updateRuntime {
            it.copy(bootRestoreNotice = null, lastErrorMessage = null)
        }
        telemetryState.value = telemetryState.value.copy(
            isPermanentWakeEnabled = true,
            bootRestoreNotice = null,
            lastErrorMessage = null
        )

        try {
            ContextCompat.startForegroundService(
                this,
                JarvisVoiceAssistantService.createStartIntent(this, action)
            )
        } catch (e: Exception) {
            val err = "Impossible de démarrer le service vocal : ${e.localizedMessage ?: "erreur système"}."
            telemetryState.value = telemetryState.value.copy(
                voiceState = VoiceState.ERROR,
                lastErrorMessage = err
            )
            appendJarvisLog(err, isError = true)
        }
    }

    private fun handleToggleActiveStandby() {
        hapticController.playStarkPulse()
        if (!hasRecordAudioPermission()) {
            pendingActivateAfterMicPermission = true
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!isWakeServiceActive()) {
            requestNotificationPermissionIfNeeded()
            startPermanentWakeService(JarvisVoiceAssistantService.ACTION_ACTIVATE_ASSISTANT)
            return
        }

        val currentSwitch = telemetryState.value.switchState
        val nextAction = if (currentSwitch == AssistantSwitchState.STANDBY) {
            JarvisVoiceAssistantService.ACTION_ACTIVATE_ASSISTANT
        } else {
            JarvisVoiceAssistantService.ACTION_RETURN_STANDBY
        }
        ContextCompat.startForegroundService(
            this,
            JarvisVoiceAssistantService.createStartIntent(this, nextAction)
        )
    }

    private fun handleOpenBatteryOptimization() {
        hapticController.playStarkPulse()
        val opened = OppoPowerHelper.requestIgnoreBatteryOptimizations(this)
        if (opened) {
            appendJarvisLog("Ouverture du réglage d'exclusion d'optimisation batterie.")
        } else {
            appendJarvisLog("Impossible d'ouvrir directement les réglages batterie.", isError = true)
        }
    }

    private fun handleOpenOppoAutoStart() {
        hapticController.playStarkPulse()
        val guidance = OppoPowerHelper.openOppoAutoStartOrAppDetails(this)
        appendJarvisLog(guidance)
        Toast.makeText(this, guidance, Toast.LENGTH_LONG).show()
    }

    private fun speakResponse(textToSpeak: String, onDoneAction: (() -> Unit)? = null) {
        telemetryState.value = telemetryState.value.copy(
            voiceState = VoiceState.SPEAKING,
            isMicrophoneInUse = false
        )
        voiceEngine?.speak(
            text = textToSpeak,
            onStart = {
                telemetryState.value = telemetryState.value.copy(
                    voiceState = VoiceState.SPEAKING,
                    isMicrophoneInUse = false
                )
            },
            onDone = {
                telemetryState.value = telemetryState.value.copy(voiceState = VoiceState.IDLE)
                onDoneAction?.invoke()
            }
        )
    }

    private fun appendJarvisLog(message: String, isError: Boolean = false) {
        JarvisRuntimeStore.appendLog(
            text = message,
            isJarvis = true,
            isError = isError
        )
    }

    // --- Gestion du cycle de vie de l'application ---

    override fun onPause() {
        super.onPause()
        cancelPendingDelayedMic()
        speechController?.stopListening()
    }

    override fun onStop() {
        super.onStop()
        cancelPendingDelayedMic()
        speechController?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingDelayedMic()
        clearConfirmationState(completeDeferredFalse = true)
        agentJob?.cancel()
        agentNotificationHelper.dismiss()
        speechController?.destroy()
        voiceEngine?.shutdown()
        batteryReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
                // Ignore if already unregistered
            }
        }
        stopAgentReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
                // Ignore if already unregistered
            }
        }
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())
    }
}

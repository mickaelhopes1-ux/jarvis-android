package com.stark.jarvis.data

enum class VoiceState(val label: String, val badge: String) {
    IDLE("EN ATTENTE", "VEILLE"),
    LISTENING("ÉCOUTE EN COURS...", "MICRO ACTIF"),
    ANALYZING("ANALYSE DU SIGNAL...", "TRAITEMENT"),
    SPEAKING("RÉPONSE VOCALE", "SYNTHÈSE"),
    ERROR("ERREUR SYSTÈME", "ALERTE")
}

enum class AssistantSwitchState(val label: String, val badge: String) {
    STANDBY("J.A.R.V.I.S. EN VEILLE", "EN VEILLE"),
    ACTIVATED("J.A.R.V.I.S. ACTIVÉ", "ACTIVÉ"),
    LISTENING("J.A.R.V.I.S. EN ÉCOUTE", "EN ÉCOUTE"),
    DISABLED("J.A.R.V.I.S. DÉSACTIVÉ", "DÉSACTIVÉ")
}

data class ContactItem(
    val id: String,
    val name: String,
    val role: String,
    val phone: String,
    val initial: String
)

data class ReminderItem(
    val id: String,
    val text: String,
    val time: String,
    val isCompleted: Boolean = false
)

data class ConfirmationPromptUi(
    val title: String,
    val details: String
)

enum class VoskDownloadStage {
    NOT_DOWNLOADED,
    CHECKING,
    DOWNLOADING,
    EXTRACTING,
    READY,
    ERROR
}

data class VoskModelState(
    val stage: VoskDownloadStage = VoskDownloadStage.NOT_DOWNLOADED,
    val progress: Float = 0f,
    val statusText: String = "Modèle vocal français hors ligne requis (~42 Mo)",
    val errorMessage: String? = null,
    val modelPath: String? = null
)

data class JarvisTelemetry(
    val batteryPercent: Int = 88,
    val isCharging: Boolean = false,
    val torchActive: Boolean = false,
    val isNightMode: Boolean = true,
    val isAlertMode: Boolean = false,
    val location: String = "Paris, Secteur Alpha",
    val assistantName: String = "J.A.R.V.I.S.",
    val userName: String = "Mickaël",
    val voiceState: VoiceState = VoiceState.IDLE,
    val switchState: AssistantSwitchState = AssistantSwitchState.DISABLED,
    val isPermanentWakeEnabled: Boolean = false,
    val isMicrophoneInUse: Boolean = false,
    val isVoskModelReady: Boolean = false,
    val voskModelStatusText: String = "Vérification du modèle vocal FR...",
    val wakeWordModeLabel: String = "Aucun moteur armé",
    val bootRestoreNotice: String? = null,
    val lastRecognizedText: String = "",
    val lastErrorMessage: String? = null,
    val voiceRmsDb: Float = 0f,
    val hasApiKey: Boolean = false,
    val activeConfirmation: ConfirmationPromptUi? = null,
    val isAccessibilityEnabled: Boolean = false,
    val isNotificationListenerEnabled: Boolean = false,
    val isAgentRunning: Boolean = false,
    val agentStatusText: String = "",
    val quotaStatusMessage: String? = null
)

enum class JarvisTab {
    HOME,
    CALLS,
    MESSAGES,
    CAMERA
}

data class JarvisLog(
    val id: String,
    val text: String,
    val isJarvis: Boolean,
    val timestamp: String,
    val isError: Boolean = false
)

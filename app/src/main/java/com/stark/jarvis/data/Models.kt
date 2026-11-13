package com.stark.jarvis.data

enum class VoiceState(val label: String, val badge: String) {
    IDLE("EN ATTENTE", "VEILLE"),
    LISTENING("ÉCOUTE EN COURS...", "MICRO ACTIF"),
    ANALYZING("ANALYSE DU SIGNAL...", "TRAITEMENT"),
    SPEAKING("RÉPONSE VOCALE", "SYNTHÈSE"),
    ERROR("ERREUR SYSTÈME", "ALERTE")
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
    val lastRecognizedText: String = "",
    val lastErrorMessage: String? = null,
    val voiceRmsDb: Float = 0f,
    val hasApiKey: Boolean = false,
    val activeConfirmation: ConfirmationPromptUi? = null,
    val isAccessibilityEnabled: Boolean = false,
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

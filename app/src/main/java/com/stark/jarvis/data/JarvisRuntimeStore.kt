package com.stark.jarvis.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ServiceRuntimeSnapshot(
    val isServiceRunning: Boolean = false,
    val switchState: AssistantSwitchState = AssistantSwitchState.DISABLED,
    val voiceState: VoiceState = VoiceState.IDLE,
    val isMicrophoneInUse: Boolean = false,
    val wakeWordModeLabel: String = "Aucun moteur armé",
    val lastRecognizedText: String = "",
    val lastErrorMessage: String? = null,
    val quotaStatusMessage: String? = null,
    val torchActive: Boolean = false,
    val isAlertMode: Boolean = false,
    val activeConfirmation: ConfirmationPromptUi? = null,
    val isAgentRunning: Boolean = false,
    val agentStatusText: String = "",
    val bootRestoreNotice: String? = null
)

object JarvisRuntimeStore {

    private const val PREFS_NAME = "jarvis_prefs"

    const val KEY_GEMINI_API_KEY = "gemini_api_key"
    const val KEY_PERMANENT_WAKE_ENABLED = "permanent_wake_enabled"
    const val KEY_BOOT_RESTORE_PENDING = "boot_restore_pending"

    private val _runtimeState = MutableStateFlow(ServiceRuntimeSnapshot())
    val runtimeState: StateFlow<ServiceRuntimeSnapshot> = _runtimeState.asStateFlow()

    private val _voskState = MutableStateFlow(VoskModelState())
    val voskState: StateFlow<VoskModelState> = _voskState.asStateFlow()

    private val _logsState = MutableStateFlow<List<JarvisLog>>(
        listOf(
            JarvisLog(
                id = "init_1",
                text = "J.A.R.V.I.S. initialisé. Liaison vocale prête.",
                isJarvis = true,
                timestamp = currentTimeLabel()
            )
        )
    )
    val logsState: StateFlow<List<JarvisLog>> = _logsState.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun updateRuntime(transform: (ServiceRuntimeSnapshot) -> ServiceRuntimeSnapshot) {
        _runtimeState.value = transform(_runtimeState.value)
    }

    fun updateVoskState(state: VoskModelState) {
        _voskState.value = state
    }

    fun appendLog(text: String, isJarvis: Boolean, isError: Boolean = false) {
        val entry = JarvisLog(
            id = "${System.currentTimeMillis()}_${_logsState.value.size}",
            text = text,
            isJarvis = isJarvis,
            timestamp = currentTimeLabel(),
            isError = isError
        )
        _logsState.value = (_logsState.value + entry).takeLast(40)
    }

    // --- Préférences durables (Clé Gemini & Interrupteur ON/OFF) ---

    fun getGeminiApiKey(context: Context): String =
        prefs(context).getString(KEY_GEMINI_API_KEY, "")?.trim().orEmpty()

    fun setGeminiApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_GEMINI_API_KEY, key.trim()).commit()
    }

    fun isPermanentWakeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERMANENT_WAKE_ENABLED, false)

    fun setPermanentWakeEnabled(context: Context, enabled: Boolean) {
        // commit() synchrone pour survivre immédiatement à un redémarrage téléphone
        prefs(context).edit().putBoolean(KEY_PERMANENT_WAKE_ENABLED, enabled).commit()
    }

    fun isBootRestorePending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BOOT_RESTORE_PENDING, false)

    fun setBootRestorePending(context: Context, pending: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOOT_RESTORE_PENDING, pending).commit()
    }

    fun currentTimeLabel(): String =
        SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())
}

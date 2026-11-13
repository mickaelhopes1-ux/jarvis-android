package com.stark.jarvis.controllers

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.util.Log
import java.util.Locale

class TorchController(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private var isTorchOn = false

    fun toggleTorch(): Boolean {
        return try {
            val cameraId = cameraManager?.cameraIdList?.firstOrNull() ?: return false
            isTorchOn = !isTorchOn
            cameraManager?.setTorchMode(cameraId, isTorchOn)
            isTorchOn
        } catch (e: Exception) {
            Log.e("TorchController", "Erreur bascule torche", e)
            false
        }
    }

    fun setTorch(enable: Boolean): Boolean {
        return try {
            val cameraId = cameraManager?.cameraIdList?.firstOrNull() ?: return false
            isTorchOn = enable
            cameraManager?.setTorchMode(cameraId, enable)
            isTorchOn
        } catch (e: Exception) {
            Log.e("TorchController", "Erreur réglage torche", e)
            false
        }
    }

    fun isEnabled(): Boolean = isTorchOn
}

class HapticController(private val context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun playStarkPulse() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 80, 50, 120)
                val amplitudes = intArrayOf(0, 180, 0, 255)
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(100)
            }
        } catch (e: Exception) {
            Log.e("HapticController", "Erreur haptique", e)
        }
    }
}

class VoiceEngine(
    private val context: Context,
    private val onReady: () -> Unit
) : TextToSpeech.OnInitListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isInitialized = false
    private var currentOnDone: (() -> Unit)? = null
    private var currentOnStart: (() -> Unit)? = null
    private var activeUtteranceId: String? = null

    init {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != null && utteranceId == activeUtteranceId) {
                    val startCallback = currentOnStart
                    mainHandler.post {
                        startCallback?.invoke()
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != null && utteranceId == activeUtteranceId) {
                    val doneCallback = currentOnDone
                    activeUtteranceId = null
                    currentOnStart = null
                    currentOnDone = null
                    mainHandler.post {
                        doneCallback?.invoke()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId != null && utteranceId == activeUtteranceId) {
                    val doneCallback = currentOnDone
                    activeUtteranceId = null
                    currentOnStart = null
                    currentOnDone = null
                    mainHandler.post {
                        doneCallback?.invoke()
                    }
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId != null && utteranceId == activeUtteranceId) {
                    val doneCallback = currentOnDone
                    activeUtteranceId = null
                    currentOnStart = null
                    currentOnDone = null
                    mainHandler.post {
                        doneCallback?.invoke()
                    }
                }
            }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.FRENCH)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isInitialized = true
                tts?.setPitch(0.95f)
                tts?.setSpeechRate(1.05f)
                mainHandler.post { onReady() }
            }
        }
    }

    fun speak(
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (isInitialized && tts != null) {
            val utteranceId = "JARVIS_${System.currentTimeMillis()}"
            activeUtteranceId = utteranceId
            currentOnStart = onStart
            currentOnDone = onDone
            val status = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) ?: TextToSpeech.ERROR
            if (status == TextToSpeech.ERROR) {
                activeUtteranceId = null
                currentOnStart = null
                currentOnDone = null
                mainHandler.post { onDone?.invoke() }
            }
        } else {
            mainHandler.post { onDone?.invoke() }
        }
    }

    fun stop() {
        activeUtteranceId = null
        currentOnStart = null
        currentOnDone = null
        tts?.stop()
    }

    fun shutdown() {
        activeUtteranceId = null
        currentOnStart = null
        currentOnDone = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

object PhoneActions {
    fun makeCall(context: Context, phoneNumber: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: SecurityException) {
            // Si la permission CALL_PHONE n'est pas encore validée, fallback vers le composeur
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
        }
    }

    fun makeCallStrict(context: Context, phoneNumber: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("PhoneActions", "Échec lancement appel ACTION_CALL", e)
            false
        }
    }

    fun sendSmsDirect(context: Context, phoneNumber: String, message: String): Boolean {
        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            }
            true
        } catch (e: Exception) {
            // Fallback vers l'appli SMS par défaut
            val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phoneNumber")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(smsIntent)
            false
        }
    }

    fun sendSmsStrict(context: Context, phoneNumber: String, message: String): Boolean {
        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            }
            true
        } catch (e: Exception) {
            Log.e("PhoneActions", "Échec envoi SMS via SmsManager", e)
            false
        }
    }
}

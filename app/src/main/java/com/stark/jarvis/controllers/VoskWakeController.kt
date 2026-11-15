package com.stark.jarvis.controllers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

sealed class VoskWakeStartResult {
    data class Started(val modeLabel: String) : VoskWakeStartResult()
    data class ModelMissing(val reason: String) : VoskWakeStartResult()
    data class Failed(val reason: String) : VoskWakeStartResult()
}

/**
 * Détecteur local hors ligne du mot d'activation « jarvis » basé sur Vosk Android,
 * sans compte ni clé API, avec grammaire fermée ["jarvis", "[unk]"] pour économiser la batterie.
 * Libère intégralement le microphone lors de stop() avant de passer la main à SpeechRecognizer.
 */
class VoskWakeController {

    private companion object {
        const val TAG = "VoskWakeController"
        const val SAMPLE_RATE = 16000.0f
        const val WAKE_GRAMMAR_JSON = "[\"jarvis\", \"[unk]\"]"
    }

    private val lock = Any()
    private var loadedModelPath: String? = null
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private val wakeTriggered = AtomicBoolean(false)

    @Volatile
    private var isListening = false

    fun isActive(): Boolean = isListening

    suspend fun start(
        modelPath: String,
        onWakeWordDetected: () -> Unit,
        onEngineError: (String) -> Unit
    ): VoskWakeStartResult {
        if (modelPath.isBlank()) {
            return VoskWakeStartResult.ModelMissing("Modèle vocal français Vosk introuvable.")
        }

        // 1. Arrêter toute session précédente pour libérer le micro
        stop()

        // 2. Charger le modèle et la grammaire sur Dispatchers.IO pour ne jamais bloquer le thread principal
        val recognizerResult = withContext(Dispatchers.IO) {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                synchronized(lock) {
                    if (voskModel == null || loadedModelPath != modelPath) {
                        voskModel?.close()
                        voskModel = Model(modelPath)
                        loadedModelPath = modelPath
                    }
                    val model = voskModel ?: return@withContext Result.failure<Recognizer>(
                        IOException("Modèle Vosk non initialisé.")
                    )
                    val recognizer = Recognizer(model, SAMPLE_RATE, WAKE_GRAMMAR_JSON)
                    voskRecognizer?.close()
                    voskRecognizer = recognizer
                    Result.success(recognizer)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        val recognizer = recognizerResult.getOrElse { err ->
            Log.e(TAG, "Échec chargement modèle/grammaire Vosk", err)
            stop()
            return VoskWakeStartResult.Failed(
                "Impossible d'initialiser le moteur hors ligne Vosk : ${err.localizedMessage ?: "erreur modèle"}."
            )
        }

        // 3. Démarrer SpeechService sur le thread principal avec la grammaire ["jarvis", "[unk]"]
        return withContext(Dispatchers.Main.immediate) {
            try {
                wakeTriggered.set(false)
                val service = SpeechService(recognizer, SAMPLE_RATE)

                val listener = object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        checkHypothesis(hypothesis, "partial", onWakeWordDetected)
                    }

                    override fun onResult(hypothesis: String?) {
                        checkHypothesis(hypothesis, "text", onWakeWordDetected)
                    }

                    override fun onFinalResult(hypothesis: String?) {
                        checkHypothesis(hypothesis, "text", onWakeWordDetected)
                    }

                    override fun onError(exception: Exception?) {
                        if (!isListening) return
                        Log.e(TAG, "Erreur flux audio Vosk", exception)
                        isListening = false
                        val msg = "Erreur microphone veille Vosk : ${exception?.localizedMessage ?: "flux interrompu"}."
                        onEngineError(msg)
                    }

                    override fun onTimeout() {
                        // La veille permanente ne fixe pas de timeout ; ignoré
                    }
                }

                val started = service.startListening(listener)
                if (!started) {
                    service.shutdown()
                    stop()
                    return@withContext VoskWakeStartResult.Failed(
                        "Impossible de démarrer l'écoute veille Vosk (micro occupé)."
                    )
                }

                synchronized(lock) {
                    speechService = service
                }
                isListening = true
                Log.i(TAG, "Veille hors ligne Vosk active (grammaire : [\"jarvis\", \"[unk]\"])")
                VoskWakeStartResult.Started("Vosk FR hors ligne : mot-clé « jarvis »")
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission microphone refusée pour Vosk", e)
                stop()
                VoskWakeStartResult.Failed("Permission microphone requise pour la veille vocale.")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur démarrage SpeechService Vosk", e)
                stop()
                VoskWakeStartResult.Failed(
                    "Impossible d'ouvrir le microphone pour Vosk : ${e.localizedMessage ?: "erreur audio"}."
                )
            }
        }
    }

    private fun checkHypothesis(
        rawJson: String?,
        jsonKey: String,
        onWakeWordDetected: () -> Unit
    ) {
        if (!isListening || rawJson.isNullOrBlank()) return
        try {
            val obj = JSONObject(rawJson)
            val text = obj.optString(jsonKey, "").trim().lowercase()
            if (text.isBlank()) return

            val tokens = text.split(Regex("\\s+"))
            if (tokens.any { it == "jarvis" }) {
                if (wakeTriggered.compareAndSet(false, true)) {
                    Log.i(TAG, "Mot d'activation « jarvis » détecté par Vosk ($jsonKey=\"$text\")")
                    // Libérer immédiatement le microphone de Vosk avant d'activer SpeechRecognizer
                    stop()
                    onWakeWordDetected()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hypothèse JSON Vosk ignorée : $rawJson", e)
        }
    }

    fun stop() {
        isListening = false
        synchronized(lock) {
            val svc = speechService
            speechService = null
            if (svc != null) {
                try {
                    svc.stop()
                } catch (_: Exception) {
                    // Ignorer si déjà arrêté
                }
                try {
                    svc.shutdown()
                } catch (e: Exception) {
                    Log.w(TAG, "Erreur lors de l'arrêt SpeechService Vosk", e)
                }
            }

            val rec = voskRecognizer
            voskRecognizer = null
            if (rec != null) {
                try {
                    rec.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Erreur fermeture Recognizer Vosk", e)
                }
            }
        }
    }

    fun release() {
        stop()
        synchronized(lock) {
            loadedModelPath = null
            val model = voskModel
            voskModel = null
            if (model != null) {
                try {
                    model.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Erreur fermeture Model Vosk", e)
                }
            }
        }
    }
}

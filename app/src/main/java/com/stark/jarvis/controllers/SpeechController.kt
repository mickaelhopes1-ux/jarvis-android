package com.stark.jarvis.controllers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.stark.jarvis.data.VoiceState

interface SpeechListener {
    fun onStateChanged(state: VoiceState)
    fun onSpeechPartial(partialText: String)
    fun onSpeechResult(resultText: String)
    fun onError(errorMessage: String, errorCode: Int)
    fun onRmsChanged(rmsdB: Float) {}
}

class SpeechController(
    private val context: Context,
    private val listener: SpeechListener
) {
    // Constructeur secondaire pour syntaxe lambda directe
    constructor(
        context: Context,
        onStateChanged: (VoiceState) -> Unit,
        onSpeechPartial: (String) -> Unit,
        onSpeechResult: (String) -> Unit,
        onError: (String, Int) -> Unit,
        onRmsChanged: ((Float) -> Unit)? = null
    ) : this(
        context,
        object : SpeechListener {
            override fun onStateChanged(state: VoiceState) = onStateChanged(state)
            override fun onSpeechPartial(partialText: String) = onSpeechPartial(partialText)
            override fun onSpeechResult(resultText: String) = onSpeechResult(resultText)
            override fun onError(errorMessage: String, errorCode: Int) = onError(errorMessage, errorCode)
            override fun onRmsChanged(rmsdB: Float) { onRmsChanged?.invoke(rmsdB) }
        }
    )

    private val TAG = "SpeechController"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech: Micro prêt à écouter")
            isListening = true
            mainHandler.post {
                listener.onStateChanged(VoiceState.LISTENING)
            }
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech: Signal vocal détecté")
            mainHandler.post {
                listener.onStateChanged(VoiceState.LISTENING)
            }
        }

        override fun onRmsChanged(rmsdB: Float) {
            mainHandler.post {
                listener.onRmsChanged(rmsdB)
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech: Fin de transmission vocale")
            isListening = false
            mainHandler.post {
                listener.onStateChanged(VoiceState.ANALYZING)
            }
        }

        override fun onError(errorCode: Int) {
            isListening = false
            val errorMsg = mapErrorCodeToMessage(errorCode)
            Log.w(TAG, "onError: code=$errorCode, message=$errorMsg")

            mainHandler.post {
                listener.onError(errorMsg, errorCode)
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val recognizedText = matches?.firstOrNull() ?: ""
            Log.d(TAG, "onResults: « $recognizedText »")

            mainHandler.post {
                if (recognizedText.isNotBlank()) {
                    listener.onStateChanged(VoiceState.ANALYZING)
                    listener.onSpeechResult(recognizedText)
                } else {
                    listener.onError("Aucune parole détectée dans le signal audio.", SpeechRecognizer.ERROR_NO_MATCH)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partialText = matches?.firstOrNull() ?: ""
            if (partialText.isNotBlank()) {
                mainHandler.post {
                    listener.onSpeechPartial(partialText)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun isListeningActive(): Boolean = isListening

    fun startListening(extendedSilenceTimeoutMs: Long? = null) {
        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    val msg = "Moteur de reconnaissance vocale indisponible. Vérifiez que l'application Google est installée et activée."
                    listener.onError(msg, -1)
                    return@post
                }

                // Réinitialisation sécurisée
                destroyRecognizer()

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(recognitionListener)
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "fr-FR")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    if (extendedSilenceTimeoutMs != null) {
                        putExtra(
                            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                            extendedSilenceTimeoutMs
                        )
                        putExtra(
                            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                            extendedSilenceTimeoutMs
                        )
                    }
                }

                listener.onStateChanged(VoiceState.LISTENING)
                speechRecognizer?.startListening(intent)
                Log.d(TAG, "SpeechRecognizer démarré avec succès en français")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur au démarrage de SpeechRecognizer", e)
                listener.onError("Erreur d'initialisation du microphone : ${e.localizedMessage}", -1)
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Erreur arrêt SpeechRecognizer", e)
            }
        }
    }

    fun cancel() {
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.cancel()
                listener.onStateChanged(VoiceState.IDLE)
            } catch (e: Exception) {
                Log.e(TAG, "Erreur annulation SpeechRecognizer", e)
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            destroyRecognizer()
        }
    }

    private fun destroyRecognizer() {
        try {
            isListening = false
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Erreur destruction SpeechRecognizer", e)
        }
    }

    private fun mapErrorCodeToMessage(code: Int): String {
        return when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "Erreur de capture audio. Vérifiez le microphone de votre appareil."
            SpeechRecognizer.ERROR_CLIENT -> "Erreur interne Android SpeechRecognizer. Réessayez."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission Microphone refusée. Activez l'autorisation dans les paramètres."
            SpeechRecognizer.ERROR_NETWORK -> "Erreur réseau. Une connexion Internet active est recommandée."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Délai de réponse réseau dépassé."
            SpeechRecognizer.ERROR_NO_MATCH -> "Aucune parole reconnue. Rapprochez-vous du micro et parlez distinctement."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Service vocal occupé. Veuillez patienter une seconde."
            SpeechRecognizer.ERROR_SERVER -> "Erreur du serveur de reconnaissance Google."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Aucune voix détectée. Appuyez sur le micro et parlez immédiatement."
            else -> "Erreur de signal vocal (Code $code)."
        }
    }
}

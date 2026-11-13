package com.stark.jarvis.controllers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

sealed class GeminiResult {
    data class Success(val text: String) : GeminiResult()
    data class Error(val userMessage: String) : GeminiResult()
}

sealed class AgentStepCommand {
    abstract val reason: String
    abstract val isSensitive: Boolean

    data class OpenApp(
        val appName: String,
        override val reason: String,
        override val isSensitive: Boolean
    ) : AgentStepCommand()

    data class ClickText(
        val targetText: String,
        override val reason: String,
        override val isSensitive: Boolean
    ) : AgentStepCommand()

    data class Scroll(
        val down: Boolean,
        override val reason: String,
        override val isSensitive: Boolean
    ) : AgentStepCommand()

    data class TypeText(
        val text: String,
        override val reason: String,
        override val isSensitive: Boolean
    ) : AgentStepCommand()

    data class Back(
        override val reason: String,
        override val isSensitive: Boolean
    ) : AgentStepCommand()

    data class Home(
        override val reason: String,
        override val isSensitive: Boolean
    ) : AgentStepCommand()

    data class Wait(
        val durationMs: Long,
        override val reason: String,
        override val isSensitive: Boolean
    ) : AgentStepCommand()

    data class Done(
        val message: String,
        override val reason: String,
        override val isSensitive: Boolean
    ) : AgentStepCommand()

    data class AskUser(
        val question: String,
        override val reason: String,
        override val isSensitive: Boolean
    ) : AgentStepCommand()
}

sealed class AgentDecisionResult {
    data class Step(val command: AgentStepCommand) : AgentDecisionResult()
    data class Error(val userMessage: String) : AgentDecisionResult()
}

class GeminiClient {

    companion object {
        private const val TAG = "GeminiClient"
        private const val PRIMARY_MODEL = "gemini-2.5-flash"
        private const val FALLBACK_MODEL = "gemini-2.5-flash-lite"
        private const val LIGHTWEIGHT_MODEL = "gemini-2.5-flash-lite"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MAX_429_RETRIES = 3
        private val DEFAULT_RETRY_BACKOFF_MS = longArrayOf(4000L, 8000L, 15000L)

        private const val SYSTEM_PROMPT =
            "Tu es J.A.R.V.I.S., assistant vocal en français, réponses courtes (2 phrases max)."

        private const val AGENT_SYSTEM_PROMPT = """Agent Android J.A.R.V.I.S. Choisis 1 action JSON strict (sans markdown) parmi la liste fermée :
open_app(target), click_text(target), scroll(direction:down|up), type_text(text), back, home, wait(ms), done(message), ask_user(question).
Format: {"action":"...","target":"...","reason":"...","sensitive":false}
Mets sensitive=true si envoyer/payer/supprimer/installer/sécurité. Format écran: texte|C(cliquable)E(éditable)."""

        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 15000
    }

    suspend fun generateReply(
        prompt: String,
        apiKey: String,
        onRetryStatus: ((String) -> Unit)? = null
    ): GeminiResult {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return GeminiResult.Error(
                "Aucune clé API Gemini configurée. Appuyez sur l'icône Paramètres (⚙️) pour enregistrer votre clé."
            )
        }

        if (prompt.isBlank()) {
            return GeminiResult.Error("Requête vocale vide.")
        }

        val attempt = executeWithQuotaRetry(
            systemPrompt = SYSTEM_PROMPT,
            prompt = prompt,
            apiKey = cleanKey,
            onRetryStatus = onRetryStatus
        )
        return mapAttemptToResult(attempt)
    }

    suspend fun generateAgentStep(
        goal: String,
        step: Int,
        maxSteps: Int,
        activePackage: String,
        screenCompactText: String,
        history: List<String>,
        apiKey: String,
        onRetryStatus: ((String) -> Unit)? = null
    ): AgentDecisionResult {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return AgentDecisionResult.Error(
                "Aucune clé API Gemini configurée pour l'agent. Appuyez sur Paramètres (⚙️) pour ajouter votre clé."
            )
        }

        val historyText = if (history.isEmpty()) {
            "aucun"
        } else {
            history.takeLast(3).joinToString(" ; ")
        }

        val userPrompt = buildString {
            appendLine("BUT:$goal")
            appendLine("ETAPE:$step/$maxSteps APP:${activePackage.ifBlank { "?" }}")
            appendLine("HIST:$historyText")
            appendLine("ECRAN:")
            appendLine(screenCompactText)
        }

        val attempt = executeWithQuotaRetry(
            systemPrompt = AGENT_SYSTEM_PROMPT,
            prompt = userPrompt,
            apiKey = cleanKey,
            onRetryStatus = onRetryStatus
        )

        return when (val result = mapAttemptToResult(attempt)) {
            is GeminiResult.Error -> AgentDecisionResult.Error(result.userMessage)
            is GeminiResult.Success -> parseStrictAgentJson(result.text)
        }
    }

    private suspend fun executeWithQuotaRetry(
        systemPrompt: String,
        prompt: String,
        apiKey: String,
        onRetryStatus: ((String) -> Unit)?
    ): HttpAttemptResult {
        var currentModel = PRIMARY_MODEL
        var rateLimitRetries = 0
        var modelNotFoundCount = 0

        while (true) {
            coroutineContext.ensureActive()
            when (val attempt = executeRequest(currentModel, systemPrompt, prompt, apiKey)) {
                is HttpAttemptResult.ModelNotFound -> {
                    modelNotFoundCount++
                    currentModel = when {
                        currentModel == PRIMARY_MODEL -> FALLBACK_MODEL
                        currentModel == FALLBACK_MODEL -> LIGHTWEIGHT_MODEL
                        else -> return HttpAttemptResult.ModelNotFound
                    }
                    if (modelNotFoundCount > 3) return HttpAttemptResult.ModelNotFound
                }
                is HttpAttemptResult.RateLimited -> {
                    if (rateLimitRetries >= MAX_429_RETRIES) {
                        return HttpAttemptResult.Failure(
                            "Quota Gemini atteint même après $MAX_429_RETRIES tentatives (y compris sur $LIGHTWEIGHT_MODEL). Patientez quelques instants."
                        )
                    }

                    val defaultWait = DEFAULT_RETRY_BACKOFF_MS[
                        rateLimitRetries.coerceAtMost(DEFAULT_RETRY_BACKOFF_MS.lastIndex)
                    ]
                    val waitMs = attempt.retryDelayMs?.coerceIn(2000L, 45000L)
                        ?.coerceAtLeast(defaultWait)
                        ?: defaultWait

                    val waitSeconds = ceil(waitMs / 1000.0).toLong().coerceAtLeast(1L)
                    rateLimitRetries++

                    // Bascule vers le modèle plus léger gemini-2.5-flash-lite dès le premier 429
                    if (currentModel != LIGHTWEIGHT_MODEL) {
                        currentModel = LIGHTWEIGHT_MODEL
                    }

                    val statusMessage = "Quota atteint, nouvelle tentative dans $waitSeconds secondes."
                    Log.w(TAG, "$statusMessage (essai $rateLimitRetries/$MAX_429_RETRIES, modèle=$currentModel)")
                    withContext(Dispatchers.Main) {
                        onRetryStatus?.invoke(statusMessage)
                    }

                    delay(waitMs)
                }
                else -> return attempt
            }
        }
    }

    private fun parseStrictAgentJson(rawText: String): AgentDecisionResult {
        val stripped = rawText.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val firstBrace = stripped.indexOf('{')
        val lastBrace = stripped.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            return AgentDecisionResult.Error("Réponse de l'agent invalide (JSON introuvable).")
        }

        val jsonText = stripped.substring(firstBrace, lastBrace + 1)
        return try {
            val obj = JSONObject(jsonText)
            val action = obj.optString("action", "").trim().lowercase()
            val reason = obj.optString("reason", "").trim()
            val sensitive = obj.optBoolean("sensitive", false)

            when (action) {
                "open_app" -> {
                    val app = obj.optString("target", obj.optString("app", "")).trim()
                    if (app.isBlank()) {
                        AgentDecisionResult.Error("Action open_app sans nom d'application.")
                    } else {
                        AgentDecisionResult.Step(AgentStepCommand.OpenApp(app, reason, sensitive))
                    }
                }
                "click_text" -> {
                    val target = obj.optString("target", obj.optString("text", "")).trim()
                    if (target.isBlank()) {
                        AgentDecisionResult.Error("Action click_text sans cible textuelle.")
                    } else {
                        AgentDecisionResult.Step(AgentStepCommand.ClickText(target, reason, sensitive))
                    }
                }
                "scroll" -> {
                    val dir = obj.optString("direction", "down").trim().lowercase()
                    val isDown = dir != "up" && dir != "haut"
                    AgentDecisionResult.Step(AgentStepCommand.Scroll(isDown, reason, sensitive))
                }
                "type_text" -> {
                    val txt = obj.optString("text", obj.optString("target", "")).trim()
                    if (txt.isBlank()) {
                        AgentDecisionResult.Error("Action type_text sans texte à saisir.")
                    } else {
                        AgentDecisionResult.Step(AgentStepCommand.TypeText(txt, reason, sensitive))
                    }
                }
                "back" -> AgentDecisionResult.Step(AgentStepCommand.Back(reason, sensitive))
                "home" -> AgentDecisionResult.Step(AgentStepCommand.Home(reason, sensitive))
                "wait" -> {
                    val ms = obj.optLong("ms", 1200L).coerceIn(400L, 4000L)
                    AgentDecisionResult.Step(AgentStepCommand.Wait(ms, reason, sensitive))
                }
                "done" -> {
                    val msg = obj.optString("message", reason.ifBlank { "Tâche terminée." }).trim()
                    AgentDecisionResult.Step(AgentStepCommand.Done(msg, reason, sensitive))
                }
                "ask_user" -> {
                    val q = obj.optString("question", obj.optString("message", "Comment souhaitez-vous continuer ?")).trim()
                    AgentDecisionResult.Step(AgentStepCommand.AskUser(q, reason, sensitive))
                }
                else -> {
                    AgentDecisionResult.Error("Action non autorisée rejetée par sécurité : « $action ».")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur parsing JSON agent : $jsonText", e)
            AgentDecisionResult.Error("Réponse JSON de l'agent mal formée.")
        }
    }

    private sealed class HttpAttemptResult {
        data class Ok(val text: String) : HttpAttemptResult()
        object ModelNotFound : HttpAttemptResult()
        data class RateLimited(val retryDelayMs: Long?) : HttpAttemptResult()
        data class Failure(val userMessage: String) : HttpAttemptResult()
    }

    private suspend fun executeRequest(
        model: String,
        systemPrompt: String,
        prompt: String,
        apiKey: String
    ): HttpAttemptResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/$model:generateContent")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("x-goog-api-key", apiKey)
            }

            val payload = buildRequestJson(systemPrompt, prompt)
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val rawBody = if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            } else {
                ""
            }

            if (responseCode in 200..299) {
                val parsedText = extractReplyText(rawBody)
                if (parsedText.isNotBlank()) {
                    HttpAttemptResult.Ok(parsedText)
                } else {
                    HttpAttemptResult.Failure("Réponse vide reçue de Gemini.")
                }
            } else {
                Log.w(TAG, "Erreur HTTP $responseCode pour le modèle $model")
                when (responseCode) {
                    HttpURLConnection.HTTP_NOT_FOUND -> HttpAttemptResult.ModelNotFound
                    429 -> HttpAttemptResult.RateLimited(extractRetryDelayMs(rawBody))
                    else -> HttpAttemptResult.Failure(mapHttpError(responseCode, rawBody))
                }
            }
        } catch (e: UnknownHostException) {
            HttpAttemptResult.Failure(
                "Pas de connexion Internet. Vérifiez votre Wi-Fi ou votre réseau mobile."
            )
        } catch (e: ConnectException) {
            HttpAttemptResult.Failure(
                "Impossible de joindre le serveur Gemini. Vérifiez votre connexion réseau."
            )
        } catch (e: SocketTimeoutException) {
            HttpAttemptResult.Failure(
                "Délai d'attente dépassé : le serveur Gemini met trop de temps à répondre."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de l'appel Gemini", e)
            HttpAttemptResult.Failure(
                "Erreur de communication avec Gemini : ${e.localizedMessage ?: "erreur inconnue"}"
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractRetryDelayMs(rawBody: String): Long? {
        try {
            val root = JSONObject(rawBody)
            val error = root.optJSONObject("error") ?: return null
            val details = error.optJSONArray("details")
            if (details != null) {
                for (i in 0 until details.length()) {
                    val detail = details.optJSONObject(i) ?: continue
                    val retryStr = detail.optString("retryDelay", "").trim()
                    parseDurationSecondsToMs(retryStr)?.let { return it }
                }
            }
            val message = error.optString("message", "")
            val msgMatch = Regex("retry\\s+in\\s+(\\d+(?:\\.\\d+)?)s", RegexOption.IGNORE_CASE).find(message)
            if (msgMatch != null) {
                val secs = msgMatch.groupValues[1].toDoubleOrNull()
                if (secs != null && secs > 0) {
                    return ceil(secs * 1000.0).toLong()
                }
            }
        } catch (_: Exception) {
            // Ignore parse issues and fall back to default backoff
        }
        return null
    }

    private fun parseDurationSecondsToMs(durationStr: String): Long? {
        if (durationStr.isBlank()) return null
        val match = Regex("^(\\d+(?:\\.\\d+)?)s$", RegexOption.IGNORE_CASE).find(durationStr.trim()) ?: return null
        val seconds = match.groupValues[1].toDoubleOrNull() ?: return null
        return if (seconds > 0) ceil(seconds * 1000.0).toLong() else null
    }

    private fun buildRequestJson(systemPrompt: String, prompt: String): JSONObject {
        val systemPart = JSONObject().put("text", systemPrompt)
        val systemInstruction = JSONObject().put("parts", JSONArray().put(systemPart))

        val userPart = JSONObject().put("text", prompt)
        val userContent = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(userPart))

        return JSONObject()
            .put("systemInstruction", systemInstruction)
            .put("contents", JSONArray().put(userContent))
    }

    private fun extractReplyText(rawBody: String): String {
        val root = JSONObject(rawBody)
        val candidates = root.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""

        val firstCandidate = candidates.optJSONObject(0) ?: return ""
        val content = firstCandidate.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""

        val builder = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val text = part.optString("text", "")
            if (text.isNotBlank()) {
                if (builder.isNotEmpty()) builder.append(" ")
                builder.append(text.trim())
            }
        }
        return builder.toString().trim()
    }

    private fun mapHttpError(code: Int, rawBody: String): String {
        val apiErrorMessage = try {
            JSONObject(rawBody).optJSONObject("error")?.optString("message", "") ?: ""
        } catch (_: Exception) {
            ""
        }
        val lowerMsg = apiErrorMessage.lowercase()

        return when (code) {
            HttpURLConnection.HTTP_BAD_REQUEST -> {
                if (lowerMsg.contains("api key") || lowerMsg.contains("key_invalid")) {
                    "Clé API Gemini invalide. Vérifiez votre clé dans les Paramètres (⚙️)."
                } else {
                    "Requête refusée par Gemini (Erreur 400). Vérifiez votre clé API."
                }
            }
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> {
                "Clé API Gemini invalide ou non autorisée (Erreur $code). Vérifiez vos Paramètres."
            }
            429 -> {
                "Quota de requêtes Gemini dépassé (Erreur 429). Patientez quelques instants avant de réessayer."
            }
            in 500..599 -> {
                "Serveur Gemini temporairement saturé ou indisponible (Erreur $code). Réessayez."
            }
            else -> {
                "Erreur du service Gemini (Code HTTP $code)."
            }
        }
    }

    private fun mapAttemptToResult(attempt: HttpAttemptResult): GeminiResult {
        return when (attempt) {
            is HttpAttemptResult.Ok -> GeminiResult.Success(attempt.text)
            is HttpAttemptResult.ModelNotFound -> GeminiResult.Error(
                "Modèle Gemini Flash introuvable sur l'API (Erreur 404)."
            )
            is HttpAttemptResult.RateLimited -> GeminiResult.Error(
                "Quota de requêtes Gemini dépassé (Erreur 429). Patientez quelques instants."
            )
            is HttpAttemptResult.Failure -> GeminiResult.Error(attempt.userMessage)
        }
    }
}

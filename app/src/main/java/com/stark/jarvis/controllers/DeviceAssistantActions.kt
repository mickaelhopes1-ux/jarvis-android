package com.stark.jarvis.controllers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.util.Log
import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

data class ResolvedContact(
    val name: String,
    val phoneNumber: String
)

sealed class ContactLookupResult {
    data class Found(val contact: ResolvedContact) : ContactLookupResult()
    data class Ambiguous(val candidates: List<ResolvedContact>) : ContactLookupResult()
    object NotFound : ContactLookupResult()
}

data class SmsTargetLookupResult(
    val lookup: ContactLookupResult,
    val resolvedMessage: String
)

sealed class AppLaunchLookupResult {
    data class Found(
        val appName: String,
        val packageName: String,
        val launchIntent: Intent
    ) : AppLaunchLookupResult()
    data class Ambiguous(val candidates: List<String>) : AppLaunchLookupResult()
    object NotFound : AppLaunchLookupResult()
}

object DeviceAssistantActions {

    private const val TAG = "DeviceAssistantActions"

    fun normalizeForSearch(input: String): String {
        val decomposed = Normalizer.normalize(input.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
        val withoutAccents = decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return withoutAccents
            .replace(Regex("[^a-z0-9+\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun extractPhoneNumber(rawTarget: String): String? {
        val cleaned = rawTarget
            .trim()
            .replace(Regex("^(?:le|la|au|à|a|numero|numéro)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()

        if (cleaned.isEmpty()) return null

        // Si des lettres sont présentes, ce n'est pas un numéro pur
        if (cleaned.any { it.isLetter() }) return null

        val compact = cleaned.replace(Regex("[\\s.\\-()]"), "")
        val digitsOnly = compact.filter { it.isDigit() }
        return if (digitsOnly.length >= 4 && compact.matches(Regex("^\\+?\\d+$"))) {
            compact
        } else {
            null
        }
    }

    fun extractLeadingPhoneNumberAndRemainder(rawText: String): Pair<String, String>? {
        val trimmed = rawText.trim()
        val match = Regex("^(\\+?\\d[\\d\\s.\\-()]{3,20}\\d)(?:\\s+(.*))?$").find(trimmed) ?: return null
        val numberPart = match.groupValues[1].replace(Regex("[\\s.\\-()]"), "")
        val remainder = match.groupValues[2].trim()
        return if (numberPart.count { it.isDigit() } >= 4) {
            numberPart to remainder
        } else {
            null
        }
    }

    fun readPhoneContacts(context: Context): List<ResolvedContact> {
        val results = mutableListOf<ResolvedContact>()
        val seenKeys = mutableSetOf<String>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val rawNumber = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    if (name.isNotBlank() && rawNumber.isNotBlank()) {
                        val cleanNumber = rawNumber.replace(Regex("[\\s.\\-()]"), "")
                        val dedupKey = normalizeForSearch(name)
                        if (cleanNumber.isNotBlank() && seenKeys.add(dedupKey)) {
                            results.add(ResolvedContact(name = name, phoneNumber = cleanNumber))
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission READ_CONTACTS manquante lors de la lecture du répertoire", e)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lecture des contacts", e)
        }

        return results
    }

    fun searchContactByName(context: Context, query: String): ContactLookupResult {
        val normQuery = normalizeForSearch(query)
        if (normQuery.isBlank()) return ContactLookupResult.NotFound

        val contacts = readPhoneContacts(context)
        return matchContactsInList(contacts, normQuery)
    }

    fun resolveSmsRecipientAndMessage(
        context: Context,
        explicitRecipient: String,
        explicitMessage: String,
        rawAfterRecipientPrep: String
    ): SmsTargetLookupResult {
        if (explicitRecipient.isNotBlank()) {
            val directNumber = extractPhoneNumber(explicitRecipient)
            if (directNumber != null) {
                return SmsTargetLookupResult(
                    lookup = ContactLookupResult.Found(ResolvedContact(directNumber, directNumber)),
                    resolvedMessage = explicitMessage.trim()
                )
            }
            return SmsTargetLookupResult(
                lookup = searchContactByName(context, explicitRecipient),
                resolvedMessage = explicitMessage.trim()
            )
        }

        // Cas sans ":" : essai d'extraction d'un numéro en début de chaîne
        val leadingPhone = extractLeadingPhoneNumberAndRemainder(rawAfterRecipientPrep)
        if (leadingPhone != null) {
            val (num, msg) = leadingPhone
            return SmsTargetLookupResult(
                lookup = ContactLookupResult.Found(ResolvedContact(num, num)),
                resolvedMessage = msg
            )
        }

        // Sinon, tester les 3, 2, puis 1 premiers mots contre le répertoire réel
        val words = rawAfterRecipientPrep.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) {
            return SmsTargetLookupResult(ContactLookupResult.NotFound, "")
        }

        val contacts = readPhoneContacts(context)
        val maxPrefixWords = min(3, words.size)
        for (wordCount in maxPrefixWords downTo 1) {
            val candidateName = words.take(wordCount).joinToString(" ")
            val normCandidate = normalizeForSearch(candidateName)
            val match = matchContactsInList(contacts, normCandidate)
            if (match !is ContactLookupResult.NotFound) {
                val remainderMessage = words.drop(wordCount).joinToString(" ").trim()
                    .removePrefix(":").trim()
                return SmsTargetLookupResult(
                    lookup = match,
                    resolvedMessage = remainderMessage
                )
            }
        }

        return SmsTargetLookupResult(ContactLookupResult.NotFound, "")
    }

    private fun matchContactsInList(
        contacts: List<ResolvedContact>,
        normQuery: String
    ): ContactLookupResult {
        if (contacts.isEmpty() || normQuery.isBlank()) return ContactLookupResult.NotFound

        val queryTokens = normQuery.split(" ").filter { it.isNotBlank() }

        // Niveau 1 : Correspondance exacte du nom complet
        val exactMatches = contacts.filter { normalizeForSearch(it.name) == normQuery }
        if (exactMatches.size == 1) return ContactLookupResult.Found(exactMatches.first())
        if (exactMatches.size > 1) return ContactLookupResult.Ambiguous(exactMatches.take(4))

        // Niveau 2 : Un mot exact du nom (ex: prénom "Alexandre") ou tous les mots de la requête en préfixe
        val tokenMatches = contacts.filter { contact ->
            val nameTokens = normalizeForSearch(contact.name).split(" ").filter { it.isNotBlank() }
            if (queryTokens.size == 1) {
                nameTokens.any { it == normQuery || it.startsWith(normQuery) }
            } else {
                queryTokens.all { qTok -> nameTokens.any { nTok -> nTok.startsWith(qTok) } }
            }
        }
        if (tokenMatches.size == 1) return ContactLookupResult.Found(tokenMatches.first())
        if (tokenMatches.size > 1) return ContactLookupResult.Ambiguous(tokenMatches.take(4))

        // Niveau 3 : Sous-chaîne normalisée
        if (normQuery.length >= 2) {
            val containsMatches = contacts.filter { normalizeForSearch(it.name).contains(normQuery) }
            if (containsMatches.size == 1) return ContactLookupResult.Found(containsMatches.first())
            if (containsMatches.size > 1) return ContactLookupResult.Ambiguous(containsMatches.take(4))
        }

        // Niveau 4 : Tolérance orthographique / vocale (distance de Levenshtein)
        val maxDistance = if (normQuery.length <= 4) 1 else 2
        val fuzzyMatches = contacts.mapNotNull { contact ->
            val normName = normalizeForSearch(contact.name)
            val nameTokens = normName.split(" ").filter { it.isNotBlank() }
            val fullDist = levenshtein(normQuery, normName)
            val bestTokenDist = nameTokens.minOfOrNull { levenshtein(normQuery, it) } ?: Int.MAX_VALUE
            val best = min(fullDist, bestTokenDist)
            if (best <= maxDistance) contact to best else null
        }.sortedBy { it.second }

        if (fuzzyMatches.isNotEmpty()) {
            val minScore = fuzzyMatches.first().second
            val topCandidates = fuzzyMatches.filter { it.second == minScore }.map { it.first }
            return if (topCandidates.size == 1) {
                ContactLookupResult.Found(topCandidates.first())
            } else {
                ContactLookupResult.Ambiguous(topCandidates.take(4))
            }
        }

        return ContactLookupResult.NotFound
    }

    fun findLaunchableApp(context: Context, spokenAppName: String): AppLaunchLookupResult {
        val rawNormQuery = normalizeForSearch(spokenAppName)
        if (rawNormQuery.isBlank()) return AppLaunchLookupResult.NotFound

        val normQuery = when (rawNormQuery) {
            "reglages", "reglage", "settings", "configuration" -> "parametres"
            "appareil photo", "photo" -> "camera"
            else -> rawNormQuery
        }

        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la liste des applications installées", e)
            emptyList()
        }

        data class AppEntry(val label: String, val normLabel: String, val packageName: String)

        val apps = resolveInfos.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.isBlank()) return@mapNotNull null
            AppEntry(label = label, normLabel = normalizeForSearch(label), packageName = pkg)
        }.distinctBy { it.packageName }

        if (apps.isEmpty()) return AppLaunchLookupResult.NotFound

        fun toFound(entry: AppEntry): AppLaunchLookupResult {
            val intent = pm.getLaunchIntentForPackage(entry.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return if (intent != null) {
                AppLaunchLookupResult.Found(entry.label, entry.packageName, intent)
            } else {
                AppLaunchLookupResult.NotFound
            }
        }

        // Niveau 1 : Correspondance exacte du nom ou suffixe du package
        val exact = apps.filter {
            it.normLabel == normQuery || it.packageName.lowercase(Locale.US).endsWith(".$normQuery")
        }
        if (exact.size == 1) return toFound(exact.first())
        if (exact.size > 1) return AppLaunchLookupResult.Ambiguous(exact.map { it.label }.distinct().take(4))

        // Niveau 2 : Commence par la requête ou mot exact dans le libellé
        val prefixOrWord = apps.filter { entry ->
            val words = entry.normLabel.split(" ")
            entry.normLabel.startsWith(normQuery) || words.any { it == normQuery }
        }
        if (prefixOrWord.size == 1) return toFound(prefixOrWord.first())
        if (prefixOrWord.size > 1) return AppLaunchLookupResult.Ambiguous(prefixOrWord.map { it.label }.distinct().take(4))

        // Niveau 3 : Contient la requête
        if (normQuery.length >= 2) {
            val contains = apps.filter {
                it.normLabel.contains(normQuery) ||
                    (it.normLabel.length >= 3 && normQuery.contains(it.normLabel)) ||
                    it.packageName.lowercase(Locale.US).contains(normQuery.replace(" ", ""))
            }
            if (contains.size == 1) return toFound(contains.first())
            if (contains.size > 1) return AppLaunchLookupResult.Ambiguous(contains.map { it.label }.distinct().take(4))
        }

        // Niveau 4 : Tolérance orthographique / reconnaissance vocale (Levenshtein)
        val maxDist = if (normQuery.length <= 4) 1 else 2
        val fuzzy = apps.mapNotNull { entry ->
            val words = entry.normLabel.split(" ").filter { it.isNotBlank() }
            val fullDist = levenshtein(normQuery, entry.normLabel)
            val wordDist = words.minOfOrNull { levenshtein(normQuery, it) } ?: Int.MAX_VALUE
            val best = min(fullDist, wordDist)
            if (best <= maxDist) entry to best else null
        }.sortedBy { it.second }

        if (fuzzy.isNotEmpty()) {
            val minScore = fuzzy.first().second
            val top = fuzzy.filter { it.second == minScore }.map { it.first }
            return if (top.size == 1) {
                toFound(top.first())
            } else {
                AppLaunchLookupResult.Ambiguous(top.map { it.label }.distinct().take(4))
            }
        }

        return AppLaunchLookupResult.NotFound
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = min(
                    min(dp[j] + 1, dp[j - 1] + 1),
                    prev + cost
                )
                prev = temp
            }
        }
        return dp[b.length]
    }
}

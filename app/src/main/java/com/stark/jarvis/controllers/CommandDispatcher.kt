package com.stark.jarvis.controllers

import com.stark.jarvis.data.ContactItem

sealed class DispatchResult {
    data class ActionExecuted(val spokenReply: String, val logText: String, val isAlert: Boolean? = null) : DispatchResult()
    data class TorchAction(val enable: Boolean, val spokenReply: String) : DispatchResult()
    data class CallLookupAction(val targetQuery: String) : DispatchResult()
    data class SmsComposeAction(
        val explicitRecipient: String,
        val explicitMessage: String,
        val rawAfterTo: String
    ) : DispatchResult()
    data class OpenAppAction(val appQuery: String) : DispatchResult()

    // Commandes d'accessibilité directes (PARTIE B)
    object AccessibilityHome : DispatchResult()
    object AccessibilityBack : DispatchResult()
    object AccessibilityNotifications : DispatchResult()
    object AccessibilityQuickSettings : DispatchResult()
    object AccessibilityRecents : DispatchResult()
    object AccessibilityReadScreen : DispatchResult()
    data class AccessibilityClick(val targetText: String) : DispatchResult()
    data class AccessibilityScroll(val down: Boolean) : DispatchResult()
    data class AccessibilityType(val textToType: String) : DispatchResult()
    object StopAgentAction : DispatchResult()

    // Commandes locales directes de réglages appareil (sans IA)
    data class SetVolumeAction(
        val percent: Int? = null,
        val deltaPercent: Int? = null,
        val isRingStream: Boolean = false
    ) : DispatchResult()
    data class SetFontScaleAction(val scale: Float, val label: String) : DispatchResult()
    data class SetBrightnessAction(val percent: Int? = null, val deltaPercent: Int? = null) : DispatchResult()
    data class OpenDeviceSettingsAction(val label: String, val settingsAction: String) : DispatchResult()

    // Agent multi-étapes (PARTIE C)
    data class AgentGoal(val goal: String) : DispatchResult()

    // Passerelle conversationnelle Gemini
    data class GeminiQuery(val rawQuery: String) : DispatchResult()
}

class CommandDispatcher(
    @Suppress("UNUSED_PARAMETER")
    private val contacts: List<ContactItem>
) {
    fun dispatch(rawInput: String, batteryPercent: Int): DispatchResult {
        val original = rawInput.trim()
        val text = original.lowercase()

        // =========================================================================
        // 0. ARRÊT D'URGENCE AGENT ("stop")
        // =========================================================================
        val normBare = DeviceAssistantActions.normalizeForSearch(original)
        if (normBare == "stop" || normBare == "stop agent" || normBare == "arrete l agent") {
            return DispatchResult.StopAgentAction
        }

        // =========================================================================
        // 1. COMMANDES LOCALES PRIORITAIRES (Matériel & Télémétrie Stark existantes)
        // =========================================================================

        // 1.1 Torche LED
        if (text.contains("torche") || text.contains("lampe") || text.contains("flash")) {
            return if (text.contains("étein") || text.contains("etein") || text.contains("coupe") || text.contains("arrête") || text.contains("arrete") || text.contains("stop")) {
                DispatchResult.TorchAction(false, "Torche LED désactivée.")
            } else {
                DispatchResult.TorchAction(true, "Torche LED activée à pleine puissance.")
            }
        }

        // 1.2 Vibreur / Haptique
        if (text.contains("vibr") || text.contains("haptique")) {
            return DispatchResult.ActionExecuted("Impulsion haptique Stark transmise à l'appareil.", "Vibreur déclenché par commande vocale.")
        }

        // 1.3 Batterie (sauf si l'utilisateur demande d'ouvrir les paramètres de batterie)
        val isSettingsPhrase = text.contains("paramètre") || text.contains("parametre") ||
            text.contains("réglage") || text.contains("reglage")
        if (!isSettingsPhrase &&
            (text.contains("batterie") || text.contains("accu") || text.contains("énergie") || text.contains("energie") || text.contains("autonomie"))
        ) {
            val reply = "Les accumulateurs sont à $batteryPercent pour cent. Niveau énergétique stable."
            return DispatchResult.ActionExecuted(reply, "Télémétrie batterie : $batteryPercent%")
        }

        // 1.4 Briefing Matin
        if (text.contains("briefing") || text.contains("rapport") || text.contains("situation") || text.contains("point")) {
            val reply = "Rapport opérationnel : systèmes de communication actifs, accus à $batteryPercent pour cent. Tous les protocoles sont au vert."
            return DispatchResult.ActionExecuted(reply, "Briefing opérationnel exécuté.")
        }

        // 1.5 Alerte de sécurité
        if (text.contains("alerte") || text.contains("danger") || text.contains("sécurité") || text.contains("securite") || text.contains("défense") || text.contains("defense")) {
            return if (text.contains("fin") || text.contains("désactive") || text.contains("desactive") || text.contains("calme") || text.contains("stop")) {
                DispatchResult.ActionExecuted("Alerte levée. Retour au protocole de veille standard.", "Alerte de sécurité désactivée.", isAlert = false)
            } else {
                DispatchResult.ActionExecuted("Alerte maximale enclenchée. Périmètre sous surveillance accrue.", "Alerte de sécurité activée.", isAlert = true)
            }
        }

        // 1.6 Mode Jour / Nuit
        if (text.contains("mode nuit") || text.contains("thème sombre") || text.contains("theme sombre") || text.contains("obscurité")) {
            return DispatchResult.ActionExecuted("Affichage tactique basculé en mode nuit.", "Mode Nuit activé.")
        }
        if (text.contains("mode jour") || text.contains("thème clair") || text.contains("theme clair") || text.contains("lumière")) {
            return DispatchResult.ActionExecuted("Affichage tactique basculé en mode jour.", "Mode Jour activé.")
        }

        // Nettoyage d'interpellations éventuelles ("Jarvis, ...", "Dis Jarvis, ...")
        val cleanOriginal = original
            .replace(Regex("^(?:dis\\s+|hey\\s+|ok\\s+)?j\\.?a\\.?r\\.?v\\.?i\\.?s\\.?[,:\\s]+", RegexOption.IGNORE_CASE), "")
            .trim()

        // =========================================================================
        // 2. COMMANDES D'ACCESSIBILITÉ DIRECTES (PARTIE B — Avant Gemini)
        // =========================================================================
        parseAccessibilityCommand(cleanOriginal)?.let { return it }

        // =========================================================================
        // 3. COMMANDES APPELS / SMS / RÉGLAGES APPAREIL DIRECTS / OUVERTURE APPS
        // =========================================================================
        parseSmsCommand(cleanOriginal)?.let { return it }
        parseCallCommand(cleanOriginal)?.let { return it }
        parseLocalDeviceControlCommand(cleanOriginal)?.let { return it }
        parseOpenAppCommand(cleanOriginal)?.let { return it }

        // =========================================================================
        // 4. AGENT MULTI-ÉTAPES (PARTIE C — Instructions complexes sur l'UI)
        // =========================================================================
        if (isMultiStepUiGoal(cleanOriginal)) {
            return DispatchResult.AgentGoal(cleanOriginal)
        }

        // =========================================================================
        // 5. PASSERELLE GEMINI (Toute autre requête conversationnelle)
        // =========================================================================
        return DispatchResult.GeminiQuery(original)
    }

    private fun parseAccessibilityCommand(input: String): DispatchResult? {
        val norm = DeviceAssistantActions.normalizeForSearch(input)

        // Accueil
        if (norm == "va a l accueil" ||
            norm == "aller a l accueil" ||
            norm == "retour a l accueil" ||
            norm == "ecran d accueil" ||
            norm == "reviens a l accueil" ||
            norm == "accueil"
        ) {
            return DispatchResult.AccessibilityHome
        }

        // Retour
        if (norm == "reviens en arriere" ||
            norm == "revenir en arriere" ||
            norm == "retour en arriere" ||
            norm == "retour" ||
            norm == "precedent" ||
            norm == "page precedente"
        ) {
            return DispatchResult.AccessibilityBack
        }

        // Notifications
        if (norm == "ouvre les notifications" ||
            norm == "ouvrir les notifications" ||
            norm == "affiche les notifications" ||
            norm == "volet des notifications" ||
            norm == "volet de notifications" ||
            norm == "notifications"
        ) {
            return DispatchResult.AccessibilityNotifications
        }

        // Réglages rapides
        if (norm == "ouvre les reglages rapides" ||
            norm == "reglages rapides" ||
            norm == "parametres rapides" ||
            norm == "raccourcis rapides"
        ) {
            return DispatchResult.AccessibilityQuickSettings
        }

        // Applications récentes
        if (norm == "applis recentes" ||
            norm == "applications recentes" ||
            norm == "ouvre les applications recentes" ||
            norm == "taches recentes"
        ) {
            return DispatchResult.AccessibilityRecents
        }

        // Lire l'écran ("que vois-tu à l'écran")
        if (norm.contains("que vois tu a l ecran") ||
            norm.contains("qu est ce qu il y a a l ecran") ||
            norm.contains("qu y a t il a l ecran") ||
            norm == "lis l ecran" ||
            norm == "lire l ecran" ||
            norm == "decris l ecran" ||
            norm == "contenu de l ecran"
        ) {
            return DispatchResult.AccessibilityReadScreen
        }

        // Défiler vers le bas / haut ("descends", "monte")
        if (norm == "descends" ||
            norm == "descendre" ||
            norm == "defile vers le bas" ||
            norm == "fais defiler vers le bas" ||
            norm == "scroll bas"
        ) {
            return DispatchResult.AccessibilityScroll(down = true)
        }
        if (norm == "monte" ||
            norm == "monter" ||
            norm == "remonte" ||
            norm == "remonter" ||
            norm == "defile vers le haut" ||
            norm == "fais defiler vers le haut" ||
            norm == "scroll haut"
        ) {
            return DispatchResult.AccessibilityScroll(down = false)
        }

        // Toucher / cliquer un élément : "touche [texte]"
        val clickRegex = Regex(
            "^(?:touche|toucher|appuie\\s+sur|appuyer\\s+sur|clique\\s+sur|cliquer\\s+sur)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        )
        clickRegex.find(input)?.let { match ->
            val target = match.groupValues[1].trim()
            if (target.isNotBlank() && !hasMultiStepConnector(target)) {
                return DispatchResult.AccessibilityClick(target)
            }
        }

        // Écrire / taper un texte : "écris [texte]"
        val typeRegex = Regex(
            "^(?:écris|ecris|écrire|ecrire|tape|taper|saisis|saisir)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        )
        typeRegex.find(input)?.let { match ->
            val textToType = match.groupValues[1].trim()
            if (textToType.isNotBlank()) {
                return DispatchResult.AccessibilityType(textToType)
            }
        }

        return null
    }

    private fun parseCallCommand(input: String): DispatchResult? {
        val callPatterns = listOf(
            Regex("^(?:appelle|appeler|appel)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:passe|passer|lance|lancer)\\s+(?:un\\s+)?appel\\s+(?:à|a|au|vers)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:téléphone|telephone|téléphoner|telephoner)\\s+(?:à|a|au)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:compose|composer)\\s+(?:le\\s+numéro|le\\s+numero|le)?\\s*(.+)$", RegexOption.IGNORE_CASE)
        )

        for (pattern in callPatterns) {
            val match = pattern.find(input) ?: continue
            val rawTarget = match.groupValues[1]
                .trim()
                .replace(Regex("^(?:le\\s+numéro|le\\s+numero|le|la|l'|à|a|au)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
            if (hasMultiStepConnector(rawTarget)) return null
            return if (rawTarget.isBlank()) {
                DispatchResult.ActionExecuted(
                    "Qui souhaitez-vous appeler ? Précisez un nom ou un numéro.",
                    "Commande d'appel sans destinataire."
                )
            } else {
                DispatchResult.CallLookupAction(rawTarget)
            }
        }

        if (input.equals("appelle", ignoreCase = true) || input.equals("appeler", ignoreCase = true)) {
            return DispatchResult.ActionExecuted(
                "Qui souhaitez-vous appeler ? Précisez un nom ou un numéro.",
                "Commande d'appel sans destinataire."
            )
        }

        return null
    }

    private fun parseSmsCommand(input: String): DispatchResult? {
        val smsTrigger = Regex(
            "^(?:(?:envoie|envoyer|écris|ecris|rédige|redige|fais|faire|passe|passer)\\s+(?:un\\s+)?)?(?:sms|texto|message(?:\\s+texte)?)\\s+(?:à|a|au|pour)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        )

        val match = smsTrigger.find(input)
        if (match == null) {
            val bareSms = Regex(
                "^(?:envoie|envoyer|écris|ecris)\\s+(?:un\\s+)?(?:sms|texto|message)$",
                RegexOption.IGNORE_CASE
            )
            if (bareSms.matches(input)) {
                return DispatchResult.ActionExecuted(
                    "Précisez le destinataire et le message. Par exemple : Envoie un SMS à Alexandre : je suis en route.",
                    "Commande SMS incomplète."
                )
            }
            return null
        }

        val afterTo = match.groupValues[1].trim()
        if (afterTo.isBlank()) {
            return DispatchResult.ActionExecuted(
                "À qui souhaitez-vous envoyer ce SMS ?",
                "Commande SMS sans destinataire."
            )
        }

        val colonIndex = afterTo.indexOf(':')
        if (colonIndex > 0) {
            val recipient = afterTo.substring(0, colonIndex)
                .trim()
                .replace(Regex("^(?:le\\s+numéro|le\\s+numero|le)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
            val message = afterTo.substring(colonIndex + 1).trim()
            return DispatchResult.SmsComposeAction(
                explicitRecipient = recipient,
                explicitMessage = message,
                rawAfterTo = afterTo
            )
        }

        val separatorRegex = Regex(
            "^(.+?)\\s+(?:pour\\s+lui\\s+dire|pour\\s+dire|qui\\s+dit|disant|avec\\s+le\\s+message|texte)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        )
        val sepMatch = separatorRegex.find(afterTo)
        if (sepMatch != null) {
            val recipient = sepMatch.groupValues[1]
                .trim()
                .replace(Regex("^(?:le\\s+numéro|le\\s+numero|le)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
            val message = sepMatch.groupValues[2].trim()
            return DispatchResult.SmsComposeAction(
                explicitRecipient = recipient,
                explicitMessage = message,
                rawAfterTo = afterTo
            )
        }

        return DispatchResult.SmsComposeAction(
            explicitRecipient = "",
            explicitMessage = "",
            rawAfterTo = afterTo
        )
    }

    private fun parseOpenAppCommand(input: String): DispatchResult? {
        // Si la phrase contient plusieurs étapes ("ouvre les réglages et mets..."), laisser à l'agent multi-étapes
        if (hasMultiStepConnector(input)) return null

        val openPatterns = listOf(
            Regex("^(?:ouvre|ouvrir)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:lance|lancer|démarre|demarre|démarrer|demarrer)\\s+(.+)$", RegexOption.IGNORE_CASE)
        )

        for (pattern in openPatterns) {
            val match = pattern.find(input) ?: continue
            val rawApp = match.groupValues[1]
                .trim()
                .replace(
                    Regex("^(?:l'application|l'appli|application|appli|le|la|les|l')\\s*", RegexOption.IGNORE_CASE),
                    ""
                )
                .trim()
            return if (rawApp.isBlank()) {
                DispatchResult.ActionExecuted(
                    "Quelle application souhaitez-vous ouvrir ?",
                    "Commande d'ouverture sans nom d'application."
                )
            } else {
                DispatchResult.OpenAppAction(rawApp)
            }
        }

        return null
    }

    private fun parseLocalDeviceControlCommand(input: String): DispatchResult? {
        val norm = DeviceAssistantActions.normalizeForSearch(input)
        if (hasMultiStepConnector(input)) return null

        // A. Ouverture directe d'écrans Paramètres Android (Wi-Fi, affichage, batterie, etc.)
        if (norm.contains("parametre") || norm.contains("reglage")) {
            val isOpenIntent = Regex("^(?:ouvre|ouvrir|affiche|afficher|va dans|aller dans|accede|acceder|lance|lancer)\\b")
                .containsMatchIn(norm) ||
                norm.startsWith("parametre") ||
                norm.startsWith("reglage")

            if (isOpenIntent) {
                return when {
                    norm.contains("wi fi") || norm.contains("wifi") || norm.contains("reseau") ->
                        DispatchResult.OpenDeviceSettingsAction(
                            label = "paramètres Wi-Fi",
                            settingsAction = android.provider.Settings.ACTION_WIFI_SETTINGS
                        )
                    norm.contains("affichage") || norm.contains("ecran") ->
                        DispatchResult.OpenDeviceSettingsAction(
                            label = "paramètres d'affichage",
                            settingsAction = android.provider.Settings.ACTION_DISPLAY_SETTINGS
                        )
                    norm.contains("batterie") || norm.contains("energie") ->
                        DispatchResult.OpenDeviceSettingsAction(
                            label = "paramètres de batterie",
                            settingsAction = android.content.Intent.ACTION_POWER_USAGE_SUMMARY
                        )
                    norm.contains("bluetooth") ->
                        DispatchResult.OpenDeviceSettingsAction(
                            label = "paramètres Bluetooth",
                            settingsAction = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                        )
                    norm.contains("son") || norm.contains("audio") || norm.contains("volume") ->
                        DispatchResult.OpenDeviceSettingsAction(
                            label = "paramètres de son",
                            settingsAction = android.provider.Settings.ACTION_SOUND_SETTINGS
                        )
                    norm.contains("localisation") || norm.contains("gps") ->
                        DispatchResult.OpenDeviceSettingsAction(
                            label = "paramètres de localisation",
                            settingsAction = android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                        )
                    norm.contains("application") || norm.contains("appli") ->
                        DispatchResult.OpenDeviceSettingsAction(
                            label = "paramètres des applications",
                            settingsAction = android.provider.Settings.ACTION_APPLICATION_SETTINGS
                        )
                    norm in setOf(
                        "ouvre les parametres", "ouvrir les parametres",
                        "ouvre les reglages", "ouvrir les reglages",
                        "parametres", "reglages"
                    ) ->
                        DispatchResult.OpenDeviceSettingsAction(
                            label = "paramètres système",
                            settingsAction = android.provider.Settings.ACTION_SETTINGS
                        )
                    else -> null
                }
            }
        }

        // B. Taille de police locale (Settings.System.FONT_SCALE, avec confirmation OUI/NON)
        if (norm.contains("taille de police") ||
            norm.contains("taille d affichage") ||
            Regex("\\bpolice\\b").containsMatchIn(norm)
        ) {
            val decimalMatch = Regex("\\b(0[.,]\\d+|1[.,]\\d+|2[.,]0+)\\b").find(input)
            if (decimalMatch != null) {
                val parsed = decimalMatch.groupValues[1].replace(',', '.').toFloatOrNull()
                if (parsed != null) {
                    val clamped = parsed.coerceIn(0.80f, 1.60f)
                    val pct = kotlin.math.round(clamped * 100f).toInt()
                    return DispatchResult.SetFontScaleAction(clamped, "$pct%")
                }
            }

            val numberMatch = Regex("\\b(\\d{2,3})\\b").find(norm)
            if (numberMatch != null) {
                val pct = numberMatch.groupValues[1].toIntOrNull()
                if (pct != null && pct in 50..200) {
                    val scale = (pct / 100f).coerceIn(0.80f, 1.60f)
                    val finalPct = kotlin.math.round(scale * 100f).toInt()
                    return DispatchResult.SetFontScaleAction(scale, "$finalPct%")
                }
            }

            return when {
                norm.contains("tres grand") ->
                    DispatchResult.SetFontScaleAction(1.30f, "très grande (130%)")
                norm.contains("grand") || norm.contains("augmente") || norm.contains("grossi") ->
                    DispatchResult.SetFontScaleAction(1.15f, "grande (115%)")
                norm.contains("normal") || norm.contains("standard") || norm.contains("defaut") ->
                    DispatchResult.SetFontScaleAction(1.00f, "normale (100%)")
                norm.contains("petit") || norm.contains("redui") || norm.contains("diminu") ->
                    DispatchResult.SetFontScaleAction(0.85f, "petite (85%)")
                else -> null
            }
        }

        // C. Luminosité locale (Settings.System.SCREEN_BRIGHTNESS, avec confirmation OUI/NON)
        if (norm.contains("luminosite")) {
            val numberMatch = Regex("\\b(\\d{1,3})\\b").find(norm)
            if (numberMatch != null) {
                val pct = numberMatch.groupValues[1].toIntOrNull()
                if (pct != null && pct in 0..100) {
                    return DispatchResult.SetBrightnessAction(percent = pct.coerceIn(1, 100))
                }
            }
            return when {
                norm.contains("max") || norm.contains("fond") || norm.contains("plein") ->
                    DispatchResult.SetBrightnessAction(percent = 100)
                norm.contains("min") || norm.contains("faible") || norm.contains("bas") ->
                    DispatchResult.SetBrightnessAction(percent = 15)
                norm.contains("moitie") || norm.contains("moyen") || norm.contains("normal") ->
                    DispatchResult.SetBrightnessAction(percent = 50)
                norm.contains("augmente") || norm.contains("monte") || norm.contains("plus") ->
                    DispatchResult.SetBrightnessAction(deltaPercent = 20)
                norm.contains("baisse") || norm.contains("diminu") || norm.contains("moins") ->
                    DispatchResult.SetBrightnessAction(deltaPercent = -20)
                else -> null
            }
        }

        // D. Volume local direct (AudioManager, SANS confirmation OUI/NON)
        if (norm.contains("volume") || norm.contains("muet") || norm.contains("silencieux") || norm.contains("coupe le son")) {
            val isRing = norm.contains("sonnerie")
            if (norm.contains("muet") || norm.contains("silencieux") || norm.contains("coupe le son") || norm.contains("zero")) {
                return DispatchResult.SetVolumeAction(percent = 0, isRingStream = isRing)
            }
            val numberMatch = Regex("\\b(\\d{1,3})\\b").find(norm)
            if (numberMatch != null) {
                val pct = numberMatch.groupValues[1].toIntOrNull()
                if (pct != null && pct in 0..100) {
                    return DispatchResult.SetVolumeAction(percent = pct, isRingStream = isRing)
                }
            }
            return when {
                norm.contains("max") || norm.contains("fond") || norm.contains("plein") ->
                    DispatchResult.SetVolumeAction(percent = 100, isRingStream = isRing)
                norm.contains("moitie") || norm.contains("moyen") ->
                    DispatchResult.SetVolumeAction(percent = 50, isRingStream = isRing)
                norm.contains("augmente") || norm.contains("monte") || norm.contains("plus fort") ->
                    DispatchResult.SetVolumeAction(deltaPercent = 15, isRingStream = isRing)
                norm.contains("baisse") || norm.contains("diminu") || norm.contains("moins fort") ->
                    DispatchResult.SetVolumeAction(deltaPercent = -15, isRingStream = isRing)
                else -> null
            }
        }

        return null
    }

    private fun hasMultiStepConnector(input: String): Boolean {
        val norm = DeviceAssistantActions.normalizeForSearch(input)
            .replace(Regex("\\bpour\\s+cent\\b"), "pourcent")
        return Regex("\\b(?:et|puis|ensuite|afin de|pour)\\b").containsMatchIn(norm)
    }

    private fun isMultiStepUiGoal(input: String): Boolean {
        val norm = DeviceAssistantActions.normalizeForSearch(input)
        if (norm.isBlank()) return false

        // Toute consigne combinée ("ouvre ... et ...", "va dans ... et ...", etc.)
        val startsWithUiTrigger = listOf(
            "ouvre ", "ouvrir ", "va dans ", "allez dans ", "mets ", "mettre ",
            "regle ", "regler ", "active ", "activer ", "desactive ", "desactiver ",
            "change ", "changer ", "augmente ", "augmenter ", "baisse ", "baisser ",
            "cherche ", "chercher ", "recherche ", "rechercher ", "configure ", "parametrer ",
            "supprime ", "supprimer ", "installe ", "installer ", "connecte ", "connecter "
        ).any { norm.startsWith(it) }

        if (startsWithUiTrigger) return true

        // Ou combinaison multi-actions explicite avec verbe UI
        if (hasMultiStepConnector(norm) &&
            Regex("\\b(?:ouvre|va|mets|regle|active|desactive|cherche|clique|touche|ecris|tape|descends|monte|police|luminosite|bluetooth|wifi|son|volume|parametre|reglage)\\b")
                .containsMatchIn(norm)
        ) {
            return true
        }

        return false
    }
}

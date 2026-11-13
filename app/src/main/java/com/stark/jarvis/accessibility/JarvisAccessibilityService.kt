package com.stark.jarvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.stark.jarvis.controllers.DeviceAssistantActions
import java.util.Locale
import kotlin.math.min

data class UiElementSummary(
    val index: Int,
    val text: String,
    val description: String,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: Rect
)

sealed class ScreenReadResult {
    data class Success(
        val packageName: String,
        val elements: List<UiElementSummary>,
        val compactText: String
    ) : ScreenReadResult()
    data class Blacklisted(val packageName: String) : ScreenReadResult()
    object Unavailable : ScreenReadResult()
}

sealed class AccessibilityActionResult {
    data class Success(val message: String) : AccessibilityActionResult()
    data class Blacklisted(val packageName: String) : AccessibilityActionResult()
    data class NotFound(val detail: String) : AccessibilityActionResult()
    data class Failed(val detail: String) : AccessibilityActionResult()
}

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"
        private const val MAX_TRAVERSAL_NODES = 350
        private const val MAX_COMPACT_ELEMENTS = 45

        const val BLACKLIST_SPOKEN_MESSAGE =
            "Accès refusé par sécurité : cette application sensible est protégée (banque, paiement ou mots de passe)."

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        @Volatile
        var currentForegroundPackage: String = ""
            private set

        private val BLACKLISTED_KEYWORDS = listOf(
            "bank",
            "banque",
            "pay",
            "wallet",
            "money",
            "password"
        )

        private val BLACKLISTED_PACKAGES = setOf(
            // Gestionnaires de mots de passe et coffres-forts
            "com.x8bit.bitwarden",
            "com.agilebits.onepassword",
            "com.lastpass.lpandroid",
            "com.dashlane",
            "keepass2android.keepass2android",
            "com.kunzisoft.keepass.free",
            "com.kunzisoft.keepass.pro",
            "proton.android.pass",
            "com.nordpass.android.app.password.manager",
            "com.roboform",
            "com.enpass.android",
            "com.google.android.apps.authenticator2",
            "com.authy.authy",
            // Paiement / Mobile Money / Banques connues
            "com.paypal.android.p2pmobile",
            "com.revolut.revolut",
            "com.lydia",
            "com.transferwise.android",
            "com.squareup.cash",
            "com.venmo",
            "com.binance.dev",
            "com.coinbase.android",
            "com.orange.orangemoney",
            "com.wave.personal",
            "mg.telma.mvola",
            "com.telma.mvola",
            "fr.creditagricole.androidapp",
            "net.bnpparibas.mescomptes",
            "mobi.societegenerale.mobile.lappli",
            "fr.lcl.android.customerarea",
            "com.boursorama.android.clients",
            "com.fortuneo.android",
            "com.caisseepargne.android.mobilebanking",
            "com.banquepopulaire.cyberplus",
            "fr.labanquepostale.accountaccess",
            "com.creditmutuel.android",
            "com.cic.android",
            "com.ing.mobile",
            "com.nickel.android"
        )

        fun isPackageBlacklisted(packageName: String?): Boolean {
            if (packageName.isNullOrBlank()) return false
            val lower = packageName.lowercase(Locale.US).trim()
            if (lower in BLACKLISTED_PACKAGES) return true
            return BLACKLISTED_KEYWORDS.any { keyword -> lower.contains(keyword) }
        }

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val expectedComponent = ComponentName(context, JarvisAccessibilityService::class.java)
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false

                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(enabledServices)
                while (splitter.hasNext()) {
                    val componentString = splitter.next()
                    val enabledComponent = ComponentName.unflattenFromString(componentString)
                    if (enabledComponent == expectedComponent) {
                        return true
                    }
                }
                false
            } catch (e: Exception) {
                Log.w(TAG, "Impossible de vérifier l'état d'accessibilité", e)
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "JarvisAccessibilityService connecté")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank()) {
            currentForegroundPackage = pkg
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "JarvisAccessibilityService interrompu")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
    }

    // --- 1. Actions Globales Système ---

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun performNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun performQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    // --- Détection Fenêtre Active & Liste Noire ---

    private fun getActiveRootNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        if (root != null) {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isNotBlank()) currentForegroundPackage = pkg
            return root
        }
        val appWindowRoot = windows?.firstOrNull { window ->
            window.root != null && window.isActive
        }?.root ?: windows?.firstOrNull { it.root != null }?.root
        if (appWindowRoot != null) {
            val pkg = appWindowRoot.packageName?.toString().orEmpty()
            if (pkg.isNotBlank()) currentForegroundPackage = pkg
        }
        return appWindowRoot
    }

    private fun checkActivePackageBlacklisted(root: AccessibilityNodeInfo?): String? {
        val pkg = root?.packageName?.toString()?.takeIf { it.isNotBlank() } ?: currentForegroundPackage
        return if (isPackageBlacklisted(pkg)) pkg else null
    }

    private fun isPasswordNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val inputType = node.inputType
        if (inputType == InputType.TYPE_NULL) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        return (inputClass == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )) || (inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    // --- 3. Lire l'écran (liste COMPACTE, sans jamais lire les mots de passe) ---

    fun readScreenCompact(): ScreenReadResult {
        val root = getActiveRootNode()
        val blockedPkg = checkActivePackageBlacklisted(root)
        if (blockedPkg != null) {
            return ScreenReadResult.Blacklisted(blockedPkg)
        }
        if (root == null) {
            return ScreenReadResult.Unavailable
        }

        val packageName = root.packageName?.toString().orEmpty()
        val elements = mutableListOf<UiElementSummary>()
        val seenSignatures = mutableSetOf<String>()
        var visitedCount = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || visitedCount >= MAX_TRAVERSAL_NODES || elements.size >= MAX_COMPACT_ELEMENTS) return
            visitedCount++

            if (!node.isVisibleToUser) return

            val isPassword = isPasswordNode(node)
            val rawText = if (isPassword) "" else node.text?.toString()?.trim().orEmpty()
            val rawDesc = if (isPassword) "" else node.contentDescription?.toString()?.trim().orEmpty()
            val clickable = node.isClickable || findClickableAncestor(node) != null
            val editable = node.isEditable

            val cleanDesc = if (rawDesc == rawText) "" else rawDesc

            if (rawText.isNotBlank() || cleanDesc.isNotBlank() || editable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val safeText = if (isPassword) "[champ protégé]" else rawText.take(80)
                val safeDesc = if (isPassword) "" else cleanDesc.take(80)
                val signature = "$safeText|$safeDesc|$clickable|$editable"
                if (seenSignatures.add(signature)) {
                    elements.add(
                        UiElementSummary(
                            index = elements.size + 1,
                            text = safeText,
                            description = safeDesc,
                            clickable = clickable,
                            editable = editable,
                            bounds = rect
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                if (elements.size >= MAX_COMPACT_ELEMENTS || visitedCount >= MAX_TRAVERSAL_NODES) break
                traverse(node.getChild(i))
            }
        }

        traverse(root)

        val compactText = if (elements.isEmpty()) {
            "Aucun élément textuel ou interactif lisible à l'écran."
        } else {
            elements.joinToString("\n") { el ->
                buildString {
                    append("[${el.index}] ")
                    if (el.text.isNotBlank()) append("\"${el.text}\"")
                    if (el.description.isNotBlank()) {
                        if (el.text.isNotBlank()) append(" ")
                        append("desc=\"${el.description}\"")
                    }
                    val flags = mutableListOf<String>()
                    if (el.clickable) flags.add("cliquable")
                    if (el.editable) flags.add("éditable")
                    if (flags.isNotEmpty()) {
                        append(" (${flags.joinToString(", ")})")
                    }
                }
            }
        }

        return ScreenReadResult.Success(
            packageName = packageName,
            elements = elements,
            compactText = compactText
        )
    }

    /**
     * Version ultra-compacte pour l'agent multi-étapes (économie maximale de tokens / quota Gemini).
     */
    fun readScreenForAgent(): ScreenReadResult {
        val full = readScreenCompact()
        if (full !is ScreenReadResult.Success) return full

        val prioritized = full.elements
            .filter { el ->
                el.clickable || el.editable || el.text.isNotBlank() || el.description.isNotBlank()
            }
            .sortedByDescending { el ->
                when {
                    el.editable -> 3
                    el.clickable -> 2
                    else -> 1
                }
            }
            .take(25)

        val shortText = if (prioritized.isEmpty()) {
            "(aucun)"
        } else {
            prioritized.joinToString("\n") { el ->
                val label = (el.text.ifBlank { el.description }).replace("\n", " ").take(42)
                val flags = buildString {
                    if (el.clickable) append("C")
                    if (el.editable) append("E")
                }
                if (flags.isNotEmpty()) "$label|$flags" else label
            }
        }

        return ScreenReadResult.Success(
            packageName = full.packageName,
            elements = prioritized,
            compactText = shortText
        )
    }

    fun buildSpokenScreenSummary(): Pair<ScreenReadResult, String> {
        return when (val result = readScreenCompact()) {
            is ScreenReadResult.Blacklisted -> {
                result to BLACKLIST_SPOKEN_MESSAGE
            }
            is ScreenReadResult.Unavailable -> {
                result to "Impossible de lire le contenu de l'écran actuellement."
            }
            is ScreenReadResult.Success -> {
                if (result.elements.isEmpty()) {
                    result to "Je ne détecte aucun texte lisible sur cet écran."
                } else {
                    val spokenItems = result.elements
                        .mapNotNull { el ->
                            val label = el.text.ifBlank { el.description }
                            if (label.isBlank() || label == "[champ protégé]") null else label
                        }
                        .distinct()
                        .take(8)

                    val summary = if (spokenItems.isEmpty()) {
                        "L'écran contient des champs interactifs sans libellé textuel."
                    } else {
                        "À l'écran, je vois : ${spokenItems.joinToString(", ")}."
                    }
                    result to summary
                }
            }
        }
    }

    // --- 2. Toucher un élément par texte / description, défiler, taper, coordonnées ---

    fun clickByTextOrDescription(query: String): AccessibilityActionResult {
        val root = getActiveRootNode()
        val blockedPkg = checkActivePackageBlacklisted(root)
        if (blockedPkg != null) {
            return AccessibilityActionResult.Blacklisted(blockedPkg)
        }
        if (root == null) {
            return AccessibilityActionResult.Failed("Fenêtre active indisponible.")
        }

        val normQuery = DeviceAssistantActions.normalizeForSearch(query)
        if (normQuery.isBlank()) {
            return AccessibilityActionResult.NotFound("Cible vide.")
        }

        data class CandidateNode(
            val node: AccessibilityNodeInfo,
            val label: String,
            val score: Int
        )

        val candidates = mutableListOf<CandidateNode>()
        var visitedCount = 0

        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null || visitedCount >= MAX_TRAVERSAL_NODES) return
            visitedCount++
            if (!node.isVisibleToUser || isPasswordNode(node)) return

            val textVal = node.text?.toString()?.trim().orEmpty()
            val descVal = node.contentDescription?.toString()?.trim().orEmpty()
            val labels = listOf(textVal, descVal).filter { it.isNotBlank() }

            var bestScore = Int.MAX_VALUE
            var matchedLabel = ""
            for (rawLabel in labels) {
                val normLabel = DeviceAssistantActions.normalizeForSearch(rawLabel)
                if (normLabel.isBlank()) continue
                val score = when {
                    normLabel == normQuery -> 0
                    normLabel.split(" ").any { it == normQuery } -> 1
                    normLabel.startsWith(normQuery) -> 2
                    normLabel.contains(normQuery) -> 3
                    normQuery.length >= 4 && normQuery.contains(normLabel) && normLabel.length >= 3 -> 4
                    else -> {
                        val dist = levenshtein(normQuery, normLabel)
                        val maxDist = if (normQuery.length <= 4) 1 else 2
                        if (dist <= maxDist) 10 + dist else Int.MAX_VALUE
                    }
                }
                if (score < bestScore) {
                    bestScore = score
                    matchedLabel = rawLabel
                }
            }

            if (bestScore < Int.MAX_VALUE) {
                val clickableBonus = if (node.isClickable || findClickableAncestor(node) != null) 0 else 5
                candidates.add(CandidateNode(node, matchedLabel, bestScore + clickableBonus))
            }

            for (i in 0 until node.childCount) {
                if (visitedCount >= MAX_TRAVERSAL_NODES) break
                collect(node.getChild(i))
            }
        }

        collect(root)

        val bestMatch = candidates.minByOrNull { it.score }
            ?: return AccessibilityActionResult.NotFound("Élément « $query » introuvable à l'écran.")

        val clickableTarget = if (bestMatch.node.isClickable) {
            bestMatch.node
        } else {
            findClickableAncestor(bestMatch.node)
        }

        if (clickableTarget != null && clickableTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return AccessibilityActionResult.Success("Élément « ${bestMatch.label} » touché.")
        }

        // Fallback : tap par coordonnées au centre du nœud
        val rect = Rect()
        bestMatch.node.getBoundsInScreen(rect)
        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            val tapped = tapAtCoordinatesInternal(rect.centerX().toFloat(), rect.centerY().toFloat())
            if (tapped) {
                return AccessibilityActionResult.Success("Élément « ${bestMatch.label} » touché par coordonnées.")
            }
        }

        return AccessibilityActionResult.Failed("Impossible d'activer l'élément « ${bestMatch.label} ».")
    }

    fun scrollDown(): AccessibilityActionResult = performScroll(forward = true)

    fun scrollUp(): AccessibilityActionResult = performScroll(forward = false)

    private fun performScroll(forward: Boolean): AccessibilityActionResult {
        val root = getActiveRootNode()
        val blockedPkg = checkActivePackageBlacklisted(root)
        if (blockedPkg != null) {
            return AccessibilityActionResult.Blacklisted(blockedPkg)
        }
        if (root == null) {
            return AccessibilityActionResult.Failed("Fenêtre active indisponible.")
        }

        val scrollableNodes = mutableListOf<AccessibilityNodeInfo>()
        var visitedCount = 0

        fun findScrollables(node: AccessibilityNodeInfo?) {
            if (node == null || visitedCount >= MAX_TRAVERSAL_NODES) return
            visitedCount++
            if (!node.isVisibleToUser) return
            if (node.isScrollable) {
                scrollableNodes.add(node)
            }
            for (i in 0 until node.childCount) {
                if (visitedCount >= MAX_TRAVERSAL_NODES) break
                findScrollables(node.getChild(i))
            }
        }

        findScrollables(root)

        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        for (scrollable in scrollableNodes) {
            if (scrollable.performAction(action)) {
                return AccessibilityActionResult.Success(
                    if (forward) "Défilement vers le bas effectué." else "Défilement vers le haut effectué."
                )
            }
        }

        // Fallback par geste tactile
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = if (forward) metrics.heightPixels * 0.72f else metrics.heightPixels * 0.30f
        val endY = if (forward) metrics.heightPixels * 0.28f else metrics.heightPixels * 0.72f

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 320L))
            .build()

        val dispatched = dispatchGesture(gesture, null, null)
        return if (dispatched) {
            AccessibilityActionResult.Success(
                if (forward) "Défilement vers le bas effectué." else "Défilement vers le haut effectué."
            )
        } else {
            AccessibilityActionResult.Failed("Impossible de faire défiler cet écran.")
        }
    }

    fun typeText(text: String): AccessibilityActionResult {
        val root = getActiveRootNode()
        val blockedPkg = checkActivePackageBlacklisted(root)
        if (blockedPkg != null) {
            return AccessibilityActionResult.Blacklisted(blockedPkg)
        }
        if (root == null) {
            return AccessibilityActionResult.Failed("Fenêtre active indisponible.")
        }

        val editableNodes = mutableListOf<AccessibilityNodeInfo>()
        var visitedCount = 0

        fun findEditables(node: AccessibilityNodeInfo?) {
            if (node == null || visitedCount >= MAX_TRAVERSAL_NODES) return
            visitedCount++
            if (!node.isVisibleToUser) return
            if (node.isEditable && !isPasswordNode(node)) {
                editableNodes.add(node)
            }
            for (i in 0 until node.childCount) {
                if (visitedCount >= MAX_TRAVERSAL_NODES) break
                findEditables(node.getChild(i))
            }
        }

        findEditables(root)

        val target = editableNodes.firstOrNull { it.isFocused } ?: editableNodes.firstOrNull()
            ?: return AccessibilityActionResult.NotFound("Aucun champ de saisie éditable détecté à l'écran.")

        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) {
            AccessibilityActionResult.Success("Texte saisi : « $text ».")
        } else {
            AccessibilityActionResult.Failed("Impossible de saisir le texte dans ce champ.")
        }
    }

    fun tapAtCoordinates(x: Float, y: Float): AccessibilityActionResult {
        val root = getActiveRootNode()
        val blockedPkg = checkActivePackageBlacklisted(root)
        if (blockedPkg != null) {
            return AccessibilityActionResult.Blacklisted(blockedPkg)
        }
        return if (tapAtCoordinatesInternal(x, y)) {
            AccessibilityActionResult.Success("Appui aux coordonnées (${x.toInt()}, ${y.toInt()}) effectué.")
        } else {
            AccessibilityActionResult.Failed("Échec du geste tactile aux coordonnées demandées.")
        }
    }

    private fun tapAtCoordinatesInternal(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable && parent.isEnabled) {
                return parent
            }
            parent = parent.parent
            depth++
        }
        return null
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

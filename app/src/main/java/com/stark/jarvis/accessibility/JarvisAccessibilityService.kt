package com.stark.jarvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.stark.jarvis.controllers.DeviceAssistantActions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

sealed class ScreenCaptureResult {
    data class Success(val base64Jpeg: String, val width: Int, val height: Int) : ScreenCaptureResult()
    data class Blacklisted(val packageName: String) : ScreenCaptureResult()
    data class FlagSecureBlocked(val reason: String) : ScreenCaptureResult()
    data class Failed(val reason: String) : ScreenCaptureResult()
}

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"
        private const val MAX_TRAVERSAL_NODES = 350
        private const val MAX_COMPACT_ELEMENTS = 45
        private const val MAX_VISION_DIMENSION_PX = 720
        private const val VISION_JPEG_QUALITY = 60
        private const val ERROR_TAKE_SCREENSHOT_SECURE_WINDOW_CODE = 6

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

    fun isTreePoorOrEmpty(result: ScreenReadResult): Boolean {
        return when (result) {
            is ScreenReadResult.Blacklisted -> false
            is ScreenReadResult.Unavailable -> true
            is ScreenReadResult.Success -> {
                val elements = result.elements
                if (elements.isEmpty()) return true
                val labeledCount = elements.count {
                    val label = it.text.ifBlank { it.description }
                    label.isNotBlank() && label != "[champ protégé]"
                }
                val interactiveCount = elements.count { it.clickable || it.editable }
                labeledCount < 2 || (interactiveCount == 0 && elements.size <= 3)
            }
        }
    }

    private fun hasVisiblePasswordNode(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        var visited = 0
        fun scan(node: AccessibilityNodeInfo?): Boolean {
            if (node == null || visited >= MAX_TRAVERSAL_NODES) return false
            visited++
            if (!node.isVisibleToUser) return false
            if (isPasswordNode(node)) return true
            for (i in 0 until node.childCount) {
                if (scan(node.getChild(i))) return true
            }
            return false
        }
        return scan(root)
    }

    suspend fun captureReducedScreenForAgent(): ScreenCaptureResult {
        val root = getActiveRootNode()
        val blockedPkg = checkActivePackageBlacklisted(root)
        if (blockedPkg != null) {
            return ScreenCaptureResult.Blacklisted(blockedPkg)
        }
        if (hasVisiblePasswordNode(root)) {
            return ScreenCaptureResult.FlagSecureBlocked(
                "Capture refusée par sécurité : champ de mot de passe détecté à l'écran."
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenCaptureResult.Failed("Capture d'accessibilité disponible à partir d'Android 11.")
        }

        val captured = withTimeoutOrNull(3500L) {
            suspendCancellableCoroutine<ScreenCaptureResult> { cont ->
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val buffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                try {
                                    // Vérifier à nouveau juste avant traitement que l'écran n'est pas passé sur une app sensible
                                    val nowBlocked = checkActivePackageBlacklisted(getActiveRootNode())
                                    if (nowBlocked != null) {
                                        if (cont.isActive) cont.resume(ScreenCaptureResult.Blacklisted(nowBlocked))
                                        return
                                    }

                                    val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                                    if (hwBitmap == null) {
                                        if (cont.isActive) cont.resume(ScreenCaptureResult.Failed("Buffer graphique indisponible."))
                                        return
                                    }
                                    val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBitmap.recycle()

                                    if (softBitmap == null) {
                                        if (cont.isActive) cont.resume(ScreenCaptureResult.Failed("Conversion bitmap impossible."))
                                        return
                                    }

                                    val srcW = softBitmap.width.coerceAtLeast(1)
                                    val srcH = softBitmap.height.coerceAtLeast(1)
                                    val scale = min(
                                        1f,
                                        MAX_VISION_DIMENSION_PX.toFloat() / max(srcW, srcH).toFloat()
                                    )
                                    val dstW = (srcW * scale).roundToInt().coerceAtLeast(1)
                                    val dstH = (srcH * scale).roundToInt().coerceAtLeast(1)
                                    val scaled = if (scale < 1f) {
                                        Bitmap.createScaledBitmap(softBitmap, dstW, dstH, true).also {
                                            if (it !== softBitmap) softBitmap.recycle()
                                        }
                                    } else {
                                        softBitmap
                                    }

                                    if (isUniformlyBlackFrame(scaled)) {
                                        scaled.recycle()
                                        if (cont.isActive) {
                                            cont.resume(
                                                ScreenCaptureResult.FlagSecureBlocked(
                                                    "Fenêtre protégée (FLAG_SECURE) : capture bloquée par sécurité."
                                                )
                                            )
                                        }
                                        return
                                    }

                                    val baos = ByteArrayOutputStream()
                                    scaled.compress(Bitmap.CompressFormat.JPEG, VISION_JPEG_QUALITY, baos)
                                    scaled.recycle()
                                    val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                                    if (cont.isActive) {
                                        cont.resume(ScreenCaptureResult.Success(base64, dstW, dstH))
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Erreur traitement screenshot", e)
                                    if (cont.isActive) {
                                        cont.resume(ScreenCaptureResult.Failed("Erreur capture : ${e.localizedMessage ?: "inconnue"}"))
                                    }
                                } finally {
                                    try {
                                        buffer.close()
                                    } catch (_: Exception) {
                                        // Ignore close errors
                                    }
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                val res = when (errorCode) {
                                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW_CODE,
                                    ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
                                        ScreenCaptureResult.FlagSecureBlocked(
                                            "Fenêtre protégée (FLAG_SECURE) : capture refusée."
                                        )
                                    else ->
                                        ScreenCaptureResult.Failed("Capture refusée par Android (code $errorCode).")
                                }
                                if (cont.isActive) cont.resume(res)
                            }
                        }
                    )
                } catch (se: SecurityException) {
                    if (cont.isActive) {
                        cont.resume(
                            ScreenCaptureResult.Failed("Permission canTakeScreenshot manquante sur le service d'accessibilité.")
                        )
                    }
                } catch (e: Exception) {
                    if (cont.isActive) {
                        cont.resume(
                            ScreenCaptureResult.Failed("Impossible de capturer l'écran : ${e.localizedMessage ?: "erreur"}")
                        )
                    }
                }
            }
        }

        return captured ?: ScreenCaptureResult.Failed("Délai de capture d'écran dépassé.")
    }

    private fun isUniformlyBlackFrame(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true
        val stepX = (w / 5).coerceAtLeast(1)
        val stepY = (h / 5).coerceAtLeast(1)
        var x = stepX / 2
        while (x < w) {
            var y = stepY / 2
            while (y < h) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) > 4 || Color.green(pixel) > 4 || Color.blue(pixel) > 4) {
                    return false
                }
                y += stepY
            }
            x += stepX
        }
        return true
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

    fun tapByNormalized(xNorm: Float, yNorm: Float): AccessibilityActionResult {
        val root = getActiveRootNode()
        val blockedPkg = checkActivePackageBlacklisted(root)
        if (blockedPkg != null) {
            return AccessibilityActionResult.Blacklisted(blockedPkg)
        }
        val metrics = resources.displayMetrics
        val px = (xNorm.coerceIn(1f, 999f) / 1000f) * metrics.widthPixels.toFloat()
        val py = (yNorm.coerceIn(1f, 999f) / 1000f) * metrics.heightPixels.toFloat()
        return if (tapAtCoordinatesInternal(px, py)) {
            AccessibilityActionResult.Success("Toucher tactile (${px.toInt()}, ${py.toInt()}) effectué.")
        } else {
            AccessibilityActionResult.Failed("Échec du geste tap_xy.")
        }
    }

    fun longPressByNormalized(xNorm: Float, yNorm: Float, durationMs: Long = 700L): AccessibilityActionResult {
        val root = getActiveRootNode()
        val blockedPkg = checkActivePackageBlacklisted(root)
        if (blockedPkg != null) {
            return AccessibilityActionResult.Blacklisted(blockedPkg)
        }
        val metrics = resources.displayMetrics
        val px = (xNorm.coerceIn(1f, 999f) / 1000f) * metrics.widthPixels.toFloat()
        val py = (yNorm.coerceIn(1f, 999f) / 1000f) * metrics.heightPixels.toFloat()
        return if (longPressAtCoordinatesInternal(px, py, durationMs)) {
            AccessibilityActionResult.Success("Appui long aux coordonnées (${px.toInt()}, ${py.toInt()}) effectué.")
        } else {
            AccessibilityActionResult.Failed("Échec du geste d'appui long.")
        }
    }

    fun longPressByTextOrDescription(query: String, durationMs: Long = 700L): AccessibilityActionResult {
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

        var bestNode: AccessibilityNodeInfo? = null
        var bestLabel = ""
        var bestScore = Int.MAX_VALUE
        var visitedCount = 0

        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null || visitedCount >= MAX_TRAVERSAL_NODES) return
            visitedCount++
            if (!node.isVisibleToUser || isPasswordNode(node)) return

            val textVal = node.text?.toString()?.trim().orEmpty()
            val descVal = node.contentDescription?.toString()?.trim().orEmpty()
            val labels = listOf(textVal, descVal).filter { it.isNotBlank() }

            var nodeBestScore = Int.MAX_VALUE
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
                if (score < nodeBestScore) {
                    nodeBestScore = score
                    matchedLabel = rawLabel
                }
            }

            if (nodeBestScore < bestScore) {
                bestScore = nodeBestScore
                bestNode = node
                bestLabel = matchedLabel
            }

            for (i in 0 until node.childCount) {
                if (visitedCount >= MAX_TRAVERSAL_NODES) break
                collect(node.getChild(i))
            }
        }

        collect(root)
        val matched = bestNode
            ?: return AccessibilityActionResult.NotFound("Élément « $query » introuvable pour un appui long.")

        val rect = Rect()
        matched.getBoundsInScreen(rect)
        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            if (longPressAtCoordinatesInternal(rect.centerX().toFloat(), rect.centerY().toFloat(), durationMs)) {
                return AccessibilityActionResult.Success("Appui long effectué sur « $bestLabel ».")
            }
        }

        if (matched.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            return AccessibilityActionResult.Success("Appui long effectué sur « $bestLabel ».")
        }

        return AccessibilityActionResult.Failed("Impossible d'effectuer un appui long sur « $bestLabel ».")
    }

    fun swipeGesture(
        direction: String,
        x1Norm: Float? = null,
        y1Norm: Float? = null,
        x2Norm: Float? = null,
        y2Norm: Float? = null,
        durationMs: Long = 350L
    ): AccessibilityActionResult {
        val root = getActiveRootNode()
        val blockedPkg = checkActivePackageBlacklisted(root)
        if (blockedPkg != null) {
            return AccessibilityActionResult.Blacklisted(blockedPkg)
        }

        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat().coerceAtLeast(100f)
        val h = metrics.heightPixels.toFloat().coerceAtLeast(100f)

        val sx: Float
        val sy: Float
        val ex: Float
        val ey: Float
        val desc: String

        if (x1Norm != null && y1Norm != null && x2Norm != null && y2Norm != null) {
            sx = (x1Norm.coerceIn(1f, 999f) / 1000f) * w
            sy = (y1Norm.coerceIn(1f, 999f) / 1000f) * h
            ex = (x2Norm.coerceIn(1f, 999f) / 1000f) * w
            ey = (y2Norm.coerceIn(1f, 999f) / 1000f) * h
            desc = "Glissement tactile effectué."
        } else {
            when (DeviceAssistantActions.normalizeForSearch(direction)) {
                "down", "bas" -> {
                    sx = w * 0.50f; sy = h * 0.28f; ex = w * 0.50f; ey = h * 0.76f
                    desc = "Glissement vers le bas effectué."
                }
                "left", "gauche" -> {
                    sx = w * 0.84f; sy = h * 0.50f; ex = w * 0.16f; ey = h * 0.50f
                    desc = "Glissement vers la gauche effectué."
                }
                "right", "droite" -> {
                    sx = w * 0.16f; sy = h * 0.50f; ex = w * 0.84f; ey = h * 0.50f
                    desc = "Glissement vers la droite effectué."
                }
                else -> {
                    sx = w * 0.50f; sy = h * 0.74f; ex = w * 0.50f; ey = h * 0.26f
                    desc = "Glissement vers le haut effectué."
                }
            }
        }

        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(180L, 1000L)))
            .build()

        return if (dispatchGesture(gesture, null, null)) {
            AccessibilityActionResult.Success(desc)
        } else {
            AccessibilityActionResult.Failed("Impossible d'exécuter le geste de glissement.")
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

    private fun longPressAtCoordinatesInternal(x: Float, y: Float, durationMs: Long = 700L): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(550L, 1500L)))
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

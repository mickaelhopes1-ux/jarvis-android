package com.stark.jarvis.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import com.stark.jarvis.accessibility.JarvisAccessibilityService
import java.util.concurrent.CopyOnWriteArrayList

data class JarvisCapturedNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long
)

class JarvisNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotifListener"
        private const val MAX_STORED_NOTIFICATIONS = 20

        @Volatile
        var instance: JarvisNotificationListenerService? = null
            private set

        private val recentNotifications = CopyOnWriteArrayList<JarvisCapturedNotification>()

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val expected = ComponentName(context, JarvisNotificationListenerService::class.java)
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ) ?: return false

                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(enabled)
                while (splitter.hasNext()) {
                    val flat = splitter.next()
                    val cn = ComponentName.unflattenFromString(flat)
                    if (cn == expected) return true
                }
                false
            } catch (e: Exception) {
                Log.w(TAG, "Impossible de vérifier NotificationListenerService", e)
                false
            }
        }

        fun getReadableNotifications(context: Context, maxCount: Int = 5): List<JarvisCapturedNotification> {
            val activeFromService = try {
                instance?.activeNotifications?.toList().orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Erreur lecture activeNotifications", e)
                emptyList()
            }

            if (activeFromService.isNotEmpty()) {
                val parsed = activeFromService.mapNotNull { sbn -> extractReadable(context, sbn) }
                    .sortedByDescending { it.postTime }
                    .distinctBy { "${it.packageName}|${it.title}|${it.text}" }
                    .take(maxCount)
                if (parsed.isNotEmpty()) return parsed
            }

            return recentNotifications
                .filter { !JarvisAccessibilityService.isPackageBlacklisted(it.packageName) && it.packageName != context.packageName }
                .sortedByDescending { it.postTime }
                .distinctBy { "${it.packageName}|${it.title}|${it.text}" }
                .take(maxCount)
        }

        fun buildSpokenNotificationsSummary(context: Context, maxCount: Int = 5): Pair<Boolean, String> {
            if (!isServiceEnabled(context)) {
                return false to "La lecture des notifications n'est pas activée. Ouvrez les réglages d'accès aux notifications via le bouton dédié sur l'accueil."
            }

            val items = getReadableNotifications(context, maxCount)
            if (items.isEmpty()) {
                return true to "Vous n'avez aucune notification récente à lire."
            }

            val header = if (items.size == 1) {
                "Vous avez 1 notification récente."
            } else {
                "Voici vos ${items.size} dernières notifications."
            }

            val details = items.mapIndexed { idx, item ->
                val num = idx + 1
                val parts = listOf(item.title, item.text).filter { it.isNotBlank() }.joinToString(" : ")
                "$num, ${item.appName}, $parts."
            }.joinToString(" ")

            return true to "$header $details"
        }

        private fun extractReadable(context: Context, sbn: StatusBarNotification): JarvisCapturedNotification? {
            val pkg = sbn.packageName.orEmpty()
            if (pkg.isBlank() || pkg == context.packageName) return null

            // Sécurité stricte : ne jamais lire les notifications des apps bancaires, paiements ou coffres-forts
            if (JarvisAccessibilityService.isPackageBlacklisted(pkg)) return null

            val notification = sbn.notification ?: return null
            if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return null
            if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
            if (notification.visibility == Notification.VISIBILITY_SECRET) return null

            val extras = notification.extras ?: return null
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
            val bodyText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val text = bigText.ifBlank { bodyText }.replace("\n", " ").take(140)

            if (title.isBlank() && text.isBlank()) return null

            val appName = resolveAppLabel(context, pkg)
            return JarvisCapturedNotification(
                key = sbn.key ?: "${pkg}_${sbn.id}_${sbn.postTime}",
                packageName = pkg,
                appName = appName,
                title = title.take(60),
                text = text,
                postTime = sbn.postTime
            )
        }

        private fun resolveAppLabel(context: Context, packageName: String): String {
            return try {
                val pm = context.packageManager
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(packageName, 0)
                }
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                packageName.substringAfterLast('.')
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refreshCacheFromActive()
        Log.i(TAG, "JarvisNotificationListenerService connecté")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) {
            instance = null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val extracted = extractReadable(this, sbn) ?: return
        recentNotifications.removeAll { it.key == extracted.key }
        recentNotifications.add(0, extracted)
        while (recentNotifications.size > MAX_STORED_NOTIFICATIONS) {
            recentNotifications.removeAt(recentNotifications.lastIndex)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val key = sbn.key ?: return
        recentNotifications.removeAll { it.key == key }
    }

    private fun refreshCacheFromActive() {
        try {
            val active = activeNotifications ?: return
            val list = active.mapNotNull { extractReadable(this, it) }
                .sortedByDescending { it.postTime }
                .take(MAX_STORED_NOTIFICATIONS)
            recentNotifications.clear()
            recentNotifications.addAll(list)
        } catch (e: Exception) {
            Log.w(TAG, "Erreur synchronisation notifications actives", e)
        }
    }
}

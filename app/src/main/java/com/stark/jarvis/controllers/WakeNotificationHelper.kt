package com.stark.jarvis.controllers

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.stark.jarvis.MainActivity
import com.stark.jarvis.R
import com.stark.jarvis.data.AssistantSwitchState
import com.stark.jarvis.service.JarvisVoiceAssistantService

object WakeNotificationHelper {

    private const val TAG = "WakeNotificationHelper"

    const val WAKE_CHANNEL_ID = "jarvis_wake_channel"
    const val WAKE_NOTIFICATION_ID = 7090

    const val BOOT_CHANNEL_ID = "jarvis_boot_channel"
    const val BOOT_NOTIFICATION_ID = 7091

    const val EXTRA_RESUME_WAKE_SERVICE = "com.stark.jarvis.EXTRA_RESUME_WAKE_SERVICE"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (nm.getNotificationChannel(WAKE_CHANNEL_ID) == null) {
                val wakeChannel = NotificationChannel(
                    WAKE_CHANNEL_ID,
                    "Veille vocale J.A.R.V.I.S.",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Notification permanente affichée lorsque la veille vocale J.A.R.V.I.S. est active."
                    setShowBadge(false)
                }
                nm.createNotificationChannel(wakeChannel)
            }

            if (nm.getNotificationChannel(BOOT_CHANNEL_ID) == null) {
                val bootChannel = NotificationChannel(
                    BOOT_CHANNEL_ID,
                    "Réarmement J.A.R.V.I.S. après démarrage",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Permet de reprendre la veille vocale J.A.R.V.I.S. en un toucher après redémarrage."
                }
                nm.createNotificationChannel(bootChannel)
            }
        }
    }

    fun buildForegroundNotification(
        context: Context,
        switchState: AssistantSwitchState,
        detailText: String,
        isMicActive: Boolean
    ): Notification {
        ensureChannels(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            context,
            101,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val micTag = if (isMicActive) "MICRO ACTIF" else "MICRO EN VEILLE"

        val primaryActionIntent: Intent
        val primaryActionLabel: String
        if (switchState == AssistantSwitchState.STANDBY) {
            primaryActionLabel = "ACTIVER"
            primaryActionIntent = Intent(context, JarvisVoiceAssistantService::class.java).apply {
                action = JarvisVoiceAssistantService.ACTION_ACTIVATE_ASSISTANT
            }
        } else {
            primaryActionLabel = "VEILLE"
            primaryActionIntent = Intent(context, JarvisVoiceAssistantService::class.java).apply {
                action = JarvisVoiceAssistantService.ACTION_RETURN_STANDBY
            }
        }

        val primaryPending = PendingIntent.getService(
            context,
            102,
            primaryActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disableIntent = Intent(context, JarvisVoiceAssistantService::class.java).apply {
            action = JarvisVoiceAssistantService.ACTION_DISABLE_SERVICE
        }
        val disablePending = PendingIntent.getService(
            context,
            103,
            disableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "${switchState.label} • $micTag"

        return NotificationCompat.Builder(context, WAKE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis_agent)
            .setContentTitle(title)
            .setContentText(detailText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppPending)
            .addAction(0, primaryActionLabel, primaryPending)
            .addAction(0, "DÉSACTIVER", disablePending)
            .build()
    }

    fun updateForegroundNotification(
        context: Context,
        switchState: AssistantSwitchState,
        detailText: String,
        isMicActive: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = buildForegroundNotification(context, switchState, detailText, isMicActive)
            nm.notify(WAKE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Erreur mise à jour notification veille", e)
        }
    }

    fun showBootRestoreNotification(context: Context, reason: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        try {
            ensureChannels(context)
            val resumeActivityIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_RESUME_WAKE_SERVICE, true)
            }
            val resumePending = PendingIntent.getActivity(
                context,
                104,
                resumeActivityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val disableBootIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_RESUME_WAKE_SERVICE, false)
            }
            val disablePending = PendingIntent.getActivity(
                context,
                105,
                disableBootIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, BOOT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_jarvis_agent)
                .setContentTitle("J.A.R.V.I.S. — Reprendre la veille vocale")
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(resumePending)
                .addAction(0, "RÉARMER EN 1 TOUCHER", resumePending)
                .addAction(0, "LAISSER DÉSACTIVÉ", disablePending)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(BOOT_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Impossible d'afficher la notification de réarmement post-reboot", e)
        }
    }

    fun dismissBootRestoreNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(BOOT_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Erreur fermeture notification boot", e)
        }
    }
}

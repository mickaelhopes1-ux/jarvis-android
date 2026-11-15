package com.stark.jarvis.controllers

import android.Manifest
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

class AgentNotificationHelper(private val context: Context) {

    companion object {
        const val ACTION_STOP_AGENT = "com.stark.jarvis.ACTION_STOP_AGENT"
        private const val CHANNEL_ID = "jarvis_agent_channel"
        private const val NOTIFICATION_ID = 7042
        private const val TAG = "AgentNotification"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ensureChannel()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Agent autonome J.A.R.V.I.S.",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Notification permanente affichée lorsque l'agent J.A.R.V.I.S. pilote l'interface."
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    fun showAgentRunning(step: Int, maxSteps: Int, detail: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        try {
            ensureChannel()
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPending = PendingIntent.getActivity(
                context,
                1,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stopIntent = Intent(ACTION_STOP_AGENT).apply {
                setPackage(context.packageName)
            }
            val stopPending = PendingIntent.getBroadcast(
                context,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_jarvis_agent)
                .setContentTitle("Agent J.A.R.V.I.S. actif (Étape $step/$maxSteps)")
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openAppPending)
                .addAction(0, "STOP", stopPending)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Impossible d'afficher la notification permanente de l'agent", e)
        }
    }

    fun dismiss() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Erreur fermeture notification agent", e)
        }
    }
}

package com.stark.jarvis.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.stark.jarvis.controllers.WakeNotificationHelper
import com.stark.jarvis.data.JarvisRuntimeStore
import com.stark.jarvis.service.JarvisVoiceAssistantService

/**
 * Restaure la veille vocale J.A.R.V.I.S. après redémarrage du téléphone (OPPO A74s 5G Android 12+),
 * dans le respect strict des restrictions de sécurité Android 12 / 13 / 14+ et ColorOS.
 */
class BootReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "JarvisBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        // 1. Respecter strictement le choix de l'utilisateur : ne jamais réactiver s'il l'avait désactivé
        if (!JarvisRuntimeStore.isPermanentWakeEnabled(context)) {
            Log.i(TAG, "J.A.R.V.I.S. était désactivé avant l'extinction : aucune restauration au démarrage.")
            JarvisRuntimeStore.setBootRestorePending(context, false)
            return
        }

        // 2. Vérifier que l'autorisation microphone est accordée
        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            val msg = "J.A.R.V.I.S. était actif : ouvrez l'application pour autoriser le microphone."
            JarvisRuntimeStore.setBootRestorePending(context, true)
            WakeNotificationHelper.showBootRestoreNotification(context, msg)
            return
        }

        // 3. Différences Android 12 / 13 vs Android 14+ :
        //    Android 14+ (API 34+) interdit le lancement d'un FGS microphone depuis BOOT_COMPLETED.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val msg = "Android 14+ requiert un toucher pour réarmer le microphone après redémarrage."
            Log.i(TAG, msg)
            JarvisRuntimeStore.setBootRestorePending(context, true)
            WakeNotificationHelper.showBootRestoreNotification(context, msg)
            return
        }

        // 4. Android 12 / 13 (dont OPPO A74s 5G Android 12) : tentative encadrée, sans contournement
        try {
            val serviceIntent = JarvisVoiceAssistantService.createStartIntent(
                context = context,
                action = JarvisVoiceAssistantService.ACTION_START_STANDBY,
                fromBoot = true
            )
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(TAG, "Restauration de la veille vocale J.A.R.V.I.S. lancée après redémarrage.")
        } catch (e: Exception) {
            // Inclut ForegroundServiceStartNotAllowedException (API 31+) et restrictions ColorOS
            Log.w(TAG, "Démarrage automatique bloqué par Android/ColorOS : bascule vers notification 1-tap.", e)
            JarvisRuntimeStore.setBootRestorePending(context, true)
            WakeNotificationHelper.showBootRestoreNotification(
                context = context,
                reason = "Démarrage automatique bloqué par Android/OPPO : touchez pour reprendre l'écoute en veille."
            )
        }
    }
}

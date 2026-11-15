package com.stark.jarvis.tile

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.stark.jarvis.MainActivity
import com.stark.jarvis.data.AssistantSwitchState
import com.stark.jarvis.data.JarvisRuntimeStore
import com.stark.jarvis.service.JarvisVoiceAssistantService

/**
 * Tuile des réglages rapides Android : « J.A.R.V.I.S. écoute ».
 * Déclenche l'écoute vocale en un geste depuis le panneau des raccourcis système.
 */
class JarvisQuickTileService : TileService() {

    companion object {
        const val EXTRA_START_LISTENING_FROM_TILE = "com.stark.jarvis.EXTRA_START_LISTENING_FROM_TILE"
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTileUi()
    }

    override fun onClick() {
        super.onClick()
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val runtime = JarvisRuntimeStore.runtimeState.value
        if (runtime.isServiceRunning && hasMic) {
            val nextAction = if (runtime.switchState == AssistantSwitchState.STANDBY) {
                JarvisVoiceAssistantService.ACTION_ACTIVATE_ASSISTANT
            } else {
                JarvisVoiceAssistantService.ACTION_RETURN_STANDBY
            }
            ContextCompat.startForegroundService(
                this,
                JarvisVoiceAssistantService.createStartIntent(this, nextAction)
            )
            refreshTileUi()
            return
        }

        // Si le service permanent est désactivé ou si une permission manque, ouvrir MainActivity et lancer l'écoute d'un geste
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_START_LISTENING_FROM_TILE, true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                201,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }

    private fun refreshTileUi() {
        val tile = qsTile ?: return
        val runtime = JarvisRuntimeStore.runtimeState.value

        tile.label = "J.A.R.V.I.S. écoute"
        when (runtime.switchState) {
            AssistantSwitchState.LISTENING,
            AssistantSwitchState.ACTIVATED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = if (runtime.switchState == AssistantSwitchState.LISTENING) "Écoute en cours" else "Mode actif"
            }
            AssistantSwitchState.STANDBY -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Veille « jarvis »"
            }
            AssistantSwitchState.DISABLED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Toucher pour parler"
            }
        }
        tile.updateTile()
    }
}

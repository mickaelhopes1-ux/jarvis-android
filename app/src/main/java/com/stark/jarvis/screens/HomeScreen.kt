package com.stark.jarvis.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stark.jarvis.components.ArcReactorCanvas
import com.stark.jarvis.data.JarvisLog
import com.stark.jarvis.data.JarvisTelemetry
import com.stark.jarvis.data.VoiceState
import com.stark.jarvis.theme.*

@Composable
fun HomeScreen(
    telemetry: JarvisTelemetry,
    logs: List<JarvisLog>,
    isListening: Boolean,
    isSpeaking: Boolean,
    onToggleMic: () -> Unit,
    onToggleTorch: () -> Unit,
    onTriggerHaptic: () -> Unit,
    onToggleAlert: () -> Unit,
    onToggleDayNight: () -> Unit,
    onQuickAction: (String) -> Unit,
    onClearError: () -> Unit = {},
    currentApiKey: String = "",
    onSaveApiKey: (String) -> Unit = {},
    onConfirmAction: () -> Unit = {},
    onCancelAction: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
    onStopAgent: () -> Unit = {}
) {
    val voiceState = telemetry.voiceState
    var showSettingsDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(if (telemetry.isNightMode) ObsidianBackground else Color(0xFF0F172A))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Dynamic Island Status Header
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, BorderCyan, RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    when (voiceState) {
                                        VoiceState.ERROR -> RoseSecurity
                                        VoiceState.LISTENING -> AmberAlert
                                        VoiceState.ANALYZING -> CyanPrimary
                                        VoiceState.SPEAKING -> EmeraldSuccess
                                        VoiceState.IDLE -> if (telemetry.isAlertMode) RoseSecurity else EmeraldSuccess
                                    }
                                )
                        )
                        Text(
                            text = "${telemetry.assistantName} : ${voiceState.badge}",
                            color = when (voiceState) {
                                VoiceState.ERROR -> RoseSecurity
                                VoiceState.LISTENING -> AmberAlert
                                VoiceState.ANALYZING -> CyanPrimary
                                VoiceState.SPEAKING -> EmeraldSuccess
                                VoiceState.IDLE -> CyanPrimary
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = Icons.Default.BatteryChargingFull,
                            contentDescription = null,
                            tint = if (telemetry.batteryPercent > 20) EmeraldSuccess else AmberAlert,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "${telemetry.batteryPercent}%",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Paramètres Clé API",
                                tint = if (telemetry.hasApiKey) CyanPrimary else AmberAlert,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Voice State Banner (States: EN ATTENTE, ÉCOUTE, ANALYSE, RÉPONSE, ERREUR)
        item {
            val (stateColor, stateBorder, stateIcon) = when (voiceState) {
                VoiceState.IDLE -> Triple(ObsidianSurface, BorderCyan, Icons.Default.GraphicEq)
                VoiceState.LISTENING -> Triple(AmberAlert.copy(alpha = 0.2f), AmberAlert, Icons.Default.Mic)
                VoiceState.ANALYZING -> Triple(CyanDark.copy(alpha = 0.3f), CyanPrimary, Icons.Default.Sync)
                VoiceState.SPEAKING -> Triple(EmeraldSuccess.copy(alpha = 0.25f), EmeraldSuccess, Icons.Default.VolumeUp)
                VoiceState.ERROR -> Triple(RoseSecurity.copy(alpha = 0.25f), RoseSecurity, Icons.Default.Warning)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(stateColor)
                    .border(1.dp, stateBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = stateIcon,
                            contentDescription = null,
                            tint = stateBorder,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "STATUT VOCAL : ${voiceState.label}",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = when (voiceState) {
                                    VoiceState.IDLE -> "Appuyez sur le micro et formulez votre ordre."
                                    VoiceState.LISTENING -> "Transmission ouverte. Parlez maintenant..."
                                    VoiceState.ANALYZING -> "Décodage phonétique et analyse Stark en cours..."
                                    VoiceState.SPEAKING -> "Synthèse vocale active sur les haut-parleurs."
                                    VoiceState.ERROR -> "Signal interrompu. Consultez le rapport ci-dessous."
                                },
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        // Error Card (if any error occurred)
        if (telemetry.lastErrorMessage != null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(RoseSecurity.copy(alpha = 0.15f))
                        .border(1.dp, RoseSecurity, RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = RoseSecurity, modifier = Modifier.size(20.dp))
                            Column {
                                Text(
                                    text = "DIAGNOSTIC ERREUR :",
                                    color = RoseSecurity,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = telemetry.lastErrorMessage,
                                    color = TextPrimary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        IconButton(onClick = onClearError) {
                            Icon(Icons.Default.Close, contentDescription = "Fermer", tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // Quota Retry Status Card (HTTP 429 backoff indicator)
        val quotaMsg = telemetry.quotaStatusMessage
        if (!quotaMsg.isNullOrBlank()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(AmberAlert.copy(alpha = 0.16f))
                        .border(1.dp, AmberAlert, RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            tint = AmberAlert,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "GESTION QUOTA GEMINI :",
                                color = AmberAlert,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = quotaMsg,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // Agent Multi-Étapes Active Banner + STOP button
        if (telemetry.isAgentRunning) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(CyanDark.copy(alpha = 0.25f))
                        .border(1.5.dp, CyanPrimary, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "AGENT AUTONOME ACTIF",
                            color = CyanPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = telemetry.agentStatusText.ifBlank { "Exécution en cours..." },
                            color = TextPrimary,
                            fontSize = 12.sp
                        )
                    }
                    Button(
                        onClick = onStopAgent,
                        colors = ButtonDefaults.buttonColors(containerColor = RoseSecurity),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "STOP",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Security Confirmation Card with OUI / NON buttons (Calls & SMS & Sensitive Agent Actions)
        val confirmation = telemetry.activeConfirmation
        if (confirmation != null) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(AmberAlert.copy(alpha = 0.14f))
                        .border(1.5.dp, AmberAlert, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = AmberAlert,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "CONFIRMATION DE SÉCURITÉ REQUISE",
                            color = AmberAlert,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Text(
                        text = confirmation.title,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = confirmation.details,
                        color = TextSecondary,
                        fontSize = 12.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onConfirmAction,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Oui",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "OUI",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Button(
                            onClick = onCancelAction,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RoseSecurity),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Non",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "NON",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // Live Recognized Text Card (Displays speech in real time)
        if (telemetry.lastRecognizedText.isNotBlank()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(ObsidianCard)
                        .border(1.dp, CyanGlow, RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SIGNAL VOCAL CAPTÉ :",
                                color = CyanGlow,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "FRANÇAIS (FR)",
                                color = TextMuted,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "« ${telemetry.lastRecognizedText} »",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Central Arc Reactor Hero
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                ArcReactorCanvas(
                    isListening = voiceState == VoiceState.LISTENING || isListening,
                    isSpeaking = voiceState == VoiceState.SPEAKING || isSpeaking,
                    isAlert = telemetry.isAlertMode || voiceState == VoiceState.ERROR,
                    sizeDp = 210
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Voice Trigger Mic Button
                Button(
                    onClick = onToggleMic,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (voiceState) {
                            VoiceState.LISTENING -> AmberAlert
                            VoiceState.ANALYZING -> CyanDark
                            VoiceState.SPEAKING -> EmeraldSuccess
                            VoiceState.ERROR -> RoseSecurity
                            VoiceState.IDLE -> CyanDark
                        }
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .height(52.dp)
                        .fillMaxWidth(0.85f)
                ) {
                    Icon(
                        imageVector = when (voiceState) {
                            VoiceState.LISTENING -> Icons.Default.MicOff
                            VoiceState.ANALYZING -> Icons.Default.Sync
                            VoiceState.SPEAKING -> Icons.Default.VolumeUp
                            VoiceState.ERROR -> Icons.Default.Refresh
                            VoiceState.IDLE -> Icons.Default.Mic
                        },
                        contentDescription = "Microphone",
                        tint = if (voiceState == VoiceState.LISTENING) ObsidianBackground else Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (voiceState) {
                            VoiceState.LISTENING -> "PARLEZ MAINTENANT..."
                            VoiceState.ANALYZING -> "TRAITEMENT VOCAL..."
                            VoiceState.SPEAKING -> "J.A.R.V.I.S. PARLE..."
                            VoiceState.ERROR -> "RÉESSAYER L'ÉCOUTE"
                            VoiceState.IDLE -> "INITIALISER L'ÉCOUTE VOCALE"
                        },
                        color = if (voiceState == VoiceState.LISTENING) ObsidianBackground else Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Quick Hardware Command Bar (Torche, Vibreur, Alerte)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Torche
                OutlinedButton(
                    onClick = onToggleTorch,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (telemetry.torchActive) AmberAlert.copy(alpha = 0.2f) else ObsidianSurface
                    ),
                    border = BorderStroke(1.dp, if (telemetry.torchActive) AmberAlert else BorderCyan)
                ) {
                    Icon(
                        imageVector = Icons.Default.FlashlightOn,
                        contentDescription = null,
                        tint = if (telemetry.torchActive) AmberAlert else CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (telemetry.torchActive) "Éteindre" else "Torche",
                        fontSize = 11.sp,
                        color = TextPrimary
                    )
                }

                // Vibreur
                OutlinedButton(
                    onClick = onTriggerHaptic,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = ObsidianSurface),
                    border = BorderStroke(1.dp, BorderCyan)
                ) {
                    Icon(
                        imageVector = Icons.Default.Vibration,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Vibrer", fontSize = 11.sp, color = TextPrimary)
                }

                // Alerte
                OutlinedButton(
                    onClick = onToggleAlert,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (telemetry.isAlertMode) RoseSecurity.copy(alpha = 0.2f) else ObsidianSurface
                    ),
                    border = BorderStroke(1.dp, if (telemetry.isAlertMode) RoseSecurity else BorderCyan)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (telemetry.isAlertMode) RoseSecurity else CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (telemetry.isAlertMode) "Armé" else "Alerte",
                        fontSize = 11.sp,
                        color = TextPrimary
                    )
                }
            }
        }

        // Six Commandes Tactiques Rapides J.A.R.V.I.S.
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, BorderCyan, RoundedCornerShape(20.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LES 6 COMMANDES TACTIQUES DIRECTES :",
                        color = CyanGlow,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(text = "ACCÈS RAPIDE", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }

                // Ligne 1 : Mode Jour/Nuit & Briefing Matin
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onToggleDayNight,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = ObsidianCard),
                        border = BorderStroke(1.dp, BorderCyan)
                    ) {
                        Icon(imageVector = if (telemetry.isNightMode) Icons.Default.DarkMode else Icons.Default.LightMode, contentDescription = null, tint = AmberAlert, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = if (telemetry.isNightMode) "Mode Nuit" else "Mode Jour", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = { onQuickAction("Briefing Matin") },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = ObsidianCard),
                        border = BorderStroke(1.dp, BorderCyan)
                    ) {
                        Icon(imageVector = Icons.Default.WbSunny, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Briefing Matin", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Ligne 2 : SMS pour Alexandre & Faire Vibrer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onQuickAction("SMS pour Alexandre") },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = ObsidianCard),
                        border = BorderStroke(1.dp, BorderCyan)
                    ) {
                        Icon(imageVector = Icons.Default.Send, contentDescription = null, tint = CyanGlow, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "SMS Alexandre", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onTriggerHaptic,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = ObsidianCard),
                        border = BorderStroke(1.dp, BorderCyan)
                    ) {
                        Icon(imageVector = Icons.Default.Vibration, contentDescription = null, tint = EmeraldSuccess, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Faire vibrer", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Ligne 3 : Check Batterie & Torche LED
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onQuickAction("Vérifie la batterie") },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = ObsidianCard),
                        border = BorderStroke(1.dp, BorderCyan)
                    ) {
                        Icon(imageVector = Icons.Default.BatteryChargingFull, contentDescription = null, tint = EmeraldSuccess, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Check Batterie", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onToggleTorch,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (telemetry.torchActive) AmberAlert.copy(alpha = 0.2f) else ObsidianCard
                        ),
                        border = BorderStroke(1.dp, if (telemetry.torchActive) AmberAlert else BorderCyan)
                    ) {
                        Icon(imageVector = Icons.Default.FlashlightOn, contentDescription = null, tint = if (telemetry.torchActive) AmberAlert else CyanPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = if (telemetry.torchActive) "Éteindre LED" else "Torche LED", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Service d'accessibilité Android (Contrôle des autres applications)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(ObsidianSurface)
                    .border(
                        1.dp,
                        if (telemetry.isAccessibilityEnabled) EmeraldSuccess else AmberAlert,
                        RoundedCornerShape(18.dp)
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessibilityNew,
                            contentDescription = null,
                            tint = if (telemetry.isAccessibilityEnabled) EmeraldSuccess else AmberAlert,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "PILOTAGE D'APPLICATIONS",
                            color = CyanGlow,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = if (telemetry.isAccessibilityEnabled) "ACTIF" else "INACTIF",
                        color = if (telemetry.isAccessibilityEnabled) EmeraldSuccess else AmberAlert,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Text(
                    text = "Important (Android 12+ / OPPO) : si le service est grisé après installation APK, ouvrez Infos sur l'application J.A.R.V.I.S. → menu ⋮ → Autoriser les paramètres restreints, puis activez l'exclusion d'optimisation de la batterie en arrière-plan.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                Button(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (telemetry.isAccessibilityEnabled) ObsidianCard else CyanDark
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = if (telemetry.isAccessibilityEnabled) EmeraldSuccess else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Activer le service d'accessibilité",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Tactical Terminal Message Logs
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, BorderCyan, RoundedCornerShape(18.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "JOURNAL DE BORD TACTIQUE :",
                        color = CyanPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Icon(imageVector = Icons.Default.Terminal, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                }

                logs.takeLast(5).forEach { log ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (log.isError) RoseSecurity.copy(alpha = 0.2f)
                                else if (log.isJarvis) ObsidianCard
                                else CyanDark.copy(alpha = 0.2f)
                            )
                            .padding(8.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (log.isJarvis) telemetry.assistantName else telemetry.userName,
                                    color = if (log.isError) RoseSecurity else if (log.isJarvis) CyanPrimary else AmberAlert,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(text = log.timestamp, color = TextMuted, fontSize = 9.sp)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = log.text,
                                color = if (log.isError) RoseSecurity else TextPrimary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        ApiKeySettingsDialog(
            initialKey = currentApiKey,
            hasApiKey = telemetry.hasApiKey,
            onDismiss = { showSettingsDialog = false },
            onSave = { newKey ->
                onSaveApiKey(newKey)
                showSettingsDialog = false
            }
        )
    }
}

@Composable
private fun ApiKeySettingsDialog(
    initialKey: String,
    hasApiKey: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var keyInput by remember(initialKey) { mutableStateOf(initialKey) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianSurface,
        titleContentColor = CyanPrimary,
        textContentColor = TextPrimary,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = CyanPrimary
                )
                Text(
                    text = "PARAMÈTRES GEMINI IA",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (hasApiKey) {
                        "Statut : Clé API enregistrée dans l'appareil ✓"
                    } else {
                        "Statut : Aucune clé API configurée"
                    },
                    color = if (hasApiKey) EmeraldSuccess else AmberAlert,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = "Saisissez votre clé API Google AI Studio. Elle reste stockée localement sur votre téléphone (SharedPreferences).",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("Clé API") },
                    placeholder = { Text("Collez votre clé API ici...") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showKey) "Masquer" else "Afficher",
                                tint = CyanGlow
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = BorderCyan,
                        focusedLabelColor = CyanPrimary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(keyInput.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = CyanDark)
            ) {
                Text("Enregistrer", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (keyInput.isNotEmpty()) {
                    TextButton(onClick = { keyInput = "" }) {
                        Text("Effacer", color = RoseSecurity)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Fermer", color = TextSecondary)
                }
            }
        }
    )
}


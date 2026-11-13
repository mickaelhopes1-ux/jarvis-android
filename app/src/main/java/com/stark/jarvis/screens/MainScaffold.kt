package com.stark.jarvis.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stark.jarvis.data.*
import com.stark.jarvis.theme.*

@Composable
fun MainScaffold(
    context: Context,
    telemetry: JarvisTelemetry,
    logs: List<JarvisLog>,
    contacts: List<ContactItem>,
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
    hasCameraPermission: Boolean = false,
    onRequestCameraPermission: () -> Unit = {},
    onConfirmAction: () -> Unit = {},
    onCancelAction: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
    onStopAgent: () -> Unit = {}
) {
    var currentTab by remember { mutableStateOf(JarvisTab.HOME) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = ObsidianBackground,
                contentColor = CyanPrimary,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == JarvisTab.HOME,
                    onClick = {
                        onTriggerHaptic()
                        currentTab = JarvisTab.HOME
                    },
                    icon = { Icon(Icons.Default.Shield, contentDescription = "Accueil") },
                    label = { Text("Accueil", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ObsidianBackground,
                        selectedTextColor = CyanPrimary,
                        indicatorColor = CyanPrimary,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    )
                )

                NavigationBarItem(
                    selected = currentTab == JarvisTab.CALLS,
                    onClick = {
                        onTriggerHaptic()
                        currentTab = JarvisTab.CALLS
                    },
                    icon = { Icon(Icons.Default.Phone, contentDescription = "Appels") },
                    label = { Text("Appels", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ObsidianBackground,
                        selectedTextColor = EmeraldSuccess,
                        indicatorColor = EmeraldSuccess,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    )
                )

                NavigationBarItem(
                    selected = currentTab == JarvisTab.MESSAGES,
                    onClick = {
                        onTriggerHaptic()
                        currentTab = JarvisTab.MESSAGES
                    },
                    icon = { Icon(Icons.Default.Message, contentDescription = "Messages") },
                    label = { Text("Messages", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ObsidianBackground,
                        selectedTextColor = CyanGlow,
                        indicatorColor = CyanGlow,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    )
                )

                NavigationBarItem(
                    selected = currentTab == JarvisTab.CAMERA,
                    onClick = {
                        onTriggerHaptic()
                        currentTab = JarvisTab.CAMERA
                    },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = "Caméra") },
                    label = { Text("Caméra", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ObsidianBackground,
                        selectedTextColor = AmberAlert,
                        indicatorColor = AmberAlert,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (currentTab) {
                JarvisTab.HOME -> HomeScreen(
                    telemetry = telemetry,
                    logs = logs,
                    isListening = isListening,
                    isSpeaking = isSpeaking,
                    onToggleMic = onToggleMic,
                    onToggleTorch = onToggleTorch,
                    onTriggerHaptic = onTriggerHaptic,
                    onToggleAlert = onToggleAlert,
                    onToggleDayNight = onToggleDayNight,
                    onQuickAction = onQuickAction,
                    onClearError = onClearError,
                    currentApiKey = currentApiKey,
                    onSaveApiKey = onSaveApiKey,
                    onConfirmAction = onConfirmAction,
                    onCancelAction = onCancelAction,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onStopAgent = onStopAgent
                )
                JarvisTab.CALLS -> CallScreen(
                    context = context,
                    contacts = contacts,
                    onTriggerHaptic = onTriggerHaptic
                )
                JarvisTab.MESSAGES -> SmsScreen(
                    context = context,
                    contacts = contacts,
                    onTriggerHaptic = onTriggerHaptic
                )
                JarvisTab.CAMERA -> CameraScreen(
                    context = context,
                    onTriggerHaptic = onTriggerHaptic,
                    hasCameraPermission = hasCameraPermission,
                    onRequestCameraPermission = onRequestCameraPermission
                )
            }
        }
    }
}

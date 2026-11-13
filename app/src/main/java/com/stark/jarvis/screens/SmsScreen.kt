package com.stark.jarvis.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stark.jarvis.controllers.PhoneActions
import com.stark.jarvis.data.ContactItem
import com.stark.jarvis.theme.*

@Composable
fun SmsScreen(
    context: Context,
    contacts: List<ContactItem>,
    onTriggerHaptic: () -> Unit
) {
    var selectedPhone by remember { mutableStateOf("0698765432") }
    var selectedName by remember { mutableStateOf("Alexandre Stark") }
    var messageBody by remember { mutableStateOf("Bonjour, message transmis via l'agent autonome J.A.R.V.I.S.") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "TRANSMISSION MESSAGERIE SÉCURISÉE",
                color = CyanGlow,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // Target Contact Selector
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
                Text(text = "DESTINATAIRE :", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    contacts.take(3).forEach { contact ->
                        val isSelected = selectedPhone == contact.phone
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                onTriggerHaptic()
                                selectedPhone = contact.phone
                                selectedName = contact.name
                            },
                            label = { Text(contact.name.split(" ")[0], fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanDark,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = selectedPhone,
                    onValueChange = { selectedPhone = it },
                    label = { Text("Numéro de téléphone") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = BorderCyan,
                        focusedLabelColor = CyanPrimary
                    )
                )
            }
        }

        // Message Body
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, BorderCyan, RoundedCornerShape(18.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(text = "CONTENU DU MESSAGE :", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

                OutlinedTextField(
                    value = messageBody,
                    onValueChange = { messageBody = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = BorderCyan,
                        focusedLabelColor = CyanPrimary
                    )
                )

                // Quick message templates
                Text(text = "Modèles d'urgence :", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SuggestionChip(
                        onClick = {
                            onTriggerHaptic()
                            messageBody = "En réunion stratégique. Je vous rappelle rapidement."
                        },
                        label = { Text("En réunion", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    SuggestionChip(
                        onClick = {
                            onTriggerHaptic()
                            messageBody = "Systèmes J.A.R.V.I.S. opérationnels. Tous les voyants sont au vert."
                        },
                        label = { Text("Tous feux verts", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Send Button
                Button(
                    onClick = {
                        if (selectedPhone.isNotEmpty() && messageBody.isNotEmpty()) {
                            onTriggerHaptic()
                            val success = PhoneActions.sendSmsDirect(context, selectedPhone, messageBody)
                            if (success) {
                                Toast.makeText(context, "SMS transmis directement à $selectedName", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanDark),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Default.Send, contentDescription = "Envoyer", tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TRANSMETTRE LE SMS VIA J.A.R.V.I.S.",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

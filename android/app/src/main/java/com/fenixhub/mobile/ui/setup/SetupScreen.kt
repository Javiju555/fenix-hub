package com.fenixhub.mobile.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fenixhub.mobile.ui.theme.HubBlue
import com.fenixhub.mobile.ui.theme.HubNight
import com.fenixhub.mobile.ui.theme.HubPanel
import com.fenixhub.mobile.ui.theme.HubTextSoft

@Composable
fun SetupScreen(
    deviceName: String,
    passphrase: String,
    groupIdPreview: String,
    isSaving: Boolean,
    overlayPermissionGranted: Boolean,
    onDeviceNameChange: (String) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onSave: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(HubNight, Color(0xFF12172D), Color(0xFF1A1A2E)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "FenixHub",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Empareja el telefono con el mismo grupo que tu escritorio para compartir portapapeles por LAN.",
                style = MaterialTheme.typography.bodyLarge,
                color = HubTextSoft,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = HubPanel),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = onDeviceNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nombre del dispositivo") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = onPassphraseChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2645)),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Outlined.NetworkCheck, contentDescription = null, tint = HubBlue)
                                Text("Group ID", color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (groupIdPreview.isBlank()) "Introduce la passphrase para derivarlo" else groupIdPreview,
                                color = HubTextSoft,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (overlayPermissionGranted) Color(0xFF173824) else Color(0xFF36271B),
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.LockOpen, contentDescription = null, tint = Color.White)
                                Text(
                                    text = if (overlayPermissionGranted) "Overlay listo" else "Permiso de overlay requerido",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "FenixHub necesita dibujar sobre otras apps para el hub flotante, el gesto de sacudida y el acceso estilo SuperHub.",
                                color = HubTextSoft,
                            )
                            if (!overlayPermissionGranted) {
                                Spacer(modifier = Modifier.height(12.dp))
                                ElevatedButton(onClick = onRequestOverlayPermission) {
                                    Text("Conceder permiso")
                                }
                            }
                        }
                    }
                    ElevatedButton(
                        onClick = onSave,
                        enabled = deviceName.isNotBlank() && passphrase.isNotBlank() && !isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isSaving) "Derivando..." else "Guardar y arrancar FenixHub")
                    }
                }
            }
        }
    }
}

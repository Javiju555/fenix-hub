package com.fenixhub.mobile.ui.hub

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fenixhub.mobile.service.HotspotManager
import com.fenixhub.mobile.ui.theme.HubBlue
import com.fenixhub.mobile.ui.theme.HubPanel

/**
 * Tarjeta de hotspot local — muestra el botón de activación y,
 * cuando está activo, las credenciales (SSID + contraseña) para
 * conectar el PC a la red privada generada por el móvil.
 *
 * Uso en HubScreen:
 * ```kotlin
 * HotspotCard(
 *     state = hotspotState,
 *     onStart = viewModel::startHotspot,
 *     onStop = viewModel::stopHotspot,
 * )
 * ```
 */
@Composable
fun HotspotCard(
    state: HotspotManager.State,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val bgColor = when (state) {
        is HotspotManager.State.Active  -> Color(0xFF0D2A1A)
        is HotspotManager.State.Failed  -> Color(0xFF2A0D0D)
        else                            -> HubPanel
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        AnimatedContent(targetState = state, label = "hotspot-state") { s ->
            when (s) {
                // ── Inactivo ────────────────────────────────────────────
                is HotspotManager.State.Idle -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Wifi,
                                contentDescription = null,
                                tint = HubBlue.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    "Hotspot sin internet",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                )
                                Text(
                                    "Conecta el PC al móvil sin WiFi externo",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.5f),
                                )
                            }
                        }
                        Button(
                            onClick = onStart,
                            colors = ButtonDefaults.buttonColors(containerColor = HubBlue),
                        ) { Text("Activar") }
                    }
                }

                // ── Arrancando ──────────────────────────────────────────
                is HotspotManager.State.Starting -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = HubBlue,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Activando hotspot…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }

                // ── Activo — mostrar credenciales ───────────────────────
                is HotspotManager.State.Active -> {
                    val clipboard = LocalClipboardManager.current
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Wifi,
                                    contentDescription = null,
                                    tint = Color(0xFF4ECB71),
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Hotspot activo",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF4ECB71),
                                )
                            }
                            IconButton(onClick = onStop) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Desactivar hotspot",
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Text(
                            "Conecta el PC a esta red WiFi:",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )

                        Spacer(Modifier.height(6.dp))

                        CredentialRow(
                            label = "Red (SSID)",
                            value = s.credentials.ssid,
                            onCopy = { clipboard.setText(AnnotatedString(s.credentials.ssid)) },
                        )
                        Spacer(Modifier.height(4.dp))
                        CredentialRow(
                            label = "Contraseña",
                            value = s.credentials.password,
                            onCopy = { clipboard.setText(AnnotatedString(s.credentials.password)) },
                        )
                    }
                }

                // ── Error ───────────────────────────────────────────────
                is HotspotManager.State.Failed -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "No se pudo activar el hotspot",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFF45E5E),
                            )
                            Text(
                                s.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f),
                            )
                        }
                        OutlinedButton(onClick = onStart) { Text("Reintentar", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                fontWeight = FontWeight.Medium,
            )
        }
        IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = "Copiar $label",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

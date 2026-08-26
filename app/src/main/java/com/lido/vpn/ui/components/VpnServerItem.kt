package com.lido.vpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lido.vpn.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VpnServerItem(
    server: VpnServer,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onMenuClick: () -> Unit,
    allTargets: List<CheckTarget> = emptyList(),
    modifier: Modifier = Modifier
) {
    val isRu = true

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .clickable { onSelect() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val flag = extractFlag(server.country) ?: "🌐"
                        // Only show flag if it's not already in the name
                        if (!server.name.contains(flag)) {
                            Text(text = flag, fontSize = 16.sp)
                            Spacer(Modifier.width(6.dp))
                        }
                        
                        val protocolTag = when(server.type.uppercase()) {
                            "VLESS" -> "[VL]"
                            "VMESS" -> "[VM]"
                            "TROJAN" -> "[TR]"
                            "SHADOWSOCKS" -> "[SS]"
                            "SOCKS" -> "[SK]"
                            else -> ""
                        }
                        
                        // Only show protocol tag if it's not already in the name
                        val displayName = if (protocolTag.isNotEmpty() && !server.name.uppercase().contains(protocolTag)) {
                            "$protocolTag ${server.name}"
                        } else server.name

                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                    
                    Text(
                        text = "${server.type} • ${server.host}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 4.dp)) {
                    val statusText = when (server.status) {
                        ServerStatus.WORKING -> "${server.ping ?: 0} ms"
                        ServerStatus.NOT_WORKING -> if (isRu) "Офлайн" else "Offline"
                        ServerStatus.UNKNOWN -> ""
                    }
                    val statusColor = when (server.status) {
                        ServerStatus.WORKING -> Color(0xFF4CAF50)
                        ServerStatus.NOT_WORKING -> MaterialTheme.colorScheme.error
                        else -> Color.Gray
                    }

                    if (statusText.isNotEmpty()) {
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }
                
                IconButton(onClick = onMenuClick, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }

            // Results of service checks
            val pings = server.servicePings ?: emptyMap()
            val errors = server.serviceErrors ?: emptyMap()
            
            if (pings.isNotEmpty() || errors.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    pings.forEach { (name, ping) ->
                        if (ping != null) {
                            val target = allTargets.find { it.name == name }
                            ServicePingBadge(target, name, ping)
                        }
                    }
                    errors.forEach { (name, error) ->
                        if (error != null && !pings.containsKey(name)) {
                            val target = allTargets.find { it.name == name }
                            ServiceErrorBadge(target, name, error)
                        }
                    }
                }
            }
        }
    }
}

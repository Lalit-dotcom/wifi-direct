package com.example.wifidirectmesh.ui.main

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.wifidirectmesh.Chat
import com.wifidirect.mesh.models.CongestionState
import com.wifidirect.mesh.models.WiFiPeer
import com.wifidirect.mesh.models.WiFiAuditLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val isStarted by viewModel.isStarted.collectAsState()
    val nodeId by viewModel.nodeId.collectAsState()
    val congestionState by viewModel.congestionState.collectAsState()
    val operatingMode by viewModel.operatingMode.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val logs by viewModel.logs.collectAsState()

    var showSendDialog by remember { mutableStateOf<WiFiPeer?>(null) }
    var testMessageText by remember { mutableStateOf("Hello via Mesh!") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Header
        item {
            Text(
                text = "Wi-Fi Mesh Dashboard",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        // 2. Node Status Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isStarted) "Node Status: ACTIVE" else "Node Status: INACTIVE",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = if (isStarted) Color(0xFF4CAF50) else Color.Gray
                        )
                        Button(
                            onClick = { if (isStarted) viewModel.stopMesh() else viewModel.startMesh() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isStarted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(text = if (isStarted) "Stop Mesh" else "Start Mesh")
                        }
                    }

                    if (isStarted) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Node ID: ${nodeId.take(12)}...", fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Congestion:")
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        when (congestionState) {
                                            CongestionState.GREEN -> Color(0xFF4CAF50)
                                            CongestionState.YELLOW -> Color(0xFFFFEB3B)
                                            CongestionState.ORANGE -> Color(0xFFFF9800)
                                            CongestionState.RED -> Color(0xFFF44336)
                                        }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = congestionState.name,
                                    color = if (congestionState == CongestionState.YELLOW) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            Text(text = "Mode:")
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = operatingMode.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Username Customization (Only when started)
        if (isStarted) {
            item {
                var tempUsername by remember { mutableStateOf(com.example.wifidirectmesh.data.MeshManager.getUsername()) }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = tempUsername,
                            onValueChange = { 
                                tempUsername = it
                                com.example.wifidirectmesh.data.MeshManager.setUsername(it)
                            },
                            label = { Text("Your Display Name") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
            }

            // 3. SOS Trigger & Group Chat
            item {
                val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 0.95f,
                    targetValue = 1.05f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = LinearOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "Scale"
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.triggerSOS() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .scale(pulseScale),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "⚠️ SEND SOS EMERGENCY ANNOUNCEMENT",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                    }

                    // Group Chat Room button
                    Button(
                        onClick = { onItemClick(com.example.wifidirectmesh.GroupChat) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "💬 OPEN GROUP CHAT ROOM",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // 4. Discovered Peers List Header
        item {
            Text(
                text = "Discovered Peers (${peers.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        if (peers.isEmpty()) {
            item {
                Text(
                    text = if (isStarted) "Scanning for peers..." else "Start the mesh module to scan",
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        } else {
            items(peers) { peer ->
                PeerRow(peer = peer, onSendMessageClick = { onItemClick(Chat(peer.devicePublicKeyId)) })
            }
        }

        // 5. Audit Console Log Header
        item {
            Text(
                text = "Structured Audit Console",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "Console logs will appear here...",
                            color = Color.DarkGray,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    } else {
                        logs.take(30).forEach { log ->
                            LogLine(log = log)
                        }
                    }
                }
            }
        }
    }

    // Send Message Dialog
    if (showSendDialog != null) {
        val targetPeer = showSendDialog!!
        AlertDialog(
            onDismissRequest = { showSendDialog = null },
            title = { Text(text = "Send Message to ${targetPeer.devicePublicKeyId.take(8)}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = testMessageText,
                        onValueChange = { testMessageText = it },
                        label = { Text("Message Body") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.sendTestMessage(targetPeer.devicePublicKeyId, testMessageText)
                        showSendDialog = null
                    }
                ) {
                    Text("Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSendDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun PeerRow(peer: WiFiPeer, onSendMessageClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Peer: ${peer.devicePublicKeyId.take(12)}...",
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "EP: ${peer.endpoints.firstOrNull()?.address ?: "N/A"}:${peer.endpoints.firstOrNull()?.port ?: 0}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "RSSI: ${peer.rssi} dBm", fontSize = 12.sp, color = Color.Gray)
                    Text(text = "Trust: ${peer.trustScore}", fontSize = 12.sp, color = Color.Gray)
                }
            }

            Button(onClick = onSendMessageClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("Chat", fontSize = 12.sp)
            }
        }
    }
}

private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
fun LogLine(log: WiFiAuditLogEntry) {
    val dateStr = logTimeFormat.format(Date(log.timestamp))
    val severityColor = when (log.severity) {
        "DEBUG" -> Color.Cyan
        "INFO" -> Color.Green
        "WARNING" -> Color.Yellow
        "ERROR" -> Color.Red
        "CRITICAL" -> Color.Red
        else -> Color.White
    }

    Text(
        text = "[$dateStr] [${log.severity}] ${log.eventType}: ${log.message}",
        color = severityColor,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
    )
}

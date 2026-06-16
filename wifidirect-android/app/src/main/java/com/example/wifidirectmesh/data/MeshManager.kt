package com.example.wifidirectmesh.data

import com.wifidirect.mesh.WiFiMeshModuleImpl
import com.wifidirect.mesh.Message
import com.wifidirect.mesh.models.*
import com.wifidirect.mesh.models.WiFiAuditLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class ChatMessage(
    val senderId: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSentByMe: Boolean
)

data class SosAlert(
    val senderId: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

object MeshManager {
    private var moduleInstance: WiFiMeshModuleImpl? = null

    private val _isStarted = MutableStateFlow(false)
    val isStarted: StateFlow<Boolean> = _isStarted.asStateFlow()

    private val _nodeId = MutableStateFlow("")
    val nodeId: StateFlow<String> = _nodeId.asStateFlow()

    private val _congestionState = MutableStateFlow(CongestionState.GREEN)
    val congestionState: StateFlow<CongestionState> = _congestionState.asStateFlow()

    private val _operatingMode = MutableStateFlow(DiscoveryMode.SOS)
    val operatingMode: StateFlow<DiscoveryMode> = _operatingMode.asStateFlow()

    private val _peers = MutableStateFlow<List<WiFiPeer>>(emptyList())
    val peers: StateFlow<List<WiFiPeer>> = _peers.asStateFlow()

    private val _logs = MutableStateFlow<List<WiFiAuditLogEntry>>(emptyList())
    val logs: StateFlow<List<WiFiAuditLogEntry>> = _logs.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages.asStateFlow()

    private val _activeSosAlert = MutableStateFlow<SosAlert?>(null)
    val activeSosAlert: StateFlow<SosAlert?> = _activeSosAlert.asStateFlow()

    fun start() {
        if (_isStarted.value) return
        
        // Start on ports 55055/55056 to avoid standard locks
        val module = WiFiMeshModuleImpl(tcpPort = 55055, discoveryPort = 55056)
        moduleInstance = module
        
        // Register payload listener for incoming CHAT and SOS messages
        module.onPayloadReceived = { peerId, payload ->
            val str = try { String(payload) } catch (e: Exception) { "" }
            if (str.startsWith("CHAT:")) {
                val text = str.substring(5)
                android.util.Log.d("MeshManager", "Received CHAT message from $peerId: $text")
                addMessage(peerId, ChatMessage(senderId = peerId, text = text, isSentByMe = false))
            } else {
                try {
                    val msg = Message.deserialize(payload)
                    if (msg.priority == QueuePriority.SOS) {
                        val alertText = try { String(msg.payload) } catch (e: Exception) { "EMERGENCY SOS" }
                        android.util.Log.d("MeshManager", "Received SOS ALERT from $peerId: $alertText")
                        triggerSosAlert(peerId, alertText)
                    }
                } catch (e: Exception) {
                    // Ignore, not a serialized SOS message
                }
            }
        }

        _nodeId.value = module.securityManager.longTermPublicKeyId
        _isStarted.value = true

        // Start discovery in aggressive SOS mode
        module.startWiFiDiscovery(DiscoveryMode.SOS)
        
        // Periodically poll state for Compose UI updates
        kotlin.concurrent.thread(name = "MeshUiPoller") {
            while (_isStarted.value) {
                val mod = moduleInstance ?: break
                _peers.value = mod.peerTable.getAll()
                _logs.value = mod.auditLogger.export().takeLast(100).reversed()
                
                // Get local congestion score and map it
                val congestionScore = mod.congestionManager.calculateCongestionScore(
                    WiFiCongestionMetrics(
                        neighborCount = _peers.value.size,
                        activeConnections = mod.connectionManager.getActiveSessionCount(),
                        queueSize = mod.scheduler.totalSize(),
                        duplicateRate = 0.05f,
                        retryRate = 0.02f,
                        latencyIncreaseMs = 10L
                    )
                )
                _congestionState.value = mod.congestionManager.updateCongestionState(congestionScore).state
                
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    fun stop() {
        _isStarted.value = false
        moduleInstance?.shutdown()
        moduleInstance = null
    }

    fun sendSOS() {
        val module = moduleInstance ?: return
        val sosMsg = Message(
            id = "sos-" + UUID.randomUUID().toString().take(8),
            priority = QueuePriority.SOS,
            expiryTimestamp = System.currentTimeMillis() + 60_000L,
            ttl = 5,
            payload = "EMERGENCY_SOS".toByteArray()
        )
        // Attempt SOS broadcast/propagation
        kotlin.concurrent.thread {
            module.attemptSOSOverWifi(sosMsg)
        }
    }

    fun sendTestMessage(peerId: String, content: String) {
        val module = moduleInstance ?: return
        // Establish connection and send payload
        kotlin.concurrent.thread {
            val session = module.connectToWiFiPeer(peerId)
            if (session != null) {
                module.transferManager.sendPayload(peerId, content.toByteArray())
            }
        }
    }

    fun sendMessage(peerId: String, text: String) {
        val module = moduleInstance ?: return
        val chatMsg = ChatMessage(
            senderId = _nodeId.value,
            text = text,
            isSentByMe = true
        )
        addMessage(peerId, chatMsg)

        kotlin.concurrent.thread(name = "MeshMessageSender") {
            try {
                // Ensure connection is established (reuses active session)
                val session = module.connectToWiFiPeer(peerId)
                if (session != null) {
                    val success = module.transferManager.sendPayload(peerId, "CHAT:$text".toByteArray())
                    if (!success) {
                        android.util.Log.e("MeshManager", "Failed to send CHAT payload to $peerId")
                    } else {
                        android.util.Log.d("MeshManager", "Sent CHAT payload to $peerId")
                    }
                } else {
                    android.util.Log.e("MeshManager", "Failed to connect to peer $peerId to send message")
                }
            } catch (e: Exception) {
                android.util.Log.e("MeshManager", "Error sending message to $peerId: ${e.message}")
            }
        }
    }

    private fun addMessage(peerId: String, message: ChatMessage) {
        val current = _messages.value
        val list = current[peerId] ?: emptyList()
        _messages.value = current + (peerId to (list + message))
    }

    fun dismissSosAlert() {
        _activeSosAlert.value = null
    }

    private fun triggerSosAlert(senderId: String, text: String) {
        val alert = SosAlert(senderId = senderId, text = text)
        _activeSosAlert.value = alert
        showSystemNotification(senderId, text)
    }

    private fun showSystemNotification(senderId: String, text: String) {
        val context = com.example.wifidirectmesh.MainActivity.currentContext ?: return
        val channelId = "sos_alerts_channel"
        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "SOS Alerts",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical SOS alerts from mesh nodes"
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("⚠️ SOS EMERGENCY ALERT")
            .setContentText("From Node: ${senderId.take(12)}... - $text")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            notificationManager.notify(1001, builder.build())
        } catch (e: SecurityException) {
            android.util.Log.e("MeshManager", "Permission denied for system notification: ${e.message}")
        }
    }
}

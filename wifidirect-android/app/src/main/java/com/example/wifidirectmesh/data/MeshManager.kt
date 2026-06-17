package com.example.wifidirectmesh.data

import com.wifidirect.mesh.WiFiMeshModuleImpl
import com.wifidirect.mesh.Message
import com.wifidirect.mesh.models.*
import com.wifidirect.mesh.models.WiFiAuditLogEntry
import com.wifidirect.mesh.sync.TransferProgress
import com.example.wifidirectmesh.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSentByMe: Boolean,
    val senderName: String? = null,
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0L,
    val isImage: Boolean = false,
    val isFile: Boolean = false,
    val isGroup: Boolean = false
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

    private val _transfers = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferProgress>> = _transfers.asStateFlow()

    /** Tracks message IDs we have already displayed, preventing duplicate bubbles from echoes. */
    private val seenMessageIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private val deliveredBundleIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private var username = ""

    fun getUsername(): String {
        if (username.isNotEmpty()) return username
        val context = MainActivity.currentContext ?: return "Node-${_nodeId.value.take(8)}"
        val prefs = context.getSharedPreferences("mesh_limits", android.content.Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        if (username.isEmpty()) {
            username = "Node-${_nodeId.value.take(8)}"
        }
        return username
    }

    fun setUsername(name: String) {
        username = name
        val context = MainActivity.currentContext ?: return
        val prefs = context.getSharedPreferences("mesh_limits", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("username", name).apply()
    }

    fun checkAndResetDailyLimits() {
        val context = MainActivity.currentContext ?: return
        val prefs = context.getSharedPreferences("mesh_limits", android.content.Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val lastDay = prefs.getString("last_day", "")
        if (lastDay != today) {
            prefs.edit()
                .putString("last_day", today)
                .putInt("image_count", 0)
                .putLong("byte_count", 0L)
                .apply()
        }
    }

    fun getDailyImageCount(): Int {
        checkAndResetDailyLimits()
        val context = MainActivity.currentContext ?: return 0
        val prefs = context.getSharedPreferences("mesh_limits", android.content.Context.MODE_PRIVATE)
        return prefs.getInt("image_count", 0)
    }

    fun getDailyByteCount(): Long {
        checkAndResetDailyLimits()
        val context = MainActivity.currentContext ?: return 0L
        val prefs = context.getSharedPreferences("mesh_limits", android.content.Context.MODE_PRIVATE)
        return prefs.getLong("byte_count", 0L)
    }

    fun incrementDailyImageCount() {
        val context = MainActivity.currentContext ?: return
        val prefs = context.getSharedPreferences("mesh_limits", android.content.Context.MODE_PRIVATE)
        val current = prefs.getInt("image_count", 0)
        prefs.edit().putInt("image_count", current + 1).apply()
    }

    fun incrementDailyByteCount(bytes: Long) {
        val context = MainActivity.currentContext ?: return
        val prefs = context.getSharedPreferences("mesh_limits", android.content.Context.MODE_PRIVATE)
        val current = prefs.getLong("byte_count", 0L)
        prefs.edit().putLong("byte_count", current + bytes).apply()
    }

    fun canSendFile(fileName: String, size: Long): String? {
        val isImage = fileName.endsWith(".png", true) || fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) || fileName.endsWith(".gif", true)
        
        if (isImage) {
            val imgCount = getDailyImageCount()
            if (imgCount >= 20) {
                return "Daily image limit (20) reached. Cannot send image."
            }
        }
        
        val byteCount = getDailyByteCount()
        if (byteCount + size > 100 * 1024 * 1024) {
            return "Daily transfer limit (100MB) reached. Cannot send file."
        }
        
        return null
    }

    private fun showToast(msg: String) {
        val context = MainActivity.currentContext ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    fun start() {
        if (_isStarted.value) return
        
        val module = WiFiMeshModuleImpl(tcpPort = 55055, discoveryPort = 55056)
        moduleInstance = module
        
        module.onPayloadReceived = { peerId, payload ->
            android.util.Log.d("MeshManager", "onPayloadReceived: received raw payload from $peerId, size=${payload.size}")
            val str = try { String(payload) } catch (e: Exception) { "" }
            if (str.startsWith("CHAT:")) {
                val text = str.substring(5)
                android.util.Log.d("MeshManager", "Received CHAT message from $peerId: $text")
                addMessage(peerId, ChatMessage(senderId = peerId, text = text, isSentByMe = false, senderName = getPeerName(peerId)))
            } else {
                try {
                    val msg = Message.deserialize(payload)
                    android.util.Log.d("MeshManager", "Deserialized Message: msgId=${msg.id} priority=${msg.priority} from $peerId")
                    if (msg.priority == QueuePriority.SOS) {
                        val alertText = try { String(msg.payload) } catch (e: Exception) { "EMERGENCY SOS" }
                        android.util.Log.d("MeshManager", "Received SOS ALERT from $peerId: $alertText")
                        triggerSosAlert(peerId, alertText)
                    } else if (msg.priority == QueuePriority.GROUP_MESSAGE) {
                        val contentStr = try { String(msg.payload) } catch (e: Exception) { "" }
                        android.util.Log.d("MeshManager", "GROUP_MESSAGE payload string: $contentStr")
                        if (contentStr.startsWith("GROUP_TXT|")) {
                            val parts = contentStr.split("|")
                            // Format: GROUP_TXT|msgId|senderId|senderName|text
                            if (parts.size >= 5) {
                                val msgId = parts[1]
                                val senderId = parts[2]
                                val senderName = parts[3]
                                val text = parts[4]
                                android.util.Log.d("MeshManager", "Processed Group Text: msgId=$msgId senderId=$senderId ($senderName) text=$text")
                                // Skip echo: we already added this message optimistically when sending
                                if (senderId == _nodeId.value) {
                                    android.util.Log.d("MeshManager", "Skipping GROUP_TXT echo from self (msgId=$msgId)")
                                } else {
                                    addGroupMessage(ChatMessage(
                                        id = msgId,
                                        senderId = senderId,
                                        text = text,
                                        isSentByMe = false,
                                        senderName = senderName,
                                        isGroup = true
                                    ))
                                }
                            } else if (parts.size == 4) {
                                // Legacy format without msgId: GROUP_TXT|senderId|senderName|text
                                val senderId = parts[1]
                                val senderName = parts[2]
                                val text = parts[3]
                                if (senderId != _nodeId.value) {
                                    addGroupMessage(ChatMessage(
                                        senderId = senderId,
                                        text = text,
                                        isSentByMe = false,
                                        senderName = senderName,
                                        isGroup = true
                                    ))
                                }
                            } else {
                                android.util.Log.w("MeshManager", "GROUP_TXT parts size was < 4: ${parts.size}")
                            }
                        } else if (contentStr.startsWith("GROUP_FILE_MANIFEST|")) {
                            val parts = contentStr.split("|")
                            if (parts.size >= 13) {
                                val bundleId = parts[1]
                                val messageId = parts[2]
                                val totalSizeBytes = parts[3].toLong()
                                val chunkSize = parts[4].toInt()
                                val chunkCount = parts[5].toInt()
                                val bundleHash = parts[6]
                                val priority = parts[7].toInt()
                                val fileName = parts[8]
                                val isImage = parts[9].toBoolean()
                                val isGroup = parts[10].toBoolean()
                                val senderId = parts[11]
                                val senderName = parts[12]

                                android.util.Log.d("MeshManager", "Processed Group File Manifest: senderId=$senderId ($senderName) file=$fileName size=$totalSizeBytes bundleId=$bundleId")
                                // Only add placeholder if we are not the original sender (already added it in sendGroupFile)
                                if (senderId != _nodeId.value) {
                                    android.util.Log.d("UI_INSERT", "Placeholder added: source=GROUP_FILE_MANIFEST, messageId=$messageId, bundleId=$bundleId, file=$fileName")
                                    addGroupMessage(ChatMessage(
                                        id = messageId,
                                        senderId = senderId,
                                        text = "Sent a file: $fileName",
                                        isSentByMe = false,
                                        senderName = senderName,
                                        fileName = fileName,
                                        fileSize = totalSizeBytes,
                                        isImage = isImage,
                                        isFile = !isImage,
                                        isGroup = true
                                    ))
                                    startIncomingFileTransferFromPeer(senderId, bundleId, messageId, totalSizeBytes, chunkSize, chunkCount, bundleHash, priority, fileName, isImage, isGroup)
                                }
                            } else {
                                android.util.Log.w("MeshManager", "GROUP_FILE_MANIFEST parts size was < 13: ${parts.size}")
                            }
                        } else {
                            android.util.Log.w("MeshManager", "GROUP_MESSAGE priority but content prefix did not match: $contentStr")
                        }
                    } else {
                        android.util.Log.d("MeshManager", "Ignored message priority: ${msg.priority}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MeshManager", "Failed to deserialize Message from payload: ${e.message}", e)
                }
            }
        }

        val transferManager = module.transferManager
        transferManager.onTransferProgressUpdated = { progress ->
            val current = _transfers.value
            _transfers.value = current + (progress.bundleId to progress)
        }

        transferManager.onFileReceived = { peerId, bundleId, fileName, fileBytes, isImage, isGroup ->
            if (deliveredBundleIds.add(bundleId)) {
                val context = MainActivity.currentContext
                if (context != null) {
                    val file = java.io.File(context.cacheDir, "${UUID.randomUUID()}_$fileName")
                    try {
                        file.writeBytes(fileBytes)
                        val filePath = file.absolutePath
                        android.util.Log.d("MeshManager", "onFileReceived: peerId=$peerId file=$fileName isGroup=$isGroup path=$filePath")

                        if (isGroup) {
                            // Update existing placeholder message (from manifest) in-place rather than appending a duplicate
                            val updated = updateGroupMessageFilePath(fileName, filePath, fileBytes.size.toLong(), isImage)
                            if (updated) {
                                android.util.Log.d("UI_INSERT", "Placeholder updated: source=onFileReceived group, bundleId=$bundleId, file=$fileName, path=$filePath")
                            } else {
                                // No placeholder found — add a fresh message (e.g. relay path where manifest wasn't received)
                                android.util.Log.d("UI_INSERT", "Fallback card added: source=onFileReceived group, bundleId=$bundleId, file=$fileName")
                                addGroupMessage(ChatMessage(
                                    id = bundleId,
                                    senderId = peerId,
                                    text = "Sent a file: $fileName",
                                    isSentByMe = false,
                                    senderName = getPeerName(peerId),
                                    filePath = filePath,
                                    fileName = fileName,
                                    fileSize = fileBytes.size.toLong(),
                                    isImage = isImage,
                                    isFile = !isImage,
                                    isGroup = true
                                ))
                            }

                            // Relay to other peers that don't have a direct link to the original sender
                            val activePeers = moduleInstance?.peerTable?.getAll() ?: emptyList()
                            for (peer in activePeers) {
                                if (peer.devicePublicKeyId != peerId) {
                                    sendFileToPeer(peer.devicePublicKeyId, fileName, fileBytes, isGroup = true, bundleId = bundleId)
                                }
                            }
                        } else {
                            android.util.Log.d("UI_INSERT", "Direct card added: source=onFileReceived direct, bundleId=$bundleId, file=$fileName")
                            addMessage(peerId, ChatMessage(
                                id = bundleId,
                                senderId = peerId,
                                text = "Sent a file: $fileName",
                                isSentByMe = false,
                                senderName = getPeerName(peerId),
                                filePath = filePath,
                                fileName = fileName,
                                fileSize = fileBytes.size.toLong(),
                                isImage = isImage,
                                isFile = !isImage,
                                isGroup = false
                            ))
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MeshManager", "Error saving received file: ${e.message}")
                    }
                }
            } else {
                android.util.Log.d("MeshManager", "onFileReceived: duplicate dropped bundleId=$bundleId")
            }
        }

        _nodeId.value = module.securityManager.longTermPublicKeyId
        _isStarted.value = true

        module.startWiFiDiscovery(DiscoveryMode.SOS)
        
        kotlin.concurrent.thread(name = "MeshUiPoller") {
            while (_isStarted.value) {
                val mod = moduleInstance ?: break
                _peers.value = mod.peerTable.getAll()
                _logs.value = mod.auditLogger.export().takeLast(100).reversed()
                
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
        // Clear peer list immediately so stale cards vanish from the dashboard
        _peers.value = emptyList()
        // Clear seen-ID set so a fresh session starts clean
        seenMessageIds.clear()
        deliveredBundleIds.clear()
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
        kotlin.concurrent.thread {
            module.attemptSOSOverWifi(sosMsg)
        }
    }

    fun sendTestMessage(peerId: String, content: String) {
        val module = moduleInstance ?: return
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
            isSentByMe = true,
            senderName = getUsername()
        )
        addMessage(peerId, chatMsg)

        kotlin.concurrent.thread(name = "MeshMessageSender") {
            try {
                val currentSessions = module.connectionManager.getActiveSessionCount()
                val hasSession = module.connectionManager.hasActiveSession(peerId)
                if (currentSessions >= 4 && !hasSession) {
                    android.util.Log.e("MeshManager", "Connection limit reached. Cannot connect to $peerId")
                    return@thread
                }
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

    fun sendGroupMessage(text: String) {
        val module = moduleInstance ?: return
        val senderName = getUsername()
        val msgId = "grp-" + UUID.randomUUID().toString().take(12)
        // Include msgId in the payload so receivers can deduplicate echoes
        val contentStr = "GROUP_TXT|$msgId|${_nodeId.value}|$senderName|$text"
        val msg = Message(
            id = msgId,
            priority = QueuePriority.GROUP_MESSAGE,
            expiryTimestamp = System.currentTimeMillis() + 300_000L,
            ttl = 8,
            payload = contentStr.toByteArray()
        )

        val localMsg = ChatMessage(
            id = msgId,
            senderId = _nodeId.value,
            text = text,
            isSentByMe = true,
            senderName = senderName,
            isGroup = true
        )
        // NOTE: do NOT pre-register msgId in seenMessageIds here.
        // addGroupMessage will register it on first insertion.
        // Echo-prevention on receive is handled in onPayloadReceived by
        // checking senderId == _nodeId.value — so no echo will ever reach addGroupMessage.
        addGroupMessage(localMsg)

        kotlin.concurrent.thread(name = "MeshGroupMessageSender") {
            val activePeers = module.peerTable.getAll()
            android.util.Log.d("MeshManager", "sendGroupMessage: sending to ${activePeers.size} peers. msgId=${msg.id}")
            for (peer in activePeers) {
                try {
                    val session = module.connectionManager.getSession(peer.devicePublicKeyId)
                        ?: module.connectToWiFiPeer(peer.devicePublicKeyId)
                    if (session != null) {
                        val success = module.transferManager.sendPayload(peer.devicePublicKeyId, msg.serialize())
                        android.util.Log.d("MeshManager", "sendGroupMessage: sent grp msg to ${peer.devicePublicKeyId}. success=$success")
                    } else {
                        android.util.Log.w("MeshManager", "sendGroupMessage: could not establish session to peer ${peer.devicePublicKeyId}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MeshManager", "sendGroupMessage: error sending to peer ${peer.devicePublicKeyId}: ${e.message}", e)
                }
            }
        }
    }

    fun sendFile(peerId: String, fileName: String, fileBytes: ByteArray) {
        val error = canSendFile(fileName, fileBytes.size.toLong())
        if (error != null) {
            showToast(error)
            return
        }

        val isImage = fileName.endsWith(".png", true) || fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) || fileName.endsWith(".gif", true)
        if (isImage) incrementDailyImageCount()
        incrementDailyByteCount(fileBytes.size.toLong())

        val context = MainActivity.currentContext ?: return
        val file = java.io.File(context.cacheDir, "${UUID.randomUUID()}_$fileName")
        file.writeBytes(fileBytes)
        
        val chatMsg = ChatMessage(
            senderId = _nodeId.value,
            text = "Sent a file: $fileName",
            isSentByMe = true,
            filePath = file.absolutePath,
            fileName = fileName,
            fileSize = fileBytes.size.toLong(),
            isImage = isImage,
            isFile = !isImage,
            isGroup = false
        )
        addMessage(peerId, chatMsg)

        val module = moduleInstance ?: return
        kotlin.concurrent.thread {
            val currentSessions = module.connectionManager.getActiveSessionCount()
            val hasSession = module.connectionManager.hasActiveSession(peerId)
            if (currentSessions >= 4 && !hasSession) {
                android.util.Log.e("MeshManager", "Connection limit reached. Cannot connect to $peerId")
                return@thread
            }
            val session = module.connectToWiFiPeer(peerId)
            if (session != null) {
                module.transferManager.sendFile(peerId, fileName, fileBytes, isGroup = false)
            }
        }
    }

    fun sendGroupFile(fileName: String, fileBytes: ByteArray) {
        val error = canSendFile(fileName, fileBytes.size.toLong())
        if (error != null) {
            showToast(error)
            return
        }

        val isImage = fileName.endsWith(".png", true) || fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) || fileName.endsWith(".gif", true)
        if (isImage) incrementDailyImageCount()
        incrementDailyByteCount(fileBytes.size.toLong())

        val context = MainActivity.currentContext ?: return
        val file = java.io.File(context.cacheDir, "${UUID.randomUUID()}_$fileName")
        file.writeBytes(fileBytes)
        
        val chatMsg = ChatMessage(
            senderId = _nodeId.value,
            text = "Sent a file: $fileName",
            isSentByMe = true,
            senderName = getUsername(),
            filePath = file.absolutePath,
            fileName = fileName,
            fileSize = fileBytes.size.toLong(),
            isImage = isImage,
            isFile = !isImage,
            isGroup = true
        )
        addGroupMessage(chatMsg)

        val module = moduleInstance ?: return
        kotlin.concurrent.thread(name = "MeshGroupFileSender") {
            val activePeers = module.peerTable.getAll()
            
            val bundleId = UUID.randomUUID().toString()
            val messageId = UUID.randomUUID().toString()
            val chunkSize = 32 * 1024
            val chunkCount = ((fileBytes.size + chunkSize - 1) / chunkSize)
            val bundleHash = "hash-" + UUID.randomUUID().toString().take(8)
            val priority = QueuePriority.GROUP_MESSAGE.value
            
            val senderName = getUsername()
            val manifestStr = "GROUP_FILE_MANIFEST|$bundleId|$messageId|${fileBytes.size}|$chunkSize|$chunkCount|$bundleHash|$priority|$fileName|$isImage|true|${_nodeId.value}|$senderName"
            
            val msg = Message(
                id = "gf-" + UUID.randomUUID().toString().take(8),
                priority = QueuePriority.GROUP_MESSAGE,
                expiryTimestamp = System.currentTimeMillis() + 300_000L,
                ttl = 8,
                payload = manifestStr.toByteArray()
            )

            android.util.Log.d("MeshManager", "sendGroupFile: sending to ${activePeers.size} peers. filename=$fileName bundleId=$bundleId")
            for (peer in activePeers) {
                try {
                    val session = module.connectionManager.getSession(peer.devicePublicKeyId)
                        ?: module.connectToWiFiPeer(peer.devicePublicKeyId)
                    if (session != null) {
                        // Pass bundleId so the chunk transfer ID matches the manifest broadcast
                        module.transferManager.sendFile(peer.devicePublicKeyId, fileName, fileBytes, isGroup = true, bundleId = bundleId)
                        val success = module.transferManager.sendPayload(peer.devicePublicKeyId, msg.serialize())
                        android.util.Log.d("MeshManager", "sendGroupFile: sent manifest & file to ${peer.devicePublicKeyId}. success=$success")
                    } else {
                        android.util.Log.w("MeshManager", "sendGroupFile: could not establish session to peer ${peer.devicePublicKeyId}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MeshManager", "sendGroupFile: error sending to peer ${peer.devicePublicKeyId}: ${e.message}", e)
                }
            }
        }
    }

    fun resumeFileTransfer(peerId: String, bundleId: String) {
        val module = moduleInstance ?: return
        module.transferManager.resumeFile(peerId, bundleId)
    }

    fun sendFileToPeer(peerId: String, fileName: String, fileBytes: ByteArray, isGroup: Boolean, bundleId: String? = null) {
        val module = moduleInstance ?: return
        kotlin.concurrent.thread {
            val session = module.connectToWiFiPeer(peerId)
            if (session != null) {
                module.transferManager.sendFile(peerId, fileName, fileBytes, isGroup = isGroup, bundleId = bundleId)
            }
        }
    }

    fun startIncomingFileTransferFromPeer(
        peerId: String,
        bundleId: String,
        messageId: String,
        totalSizeBytes: Long,
        chunkSize: Int,
        chunkCount: Int,
        bundleHash: String,
        priority: Int,
        fileName: String,
        isImage: Boolean,
        isGroup: Boolean
    ) {
        val module = moduleInstance ?: return
        if (module.transferManager.isCompleted(bundleId)) {
            android.util.Log.d("MeshManager", "startIncomingFileTransferFromPeer: bundleId=$bundleId already completed. Skipping.")
            return
        }
        kotlin.concurrent.thread {
            val session = module.connectToWiFiPeer(peerId)
            if (session != null) {
                val manifest = WiFiBundleManifest(
                    bundleId = bundleId,
                    messageId = messageId,
                    totalSizeBytes = totalSizeBytes,
                    chunkSizeValue = chunkSize,
                    chunkCount = chunkCount,
                    bundleHash = bundleHash,
                    priority = priority,
                    expiresAt = System.currentTimeMillis() + 3600_000L
                )
                module.transferManager.chunkEngine.initIncoming(manifest, peerId)

                val progress = TransferProgress(
                    bundleId = bundleId,
                    fileName = fileName,
                    totalBytes = totalSizeBytes,
                    bytesTransferred = 0L,
                    isSender = false,
                    isComplete = false,
                    isImage = isImage,
                    isGroup = isGroup,
                    peerId = peerId
                )
                module.transferManager.transferProgress[bundleId] = progress
                module.transferManager.onTransferProgressUpdated?.invoke(progress)

                val bitmask = module.transferManager.chunkEngine.getReceivedChunkMask(bundleId) ?: java.util.BitSet()
                val bitmaskStr = (0 until bitmask.length()).filter { bitmask.get(it) }.joinToString(",")
                module.transferManager.sendPayload(peerId, "MANIFEST_ACK|$bundleId|$bitmaskStr".toByteArray())
            }
        }
    }

    fun getPeerName(peerId: String): String = "Node-${peerId.take(8)}"

    private fun addMessage(peerId: String, message: ChatMessage) {
        // Deduplication: skip if this message ID was already displayed
        if (!seenMessageIds.add(message.id)) {
            android.util.Log.d("MeshManager", "addMessage: duplicate dropped id=${message.id}")
            return
        }
        // Cap the seen-IDs set size to avoid unbounded memory growth
        if (seenMessageIds.size > 500) {
            seenMessageIds.iterator().let { it.next(); it.remove() }
        }
        val current = _messages.value
        val list = current[peerId] ?: emptyList()
        _messages.value = current + (peerId to (list + message))
    }

    private fun addGroupMessage(message: ChatMessage) {
        // Deduplication: skip if this message ID was already displayed
        if (!seenMessageIds.add(message.id)) {
            android.util.Log.d("MeshManager", "addGroupMessage: duplicate dropped id=${message.id}")
            return
        }
        if (seenMessageIds.size > 500) {
            seenMessageIds.iterator().let { it.next(); it.remove() }
        }
        val current = _messages.value
        val list = current["GROUP_CHAT"] ?: emptyList()
        _messages.value = current + ("GROUP_CHAT" to (list + message))
    }

    /**
     * Finds the most recent group placeholder message for [fileName] that has no filePath yet
     * and updates it in-place with the completed download path and size.
     * Returns true if an existing placeholder was found and updated, false otherwise.
     */
    private fun updateGroupMessageFilePath(fileName: String, filePath: String, fileSize: Long, isImage: Boolean): Boolean {
        val current = _messages.value
        val list = (current["GROUP_CHAT"] ?: emptyList()).toMutableList()
        val idx = list.indexOfLast { it.fileName == fileName && it.filePath == null && it.isGroup }
        if (idx == -1) return false
        list[idx] = list[idx].copy(
            filePath = filePath,
            fileSize = fileSize,
            isImage = isImage,
            isFile = !isImage
        )
        _messages.value = current + ("GROUP_CHAT" to list.toList())
        android.util.Log.d("MeshManager", "updateGroupMessageFilePath: updated placeholder at idx=$idx for file=$fileName")
        return true
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
        val context = MainActivity.currentContext ?: return
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

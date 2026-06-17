package com.wifidirect.mesh.sync

import com.wifidirect.mesh.connection.WiFiConnectionManager
import com.wifidirect.mesh.models.WiFiConnectionSession
import com.wifidirect.mesh.security.WiFiSecurityManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

import com.wifidirect.mesh.models.QueuePriority
import com.wifidirect.mesh.models.WiFiBundleManifest
import com.wifidirect.mesh.models.WiFiChunk
import java.util.BitSet
import java.util.UUID

data class TransferProgress(
    val bundleId: String,
    val fileName: String,
    val totalBytes: Long,
    val bytesTransferred: Long,
    val isSender: Boolean,
    val isComplete: Boolean = false,
    val isFailed: Boolean = false,
    val isImage: Boolean = false,
    val isGroup: Boolean = false,
    val peerId: String
)

class WiFiTransferManager(
    private val connectionManager: WiFiConnectionManager,
    private val securityManager: WiFiSecurityManager,
    private val onPayloadReceived: (peerId: String, payload: ByteArray) -> Unit = { _, _ -> }
) {
    private val activeListeners = ConcurrentHashMap<String, Boolean>()
    private val executor = Executors.newCachedThreadPool()

    val chunkEngine = WiFiChunkTransferEngine()
    val transferProgress = ConcurrentHashMap<String, TransferProgress>()
    @Volatile var onTransferProgressUpdated: ((TransferProgress) -> Unit)? = null
    @Volatile var onFileReceived: ((peerId: String, bundleId: String, fileName: String, fileBytes: ByteArray, isImage: Boolean, isGroup: Boolean) -> Unit)? = null
    private val completedBundleIds = ConcurrentHashMap.newKeySet<String>()

    fun isCompleted(bundleId: String): Boolean = completedBundleIds.contains(bundleId)

    fun startListeningToSession(session: WiFiConnectionSession) {
        val peerId = session.peerId
        if (connectionManager.getActiveSessionCount() > 4) {
            System.err.println("Active sessions count exceeded limit of 4. Closing session with ${peerId}")
            connectionManager.closeSession(peerId)
            return
        }
        if (activeListeners.putIfAbsent(peerId, true) != null) return // Already listening

        executor.submit {
            val socket = session.socketHandle
            if (socket == null) {
                activeListeners.remove(peerId)
                return@submit
            }
            try {
                val input = DataInputStream(socket.getInputStream())
                while (activeListeners[peerId] == true) {
                    val encryptedPayload = readFrame(input)
                    val decryptedPayload = securityManager.decryptPayload(session.sessionId, encryptedPayload)
                    if (decryptedPayload != null) {
                        val str = try { String(decryptedPayload) } catch (e: Exception) { "" }
                        if (str.startsWith("FILE_MANIFEST|") || str.startsWith("MANIFEST_ACK|") ||
                            str.startsWith("FILE_CHUNK|") || str.startsWith("CHUNK_ACK|")) {
                            handleFileTransferPayload(peerId, str)
                        } else {
                            onPayloadReceived(peerId, decryptedPayload)
                        }
                    } else {
                        System.err.println("Failed to decrypt incoming payload from $peerId")
                    }
                }
            } catch (e: IOException) {
                // Connection closed or broken
                System.err.println("Transfer connection lost with $peerId: ${e.message}")
            } finally {
                activeListeners.remove(peerId)
                connectionManager.closeSession(peerId)
            }
        }
    }

    fun stopListeningToSession(peerId: String) {
        activeListeners[peerId] = false
    }

    fun sendPayload(peerId: String, payload: ByteArray): Boolean {
        val session = connectionManager.getSession(peerId) ?: return false
        val socket = session.socketHandle ?: return false
        
        try {
            val encryptedPayload = securityManager.encryptPayload(session.sessionId, payload) ?: return false
            val output = DataOutputStream(socket.getOutputStream())
            writeFrame(output, encryptedPayload)
            
            synchronized(session) {
                session.lastActivityTimestamp = System.currentTimeMillis()
            }
            return true
        } catch (e: IOException) {
            System.err.println("Failed to write payload to $peerId: ${e.message}")
            connectionManager.closeSession(peerId)
            return false
        }
    }

    private fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
        synchronized(out) {
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val length = input.readInt()
        if (length < 0 || length > 10 * 1024 * 1024) { // 10MB cap
            throw IOException("Invalid frame size: $length")
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes
    }

    private fun handleFileTransferPayload(peerId: String, payloadStr: String) {
        val parts = payloadStr.split("|")
        if (parts.isEmpty()) return
        val command = parts[0]
        try {
            when (command) {
                "FILE_MANIFEST" -> {
                    if (parts.size >= 12) {
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
                        chunkEngine.initIncoming(manifest, peerId)
                        
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
                        transferProgress[bundleId] = progress
                        onTransferProgressUpdated?.invoke(progress)

                        val bitmask = chunkEngine.getReceivedChunkMask(bundleId) ?: BitSet()
                        val bitmaskStr = bitmaskToIndicesString(bitmask)
                        sendPayload(peerId, "MANIFEST_ACK|$bundleId|$bitmaskStr".toByteArray())
                    }
                }
                "MANIFEST_ACK" -> {
                    if (parts.size >= 2) {
                        val bundleId = parts[1]
                        val bitmaskStr = if (parts.size >= 3) parts[2] else ""
                        
                        val bitmask = indicesStringToBitmask(bitmaskStr)
                        for (i in 0 until bitmask.length()) {
                            if (bitmask.get(i)) {
                                chunkEngine.markChunkAcked(bundleId, i)
                            }
                        }

                        val progress = transferProgress[bundleId]
                        if (progress != null) {
                            val ackedCount = bitmask.cardinality()
                            val bytesTransferred = minOf(progress.totalBytes, ackedCount.toLong() * 32 * 1024)
                            val updated = progress.copy(bytesTransferred = bytesTransferred)
                            transferProgress[bundleId] = updated
                            onTransferProgressUpdated?.invoke(updated)
                        }

                        sendNextChunk(peerId, bundleId)
                    }
                }
                "FILE_CHUNK" -> {
                    if (parts.size >= 5) {
                        val bundleId = parts[1]
                        val chunkIndex = parts[2].toInt()
                        
                        if (completedBundleIds.contains(bundleId)) {
                            sendPayload(peerId, "CHUNK_ACK|$bundleId|$chunkIndex".toByteArray())
                            return
                        }

                        val chunkHash = parts[3]
                        val hexPayload = parts[4]
                        val payload = hexToBytes(hexPayload)

                        val chunk = WiFiChunk(
                            bundleId = bundleId,
                            chunkIndex = chunkIndex,
                            payload = payload,
                            chunkHash = chunkHash
                        )
                        val accepted = chunkEngine.receiveChunk(chunk)
                        if (accepted) {
                            sendPayload(peerId, "CHUNK_ACK|$bundleId|$chunkIndex".toByteArray())

                            val progress = transferProgress[bundleId]
                            if (progress != null) {
                                val receivedCount = (chunkEngine.getReceivedChunkMask(bundleId)?.cardinality() ?: 0)
                                val bytesTransferred = minOf(progress.totalBytes, receivedCount.toLong() * 32 * 1024)
                                val isComplete = chunkEngine.isIncomingComplete(bundleId)
                                val updated = progress.copy(
                                    bytesTransferred = bytesTransferred,
                                    isComplete = isComplete
                                )
                                transferProgress[bundleId] = updated
                                onTransferProgressUpdated?.invoke(updated)

                                if (isComplete && completedBundleIds.add(bundleId)) {
                                    val fullBytes = chunkEngine.tryReassemble(bundleId)
                                    if (fullBytes != null) {
                                        onFileReceived?.invoke(peerId, bundleId, progress.fileName, fullBytes, progress.isImage, progress.isGroup)
                                    }
                                }
                            }
                        }
                    }
                }
                "CHUNK_ACK" -> {
                    if (parts.size >= 3) {
                        val bundleId = parts[1]
                        val chunkIndex = parts[2].toInt()

                        chunkEngine.markChunkAcked(bundleId, chunkIndex)

                        val progress = transferProgress[bundleId]
                        if (progress != null) {
                            val missing = chunkEngine.getMissingChunks(bundleId)
                            val totalChunks = (progress.totalBytes + (32 * 1024) - 1) / (32 * 1024)
                            val ackedCount = totalChunks - (missing?.cardinality() ?: 0)
                            val bytesTransferred = minOf(progress.totalBytes, ackedCount.toLong() * 32 * 1024)
                            val isComplete = ackedCount >= totalChunks
                            val updated = progress.copy(
                                bytesTransferred = bytesTransferred,
                                isComplete = isComplete
                            )
                            transferProgress[bundleId] = updated
                            onTransferProgressUpdated?.invoke(updated)
                        }

                        sendNextChunk(peerId, bundleId)
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Error parsing file transfer payload: ${e.message}")
        }
    }

    private fun sendNextChunk(peerId: String, bundleId: String) {
        val nextChunk = chunkEngine.nextChunkToSend(bundleId)
        if (nextChunk != null) {
            val hexPayload = bytesToHex(nextChunk.payload)
            val chunkMsg = "FILE_CHUNK|$bundleId|${nextChunk.chunkIndex}|${nextChunk.chunkHash}|$hexPayload"
            sendPayload(peerId, chunkMsg.toByteArray())
        } else {
            val progress = transferProgress[bundleId]
            if (progress != null && !progress.isComplete) {
                val updated = progress.copy(isComplete = true)
                transferProgress[bundleId] = updated
                onTransferProgressUpdated?.invoke(updated)
            }
        }
    }

    fun sendFile(peerId: String, fileName: String, fileBytes: ByteArray, isGroup: Boolean = false, bundleId: String? = null): String {
        val bundleId = bundleId ?: UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()
        val isImage = fileName.endsWith(".png", true) || fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) || fileName.endsWith(".gif", true)

        val manifest = chunkEngine.prepareOutgoing(
            bundleId = bundleId,
            messageId = messageId,
            payload = fileBytes,
            priority = if (isGroup) QueuePriority.GROUP_MESSAGE.value else QueuePriority.DIRECT_MESSAGE.value,
            expiresAt = System.currentTimeMillis() + 3600_000L,
            peerId = peerId
        )

        val progress = TransferProgress(
            bundleId = bundleId,
            fileName = fileName,
            totalBytes = fileBytes.size.toLong(),
            bytesTransferred = 0L,
            isSender = true,
            isComplete = false,
            isImage = isImage,
            isGroup = isGroup,
            peerId = peerId
        )
        transferProgress[bundleId] = progress
        onTransferProgressUpdated?.invoke(progress)

        val manifestMsg = "FILE_MANIFEST|$bundleId|$messageId|${fileBytes.size}|${manifest.chunkSizeValue}|${manifest.chunkCount}|${manifest.bundleHash}|${manifest.priority}|$fileName|$isImage|$isGroup|$peerId"
        sendPayload(peerId, manifestMsg.toByteArray())

        return bundleId
    }

    fun resumeFile(peerId: String, bundleId: String) {
        val progress = transferProgress[bundleId] ?: return
        val updated = progress.copy(isFailed = false, isComplete = false)
        transferProgress[bundleId] = updated
        onTransferProgressUpdated?.invoke(updated)

        val priority = if (progress.isGroup) QueuePriority.GROUP_MESSAGE.value else QueuePriority.DIRECT_MESSAGE.value
        val totalChunks = (progress.totalBytes + (32 * 1024) - 1) / (32 * 1024)
        
        val manifestMsg = "FILE_MANIFEST|$bundleId|msg-resume|${progress.totalBytes}|${32 * 1024}|$totalChunks|hash-resume|$priority|${progress.fileName}|${progress.isImage}|${progress.isGroup}|$peerId"
        sendPayload(peerId, manifestMsg.toByteArray())
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun bitmaskToIndicesString(bitSet: BitSet): String {
        val list = mutableListOf<Int>()
        for (i in 0 until bitSet.length()) {
            if (bitSet.get(i)) {
                list.add(i)
            }
        }
        return list.joinToString(",")
    }

    private fun indicesStringToBitmask(str: String): BitSet {
        val bitSet = BitSet()
        if (str.isEmpty()) return bitSet
        str.split(",").forEach {
            if (it.isNotEmpty()) {
                bitSet.set(it.toInt())
            }
        }
        return bitSet
    }
}

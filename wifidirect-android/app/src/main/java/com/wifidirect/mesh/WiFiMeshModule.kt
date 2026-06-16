package com.wifidirect.mesh

import com.wifidirect.mesh.models.*
import java.nio.ByteBuffer

// Core Context and Payload structures
data class SystemContext(
    private val batteryLevel: Int,
    private val isCharging: Boolean,
    private val tempCelsius: Float,
    private val userPolicy: UserWiFiPolicy,
    private val hasActiveSOS: Boolean,
    private val isBackground: Boolean
) {
    fun getBatteryLevel() = batteryLevel
    fun isDeviceCharging() = isCharging
    fun getDeviceTemperatureCelsius() = tempCelsius
    fun getUserWiFiPolicy() = userPolicy
    fun hasActiveSOS() = hasActiveSOS
    fun isBackground() = isBackground
}

data class Message(
    val id: String,
    val priority: QueuePriority,
    val expiryTimestamp: Long,
    var ttl: Int,
    val payload: ByteArray
) {
    fun serialize(): ByteArray {
        val idBytes = id.toByteArray()
        val payloadSize = payload.size
        val buffer = ByteBuffer.allocate(4 + idBytes.size + 4 + 8 + 4 + 4 + payloadSize)
        buffer.putInt(idBytes.size)
        buffer.put(idBytes)
        buffer.putInt(priority.value)
        buffer.putLong(expiryTimestamp)
        buffer.putInt(ttl)
        buffer.putInt(payloadSize)
        buffer.put(payload)
        return buffer.array()
    }

    companion object {
        fun deserialize(bytes: ByteArray): Message {
            val buffer = ByteBuffer.wrap(bytes)
            val idLen = buffer.getInt()
            val idBytes = ByteArray(idLen)
            buffer.get(idBytes)
            val id = String(idBytes)
            val priorityVal = buffer.getInt()
            val priority = QueuePriority.values().first { it.value == priorityVal }
            val expiry = buffer.getLong()
            val ttl = buffer.getInt()
            val payloadLen = buffer.getInt()
            val payload = ByteArray(payloadLen)
            buffer.get(payload)
            return Message(id, priority, expiry, ttl, payload)
        }
    }
}

data class WiFiClusterACKSummary(
    val senderId: String,
    val messageIds: List<String>,
    val timestamp: Long
) {
    fun serialize(): ByteArray {
        val senderBytes = senderId.toByteArray()
        val listSize = messageIds.size
        var totalIdBytes = 0
        val idByteList = messageIds.map { it.toByteArray() }
        idByteList.forEach { totalIdBytes += 4 + it.size }

        val buffer = ByteBuffer.allocate(4 + senderBytes.size + 4 + totalIdBytes + 8)
        buffer.putInt(senderBytes.size)
        buffer.put(senderBytes)
        buffer.putInt(listSize)
        idByteList.forEach {
            buffer.putInt(it.size)
            buffer.put(it)
        }
        buffer.putLong(timestamp)
        return buffer.array()
    }

    companion object {
        fun deserialize(bytes: ByteArray): WiFiClusterACKSummary {
            val buffer = ByteBuffer.wrap(bytes)
            val senderLen = buffer.getInt()
            val senderBytes = ByteArray(senderLen)
            buffer.get(senderBytes)
            val senderId = String(senderBytes)
            val listSize = buffer.getInt()
            val messageIds = mutableListOf<String>()
            for (i in 0 until listSize) {
                val len = buffer.getInt()
                val idBytes = ByteArray(len)
                buffer.get(idBytes)
                messageIds.add(String(idBytes))
            }
            val timestamp = buffer.getLong()
            return WiFiClusterACKSummary(senderId, messageIds, timestamp)
        }
    }
}

interface WiFiMeshModule {
    fun evaluateWiFiEligibility(context: SystemContext): WiFiPolicyState
    fun startWiFiDiscovery(mode: DiscoveryMode)
    fun stopWiFiDiscovery(reason: String)
    fun handlePeerSighting(sighting: WiFiPeerSighting)
    fun rankWiFiPeersForConnection(peers: List<WiFiPeer>, context: SystemContext): List<WiFiRelayCandidate>
    fun connectToWiFiPeer(peerId: String): WiFiConnectionSession?
    fun createWiFiCluster()
    fun joinWiFiCluster(cluster: WiFiCluster)
    fun leaveWiFiCluster(reason: String)
    fun electClusterLeader(candidates: List<WiFiLeaderCandidate>): String
    fun calculateWiFiLeaderScore(candidate: WiFiLeaderCandidate): Float
    fun performSecureHandshake(session: WiFiConnectionSession): Boolean
    fun exchangeCapabilities(session: WiFiConnectionSession)
    fun exchangeBackpressure(session: WiFiConnectionSession)
    fun exchangeMessageSummary(session: WiFiConnectionSession): WiFiMessageSummary
    fun selectBundlesForTransfer(peerId: String, remoteSummary: WiFiMessageSummary): List<String>
    fun scheduleWiFiTransfer(peerId: String, bundleId: String)
    fun sendBundleChunk(peerId: String, chunk: WiFiChunk)
    fun receiveBundleChunk(peerId: String, chunk: WiFiChunk)
    fun resumeBundleTransfer(peerId: String, bundleId: String)
    fun sendWiFiAck(peerId: String, ack: WiFiClusterACKSummary)
    fun aggregateAck(messageId: String): WiFiClusterACKSummary
    fun calculateCongestionScore(metrics: WiFiCongestionMetrics): Float
    fun updateCongestionState(score: Float): WiFiCongestionState
    fun applyCongestionPolicy(state: WiFiCongestionState)
    fun shouldRebroadcast(message: Message, context: SystemContext): Boolean
    fun calculateForwardProbability(message: Message, context: SystemContext): Float
    fun scheduleRebroadcast(message: Message)
    fun cancelRebroadcast(messageId: String)
    fun updateWiFiLinkQuality(peerId: String, metrics: WiFiLinkState)
    fun advertiseBackpressure(): WiFiBackpressureState
    fun handleBackpressureFromPeer(peerId: String, state: WiFiBackpressureState)
    fun applyWiFiCooldown(peerId: String, reason: WiFiFailureReason)
    fun handleWiFiFailure(peerId: String, reason: WiFiFailureReason)
    fun reportWiFiStatusToRouter(): WiFiFailureReason?
    fun logWiFiAuditEvent(event: WiFiAuditLogEntry)
}

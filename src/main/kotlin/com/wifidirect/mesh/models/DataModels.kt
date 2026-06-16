package com.wifidirect.mesh.models

import java.net.Socket
import java.util.BitSet

// Enums
enum class ThermalState { NORMAL, HIGH, CRITICAL }
enum class UserWiFiPolicy { ALLOW_ALL, SMART_ONLY, SOS_ONLY }
enum class DiscoveryMode { ACTIVE, IDLE, SOS, CONGESTED, LOW_POWER }
enum class CongestionState { GREEN, YELLOW, ORANGE, RED }
enum class QueuePriority(val value: Int) {
    CONTROL(0),
    SOS(1),
    SOS_ACK(2),
    EMERGENCY(3),
    ROUTING_SUMMARY(4),
    DIRECT_MESSAGE(5),
    GROUP_MESSAGE(6),
    BULK_SYNC(7),
    BACKGROUND(8)
}
enum class WiFiFailureReason {
    WIFI_UNSUPPORTED,
    WIFI_DISABLED,
    WIFI_POLICY_BLOCKED,
    LOW_BATTERY,
    THERMAL_CRITICAL,
    DISCOVERY_TIMEOUT,
    NO_PEERS_FOUND,
    CONNECTION_FAILED,
    HANDSHAKE_FAILED,
    TRANSFER_TIMEOUT,
    ACK_TIMEOUT,
    CONGESTION_RED,
    LEADER_OVERLOADED,
    OS_RESTRICTED,
    USER_LOW_POWER_MODE,
    PEER_COOLDOWN_ACTIVE
}
enum class TransportType { WIFI, BLE, SMS, INTERNET }
enum class DiscoveryType { WIFI_DIRECT, SAME_LAN, HOTSPOT, NAN }

// Endpoint abstraction
data class WiFiEndpoint(
    val type: DiscoveryType,
    val address: String, // IP Address or MAC Address or NAN ID
    val port: Int = 53535
)

// Data Models
data class WiFiCapability(
    val wifiDirectSupported: Boolean,
    val nanSupported: Boolean,
    val hotspotSupported: Boolean,
    val sameLanSupported: Boolean,
    val maxSupportedBandwidthMbps: Float
)

data class WiFiPolicyState(
    val isEligible: Boolean,
    val batteryLevel: Int, // 0 - 100
    val isCharging: Boolean,
    val thermalState: ThermalState,
    val isBackground: Boolean,
    val isLowPowerMode: Boolean,
    val userWiFiPolicy: UserWiFiPolicy,
    val hasActiveSOS: Boolean
)

data class WiFiPeer(
    val devicePublicKeyId: String, // SHA-256 hash of device public key
    val userPublicKeyId: String?,  // Optional user identifier mapping
    var trustScore: Float,         // 0.0 to 1.0
    var lastSeenTimestamp: Long,
    var failureCount: Int,
    var isBlocked: Boolean,
    val endpoints: MutableList<WiFiEndpoint>,
    var rssi: Int = -50,
    var congestionState: CongestionState = CongestionState.GREEN,
    var queuePressure: Float = 0.0f,
    var acceptingBulk: Boolean = true,
    var cooldownUntil: Long = 0L
)

data class WiFiPeerSighting(
    val sourceMode: DiscoveryType,
    val endpoint: WiFiEndpoint,
    val devicePublicKeyId: String,
    val sightingTimestamp: Long,
    val rssi: Int
)

data class WiFiLinkState(
    val peerId: String,
    val rttMs: Long,
    val packetLossRate: Float,
    val txPower: Int,
    val currentBandwidthBytesPerSec: Long
)

data class WiFiConnectionSession(
    val sessionId: String,
    val peerId: String,
    val socketHandle: Socket?,
    var securitySession: WiFiSecuritySession?,
    val connectionTimestamp: Long,
    var lastActivityTimestamp: Long,
    var activeTransferCount: Int,
    val isInitiator: Boolean,
    var remoteEphemeralKey: ByteArray? = null,
    var peerPublicKeyId: String? = null
)

data class WiFiCluster(
    val clusterId: String,
    val leaderId: String,
    val members: List<WiFiClusterMember>,
    val creationTimestamp: Long,
    val syncSlotEpoch: Long
)

data class WiFiClusterMember(
    val peerId: String,
    val joinTimestamp: Long,
    val rssiToLeader: Int,
    val assignedSyncSlot: Int
)

data class WiFiLeaderCandidate(
    val peerId: String,
    val leaderScore: Float,
    val isCharging: Boolean,
    val hasGateway: Boolean
)

data class WiFiBackpressureState(
    val nodeId: String,
    val congestionState: CongestionState,
    val queuePressure: Float,
    val acceptingSos: Boolean,
    val acceptingControl: Boolean,
    val acceptingDirectMessages: Boolean,
    val acceptingGroupMessages: Boolean,
    val acceptingBulk: Boolean,
    val recommendedRetryAfterMs: Long,
    val maxIncomingSessions: Int,
    val currentIncomingSessions: Int
)

data class WiFiCongestionMetrics(
    val neighborCount: Int,
    val activeConnections: Int,
    val queueSize: Int,
    val duplicateRate: Float, // duplicate receive rate
    val retryRate: Float,
    val latencyIncreaseMs: Long
)

data class WiFiCongestionState(
    val state: CongestionState,
    val currentScore: Float,
    val lastEvaluationTime: Long
)

enum class WiFiOperatingMode { IDLE, ACTIVE, SOS, CHARGING, LOW_POWER, CONGESTED }

data class WiFiMessageSummary(
    val nodeId: String,
    val summaryVersion: Int,
    val bloomFilterBits: ByteArray,
    val highestPriorityWaiting: Int,
    val activeSosCount: Int = 0,
    val bundleCount: Int = 0,
    val gatewayAvailable: Boolean,
    val congestionState: CongestionState = CongestionState.GREEN,
    val acceptingBulk: Boolean = true
)

data class WiFiRetryRecord(
    val peerId: String,
    val failureCount: Int,
    val nextRetryAt: Long,
    val maxDelayMs: Long = 600_000L  // 10 minutes max
) {
    companion object {
        private const val BASE_DELAY_MS = 5_000L
        private const val MAX_DELAY_MS  = 600_000L

        fun computeNextDelay(failureCount: Int, jitterMs: Long = 2_000L): Long {
            val exp = Math.min(failureCount, 10) // cap exponent at 10
            val base = BASE_DELAY_MS * (1L shl exp)
            val capped = Math.min(base, MAX_DELAY_MS)
            val jitter = (Math.random() * jitterMs).toLong()
            return capped + jitter
        }
    }
}

data class WiFiBundleManifest(
    val bundleId: String,
    val messageId: String,
    val totalSizeBytes: Long,
    val chunkSizeValue: Int,
    val chunkCount: Int,
    val bundleHash: String,
    val priority: Int,
    val expiresAt: Long
)

data class WiFiChunk(
    val bundleId: String,
    val chunkIndex: Int,
    val payload: ByteArray,
    val chunkHash: String
)

data class WiFiTransferState(
    val bundleId: String,
    val peerId: String,
    val receivedChunksMask: BitSet,
    val isOutgoing: Boolean,
    var lastChunkTimestamp: Long
)

data class WiFiQueueState(
    val queuePressures: Map<Int, Float>,
    val totalBytesQueued: Long,
    val maxAllowedBytes: Long
)

data class WiFiRelayCandidate(
    val peerId: String,
    val relayScore: Float,
    val linkQuality: Float
)

data class WiFiFailureRecord(
    val peerId: String,
    val failureReason: WiFiFailureReason,
    val timestamp: Long
)

data class WiFiCooldownRecord(
    val peerId: String,
    val cooldownUntil: Long,
    val currentBackoffExponent: Int
)

data class WiFiAuditLogEntry(
    val timestamp: Long,
    val eventType: String,
    val severity: String, // INFO, WARNING, ERROR
    val message: String
)

data class WiFiSecuritySession(
    val sessionId: String,
    val sharedSecret: ByteArray,
    val encryptKey: ByteArray,
    val decryptKey: ByteArray,
    var nonce: Long = 0L
)

data class WiFiSyncSlot(
    val slotIndex: Int,
    val startTimeOffsetMs: Int,
    val durationMs: Int
)

data class WiFiGatewayAnnouncement(
    val gatewayNodeId: String,
    val rttToGateway: Int,
    val gatewayLoadScore: Float
)

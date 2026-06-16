package com.wifidirect.mesh

import com.wifidirect.mesh.ack.WiFiAckManager
import com.wifidirect.mesh.audit.WiFiAuditEventType
import com.wifidirect.mesh.audit.WiFiAuditLogger
import com.wifidirect.mesh.audit.WiFiAuditSeverity
import com.wifidirect.mesh.broadcast.WiFiBroadcastController
import com.wifidirect.mesh.cluster.WiFiClusterManager
import com.wifidirect.mesh.cluster.WiFiLeaderElectionEngine
import com.wifidirect.mesh.connection.WiFiConnectionManager
import com.wifidirect.mesh.connection.WiFiPeerTable
import com.wifidirect.mesh.discovery.WiFiDiscoveryManager
import com.wifidirect.mesh.driver.SameLanTransportDriver
import com.wifidirect.mesh.fallback.WiFiFallbackController
import com.wifidirect.mesh.models.*
import com.wifidirect.mesh.policy.WiFiCapabilityManager
import com.wifidirect.mesh.policy.WiFiPolicyEngine
import com.wifidirect.mesh.security.CryptoUtils
import com.wifidirect.mesh.security.WiFiSecurityManager
import com.wifidirect.mesh.sync.*
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class WiFiMeshModuleImpl(
    val tcpPort: Int = 53535,
    val discoveryPort: Int = 53530
) : WiFiMeshModule {

    // ---- Sub-components ----

    val auditLogger = WiFiAuditLogger(maxEntries = 2000)
    val capabilityManager = WiFiCapabilityManager()
    val policyEngine = WiFiPolicyEngine(capabilityManager)
    val peerTable = WiFiPeerTable()
    val securityManager = WiFiSecurityManager()
    val fallbackController = WiFiFallbackController()
    val congestionManager = WiFiCongestionManager()
    val scheduler = WiFiScheduler(maxTotalMessages = 500)
    val syncManager = WiFiSyncManager(localNodeId = securityManager.longTermPublicKeyId)
    val chunkEngine = WiFiChunkTransferEngine()
    val broadcastController = WiFiBroadcastController()
    val ackManager = WiFiAckManager(localNodeId = securityManager.longTermPublicKeyId)
    val leaderElectionEngine = WiFiLeaderElectionEngine()

    val connectionManager: WiFiConnectionManager = WiFiConnectionManager(peerTable, securityManager) { session ->
        transferManager.startListeningToSession(session)
    }

    val transferManager: WiFiTransferManager = WiFiTransferManager(connectionManager, securityManager) { peerId, payload ->
        handleIncomingPayload(peerId, payload)
    }

    val discoveryManager = WiFiDiscoveryManager(
        peerTable, securityManager.longTermPublicKeyId, tcpPort, discoveryPort,
        isServerReady = { connectionManager.isServerReady }
    )
    val sameLanDriver = SameLanTransportDriver(discoveryManager, connectionManager, transferManager)

    val clusterManager = WiFiClusterManager(
        localNodeId     = securityManager.longTermPublicKeyId,
        electionEngine  = leaderElectionEngine,
        auditLogger     = auditLogger
    ).also { cm ->
        cm.onLeaderElected = { leaderId ->
            auditLogger.info(WiFiAuditEventType.LEADER_ELECTED, "Leader elected: $leaderId")
        }
        cm.onRequestReelection = {
            auditLogger.warn(WiFiAuditEventType.LEADER_TIMEOUT, "Re-election triggered")
            currentCluster?.let { cluster ->
                val candidates = peerTable.getAll().map { peer ->
                    WiFiLeaderCandidate(peer.devicePublicKeyId, peer.trustScore, false, false)
                }
                electClusterLeader(candidates)
            }
        }
    }

    // ---- State ----

    @Volatile var onPayloadReceived: ((peerId: String, payload: ByteArray) -> Unit)? = null

    private var currentCongestionState = WiFiCongestionState(CongestionState.GREEN, 0.0f, System.currentTimeMillis())
    private val seenMessageCache = ConcurrentHashMap.newKeySet<String>()
    private val scheduledRebroadcasts = ConcurrentHashMap<String, Thread>()
    private val duplicateCountMap = ConcurrentHashMap<String, Int>()
    private val retryRecords = ConcurrentHashMap<String, WiFiRetryRecord>()

    // Cluster state
    private var currentCluster: WiFiCluster? = null
    private var isClusterLeader = false

    // SOS timeout for reporting
    private var lastSOSAttemptTime: Long = 0L
    private val SOS_TIMEOUT_MS = 8_000L

    // Maintenance executor
    private val maintenanceExecutor = Executors.newSingleThreadScheduledExecutor()

    init {
        // Wire congestion state changes to discovery interval updates
        congestionManager.onStateChange = { state ->
            val interval = congestionManager.recommendedDiscoveryIntervalMs()
            if (interval < Long.MAX_VALUE) {
                discoveryManager.setDiscoveryInterval(interval)
            }
            auditLogger.info(WiFiAuditEventType.CONGESTION_UPDATE,
                "Congestion changed to ${state.state} (score=${state.currentScore})")
        }

        // Schedule periodic maintenance
        maintenanceExecutor.scheduleAtFixedRate({
            peerTable.cleanExpiredPeers()
            scheduler.pruneExpired()
            chunkEngine.evictExpired()
        }, 60, 60, TimeUnit.SECONDS)
    }

    // ================================================================
    // WiFiMeshModule Implementation
    // ================================================================

    override fun evaluateWiFiEligibility(context: SystemContext): WiFiPolicyState {
        return policyEngine.evaluateWiFiEligibility(
            batteryLevel = context.getBatteryLevel(),
            isCharging   = context.isDeviceCharging(),
            tempCelsius  = context.getDeviceTemperatureCelsius(),
            userPolicy   = context.getUserWiFiPolicy(),
            hasActiveSOS = context.hasActiveSOS(),
            isBackground = context.isBackground()
        )
    }

    override fun startWiFiDiscovery(mode: DiscoveryMode) {
        auditLogger.info(WiFiAuditEventType.DISCOVERY_START, "Discovery started in mode $mode")
        connectionManager.startServer(tcpPort)
        sameLanDriver.startDiscovery(mode)
    }

    override fun stopWiFiDiscovery(reason: String) {
        auditLogger.info(WiFiAuditEventType.DISCOVERY_STOP, "Discovery stopped: $reason")
        sameLanDriver.stopDiscovery()
    }

    override fun handlePeerSighting(sighting: WiFiPeerSighting) {
        peerTable.mergePeerSighting(sighting)
        auditLogger.debug(WiFiAuditEventType.PEER_SIGHTED,
            "Peer ${sighting.devicePublicKeyId} sighted via ${sighting.sourceMode}")
    }

    override fun rankWiFiPeersForConnection(peers: List<WiFiPeer>, context: SystemContext): List<WiFiRelayCandidate> {
        return peers
            .filter { !it.isBlocked && it.cooldownUntil <= System.currentTimeMillis() }
            .map { peer ->
                var score = 0.0f

                // Link quality from RSSI (-100 to -30 dBm → 0.0 to 1.0)
                val linkQuality = ((peer.rssi + 100).coerceIn(0, 70) / 70.0f)
                score += 0.35f * linkQuality

                // Congestion penalty
                val congestionPenalty = when (peer.congestionState) {
                    CongestionState.GREEN  -> 0.00f
                    CongestionState.YELLOW -> 0.15f
                    CongestionState.ORANGE -> 0.45f
                    CongestionState.RED    -> 0.90f
                }
                score -= 0.30f * congestionPenalty

                // Trust score
                score += 0.20f * peer.trustScore

                // Queue pressure penalty
                score -= 0.10f * peer.queuePressure

                // Failure penalty (exponential decay)
                val failPenalty = (1.0f - Math.pow(0.7, peer.failureCount.toDouble()).toFloat()).coerceIn(0f, 1f)
                score -= 0.05f * failPenalty

                WiFiRelayCandidate(peer.devicePublicKeyId, score.coerceIn(0.0f, 1.0f), linkQuality)
            }
            .sortedByDescending { it.relayScore }
    }

    override fun connectToWiFiPeer(peerId: String): WiFiConnectionSession? {
        val existingSession = connectionManager.getSession(peerId)
        if (existingSession != null && existingSession.socketHandle?.isConnected == true && !existingSession.socketHandle.isClosed) {
            return existingSession
        }

        val peer = peerTable.get(peerId) ?: run {
            fallbackController.recordFailure(WiFiFailureReason.NO_PEERS_FOUND)
            return null
        }

        // Check peer cooldown
        if (peer.cooldownUntil > System.currentTimeMillis()) {
            fallbackController.recordFailure(WiFiFailureReason.PEER_COOLDOWN_ACTIVE)
            auditLogger.warn(WiFiAuditEventType.PEER_COOLDOWN, "Peer $peerId in cooldown until ${peer.cooldownUntil}")
            return null
        }

        val endpoint = peer.endpoints.firstOrNull { it.type == DiscoveryType.SAME_LAN }
            ?: peer.endpoints.firstOrNull()
            ?: run {
                fallbackController.recordFailure(WiFiFailureReason.CONNECTION_FAILED)
                return null
            }

        auditLogger.info(WiFiAuditEventType.CONNECT_ATTEMPT, "Connecting to $peerId via ${endpoint.type}")

        val connected = sameLanDriver.connectToPeer(peerId, endpoint)
        if (connected) {
            auditLogger.info(WiFiAuditEventType.CONNECT_SUCCESS, "Connected to $peerId")
            fallbackController.recordRecovery()
            return connectionManager.getSession(peerId)
        } else {
            handleWiFiFailure(peerId, WiFiFailureReason.CONNECTION_FAILED)
            return null
        }
    }

    override fun createWiFiCluster() {
        currentCluster = clusterManager.createCluster()
        isClusterLeader = true
    }

    override fun joinWiFiCluster(cluster: WiFiCluster) {
        isClusterLeader = false
        currentCluster = cluster
        clusterManager.joinCluster(cluster)
    }

    override fun leaveWiFiCluster(reason: String) {
        currentCluster = null
        isClusterLeader = false
        clusterManager.leaveCluster(reason)
    }

    override fun electClusterLeader(candidates: List<WiFiLeaderCandidate>): String {
        if (candidates.isEmpty()) return securityManager.longTermPublicKeyId
        val winner = leaderElectionEngine.electLeaderFromCandidates(candidates)
        auditLogger.info(WiFiAuditEventType.LEADER_ELECTED, "Leader elected: $winner")
        return winner
    }

    override fun calculateWiFiLeaderScore(candidate: WiFiLeaderCandidate): Float {
        // Map the simplified WiFiLeaderCandidate to the full LeaderInput
        val input = WiFiLeaderElectionEngine.LeaderInput(
            peerId                  = candidate.peerId,
            batteryFraction         = 0.7f,  // Default for external candidates without full context
            isCharging              = candidate.isCharging,
            connectivityStability   = 0.8f,
            deviceCapabilityScore   = 0.7f,
            hasGateway              = candidate.hasGateway,
            mobilityScore           = 0.9f,
            historicalUptime        = 0.8f,
            congestionScore         = 0.2f,
            trustScore              = candidate.leaderScore,
            thermalState            = ThermalState.NORMAL,
            recentFailureFraction   = 0.0f,
            storageFraction         = 0.8f
        )
        return leaderElectionEngine.calculateLeaderScore(input)
    }

    override fun performSecureHandshake(session: WiFiConnectionSession): Boolean {
        val ok = session.securitySession != null
        if (ok) auditLogger.info(WiFiAuditEventType.HANDSHAKE_OK, "Handshake OK with ${session.peerId}")
        else    auditLogger.warn(WiFiAuditEventType.HANDSHAKE_FAIL, "Handshake FAILED with ${session.peerId}")
        return ok
    }

    override fun exchangeCapabilities(session: WiFiConnectionSession) {
        val caps = capabilityManager.getCapabilities()
        val capStr = buildString {
            append("CAP:")
            if (caps.wifiDirectSupported) append("wifi_direct,")
            if (caps.sameLanSupported)    append("same_lan,")
            if (caps.hotspotSupported)    append("hotspot,")
            if (caps.nanSupported)        append("nan")
        }.trimEnd(',')
        transferManager.sendPayload(session.peerId, capStr.toByteArray())
    }

    override fun exchangeBackpressure(session: WiFiConnectionSession) {
        val bpState = advertiseBackpressure()
        val bpBytes = "BP:${bpState.congestionState.name},${bpState.queuePressure},${bpState.acceptingBulk}".toByteArray()
        transferManager.sendPayload(session.peerId, bpBytes)
        auditLogger.debug(WiFiAuditEventType.BACKPRESSURE_SENT,
            "Backpressure sent to ${session.peerId}: ${bpState.congestionState}")
    }

    override fun exchangeMessageSummary(session: WiFiConnectionSession): WiFiMessageSummary {
        val summary = syncManager.buildLocalSummary(gatewayAvailable = false)
        auditLogger.debug(WiFiAuditEventType.SYNC_SUMMARY_SENT,
            "Summary sent to ${session.peerId}, ${syncManager.localMessageCount()} messages")
        return summary
    }

    override fun selectBundlesForTransfer(peerId: String, remoteSummary: WiFiMessageSummary): List<String> {
        val missing = syncManager.computeMissingForPeer(remoteSummary)
        return missing.mapNotNull { it.bundleId ?: it.messageId }
    }

    override fun scheduleWiFiTransfer(peerId: String, bundleId: String) {
        auditLogger.info(WiFiAuditEventType.TRANSFER_START, "Scheduled transfer of $bundleId → $peerId")
    }

    override fun sendBundleChunk(peerId: String, chunk: WiFiChunk) {
        val payload = "CHUNK:${chunk.bundleId}:${chunk.chunkIndex}:${chunk.chunkHash}:${CryptoUtils.bytesToHex(chunk.payload)}".toByteArray()
        transferManager.sendPayload(peerId, payload)
    }

    override fun receiveBundleChunk(peerId: String, chunk: WiFiChunk) {
        val accepted = chunkEngine.receiveChunk(chunk)
        if (!accepted) {
            auditLogger.error(WiFiAuditEventType.CHUNK_CORRUPT,
                "Corrupted chunk ${chunk.chunkIndex} from $peerId — bundle ${chunk.bundleId}")
            return
        }
        auditLogger.debug(WiFiAuditEventType.CHUNK_RECEIVED,
            "Chunk ${chunk.chunkIndex} OK for ${chunk.bundleId}")

        // Reassemble if complete
        val full = chunkEngine.tryReassemble(chunk.bundleId)
        if (full != null) {
            auditLogger.info(WiFiAuditEventType.TRANSFER_COMPLETE,
                "Bundle ${chunk.bundleId} fully received (${full.size} bytes)")
        }
    }

    override fun resumeBundleTransfer(peerId: String, bundleId: String) {
        chunkEngine.resumeTransfer(bundleId)
        val nextChunk = chunkEngine.nextChunkToSend(bundleId)
        if (nextChunk != null) {
            sendBundleChunk(peerId, nextChunk)
            auditLogger.info(WiFiAuditEventType.CHUNK_RESUME,
                "Resumed transfer of $bundleId, next chunk ${nextChunk.chunkIndex}")
        }
    }

    override fun sendWiFiAck(peerId: String, ack: WiFiClusterACKSummary) {
        // Only send if not rate-limited
        val canSend = ack.messageIds.any { ackManager.canSendAck(it) }
        if (canSend) {
            transferManager.sendPayload(peerId, ack.serialize())
            auditLogger.debug(WiFiAuditEventType.ACK_SENT, "ACK sent to $peerId for ${ack.messageIds.size} messages")
        }
    }

    override fun aggregateAck(messageId: String): WiFiClusterACKSummary {
        val suppressed = ackManager.isAckSuppressed(messageId)
        if (suppressed) {
            // Return empty ACK (suppressed)
            return WiFiClusterACKSummary(securityManager.longTermPublicKeyId, emptyList(), System.currentTimeMillis())
        }
        val summary = ackManager.buildAggregatedAck(listOf(messageId))
            ?: WiFiClusterACKSummary(securityManager.longTermPublicKeyId, listOf(messageId), System.currentTimeMillis())
        auditLogger.debug(WiFiAuditEventType.ACK_AGGREGATED, "Aggregated ACK for $messageId")
        return summary
    }

    override fun calculateCongestionScore(metrics: WiFiCongestionMetrics): Float {
        return congestionManager.calculateCongestionScore(metrics)
    }

    override fun updateCongestionState(score: Float): WiFiCongestionState {
        val state = congestionManager.updateCongestionState(score)
        currentCongestionState = state
        applyCongestionPolicy(state)
        return state
    }

    override fun applyCongestionPolicy(state: WiFiCongestionState) {
        val interval = congestionManager.recommendedDiscoveryIntervalMs()
        if (interval < Long.MAX_VALUE) {
            discoveryManager.setDiscoveryInterval(interval)
        }
        // Pause bulk chunks on ORANGE or RED
        if (state.state == CongestionState.ORANGE || state.state == CongestionState.RED) {
            chunkEngine.pauseTransfersByPriority(QueuePriority.BULK_SYNC.value)
        }
    }

    override fun shouldRebroadcast(message: Message, context: SystemContext): Boolean {
        val neighborCount = peerTable.getAll().size
        return broadcastController.shouldRebroadcast(message, currentCongestionState.state, neighborCount)
    }

    override fun calculateForwardProbability(message: Message, context: SystemContext): Float {
        val neighborCount = peerTable.getAll().size
        return broadcastController.calculateForwardProbability(message, currentCongestionState.state, neighborCount)
    }

    override fun scheduleRebroadcast(message: Message) {
        val rankedPeers = rankWiFiPeersForConnection(peerTable.getAll(), SystemContext(80, false, 30f, UserWiFiPolicy.ALLOW_ALL, false, false))
        broadcastController.scheduleRebroadcast(message, currentCongestionState.state, rankedPeers,
            sender = { peerId: String, msg: Message ->
                val session = connectionManager.getSession(peerId) ?: connectToWiFiPeer(peerId)
                if (session != null) {
                    val success = transferManager.sendPayload(peerId, msg.serialize())
                    auditLogger.debug(WiFiAuditEventType.TRANSFER_START, "Relayed message ${msg.id} to $peerId. success=$success")
                } else {
                    auditLogger.warn(WiFiAuditEventType.PEER_FAILURE, "Failed to connect to $peerId to relay message ${msg.id}")
                }
            }
        )
    }

    override fun cancelRebroadcast(messageId: String) {
        broadcastController.cancelRebroadcast(messageId)
        scheduledRebroadcasts.remove(messageId)?.interrupt()
    }

    override fun updateWiFiLinkQuality(peerId: String, metrics: WiFiLinkState) {
        auditLogger.debug(WiFiAuditEventType.CONNECT_SUCCESS,
            "Link $peerId RTT=${metrics.rttMs}ms loss=${metrics.packetLossRate}")
    }

    override fun advertiseBackpressure(): WiFiBackpressureState {
        val qPressure = scheduler.queuePressure()
        return congestionManager.buildBackpressureState(
            nodeId           = securityManager.longTermPublicKeyId,
            queuePressure    = qPressure,
            maxIncoming      = 2,
            currentIncoming  = 0
        )
    }

    override fun handleBackpressureFromPeer(peerId: String, state: WiFiBackpressureState) {
        peerTable.get(peerId)?.let { peer ->
            peer.congestionState = state.congestionState
            peer.queuePressure   = state.queuePressure
            peer.acceptingBulk   = state.acceptingBulk
            if (!state.acceptingBulk) {
                peer.cooldownUntil = System.currentTimeMillis() + state.recommendedRetryAfterMs
            }
        }
        auditLogger.debug(WiFiAuditEventType.BACKPRESSURE_RECV,
            "Backpressure from $peerId: ${state.congestionState}")
    }

    override fun applyWiFiCooldown(peerId: String, reason: WiFiFailureReason) {
        val record = retryRecords[peerId]
        val failureCount = (record?.failureCount ?: 0) + 1
        val delay = WiFiRetryRecord.computeNextDelay(failureCount)
        val nextRetry = System.currentTimeMillis() + delay

        retryRecords[peerId] = WiFiRetryRecord(peerId, failureCount, nextRetry)
        peerTable.applyCooldown(peerId, delay)

        auditLogger.warn(WiFiAuditEventType.PEER_COOLDOWN,
            "Cooldown applied to $peerId (${failureCount} failures), retry in ${delay}ms")
    }

    override fun handleWiFiFailure(peerId: String, reason: WiFiFailureReason) {
        fallbackController.recordFailure(reason)
        auditLogger.warn(WiFiAuditEventType.PEER_FAILURE, "Peer $peerId failure: $reason")
        applyWiFiCooldown(peerId, reason)
    }

    override fun reportWiFiStatusToRouter(): WiFiFailureReason? {
        return fallbackController.reportToRouter()
    }

    override fun logWiFiAuditEvent(event: WiFiAuditLogEntry) {
        auditLogger.log(event.eventType, event.severity, event.message)
    }

    // ================================================================
    // Public accessors for testing and integration
    // ================================================================

    fun getAuditLogs(): List<WiFiAuditLogEntry> = auditLogger.export()

    fun shouldFallbackToBLE(): Boolean = fallbackController.shouldFallbackToBLE()

    fun shouldEscalateToSMS(): Boolean = fallbackController.shouldEscalateToSMS()

    /**
     * Attempt SOS delivery over Wi-Fi with a timeout.
     * Returns true if SOS was successfully dispatched to at least one peer.
     * On timeout, records failure for fallback routing.
     */
    fun attemptSOSOverWifi(sosMessage: Message): Boolean {
        lastSOSAttemptTime = System.currentTimeMillis()
        auditLogger.info(WiFiAuditEventType.SOS_ATTEMPT, "SOS Wi-Fi attempt started")

        val peers = peerTable.getActivePeersSortedByScore()
        if (peers.isEmpty()) {
            fallbackController.recordFailure(WiFiFailureReason.NO_PEERS_FOUND)
            auditLogger.warn(WiFiAuditEventType.SOS_TIMEOUT, "SOS: no peers found")
            return false
        }

        // Pause any bulk chunk transfers
        chunkEngine.pauseTransfersByPriority(QueuePriority.BULK_SYNC.value)

        var sent = false
        for (peer in peers.take(3)) {  // Try up to 3 peers
            val session = connectionManager.getSession(peer.devicePublicKeyId)
                ?: connectToWiFiPeer(peer.devicePublicKeyId)

            if (session != null) {
                val ok = transferManager.sendPayload(peer.devicePublicKeyId, sosMessage.serialize())
                if (ok) {
                    sent = true
                    auditLogger.info(WiFiAuditEventType.SOS_SUCCESS,
                        "SOS sent to ${peer.devicePublicKeyId}")
                    fallbackController.recordSOSSuccess()
                    break
                }
            }

            // Check timeout
            if (System.currentTimeMillis() - lastSOSAttemptTime > SOS_TIMEOUT_MS) {
                fallbackController.recordFailure(WiFiFailureReason.TRANSFER_TIMEOUT)
                auditLogger.warn(WiFiAuditEventType.SOS_TIMEOUT, "SOS Wi-Fi attempt timed out")
                return false
            }
        }
        return sent
    }

    fun shutdown() {
        maintenanceExecutor.shutdownNow()
        clusterManager.shutdown()
        broadcastController.shutdown()
        connectionManager.stopServer()
        sameLanDriver.stopDiscovery()
    }

    // ================================================================
    // Internal payload dispatch
    // ================================================================

    private fun handleIncomingPayload(peerId: String, payload: ByteArray) {
        onPayloadReceived?.invoke(peerId, payload)
        val str = try { String(payload) } catch (e: Exception) { "" }
        when {
            str.startsWith("CHAT:") -> {
                auditLogger.info(WiFiAuditEventType.UNKNOWN_PAYLOAD, "Chat message payload processed from $peerId")
            }
            str.startsWith("CAP:") -> {
                auditLogger.debug(WiFiAuditEventType.CONNECT_SUCCESS, "Capabilities from $peerId: $str")
            }
            str.startsWith("BP:") -> {
                auditLogger.debug(WiFiAuditEventType.BACKPRESSURE_RECV, "Backpressure from $peerId: $str")
            }
            str.startsWith("CHUNK:") -> {
                auditLogger.debug(WiFiAuditEventType.CHUNK_RECEIVED, "Chunk payload from $peerId")
            }
            else -> {
                try {
                    val msg = Message.deserialize(payload)
                    auditLogger.debug(WiFiAuditEventType.CHUNK_RECEIVED,
                        "Message ${msg.id} priority ${msg.priority} from $peerId")
                    // Register in sync manager
                    syncManager.registerMessage(
                        WiFiSyncManager.MessageMeta(msg.id, null, msg.priority.value, msg.expiryTimestamp)
                    )
                    // Enqueue for relay if needed
                    if (shouldRebroadcast(msg, SystemContext(80, false, 30f, UserWiFiPolicy.ALLOW_ALL, false, false))) {
                        scheduleRebroadcast(msg)
                    }
                    // Record ACK
                    ackManager.recordReceipt(msg.id, msg.priority, peerId)
                } catch (e: Exception) {
                    try {
                        val ack = WiFiClusterACKSummary.deserialize(payload)
                        val suppressed = ackManager.processAckSummary(ack)
                        auditLogger.debug(WiFiAuditEventType.ACK_RECEIVED,
                            "ACK received from $peerId, suppressed ${suppressed.size} msgs")
                    } catch (ex: Exception) {
                        auditLogger.warn(WiFiAuditEventType.UNKNOWN_PAYLOAD,
                            "Unknown payload from $peerId (${payload.size} bytes)")
                    }
                }
            }
        }
    }
}

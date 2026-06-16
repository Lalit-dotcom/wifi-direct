package com.wifidirect.mesh.cluster

import com.wifidirect.mesh.audit.WiFiAuditLogger
import com.wifidirect.mesh.audit.WiFiAuditEventType
import com.wifidirect.mesh.audit.WiFiAuditSeverity
import com.wifidirect.mesh.models.*
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * WiFiClusterManager — Full cluster lifecycle management.
 *
 * Responsibilities (spec §CLUSTER MANAGEMENT):
 *  - Create / join / leave cluster
 *  - Heartbeat (leader sends, members receive)
 *  - Leader timeout detection → emergency re-election
 *  - Sync slot assignment (deterministic, hash-based)
 *  - Cluster split when too large / overloaded
 *  - Cluster merge summary exchange
 *  - Gateway announcement propagation
 *  - Backpressure announcement
 *
 * Heartbeat interval: 15 seconds (configurable)
 * Leader timeout:     60 seconds (4× missed heartbeats)
 *
 * Sync slot model:
 *   Deterministic slots = hash(peerId) mod slotCount
 *   SOS/control bypass all slots.
 *   Bulk must respect slots.
 */
class WiFiClusterManager(
    private val localNodeId: String,
    private val electionEngine: WiFiLeaderElectionEngine,
    private val auditLogger: WiFiAuditLogger,
    private val heartbeatIntervalMs: Long = 15_000L,
    private val leaderTimeoutMs: Long = 60_000L,
    private val maxClusterSize: Int = 30,
    private val syncSlotCount: Int = 8
) {
    // State
    private val currentCluster = AtomicReference<WiFiCluster?>(null)
    private val isLeader = AtomicBoolean(false)
    private val memberMap = ConcurrentHashMap<String, WiFiClusterMember>()
    private val lastLeaderHeartbeatMs = AtomicReference(System.currentTimeMillis())

    // Leader candidate tracking
    private val candidateScores = ConcurrentHashMap<String, Float>()

    // Scheduler for heartbeat
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var heartbeatFuture: ScheduledFuture<*>? = null

    // Callbacks
    var onLeaderElected: ((leaderId: String) -> Unit)? = null
    var onHeartbeatSend: ((heartbeat: WiFiClusterHeartbeat) -> Unit)? = null
    var onClusterSplit: ((subClusters: List<WiFiCluster>) -> Unit)? = null
    var onRequestReelection: (() -> Unit)? = null

    // Congestion state for inclusion in heartbeat
    var currentCongestionState: CongestionState = CongestionState.GREEN
    var gatewayAvailable: Boolean = false
    var queuePressure: Float = 0.0f
    var leaderBatteryClass: String = "medium"

    // ---- Cluster Lifecycle ----

    fun createCluster(): WiFiCluster {
        val clusterId = "cluster-${SecureRandom().nextInt(99999)}"
        val syncEpoch = System.currentTimeMillis() / heartbeatIntervalMs

        val cluster = WiFiCluster(
            clusterId         = clusterId,
            leaderId          = localNodeId,
            members           = emptyList(),
            creationTimestamp = System.currentTimeMillis(),
            syncSlotEpoch     = syncEpoch
        )
        currentCluster.set(cluster)
        isLeader.set(true)
        memberMap.clear()

        auditLogger.info(WiFiAuditEventType.CLUSTER_CREATE, "Created cluster $clusterId as leader")
        startHeartbeat()
        return cluster
    }

    fun joinCluster(cluster: WiFiCluster) {
        currentCluster.set(cluster)
        isLeader.set(cluster.leaderId == localNodeId)
        lastLeaderHeartbeatMs.set(System.currentTimeMillis())
        auditLogger.info(WiFiAuditEventType.CLUSTER_JOIN, "Joined cluster ${cluster.clusterId}, leader=${cluster.leaderId}")

        if (isLeader.get()) startHeartbeat()
        else startLeaderWatchdog()
    }

    fun leaveCluster(reason: String) {
        val cid = currentCluster.get()?.clusterId ?: "none"
        heartbeatFuture?.cancel(false)
        currentCluster.set(null)
        isLeader.set(false)
        memberMap.clear()
        auditLogger.info(WiFiAuditEventType.CLUSTER_LEAVE, "Left cluster $cid: $reason")
    }

    // ---- Member Management ----

    fun addMember(peerId: String, rssiToLeader: Int) {
        val slot = assignSyncSlot(peerId)
        val member = WiFiClusterMember(
            peerId          = peerId,
            joinTimestamp   = System.currentTimeMillis(),
            rssiToLeader    = rssiToLeader,
            assignedSyncSlot = slot
        )
        memberMap[peerId] = member
        auditLogger.debug(WiFiAuditEventType.CLUSTER_JOIN, "Member $peerId joined, slot=$slot")

        // Check if cluster is too large and needs splitting
        if (memberMap.size > maxClusterSize) {
            triggerClusterSplit()
        }
    }

    fun removeMember(peerId: String) {
        memberMap.remove(peerId)
    }

    fun getMemberList(): List<WiFiClusterMember> = memberMap.values.toList()

    // ---- Heartbeat ----

    private fun startHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = scheduler.scheduleAtFixedRate({
            sendHeartbeat()
        }, 0, heartbeatIntervalMs, TimeUnit.MILLISECONDS)
    }

    private fun sendHeartbeat() {
        val cluster = currentCluster.get() ?: return
        val heartbeat = WiFiClusterHeartbeat(
            clusterId          = cluster.clusterId,
            leaderId           = localNodeId,
            timestamp          = System.currentTimeMillis(),
            memberCount        = memberMap.size,
            congestionState    = currentCongestionState,
            gatewayAvailable   = gatewayAvailable,
            leaderBatteryClass = leaderBatteryClass,
            acceptingBulk      = currentCongestionState == CongestionState.GREEN,
            syncSlotEpoch      = cluster.syncSlotEpoch
        )
        auditLogger.debug(WiFiAuditEventType.CLUSTER_HEARTBEAT, "Heartbeat sent: ${cluster.clusterId}")
        onHeartbeatSend?.invoke(heartbeat)
    }

    /**
     * Called when a heartbeat is received from the leader.
     */
    fun receiveHeartbeat(heartbeat: WiFiClusterHeartbeat) {
        val cluster = currentCluster.get() ?: return
        if (heartbeat.clusterId != cluster.clusterId) return

        lastLeaderHeartbeatMs.set(System.currentTimeMillis())
        currentCongestionState = heartbeat.congestionState
        gatewayAvailable = heartbeat.gatewayAvailable
        auditLogger.debug(WiFiAuditEventType.CLUSTER_HEARTBEAT, "Heartbeat received from ${heartbeat.leaderId}")
    }

    /**
     * Leader watchdog: monitors for missed heartbeats.
     */
    private fun startLeaderWatchdog() {
        scheduler.scheduleAtFixedRate({
            val elapsed = System.currentTimeMillis() - lastLeaderHeartbeatMs.get()
            if (elapsed > leaderTimeoutMs) {
                auditLogger.warn(WiFiAuditEventType.LEADER_TIMEOUT,
                    "Leader heartbeat timeout after ${elapsed}ms — triggering re-election")
                heartbeatFuture?.cancel(false)
                onRequestReelection?.invoke()
            }
        }, leaderTimeoutMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS)
    }

    // ---- Leader Election ----

    fun registerCandidateScore(peerId: String, score: Float) {
        candidateScores[peerId] = score
    }

    fun runElection(): String {
        val candidates = candidateScores.map { (id, score) ->
            WiFiLeaderCandidate(
                peerId      = id,
                leaderScore = score,
                isCharging  = false,   // Simplified: full info in LeaderInput variant
                hasGateway  = false
            )
        }
        val winner = if (candidates.isEmpty()) localNodeId
                     else electionEngine.electLeaderFromCandidates(candidates)

        auditLogger.info(WiFiAuditEventType.LEADER_ELECTED, "New leader elected: $winner")
        onLeaderElected?.invoke(winner)

        if (winner == localNodeId) {
            isLeader.set(true)
            startHeartbeat()
        }
        candidateScores.clear()
        return winner
    }

    // ---- Sync Slots ----

    /**
     * Assigns a deterministic sync slot using hash(peerId) mod slotCount.
     * SOS/control messages bypass sync slots.
     */
    fun assignSyncSlot(peerId: String): Int {
        return Math.abs(peerId.hashCode()) % syncSlotCount
    }

    /**
     * Returns true if this node is in its active sync slot right now.
     * Slot duration = heartbeatIntervalMs / syncSlotCount.
     */
    fun isInSyncSlot(peerId: String): Boolean {
        val slotDuration = heartbeatIntervalMs / syncSlotCount
        val currentSlot = (System.currentTimeMillis() / slotDuration) % syncSlotCount
        return assignSyncSlot(peerId).toLong() == currentSlot
    }

    // ---- Cluster Split ----

    /**
     * Split cluster into two sub-clusters when it becomes too large or overloaded.
     */
    private fun triggerClusterSplit() {
        auditLogger.warn(WiFiAuditEventType.CLUSTER_SPLIT,
            "Cluster ${currentCluster.get()?.clusterId} splitting (size=${memberMap.size})")

        val members = memberMap.values.toList()
        val half = members.size / 2

        val group1 = members.take(half)
        val group2 = members.drop(half)

        val epoch = System.currentTimeMillis() / heartbeatIntervalMs

        val cluster1 = WiFiCluster(
            clusterId         = "cluster-split-A-${SecureRandom().nextInt(9999)}",
            leaderId          = localNodeId,
            members           = group1,
            creationTimestamp = System.currentTimeMillis(),
            syncSlotEpoch     = epoch
        )
        val cluster2 = WiFiCluster(
            clusterId         = "cluster-split-B-${SecureRandom().nextInt(9999)}",
            leaderId          = group2.firstOrNull()?.peerId ?: localNodeId,
            members           = group2,
            creationTimestamp = System.currentTimeMillis(),
            syncSlotEpoch     = epoch
        )

        onClusterSplit?.invoke(listOf(cluster1, cluster2))
    }

    // ---- Cluster Merge ----

    /**
     * Evaluate whether merging with another cluster is safe.
     * Returns true only if combined size stays within limit and congestion is GREEN or YELLOW.
     */
    fun canMergeWith(otherCluster: WiFiCluster): Boolean {
        val combined = memberMap.size + otherCluster.members.size
        return combined <= maxClusterSize &&
               (currentCongestionState == CongestionState.GREEN ||
                currentCongestionState == CongestionState.YELLOW)
    }

    // ---- Accessors ----

    fun currentCluster(): WiFiCluster? = currentCluster.get()
    fun isCurrentlyLeader(): Boolean = isLeader.get()
    fun clusterSize(): Int = memberMap.size

    fun shutdown() {
        heartbeatFuture?.cancel(false)
        scheduler.shutdownNow()
    }
}

/**
 * WiFiClusterHeartbeat — Heartbeat message sent by cluster leader.
 * Matches spec example format.
 */
data class WiFiClusterHeartbeat(
    val clusterId: String,
    val leaderId: String,
    val timestamp: Long,
    val memberCount: Int,
    val congestionState: CongestionState,
    val gatewayAvailable: Boolean,
    val leaderBatteryClass: String,   // "low", "medium", "high"
    val acceptingBulk: Boolean,
    val syncSlotEpoch: Long
)

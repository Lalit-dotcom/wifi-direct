package com.wifidirect.mesh.sync

import com.wifidirect.mesh.models.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * WiFiCongestionManager — Full congestion detection, scoring, and policy enforcement.
 *
 * Congestion score formula (spec §CONGESTION CONTROL):
 *
 *   CongestionScore =
 *     w1 * QueuePressure
 *   + w2 * NeighborDensity
 *   + w3 * DuplicateRate
 *   + w4 * RetryRate
 *   + w5 * LatencyIncrease
 *   + w6 * TransferFailureRate
 *   + w7 * LeaderLoad
 *   + w8 * ChannelBusyTime
 *
 * Normalized 0.0–1.0.
 *
 * States:
 *   GREEN  [0.00–0.35)  — normal operation
 *   YELLOW [0.35–0.60)  — reduce discovery, batch messages
 *   ORANGE [0.60–0.85)  — emergency/direct only, pause bulk, reduce peers
 *   RED    [0.85–1.00]  — SOS/control only
 *
 * NeighborDensity uses a logistic (S-curve) to give diminishing returns:
 *   density = 1 / (1 + exp(-0.15 * (n - 15)))
 * This means 15 neighbors is the inflection point.
 */
class WiFiCongestionManager {

    // Weights (sum = 1.0)
    private val W1_QUEUE_PRESSURE       = 0.20f
    private val W2_NEIGHBOR_DENSITY     = 0.15f
    private val W3_DUPLICATE_RATE       = 0.15f
    private val W4_RETRY_RATE           = 0.15f
    private val W5_LATENCY_INCREASE     = 0.15f
    private val W6_TRANSFER_FAILURE     = 0.10f
    private val W7_LEADER_LOAD         = 0.05f
    private val W8_CHANNEL_BUSY        = 0.05f

    // Thresholds
    private val THRESHOLD_YELLOW = 0.35f
    private val THRESHOLD_ORANGE = 0.60f
    private val THRESHOLD_RED    = 0.85f

    // Discovery intervals per state (ms)
    val DISCOVERY_INTERVAL_GREEN  = 15_000L
    val DISCOVERY_INTERVAL_YELLOW = 45_000L
    val DISCOVERY_INTERVAL_ORANGE = 120_000L
    val DISCOVERY_INTERVAL_RED    = Long.MAX_VALUE   // Stop discovery in RED

    // Current state
    private val currentState = AtomicReference(
        WiFiCongestionState(CongestionState.GREEN, 0.0f, System.currentTimeMillis())
    )

    // Listener called whenever state changes
    var onStateChange: ((WiFiCongestionState) -> Unit)? = null

    /**
     * Calculate the congestion score from raw metrics.
     *
     * @param metrics   Raw observations from the node.
     * @param maxQueue  Maximum expected queue size for normalization (default 200).
     * @return          Normalized score in [0.0, 1.0].
     */
    fun calculateCongestionScore(
        metrics: WiFiCongestionMetrics,
        maxQueue: Int = 200,
        maxLatencyMs: Long = 2000L,
        leaderLoad: Float = 0.0f,
        channelBusyRatio: Float = 0.0f,
        transferFailureRate: Float = 0.0f
    ): Float {
        // W1: Queue pressure (normalized)
        val queuePressure = (metrics.queueSize.toFloat() / maxQueue.toFloat()).coerceIn(0f, 1f)

        // W2: Neighbor density — logistic / S-curve with inflection at 15 neighbors
        val density = (1.0 / (1.0 + Math.exp(-0.15 * (metrics.neighborCount - 15)))).toFloat()

        // W3: Duplicate receive rate (already 0–1)
        val duplicateRate = metrics.duplicateRate.coerceIn(0f, 1f)

        // W4: Retry rate (already 0–1)
        val retryRate = metrics.retryRate.coerceIn(0f, 1f)

        // W5: Latency increase (normalized vs max expected)
        val latencyNorm = (metrics.latencyIncreaseMs.toFloat() / maxLatencyMs.toFloat()).coerceIn(0f, 1f)

        // W6: Transfer failure rate (provided)
        val tfr = transferFailureRate.coerceIn(0f, 1f)

        // W7: Leader load (0–1, provided externally)
        val leaderLoadNorm = leaderLoad.coerceIn(0f, 1f)

        // W8: Channel busy time ratio (0–1, optional from platform)
        val channelBusy = channelBusyRatio.coerceIn(0f, 1f)

        val score = W1_QUEUE_PRESSURE   * queuePressure  +
                    W2_NEIGHBOR_DENSITY * density         +
                    W3_DUPLICATE_RATE   * duplicateRate   +
                    W4_RETRY_RATE       * retryRate       +
                    W5_LATENCY_INCREASE * latencyNorm     +
                    W6_TRANSFER_FAILURE * tfr             +
                    W7_LEADER_LOAD     * leaderLoadNorm   +
                    W8_CHANNEL_BUSY    * channelBusy

        return score.coerceIn(0f, 1f)
    }

    /**
     * Update the congestion state based on the computed score.
     * Fires onStateChange if the state level changes.
     */
    fun updateCongestionState(score: Float): WiFiCongestionState {
        val newLevel = when {
            score >= THRESHOLD_RED    -> CongestionState.RED
            score >= THRESHOLD_ORANGE -> CongestionState.ORANGE
            score >= THRESHOLD_YELLOW -> CongestionState.YELLOW
            else                      -> CongestionState.GREEN
        }
        val prev = currentState.get()
        val updated = WiFiCongestionState(newLevel, score, System.currentTimeMillis())
        currentState.set(updated)

        if (prev.state != newLevel) {
            onStateChange?.invoke(updated)
        }
        return updated
    }

    /** Convenience: run metrics through score + update in one call. */
    fun evaluate(
        metrics: WiFiCongestionMetrics,
        leaderLoad: Float = 0.0f,
        channelBusyRatio: Float = 0.0f,
        transferFailureRate: Float = 0.0f
    ): WiFiCongestionState {
        val score = calculateCongestionScore(metrics, leaderLoad = leaderLoad,
                        channelBusyRatio = channelBusyRatio,
                        transferFailureRate = transferFailureRate)
        return updateCongestionState(score)
    }

    fun currentState(): WiFiCongestionState = currentState.get()
    fun currentLevel(): CongestionState = currentState.get().state

    /**
     * Returns the discovery interval appropriate for current congestion state.
     */
    fun recommendedDiscoveryIntervalMs(): Long = when (currentLevel()) {
        CongestionState.GREEN  -> DISCOVERY_INTERVAL_GREEN
        CongestionState.YELLOW -> DISCOVERY_INTERVAL_YELLOW
        CongestionState.ORANGE -> DISCOVERY_INTERVAL_ORANGE
        CongestionState.RED    -> DISCOVERY_INTERVAL_RED
    }

    /**
     * Returns whether bulk transfer is allowed in the current state.
     */
    fun isBulkAllowed(): Boolean = currentLevel() == CongestionState.GREEN

    /**
     * Returns whether a given priority class is eligible to transfer
     * under the current congestion state.
     *
     * GREEN:  all allowed
     * YELLOW: all except heavy bulk reduced (still allowed, just lower rate)
     * ORANGE: priorities 0–5 (CONTROL → DIRECT_MESSAGE)
     * RED:    priorities 0–2 (CONTROL, SOS, SOS_ACK) only
     */
    fun isTrafficEligible(priority: QueuePriority): Boolean = when (currentLevel()) {
        CongestionState.GREEN  -> true
        CongestionState.YELLOW -> priority.value <= QueuePriority.ROUTING_SUMMARY.value
                                  || priority == QueuePriority.DIRECT_MESSAGE
                                  || priority == QueuePriority.GROUP_MESSAGE
        CongestionState.ORANGE -> priority.value <= QueuePriority.DIRECT_MESSAGE.value
        CongestionState.RED    -> priority.value <= QueuePriority.SOS_ACK.value
    }

    /**
     * Builds the backpressure state for advertising to peers.
     */
    fun buildBackpressureState(
        nodeId: String,
        queuePressure: Float,
        maxIncoming: Int,
        currentIncoming: Int
    ): WiFiBackpressureState {
        val level = currentLevel()
        return WiFiBackpressureState(
            nodeId                  = nodeId,
            congestionState         = level,
            queuePressure           = queuePressure,
            acceptingSos            = true,
            acceptingControl        = true,
            acceptingDirectMessages = level != CongestionState.RED,
            acceptingGroupMessages  = level == CongestionState.GREEN || level == CongestionState.YELLOW,
            acceptingBulk           = level == CongestionState.GREEN,
            recommendedRetryAfterMs = when (level) {
                CongestionState.GREEN  -> 5_000L
                CongestionState.YELLOW -> 15_000L
                CongestionState.ORANGE -> 30_000L
                CongestionState.RED    -> 60_000L
            },
            maxIncomingSessions     = maxIncoming,
            currentIncomingSessions = currentIncoming
        )
    }
}

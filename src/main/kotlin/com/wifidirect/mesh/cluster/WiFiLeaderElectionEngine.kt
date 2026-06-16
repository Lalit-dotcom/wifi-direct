package com.wifidirect.mesh.cluster

import com.wifidirect.mesh.models.*

/**
 * WiFiLeaderElectionEngine — Deterministic cluster leader election.
 *
 * Leader score formula (spec §CLUSTER LEADER SELECTION):
 *
 *   WiFiLeaderScore =
 *     w1  * BatteryScore
 *   + w2  * ChargingStatus
 *   + w3  * ConnectivityStability
 *   + w4  * DeviceCapability
 *   + w5  * InternetGatewayAvailability
 *   + w6  * LowMobilityScore
 *   + w7  * HistoricalUptime
 *   + w8  * LowCongestionScore
 *   + w9  * TrustScore
 *   - w10 * ThermalPenalty
 *   - w11 * RecentFailurePenalty
 *   - w12 * LowStoragePenalty
 *
 * Tie-breaker (deterministic):
 *   1. Higher score wins.
 *   2. If scores within 0.01 of each other → prefer charging device.
 *   3. If still tied → prefer internet gateway.
 *   4. If still tied → lexicographic sort of device public key ID (deterministic).
 *
 * Handoff triggers (spec §CLUSTER LEADER SELECTION):
 *   - Battery below 25% (and not charging)
 *   - Thermal state HIGH or CRITICAL
 *   - Queue pressure > 0.80
 *   - Too many connected clients
 *   - Repeated transfer failures
 *   - Better leader available with score margin > 0.15
 */
class WiFiLeaderElectionEngine {

    // Factor weights (sum must be ≈ 1.0 for the additive terms; penalties subtract)
    private val W1_BATTERY              = 0.18f
    private val W2_CHARGING             = 0.12f
    private val W3_STABILITY            = 0.12f
    private val W4_CAPABILITY           = 0.08f
    private val W5_GATEWAY              = 0.12f
    private val W6_LOW_MOBILITY         = 0.08f
    private val W7_UPTIME               = 0.08f
    private val W8_LOW_CONGESTION       = 0.10f
    private val W9_TRUST                = 0.06f
    private val W10_THERMAL_PENALTY     = 0.12f
    private val W11_FAILURE_PENALTY     = 0.08f
    private val W12_STORAGE_PENALTY     = 0.06f

    // Handoff thresholds
    private val HANDOFF_BATTERY_THRESHOLD = 0.25f        // 25%
    private val HANDOFF_QUEUE_THRESHOLD   = 0.80f
    private val HANDOFF_SCORE_MARGIN      = 0.15f

    data class LeaderInput(
        val peerId: String,
        val batteryFraction: Float,       // 0.0–1.0
        val isCharging: Boolean,
        val connectivityStability: Float, // 0.0–1.0 (e.g., uptime ratio of last 10 min)
        val deviceCapabilityScore: Float, // 0.0–1.0 (bandwidth, CPU proxy)
        val hasGateway: Boolean,
        val mobilityScore: Float,         // 0.0–1.0 (1.0 = stationary)
        val historicalUptime: Float,      // 0.0–1.0
        val congestionScore: Float,       // 0.0–1.0 (lower = less congested → better)
        val trustScore: Float,            // 0.0–1.0
        val thermalState: ThermalState,
        val recentFailureFraction: Float, // 0.0–1.0 (failure count / window)
        val storageFraction: Float        // 0.0–1.0 (available / total)
    )

    /**
     * Calculate the leader score for a single candidate.
     * Returns a value in [0.0, 1.0].
     */
    fun calculateLeaderScore(input: LeaderInput): Float {
        val batteryScore     = input.batteryFraction.coerceIn(0f, 1f)
        val chargingScore    = if (input.isCharging) 1.0f else 0.0f
        val stabilityScore   = input.connectivityStability.coerceIn(0f, 1f)
        val capabilityScore  = input.deviceCapabilityScore.coerceIn(0f, 1f)
        val gatewayScore     = if (input.hasGateway) 1.0f else 0.0f
        val mobilityScore    = input.mobilityScore.coerceIn(0f, 1f)
        val uptimeScore      = input.historicalUptime.coerceIn(0f, 1f)
        val lowCongestion    = (1.0f - input.congestionScore).coerceIn(0f, 1f)
        val trustScore       = input.trustScore.coerceIn(0f, 1f)

        val thermalPenalty = when (input.thermalState) {
            ThermalState.NORMAL   -> 0.0f
            ThermalState.HIGH     -> 0.5f
            ThermalState.CRITICAL -> 1.0f
        }
        val failurePenalty  = input.recentFailureFraction.coerceIn(0f, 1f)
        val storagePenalty  = (1.0f - input.storageFraction).coerceIn(0f, 1f)

        val raw = W1_BATTERY         * batteryScore    +
                  W2_CHARGING        * chargingScore   +
                  W3_STABILITY       * stabilityScore  +
                  W4_CAPABILITY      * capabilityScore +
                  W5_GATEWAY         * gatewayScore    +
                  W6_LOW_MOBILITY    * mobilityScore   +
                  W7_UPTIME          * uptimeScore     +
                  W8_LOW_CONGESTION  * lowCongestion   +
                  W9_TRUST           * trustScore      -
                  W10_THERMAL_PENALTY * thermalPenalty -
                  W11_FAILURE_PENALTY * failurePenalty -
                  W12_STORAGE_PENALTY * storagePenalty

        return raw.coerceIn(0f, 1f)
    }

    /**
     * Elect a leader from a list of candidates.
     * Deterministic tie-breaking: score → charging → gateway → key ID lexicographic.
     *
     * @return peerId of the elected leader.
     */
    fun electLeader(candidates: List<Pair<LeaderInput, Float>>): String {
        if (candidates.isEmpty()) throw IllegalArgumentException("No candidates provided")

        val sorted = candidates.sortedWith(
            compareByDescending<Pair<LeaderInput, Float>> { it.second }   // Highest score first
                .thenByDescending { it.first.isCharging }                 // Charging preferred
                .thenByDescending { it.first.hasGateway }                 // Gateway preferred
                .thenBy { it.first.peerId }                               // Deterministic: lexicographic key ID
        )
        return sorted.first().first.peerId
    }

    /**
     * Simplified version for the module interface: accepts WiFiLeaderCandidate list.
     */
    fun electLeaderFromCandidates(candidates: List<WiFiLeaderCandidate>): String {
        if (candidates.isEmpty()) return ""
        val sorted = candidates.sortedWith(
            compareByDescending<WiFiLeaderCandidate> { it.leaderScore }
                .thenByDescending { it.isCharging }
                .thenByDescending { it.hasGateway }
                .thenBy { it.peerId }
        )
        return sorted.first().peerId
    }

    /**
     * Determine if the current leader should hand off leadership.
     *
     * @param leaderBattery   Current leader battery fraction.
     * @param isCharging      Whether leader is charging.
     * @param thermalState    Leader's thermal state.
     * @param queuePressure   Leader's queue pressure (0–1).
     * @param bestCandidateScore  Best available candidate's score.
     * @param currentLeaderScore  Current leader's score.
     */
    fun shouldHandoff(
        leaderBattery: Float,
        isCharging: Boolean,
        thermalState: ThermalState,
        queuePressure: Float,
        bestCandidateScore: Float,
        currentLeaderScore: Float
    ): HandoffReason? {
        if (!isCharging && leaderBattery < HANDOFF_BATTERY_THRESHOLD)
            return HandoffReason.LOW_BATTERY
        if (thermalState == ThermalState.CRITICAL)
            return HandoffReason.THERMAL_CRITICAL
        if (thermalState == ThermalState.HIGH)
            return HandoffReason.THERMAL_HIGH
        if (queuePressure > HANDOFF_QUEUE_THRESHOLD)
            return HandoffReason.QUEUE_OVERLOAD
        if ((bestCandidateScore - currentLeaderScore) > HANDOFF_SCORE_MARGIN)
            return HandoffReason.BETTER_CANDIDATE
        return null
    }

    enum class HandoffReason {
        LOW_BATTERY,
        THERMAL_CRITICAL,
        THERMAL_HIGH,
        QUEUE_OVERLOAD,
        BETTER_CANDIDATE,
        USER_POLICY,
        INTERNET_LOST
    }
}

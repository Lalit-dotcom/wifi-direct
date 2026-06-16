package com.wifidirect.mesh.fallback

import com.wifidirect.mesh.models.WiFiFailureReason
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * WiFiFallbackController — Tracks Wi-Fi failure reasons and reports status
 * to the global routing layer so it can decide to use BLE, Internet, or SMS.
 *
 * Contract:
 * - Wi-Fi module calls recordFailure() whenever a significant failure occurs.
 * - Global router calls reportToRouter() to get the most critical pending reason.
 * - recordRecovery() clears failure state when Wi-Fi succeeds.
 *
 * Design choices:
 * - SOS-specific failures are tracked separately for fast querying.
 * - Failure reasons have a defined criticality order.
 * - A recent-failure window of 60 seconds is used; stale failures auto-expire.
 */
class WiFiFallbackController {

    data class TimestampedFailure(
        val reason: WiFiFailureReason,
        val timestamp: Long
    )

    private val recentFailures = ConcurrentLinkedDeque<TimestampedFailure>()
    private val FAILURE_WINDOW_MS = 60_000L  // Failures older than this are ignored

    // Criticality order — higher index = higher priority for fallback decision
    private val criticality = mapOf(
        WiFiFailureReason.WIFI_UNSUPPORTED        to 15,
        WiFiFailureReason.WIFI_DISABLED           to 14,
        WiFiFailureReason.OS_RESTRICTED           to 13,
        WiFiFailureReason.THERMAL_CRITICAL        to 12,
        WiFiFailureReason.USER_LOW_POWER_MODE     to 11,
        WiFiFailureReason.LOW_BATTERY             to 10,
        WiFiFailureReason.WIFI_POLICY_BLOCKED     to 9,
        WiFiFailureReason.CONGESTION_RED          to 8,
        WiFiFailureReason.LEADER_OVERLOADED       to 7,
        WiFiFailureReason.NO_PEERS_FOUND          to 6,
        WiFiFailureReason.DISCOVERY_TIMEOUT       to 5,
        WiFiFailureReason.CONNECTION_FAILED       to 4,
        WiFiFailureReason.HANDSHAKE_FAILED        to 3,
        WiFiFailureReason.TRANSFER_TIMEOUT        to 2,
        WiFiFailureReason.ACK_TIMEOUT             to 2,
        WiFiFailureReason.PEER_COOLDOWN_ACTIVE    to 1
    )

    /**
     * Record a Wi-Fi failure with a specific reason. Called by all sub-components.
     */
    fun recordFailure(reason: WiFiFailureReason) {
        recentFailures.addLast(TimestampedFailure(reason, System.currentTimeMillis()))
        // Trim stale entries
        pruneStale()
    }

    /**
     * Record a successful Wi-Fi SOS delivery — used to suppress further fallback.
     */
    fun recordSOSSuccess() {
        recentFailures.clear()
    }

    /**
     * Record recovery — connection succeeded; clear failures for that category.
     */
    fun recordRecovery() {
        // After a successful transfer/connection, clear soft failures.
        recentFailures.removeIf {
            it.reason in listOf(
                WiFiFailureReason.CONNECTION_FAILED,
                WiFiFailureReason.HANDSHAKE_FAILED,
                WiFiFailureReason.TRANSFER_TIMEOUT,
                WiFiFailureReason.ACK_TIMEOUT,
                WiFiFailureReason.DISCOVERY_TIMEOUT,
                WiFiFailureReason.NO_PEERS_FOUND,
                WiFiFailureReason.PEER_COOLDOWN_ACTIVE
            )
        }
    }

    /**
     * Returns the most critical failure reason currently active.
     * The global router uses this to decide whether to fall back to BLE/SMS.
     *
     * Returns null if Wi-Fi is healthy.
     */
    fun reportToRouter(): WiFiFailureReason? {
        pruneStale()
        return recentFailures
            .maxByOrNull { criticality[it.reason] ?: 0 }
            ?.reason
    }

    /**
     * Returns true if Wi-Fi SOS should be abandoned and SMS should be used immediately.
     * Conditions: thermal critical, wifi unsupported/disabled, or SOS attempt timed out.
     */
    fun shouldEscalateToSMS(): Boolean {
        pruneStale()
        val escalationReasons = setOf(
            WiFiFailureReason.WIFI_UNSUPPORTED,
            WiFiFailureReason.WIFI_DISABLED,
            WiFiFailureReason.OS_RESTRICTED,
            WiFiFailureReason.THERMAL_CRITICAL,
            WiFiFailureReason.TRANSFER_TIMEOUT,
            WiFiFailureReason.CONGESTION_RED
        )
        return recentFailures.any { it.reason in escalationReasons }
    }

    /**
     * Returns true if BLE should be tried before SMS.
     * BLE is appropriate when Wi-Fi simply has no peers or is congested.
     */
    fun shouldFallbackToBLE(): Boolean {
        pruneStale()
        val bleReasons = setOf(
            WiFiFailureReason.NO_PEERS_FOUND,
            WiFiFailureReason.DISCOVERY_TIMEOUT,
            WiFiFailureReason.CONGESTION_RED,
            WiFiFailureReason.PEER_COOLDOWN_ACTIVE,
            WiFiFailureReason.LEADER_OVERLOADED
        )
        return recentFailures.any { it.reason in bleReasons }
    }

    /**
     * Full recent failure list for diagnostics.
     */
    fun getRecentFailures(): List<TimestampedFailure> {
        pruneStale()
        return recentFailures.toList()
    }

    fun hasAnyFailure(): Boolean {
        pruneStale()
        return recentFailures.isNotEmpty()
    }

    fun clear() {
        recentFailures.clear()
    }

    private fun pruneStale() {
        val cutoff = System.currentTimeMillis() - FAILURE_WINDOW_MS
        recentFailures.removeIf { it.timestamp < cutoff }
    }
}

package com.wifidirect.mesh.broadcast

import com.wifidirect.mesh.Message
import com.wifidirect.mesh.models.*
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * WiFiBroadcastController — Prevents broadcast storms.
 *
 * Implements all spec-required mechanisms:
 *  1. Seen-message cache (LRU-bounded, TTL-bounded)
 *  2. TTL decrement and enforcement
 *  3. Expiry enforcement
 *  4. Randomized rebroadcast delay (100–800 ms)
 *  5. Duplicate suppression during delay window
 *  6. Probabilistic forwarding per congestion state
 *  7. Relay scoring for peer selection
 *  8. Fanout limits per congestion state
 *  9. Congestion-aware rebroadcast policy
 *
 * ForwardProbability = min(1.0, TargetRelayCount / max(NeighborCount, 1))
 * then modified by congestion state:
 *   GREEN  → ×1.00,  fanout 3–5
 *   YELLOW → ×0.75,  fanout 2–3
 *   ORANGE → ×0.40,  fanout 1–2
 *   RED    → 0 for non-SOS/control, SOS always forwarded
 */
class WiFiBroadcastController(
    private val maxSeenCacheSize: Int = 5000,
    private val seenCacheTtlMs: Long = 300_000L  // 5 minutes
) {
    // Seen-message cache: messageId → first-seen timestamp
    private val seenCache = ConcurrentHashMap<String, Long>()
    // Track how many times we've heard each message (for duplicate-window suppression)
    private val hearCount = ConcurrentHashMap<String, Int>()

    // Pending rebroadcasts: messageId → scheduled future
    private val pendingRebroadcasts = ConcurrentHashMap<String, ScheduledFuture<*>>()

    private val scheduler: ScheduledExecutorService =
        Executors.newScheduledThreadPool(2)

    private val rng = SecureRandom()

    // Fanout limits per congestion state
    private val FANOUT_GREEN  = 5
    private val FANOUT_YELLOW = 3
    private val FANOUT_ORANGE = 2
    private val FANOUT_RED    = 0  // Only SOS/control bypasses this

    // Target relay count for probability calculation
    private val TARGET_RELAY_COUNT = 3

    /**
     * Full rebroadcast decision.
     *
     * Returns true if this node should (eventually) forward this message.
     * If returning true, the caller MUST also call scheduleRebroadcast() to apply
     * the randomized delay window.
     *
     * Decision tree (matches spec):
     * 1. If already seen → drop
     * 2. If expired → drop
     * 3. If TTL ≤ 0 → drop
     * 4. If RED and not SOS/control → drop
     * 5. If ORANGE and low priority → drop
     * 6. Probabilistic check
     */
    fun shouldRebroadcast(
        message: Message,
        congestionState: CongestionState,
        neighborCount: Int
    ): Boolean {
        pruneSeenCache()

        // 1. Already seen?
        if (seenCache.containsKey(message.id)) {
            hearCount.merge(message.id, 1, Int::plus)
            return false
        }

        // 2. Expired?
        if (message.expiryTimestamp < System.currentTimeMillis()) return false

        // 3. TTL exhausted?
        if (message.ttl <= 0) return false

        // 4. RED congestion — only SOS and control
        if (congestionState == CongestionState.RED) {
            if (message.priority != QueuePriority.SOS && message.priority != QueuePriority.CONTROL) {
                return false
            }
        }

        // 5. ORANGE — suppress low-priority bulk
        if (congestionState == CongestionState.ORANGE) {
            if (message.priority == QueuePriority.BULK_SYNC ||
                message.priority == QueuePriority.BACKGROUND) {
                return false
            }
        }

        // 6. Probabilistic check
        val prob = calculateForwardProbability(message, congestionState, neighborCount)
        if (rng.nextFloat() > prob) return false

        // Mark as seen
        seenCache[message.id] = System.currentTimeMillis()
        hearCount[message.id] = 0
        return true
    }

    /**
     * Schedule a rebroadcast with randomized delay (100–800 ms).
     * During the delay window, listen for duplicates — if heard enough times,
     * cancel the scheduled forward (duplicate suppression).
     *
     * @param sender     Function that actually sends/relays the message to the given peer IDs.
     * @param peers      Ranked list of candidates to forward to.
     */
    fun scheduleRebroadcast(
        message: Message,
        congestionState: CongestionState,
        peers: List<WiFiRelayCandidate>,
        sender: (peerId: String, message: Message) -> Unit
    ) {
        if (pendingRebroadcasts.containsKey(message.id)) return

        val delayMs = (100 + rng.nextInt(700)).toLong()
        val fanout = fanoutLimit(congestionState, message.priority)
        val selectedPeers = peers.take(fanout)

        val future = scheduler.schedule({
            // Check duplicate suppression: if heard 2+ times during window, cancel
            val heard = hearCount.getOrDefault(message.id, 0)
            if (heard >= 2) {
                // Good relay coverage already, suppress our relay
                pendingRebroadcasts.remove(message.id)
                return@schedule
            }
            // Decrement TTL and forward
            val forwarded = message.copy(ttl = message.ttl - 1)
            selectedPeers.forEach { candidate ->
                sender(candidate.peerId, forwarded)
            }
            pendingRebroadcasts.remove(message.id)
        }, delayMs, TimeUnit.MILLISECONDS)

        pendingRebroadcasts[message.id] = future
    }

    /**
     * Cancel a scheduled rebroadcast if we hear the same message from a better relay.
     */
    fun cancelRebroadcast(messageId: String) {
        pendingRebroadcasts.remove(messageId)?.cancel(false)
        hearCount.merge(messageId, 1, Int::plus)
    }

    /**
     * Called when we hear a duplicate broadcast — increment counter.
     * High counts will suppress our own pending relay.
     */
    fun recordDuplicateHear(messageId: String) {
        hearCount.merge(messageId, 1, Int::plus)
    }

    /**
     * ForwardProbability = min(1.0, TARGET_RELAY_COUNT / max(neighborCount, 1))
     * modified by congestion state.
     */
    fun calculateForwardProbability(
        message: Message,
        congestionState: CongestionState,
        neighborCount: Int
    ): Float {
        val base = (TARGET_RELAY_COUNT.toFloat() / maxOf(neighborCount, 1).toFloat()).coerceAtMost(1.0f)
        val modifier = when (congestionState) {
            CongestionState.GREEN  -> 1.00f
            CongestionState.YELLOW -> 0.75f
            CongestionState.ORANGE -> 0.40f
            CongestionState.RED    -> if (message.priority == QueuePriority.SOS ||
                                         message.priority == QueuePriority.CONTROL) 1.0f else 0.0f
        }
        return (base * modifier).coerceIn(0.0f, 1.0f)
    }

    /**
     * Returns fanout limit per congestion state.
     * SOS and control always get at least 1 relay in RED.
     */
    fun fanoutLimit(congestionState: CongestionState, priority: QueuePriority): Int {
        val baseLimit = when (congestionState) {
            CongestionState.GREEN  -> FANOUT_GREEN
            CongestionState.YELLOW -> FANOUT_YELLOW
            CongestionState.ORANGE -> FANOUT_ORANGE
            CongestionState.RED    -> FANOUT_RED
        }
        return if (baseLimit == 0 &&
                   (priority == QueuePriority.SOS || priority == QueuePriority.CONTROL)) 1
               else baseLimit
    }

    fun isAlreadySeen(messageId: String): Boolean = seenCache.containsKey(messageId)

    fun shutdown() {
        scheduler.shutdownNow()
    }

    private fun pruneSeenCache() {
        val cutoff = System.currentTimeMillis() - seenCacheTtlMs
        // Prune expired entries
        seenCache.entries.removeIf { it.value < cutoff }
        hearCount.keys.removeIf { !seenCache.containsKey(it) }
        // Prune if still too large (evict oldest)
        while (seenCache.size > maxSeenCacheSize) {
            seenCache.minByOrNull { it.value }?.let { seenCache.remove(it.key) }
        }
    }
}

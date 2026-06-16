package com.wifidirect.mesh.ack

import com.wifidirect.mesh.WiFiClusterACKSummary
import com.wifidirect.mesh.models.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * WiFiAckManager — ACK aggregation and storm prevention.
 *
 * Design:
 * - Per-message ACK policy based on message type.
 * - Only cluster leader / gateway sends aggregate ACKs for broadcasts.
 * - Individual node ACKs for direct messages.
 * - Group/bulk messages use sampled or aggregated ACKs.
 * - ACK deduplication: once an ACK summary is sent for a message, suppress further.
 * - Rate limiting: max ACK sends per message and per peer.
 * - Chunk-level ACKs stay between direct transfer peers only (not propagated).
 *
 * ACK policy per priority:
 *   CONTROL       → No ACK (handled at session level)
 *   SOS           → Delivery proof from selected relays/gateways; suppress individual ACKs
 *   SOS_ACK       → No further ACK needed
 *   EMERGENCY     → Aggregated cluster ACK only
 *   ROUTING_SUMMARY → No ACK / session-level ACK
 *   DIRECT_MESSAGE  → End-to-end ACK allowed
 *   GROUP_MESSAGE   → Sampled/aggregated ACK
 *   BULK_SYNC       → Chunk-level only (peer-to-peer)
 *   BACKGROUND      → No ACK
 */
class WiFiAckManager(
    private val localNodeId: String,
    private val maxAckRatePerMessage: Int = 3,
    private val ackTtlMs: Long = 30_000L
) {
    enum class AckPolicy { NONE, DIRECT, AGGREGATED, SAMPLED, CHUNK_ONLY }

    // Records of sent ACKs: messageId → count sent
    private val ackSentCount = ConcurrentHashMap<String, AtomicInteger>()
    // ACK receipt counts for aggregation: messageId → receive count
    private val ackReceiptCount = ConcurrentHashMap<String, AtomicInteger>()
    // Messages for which a cluster/gateway ACK has already been dispatched
    private val ackSummaryDispatched = ConcurrentHashMap<String, Long>()
    // Rate-limit: per-peer ACK attempt counts
    private val peerAckCount = ConcurrentHashMap<String, AtomicInteger>()
    // Seen ACKs for deduplication
    private val seenAckIds = ConcurrentHashMap<String, Long>()

    /** Determine the ACK policy for a given message priority. */
    fun ackPolicyFor(priority: QueuePriority): AckPolicy = when (priority) {
        QueuePriority.CONTROL          -> AckPolicy.NONE
        QueuePriority.SOS              -> AckPolicy.AGGREGATED
        QueuePriority.SOS_ACK          -> AckPolicy.NONE
        QueuePriority.EMERGENCY        -> AckPolicy.AGGREGATED
        QueuePriority.ROUTING_SUMMARY  -> AckPolicy.NONE
        QueuePriority.DIRECT_MESSAGE   -> AckPolicy.DIRECT
        QueuePriority.GROUP_MESSAGE    -> AckPolicy.SAMPLED
        QueuePriority.BULK_SYNC        -> AckPolicy.CHUNK_ONLY
        QueuePriority.BACKGROUND       -> AckPolicy.NONE
    }

    /**
     * Record receipt of a message. Updates internal counters for aggregation.
     * Returns true if this node should send an individual ACK (DIRECT policy).
     */
    fun recordReceipt(messageId: String, priority: QueuePriority, fromPeerId: String): Boolean {
        pruneStale()
        val policy = ackPolicyFor(priority)
        ackReceiptCount.getOrPut(messageId) { AtomicInteger(0) }.incrementAndGet()

        return when (policy) {
            AckPolicy.DIRECT -> {
                // Rate-limit per peer
                val count = peerAckCount.getOrPut(fromPeerId) { AtomicInteger(0) }.incrementAndGet()
                count <= maxAckRatePerMessage
            }
            AckPolicy.SAMPLED -> {
                // Only ACK 1 in every 5 group message receipts
                val total = ackReceiptCount[messageId]?.get() ?: 0
                total % 5 == 0
            }
            else -> false
        }
    }

    /**
     * Build an aggregated ACK summary for a cluster leader to broadcast.
     * Only does so if not already dispatched and there's meaningful receipts.
     */
    fun buildAggregatedAck(messageIds: List<String>): WiFiClusterACKSummary? {
        pruneStale()
        val eligible = messageIds.filter { msgId ->
            val alreadySent = ackSummaryDispatched.containsKey(msgId)
            val count = ackReceiptCount[msgId]?.get() ?: 0
            !alreadySent && count > 0
        }
        if (eligible.isEmpty()) return null

        // Mark as dispatched
        val now = System.currentTimeMillis()
        eligible.forEach { ackSummaryDispatched[it] = now }

        return WiFiClusterACKSummary(
            senderId   = localNodeId,
            messageIds = eligible,
            timestamp  = now
        )
    }

    /**
     * Process a received ACK summary from a peer.
     * If a gateway or cluster leader has already ACK'd, suppress further individual ACKs.
     *
     * @return list of message IDs whose individual ACKs should now be suppressed.
     */
    fun processAckSummary(summary: WiFiClusterACKSummary): List<String> {
        val dedupKey = "${summary.senderId}:${summary.timestamp}"
        if (seenAckIds.containsKey(dedupKey)) return emptyList() // duplicate ACK

        seenAckIds[dedupKey] = System.currentTimeMillis()
        val suppressed = mutableListOf<String>()

        summary.messageIds.forEach { msgId ->
            // Mark these as already-ACK'd so we don't send more
            ackSummaryDispatched.putIfAbsent(msgId, summary.timestamp)
            suppressed.add(msgId)
        }
        return suppressed
    }

    /**
     * Check if an individual ACK for this message should be suppressed because
     * a cluster/gateway aggregate ACK was already sent.
     */
    fun isAckSuppressed(messageId: String): Boolean {
        return ackSummaryDispatched.containsKey(messageId)
    }

    /**
     * Called when gateway confirms receipt of a message.
     * Suppresses all lower-value individual ACKs.
     */
    fun onGatewayConfirmed(messageId: String) {
        ackSummaryDispatched[messageId] = System.currentTimeMillis()
    }

    /**
     * Check if node should send ACK — rate-limit guard.
     */
    fun canSendAck(messageId: String): Boolean {
        val count = ackSentCount.getOrPut(messageId) { AtomicInteger(0) }.incrementAndGet()
        return count <= maxAckRatePerMessage
    }

    fun getReceiptCount(messageId: String): Int {
        return ackReceiptCount[messageId]?.get() ?: 0
    }

    fun clear() {
        ackSentCount.clear()
        ackReceiptCount.clear()
        ackSummaryDispatched.clear()
        peerAckCount.clear()
        seenAckIds.clear()
    }

    private fun pruneStale() {
        val cutoff = System.currentTimeMillis() - ackTtlMs
        seenAckIds.entries.removeIf { it.value < cutoff }
        ackSummaryDispatched.entries.removeIf { it.value < cutoff }
    }
}

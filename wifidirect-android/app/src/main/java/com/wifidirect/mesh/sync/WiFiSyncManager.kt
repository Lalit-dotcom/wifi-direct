package com.wifidirect.mesh.sync

import com.wifidirect.mesh.models.*
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

/**
 * WiFiSyncManager — Summary-before-transfer sync engine.
 *
 * Implements the spec §MESSAGE/BUNDLE SYNC REQUIREMENTS:
 *
 * Flow:
 *   1. Build a compact local message summary (Bloom filter bits + metadata).
 *   2. Exchange summaries with peer.
 *   3. Diff summaries to find what the peer is missing.
 *   4. Rank missing bundles by priority.
 *   5. Return prioritized transfer list.
 *
 * Bloom filter design:
 *   - Simple bit-array of N bits (configurable, default 8192 bits = 1024 bytes)
 *   - k=3 hash functions (FNV variants)
 *   - False positive rate ~1% for 1000 items with 8192 bits
 *   - Used only as a compact "have you seen this?" indicator
 *
 * The remote peer sends us their summary → we compute what they likely DON'T have →
 * we send them only what's missing, ordered by priority.
 */
class WiFiSyncManager(
    private val localNodeId: String,
    private val bloomBits: Int = 8192
) {
    // Local message store: messageId → (priority, expiresAt)
    data class MessageMeta(
        val messageId: String,
        val bundleId: String?,
        val priority: Int,
        val expiresAt: Long,
        val sizeBytes: Long = 0L
    )

    private val localMessages = ConcurrentHashMap<String, MessageMeta>()
    private val summaryVersion = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Register a message in the local store so it can be included in summaries.
     */
    fun registerMessage(meta: MessageMeta) {
        val now = System.currentTimeMillis()
        if (meta.expiresAt <= now) return  // Already expired, skip
        localMessages[meta.messageId] = meta
    }

    fun unregisterMessage(messageId: String) {
        localMessages.remove(messageId)
    }

    /**
     * Build a compact WiFiMessageSummary for exchange with a peer.
     *
     * Summary fields:
     *  - nodeId                   : our identity
     *  - bloomFilterBits          : Bloom filter encoding all our known message IDs
     *  - highestPriorityWaiting   : lowest QueuePriority.value we have ready to send
     *  - gatewayAvailable         : whether we have internet access
     */
    fun buildLocalSummary(gatewayAvailable: Boolean = false): WiFiMessageSummary {
        pruneExpired()
        val bloom = buildBloomFilter(localMessages.keys.toList())
        val highestPriority = localMessages.values
            .minByOrNull { it.priority }?.priority
            ?: QueuePriority.BACKGROUND.value

        return WiFiMessageSummary(
            nodeId                = localNodeId,
            summaryVersion        = summaryVersion.incrementAndGet(),
            bloomFilterBits       = bloom,
            highestPriorityWaiting = highestPriority,
            gatewayAvailable       = gatewayAvailable
        )
    }

    /**
     * Given the remote peer's summary, determine which of our messages they likely lack.
     * Returns a prioritized list of MessageMeta for transfer.
     *
     * @param remoteSummary   Summary received from the peer.
     * @param maxItems        Cap on items to return (avoid summary storms).
     * @return                Sorted list of message metas to send.
     */
    fun computeMissingForPeer(
        remoteSummary: WiFiMessageSummary,
        maxItems: Int = 30
    ): List<MessageMeta> {
        pruneExpired()
        val remoteBloom = remoteSummary.bloomFilterBits

        return localMessages.values
            .filter { meta ->
                // Not in their Bloom filter (might be false positive, but worth sending)
                !bloomMightContain(remoteBloom, meta.messageId)
            }
            .sortedWith(
                compareBy<MessageMeta> { it.priority }          // Lower value = higher priority
                    .thenBy { it.expiresAt }                    // More urgent expiry first
            )
            .take(maxItems)
    }

    /**
     * Check if a peer's Bloom filter likely contains a given message ID.
     * False positives possible (leads to no transfer, which is acceptable).
     * False negatives are impossible (we won't miss sending something they need).
     */
    fun peerLikelyHas(remoteBloom: ByteArray, messageId: String): Boolean {
        return bloomMightContain(remoteBloom, messageId)
    }

    /**
     * Build a Bloom filter from a list of message IDs.
     * Uses 3 FNV-1a hash variants to set bits.
     */
    fun buildBloomFilter(messageIds: List<String>): ByteArray {
        val bits = BitSet(bloomBits)
        for (id in messageIds) {
            val hashes = bloomHashes(id)
            hashes.forEach { h -> bits.set(Math.abs(h) % bloomBits) }
        }
        // Convert BitSet to byte array of fixed size
        val bytes = ByteArray((bloomBits + 7) / 8)
        val bsBytes = bits.toByteArray()
        bsBytes.copyInto(bytes, 0, 0, minOf(bsBytes.size, bytes.size))
        return bytes
    }

    /**
     * Check if the given bit array might contain the given ID.
     */
    private fun bloomMightContain(bloomBytes: ByteArray, messageId: String): Boolean {
        val bits = BitSet.valueOf(bloomBytes)
        val hashes = bloomHashes(messageId)
        return hashes.all { h -> bits.get(Math.abs(h) % bloomBits) }
    }

    /**
     * Generate k=3 hash values for a message ID using FNV-1a variants.
     */
    private fun bloomHashes(messageId: String): List<Int> {
        val bytes = messageId.toByteArray()
        val h1 = fnv1a(bytes, seed = 0x811c9dc5.toInt())
        val h2 = fnv1a(bytes, seed = 0x01000193)
        val h3 = fnv1a(bytes, seed = 0x04c11db7.toInt())
        return listOf(h1, h2, h3)
    }

    private fun fnv1a(data: ByteArray, seed: Int): Int {
        var hash = seed
        for (b in data) {
            hash = hash xor b.toInt()
            hash *= 0x01000193
        }
        return hash
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        localMessages.entries.removeIf { it.value.expiresAt <= now }
    }

    fun localMessageCount(): Int = localMessages.size
    fun getLocalMessages(): Map<String, MessageMeta> = localMessages.toMap()
    fun hasMessage(messageId: String): Boolean = localMessages.containsKey(messageId)
}

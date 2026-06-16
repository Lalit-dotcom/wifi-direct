package com.wifidirect.mesh.sync

import com.wifidirect.mesh.models.*
import com.wifidirect.mesh.security.CryptoUtils
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * WiFiChunkTransferEngine — Chunked, resumable, hash-verified bundle transfer.
 *
 * Design (spec §CHUNKED AND RESUMABLE TRANSFER):
 *
 * 1. Sender creates a WiFiBundleManifest describing the bundle.
 * 2. Receiver acks the manifest and reports received chunks (via BitSet).
 * 3. Sender sends only MISSING chunks.
 * 4. Each chunk carries its own SHA-256 hash; receiver verifies immediately.
 * 5. On resumption, sender re-queries the receiver's chunk bitmask and sends only gaps.
 * 6. SOS/emergency bundles can interrupt bulk chunks.
 * 7. Expired low-priority incomplete bundles are evicted when storage is low.
 *
 * Default chunk size: 32 KB (configurable).
 * Max active transfers: configurable.
 */
class WiFiChunkTransferEngine(
    private val defaultChunkSizeBytes: Int = 32 * 1024,   // 32 KB
    private val maxActiveTransfers: Int = 4
) {
    // Outgoing transfers: bundleId → state
    private val outgoing = ConcurrentHashMap<String, OutgoingTransferState>()
    // Incoming transfers: bundleId → state
    private val incoming = ConcurrentHashMap<String, IncomingTransferState>()

    private val lock = ReentrantLock()

    data class OutgoingTransferState(
        val manifest: WiFiBundleManifest,
        val fullPayload: ByteArray,
        val peerId: String,
        var sentChunks: BitSet = BitSet(),
        var ackedChunks: BitSet = BitSet(),
        var lastActivityMs: Long = System.currentTimeMillis(),
        var paused: Boolean = false
    )

    data class IncomingTransferState(
        val manifest: WiFiBundleManifest,
        val peerId: String,
        val receivedChunks: BitSet = BitSet(),
        val chunkData: Array<ByteArray?>,
        var lastActivityMs: Long = System.currentTimeMillis(),
        var isComplete: Boolean = false
    ) {
        constructor(manifest: WiFiBundleManifest, peerId: String) : this(
            manifest       = manifest,
            peerId         = peerId,
            chunkData      = arrayOfNulls(manifest.chunkCount)
        )
    }

    /**
     * Create a manifest for a payload to be sent.
     * Splits payload into chunks and records the outgoing state.
     */
    fun prepareOutgoing(
        bundleId: String,
        messageId: String,
        payload: ByteArray,
        priority: Int,
        expiresAt: Long,
        peerId: String
    ): WiFiBundleManifest {
        val chunkCount = (payload.size + defaultChunkSizeBytes - 1) / defaultChunkSizeBytes
        val bundleHash = CryptoUtils.bytesToHex(CryptoUtils.sha256(payload))

        val manifest = WiFiBundleManifest(
            bundleId       = bundleId,
            messageId      = messageId,
            totalSizeBytes = payload.size.toLong(),
            chunkSizeValue = defaultChunkSizeBytes,
            chunkCount     = chunkCount,
            bundleHash     = bundleHash,
            priority       = priority,
            expiresAt      = expiresAt
        )

        outgoing[bundleId] = OutgoingTransferState(
            manifest    = manifest,
            fullPayload = payload,
            peerId      = peerId
        )
        return manifest
    }

    /**
     * Build the next chunk to send to the peer.
     * Only sends chunks that are NOT already acked.
     * Returns null if all chunks are sent or transfer is paused.
     */
    fun nextChunkToSend(bundleId: String): WiFiChunk? {
        val state = outgoing[bundleId] ?: return null
        if (state.paused) return null
        if (state.manifest.expiresAt < System.currentTimeMillis()) {
            outgoing.remove(bundleId)
            return null
        }

        val total = state.manifest.chunkCount
        for (i in 0 until total) {
            if (!state.ackedChunks.get(i)) {
                return buildChunk(state, i)
            }
        }
        return null  // All acked
    }

    /**
     * Mark a chunk as acked by the receiver. Used by sender side.
     */
    fun markChunkAcked(bundleId: String, chunkIndex: Int) {
        outgoing[bundleId]?.let { state ->
            state.ackedChunks.set(chunkIndex)
            state.lastActivityMs = System.currentTimeMillis()
        }
    }

    /**
     * Sender: get bitmask of missing (not acked) chunks. Used to re-calculate resume plan.
     */
    fun getMissingChunks(bundleId: String): BitSet? {
        val state = outgoing[bundleId] ?: return null
        val missing = BitSet(state.manifest.chunkCount)
        for (i in 0 until state.manifest.chunkCount) {
            if (!state.ackedChunks.get(i)) missing.set(i)
        }
        return missing
    }

    /**
     * Initialize an incoming transfer from a received manifest.
     */
    fun initIncoming(manifest: WiFiBundleManifest, peerId: String) {
        incoming.putIfAbsent(manifest.bundleId, IncomingTransferState(manifest, peerId))
    }

    /**
     * Process a received chunk.
     *
     * @return true if chunk is valid and accepted.
     *         false if hash verification fails or duplicate/unexpected.
     */
    fun receiveChunk(chunk: WiFiChunk): Boolean {
        val state = incoming[chunk.bundleId] ?: return false

        // Duplicate check
        if (state.receivedChunks.get(chunk.chunkIndex)) return true  // Already have it

        // Hash verification
        val actualHash = CryptoUtils.bytesToHex(CryptoUtils.sha256(chunk.payload))
        if (actualHash != chunk.chunkHash) {
            return false  // Corrupted chunk — caller should log and request re-send
        }

        // Store chunk
        state.chunkData[chunk.chunkIndex] = chunk.payload
        state.receivedChunks.set(chunk.chunkIndex)
        state.lastActivityMs = System.currentTimeMillis()

        // Check if complete
        if (state.receivedChunks.cardinality() == state.manifest.chunkCount) {
            state.isComplete = true
            // Verify full bundle hash
            val full = reassemble(state)
            if (full != null) {
                val fullHash = CryptoUtils.bytesToHex(CryptoUtils.sha256(full))
                if (fullHash != state.manifest.bundleHash) {
                    // Corrupt bundle — discard
                    incoming.remove(chunk.bundleId)
                    return false
                }
            }
        }
        return true
    }

    /**
     * Get the bitset of received chunks — sent to the peer so they know what to resume with.
     */
    fun getReceivedChunkMask(bundleId: String): BitSet? {
        return incoming[bundleId]?.receivedChunks?.clone() as? BitSet
    }

    /**
     * Check if an incoming transfer is complete and return the full reassembled payload.
     */
    fun tryReassemble(bundleId: String): ByteArray? {
        val state = incoming[bundleId] ?: return null
        if (!state.isComplete) return null
        return reassemble(state)
    }

    /**
     * Pause a bulk transfer (e.g., when SOS arrives or congestion hits ORANGE/RED).
     */
    fun pauseTransfer(bundleId: String) {
        outgoing[bundleId]?.paused = true
    }

    /**
     * Resume a paused transfer.
     */
    fun resumeTransfer(bundleId: String) {
        outgoing[bundleId]?.paused = false
    }

    /**
     * Pause all transfers with priority >= threshold (e.g., pause bulk when SOS arrives).
     * Priority 0 = CONTROL (most urgent), 7 = BULK, 8 = BACKGROUND.
     */
    fun pauseTransfersByPriority(minPriorityValue: Int) {
        outgoing.values.forEach { state ->
            if (state.manifest.priority >= minPriorityValue) {
                state.paused = true
            }
        }
    }

    /**
     * Remove expired or abandoned transfers to free storage.
     */
    fun evictExpired(): Int {
        val now = System.currentTimeMillis()
        var evicted = 0
        outgoing.entries.removeIf { (_, s) -> (s.manifest.expiresAt < now).also { if (it) evicted++ } }
        incoming.entries.removeIf { (_, s) -> (s.manifest.expiresAt < now).also { if (it) evicted++ } }
        return evicted
    }

    /**
     * Evict low-priority incomplete bundles when storage is low.
     * Keeps SOS/emergency bundles (priority < 3).
     */
    fun evictLowPriorityIncomplete(keepPriorityBelow: Int = 4): Int {
        var evicted = 0
        incoming.entries.removeIf { (_, s) ->
            (!s.isComplete && s.manifest.priority >= keepPriorityBelow).also { if (it) evicted++ }
        }
        return evicted
    }

    fun isIncomingComplete(bundleId: String): Boolean = incoming[bundleId]?.isComplete == true
    fun hasOutgoing(bundleId: String): Boolean = outgoing.containsKey(bundleId)
    fun hasIncoming(bundleId: String): Boolean = incoming.containsKey(bundleId)
    fun activeOutgoingCount(): Int = outgoing.size
    fun activeIncomingCount(): Int = incoming.size

    // ---- Internals ----

    private fun buildChunk(state: OutgoingTransferState, index: Int): WiFiChunk {
        val start = index * state.manifest.chunkSizeValue
        val end = minOf(start + state.manifest.chunkSizeValue, state.fullPayload.size)
        val chunkBytes = state.fullPayload.copyOfRange(start, end)
        val hash = CryptoUtils.bytesToHex(CryptoUtils.sha256(chunkBytes))
        state.sentChunks.set(index)
        state.lastActivityMs = System.currentTimeMillis()
        return WiFiChunk(
            bundleId    = state.manifest.bundleId,
            chunkIndex  = index,
            payload     = chunkBytes,
            chunkHash   = hash
        )
    }

    private fun reassemble(state: IncomingTransferState): ByteArray? {
        val total = state.manifest.chunkCount
        val parts = (0 until total).map { i ->
            state.chunkData[i] ?: return null  // Missing chunk
        }
        return parts.fold(ByteArray(0)) { acc, b -> acc + b }
    }
}

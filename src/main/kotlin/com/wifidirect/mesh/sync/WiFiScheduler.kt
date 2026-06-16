package com.wifidirect.mesh.sync

import com.wifidirect.mesh.Message
import com.wifidirect.mesh.models.*
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * WiFiScheduler — Priority queue manager for outgoing Wi-Fi message transfer.
 *
 * Maintains 9 separate queues (one per QueuePriority level).
 * Schedules messages by urgency. Under congestion, filters by allowed priorities.
 *
 * SOS messages ALWAYS come first, regardless of insertion order.
 * Expired messages are pruned on dequeue.
 * Storage quota enforced with priority-based drop policy.
 *
 * Drop policy when over quota (spec §QUEUE MANAGEMENT):
 *   1. Drop expired background
 *   2. Drop duplicate bundles
 *   3. Drop low-priority bulk
 *   4. Drop old group messages
 *   5. Delay direct messages if possible
 *   6. Keep SOS/emergency until expiry
 */
class WiFiScheduler(
    private val maxTotalMessages: Int = 500,
    private val maxBytesPerQueue: Long = 10 * 1024 * 1024L  // 10 MB per queue
) {
    data class ScheduledMessage(
        val message: Message,
        val enqueuedAt: Long = System.currentTimeMillis()
    ) : Comparable<ScheduledMessage> {
        override fun compareTo(other: ScheduledMessage): Int {
            val priCmp = this.message.priority.value.compareTo(other.message.priority.value)
            if (priCmp != 0) return priCmp
            return this.enqueuedAt.compareTo(other.enqueuedAt)  // FIFO within same priority
        }
    }

    // One priority-sorted queue per QueuePriority level for O(log n) enqueue/dequeue
    private val queues: Map<QueuePriority, PriorityQueue<ScheduledMessage>> =
        QueuePriority.values().associateWith { PriorityQueue() }

    private val lock = ReentrantLock()

    // Dedup: track message IDs in the scheduler
    private val enqueuedIds = ConcurrentHashMap<String, QueuePriority>()

    // Stats
    var totalDropped: Int = 0
        private set

    /**
     * Enqueue a message. Deduplicates by message ID.
     * Returns false if message was already enqueued or is expired.
     */
    fun enqueue(message: Message): Boolean {
        if (message.expiryTimestamp < System.currentTimeMillis()) return false   // already expired
        if (enqueuedIds.containsKey(message.id)) return false                    // duplicate

        lock.withLock {
            enforceStorageQuota()
            queues[message.priority]?.add(ScheduledMessage(message))
            enqueuedIds[message.id] = message.priority
        }
        return true
    }

    /**
     * Dequeue the highest-priority eligible message.
     * Skips expired messages.
     *
     * @param congestionState  Current congestion level — controls which priorities are eligible.
     * @param congestionManager Used to check isTrafficEligible.
     * @return Next message to send, or null if none eligible.
     */
    fun dequeue(congestionManager: WiFiCongestionManager): Message? {
        lock.withLock {
            for (priority in QueuePriority.values()) {
                if (!congestionManager.isTrafficEligible(priority)) continue
                val queue = queues[priority] ?: continue
                // Remove expired messages at the head
                while (queue.isNotEmpty()) {
                    val candidate = queue.peek()!!
                    if (candidate.message.expiryTimestamp < System.currentTimeMillis()) {
                        queue.poll()
                        enqueuedIds.remove(candidate.message.id)
                        continue
                    }
                    // Valid message found
                    queue.poll()
                    enqueuedIds.remove(candidate.message.id)
                    return candidate.message
                }
            }
            return null
        }
    }

    /**
     * Peek at the highest waiting priority without dequeuing.
     */
    fun highestWaitingPriority(): Int {
        for (priority in QueuePriority.values()) {
            if (!queues[priority].isNullOrEmpty()) return priority.value
        }
        return QueuePriority.BACKGROUND.value
    }

    /**
     * Returns true if any SOS or CONTROL message is pending.
     */
    fun hasUrgentPending(): Boolean {
        return !(queues[QueuePriority.CONTROL].isNullOrEmpty() &&
                 queues[QueuePriority.SOS].isNullOrEmpty() &&
                 queues[QueuePriority.SOS_ACK].isNullOrEmpty())
    }

    /** Total message count across all queues. */
    fun totalSize(): Int = lock.withLock { queues.values.sumOf { it.size } }

    /** Queue pressure as a ratio of maxTotalMessages. */
    fun queuePressure(): Float = (totalSize().toFloat() / maxTotalMessages.toFloat()).coerceIn(0f, 1f)

    /** Remove a specific message by ID (e.g. after successful delivery). */
    fun remove(messageId: String): Boolean {
        var result = false
        lock.withLock {
            val priority = enqueuedIds[messageId]
            if (priority != null) {
                val queue = queues[priority]
                if (queue != null) {
                    val removed = queue.removeIf { it.message.id == messageId }
                    if (removed) enqueuedIds.remove(messageId)
                    result = removed
                }
            }
        }
        return result
    }

    /** Count of messages per priority level — for diagnostics. */
    fun queueStats(): Map<QueuePriority, Int> {
        return lock.withLock {
            queues.mapValues { it.value.size }
        }
    }

    /** Prune all expired messages across all queues. */
    fun pruneExpired(): Int {
        val now = System.currentTimeMillis()
        var pruned = 0
        lock.withLock {
            for ((_, queue) in queues) {
                val toRemove = queue.filter { it.message.expiryTimestamp < now }
                toRemove.forEach {
                    queue.remove(it)
                    enqueuedIds.remove(it.message.id)
                    pruned++
                }
            }
        }
        return pruned
    }

    /**
     * Enforce storage quota.
     * Drop policy (spec §QUEUE MANAGEMENT):
     *   1. Expired background
     *   2. Old bulk
     *   3. Old group messages
     *   4. Oldest direct messages (NOT SOS/emergency)
     */
    private fun enforceStorageQuota() {
        if (totalSize() < maxTotalMessages) return

        val now = System.currentTimeMillis()
        val dropOrder = listOf(
            QueuePriority.BACKGROUND,
            QueuePriority.BULK_SYNC,
            QueuePriority.GROUP_MESSAGE,
            QueuePriority.DIRECT_MESSAGE
        )
        for (priority in dropOrder) {
            val queue = queues[priority] ?: continue
            if (queue.isNotEmpty()) {
                // Drop oldest
                val oldest = queue.minByOrNull { it.enqueuedAt }
                if (oldest != null) {
                    queue.remove(oldest)
                    enqueuedIds.remove(oldest.message.id)
                    totalDropped++
                    return
                }
            }
        }
    }

    fun clear() {
        lock.withLock {
            queues.values.forEach { it.clear() }
            enqueuedIds.clear()
        }
    }
}

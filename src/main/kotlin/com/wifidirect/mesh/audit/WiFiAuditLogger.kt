package com.wifidirect.mesh.audit

import com.wifidirect.mesh.models.WiFiAuditLogEntry
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * WiFiAuditLogger — Structured ring-buffer audit logger.
 *
 * Design principles:
 * - Ring buffer with a configurable max size to avoid unbounded memory growth.
 * - Thread-safe — uses ConcurrentLinkedDeque with size bounding.
 * - Severity-filtered export.
 * - Events are tagged with predefined event-type constants.
 */
object WiFiAuditEventType {
    const val DISCOVERY_START    = "DISCOVERY_START"
    const val DISCOVERY_STOP     = "DISCOVERY_STOP"
    const val PEER_SIGHTED       = "PEER_SIGHTED"
    const val PEER_MERGED        = "PEER_MERGED"
    const val PEER_EXPIRED       = "PEER_EXPIRED"
    const val CONNECT_ATTEMPT    = "CONNECT_ATTEMPT"
    const val CONNECT_SUCCESS    = "CONNECT_SUCCESS"
    const val CONNECT_FAIL       = "CONNECT_FAIL"
    const val HANDSHAKE_OK       = "HANDSHAKE_OK"
    const val HANDSHAKE_FAIL     = "HANDSHAKE_FAIL"
    const val SYNC_SUMMARY_SENT  = "SYNC_SUMMARY_SENT"
    const val SYNC_SUMMARY_RECV  = "SYNC_SUMMARY_RECV"
    const val TRANSFER_START     = "TRANSFER_START"
    const val TRANSFER_COMPLETE  = "TRANSFER_COMPLETE"
    const val TRANSFER_ABORT     = "TRANSFER_ABORT"
    const val CHUNK_RECEIVED     = "CHUNK_RECEIVED"
    const val CHUNK_CORRUPT      = "CHUNK_CORRUPT"
    const val CHUNK_RESUME       = "CHUNK_RESUME"
    const val CONGESTION_UPDATE  = "CONGESTION_UPDATE"
    const val BROADCAST_FORWARD  = "BROADCAST_FORWARD"
    const val BROADCAST_SUPPRESS = "BROADCAST_SUPPRESS"
    const val ACK_SENT           = "ACK_SENT"
    const val ACK_RECEIVED       = "ACK_RECEIVED"
    const val ACK_AGGREGATED     = "ACK_AGGREGATED"
    const val BACKPRESSURE_SENT  = "BACKPRESSURE_SENT"
    const val BACKPRESSURE_RECV  = "BACKPRESSURE_RECV"
    const val CLUSTER_CREATE     = "CLUSTER_CREATE"
    const val CLUSTER_JOIN       = "CLUSTER_JOIN"
    const val CLUSTER_LEAVE      = "CLUSTER_LEAVE"
    const val CLUSTER_HEARTBEAT  = "CLUSTER_HEARTBEAT"
    const val CLUSTER_SPLIT      = "CLUSTER_SPLIT"
    const val CLUSTER_MERGE      = "CLUSTER_MERGE"
    const val LEADER_ELECTED     = "LEADER_ELECTED"
    const val LEADER_HANDOFF     = "LEADER_HANDOFF"
    const val LEADER_TIMEOUT     = "LEADER_TIMEOUT"
    const val FALLBACK_REPORT    = "FALLBACK_REPORT"
    const val SOS_ATTEMPT        = "SOS_ATTEMPT"
    const val SOS_SUCCESS        = "SOS_SUCCESS"
    const val SOS_TIMEOUT        = "SOS_TIMEOUT"
    const val PEER_FAILURE       = "PEER_FAILURE"
    const val PEER_COOLDOWN      = "PEER_COOLDOWN"
    const val UNKNOWN_PAYLOAD    = "UNKNOWN_PAYLOAD"
    const val QUEUE_DROP         = "QUEUE_DROP"
    const val STORAGE_PRESSURE   = "STORAGE_PRESSURE"
}

object WiFiAuditSeverity {
    const val DEBUG   = "DEBUG"
    const val INFO    = "INFO"
    const val WARNING = "WARNING"
    const val ERROR   = "ERROR"
    const val CRITICAL = "CRITICAL"

    private val ORDER = mapOf(DEBUG to 0, INFO to 1, WARNING to 2, ERROR to 3, CRITICAL to 4)
    fun isAtLeast(severity: String, minimum: String): Boolean =
        (ORDER[severity] ?: 0) >= (ORDER[minimum] ?: 0)
}

class WiFiAuditLogger(private val maxEntries: Int = 2000) {

    private val entries = ConcurrentLinkedDeque<WiFiAuditLogEntry>()

    /**
     * Log an audit event. If ring buffer is full, oldest entry is evicted.
     */
    fun log(eventType: String, severity: String, message: String) {
        val entry = WiFiAuditLogEntry(
            timestamp  = System.currentTimeMillis(),
            eventType  = eventType,
            severity   = severity,
            message    = message
        )
        entries.addLast(entry)
        // Ring-buffer eviction
        while (entries.size > maxEntries) {
            entries.pollFirst()
        }
    }

    fun info(eventType: String, message: String)     = log(eventType, WiFiAuditSeverity.INFO,     message)
    fun warn(eventType: String, message: String)     = log(eventType, WiFiAuditSeverity.WARNING,  message)
    fun error(eventType: String, message: String)    = log(eventType, WiFiAuditSeverity.ERROR,    message)
    fun debug(eventType: String, message: String)    = log(eventType, WiFiAuditSeverity.DEBUG,    message)
    fun critical(eventType: String, message: String) = log(eventType, WiFiAuditSeverity.CRITICAL, message)

    /** Export all entries at or above the given minimum severity. */
    fun export(minimumSeverity: String = WiFiAuditSeverity.DEBUG): List<WiFiAuditLogEntry> {
        return entries.filter { WiFiAuditSeverity.isAtLeast(it.severity, minimumSeverity) }
    }

    /** Export entries matching a specific event type. */
    fun exportByType(eventType: String): List<WiFiAuditLogEntry> {
        return entries.filter { it.eventType == eventType }
    }

    /** Export last N entries. */
    fun exportLast(n: Int): List<WiFiAuditLogEntry> {
        return entries.toList().takeLast(n)
    }

    fun clear() {
        entries.clear()
    }

    fun size(): Int = entries.size
}

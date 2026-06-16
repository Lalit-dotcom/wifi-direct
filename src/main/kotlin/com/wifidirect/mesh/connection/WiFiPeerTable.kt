package com.wifidirect.mesh.connection

import com.wifidirect.mesh.models.*
import java.util.concurrent.ConcurrentHashMap

class WiFiPeerTable {
    private val peers = ConcurrentHashMap<String, WiFiPeer>()

    fun get(devicePublicKeyId: String): WiFiPeer? {
        return peers[devicePublicKeyId]
    }

    fun getAll(): List<WiFiPeer> {
        return peers.values.toList()
    }

    fun insert(peer: WiFiPeer) {
        peers[peer.devicePublicKeyId] = peer
    }

    fun update(peer: WiFiPeer) {
        peers[peer.devicePublicKeyId] = peer
    }

    fun remove(devicePublicKeyId: String) {
        peers.remove(devicePublicKeyId)
    }

    fun clear() {
        peers.clear()
    }

    fun mergePeerSighting(sighting: WiFiPeerSighting) {
        val deviceKeyId = sighting.devicePublicKeyId
        val existingPeer = peers[deviceKeyId]

        if (existingPeer != null) {
            synchronized(existingPeer) {
                val endpointExists = existingPeer.endpoints.any { it.address == sighting.endpoint.address && it.type == sighting.endpoint.type }
                if (!endpointExists) {
                    existingPeer.endpoints.add(sighting.endpoint)
                }
                existingPeer.lastSeenTimestamp = System.currentTimeMillis()
                existingPeer.rssi = sighting.rssi
            }
        } else {
            // Evict any stale peer that shares the same IP but a different node ID
            // (e.g. the remote device rebooted and got a new long-term key)
            val staleKeyId = peers.entries
                .firstOrNull { (_, peer) ->
                    peer.endpoints.any { ep -> ep.address == sighting.endpoint.address }
                }?.key
            if (staleKeyId != null && staleKeyId != deviceKeyId) {
                peers.remove(staleKeyId)
            }

            val newPeer = WiFiPeer(
                devicePublicKeyId = deviceKeyId,
                userPublicKeyId = null, // resolved post-handshake
                trustScore = 0.5f,     // baseline trust
                lastSeenTimestamp = System.currentTimeMillis(),
                failureCount = 0,
                isBlocked = false,
                endpoints = mutableListOf(sighting.endpoint),
                rssi = sighting.rssi
            )
            peers[deviceKeyId] = newPeer
        }
    }

    fun cleanExpiredPeers(expiryThresholdMs: Long = 300000L) { // 5 minutes default
        val now = System.currentTimeMillis()
        peers.entries.removeIf { (_, peer) ->
            (now - peer.lastSeenTimestamp) > expiryThresholdMs
        }
    }

    fun getActivePeersSortedByScore(): List<WiFiPeer> {
        val now = System.currentTimeMillis()
        return peers.values.filter { peer ->
            !peer.isBlocked && peer.cooldownUntil <= now
        }.sortedByDescending { it.rssi } // Simplified sorting, higher RSSI first
    }

    fun applyCooldown(peerId: String, cooldownDurationMs: Long) {
        peers[peerId]?.let { peer ->
            synchronized(peer) {
                peer.cooldownUntil = System.currentTimeMillis() + cooldownDurationMs
                peer.failureCount++
            }
        }
    }

    fun resetCooldown(peerId: String) {
        peers[peerId]?.let { peer ->
            synchronized(peer) {
                peer.cooldownUntil = 0L
                peer.failureCount = 0
            }
        }
    }
}

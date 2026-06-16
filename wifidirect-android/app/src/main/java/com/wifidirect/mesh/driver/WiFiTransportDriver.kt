package com.wifidirect.mesh.driver

import com.wifidirect.mesh.connection.WiFiConnectionManager
import com.wifidirect.mesh.connection.WiFiPeerTable
import com.wifidirect.mesh.discovery.WiFiDiscoveryManager
import com.wifidirect.mesh.models.*
import com.wifidirect.mesh.sync.WiFiTransferManager

interface WiFiTransportDriver {
    fun getModeType(): DiscoveryType
    fun isSupported(): Boolean
    fun startDiscovery(mode: DiscoveryMode)
    fun stopDiscovery()
    fun connectToPeer(peerId: String, endpoint: WiFiEndpoint): Boolean
    fun disconnectFromPeer(peerId: String)
}

class SameLanTransportDriver(
    private val discoveryManager: WiFiDiscoveryManager,
    private val connectionManager: WiFiConnectionManager,
    private val transferManager: WiFiTransferManager
) : WiFiTransportDriver {

    override fun getModeType(): DiscoveryType = DiscoveryType.SAME_LAN
    override fun isSupported(): Boolean = true // Always supported on standard IP networks

    override fun startDiscovery(mode: DiscoveryMode) {
        discoveryManager.startDiscovery(mode)
    }

    override fun stopDiscovery() {
        discoveryManager.stopDiscovery()
    }

    override fun connectToPeer(peerId: String, endpoint: WiFiEndpoint): Boolean {
        val session = connectionManager.connectToPeer(endpoint.address, endpoint.port, peerId)
        if (session != null) {
            transferManager.startListeningToSession(session)
            return true
        }
        return false
    }

    override fun disconnectFromPeer(peerId: String) {
        connectionManager.closeSession(peerId)
    }
}

class WiFiDirectTransportDriver(
    private val peerTable: WiFiPeerTable,
    private var isSupportedOverride: Boolean = false // dynamically set to true on Android P2P platforms
) : WiFiTransportDriver {

    private var isScanning = false

    override fun getModeType(): DiscoveryType = DiscoveryType.WIFI_DIRECT
    override fun isSupported(): Boolean = isSupportedOverride

    override fun startDiscovery(mode: DiscoveryMode) {
        if (!isSupported()) return
        isScanning = true
        // On Android, this calls WifiP2pManager.discoverPeers
    }

    override fun stopDiscovery() {
        isScanning = false
        // On Android, WifiP2pManager.stopPeerDiscovery
    }

    override fun connectToPeer(peerId: String, endpoint: WiFiEndpoint): Boolean {
        if (!isSupported()) return false
        // On Android, WifiP2pManager.connect with P2pConfig
        return true
    }

    override fun disconnectFromPeer(peerId: String) {
        // On Android, WifiP2pManager.removeGroup
    }
}

class HotspotClusterTransportDriver : WiFiTransportDriver {
    private var isAPActive = false

    override fun getModeType(): DiscoveryType = DiscoveryType.HOTSPOT
    override fun isSupported(): Boolean = true // Supported on most mobile devices

    override fun startDiscovery(mode: DiscoveryMode) {
        // station scanning or starting softAP
    }

    override fun stopDiscovery() {
        // stop AP or station scanning
    }

    override fun connectToPeer(peerId: String, endpoint: WiFiEndpoint): Boolean {
        // connect to SSID WPA3 password
        return true
    }

    override fun disconnectFromPeer(peerId: String) {
        // disconnect station
    }
}

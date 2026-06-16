package com.wifidirect.mesh.discovery

import com.wifidirect.mesh.connection.WiFiPeerTable
import com.wifidirect.mesh.models.*
import com.wifidirect.mesh.security.CryptoUtils
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class WiFiDiscoveryManager(
    private val peerTable: WiFiPeerTable,
    private val publicKeyId: String,
    private val tcpServerPort: Int = 53535,
    private val discoveryPort: Int = 53530,
    private val isServerReady: () -> Boolean = { true }
) {
    companion object {
        private val activeManagers = java.util.concurrent.CopyOnWriteArrayList<WiFiDiscoveryManager>()
    }

    private var udpSocket: DatagramSocket? = null
    private var isSearching = false
    private var discoveryIntervalMs = 15000L // default 15s

    fun startDiscovery(mode: DiscoveryMode) {
        if (isSearching) return
        isSearching = true
        activeManagers.add(this)

        discoveryIntervalMs = when (mode) {
            DiscoveryMode.SOS -> 3000L // 3s aggressive
            DiscoveryMode.ACTIVE -> 15000L // 15s standard
            DiscoveryMode.IDLE -> 300000L // 5m idle
            DiscoveryMode.LOW_POWER -> 60000L // 1m low power
            DiscoveryMode.CONGESTED -> 60000L // 1m throttled
        }

        try {
            udpSocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(java.net.InetSocketAddress(discoveryPort))
            }
        } catch (e: IOException) {
            System.err.println("Could not bind UDP discovery socket on port $discoveryPort: ${e.message}. Proceeding in local-only loopback mode.")
        }

        // Start Receiver thread
        thread(name = "WiFiDiscoveryReceiver") {
            val buffer = ByteArray(1024)
            while (isSearching) {
                val socket = udpSocket
                if (socket == null) {
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    processDiscoveryPacket(packet)
                } catch (e: IOException) {
                    if (isSearching) {
                        System.err.println("UDP Discovery receive error: ${e.message}")
                    }
                }
            }
        }

        // Start Broadcast Transmitter thread
        thread(name = "WiFiDiscoveryTransmitter") {
            while (isSearching) {
                try {
                    sendBroadcastAnnouncement()
                } catch (e: Exception) {
                    System.err.println("UDP Broadcast send error: ${e.message}")
                }
                try {
                    Thread.sleep(discoveryIntervalMs)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    fun stopDiscovery() {
        if (!isSearching) return
        isSearching = false
        activeManagers.remove(this)
        udpSocket?.close()
        udpSocket = null
    }

    fun setDiscoveryInterval(intervalMs: Long) {
        this.discoveryIntervalMs = intervalMs
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun sendBroadcastAnnouncement() {
        if (!isServerReady()) {
            return
        }
        val socket = udpSocket
        val pubKeyBytes = try {
            hexToBytes(publicKeyId)
        } catch (e: Exception) {
            CryptoUtils.sha256(publicKeyId.toByteArray())
        }
        
        // Packet payload format: [Version(1) | TCPPort(4) | PubKeyHash(32)]
        val byteBuffer = ByteBuffer.allocate(1 + 4 + 32)
        byteBuffer.put(1.toByte()) // protocol version
        byteBuffer.putInt(tcpServerPort)
        byteBuffer.put(pubKeyBytes)

        val data = byteBuffer.array()
        if (socket != null) {
            try {
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(data, data.size, broadcastAddr, discoveryPort)
                socket.send(packet)
            } catch (e: Exception) {
                // Ignore socket broadcast errors in restricted environments
            }
        }

        // In-memory loopback bypass for testing/same-JVM discovery
        for (manager in activeManagers) {
            if (manager !== this && manager.discoveryPort == this.discoveryPort) {
                try {
                    val packetBytes = data.copyOf()
                    val loopbackPacket = DatagramPacket(packetBytes, packetBytes.size, InetAddress.getByName("127.0.0.1"), discoveryPort)
                    manager.processDiscoveryPacket(loopbackPacket)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun processDiscoveryPacket(packet: DatagramPacket) {
        val data = packet.data
        if (packet.length < 37) return // Corrupted size

        val buffer = ByteBuffer.wrap(data, 0, packet.length)
        val version = buffer.get()
        if (version != 1.toByte()) return

        val tcpPort = buffer.getInt()
        val pubKeyHash = ByteArray(32)
        buffer.get(pubKeyHash)

        val discoveredPubKeyId = CryptoUtils.bytesToHex(pubKeyHash)
        
        // Skip self announcements
        if (discoveredPubKeyId == publicKeyId) return

        val senderIp = packet.address.hostAddress
        val endpoint = WiFiEndpoint(
            type = DiscoveryType.SAME_LAN,
            address = senderIp,
            port = tcpPort
        )

        val sighting = WiFiPeerSighting(
            sourceMode = DiscoveryType.SAME_LAN,
            endpoint = endpoint,
            devicePublicKeyId = discoveredPubKeyId,
            sightingTimestamp = System.currentTimeMillis(),
            rssi = -55 // same-lan baseline RSSI
        )

        peerTable.mergePeerSighting(sighting)
    }
}

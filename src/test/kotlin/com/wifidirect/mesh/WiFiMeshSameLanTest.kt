package com.wifidirect.mesh

import com.wifidirect.mesh.connection.*
import com.wifidirect.mesh.discovery.WiFiDiscoveryManager
import com.wifidirect.mesh.models.*
import com.wifidirect.mesh.policy.*
import com.wifidirect.mesh.security.*
import com.wifidirect.mesh.sync.WiFiTransferManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WiFiMeshSameLanTest {

    @Test
    fun testPolicyEngineEligibility() {
        val capabilityManager = WiFiCapabilityManager()
        val policyEngine = WiFiPolicyEngine(capabilityManager)

        // Scenario 1: Normal conditions
        val policyNormal = policyEngine.evaluateWiFiEligibility(
            batteryLevel = 80,
            isCharging = false,
            tempCelsius = 30.0f,
            userPolicy = UserWiFiPolicy.ALLOW_ALL,
            hasActiveSOS = false,
            isBackground = false
        )
        assertTrue(policyNormal.isEligible)
        assertFalse(policyNormal.isLowPowerMode)
        assertEquals(ThermalState.NORMAL, policyNormal.thermalState)

        // Scenario 2: Critical temperature without SOS
        val policyHot = policyEngine.evaluateWiFiEligibility(
            batteryLevel = 80,
            isCharging = false,
            tempCelsius = 52.0f,
            userPolicy = UserWiFiPolicy.ALLOW_ALL,
            hasActiveSOS = false,
            isBackground = false
        )
        assertFalse(policyHot.isEligible)
        assertEquals(ThermalState.CRITICAL, policyHot.thermalState)

        // Scenario 3: Critical temperature WITH SOS (must override)
        val policyHotSOS = policyEngine.evaluateWiFiEligibility(
            batteryLevel = 80,
            isCharging = false,
            tempCelsius = 52.0f,
            userPolicy = UserWiFiPolicy.ALLOW_ALL,
            hasActiveSOS = true,
            isBackground = false
        )
        assertTrue(policyHotSOS.isEligible)

        // Scenario 4: Low battery without SOS
        val policyLowBattery = policyEngine.evaluateWiFiEligibility(
            batteryLevel = 15,
            isCharging = false,
            tempCelsius = 30.0f,
            userPolicy = UserWiFiPolicy.ALLOW_ALL,
            hasActiveSOS = false,
            isBackground = false
        )
        assertFalse(policyLowBattery.isEligible)
        assertTrue(policyLowBattery.isLowPowerMode)
    }

    @Test
    fun testPeerTableDeduplication() {
        val peerTable = WiFiPeerTable()

        val sighting1 = WiFiPeerSighting(
            sourceMode = DiscoveryType.SAME_LAN,
            endpoint = WiFiEndpoint(DiscoveryType.SAME_LAN, "192.168.1.50", 53535),
            devicePublicKeyId = "device-A-key-hash",
            sightingTimestamp = System.currentTimeMillis(),
            rssi = -60
        )

        val sighting2 = WiFiPeerSighting(
            sourceMode = DiscoveryType.WIFI_DIRECT,
            endpoint = WiFiEndpoint(DiscoveryType.WIFI_DIRECT, "02:00:00:11:22:33", 53535),
            devicePublicKeyId = "device-A-key-hash", // Same key ID
            sightingTimestamp = System.currentTimeMillis() + 1000,
            rssi = -40
        )

        // Merge first sighting
        peerTable.mergePeerSighting(sighting1)
        assertEquals(1, peerTable.getAll().size)
        assertEquals(1, peerTable.get("device-A-key-hash")?.endpoints?.size)
        assertEquals(-60, peerTable.get("device-A-key-hash")?.rssi)

        // Merge second sighting (should deduplicate and merge endpoints)
        peerTable.mergePeerSighting(sighting2)
        assertEquals(1, peerTable.getAll().size) // Still only 1 peer
        val mergedPeer = peerTable.get("device-A-key-hash")
        assertNotNull(mergedPeer)
        assertEquals(2, mergedPeer?.endpoints?.size) // Has 2 endpoints now
        assertEquals(-40, mergedPeer?.rssi) // RSSI updated to latest/better
    }

    @Test
    fun testCryptoECDHAndAESGCM() {
        val securityA = WiFiSecurityManager()
        val securityB = WiFiSecurityManager()

        // Step 1: Ephemeral key generation
        val ephemeralA = CryptoUtils.generateECKeyPair()
        val ephemeralB = CryptoUtils.generateECKeyPair()

        // Step 2: Compute shared secret
        val secretA = CryptoUtils.computeECDH(ephemeralA.private, ephemeralB.public.encoded)
        val secretB = CryptoUtils.computeECDH(ephemeralB.private, ephemeralA.public.encoded)
        assertArrayEquals(secretA, secretB)

        // Step 3: Derive keys
        val (encKeyA, macKeyA) = CryptoUtils.hkdfDeriveKeys(secretA)
        val (encKeyB, macKeyB) = CryptoUtils.hkdfDeriveKeys(secretB)
        assertArrayEquals(encKeyA, encKeyB)
        assertArrayEquals(macKeyA, macKeyB)

        // Step 4: AES-GCM Encrypt & Decrypt
        val plaintext = "Hello Secure Mesh!".toByteArray()
        val nonce = ByteArray(12).apply { this[0] = 1 }
        val ciphertext = CryptoUtils.encryptAES_GCM(plaintext, encKeyA, nonce)
        val decrypted = CryptoUtils.decryptAES_GCM(ciphertext, encKeyB, nonce)
        assertArrayEquals(plaintext, decrypted)

        // Step 5: Signatures
        val sigPayload = "HandshakeData".toByteArray()
        val sig = CryptoUtils.signData(sigPayload, securityA.longTermKeyPair.private)
        val verified = CryptoUtils.verifySignature(sigPayload, sig, securityA.longTermKeyPair.public)
        assertTrue(verified)
    }

    @Test
    fun testLeaderElectionAndScoring() {
        val module = WiFiMeshModuleImpl()

        val candidate1 = WiFiLeaderCandidate("node-z-id", 0.8f, isCharging = true, hasGateway = true)
        val candidate2 = WiFiLeaderCandidate("node-a-id", 0.8f, isCharging = true, hasGateway = true)
        val candidate3 = WiFiLeaderCandidate("node-c-id", 0.5f, isCharging = false, hasGateway = false)

        val leaderScore1 = module.calculateWiFiLeaderScore(candidate1)
        val leaderScore3 = module.calculateWiFiLeaderScore(candidate3)
        assertTrue(leaderScore1 > leaderScore3)

        // Deterministic election: candidate1 and candidate2 are tied on scores, charging, and gateway.
        // It must resolve using lexicographical comparison of peer ID. "node-a-id" < "node-z-id"
        val winner = module.electClusterLeader(listOf(candidate1, candidate2, candidate3))
        assertEquals("node-a-id", winner)
    }

    @Test
    fun testRelayScoring() {
        val module = WiFiMeshModuleImpl()
        
        val peerA = WiFiPeer("peer-a", null, 0.9f, System.currentTimeMillis(), 0, false, mutableListOf(), -45, CongestionState.GREEN)
        val peerB = WiFiPeer("peer-b", null, 0.9f, System.currentTimeMillis(), 0, false, mutableListOf(), -45, CongestionState.RED)

        val candidates = module.rankWiFiPeersForConnection(listOf(peerA, peerB), SystemContext(80, false, 30f, UserWiFiPolicy.ALLOW_ALL, false, false))
        
        assertEquals(2, candidates.size)
        assertEquals("peer-a", candidates[0].peerId) // peer-a should rank first due to green congestion state vs red
        assertTrue(candidates[0].relayScore > candidates[1].relayScore)
    }

    @Test
    fun testMessageAndAckSerialization() {
        val payload = "decentralized payload data".toByteArray()
        val message = Message("msg-1234", QueuePriority.EMERGENCY, System.currentTimeMillis() + 60000, 8, payload)

        val serializedMsg = message.serialize()
        val deserializedMsg = Message.deserialize(serializedMsg)

        assertEquals(message.id, deserializedMsg.id)
        assertEquals(message.priority, deserializedMsg.priority)
        assertEquals(message.expiryTimestamp, deserializedMsg.expiryTimestamp)
        assertEquals(message.ttl, deserializedMsg.ttl)
        assertArrayEquals(message.payload, deserializedMsg.payload)

        // ACK Serialization
        val ack = WiFiClusterACKSummary("sender-node", listOf("msg-1", "msg-2", "msg-3"), System.currentTimeMillis())
        val serializedAck = ack.serialize()
        val deserializedAck = WiFiClusterACKSummary.deserialize(serializedAck)

        assertEquals(ack.senderId, deserializedAck.senderId)
        assertEquals(ack.messageIds, deserializedAck.messageIds)
        assertEquals(ack.timestamp, deserializedAck.timestamp)
    }

    @Test
    fun testCongestionTransitions() {
        val module = WiFiMeshModuleImpl()

        // Default state is green
        val state1 = module.updateCongestionState(0.2f)
        assertEquals(CongestionState.GREEN, state1.state)

        // Orange state
        val state2 = module.updateCongestionState(0.65f)
        assertEquals(CongestionState.ORANGE, state2.state)

        // Red state
        val state3 = module.updateCongestionState(0.9f)
        assertEquals(CongestionState.RED, state3.state)
    }

    @Test
    fun testGossipRebroadcastProbability() {
        val module = WiFiMeshModuleImpl()
        val context = SystemContext(80, false, 30f, UserWiFiPolicy.ALLOW_ALL, false, false)
        val message = Message("msg-1", QueuePriority.DIRECT_MESSAGE, System.currentTimeMillis() + 60000, 8, ByteArray(1))

        // Green Congestion State
        module.updateCongestionState(0.1f)
        val probGreen = module.calculateForwardProbability(message, context)

        // Red Congestion State
        module.updateCongestionState(0.9f)
        val probRed = module.calculateForwardProbability(message, context)

        // In Red congestion, direct messages (Priority 5) are paused, probability should be 0.0f
        assertEquals(0.0f, probRed)
        assertTrue(probGreen > 0.0f)
    }

    @Test
    fun testEndToEndSameLanDiscoveryAndHandshake() {
        // We will spin up two nodes locally on loopback interface: Node A and Node B
        val peerTableA = WiFiPeerTable()
        val peerTableB = WiFiPeerTable()

        val securityA = WiFiSecurityManager()
        val securityB = WiFiSecurityManager()

        val latch = CountDownLatch(1)
        var receivedPayload: String? = null

        // Initialize Connection Managers
        val connManagerA = WiFiConnectionManager(peerTableA, securityA)
        
        // Define transfer manager B first to handle incoming payload callbacks
        var transferManagerB: WiFiTransferManager? = null
        val connManagerB = WiFiConnectionManager(peerTableB, securityB) { session ->
            // Incoming session accepted on Node B
            transferManagerB?.startListeningToSession(session)
        }
        
        val transferManagerA = WiFiTransferManager(connManagerA, securityA)
        transferManagerB = WiFiTransferManager(connManagerB, securityB) { peerId, payload ->
            receivedPayload = String(payload)
            latch.countDown()
        }

        // Start TCP servers
        connManagerA.startServer(port = 54321)
        connManagerB.startServer(port = 54322)

        // Run same-LAN Discovery manager to discover each other
        val discoveryA = WiFiDiscoveryManager(peerTableA, securityA.longTermPublicKeyId, tcpServerPort = 54321, discoveryPort = 54301)
        val discoveryB = WiFiDiscoveryManager(peerTableB, securityB.longTermPublicKeyId, tcpServerPort = 54322, discoveryPort = 54301)

        try {
            discoveryA.startDiscovery(DiscoveryMode.SOS)
            discoveryB.startDiscovery(DiscoveryMode.SOS)

            // Wait up to 5 seconds for discovery sightings to occur
            var peerFound = false
            for (i in 1..50) {
                if (peerTableA.get(securityB.longTermPublicKeyId) != null) {
                    peerFound = true
                    break
                }
                Thread.sleep(100)
            }
            assertTrue(peerFound, "Node A should discover Node B via UDP announcement")

            val discoveredPeer = peerTableA.get(securityB.longTermPublicKeyId)!!
            val endpoint = discoveredPeer.endpoints.first()

            // Establish connection and perform handshake
            val sessionA = connManagerA.connectToPeer("127.0.0.1", endpoint.port, discoveredPeer.devicePublicKeyId)
            assertNotNull(sessionA, "Node A should connect and handshake with Node B successfully")

            transferManagerA.startListeningToSession(sessionA!!)

            // Send encrypted message from A to B
            val sent = transferManagerA.sendPayload(discoveredPeer.devicePublicKeyId, "Hello Decentralized World!".toByteArray())
            assertTrue(sent, "A should be able to send an encrypted payload to B")

            // Wait for B to receive and decrypt it
            val success = latch.await(5, TimeUnit.SECONDS)
            assertTrue(success, "Payload should be received by B within the timeout")
            assertEquals("Hello Decentralized World!", receivedPayload)

        } finally {
            discoveryA.stopDiscovery()
            discoveryB.stopDiscovery()
            connManagerA.stopServer()
            connManagerB.stopServer()
        }
    }
}

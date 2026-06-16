package com.wifidirect.mesh

import com.wifidirect.mesh.ack.WiFiAckManager
import com.wifidirect.mesh.audit.WiFiAuditEventType
import com.wifidirect.mesh.audit.WiFiAuditLogger
import com.wifidirect.mesh.audit.WiFiAuditSeverity
import com.wifidirect.mesh.broadcast.WiFiBroadcastController
import com.wifidirect.mesh.cluster.WiFiClusterManager
import com.wifidirect.mesh.cluster.WiFiLeaderElectionEngine
import com.wifidirect.mesh.connection.WiFiConnectionManager
import com.wifidirect.mesh.connection.WiFiPeerTable
import com.wifidirect.mesh.discovery.WiFiDiscoveryManager
import com.wifidirect.mesh.fallback.WiFiFallbackController
import com.wifidirect.mesh.models.*
import com.wifidirect.mesh.policy.WiFiCapabilityManager
import com.wifidirect.mesh.policy.WiFiPolicyEngine
import com.wifidirect.mesh.security.CryptoUtils
import com.wifidirect.mesh.security.WiFiSecurityManager
import com.wifidirect.mesh.sync.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.BitSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WiFiMeshFullTest — Comprehensive test suite covering all 30 spec test cases.
 *
 * Test groups:
 *  1.  Basic policy and eligibility
 *  2.  Peer discovery and deduplication
 *  3.  Security / handshake
 *  4.  Sync manager and Bloom filter
 *  5.  Chunked transfer engine
 *  6.  Priority scheduling
 *  7.  SOS priority
 *  8.  Broadcast storm suppression
 *  9.  ACK aggregation and storm prevention
 * 10.  Congestion state machine
 * 11.  Backpressure enforcement
 * 12.  Leader election
 * 13.  Fallback controller
 * 14.  Retry backoff
 * 15.  End-to-end same-LAN
 */
class WiFiMeshFullTest {

    // ================================================================
    // 1. Policy engine — eligibility
    // ================================================================

    @Test
    fun `test 01 - policy eligibility normal conditions`() {
        val engine = WiFiPolicyEngine(WiFiCapabilityManager())
        val policy = engine.evaluateWiFiEligibility(80, false, 30f, UserWiFiPolicy.ALLOW_ALL, false, false)
        assertTrue(policy.isEligible)
        assertEquals(ThermalState.NORMAL, policy.thermalState)
        assertFalse(policy.isLowPowerMode)
    }

    @Test
    fun `test 02 - policy blocked on thermal critical`() {
        val engine = WiFiPolicyEngine(WiFiCapabilityManager())
        val policy = engine.evaluateWiFiEligibility(80, false, 55f, UserWiFiPolicy.ALLOW_ALL, false, false)
        assertFalse(policy.isEligible)
        assertEquals(ThermalState.CRITICAL, policy.thermalState)
    }

    @Test
    fun `test 03 - policy SOS overrides thermal critical`() {
        val engine = WiFiPolicyEngine(WiFiCapabilityManager())
        val policy = engine.evaluateWiFiEligibility(80, false, 55f, UserWiFiPolicy.ALLOW_ALL, true, false)
        assertTrue(policy.isEligible, "SOS must override thermal critical block")
    }

    @Test
    fun `test 04 - policy blocked on low battery`() {
        val engine = WiFiPolicyEngine(WiFiCapabilityManager())
        val policy = engine.evaluateWiFiEligibility(15, false, 30f, UserWiFiPolicy.ALLOW_ALL, false, false)
        assertFalse(policy.isEligible)
        assertTrue(policy.isLowPowerMode)
    }

    @Test
    fun `test 05 - thermal stops non-SOS wifi`() {
        val engine = WiFiPolicyEngine(WiFiCapabilityManager())
        val lowBatteryNoSOS = engine.evaluateWiFiEligibility(3, false, 30f, UserWiFiPolicy.ALLOW_ALL, false, false)
        assertFalse(lowBatteryNoSOS.isEligible, "Battery < 5% without SOS should block Wi-Fi")
        val lowBatterySOS = engine.evaluateWiFiEligibility(7, false, 30f, UserWiFiPolicy.ALLOW_ALL, true, false)
        assertTrue(lowBatterySOS.isEligible, "SOS with 7% battery should still be allowed")
    }

    // ================================================================
    // 2. Peer discovery and deduplication
    // ================================================================

    @Test
    fun `test 06 - peer deduplication across multiple modes`() {
        val table = WiFiPeerTable()

        val sighting1 = WiFiPeerSighting(
            sourceMode          = DiscoveryType.SAME_LAN,
            endpoint            = WiFiEndpoint(DiscoveryType.SAME_LAN, "192.168.1.100", 53535),
            devicePublicKeyId   = "peer-key-abc123",
            sightingTimestamp   = System.currentTimeMillis(),
            rssi                = -60
        )
        val sighting2 = WiFiPeerSighting(
            sourceMode          = DiscoveryType.WIFI_DIRECT,
            endpoint            = WiFiEndpoint(DiscoveryType.WIFI_DIRECT, "AA:BB:CC:DD:EE:FF", 53535),
            devicePublicKeyId   = "peer-key-abc123",  // Same key — same peer!
            sightingTimestamp   = System.currentTimeMillis() + 1000,
            rssi                = -40
        )

        table.mergePeerSighting(sighting1)
        assertEquals(1, table.getAll().size)
        assertEquals(1, table.get("peer-key-abc123")?.endpoints?.size)

        table.mergePeerSighting(sighting2)
        assertEquals(1, table.getAll().size, "Must remain 1 peer after second sighting")
        assertEquals(2, table.get("peer-key-abc123")?.endpoints?.size, "Should have 2 endpoints")
        assertEquals(-40, table.get("peer-key-abc123")?.rssi, "RSSI should update to latest")
    }

    @Test
    fun `test 07 - peer identity is key-based not IP-based`() {
        val table = WiFiPeerTable()
        val keyId = "device-pubkey-hash-xyz"

        // Same peer, IP changes
        table.mergePeerSighting(WiFiPeerSighting(DiscoveryType.SAME_LAN,
            WiFiEndpoint(DiscoveryType.SAME_LAN, "10.0.0.5", 53535), keyId, System.currentTimeMillis(), -55))
        table.mergePeerSighting(WiFiPeerSighting(DiscoveryType.SAME_LAN,
            WiFiEndpoint(DiscoveryType.SAME_LAN, "10.0.0.99", 53535), keyId, System.currentTimeMillis() + 5000, -50))

        assertEquals(1, table.getAll().size, "IP change must not create a duplicate peer entry")
        assertEquals(keyId, table.getAll().first().devicePublicKeyId)
    }

    // ================================================================
    // 3. Security / cryptography
    // ================================================================

    @Test
    fun `test 08 - ECDH shared secret matches both sides`() {
        val kpA = CryptoUtils.generateECKeyPair()
        val kpB = CryptoUtils.generateECKeyPair()
        val secretA = CryptoUtils.computeECDH(kpA.private, kpB.public.encoded)
        val secretB = CryptoUtils.computeECDH(kpB.private, kpA.public.encoded)
        assertArrayEquals(secretA, secretB, "ECDH shared secrets must be equal on both sides")
    }

    @Test
    fun `test 09 - AES-GCM encrypt-decrypt roundtrip`() {
        val key = CryptoUtils.hkdfDeriveKeys(ByteArray(32)).first
        val nonce = ByteArray(12).apply { this[0] = 1 }
        val plaintext = "SOS: Emergency at location X".toByteArray()
        val cipher = CryptoUtils.encryptAES_GCM(plaintext, key, nonce)
        val decrypted = CryptoUtils.decryptAES_GCM(cipher, key, nonce)
        assertArrayEquals(plaintext, decrypted)
        assertFalse(cipher.contentEquals(plaintext), "Ciphertext must differ from plaintext")
    }

    @Test
    fun `test 10 - ECDSA sign and verify`() {
        val sec = WiFiSecurityManager()
        val data = "Handshake:ephemeral-pub-key-data".toByteArray()
        val sig = CryptoUtils.signData(data, sec.longTermKeyPair.private)
        assertTrue(CryptoUtils.verifySignature(data, sig, sec.longTermKeyPair.public))
        // Tampered data must fail
        val tampered = data.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(CryptoUtils.verifySignature(tampered, sig, sec.longTermKeyPair.public))
    }

    // ================================================================
    // 4. Sync manager / Bloom filter
    // ================================================================

    @Test
    fun `test 11 - bloom filter avoids full sync storm`() {
        val syncA = WiFiSyncManager("node-A")
        val syncB = WiFiSyncManager("node-B")

        // Register 100 messages on A
        val now = System.currentTimeMillis()
        for (i in 1..100) {
            syncA.registerMessage(WiFiSyncManager.MessageMeta("msg-$i", null, 5, now + 60_000, 100))
        }

        // B has none
        val summaryA = syncA.buildLocalSummary()
        val summaryB = syncB.buildLocalSummary()

        // A computes what B is missing → expects all 100
        val missingForB = syncA.computeMissingForPeer(summaryB)
        assertEquals(30, missingForB.size, "Should return up to 30 items per batch")
        assertTrue(missingForB.isNotEmpty())

        // B computes what A is missing → expects 0 (B has nothing to offer)
        val missingForA = syncB.computeMissingForPeer(summaryA)
        assertTrue(missingForA.isEmpty(), "B has nothing A doesn't know about")
    }

    @Test
    fun `test 12 - bloom filter no duplicate sync`() {
        val sync = WiFiSyncManager("node-X")
        val now = System.currentTimeMillis()
        sync.registerMessage(WiFiSyncManager.MessageMeta("msg-known", null, 5, now + 60_000, 50))

        val localSummary = sync.buildLocalSummary()

        // Peer sends us a summary that includes msg-known → we should not re-send it
        val peerHas = sync.peerLikelyHas(localSummary.bloomFilterBits, "msg-known")
        assertTrue(peerHas, "Node should detect its own message is in the bloom filter")
    }

    // ================================================================
    // 5. Chunked transfer engine
    // ================================================================

    @Test
    fun `test 13 - chunked transfer resume after interruption`() {
        val engine = WiFiChunkTransferEngine(defaultChunkSizeBytes = 10)
        val payload = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toByteArray() // 36 bytes → 4 chunks of 10
        val expiresAt = System.currentTimeMillis() + 60_000L

        val manifest = engine.prepareOutgoing("bundle-1", "msg-1", payload, 7, expiresAt, "peer-X")
        assertEquals(4, manifest.chunkCount)

        engine.initIncoming(manifest, "peer-X")

        // Build and deliver chunks 0, 1, 3 directly — skip chunk 2 (simulate network interruption)
        for (i in listOf(0, 1, 3)) {
            val start = i * 10
            val end = minOf(start + 10, payload.size)
            val chunkBytes = payload.copyOfRange(start, end)
            val hash = CryptoUtils.bytesToHex(CryptoUtils.sha256(chunkBytes))
            val chunk = WiFiChunk("bundle-1", i, chunkBytes, hash)
            // Simulate sender marking it acked
            engine.markChunkAcked("bundle-1", i)
            // Simulate receiver accepting it
            val accepted = engine.receiveChunk(chunk)
            assertTrue(accepted, "Chunk $i should be accepted")
        }

        assertFalse(engine.isIncomingComplete("bundle-1"), "Bundle should not be complete with chunk 2 missing")

        // Verify the outgoing state shows chunk 2 as missing (not acked)
        val missingChunks = engine.getMissingChunks("bundle-1")!!
        assertTrue(missingChunks.get(2), "Chunk 2 should be flagged as missing in the outgoing ack-map")

        // Build and deliver chunk 2 (resume)
        val start = 2 * 10
        val end = minOf(start + 10, payload.size)
        val chunkBytes = payload.copyOfRange(start, end)
        val hash = CryptoUtils.bytesToHex(CryptoUtils.sha256(chunkBytes))
        val chunk2 = WiFiChunk("bundle-1", 2, chunkBytes, hash)

        val accepted = engine.receiveChunk(chunk2)
        assertTrue(accepted)
        assertTrue(engine.isIncomingComplete("bundle-1"), "Bundle must be complete after receiving chunk 2")

        val reassembled = engine.tryReassemble("bundle-1")
        assertNotNull(reassembled)
        assertArrayEquals(payload, reassembled)
    }

    @Test
    fun `test 14 - invalid chunk hash is rejected`() {
        val engine = WiFiChunkTransferEngine(defaultChunkSizeBytes = 10)
        val payload = "0123456789".toByteArray()
        val expiresAt = System.currentTimeMillis() + 60_000L
        val manifest = engine.prepareOutgoing("bundle-bad", "msg-bad", payload, 7, expiresAt, "peer-Y")
        engine.initIncoming(manifest, "peer-Y")

        val corruptChunk = WiFiChunk("bundle-bad", 0, payload, "BADHASH")
        val accepted = engine.receiveChunk(corruptChunk)
        assertFalse(accepted, "Chunk with invalid hash must be rejected")
    }

    @Test
    fun `test 15 - bulk transfer pauses when SOS arrives`() {
        val engine = WiFiChunkTransferEngine(defaultChunkSizeBytes = 32)
        val payload = ByteArray(320) { it.toByte() }
        val expiresAt = System.currentTimeMillis() + 60_000L
        engine.prepareOutgoing("bundle-bulk", "msg-bulk", payload, QueuePriority.BULK_SYNC.value, expiresAt, "peer-Z")

        // Simulate SOS arriving — pause bulk
        engine.pauseTransfersByPriority(QueuePriority.BULK_SYNC.value)

        // nextChunkToSend should return null since bulk is paused
        val chunk = engine.nextChunkToSend("bundle-bulk")
        assertNull(chunk, "Bulk transfer must be paused when SOS arrives")

        // Resume
        engine.resumeTransfer("bundle-bulk")
        val resumedChunk = engine.nextChunkToSend("bundle-bulk")
        assertNotNull(resumedChunk, "Transfer should resume after SOS completes")
    }

    // ================================================================
    // 6. Priority scheduler
    // ================================================================

    @Test
    fun `test 16 - SOS dequeued before bulk`() {
        val scheduler = WiFiScheduler()
        val congestion = WiFiCongestionManager()
        val expiresAt = System.currentTimeMillis() + 60_000L

        val bulkMsg = Message("bulk-1", QueuePriority.BULK_SYNC, expiresAt, 5, ByteArray(1))
        val sosMsg  = Message("sos-1",  QueuePriority.SOS,       expiresAt, 5, ByteArray(1))

        scheduler.enqueue(bulkMsg)
        scheduler.enqueue(sosMsg)

        val first = scheduler.dequeue(congestion)
        assertEquals("sos-1", first?.id, "SOS must be dequeued before bulk")
    }

    @Test
    fun `test 17 - expired messages are pruned`() {
        val scheduler = WiFiScheduler()
        val congestion = WiFiCongestionManager()
        val expired = Message("exp-1", QueuePriority.BULK_SYNC, System.currentTimeMillis() - 1000L, 5, ByteArray(1))
        scheduler.enqueue(expired)
        // Enqueue will reject already-expired messages
        assertEquals(0, scheduler.totalSize())
    }

    @Test
    fun `test 18 - RED congestion only allows SOS and control`() {
        val scheduler = WiFiScheduler()
        val congestion = WiFiCongestionManager()
        congestion.updateCongestionState(0.9f)  // RED

        val expiresAt = System.currentTimeMillis() + 60_000L
        scheduler.enqueue(Message("direct-1", QueuePriority.DIRECT_MESSAGE, expiresAt, 5, ByteArray(1)))
        scheduler.enqueue(Message("sos-1",    QueuePriority.SOS,            expiresAt, 5, ByteArray(1)))
        scheduler.enqueue(Message("ctrl-1",   QueuePriority.CONTROL,        expiresAt, 5, ByteArray(1)))

        val msgs = mutableListOf<Message>()
        repeat(5) { scheduler.dequeue(congestion)?.let { msgs.add(it) } }

        assertEquals(2, msgs.size, "Only SOS and CONTROL should dequeue in RED congestion")
        assertTrue(msgs.all { it.priority == QueuePriority.SOS || it.priority == QueuePriority.CONTROL })
    }

    // ================================================================
    // 7. SOS over Wi-Fi
    // ================================================================

    @Test
    fun `test 19 - SOS over wifi records failure when no peers`() {
        val module = WiFiMeshModuleImpl(tcpPort = 55001, discoveryPort = 55002)
        try {
            val sosMsg = Message("sos-test", QueuePriority.SOS, System.currentTimeMillis() + 30_000L, 3, "SOS_PAYLOAD".toByteArray())
            val result = module.attemptSOSOverWifi(sosMsg)
            assertFalse(result, "SOS should fail when no peers are available")
            assertTrue(module.fallbackController.hasAnyFailure())
            assertTrue(module.shouldFallbackToBLE() || module.shouldEscalateToSMS())
        } finally {
            module.shutdown()
        }
    }

    // ================================================================
    // 8. Broadcast storm suppression
    // ================================================================

    @Test
    fun `test 20 - seen message is not rebroadcast`() {
        val controller = WiFiBroadcastController()
        val expiresAt = System.currentTimeMillis() + 60_000L
        val msg = Message("msg-broadcast-1", QueuePriority.DIRECT_MESSAGE, expiresAt, 5, ByteArray(1))

        val first = controller.shouldRebroadcast(msg, CongestionState.GREEN, 3)
        assertTrue(first, "First sight of message should allow rebroadcast (probable)")

        // Mark as seen internally by calling shouldRebroadcast again (it marks on first accept)
        val second = controller.shouldRebroadcast(msg, CongestionState.GREEN, 3)
        assertFalse(second, "Second sight of same message must be suppressed")
    }

    @Test
    fun `test 21 - RED congestion suppresses non-SOS broadcasts`() {
        val controller = WiFiBroadcastController()
        val expiresAt = System.currentTimeMillis() + 60_000L
        val bulkMsg = Message("bulk-broadcast", QueuePriority.BULK_SYNC, expiresAt, 5, ByteArray(1))
        val result = controller.shouldRebroadcast(bulkMsg, CongestionState.RED, 20)
        assertFalse(result, "BULK must not be rebroadcast under RED congestion")
    }

    @Test
    fun `test 22 - SOS is always forwarded even in RED`() {
        val controller = WiFiBroadcastController()
        val expiresAt = System.currentTimeMillis() + 60_000L
        // Run multiple SOS messages since shouldRebroadcast is probabilistic but SOS in RED
        // has modifier 1.0 and base probability = TARGET/max(neighbor,1)
        val sosMsg = Message("sos-forward-1", QueuePriority.SOS, expiresAt, 5, ByteArray(1))
        // With 1 neighbor, base = 3/1 = 1.0, modifier = 1.0 → always forward
        val result = controller.shouldRebroadcast(sosMsg, CongestionState.RED, 1)
        assertTrue(result, "SOS must be forwardable even in RED congestion")
    }

    @Test
    fun `test 23 - TTL exhausted message is dropped`() {
        val controller = WiFiBroadcastController()
        val expiredTtl = Message("msg-ttl0", QueuePriority.DIRECT_MESSAGE,
            System.currentTimeMillis() + 60_000L, 0, ByteArray(1))
        assertFalse(controller.shouldRebroadcast(expiredTtl, CongestionState.GREEN, 5))
    }

    @Test
    fun `test 24 - expired message is dropped`() {
        val controller = WiFiBroadcastController()
        val expiredMsg = Message("msg-exp", QueuePriority.DIRECT_MESSAGE,
            System.currentTimeMillis() - 1000L, 5, ByteArray(1))
        assertFalse(controller.shouldRebroadcast(expiredMsg, CongestionState.GREEN, 5))
    }

    @Test
    fun `test 25 - forward probability decreases with more neighbors`() {
        val controller = WiFiBroadcastController()
        val msg = Message("msg-prob", QueuePriority.DIRECT_MESSAGE,
            System.currentTimeMillis() + 60_000L, 5, ByteArray(1))
        val prob5   = controller.calculateForwardProbability(msg, CongestionState.GREEN, 5)
        val prob50  = controller.calculateForwardProbability(msg, CongestionState.GREEN, 50)
        assertTrue(prob5 > prob50, "Forward probability should decrease with more neighbors")
    }

    // ================================================================
    // 9. ACK manager
    // ================================================================

    @Test
    fun `test 26 - ACK aggregation prevents storm`() {
        val ackMgr = WiFiAckManager("node-test")

        // Record many individual receipts of broadcast
        for (i in 1..20) {
            ackMgr.recordReceipt("msg-broadcast", QueuePriority.EMERGENCY, "peer-$i")
        }

        val summary = ackMgr.buildAggregatedAck(listOf("msg-broadcast"))
        assertNotNull(summary, "Cluster ACK summary should be built")
        assertEquals(listOf("msg-broadcast"), summary?.messageIds)

        // Second call should be null (already dispatched, deduplication)
        val second = ackMgr.buildAggregatedAck(listOf("msg-broadcast"))
        assertNull(second, "Second ACK summary for same message should be suppressed")
    }

    @Test
    fun `test 27 - direct message gets individual ACK`() {
        val ackMgr = WiFiAckManager("node-direct")
        val shouldAck = ackMgr.recordReceipt("msg-direct", QueuePriority.DIRECT_MESSAGE, "peer-A")
        assertTrue(shouldAck, "Direct messages should generate individual ACKs")
    }

    @Test
    fun `test 28 - gateway confirmation suppresses individual ACKs`() {
        val ackMgr = WiFiAckManager("node-gw")
        ackMgr.onGatewayConfirmed("msg-gw-test")
        assertTrue(ackMgr.isAckSuppressed("msg-gw-test"), "Individual ACK must be suppressed after gateway confirms")
    }

    @Test
    fun `test 29 - duplicate ACK summary is ignored`() {
        val ackMgr = WiFiAckManager("node-dup")
        val summary = WiFiClusterACKSummary("sender-A", listOf("msg-1", "msg-2"), System.currentTimeMillis())
        val first  = ackMgr.processAckSummary(summary)
        val second = ackMgr.processAckSummary(summary)  // Same timestamp = duplicate
        assertEquals(2, first.size)
        assertEquals(0, second.size, "Duplicate ACK summary must be ignored")
    }

    // ================================================================
    // 10. Congestion state machine
    // ================================================================

    @Test
    fun `test 30 - congestion transitions GREEN to RED`() {
        val cm = WiFiCongestionManager()

        val green = cm.updateCongestionState(0.2f)
        assertEquals(CongestionState.GREEN, green.state)

        val yellow = cm.updateCongestionState(0.40f)
        assertEquals(CongestionState.YELLOW, yellow.state)

        val orange = cm.updateCongestionState(0.65f)
        assertEquals(CongestionState.ORANGE, orange.state)

        val red = cm.updateCongestionState(0.90f)
        assertEquals(CongestionState.RED, red.state)
    }

    @Test
    fun `test 31 - discovery interval slows under congestion`() {
        val cm = WiFiCongestionManager()
        cm.updateCongestionState(0.1f)
        val greenInterval = cm.recommendedDiscoveryIntervalMs()

        cm.updateCongestionState(0.50f)
        val yellowInterval = cm.recommendedDiscoveryIntervalMs()

        cm.updateCongestionState(0.70f)
        val orangeInterval = cm.recommendedDiscoveryIntervalMs()

        assertTrue(greenInterval < yellowInterval, "Discovery must slow from GREEN to YELLOW")
        assertTrue(yellowInterval < orangeInterval, "Discovery must slow from YELLOW to ORANGE")
    }

    @Test
    fun `test 32 - bulk pauses under ORANGE and RED`() {
        val cm = WiFiCongestionManager()
        cm.updateCongestionState(0.1f)
        assertTrue(cm.isBulkAllowed(), "Bulk should be allowed in GREEN")

        cm.updateCongestionState(0.65f)
        assertFalse(cm.isBulkAllowed(), "Bulk should be paused in ORANGE")
    }

    @Test
    fun `test 33 - congestion score calculation with 8 factors`() {
        val cm = WiFiCongestionManager()
        val metrics = WiFiCongestionMetrics(
            neighborCount     = 40,   // High density
            activeConnections = 4,
            queueSize         = 150,  // High queue
            duplicateRate     = 0.5f,
            retryRate         = 0.4f,
            latencyIncreaseMs = 800L
        )
        val score = cm.calculateCongestionScore(metrics, leaderLoad = 0.9f, channelBusyRatio = 0.8f)
        assertTrue(score > 0.5f, "Heavy congestion metrics should produce high score (got $score)")
    }

    // ================================================================
    // 11. Backpressure
    // ================================================================

    @Test
    fun `test 34 - backpressure rejects bulk in ORANGE`() {
        val cm = WiFiCongestionManager()
        cm.updateCongestionState(0.70f)  // ORANGE

        val bp = cm.buildBackpressureState("node-bp", 0.8f, 2, 2)
        assertFalse(bp.acceptingBulk)
        assertTrue(bp.acceptingSos)
        assertTrue(bp.acceptingControl)
        assertFalse(bp.acceptingGroupMessages, "Group messages must be refused in ORANGE")
    }

    @Test
    fun `test 35 - peer backpressure state is honored`() {
        val module = WiFiMeshModuleImpl(tcpPort = 55010, discoveryPort = 55011)
        val peerTable = module.peerTable

        // Add a peer to the table
        val peer = WiFiPeer(
            devicePublicKeyId = "peer-overloaded",
            userPublicKeyId   = null,
            trustScore        = 0.9f,
            lastSeenTimestamp = System.currentTimeMillis(),
            failureCount      = 0,
            isBlocked         = false,
            endpoints         = mutableListOf(WiFiEndpoint(DiscoveryType.SAME_LAN, "192.168.1.5", 53535)),
            acceptingBulk     = true
        )
        peerTable.insert(peer)

        val bpState = WiFiBackpressureState(
            nodeId                  = "peer-overloaded",
            congestionState         = CongestionState.RED,
            queuePressure           = 0.95f,
            acceptingSos            = true,
            acceptingControl        = true,
            acceptingDirectMessages = false,
            acceptingGroupMessages  = false,
            acceptingBulk           = false,
            recommendedRetryAfterMs = 30_000L,
            maxIncomingSessions     = 2,
            currentIncomingSessions = 2
        )

        module.handleBackpressureFromPeer("peer-overloaded", bpState)

        val updatedPeer = peerTable.get("peer-overloaded")
        assertNotNull(updatedPeer)
        assertFalse(updatedPeer!!.acceptingBulk, "Peer must respect backpressure: no bulk accepted")
        assertEquals(CongestionState.RED, updatedPeer.congestionState)
        module.shutdown()
    }

    // ================================================================
    // 12. Leader election
    // ================================================================

    @Test
    fun `test 36 - charging device wins election`() {
        val engine = WiFiLeaderElectionEngine()

        val chargingInput = WiFiLeaderElectionEngine.LeaderInput(
            peerId = "node-charging", batteryFraction = 0.9f, isCharging = true,
            connectivityStability = 0.9f, deviceCapabilityScore = 0.8f, hasGateway = true,
            mobilityScore = 0.95f, historicalUptime = 0.9f, congestionScore = 0.1f,
            trustScore = 1.0f, thermalState = ThermalState.NORMAL,
            recentFailureFraction = 0.0f, storageFraction = 0.9f
        )
        val lowBatInput = WiFiLeaderElectionEngine.LeaderInput(
            peerId = "node-low-bat", batteryFraction = 0.2f, isCharging = false,
            connectivityStability = 0.5f, deviceCapabilityScore = 0.5f, hasGateway = false,
            mobilityScore = 0.6f, historicalUptime = 0.6f, congestionScore = 0.5f,
            trustScore = 0.5f, thermalState = ThermalState.HIGH,
            recentFailureFraction = 0.2f, storageFraction = 0.4f
        )

        val scoreA = engine.calculateLeaderScore(chargingInput)
        val scoreB = engine.calculateLeaderScore(lowBatInput)
        assertTrue(scoreA > scoreB, "Charging high-battery device must score higher (A=$scoreA, B=$scoreB)")

        val winner = engine.electLeader(listOf(Pair(chargingInput, scoreA), Pair(lowBatInput, scoreB)))
        assertEquals("node-charging", winner)
    }

    @Test
    fun `test 37 - leader election is deterministic with tie-breaker`() {
        val module = WiFiMeshModuleImpl(tcpPort = 55020, discoveryPort = 55021)
        try {
            val c1 = WiFiLeaderCandidate("node-z-id", 0.8f, isCharging = true, hasGateway = true)
            val c2 = WiFiLeaderCandidate("node-a-id", 0.8f, isCharging = true, hasGateway = true)
            val c3 = WiFiLeaderCandidate("node-m-id", 0.8f, isCharging = true, hasGateway = true)

            // Run election 10 times — must always be same winner
            val winners = (1..10).map { module.electClusterLeader(listOf(c1, c2, c3)) }.toSet()
            assertEquals(1, winners.size, "Election must be deterministic")
            assertEquals("node-a-id", winners.first(), "Lexicographically smallest key ID wins the tie-break")
        } finally {
            module.shutdown()
        }
    }

    @Test
    fun `test 38 - handoff triggered on low battery`() {
        val engine = WiFiLeaderElectionEngine()
        val reason = engine.shouldHandoff(
            leaderBattery        = 0.20f,
            isCharging           = false,
            thermalState         = ThermalState.NORMAL,
            queuePressure        = 0.3f,
            bestCandidateScore   = 0.6f,
            currentLeaderScore   = 0.5f
        )
        assertEquals(WiFiLeaderElectionEngine.HandoffReason.LOW_BATTERY, reason)
    }

    @Test
    fun `test 39 - handoff triggered on thermal critical`() {
        val engine = WiFiLeaderElectionEngine()
        val reason = engine.shouldHandoff(0.8f, true, ThermalState.CRITICAL, 0.2f, 0.6f, 0.7f)
        assertEquals(WiFiLeaderElectionEngine.HandoffReason.THERMAL_CRITICAL, reason)
    }

    // ================================================================
    // 13. Fallback controller
    // ================================================================

    @Test
    fun `test 40 - fallback reports most critical reason`() {
        val fc = WiFiFallbackController()
        fc.recordFailure(WiFiFailureReason.PEER_COOLDOWN_ACTIVE)
        fc.recordFailure(WiFiFailureReason.NO_PEERS_FOUND)
        fc.recordFailure(WiFiFailureReason.THERMAL_CRITICAL)

        val reported = fc.reportToRouter()
        assertEquals(WiFiFailureReason.THERMAL_CRITICAL, reported,
            "Most critical failure reason should be reported")
    }

    @Test
    fun `test 41 - fallback recommends BLE for NO_PEERS_FOUND`() {
        val fc = WiFiFallbackController()
        fc.recordFailure(WiFiFailureReason.NO_PEERS_FOUND)
        assertTrue(fc.shouldFallbackToBLE())
        assertFalse(fc.shouldEscalateToSMS())
    }

    @Test
    fun `test 42 - fallback recommends SMS for THERMAL_CRITICAL`() {
        val fc = WiFiFallbackController()
        fc.recordFailure(WiFiFailureReason.THERMAL_CRITICAL)
        assertTrue(fc.shouldEscalateToSMS())
    }

    @Test
    fun `test 43 - recovery clears soft failures`() {
        val fc = WiFiFallbackController()
        fc.recordFailure(WiFiFailureReason.CONNECTION_FAILED)
        fc.recordFailure(WiFiFailureReason.NO_PEERS_FOUND)
        assertTrue(fc.hasAnyFailure())

        fc.recordRecovery()
        assertFalse(fc.hasAnyFailure(), "Recovery should clear soft failures")
    }

    // ================================================================
    // 14. Retry backoff
    // ================================================================

    @Test
    fun `test 44 - exponential backoff increases delay`() {
        val delay0 = WiFiRetryRecord.computeNextDelay(0, jitterMs = 0)
        val delay1 = WiFiRetryRecord.computeNextDelay(1, jitterMs = 0)
        val delay2 = WiFiRetryRecord.computeNextDelay(2, jitterMs = 0)
        val delay3 = WiFiRetryRecord.computeNextDelay(3, jitterMs = 0)

        assertTrue(delay0 < delay1, "Delay must increase with failure count")
        assertTrue(delay1 < delay2)
        assertTrue(delay2 < delay3)
    }

    @Test
    fun `test 45 - backoff caps at max delay`() {
        val maxDelay = WiFiRetryRecord.computeNextDelay(100, jitterMs = 0)
        assertTrue(maxDelay <= 600_001L, "Backoff must cap at max delay (600s + jitter)")
    }

    @Test
    fun `test 46 - peer cooldown applied on failure`() {
        val module = WiFiMeshModuleImpl(tcpPort = 55030, discoveryPort = 55031)
        val table = module.peerTable

        table.mergePeerSighting(WiFiPeerSighting(
            DiscoveryType.SAME_LAN,
            WiFiEndpoint(DiscoveryType.SAME_LAN, "1.2.3.4", 53535),
            "peer-cooldown-test", System.currentTimeMillis(), -55
        ))

        module.handleWiFiFailure("peer-cooldown-test", WiFiFailureReason.CONNECTION_FAILED)

        val peer = table.get("peer-cooldown-test")
        assertNotNull(peer)
        assertTrue(peer!!.cooldownUntil > System.currentTimeMillis(),
            "Peer should be in cooldown after failure")
        module.shutdown()
    }

    // ================================================================
    // 15. End-to-end same-LAN
    // ================================================================

    @Test
    fun `test 47 - end-to-end same-LAN discovery handshake and transfer`() {
        val secA = WiFiSecurityManager()
        val secB = WiFiSecurityManager()

        val tableA = WiFiPeerTable()
        val tableB = WiFiPeerTable()

        val latch = CountDownLatch(1)
        var received: String? = null

        val connA = WiFiConnectionManager(tableA, secA)

        var transferB: WiFiTransferManager? = null
        val connB = WiFiConnectionManager(tableB, secB) { session ->
            transferB?.startListeningToSession(session)
        }

        val transferA = WiFiTransferManager(connA, secA)
        transferB = WiFiTransferManager(connB, secB) { _, payload ->
            received = String(payload)
            latch.countDown()
        }

        connA.startServer(55100)
        connB.startServer(55101)

        val discA = WiFiDiscoveryManager(tableA, secA.longTermPublicKeyId, 55100, 55200)
        val discB = WiFiDiscoveryManager(tableB, secB.longTermPublicKeyId, 55101, 55200)

        try {
            discA.startDiscovery(DiscoveryMode.SOS)
            discB.startDiscovery(DiscoveryMode.SOS)

            // Wait for discovery — up to 15 seconds
            var found = false
            for (i in 1..150) {
                if (tableA.get(secB.longTermPublicKeyId) != null) { found = true; break }
                Thread.sleep(100)
            }
            assertTrue(found, "Node A must discover Node B via UDP broadcast")

            val peer = tableA.get(secB.longTermPublicKeyId)!!
            val session = connA.connectToPeer("127.0.0.1", peer.endpoints.first().port, peer.devicePublicKeyId)
            assertNotNull(session, "Handshake must succeed")

            transferA.startListeningToSession(session!!)
            val sent = transferA.sendPayload(peer.devicePublicKeyId, "MeshPayload:Hello!".toByteArray())
            assertTrue(sent, "Payload must be sent successfully")

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Payload must be received by node B")
            assertEquals("MeshPayload:Hello!", received)
        } finally {
            discA.stopDiscovery(); discB.stopDiscovery()
            connA.stopServer(); connB.stopServer()
        }
    }

    @Test
    fun `test 48 - cluster manager sync slots are deterministic`() {
        val electionEngine = WiFiLeaderElectionEngine()
        val cm = WiFiClusterManager("leader-node", electionEngine, WiFiAuditLogger())

        val slotA = cm.assignSyncSlot("peer-alpha")
        val slotB = cm.assignSyncSlot("peer-beta")
        val slotC = cm.assignSyncSlot("peer-alpha")  // same as slotA

        assertEquals(slotA, slotC, "Sync slot assignment must be deterministic for same peer")
        assertTrue(slotA in 0..7, "Slot must be within valid range")
        assertTrue(slotB in 0..7)
    }

    @Test
    fun `test 49 - cluster split triggered when too many members`() {
        val electionEngine = WiFiLeaderElectionEngine()
        var splitCalled = false
        val cm = WiFiClusterManager("leader-node", electionEngine, WiFiAuditLogger(), maxClusterSize = 5)
        cm.onClusterSplit = { splitCalled = true }
        cm.createCluster()

        for (i in 1..6) {
            cm.addMember("peer-$i", rssiToLeader = -50)
        }
        assertTrue(splitCalled, "Cluster split must be triggered when size exceeds max")
        cm.shutdown()
    }

    @Test
    fun `test 50 - audit logger ring buffer evicts oldest entries`() {
        val logger = WiFiAuditLogger(maxEntries = 5)
        for (i in 1..10) {
            logger.info("TEST_EVENT", "Event $i")
        }
        assertEquals(5, logger.size(), "Ring buffer should cap at maxEntries")
        val entries = logger.export()
        assertEquals(5, entries.size)
        // Last entry should be Event 10
        assertEquals("Event 10", entries.last().message)
    }
}

package com.wifidirect.mesh.connection

import com.wifidirect.mesh.models.*
import com.wifidirect.mesh.security.CryptoUtils
import com.wifidirect.mesh.security.WiFiSecurityManager
import java.security.SecureRandom
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class WiFiConnectionManager(
    private val peerTable: WiFiPeerTable,
    private val securityManager: WiFiSecurityManager,
    private val incomingSessionCallback: (WiFiConnectionSession) -> Unit = {}
) {
    private val sessions = ConcurrentHashMap<String, WiFiConnectionSession>()
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    @Volatile var isServerReady = false
        private set
    private val executor = Executors.newCachedThreadPool()

    fun startServer(port: Int = 53535) {
        if (isRunning) return
        try {
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }
            isRunning = true
            isServerReady = true
        } catch (e: IOException) {
            System.err.println("Failed to start server on port $port: ${e.message}")
            isRunning = false
            isServerReady = false
            return
        }
        thread(name = "WiFiServerThread") {
            try {
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    executor.submit {
                        handleIncomingSocket(clientSocket)
                    }
                }
            } catch (e: IOException) {
                if (isRunning) {
                    System.err.println("Server socket error: ${e.message}")
                }
            } finally {
                isServerReady = false
            }
        }
    }

    fun stopServer() {
        isRunning = false
        isServerReady = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // ignore
        }
        serverSocket = null
        sessions.values.forEach { closeSession(it.peerId) }
        executor.shutdownNow()
    }

    fun connectToPeer(host: String, port: Int, targetPeerId: String): WiFiConnectionSession? {
        var socket: Socket? = null
        var lastException: Exception? = null
        var attempt = 0
        val maxAttempts = 4 // 1 initial + 3 retries
        var delayMs = 500L

        while (attempt < maxAttempts) {
            try {
                socket = Socket(host, port)
                break
            } catch (e: Exception) {
                lastException = e
                attempt++
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(delayMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    delayMs *= 2
                }
            }
        }

        if (socket == null) {
            val msg = lastException?.message ?: "Unknown error"
            System.err.println("Failed to connect to peer $targetPeerId after $attempt attempts: $msg")
            peerTable.applyCooldown(targetPeerId, 10000L)
            return null
        }

        try {
            val session = WiFiConnectionSession(
                sessionId = UUID.randomUUID().toString(),
                peerId = targetPeerId,
                socketHandle = socket,
                securitySession = null,
                connectionTimestamp = System.currentTimeMillis(),
                lastActivityTimestamp = System.currentTimeMillis(),
                activeTransferCount = 0,
                isInitiator = true
            )

            val handshakeSuccess = performHandshake(session)
            if (handshakeSuccess) {
                closeSession(targetPeerId) // Close old session if any exists
                sessions[targetPeerId] = session
                peerTable.resetCooldown(targetPeerId)
                return session
            } else {
                try { socket.close() } catch (ex: IOException) {}
                peerTable.applyCooldown(targetPeerId, 10000L) // 10s base cooldown on fail
            }
        } catch (e: Exception) {
            System.err.println("Failed to perform handshake with peer $targetPeerId: ${e.message}")
            try { socket.close() } catch (ex: IOException) {}
            peerTable.applyCooldown(targetPeerId, 10000L)
        }
        return null
    }

    fun getSession(peerId: String): WiFiConnectionSession? {
        return sessions[peerId]
    }

    fun getActiveSessionCount(): Int = sessions.size

    fun hasActiveSession(peerId: String): Boolean = sessions.containsKey(peerId)

    fun closeSession(peerId: String) {
        val session = sessions.remove(peerId) ?: return
        try {
            session.socketHandle?.close()
        } catch (e: IOException) {
            // ignore
        }
        session.securitySession?.let { securityManager.removeSession(it.sessionId) }
    }

    private fun handleIncomingSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val session = WiFiConnectionSession(
                sessionId = UUID.randomUUID().toString(),
                peerId = "pending-${UUID.randomUUID().toString().take(8)}", // Ephemeral until authenticated
                socketHandle = socket,
                securitySession = null,
                connectionTimestamp = System.currentTimeMillis(),
                lastActivityTimestamp = System.currentTimeMillis(),
                activeTransferCount = 0,
                isInitiator = false
            )

            val handshakeSuccess = performHandshake(session)
            if (handshakeSuccess && session.peerPublicKeyId != null) {
                val authenticatedPeerId = session.peerPublicKeyId!!
                // Re-map session to correct peerId
                val finalSession = session.copy(peerId = authenticatedPeerId)
                finalSession.securitySession = session.securitySession
                closeSession(authenticatedPeerId) // Close old session if any exists
                sessions[authenticatedPeerId] = finalSession
                
                // Invoke callback
                incomingSessionCallback(finalSession)
            } else {
                try { socket.close() } catch (e: IOException) {}
            }
        } catch (e: Exception) {
            System.err.println("Error handling incoming socket connection: ${e.message}")
            try { socket.close() } catch (ex: IOException) {}
        }
    }

    private fun performHandshake(session: WiFiConnectionSession): Boolean {
        val socket = session.socketHandle ?: return false
        try {
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            // 1. Generate Ephemeral curve KeyPair
            val ephemeralKeyPair = CryptoUtils.generateECKeyPair()
            val localEpkBytes = ephemeralKeyPair.public.encoded

            // 2. Exchange Ephemeral Keys
            val remoteEpkBytes = if (session.isInitiator) {
                writeBytes(output, localEpkBytes)
                readBytes(input)
            } else {
                val remoteBytes = readBytes(input)
                writeBytes(output, localEpkBytes)
                remoteBytes
            }
            session.remoteEphemeralKey = remoteEpkBytes

            // 3. Compute Shared Secret & Derive Session Keys
            val sharedSecret = CryptoUtils.computeECDH(ephemeralKeyPair.private, remoteEpkBytes)
            val (encKey, macKey) = CryptoUtils.hkdfDeriveKeys(sharedSecret)

            val secSession = if (session.isInitiator) {
                WiFiSecuritySession(
                    sessionId = session.sessionId,
                    sharedSecret = sharedSecret,
                    encryptKey = encKey,
                    decryptKey = macKey
                )
            } else {
                WiFiSecuritySession(
                    sessionId = session.sessionId,
                    sharedSecret = sharedSecret,
                    encryptKey = macKey,
                    decryptKey = encKey
                )
            }
            session.securitySession = secSession
            securityManager.registerSession(session.sessionId, secSession)

            // 4. Encrypt Signature of Ephemeral Keys using Long-Term Device Keys
            val sigPayload = localEpkBytes + remoteEpkBytes
            val localSignatureBytes = CryptoUtils.signData(sigPayload, securityManager.longTermKeyPair.private)
            
            // Build Plaintext Identity Payload: [PubkeyLength (4) | PubkeyBytes | Timestamp (8) | Nonce (8) | SignatureLength (4) | SignatureBytes]
            val localPubkeyBytes = securityManager.longTermKeyPair.public.encoded
            val identityBuffer = ByteBuffer.allocate(4 + localPubkeyBytes.size + 8 + 8 + 4 + localSignatureBytes.size)
            identityBuffer.putInt(localPubkeyBytes.size)
            identityBuffer.put(localPubkeyBytes)
            identityBuffer.putLong(System.currentTimeMillis())
            identityBuffer.putLong(SecureRandom().nextLong())
            identityBuffer.putInt(localSignatureBytes.size)
            identityBuffer.put(localSignatureBytes)
            val plaintextIdentity = identityBuffer.array()

            // Encrypt using our AES-GCM helper in SecurityManager
            val encryptedIdentity = securityManager.encryptPayload(session.sessionId, plaintextIdentity) ?: return false

            // 5. Exchange Encrypted Identity Payloads
            val remoteEncryptedIdentity = if (session.isInitiator) {
                writeBytes(output, encryptedIdentity)
                readBytes(input)
            } else {
                val remoteBytes = readBytes(input)
                writeBytes(output, encryptedIdentity)
                remoteBytes
            }

            // Decrypt Remote Identity
            val remotePlaintextIdentity = securityManager.decryptPayload(session.sessionId, remoteEncryptedIdentity) ?: return false
            val remoteBuffer = ByteBuffer.wrap(remotePlaintextIdentity)
            
            val remotePubkeyLen = remoteBuffer.getInt()
            val remotePubkeyBytes = ByteArray(remotePubkeyLen)
            remoteBuffer.get(remotePubkeyBytes)
            
            val remoteTimestamp = remoteBuffer.getLong()
            val remoteNonce = remoteBuffer.getLong()
            
            val remoteSigLen = remoteBuffer.getInt()
            val remoteSignatureBytes = ByteArray(remoteSigLen)
            remoteBuffer.get(remoteSignatureBytes)

            // Verify Timestamp tolerance (10 minutes)
            if (Math.abs(System.currentTimeMillis() - remoteTimestamp) > 600000) {
                System.err.println("Handshake failed: clock skew exceeds tolerance limit.")
                return false
            }

            // Verify Signature
            val expectedSigPayload = remoteEpkBytes + localEpkBytes
            val remotePubkey = CryptoUtils.decodePublicKey(remotePubkeyBytes)
            val isVerified = CryptoUtils.verifySignature(expectedSigPayload, remoteSignatureBytes, remotePubkey)
            if (!isVerified) {
                System.err.println("Handshake failed: invalid remote signature.")
                return false
            }

            // Successfully authenticated
            session.peerPublicKeyId = CryptoUtils.bytesToHex(CryptoUtils.sha256(remotePubkeyBytes))
            return true

        } catch (e: Exception) {
            System.err.println("Handshake exception: ${e.message}")
            return false
        }
    }

    // Framing Helpers
    private fun writeBytes(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val length = input.readInt()
        if (length < 0 || length > 10 * 1024 * 1024) { // 10MB sanity cap
            throw IOException("Invalid frame length: $length")
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes
    }
}

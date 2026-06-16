package com.wifidirect.mesh.sync

import com.wifidirect.mesh.connection.WiFiConnectionManager
import com.wifidirect.mesh.models.WiFiConnectionSession
import com.wifidirect.mesh.security.WiFiSecurityManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class WiFiTransferManager(
    private val connectionManager: WiFiConnectionManager,
    private val securityManager: WiFiSecurityManager,
    private val onPayloadReceived: (peerId: String, payload: ByteArray) -> Unit = { _, _ -> }
) {
    private val activeListeners = ConcurrentHashMap<String, Boolean>()
    private val executor = Executors.newCachedThreadPool()

    fun startListeningToSession(session: WiFiConnectionSession) {
        val peerId = session.peerId
        if (activeListeners.putIfAbsent(peerId, true) != null) return // Already listening

        executor.submit {
            val socket = session.socketHandle
            if (socket == null) {
                activeListeners.remove(peerId)
                return@submit
            }
            try {
                val input = DataInputStream(socket.getInputStream())
                while (activeListeners[peerId] == true) {
                    val encryptedPayload = readFrame(input)
                    val decryptedPayload = securityManager.decryptPayload(session.sessionId, encryptedPayload)
                    if (decryptedPayload != null) {
                        onPayloadReceived(peerId, decryptedPayload)
                    } else {
                        System.err.println("Failed to decrypt incoming payload from $peerId")
                    }
                }
            } catch (e: IOException) {
                // Connection closed or broken
                System.err.println("Transfer connection lost with $peerId: ${e.message}")
            } finally {
                activeListeners.remove(peerId)
                connectionManager.closeSession(peerId)
            }
        }
    }

    fun stopListeningToSession(peerId: String) {
        activeListeners[peerId] = false
    }

    fun sendPayload(peerId: String, payload: ByteArray): Boolean {
        val session = connectionManager.getSession(peerId) ?: return false
        val socket = session.socketHandle ?: return false
        
        try {
            val encryptedPayload = securityManager.encryptPayload(session.sessionId, payload) ?: return false
            val output = DataOutputStream(socket.getOutputStream())
            writeFrame(output, encryptedPayload)
            
            synchronized(session) {
                session.lastActivityTimestamp = System.currentTimeMillis()
            }
            return true
        } catch (e: IOException) {
            System.err.println("Failed to write payload to $peerId: ${e.message}")
            connectionManager.closeSession(peerId)
            return false
        }
    }

    private fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
        synchronized(out) {
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val length = input.readInt()
        if (length < 0 || length > 10 * 1024 * 1024) { // 10MB cap
            throw IOException("Invalid frame size: $length")
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes
    }
}

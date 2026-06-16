package com.wifidirect.mesh.security

import com.wifidirect.mesh.models.WiFiSecuritySession
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    init {
        // Add security providers if needed, standard Java has EC
    }

    fun generateECKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    fun decodePublicKey(encoded: ByteArray): PublicKey {
        val kf = KeyFactory.getInstance("EC")
        val spec = X509EncodedKeySpec(encoded)
        return kf.generatePublic(spec)
    }

    fun computeECDH(privateKey: PrivateKey, remotePublicKeyBytes: ByteArray): ByteArray {
        val remotePublicKey = decodePublicKey(remotePublicKeyBytes)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(remotePublicKey, true)
        return ka.generateSecret()
    }

    fun hkdfDeriveKeys(sharedSecret: ByteArray, salt: ByteArray = ByteArray(32)): Pair<ByteArray, ByteArray> {
        // Simple HKDF-Extract-and-Expand using HMAC-SHA256
        val mac = Mac.getInstance("HmacSHA256")
        val saltKey = SecretKeySpec(salt, "HmacSHA256")
        mac.init(saltKey)
        val prk = mac.doFinal(sharedSecret)

        val prkKey = SecretKeySpec(prk, "HmacSHA256")
        
        // Derive encKey (Info = "encKey")
        mac.init(prkKey)
        mac.update("encKey".toByteArray())
        mac.update(1.toByte())
        val rawEncKey = mac.doFinal()

        // Derive macKey (Info = "macKey")
        mac.init(prkKey)
        mac.update("macKey".toByteArray())
        mac.update(1.toByte())
        val rawMacKey = mac.doFinal()

        return Pair(rawEncKey.copyOf(32), rawMacKey.copyOf(32)) // return 256-bit keys
    }

    fun signData(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    fun verifySignature(data: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initVerify(publicKey)
        signer.update(data)
        return signer.verify(signature)
    }

    fun encryptAES_GCM(plaintext: ByteArray, keyBytes: ByteArray, nonce: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce) // 128-bit authentication tag
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        return cipher.doFinal(plaintext)
    }

    fun decryptAES_GCM(ciphertext: ByteArray, keyBytes: ByteArray, nonce: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext)
    }

    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

class WiFiSecurityManager(
    val longTermKeyPair: KeyPair = CryptoUtils.generateECKeyPair()
) {
    val longTermPublicKeyId: String = CryptoUtils.bytesToHex(CryptoUtils.sha256(longTermKeyPair.public.encoded))

    // Stores active sessions
    private val activeSessions = HashMap<String, WiFiSecuritySession>()

    fun registerSession(sessionId: String, session: WiFiSecuritySession) {
        activeSessions[sessionId] = session
    }

    fun getSession(sessionId: String): WiFiSecuritySession? {
        return activeSessions[sessionId]
    }

    fun removeSession(sessionId: String) {
        activeSessions.remove(sessionId)
    }

    fun encryptPayload(sessionId: String, plaintext: ByteArray): ByteArray? {
        val session = activeSessions[sessionId] ?: return null
        val nonceBytes = ByteArray(12).apply {
            val n = synchronized(session) { ++session.nonce }
            for (i in 0..7) {
                this[i] = (n ushr (i * 8)).toByte()
            }
        }
        val ciphertext = CryptoUtils.encryptAES_GCM(plaintext, session.encryptKey, nonceBytes)
        // Prepend nonce (12 bytes) to the ciphertext for transmission
        return nonceBytes + ciphertext
    }

    fun decryptPayload(sessionId: String, encryptedPayload: ByteArray): ByteArray? {
        val session = activeSessions[sessionId] ?: return null
        if (encryptedPayload.size < 12) return null
        val nonceBytes = encryptedPayload.copyOfRange(0, 12)
        val ciphertext = encryptedPayload.copyOfRange(12, encryptedPayload.size)
        return CryptoUtils.decryptAES_GCM(ciphertext, session.decryptKey, nonceBytes)
    }
}

package com.meshnet.meshnet_app.crypto

import android.util.Log
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.DataInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class DoubleRatchet(
    private val sharedSecret: ByteArray,
    private var dhSendKeyPair: DHKeyPair,
    private var dhRemoteKey: ByteArray,
) {
    data class DHKeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    companion object {
        private const val TAG = "DoubleRatchet"
        private const val KEY_BYTES = 32
        private const val MAX_SKIPPED = 1000

        fun generateKeyPair(): DHKeyPair {
            val gen = X25519KeyPairGenerator()
            gen.init(X25519KeyGenerationParameters(SecureRandom()))
            val pair = gen.generateKeyPair()
            return DHKeyPair(
                privateKey = (pair.private as X25519PrivateKeyParameters).encoded,
                publicKey = (pair.public as X25519PublicKeyParameters).encoded,
            )
        }

        fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
            val priv = X25519PrivateKeyParameters(privateKey, 0)
            val pub = X25519PublicKeyParameters(publicKey, 0)
            val agreement = X25519Agreement()
            agreement.init(priv)
            val secret = ByteArray(KEY_BYTES)
            agreement.calculateAgreement(pub, secret, 0)
            return secret
        }

        fun kdfRootKey(rootKey: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
            val newRoot = hmacSha256(rootKey, dhOutput + 0x01.toByte())
            val chainKey = hmacSha256(rootKey, dhOutput + 0x02.toByte())
            return newRoot to chainKey
        }

        fun kdfChainKey(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
            val newChain = hmacSha256(chainKey, byteArrayOf(0x01))
            val messageKey = hmacSha256(chainKey, byteArrayOf(0x02))
            return newChain to messageKey
        }

        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }
    }

    private var rootKey: ByteArray = sharedSecret.copyOf()
    private var sendChainKey: ByteArray = ByteArray(KEY_BYTES)
    private var recvChainKey: ByteArray = ByteArray(KEY_BYTES)
    private var sendCount: Int = 0
    private var recvCount: Int = 0
    private var prevSendCount: Int = 0
    private val skippedMessageKeys = mutableMapOf<SkippedKeyIndex, ByteArray>()
    private var totalMessagesSent: Int = 0
    private var totalMessagesReceived: Int = 0
    private var lastRatchetTimeMs: Long = System.currentTimeMillis()

    /** Check if key rotation is needed: 100+ messages or 1 hour. */
    fun shouldRotate(): Boolean {
        return totalMessagesSent > 100 ||
            (System.currentTimeMillis() - lastRatchetTimeMs) > 3_600_000
    }

    fun getSendCount(): Int = totalMessagesSent

    data class SkippedKeyIndex(val dhPublicKey: ByteArray, val messageNum: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SkippedKeyIndex) return false
            return dhPublicKey.contentEquals(other.dhPublicKey) && messageNum == other.messageNum
        }
        override fun hashCode(): Int = dhPublicKey.contentHashCode() * 31 + messageNum
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val (newSendChain, messageKey) = kdfChainKey(sendChainKey)
        sendChainKey = newSendChain
        sendCount++
        totalMessagesSent++

        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = MeshCrypto.chachaCipherWithKey(messageKey)
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            SecretKeySpec(messageKey, "ChaCha20"),
            javax.crypto.spec.IvParameterSpec(nonce)
        )
        val ciphertext = cipher.doFinal(plaintext)

        val header = ByteArray(40)
        System.arraycopy(dhSendKeyPair.publicKey, 0, header, 0, 32)
        header[32] = (prevSendCount ushr 24).toByte()
        header[33] = (prevSendCount ushr 16).toByte()
        header[34] = (prevSendCount ushr 8).toByte()
        header[35] = prevSendCount.toByte()
        val msgNum = sendCount - 1
        header[36] = (msgNum ushr 24).toByte()
        header[37] = (msgNum ushr 16).toByte()
        header[38] = (msgNum ushr 8).toByte()
        header[39] = msgNum.toByte()

        return header + nonce + ciphertext
    }

    fun decrypt(messageBytes: ByteArray): ByteArray? {
        if (messageBytes.size < 40 + 12 + 16) return null

        val header = messageBytes.copyOfRange(0, 40)
        val theirPublicKey = header.copyOfRange(0, 32)
        val prevChainLen = ((header[32].toInt() and 0xFF) shl 24) or
                ((header[33].toInt() and 0xFF) shl 16) or
                ((header[34].toInt() and 0xFF) shl 8) or
                (header[35].toInt() and 0xFF)
        val messageNum = ((header[36].toInt() and 0xFF) shl 24) or
                ((header[37].toInt() and 0xFF) shl 16) or
                ((header[38].toInt() and 0xFF) shl 8) or
                (header[39].toInt() and 0xFF)

        val nonce = messageBytes.copyOfRange(40, 52)
        val ciphertext = messageBytes.copyOfRange(52, messageBytes.size)

        if (!theirPublicKey.contentEquals(dhRemoteKey)) {
            skipMessages(recvChainKey, recvCount, dhRemoteKey)
            performRatchetStep(theirPublicKey)
        }

        while (recvCount < messageNum) {
            val (newChain, mk) = kdfChainKey(recvChainKey)
            recvChainKey = newChain
            val idx = SkippedKeyIndex(theirPublicKey, recvCount)
            skippedMessageKeys[idx] = mk
            recvCount++
            if (skippedMessageKeys.size > MAX_SKIPPED) {
                val keys = skippedMessageKeys.keys.toList()
                for (k in keys.take(skippedMessageKeys.size - MAX_SKIPPED)) {
                    skippedMessageKeys.remove(k)
                }
            }
        }

        val skipIdx = SkippedKeyIndex(theirPublicKey, messageNum)
        val skippedKey = skippedMessageKeys.remove(skipIdx)
        if (skippedKey != null) {
            return decryptWithKey(skippedKey, nonce, ciphertext)
        }

        val (newChain, messageKey) = kdfChainKey(recvChainKey)
        recvChainKey = newChain
        recvCount++
        totalMessagesReceived++

        return decryptWithKey(messageKey, nonce, ciphertext)
    }

    private fun decryptWithKey(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray? {
        return try {
            val cipher = MeshCrypto.chachaCipherWithKey(key)
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "ChaCha20"),
                javax.crypto.spec.IvParameterSpec(nonce)
            )
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "Decrypt error: ${e.message}")
            null
        }
    }

    private fun performRatchetStep(theirPublicKey: ByteArray) {
        val dhOutput = dh(dhSendKeyPair.privateKey, theirPublicKey)
        val (newRoot1, recvChain) = kdfRootKey(rootKey, dhOutput)

        val newKeyPair = generateKeyPair()
        val dhOutput2 = dh(newKeyPair.privateKey, theirPublicKey)
        val (newRoot2, sendChain) = kdfRootKey(newRoot1, dhOutput2)

        dhSendKeyPair = newKeyPair
        dhRemoteKey = theirPublicKey
        rootKey = newRoot2
        sendChainKey = sendChain
        recvChainKey = recvChain
        prevSendCount = sendCount
        sendCount = 0
        recvCount = 0
        lastRatchetTimeMs = System.currentTimeMillis()
    }

    private fun skipMessages(chainKey: ByteArray, count: Int, dhKey: ByteArray) {
        var ck = chainKey
        for (i in 0 until count) {
            val (newChain, mk) = kdfChainKey(ck)
            ck = newChain
            val idx = SkippedKeyIndex(dhKey, i)
            skippedMessageKeys[idx] = mk
        }
    }

    fun serialize(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.write(dhSendKeyPair.privateKey)
        dos.write(dhSendKeyPair.publicKey)
        dos.write(dhRemoteKey)
        dos.write(rootKey)
        dos.write(sendChainKey)
        dos.write(recvChainKey)
        dos.writeInt(sendCount)
        dos.writeInt(recvCount)
        dos.writeInt(prevSendCount)
        dos.writeInt(skippedMessageKeys.size)
        skippedMessageKeys.forEach { (idx, key) ->
            dos.write(idx.dhPublicKey)
            dos.writeInt(idx.messageNum)
            dos.write(key)
        }
        dos.writeLong(totalMessagesSent.toLong())
        dos.writeLong(totalMessagesReceived.toLong())
        dos.writeLong(lastRatchetTimeMs)
        return baos.toByteArray()
    }

    fun deserialize(data: ByteArray) {
        val dis = DataInputStream(data.inputStream())
        val sendPriv = ByteArray(KEY_BYTES); dis.readFully(sendPriv)
        val sendPub = ByteArray(KEY_BYTES); dis.readFully(sendPub)
        val remoteKey = ByteArray(KEY_BYTES); dis.readFully(remoteKey)
        val rk = ByteArray(KEY_BYTES); dis.readFully(rk)
        val sendChain = ByteArray(KEY_BYTES); dis.readFully(sendChain)
        val recvChain = ByteArray(KEY_BYTES); dis.readFully(recvChain)
        sendCount = dis.readInt()
        recvCount = dis.readInt()
        prevSendCount = dis.readInt()
        val skipCount = dis.readInt()
        skippedMessageKeys.clear()
        for (i in 0 until skipCount) {
            val dhKey = ByteArray(KEY_BYTES); dis.readFully(dhKey)
            val msgNum = dis.readInt()
            val mk = ByteArray(KEY_BYTES); dis.readFully(mk)
            skippedMessageKeys[SkippedKeyIndex(dhKey, msgNum)] = mk
        }
        if (dis.available() >= 24) {
            totalMessagesSent = dis.readLong().toInt()
            totalMessagesReceived = dis.readLong().toInt()
            lastRatchetTimeMs = dis.readLong()
        }
        dhSendKeyPair = DHKeyPair(sendPriv, sendPub)
        dhRemoteKey = remoteKey
        rootKey = rk
        sendChainKey = sendChain
        recvChainKey = recvChain
    }

    fun getSendPublicKey(): ByteArray = dhSendKeyPair.publicKey.copyOf()
}

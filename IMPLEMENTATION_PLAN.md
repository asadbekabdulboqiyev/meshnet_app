# MeshNet 5-Feature Implementation Plan

## Global Dependency Order

```
Feature 1: BLE Notification Chunking Fix (PREREQUISITE for all others)
Feature 4: Forward Secrecy (prerequisite for encrypted binary/group)
Feature 1: Image/File Sharing (depends on #1 fix + #4)
Feature 3: Voice Message (depends on #1 fix + #4)
Feature 2: Group Messaging (depends on #4)
Feature 5: Network Map (independent, can parallel with any)
```

---

# PHASE 0: CRITICAL BUG FIX — BLE Notification Chunking

**Problem**: `BleTransport.kt:440` — `notifyDevice()` sends the entire wire bytes in one `notifyCharacteristicChanged()` call. If data > MTU (~244B), the notification fails silently. This must be fixed before any binary/large data feature.

## Files to Modify

| File | Change |
|------|--------|
| `android/.../transport/BleTransport.kt` | Add `notifyChunks()` method; rewrite `notifyDevice()` to use it |

## Solution: Chunked Notifications (Same Pattern as writeChunks)

```
Notification path:
  1. If data.size <= chunkCapacity → send single notify (current behavior)
  2. If data.size > chunkCapacity → prepend 7-byte chunk header, then
     send each chunk via separate notifyCharacteristicChanged() calls
     with 50ms delay between them (BLE stack needs time to process)
```

### New Method: `notifyChunks()`

```kotlin
@SuppressLint("MissingPermission")
private fun notifyChunks(
    address: String,
    data: ByteArray,
    onDone: (Boolean) -> Unit
) {
    val device = connectedServerDevices[address] ?: run { onDone(false); return }
    val server = gattServer ?: run { onDone(false); return }
    val service = server.getService(SERVICE_UUID) ?: run { onDone(false); return }
    val txChar = service.getCharacteristic(TX_CHAR_UUID) ?: run { onDone(false); return }

    val capacity = MAX_PAYLOAD  // notification uses fixed 244, no MTU negotiation on server side
    if (data.size <= capacity) {
        // Single notification — existing behavior
        val ok = sendSingleNotify(server, device, txChar, data)
        onDone(ok)
        return
    }

    // Multi-chunk notification with 7-byte header
    val header = ByteArray(7).apply {
        this[0] = MeshFrame.MAGIC1
        this[1] = MeshFrame.MAGIC2
        this[2] = CHUNK_MARKER
        val len = data.size
        this[3] = (len ushr 24).toByte()
        this[4] = (len ushr 16).toByte()
        this[5] = (len ushr 8).toByte()
        this[6] = len.toByte()
    }
    val wire = header + data

    // Chunk wire into notification-sized pieces
    val chunks = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < wire.size) {
        val end = minOf(offset + capacity, wire.size)
        chunks.add(wire.copyOfRange(offset, end))
        offset = end
    }

    // Send each chunk with delay
    mainHandler.post {
        notifyChunkSequential(server, device, txChar, chunks, 0) { ok ->
            onDone(ok)
        }
    }
}

@SuppressLint("MissingPermission")
private fun notifyChunkSequential(
    server: BluetoothGattServer,
    device: BluetoothDevice,
    txChar: BluetoothGattCharacteristic,
    chunks: List<ByteArray>,
    index: Int,
    onDone: (Boolean) -> Unit,
) {
    if (index >= chunks.size) {
        onDone(true)
        return
    }
    val ok = sendSingleNotify(server, device, txChar, chunks[index])
    if (!ok) {
        onDone(false)
        return
    }
    // 50ms delay between chunks for BLE stack
    mainHandler.postDelayed({
        notifyChunkSequential(server, device, txChar, chunks, index + 1, onDone)
    }, 50L)
}

@SuppressLint("MissingPermission")
private fun sendSingleNotify(
    server: BluetoothGattServer,
    device: BluetoothDevice,
    txChar: BluetoothGattCharacteristic,
    data: ByteArray,
): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            txChar.value = data
            server.notifyCharacteristicChanged(device, txChar, false, data)
        } else {
            txChar.value = data
            server.notifyCharacteristicChanged(device, txChar, false)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Single notify xato: ${device.address} — ${e.message}")
        false
    }
}
```

### Modify `notifyDevice()` (line 440):

```kotlin
private fun notifyDevice(address: String, data: ByteArray, onResult: (Boolean) -> Unit = {}) {
    val device = connectedServerDevices[address] ?: run { onResult(false); return }
    val server = gattServer ?: run { onResult(false); return }
    val service = server.getService(SERVICE_UUID) ?: run { onResult(false); return }
    val txChar = service.getCharacteristic(TX_CHAR_UUID) ?: run { onResult(false); return }
    notifyChunks(address, data, onResult)
}
```

### Update `sendFrame()` (line 389):

```kotlin
fun sendFrame(targetDeviceId: String, frame: MeshFrame, onSent: (Boolean) -> Unit) {
    try {
        val address = addressByDeviceId[targetDeviceId]
        if (address == null) { onSent(false); return }
        val wire = MeshFrame.encode(frame)
        if (connectedServerDevices.containsKey(address)) {
            notifyChunks(address, wire) { ok ->
                if (ok) {
                    onSent(true)
                } else {
                    Log.d(TAG, "Notification muvaffaqiyatsiz, client write fallback: $address")
                    enqueueWrite(address, wire, onSent)
                }
            }
            return
        }
        enqueueWrite(address, wire, onSent)
    } catch (e: SecurityException) {
        Log.e(TAG, "sendFrame: ruxsat yo'q ($targetDeviceId)", e)
        onSent(false)
    } catch (e: Exception) {
        Log.e(TAG, "sendFrame xato ($targetDeviceId): ${e.message}")
        onSent(false)
    }
}
```

### Update `sendToAll()` (line 417):

```kotlin
fun sendToAll(frame: MeshFrame) {
    val targets = synchronized(gattClients) { addressByDeviceId.entries.toList() }
    if (targets.isEmpty()) return
    val wire = MeshFrame.encode(frame)
    targets.forEach { (targetDeviceId, address) ->
        try {
            if (connectedServerDevices.containsKey(address)) {
                notifyChunks(address, wire) { ok ->
                    if (!ok) enqueueWrite(address, wire) {}
                }
            } else {
                enqueueWrite(address, wire) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendToAll xato ($targetDeviceId): ${e.message}")
        }
    }
}
```

---

# FEATURE 4: FORWARD SECRECY (Double Ratchet Protocol)

**Must be implemented before binary/group features because it replaces the current static X25519+ChaCha20 encryption with per-message key rotation.**

## New Files to Create

### Kotlin (Android)

#### 1. `android/.../crypto/DoubleRatchet.kt`
**Purpose**: Core Double Ratchet state machine

```kotlin
package com.meshnet.meshnet_app.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Double Ratchet Protocol (X3DH + Symmetric Ratchet).
 *
 * Two chains per session:
 *   - Sending chain: ratchet keys + chain key -> message keys
 *   - Receiving chain: ratchet keys + chain key -> message keys
 *
 * State machine:
 *   INIT -> RATCHET (on DH ratchet step) -> CHAIN (skip for receiving) -> ENCRYPT/DECRYPT
 */
class DoubleRatchet(
    private val sharedSecret: ByteArray,  // X3DH output (32 bytes)
    private val localKeyPair: DHKeyPair,  // our X25519 key pair for this session
    private val remotePublicKey: ByteArray, // peer's X25519 public key
) {
    data class DHKeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    data class RatchetState(
        val dhSendKeyPair: DHKeyPair,
        val dhRemoteKey: ByteArray,
        val rootKey: ByteArray,         // 32 bytes
        val sendChainKey: ByteArray,    // 32 bytes
        val recvChainKey: ByteArray,    // 32 bytes
        val sendCount: Int,             // messages sent on current send chain
        val recvCount: Int,             // messages received on current recv chain
        val prevSendCount: Int,         // send count at last ratchet step
        val skippedMessageKeys: Map<SkippedKeyIndex, ByteArray>, // msgNum -> key
    ) {
        data class SkippedKeyIndex(val dhPublicKey: ByteArray, val messageNum: Int)
    }

    companion object {
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
            // HKDF-like: derive new root key + chain key
            val newRoot = hmacSha256(rootKey, dhOutput + 0x01.toByte())
            val chainKey = hmacSha256(rootKey, dhOutput + 0x02.toByte())
            return newRoot to chainKey
        }

        fun kdfChainKey(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
            val newChain = hmacSha256(chainKey, 0x01.toByte())
            val messageKey = hmacSha256(chainKey, 0x02.toByte())
            return newChain to messageKey
        }

        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }
    }

    @Volatile
    var state: RatchetState

    init {
        state = RatchetState(
            dhSendKeyPair = localKeyPair,
            dhRemoteKey = remotePublicKey,
            rootKey = sharedSecret,
            sendChainKey = ByteArray(KEY_BYTES),
            recvChainKey = ByteArray(KEY_BYTES),
            sendCount = 0,
            recvCount = 0,
            prevSendCount = 0,
            skippedMessageKeys = emptyMap(),
        )
    }

    /** Encrypt a plaintext message. Returns header + encrypted payload. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        // Advance sending chain
        val (newSendChain, messageKey) = kdfChainKey(state.sendChainKey)
        state = state.copy(sendChainKey = newSendChain, sendCount = state.sendCount + 1)

        // Encrypt with message key
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = MeshCrypto.chachaCipherWithKey(messageKey)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, 
            javax.crypto.spec.SecretKeySpec(messageKey, "ChaCha20"),
            javax.crypto.spec.IvParameterSpec(nonce))
        val ciphertext = cipher.doFinal(plaintext)

        // Header: dhPublicKey(32) + previousChainLength(4) + messageNumber(4) = 40 bytes
        val header = ByteArray(40)
        System.arraycopy(state.dhSendKeyPair.publicKey, 0, header, 0, 32)
        header[32] = (state.prevSendCount ushr 24).toByte()
        header[33] = (state.prevSendCount ushr 16).toByte()
        header[34] = (state.prevSendCount ushr 8).toByte()
        header[35] = state.prevSendCount.toByte()
        header[36] = (state.sendCount - 1 ushr 24).toByte() // sendCount was already incremented
        header[37] = (state.sendCount - 1 ushr 16).toByte()
        header[38] = (state.sendCount - 1 ushr 8).toByte()
        header[39] = (state.sendCount - 1).toByte()

        return header + nonce + ciphertext
    }

    /** Decrypt a received message. Input = header(40) + nonce(12) + ciphertext. */
    fun decrypt(messageBytes: ByteArray): ByteArray? {
        if (messageBytes.size < 40 + 12 + 16) return null // min overhead

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

        // Check if this is a new ratchet step (new remote public key)
        if (!theirPublicKey.contentEquals(state.dhRemoteKey)) {
            // Skip any pending messages on current recv chain
            skipMessages(state.recvChainKey, state.recvCount, state.dhRemoteKey)
            // DH ratchet step
            performRatchetStep(theirPublicKey)
        }

        // Skip already-read messages
        while (state.recvCount < messageNum) {
            val (newChain, mk) = kdfChainKey(state.recvChainKey)
            state = state.copy(recvChainKey = newChain, recvCount = state.recvCount + 1)
            // Store skipped key
            val idx = RatchetState.SkippedKeyIndex(theirPublicKey, state.recvCount - 1)
            val newSkipped = state.skippedMessageKeys.toMutableMap()
            newSkipped[idx] = mk
            if (newSkipped.size > MAX_SKIPPED) {
                // Trim oldest entries
                val keys = newSkipped.keys.toList()
                for (k in keys.take(newSkipped.size - MAX_SKIPPED)) {
                    newSkipped.remove(k)
                }
            }
            state = state.copy(skippedMessageKeys = newSkipped)
        }

        // Try skipped key first
        val skipIdx = RatchetState.SkippedKeyIndex(theirPublicKey, messageNum)
        val skippedKey = state.skippedMessageKeys[skipIdx]
        if (skippedKey != null) {
            val newSkipped = state.skippedMessageKeys.toMutableMap()
            newSkipped.remove(skipIdx)
            state = state.copy(skippedMessageKeys = newSkipped)
            return decryptWithKey(skippedKey, nonce, ciphertext)
        }

        // Advance recv chain
        val (newChain, messageKey) = kdfChainKey(state.recvChainKey)
        state = state.copy(recvChainKey = newChain, recvCount = state.recvCount + 1)

        return decryptWithKey(messageKey, nonce, ciphertext)
    }

    private fun decryptWithKey(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray? {
        return try {
            val cipher = MeshCrypto.chachaCipherWithKey(key)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "ChaCha20"),
                javax.crypto.spec.IvParameterSpec(nonce))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    private fun performRatchetStep(theirPublicKey: ByteArray) {
        // Receive step: DH(theirNew, ourCurrent) -> new root key + recv chain
        val dhOutput = dh(state.dhSendKeyPair.privateKey, theirPublicKey)
        val (newRoot1, recvChain) = kdfRootKey(state.rootKey, dhOutput)

        // Send step: new DH key pair -> new root key + send chain
        val newKeyPair = generateKeyPair()
        val dhOutput2 = dh(newKeyPair.privateKey, theirPublicKey)
        val (newRoot2, sendChain) = kdfRootKey(newRoot1, dhOutput2)

        state = state.copy(
            dhSendKeyPair = newKeyPair,
            dhRemoteKey = theirPublicKey,
            rootKey = newRoot2,
            sendChainKey = sendChain,
            recvChainKey = recvChain,
            prevSendCount = state.sendCount,
            sendCount = 0,
            recvCount = 0,
        )
    }

    private fun skipMessages(chainKey: ByteArray, count: Int, dhKey: ByteArray) {
        var ck = chainKey
        val newSkipped = state.skippedMessageKeys.toMutableMap()
        for (i in 0 until count) {
            val (newChain, mk) = kdfChainKey(ck)
            ck = newChain
            val idx = RatchetState.SkippedKeyIndex(dhKey, i)
            newSkipped[idx] = mk
        }
        state = state.copy(skippedMessageKeys = newSkipped)
    }

    /** Serialize state for persistence. */
    fun serialize(): ByteArray {
        // Simple: concatenation of all fields with length prefixes
        // Implementation: use ByteArrayOutputStream + DataOutputStream
        val baos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(baos)
        dos.write(state.dhSendKeyPair.privateKey)
        dos.write(state.dhSendKeyPair.publicKey)
        dos.write(state.dhRemoteKey)
        dos.write(state.rootKey)
        dos.write(state.sendChainKey)
        dos.write(state.recvChainKey)
        dos.writeInt(state.sendCount)
        dos.writeInt(state.recvCount)
        dos.writeInt(state.prevSendCount)
        dos.writeInt(state.skippedMessageKeys.size)
        state.skippedMessageKeys.forEach { (idx, key) ->
            dos.write(idx.dhPublicKey)
            dos.writeInt(idx.messageNum)
            dos.write(key)
        }
        return baos.toByteArray()
    }

    /** Deserialize state from persistence. */
    fun deserialize(data: ByteArray) {
        val dis = java.io.DataInputStream(data.inputStream())
        val sendPriv = ByteArray(KEY_BYTES); dis.readFully(sendPriv)
        val sendPub = ByteArray(KEY_BYTES); dis.readFully(sendPub)
        val remoteKey = ByteArray(KEY_BYTES); dis.readFully(remoteKey)
        val rootKey = ByteArray(KEY_BYTES); dis.readFully(rootKey)
        val sendChain = ByteArray(KEY_BYTES); dis.readFully(sendChain)
        val recvChain = ByteArray(KEY_BYTES); dis.readFully(recvChain)
        val sendCount = dis.readInt()
        val recvCount = dis.readInt()
        val prevSendCount = dis.readInt()
        val skipCount = dis.readInt()
        val skipped = mutableMapOf<RatchetState.SkippedKeyIndex, ByteArray>()
        for (i in 0 until skipCount) {
            val dhKey = ByteArray(KEY_BYTES); dis.readFully(dhKey)
            val msgNum = dis.readInt()
            val mk = ByteArray(KEY_BYTES); dis.readFully(mk)
            skipped[RatchetState.SkippedKeyIndex(dhKey, msgNum)] = mk
        }
        state = state.copy(
            dhSendKeyPair = DHKeyPair(sendPriv, sendPub),
            dhRemoteKey = remoteKey,
            rootKey = rootKey,
            sendChainKey = sendChain,
            recvChainKey = recvChain,
            sendCount = sendCount,
            recvCount = recvCount,
            prevSendCount = prevSendCount,
            skippedMessageKeys = skipped,
        )
    }
}
```

#### 2. `android/.../crypto/RatchetSession.kt`
**Purpose**: Manages one Double Ratchet session per peer

```kotlin
package com.meshnet.meshnet_app.crypto

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

class RatchetSessionStore(context: Context) {

    data class SessionInfo(
        val peerId: String,
        val localKeyPair: DoubleRatchet.DHKeyPair,
        val remotePublicKey: String, // base64
        val initState: String,       // "initiator" or "responder"
        val serializedState: String, // base64 of DoubleRatchet state
        val createdAtMs: Long,
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("meshnet_ratchet", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(peerId: String, session: DoubleRatchet) {
        val info = SessionInfo(
            peerId = peerId,
            localKeyPair = session.state.dhSendKeyPair,
            remotePublicKey = MeshCrypto.b64(session.state.dhRemoteKey),
            initState = "active",
            serializedState = MeshCrypto.b64(session.serialize()),
            createdAtMs = System.currentTimeMillis(),
        )
        prefs.edit().putString("session_$peerId", gson.toJson(info)).apply()
    }

    fun load(peerId: String): DoubleRatchet? {
        val json = prefs.getString("session_$peerId", null) ?: return null
        return try {
            val info = gson.fromJson(json, SessionInfo::class.java)
            val session = DoubleRatchet(
                sharedSecret = ByteArray(32), // reconstructed from root key
                localKeyPair = info.localKeyPair,
                remotePublicKey = MeshCrypto.unb64(info.remotePublicKey),
            )
            session.deserialize(MeshCrypto.unb64(info.serializedState))
            session
        } catch (e: Exception) {
            null
        }
    }

    fun remove(peerId: String) {
        prefs.edit().remove("session_$peerId").apply()
    }

    fun hasSession(peerId: String): Boolean =
        prefs.contains("session_$peerId")
}
```

#### 3. Modify `MeshCrypto.kt` — Add `chachaCipherWithKey()` helper

```kotlin
// Add to MeshCrypto companion:
fun chachaCipherWithKey(key: ByteArray): javax.crypto.Cipher {
    return try {
        val cipher = Cipher.getInstance(CHACHA_CIPHER)
        cipher
    } catch (_: GeneralSecurityException) {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        Cipher.getInstance(CHACHA_CIPHER, "BC")
    }
}
```

### Flutter Side

#### 4. `lib/crypto/ratchet_service.dart`
**Purpose**: Wraps MethodChannel for Double Ratchet session management

```dart
class RatchetService {
  static const _method = MethodChannel('meshnet/ratchet');

  Future<bool> initSession(String peerId) async {
    return await _method.invokeMethod('initSession', {'peerId': peerId}) == true;
  }

  Future<bool> hasSession(String peerId) async {
    return await _method.invokeMethod('hasSession', {'peerId': peerId}) == true;
  }

  Future<bool> deleteSession(String peerId) async {
    return await _method.invokeMethod('deleteSession', {'peerId': peerId}) == true;
  }
}
```

## Wire Protocol Changes

### New MessageType Values

```kotlin
enum class MessageType(val code: Byte) {
    // ... existing 0x01-0x08 ...
    FILE_START(0x10),      // File transfer initiation
    FILE_CHUNK(0x11),      // Binary data chunk
    FILE_END(0x12),        // File transfer completion
    GROUP_CREATE(0x20),    // Create group
    GROUP_MSG(0x21),       // Group text message
    GROUP_ADD_MEMBER(0x22),// Add member to group
    GROUP_REMOVE_MEMBER(0x23), // Remove member
    GROUP_KEY_DIST(0x24),  // Distribute group key
    GROUP_LEAVE(0x25),     // Leave group
    VOICE_MSG(0x30),       // Voice message
    RATCHET_INIT(0x40),    // Double Ratchet session initialization
    RATCHET_MSG(0x41),     // Message using ratchet encryption
}
```

### Double Ratchet Wire Format

```
RATCHET_INIT payload:
  [0..31]   senderPublicKey (32 bytes)
  [32..63]  receiverPublicKey (32 bytes)  — for responder's first response
  [64..95]  encrypted initial message key (32 bytes) — optional

RATCHET_MSG payload:
  [0..39]   ratchet header (40 bytes): dhPublicKey(32) + prevChainLen(4) + msgNum(4)
  [40..51]  nonce (12 bytes)
  [52..]    ciphertext (encrypted with message key)
```

## Data Models

### `DoubleRatchetState` (Kotlin — serializable)
Already defined in `DoubleRatchet.kt` above.

### `RatchetSession` (Dart — for UI tracking)
```dart
class RatchetSession {
  final String peerId;
  final bool isActive;
  final int sendCount;
  final int recvCount;
  final DateTime createdAt;
}
```

## Implementation Order

1. Create `DoubleRatchet.kt` with encrypt/decrypt/serialize/deserialize
2. Create `RatchetSessionStore.kt` for persistence
3. Add `chachaCipherWithKey()` to `MeshCrypto.kt`
4. Add `RATCHET_INIT`/`RATCHET_MSG` to `MessageType.kt`
5. Modify `RoutingEngine.kt`: intercept `sendText()` to use ratchet encrypt when session exists, fallback to static for new peers
6. Modify `RoutingEngine.kt`: intercept `decryptAndDeliver()` to use ratchet decrypt
7. Add MethodChannel commands for session management
8. Create Flutter `ratchet_service.dart`
9. **Backward compatibility**: If frame type is `TEXT` (0x02), use old encryption; if `RATCHET_MSG` (0x41), use Double Ratchet. This allows mixed old/new clients.

## Edge Cases

- **Out-of-sync**: If receiver skips messages, the skipped message keys are stored. Max 1000 skipped keys; beyond that, session reset.
- **Session lost**: If deserialization fails, initiate new RATCHET_INIT.
- **First message**: Initiator sends RATCHET_INIT with their public key. Responder creates session and sends RATCHET_INIT back.
- **Compromise**: If a key is compromised, past messages remain safe (backward secrecy) but future messages need new session.

## Testing

- Unit test: `DoubleRatchetTest.kt` — encrypt/decrypt round-trip, out-of-order delivery, skipped messages, serialize/deserialize
- Integration test: Two mock peers exchange messages through Double Ratchet, verify decryption

---

# FEATURE 1: IMAGE/FILE SHARING

## Files to Create

### Kotlin (Android)

#### 1. `android/.../protocol/FileTransfer.kt`
**Purpose**: File chunking, assembly, progress tracking

```kotlin
package com.meshnet.meshnet_app.protocol

import java.io.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FileTransferManager {

    data class FileInfo(
        val transferId: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val senderId: String,
        val targetId: String,
    )

    data class TransferProgress(
        val transferId: String,
        val totalBytes: Long,
        val sentBytes: Long,
        val receivedBytes: Long,
        val status: String, // "transferring", "completed", "failed", "cancelled"
    ) {
        val percent: Int get() = if (totalBytes > 0) ((sentBytes + receivedBytes) * 100 / totalBytes).toInt() else 0
    }

    companion object {
        const val FILE_CHUNK_SIZE = 200 // bytes per BLE chunk (within MTU)
        const val WIFI_CHUNK_SIZE = 64 * 1024 // 64KB for WiFi Direct
        const val MAX_FILE_SIZE = 50 * 1024 * 1024 // 50MB limit
        const val BLE_SIZE_THRESHOLD = 50 * 1024 // >50KB -> use WiFi Direct
    }

    // transferId -> progress
    private val activeTransfers = ConcurrentHashMap<String, TransferProgress>()

    // transferId -> chunks received so far (for reassembly)
    private val assemblyBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()

    // transferId -> FileInfo
    private val pendingInfo = ConcurrentHashMap<String, FileInfo>()

    fun getProgress(transferId: String): TransferProgress? = activeTransfers[transferId]

    fun cancelTransfer(transferId: String) {
        activeTransfers[transferId] = activeTransfers[transferId]?.copy(status = "cancelled")
            ?: return
        assemblyBuffers.remove(transferId)
    }

    /** Start a new file transfer: generates transferId, creates FILE_START frame. */
    fun startTransfer(
        fileName: String,
        fileSize: Long,
        mimeType: String,
        senderId: String,
        targetId: String,
    ): Pair<String, MeshFrame> {
        require(fileSize <= MAX_FILE_SIZE) { "File too large: $fileSize bytes" }

        val transferId = UUID.randomUUID().toString()

        // FILE_START payload format:
        // [0..15]  transferId (UUID bytes)
        // [16..23] fileSize (8 bytes, big-endian)
        // [24..]   fileName + "\0" + mimeType (null-separated UTF-8 strings)
        val idBytes = uuidToBytes(transferId)
        val nameBytes = fileName.toByteArray()
        val mimeBytes = mimeType.toByteArray()
        val payload = ByteArray(24 + nameBytes.size + 1 + mimeBytes.size)
        System.arraycopy(idBytes, 0, payload, 0, 16)
        payload[16] = (fileSize ushr 56).toByte()
        payload[17] = (fileSize ushr 48).toByte()
        payload[18] = (fileSize ushr 40).toByte()
        payload[19] = (fileSize ushr 32).toByte()
        payload[20] = (fileSize ushr 24).toByte()
        payload[21] = (fileSize ushr 16).toByte()
        payload[22] = (fileSize ushr 8).toByte()
        payload[23] = fileSize.toByte()
        System.arraycopy(nameBytes, 0, payload, 24, nameBytes.size)
        payload[24 + nameBytes.size] = 0x00 // separator
        System.arraycopy(mimeBytes, 0, payload, 25 + nameBytes.size, mimeBytes.size)

        activeTransfers[transferId] = TransferProgress(
            transferId, fileSize, 0, 0, "transferring"
        )

        val frame = MeshFrame(
            type = MessageType.FILE_START,
            hopLimit = RoutingEngine.MAX_HOP,
            ttl = 6,
            encrypted = true,
            senderId = senderId,
            targetId = targetId,
            msgSeq = System.currentTimeMillis(),
            payload = payload,
            senderPublicKey = null,
        )
        return transferId to frame
    }

    /** Read file and generate FILE_CHUNK frames (called by RoutingEngine). */
    fun generateChunkFrames(
        transferId: String,
        fileBytes: ByteArray,
        senderId: String,
        targetId: String,
        useWifi: Boolean,
    ): List<MeshFrame> {
        val chunkSize = if (useWifi) WIFI_CHUNK_SIZE else FILE_CHUNK_SIZE
        val frames = mutableListOf<MeshFrame>()
        var offset = 0
        var seq = System.currentTimeMillis()

        while (offset < fileBytes.size) {
            val end = minOf(offset + chunkSize, fileBytes.size)
            val chunkData = fileBytes.copyOfRange(offset, end)

            // FILE_CHUNK payload:
            // [0..15]  transferId (16 bytes)
            // [16..23] chunkIndex (8 bytes, big-endian)
            // [24..]   chunk data
            val idBytes = uuidToBytes(transferId)
            val payload = ByteArray(24 + chunkData.size)
            System.arraycopy(idBytes, 0, payload, 0, 16)
            val chunkIndex = offset.toLong() / chunkSize
            payload[16] = (chunkIndex ushr 56).toByte()
            payload[17] = (chunkIndex ushr 48).toByte()
            payload[18] = (chunkIndex ushr 40).toByte()
            payload[19] = (chunkIndex ushr 32).toByte()
            payload[20] = (chunkIndex ushr 24).toByte()
            payload[21] = (chunkIndex ushr 16).toByte()
            payload[22] = (chunkIndex ushr 8).toByte()
            payload[23] = chunkIndex.toByte()
            System.arraycopy(chunkData, 0, payload, 24, chunkData.size)

            frames.add(MeshFrame(
                type = MessageType.FILE_CHUNK,
                hopLimit = RoutingEngine.MAX_HOP,
                ttl = 6,
                encrypted = true,
                senderId = senderId,
                targetId = targetId,
                msgSeq = seq++,
                payload = payload,
                senderPublicKey = null,
            ))
            offset = end
        }

        // FILE_END frame
        val endPayload = uuidToBytes(transferId)
        frames.add(MeshFrame(
            type = MessageType.FILE_END,
            hopLimit = RoutingEngine.MAX_HOP,
            ttl = 6,
            encrypted = true,
            senderId = senderId,
            targetId = targetId,
            msgSeq = seq++,
            payload = endPayload,
            senderPublicKey = null,
        ))

        return frames
    }

    /** Handle received FILE_START: store info, prepare assembly buffer. */
    fun handleFileStart(senderId: String, payload: ByteArray): FileInfo? {
        if (payload.size < 24) return null
        val transferId = bytesToUuid(payload.copyOfRange(0, 16)) ?: return null
        val fileSize = ((payload[16].toLong() and 0xFF) shl 56) or
            ((payload[17].toLong() and 0xFF) shl 48) or
            ((payload[18].toLong() and 0xFF) shl 40) or
            ((payload[19].toLong() and 0xFF) shl 32) or
            ((payload[20].toLong() and 0xFF) shl 24) or
            ((payload[21].toLong() and 0xFF) shl 16) or
            ((payload[22].toLong() and 0xFF) shl 8) or
            (payload[23].toLong() and 0xFF)

        val strings = String(payload, 24, payload.size - 24).split("\0")
        val fileName = strings.getOrElse(0) { "unknown" }
        val mimeType = strings.getOrElse(1) { "application/octet-stream" }

        val info = FileInfo(transferId, fileName, fileSize, mimeType, senderId, "")
        pendingInfo[transferId] = info
        assemblyBuffers[transferId] = ByteArrayOutputStream()
        activeTransfers[transferId] = TransferProgress(
            transferId, fileSize, 0, 0, "transferring"
        )
        return info
    }

    /** Handle received FILE_CHUNK: append to assembly buffer. */
    fun handleFileChunk(payload: ByteArray): Pair<String, ByteArray>? {
        if (payload.size < 24) return null
        val transferId = bytesToUuid(payload.copyOfRange(0, 16)) ?: return null
        val chunkData = payload.copyOfRange(24, payload.size)

        assemblyBuffers[transferId]?.write(chunkData)

        val progress = activeTransfers[transferId]
        if (progress != null) {
            activeTransfers[transferId] = progress.copy(
                receivedBytes = progress.receivedBytes + chunkData.size
            )
        }
        return transferId to chunkData
    }

    /** Handle received FILE_END: complete transfer, return assembled file bytes. */
    fun handleFileEnd(payload: ByteArray): Triple<String, ByteArray?, FileInfo>? {
        val transferId = bytesToUuid(payload) ?: return null
        val buffer = assemblyBuffers.remove(transferId)
        val info = pendingInfo.remove(transferId)
        val fileBytes = buffer?.toByteArray()

        activeTransfers[transferId] = activeTransfers[transferId]?.copy(
            status = "completed",
            receivedBytes = fileBytes?.size?.toLong() ?: 0,
        )

        return Triple(transferId, fileBytes, info)
    }

    private fun uuidToBytes(uuid: String): ByteArray {
        return try {
            val u = java.util.UUID.fromString(uuid)
            java.nio.ByteBuffer.allocate(16).apply {
                putLong(u.mostSignificantBits)
                putLong(u.leastSignificantBits)
            }.array()
        } catch (e: Exception) { ByteArray(16) }
    }

    private fun bytesToUuid(raw: ByteArray): String? {
        return try {
            val bb = java.nio.ByteBuffer.wrap(raw)
            java.util.UUID(bb.long, bb.long).toString()
        } catch (e: Exception) { null }
    }
}
```

#### 2. `android/.../protocol/MediaCompressor.kt`
**Purpose**: Image compression before sending

```kotlin
package com.meshnet.meshnet_app.protocol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream

class MediaCompressor(private val context: Context) {

    companion object {
        const val MAX_IMAGE_DIMENSION = 1920 // max width/height
        const val JPEG_QUALITY = 75
    }

    /** Compress image from URI to JPEG bytes. */
    fun compressImage(uri: Uri): ByteArray {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI")

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()

        val width = options.outWidth
        val height = options.outHeight
        var sampleSize = 1
        while (width / sampleSize > MAX_IMAGE_DIMENSION || height / sampleSize > MAX_IMAGE_DIMENSION) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val stream2 = context.contentResolver.openInputStream(uri)!!
        val bitmap = BitmapFactory.decodeStream(stream2, null, decodeOptions)!!
        stream2.close()

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        bitmap.recycle()
        return outputStream.toByteArray()
    }

    /** Read file from URI to bytes. */
    fun readFile(uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI")
        return stream.use { it.readBytes() }
    }

    /** Get file size from URI. */
    fun getFileSize(uri: Uri): Long {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE))
        } ?: 0L
    }

    /** Get MIME type from URI. */
    fun getMimeType(uri: Uri): String {
        return context.contentResolver.getType(uri) ?: "application/octet-stream"
    }

    /** Get file name from URI. */
    fun getFileName(uri: Uri): String {
        var name = "unknown"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }
}
```

### Flutter Side

#### 3. `lib/models/message_model.dart`
**Purpose**: Extended chat message model supporting media

```dart
enum MessageType { text, image, file, voice, groupText }

class ChatMessage {
  final String messageId;
  final String text;
  final MessageType type;
  final bool fromMe;
  final String? fromDeviceId;
  final MessageStatus status;
  final DateTime? timestamp;

  // Media fields
  final String? localPath;      // local file path (sent)
  final String? remotePath;     // received file path
  final String? fileName;
  final int? fileSize;
  final String? mimeType;
  final double? transferProgress; // 0.0 - 1.0

  // Voice fields
  final Duration? audioDuration;

  // Group fields
  final String? groupName;
  final String? senderName;

  const ChatMessage({
    required this.messageId,
    this.text = '',
    this.type = MessageType.text,
    required this.fromMe,
    this.fromDeviceId,
    this.status = MessageStatus.sent,
    this.timestamp,
    this.localPath,
    this.remotePath,
    this.fileName,
    this.fileSize,
    this.mimeType,
    this.transferProgress,
    this.audioDuration,
    this.groupName,
    this.senderName,
  });

  ChatMessage copyWith({...}) { /* standard copyWith */ }
}

enum MessageStatus { pending, sent, delivered, failed, transferring }
```

#### 4. `lib/screens/media_preview.dart`
**Purpose**: Full-screen image viewer

```dart
class MediaPreviewScreen extends StatelessWidget {
  final String filePath;
  final String? title;
  const MediaPreviewScreen({required this.filePath, this.title});
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        title: title != null ? Text(title!) : null,
        actions: [
          IconButton(
            icon: Icon(Icons.share),
            onPressed: () { /* share file */ },
          ),
        ],
      ),
      body: InteractiveViewer(
        minScale: 0.5,
        maxScale: 4.0,
        child: Center(child: Image.file(File(filePath))),
      ),
    );
  }
}
```

#### 5. `lib/widgets/file_bubble.dart`
**Purpose**: File message bubble in chat

```dart
class FileBubble extends StatelessWidget {
  final ChatMessage message;
  final VoidCallback? onTap;
  const FileBubble({required this.message, this.onTap});

  @override
  Widget build(BuildContext context) {
    final isImage = message.mimeType?.startsWith('image/') == true;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        // ... card UI with file icon, name, size, progress indicator
      ),
    );
  }
}
```

#### 6. `lib/core/mesh_service.dart` — Add file transfer methods

```dart
// New MethodChannel commands:
Future<String> sendFile(String targetDeviceId, String filePath, {String? transferId}) async {
  return await _method.invokeMethod('sendFile', {
    'targetDeviceId': targetDeviceId,
    'filePath': filePath,
    'transferId': transferId,
  });
}

Future<String> sendImage(String targetDeviceId, String imagePath) async {
  return await _method.invokeMethod('sendImage', {
    'targetDeviceId': targetDeviceId,
    'imagePath': imagePath,
  });
}

Future<bool> cancelTransfer(String transferId) async {
  return await _method.invokeMethod('cancelTransfer', {
    'transferId': transferId,
  }) == true;
}

Future<String> getReceivedFilePath(String transferId) async {
  return await _method.invokeMethod('getReceivedFilePath', {
    'transferId': transferId,
  });
}
```

### New EventChannel Events

```dart
// From Kotlin -> Flutter:
"fileTransferStarted" -> { transferId, fileName, fileSize, mimeType, senderId }
"fileTransferProgress" -> { transferId, sentBytes, totalBytes, percent }
"fileTransferComplete" -> { transferId, filePath, fileName, senderId }
"fileTransferFailed" -> { transferId, error }
```

### New Dependencies (pubspec.yaml)

```yaml
dependencies:
  image_picker: ^1.1.2
  file_picker: ^8.1.6
  path_provider: ^2.1.6  # already installed
  photo_view: ^0.15.0    # zoomable image viewer
  flutter_cached_pdfview: ^0.4.2  # PDF preview
```

### RoutingEngine.kt Changes

Add to `handleIncomingFrame()`:
```kotlin
MessageType.FILE_START -> handleFileStart(frame)
MessageType.FILE_CHUNK -> handleFileChunk(frame)
MessageType.FILE_END -> handleFileEnd(frame)
```

Add new methods:
```kotlin
fun sendFile(targetId: String, fileBytes: ByteArray, fileName: String, mimeType: String): String? {
    val peer = peerStore.authorized(targetId) ?: return null
    val sharedSecret = MeshCrypto.computeSharedSecret(identityPrivateKey, MeshCrypto.unb64(peer.publicKey))
    val aad = "MeshNet:$targetId".toByteArray(StandardCharsets.UTF_8)

    val useWifi = fileBytes.size > FileTransferManager.BLE_SIZE_THRESHOLD
    val (transferId, startFrame) = fileTransferManager.startTransfer(
        fileName, fileBytes.size.toLong(), mimeType, identityDeviceId, targetId
    )

    // Encrypt all chunks with Double Ratchet (if session exists) or static key
    val encryptedBytes = MeshCrypto.encrypt(sharedSecret, fileBytes, aad)

    val chunkFrames = fileTransferManager.generateChunkFrames(
        transferId, encryptedBytes, identityDeviceId, targetId, useWifi
    )

    // Send all frames
    listenerList.forEach { it.onFrameToSend(startFrame, if (useWifi) "wifi" else null) }
    chunkFrames.forEach { frame ->
        listenerList.forEach { it.onFrameToSend(frame, if (useWifi) "wifi" else null) }
    }

    return transferId
}
```

## UI Components

### Chat View Updates (`chat_view.dart`)

Replace `_ChatMessage` with `ChatMessage` from `message_model.dart`. Update `_MessageBubble` to handle multiple message types:

```
_MessageBubble
├── Text message (existing)
├── Image message → thumbnail, tap to full screen
├── File message → icon + filename + size + progress
└── Voice message → (Feature 3)
```

### Input Bar Updates

```
Row(
  IconButton(photo_camera) — pick image
  IconButton(attach_file)  — pick any file
  Expanded(TextField)      — text input
  IconButton(send)
)
```

## Edge Cases

- **Offline transfer**: If receiver is offline, FILE_START goes to store-and-forward. When peer comes online, chunks are sent sequentially.
- **Incomplete transfer**: If assembly buffer is incomplete (missing chunks), transfer stays in "transferring" state. After 24h, expires.
- **File > 50MB**: Reject with error to Flutter. Show snackbar.
- **Interrupted BLE**: If BLE disconnects during transfer, chunks already received are kept. On reconnect, resume from last received chunk index.
- **WiFi Direct fallback**: If >50KB, prefer WiFi Direct. If WiFi fails, fall back to BLE chunking.

## Testing

- Unit test: `FileTransferTest.kt` — chunk generation, assembly, progress tracking
- Widget test: `FileBubble` renders correctly for image/file/voice
- Integration test: Pick image → compress → encrypt → chunk → transfer → decrypt → save

---

# FEATURE 2: GROUP MESSAGING

## Files to Create

### Kotlin (Android)

#### 1. `android/.../protocol/GroupManager.kt`
**Purpose**: Group CRUD, key distribution, member management

```kotlin
package com.meshnet.meshnet_app.protocol

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.meshnet.meshnet_app.crypto.MeshCrypto
import java.security.SecureRandom

class GroupStore(context: Context) {

    data class GroupMember(
        val deviceId: String,
        val displayName: String,
        val role: String = "member", // "admin", "member"
    )

    data class Group(
        val groupId: String,
        val name: String,
        val members: List<GroupMember>,
        val symmetricKey: String, // Base64 URL-safe (AES-256 key for group messages)
        val createdAtMs: Long,
        val createdBy: String,
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("meshnet_groups", Context.MODE_PRIVATE)
    private val gson = Gson()

    private fun load(): MutableMap<String, Group> {
        val json = prefs.getString("groups", null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, Group>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) { mutableMapOf() }
    }

    private fun save(groups: MutableMap<String, Group>) {
        prefs.edit().putString("groups", gson.toJson(groups)).apply()
    }

    fun createGroup(name: String, members: List<GroupMember>, createdBy: String): Group {
        val groupId = java.util.UUID.randomUUID().toString()
        val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val group = Group(
            groupId = groupId,
            name = name,
            members = members,
            symmetricKey = MeshCrypto.b64(keyBytes),
            createdAtMs = System.currentTimeMillis(),
            createdBy = createdBy,
        )
        val groups = load()
        groups[groupId] = group
        save(groups)
        return group
    }

    fun getGroup(groupId: String): Group? = load()[groupId]

    fun getAllGroups(): List<Group> = load().values.toList()

    fun updateGroup(group: Group) {
        val groups = load()
        groups[group.groupId] = group
        save(groups)
    }

    fun deleteGroup(groupId: String) {
        val groups = load()
        groups.remove(groupId)
        save(groups)
    }

    fun addMember(groupId: String, member: GroupMember) {
        val group = load()[groupId] ?: return
        val updated = group.copy(members = group.members + member)
        updateGroup(updated)
    }

    fun removeMember(groupId: String, deviceId: String) {
        val group = load()[groupId] ?: return
        val updated = group.copy(members = group.members.filter { it.deviceId != deviceId })
        updateGroup(updated)
    }

    fun getMemberDeviceIds(groupId: String): List<String> {
        return load()[groupId]?.members?.map { it.deviceId } ?: emptyList()
    }

    fun getSymmetricKey(groupId: String): ByteArray? {
        return load()[groupId]?.symmetricKey?.let { MeshCrypto.unb64(it) }
    }
}
```

#### 2. Modify `RoutingEngine.kt`

Add group message handling:

```kotlin
fun sendGroupMessage(groupId: String, message: String): String? {
    val group = groupStore.getGroup(groupId) ?: return null
    val symmetricKey = MeshCrypto.unb64(group.symmetricKey)
    val aad = "MeshGroup:$groupId".toByteArray(StandardCharsets.UTF_8)
    val ciphertext = MeshCrypto.encrypt(symmetricKey, message.toByteArray(StandardCharsets.UTF_8), aad)

    val seq = nextSeq()
    registerSeen("$identityDeviceId:$seq")

    // Send to each member
    val memberIds = group.members.map { it.deviceId }.filter { it != identityDeviceId }
    memberIds.forEach { memberId ->
        val frame = MeshFrame(
            type = MessageType.GROUP_MSG,
            hopLimit = MAX_HOP,
            ttl = 6,
            encrypted = true,
            senderId = identityDeviceId,
            targetId = memberId,
            msgSeq = seq,
            payload = ciphertext,
            senderPublicKey = null,
        )
        emitForSend(frame, transportHint(memberId))
    }

    val msgId = "${identityDeviceId}:$seq"
    messagesSent++
    return msgId
}

fun sendGroupKeyDistribution(groupId: String, targetDeviceId: String): Boolean {
    val group = groupStore.getGroup(groupId) ?: return false
    val symmetricKey = MeshCrypto.unb64(group.symmetricKey)

    // Encrypt group key with the target's shared secret
    val peer = peerStore.authorized(targetDeviceId) ?: return false
    val sharedSecret = MeshCrypto.computeSharedSecret(
        identityPrivateKey, MeshCrypto.unb64(peer.publicKey)
    )
    val encryptedKey = MeshCrypto.encrypt(sharedSecret, symmetricKey)

    val payload = encryptedKey
    val frame = MeshFrame(
        type = MessageType.GROUP_KEY_DIST,
        hopLimit = MAX_HOP,
        ttl = 6,
        encrypted = false,
        senderId = identityDeviceId,
        targetId = targetDeviceId,
        msgSeq = nextSeq(),
        payload = payload,
        senderPublicKey = null,
    )
    emitForSend(frame, transportHint(targetDeviceId))
    return true
}

private fun handleGroupMsg(frame: MeshFrame) {
    if (frame.targetId != identityDeviceId) {
        if (frame.hopLimit > 0) relayFrame(frame)
        return
    }
    // Find which group this belongs to (check all group symmetric keys)
    val groups = groupStore.getAllGroups()
    for (group in groups) {
        try {
            val key = MeshCrypto.unb64(group.symmetricKey)
            val aad = "MeshGroup:${group.groupId}".toByteArray(StandardCharsets.UTF_8)
            val plain = MeshCrypto.decrypt(key, frame.payload, aad)
            val text = String(plain, StandardCharsets.UTF_8)
            listenerList.forEach { it.onGroupMessageReceived(group.groupId, frame.senderId, text, "${frame.senderId}:${frame.msgSeq}") }
            sendDeliveryReport(frame, true)
            return
        } catch (_: Exception) { }
    }
    sendDeliveryReport(frame, false)
}

private fun handleGroupKeyDist(frame: MeshFrame) {
    // Decrypt the symmetric key with our shared secret
    val peer = peerStore.authorized(frame.senderId) ?: return
    val sharedSecret = MeshCrypto.computeSharedSecret(
        identityPrivateKey, MeshCrypto.unb64(peer.publicKey)
    )
    try {
        val symmetricKey = MeshCrypto.decrypt(sharedSecret, frame.payload)
        // Store the group key (group info must be created separately)
        // This just stores the key; group metadata comes via separate GROUP_CREATE
    } catch (e: Exception) {
        Log.e(TAG, "Group key decrypt failed: ${e.message}")
    }
}
```

#### 3. Add `GROUP_MSG` and `GROUP_KEY_DIST` to `routeFrame()`:

```kotlin
MessageType.GROUP_MSG -> handleGroupMsg(frame)
MessageType.GROUP_KEY_DIST -> handleGroupKeyDist(frame)
```

### Flutter Side

#### 4. `lib/models/group_model.dart`

```dart
class MeshGroup {
  final String groupId;
  final String name;
  final List<GroupMember> members;
  final DateTime createdAt;
  final String createdBy;

  const MeshGroup({...});
}

class GroupMember {
  final String deviceId;
  final String displayName;
  final String role;
  const GroupMember({...});
}
```

#### 5. `lib/screens/group_chat_view.dart`

```
GroupChatView
├── AppBar (group name, member count, info button)
├── ListView (messages)
│   ├── TextBubble (fromMe flag + senderName for group)
│   ├── ImageBubble
│   └── FileBubble
└── InputBar (text + attach + send)
```

#### 6. `lib/screens/create_group_screen.dart`

```
CreateGroupScreen
├── TextField (group name)
├── ListView (selected members with checkboxes)
├── Button "Create Group"
```

#### 7. `lib/screens/group_info_screen.dart`

```
GroupInfoScreen
├── Group name (editable if admin)
├── Members list with roles
├── Add member button
├── Leave group button
└── Delete group button (admin only)
```

## Wire Protocol

### GROUP_CREATE payload:
```kotlin
// [0..15]   groupId (16 bytes UUID)
// [16..]    groupName (UTF-8) + "\0" + memberDeviceIds (each 16 bytes, null-separated)
```

### GROUP_MSG payload:
```kotlin
// Same as TEXT but encrypted with group symmetric key
// AAD: "MeshGroup:$groupId"
```

### GROUP_KEY_DIST payload:
```kotlin
// E2E encrypted symmetric key for the target peer
```

### GROUP_ADD_MEMBER payload:
```kotlin
// [0..15]   groupId (16 bytes)
// [16..31]  newMemberDeviceId (16 bytes)
// [32..]    encryptedSymmetricKey (encrypted for new member)
```

## New Dependencies

No additional packages needed for group messaging.

## Implementation Order

1. Create `GroupStore.kt` — group CRUD persistence
2. Add `GROUP_CREATE`, `GROUP_MSG`, `GROUP_KEY_DIST`, `GROUP_ADD_MEMBER`, `GROUP_REMOVE_MEMBER`, `GROUP_LEAVE` to `MessageType.kt`
3. Add `sendGroupMessage()`, `sendGroupKeyDistribution()` to `RoutingEngine.kt`
4. Add MethodChannel commands: `createGroup`, `sendGroupMessage`, `getGroups`, `getGroupInfo`, `addMember`, `removeMember`, `leaveGroup`
5. Create Flutter models and screens
6. Update `ChatView` to accept optional `groupId` parameter

## Edge Cases

- **Member offline**: GROUP_KEY_DIST goes to store-and-forward. When member comes online, key is delivered.
- **Key rotation**: When member is removed, generate new symmetric key and re-distribute to remaining members.
- **Multiple admins**: Any member can be admin. Role stored in GroupMember.
- **Duplicate messages**: Handled by existing seen-cache in RoutingEngine.

---

# FEATURE 3: VOICE MESSAGE

## Files to Create

### Kotlin (Android)

#### 1. `android/.../protocol/VoiceRecorder.kt`
**Purpose**: Audio recording, Opus encoding

```kotlin
package com.meshnet.meshnet_app.protocol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val MAX_DURATION_MS = 120_000 // 2 minutes max
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    fun startRecording(onData: (ByteArray) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        )

        isRecording = true
        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ByteArray(4096)
            val startTime = System.currentTimeMillis()
            while (isRecording && (System.currentTimeMillis() - startTime) < MAX_DURATION_MS) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    onData(buffer.copyOf(read))
                }
            }
        }.also { it.start() }
    }

    fun stopRecording(): ByteArray? {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordingThread?.join(1000)
        return null
    }

    /** Save raw PCM to file for later Opus encoding. */
    fun savePcmToFile(pcmData: ByteArray): File {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.pcm")
        FileOutputStream(file).use { it.write(pcmData) }
        return file
    }
}
```

#### 2. `android/.../protocol/VoiceEncoder.kt`
**Purpose**: PCM to Opus/AAC encoding

```kotlin
package com.meshnet.meshnet_app.protocol

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class VoiceEncoder {

    companion object {
        const val AUDIO_MIME = "audio/mp4a-latm" // AAC
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val BIT_RATE = 32000
    }

    /** Encode raw PCM to AAC. Returns AAC byte array. */
    fun encodePcmToAac(pcmData: ByteArray): ByteArray {
        val format = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, CHANNELS)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcmData.size)

        val codec = MediaCodec.createEncoderByType(AUDIO_MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val output = ByteArrayOutputStream()
        val inputBuffer = ByteBuffer.allocateDirect(pcmData.size)
        val bufferInfo = MediaCodec.BufferInfo()
        var inputOffset = 0
        var outputDone = false

        while (!outputDone) {
            // Feed input
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuf = codec.getInputBuffer(inputIndex) ?: continue
                val remaining = minOf(4096, pcmData.size - inputOffset)
                if (remaining > 0) {
                    inputBuf.put(pcmData, inputOffset, remaining)
                    codec.queueInputBuffer(inputIndex, 0, remaining, inputOffset * 1000L / (SAMPLE_RATE * 2), 0)
                    inputOffset += remaining
                } else {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }

            // Get output
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex >= 0) {
                val outputBuf = codec.getOutputBuffer(outputIndex) ?: continue
                val data = ByteArray(bufferInfo.size)
                outputBuf.get(data)
                output.write(data)
                codec.releaseOutputBuffer(outputIndex, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }

        codec.stop()
        codec.release()
        return output.toByteArray()
    }

    /** Decode AAC to PCM for playback. */
    fun decodeAacToPcm(aacData: ByteArray): ByteArray {
        val format = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, CHANNELS)
        val codec = MediaCodec.createDecoderByType(AUDIO_MIME)
        codec.configure(format, null, null, 0)
        codec.start()

        val output = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputOffset = 0
        var outputDone = false

        while (!outputDone) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuf = codec.getInputBuffer(inputIndex) ?: continue
                val remaining = minOf(4096, aacData.size - inputOffset)
                if (remaining > 0) {
                    inputBuf.put(aacData, inputOffset, remaining)
                    codec.queueInputBuffer(inputIndex, 0, remaining, 0, 0)
                    inputOffset += remaining
                } else {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex >= 0) {
                val outputBuf = codec.getOutputBuffer(outputIndex) ?: continue
                val data = ByteArray(bufferInfo.size)
                outputBuf.get(data)
                output.write(data)
                codec.releaseOutputBuffer(outputIndex, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }

        codec.stop()
        codec.release()
        return output.toByteArray()
    }
}
```

### Flutter Side

#### 3. `lib/widgets/voice_recorder_button.dart`
**Purpose**: Press-and-hold recording button (WhatsApp-style)

```dart
class VoiceRecorderButton extends StatefulWidget {
  final Function(Uint8List audioData, Duration duration) onRecordComplete;
  final VoidCallback? onCancel;
  const VoiceRecorderButton({required this.onRecordComplete, this.onCancel});
  @override
  State<VoiceRecorderButton> createState() => _VoiceRecorderButtonState();
}

class _VoiceRecorderButtonState extends State<VoiceRecorderButton>
    with SingleTickerProviderStateMixin {
  bool _isRecording = false;
  late AnimationController _animController;
  DateTime? _startTime;

  @override
  void initState() {
    super.initState();
    _animController = AnimationController(
      vsync: this, duration: Duration(milliseconds: 500),
    )..repeat(reverse: true);
  }

  // On long press start: begin recording
  // On long press end: stop recording, call onRecordComplete
  // On long press cancel: cancel recording
}
```

#### 4. `lib/widgets/voice_message_bubble.dart`
**Purpose**: Audio player with waveform

```dart
class VoiceMessageBubble extends StatefulWidget {
  final ChatMessage message;
  const VoiceMessageBubble({required this.message});
  @override
  State<VoiceMessageBubble> createState() => _VoiceMessageBubbleState();
}

class _VoiceMessageBubbleState extends State<VoiceMessageBubble> {
  bool _isPlaying = false;
  Duration _position = Duration.zero;
  // AudioPlayer from just_audio package

  @override
  Widget build(BuildContext context) {
    return Container(
      // Waveform visualization
      // Play/pause button
      // Duration display
      // Progress bar
    );
  }
}
```

#### 5. `lib/widgets/audio_waveform.dart`
**Purpose**: Simple waveform visualization

```dart
class AudioWaveform extends StatelessWidget {
  final List<double> amplitudes;
  final Duration position;
  final Duration totalDuration;
  final Color activeColor;
  final Color inactiveColor;
  const AudioWaveform({...});
  @override
  Widget build(BuildContext context) {
    // Draw vertical bars representing audio amplitude
    // Highlight played portion
  }
}
```

## New Dependencies

```yaml
dependencies:
  just_audio: ^0.9.42       # Audio playback
  record: ^5.2.0            # Audio recording (cross-platform)
  path_provider: ^2.1.6      # already installed
```

## Wire Protocol

### VOICE_MSG payload format:
```
[0..15]   transferId (16 bytes)
[16..19]  durationMs (4 bytes, big-endian)
[20..23]  sampleRate (4 bytes, big-endian)
[24..27]  channels (2 bytes) + format (2 bytes: 0=AAC, 1=Opus)
[28..]    encoded audio data
```

For small voice messages (<244 bytes after encoding), send as single VOICE_MSG frame.
For larger ones, use FILE_CHUNK transfer mechanism with VOICE_MSG as FILE_START equivalent.

## Implementation Order

1. Add `RECORD_AUDIO` permission handling
2. Create `VoiceRecorder.kt` + `VoiceEncoder.kt`
3. Add `VOICE_MSG` to `MessageType.kt`
4. Add MethodChannel commands: `startRecording`, `stopRecording`, `sendVoiceMessage`
5. Create Flutter widgets: recorder button, waveform, player bubble
6. Update `ChatView` to include voice button and voice message bubble

## Edge Cases

- **Permission denied**: Show snackbar with settings link
- **Recording interrupted**: Handle AudioRecord errors gracefully
- **Large voice file**: Use file transfer mechanism for >244 bytes
- **Concurrent recording**: Only one recording at a time

---

# FEATURE 5: NETWORK MAP (Topology Visualization)

## Files to Create

### Flutter Side

#### 1. `lib/screens/network_map_view.dart`
**Purpose**: Interactive force-directed graph visualization

```dart
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class NetworkMapView extends ConsumerStatefulWidget {
  const NetworkMapView({super.key});
  @override
  ConsumerState<NetworkMapView> createState() => _NetworkMapViewState();
}

class _NetworkMapViewState extends ConsumerState<NetworkMapView>
    with SingleTickerProviderStateMixin {
  late AnimationController _animController;
  final List<GraphNode> _nodes = [];
  final List<GraphEdge> _edges = [];
  final TransformationController _transformationController = TransformationController();
  GraphNode? _selectedNode;
  Offset _panOffset = Offset.zero;
  double _scale = 1.0;

  @override
  void initState() {
    super.initState();
    _animController = AnimationController(
      vsync: this,
      duration: Duration(milliseconds: 16), // ~60fps
    )..repeat();

    _animController.addListener(_tick);
  }

  @override
  void dispose() {
    _animController.dispose();
    _transformationController.dispose();
    super.dispose();
  }

  void _tick() {
    // Force-directed layout iteration
    _applyForces();
    setState(() {});
  }

  void _applyForces() {
    // Repulsion between all nodes (Coulomb's law)
    for (int i = 0; i < _nodes.length; i++) {
      for (int j = i + 1; j < _nodes.length; j++) {
        final a = _nodes[i];
        final b = _nodes[j];
        final dx = b.x - a.x;
        final dy = b.y - a.y;
        final dist = sqrt(dx * dx + dy * dy).clamp(1.0, 500.0);
        final force = 5000.0 / (dist * dist); // repulsion
        final fx = (dx / dist) * force;
        final fy = (dy / dist) * force;

        if (!a.isFixed) { a.x -= fx * 0.01; a.y -= fy * 0.01; }
        if (!b.isFixed) { b.x += fx * 0.01; b.y += fy * 0.01; }
      }
    }

    // Attraction along edges (Hooke's law)
    for (final edge in _edges) {
      final a = edge.from;
      final b = edge.to;
      final dx = b.x - a.x;
      final dy = b.y - a.y;
      final dist = sqrt(dx * dx + dy * dy).clamp(1.0, 500.0);
      final force = (dist - 150) * 0.005; // spring (target distance 150)
      final fx = (dx / dist) * force;
      final fy = (dy / dist) * force;

      if (!a.isFixed) { a.x += fx; a.y += fy; }
      if (!b.isFixed) { b.x -= fx; b.y -= fy; }
    }

    // Centering force
    final centerX = 300.0;
    final centerY = 400.0;
    for (final node in _nodes) {
      if (!node.isFixed) {
        node.x += (centerX - node.x) * 0.001;
        node.y += (centerY - node.y) * 0.001;
      }
      // Keep within bounds
      node.x = node.x.clamp(50.0, 550.0);
      node.y = node.y.clamp(50.0, 750.0);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Tarmoq xaritasi'),
        actions: [
          IconButton(
            icon: const Icon(Icons.info_outline),
            onPressed: _showNodeInfo,
          ),
        ],
      ),
      body: GestureDetector(
        onScaleUpdate: (details) {
          setState(() {
            _scale = (_scale * details.scale).clamp(0.3, 3.0);
            _panOffset += details.focalPointDelta;
          });
        },
        onTapUp: (details) {
          final tapPos = (details.localPosition - _panOffset) / _scale;
          _selectedNode = _nodes.firstWhereOrNull(
            (n) => (Offset(n.x, n.y) - tapPos).distance < 30,
          );
          setState(() {});
        },
        child: CustomPaint(
          painter: NetworkMapPainter(
            nodes: _nodes,
            edges: _edges,
            scale: _scale,
            panOffset: _panOffset,
            selectedNode: _selectedNode,
          ),
          size: Size.infinite,
        ),
      ),
    );
  }
}

class GraphNode {
  String id;
  String name;
  double x, y;
  bool isSelf;
  bool isFixed;
  int quality; // 0-100
  int hops;
  bool isOnline;

  GraphNode({
    required this.id,
    required this.name,
    required this.x,
    required this.y,
    this.isSelf = false,
    this.isFixed = false,
    this.quality = 50,
    this.hops = 0,
    this.isOnline = true,
  });
}

class GraphEdge {
  GraphNode from, to;
  int quality; // 0-100
  int hops;

  GraphEdge({required this.from, required this.to, this.quality = 50, this.hops = 1});
}
```

#### 2. `lib/widgets/network_map_painter.dart`
**Purpose**: CustomPainter for 60fps graph rendering

```dart
class NetworkMapPainter extends CustomPainter {
  final List<GraphNode> nodes;
  final List<GraphEdge> edges;
  final double scale;
  final Offset panOffset;
  final GraphNode? selectedNode;

  NetworkMapPainter({...});

  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.translate(panOffset.dx, panOffset.dy);
    canvas.scale(scale);

    // Draw edges with quality-based color
    for (final edge in edges) {
      final paint = Paint()
        ..strokeWidth = _edgeThickness(edge.quality)
        ..color = _qualityColor(edge.quality)
        ..style = PaintingStyle.stroke;
      canvas.drawLine(
        Offset(edge.from.x, edge.from.y),
        Offset(edge.to.x, edge.to.y),
        paint,
      );

      // Draw hop count label on edge
      _drawEdgeLabel(canvas, edge);
    }

    // Draw nodes
    for (final node in nodes) {
      _drawNode(canvas, node);
    }

    // Draw selected node info panel
    if (selectedNode != null) {
      _drawInfoPanel(canvas, selectedNode!);
    }

    canvas.restore();
  }

  void _drawNode(Canvas canvas, GraphNode node) {
    final radius = node.isSelf ? 28.0 : 20.0;
    final paint = Paint()..color = node.isSelf
        ? const Color(0xFF00C853)
        : _qualityColor(node.quality);

    // Glow effect for online nodes
    if (node.isOnline) {
      final glowPaint = Paint()
        ..color = paint.color.withOpacity(0.3)
        ..maskFilter = MaskFilter.blur(BlurStyle.normal, 15);
      canvas.drawCircle(Offset(node.x, node.y), radius + 8, glowPaint);
    }

    canvas.drawCircle(Offset(node.x, node.y), radius, paint);

    // Node name
    final textPainter = TextPainter(
      text: TextSpan(
        text: node.name.length > 8 ? '${node.name.substring(0, 8)}...' : node.name,
        style: TextStyle(
          color: Colors.white,
          fontSize: 10,
          fontWeight: FontWeight.w500,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    textPainter.paint(canvas, Offset(
      node.x - textPainter.width / 2,
      node.y + radius + 4,
    ));

    // Self indicator (double circle)
    if (node.isSelf) {
      canvas.drawCircle(
        Offset(node.x, node.y), radius - 4,
        Paint()..color = Colors.white..style = PaintingStyle.stroke..strokeWidth = 2,
      );
    }
  }

  Color _qualityColor(int quality) {
    if (quality >= 70) return const Color(0xFF00C853);
    if (quality >= 40) return const Color(0xFFFFC107);
    return const Color(0xFFEF4444);
  }

  double _edgeThickness(int quality) => 1.0 + (quality / 50);

  void _drawEdgeLabel(Canvas canvas, GraphEdge edge) {
    final midX = (edge.from.x + edge.to.x) / 2;
    final midY = (edge.from.y + edge.to.y) / 2;
    final textPainter = TextPainter(
      text: TextSpan(
        text: '${edge.hops}q',
        style: TextStyle(color: Colors.white70, fontSize: 9),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    textPainter.paint(canvas, Offset(midX - 6, midY - 8));
  }

  void _drawInfoPanel(Canvas canvas, GraphNode node) {
    // Semi-transparent panel below node showing:
    // - Name, Signal strength, Hops, Link quality, Transport, Online status
  }

  @override
  bool shouldRepaint(covariant NetworkMapPainter oldDelegate) => true;
}
```

#### 3. Update `lib/screens/network_view.dart`

Add navigation button to Network Map:
```dart
// In AppBar actions, add:
IconButton(
  icon: Icon(Icons.map),
  tooltip: 'Tarmoq xaritasi',
  onPressed: () {
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => const NetworkMapView()),
    );
  },
),
```

### Kotlin Side

#### 4. Modify `MeshEngine.kt`

Add new MethodChannel commands for topology data:

```kotlin
"getTopology" -> {
    val peers = peerStore.all()
    val routes = routing.routeSnapshot()
    val stats = routing.stats()

    // Build nodes list
    val nodes = mutableListOf<Map<String, Any?>>()
    // Self node
    nodes.add(mapOf(
        "id" to identity.deviceId(),
        "name" to identity.displayName(),
        "isSelf" to true,
        "isOnline" to true,
        "quality" to 100,
        "hops" to 0,
    ))

    // Peer nodes
    peers.forEach { peer ->
        val route = routing.findRoute(peer.deviceId)
        nodes.add(mapOf(
            "id" to peer.deviceId,
            "name" to peer.displayName,
            "isSelf" to false,
            "isOnline" to (peer.lastSeenMs > 0 && System.currentTimeMillis() - peer.lastSeenMs < 60_000),
            "quality" to (route?.qualityScore() ?: peer.linkQuality),
            "hops" to (route?.hopCount ?: 0),
        ))
    }

    // Build edges list from routes
    val edges = routes.map { route ->
        val fromNode = if (route["nextHop"] == identity.deviceId()) {
            identity.deviceId()
        } else {
            route["nextHop"] as String
        }
        mapOf(
            "from" to fromNode,
            "to" to route["destination"],
            "quality" to route["quality"],
            "hops" to route["hopCount"],
        )
    }

    result.success(mapOf(
        "nodes" to nodes,
        "edges" to edges,
        "stats" to stats,
    ))
}
```

## Data Models

### `TopologyData` (Dart)
```dart
class TopologyData {
  final List<TopologyNode> nodes;
  final List<TopologyEdge> edges;
  final Map<String, dynamic> stats;
}

class TopologyNode {
  final String id;
  final String name;
  final bool isSelf;
  final bool isOnline;
  final int quality;
  final int hops;
}

class TopologyEdge {
  final String from;
  final String to;
  final int quality;
  final int hops;
}
```

## Force-Directed Layout Algorithm (Detailed)

### Parameters:
- **Repulsion constant** (Coulomb): `k_rep = 5000`
- **Attraction constant** (Hooke): `k_att = 0.005`
- **Target edge length**: `150px`
- **Damping factor**: `0.01` (velocity decay)
- **Centering force**: `0.001` toward canvas center
- **Iteration rate**: 60fps (16ms per frame)

### Algorithm per tick:
```
1. Initialize all node velocities to (0, 0)
2. For each pair of nodes (i, j):
   - Compute repulsion force: F = k_rep / dist²
   - Apply force to both nodes (Newton's 3rd law)
3. For each edge (i, j):
   - Compute attraction force: F = k_att * (dist - targetLen)
   - Apply force to both nodes
4. For each non-self node:
   - Apply centering force toward canvas center
5. For each node:
   - Update position: pos += velocity * damping
   - Clamp to canvas bounds
6. Self node is fixed at center
```

### Performance:
- Max 20 nodes (mesh network practical limit)
- O(n²) per frame is fine for 20 nodes (400 operations)
- Canvas uses `CustomPainter` for hardware-accelerated rendering
- `shouldRepaint` returns true every frame for animation

## Edge Cases

- **No peers**: Show self-node only with "Hozircha hech kim yo'q" message
- **Single peer**: Self + peer, edge between them
- **Multi-hop**: Show intermediate hops as separate nodes (from route table)
- **Node disappears**: Animate fade-out, remove after 5s
- **Route change**: Animate edge color change smoothly

## Implementation Order

1. Create `NetworkMapView` with CustomPaint
2. Create `NetworkMapPainter`
3. Add `getTopology` MethodChannel command
4. Add data models
5. Connect to `peersProvider` + `routeChanged` events
6. Add pan/zoom/gesture handling
7. Add node tap info panel
8. Add to NetworkView navigation

---

# COMPLETE FILE MANIFEST

## New Files (Kotlin)

| # | Path | Purpose |
|---|------|---------|
| 1 | `android/.../crypto/DoubleRatchet.kt` | Double Ratchet state machine |
| 2 | `android/.../crypto/RatchetSession.kt` | Session persistence store |
| 3 | `android/.../protocol/FileTransfer.kt` | File chunking/assembly/progress |
| 4 | `android/.../protocol/MediaCompressor.kt` | Image compression |
| 5 | `android/.../protocol/GroupManager.kt` | Group CRUD + key distribution |
| 6 | `android/.../protocol/VoiceRecorder.kt` | Audio recording |
| 7 | `android/.../protocol/VoiceEncoder.kt` | PCM→AAC encoding |

## New Files (Flutter)

| # | Path | Purpose |
|---|------|---------|
| 8 | `lib/models/message_model.dart` | Extended ChatMessage with media |
| 9 | `lib/models/group_model.dart` | Group + GroupMember models |
| 10 | `lib/crypto/ratchet_service.dart` | Ratchet MethodChannel wrapper |
| 11 | `lib/widgets/file_bubble.dart` | File message bubble |
| 12 | `lib/widgets/voice_recorder_button.dart` | Press-and-hold recorder |
| 13 | `lib/widgets/voice_message_bubble.dart` | Audio player + waveform |
| 14 | `lib/widgets/audio_waveform.dart` | Waveform visualization |
| 15 | `lib/screens/media_preview.dart` | Full-screen image viewer |
| 16 | `lib/screens/group_chat_view.dart` | Group chat screen |
| 17 | `lib/screens/create_group_screen.dart` | Create group screen |
| 18 | `lib/screens/group_info_screen.dart` | Group info/settings |
| 19 | `lib/screens/network_map_view.dart` | Force-directed graph map |
| 20 | `lib/widgets/network_map_painter.dart` | CustomPainter for graph |
| 21 | `lib/core/file_transfer_service.dart` | File transfer MethodChannel |
| 22 | `lib/core/voice_service.dart` | Voice recording MethodChannel |
| 23 | `lib/core/group_service.dart` | Group MethodChannel wrapper |

## Modified Files (Kotlin)

| # | Path | Changes |
|---|------|---------|
| 24 | `BleTransport.kt` | Add `notifyChunks()`, rewrite `notifyDevice()`, `sendFrame()`, `sendToAll()` |
| 25 | `MessageType.kt` | Add 9 new enum values (0x10-0x41) |
| 26 | `MeshFrame.kt` | No changes needed (payload is variable-length ByteArray already) |
| 27 | `RoutingEngine.kt` | Add FILE_START/CHUNK/END handlers, GROUP_MSG/KEY_DIST handlers, VOICE_MSG handler, RATCHET_MSG handler, sendFile/sendGroupMessage/sendVoiceMessage methods, update `handleIncomingFrame()` and `routeFrame()` |
| 28 | `MeshEngine.kt` | Add MethodChannel commands: sendFile, sendImage, cancelTransfer, createGroup, sendGroupMessage, getGroups, getGroupInfo, addMember, removeMember, leaveGroup, startRecording, stopRecording, sendVoiceMessage, initSession, hasSession, getTopology |
| 29 | `MeshCrypto.kt` | Add `chachaCipherWithKey()` helper |
| 30 | `MessageStore.kt` | Add group message storage, file transfer record storage |
| 31 | `PeerStore.kt` | No changes needed |

## Modified Files (Flutter)

| # | Path | Changes |
|---|------|---------|
| 32 | `pubspec.yaml` | Add image_picker, file_picker, photo_view, just_audio, record |
| 33 | `lib/core/mesh_service.dart` | Add sendFile, sendImage, cancelTransfer, startRecording, stopRecording, sendVoiceMessage, createGroup, sendGroupMessage, getGroups, getTopology, initSession methods |
| 34 | `lib/core/providers.dart` | Add groupsProvider, topologyProvider, fileTransfersProvider |
| 35 | `lib/screens/chat_view.dart` | Replace _ChatMessage with ChatMessage model, add media input buttons, handle FILE/VOICE message types in event listener, update _MessageBubble to render media |
| 36 | `lib/screens/contacts_view.dart` | Add "Create Group" button, group list section |
| 37 | `lib/screens/network_view.dart` | Add navigation to Network Map |
| 38 | `lib/screens/topology_view.dart` | Add "View Map" button |
| 39 | `lib/screens/home_screen.dart` | Add RECORD_AUDIO permission handling |
| 40 | `lib/main.dart` | No changes needed |

## New Dependencies

### pubspec.yaml additions:
```yaml
image_picker: ^1.1.2          # Pick images from gallery/camera
file_picker: ^8.1.6           # Pick any file type
photo_view: ^0.15.0           # Zoomable image viewer
just_audio: ^0.9.42           # Audio playback
record: ^5.2.0                # Audio recording
```

### Android permissions to add (AndroidManifest.xml):
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
```

---

# COMPLETE MessageType ENUM

```kotlin
enum class MessageType(val code: Byte) {
    // Existing (0x01-0x08)
    PEER_PING(0x01),
    TEXT(0x02),
    PAIR_REQ(0x03),
    PAIR_ACK(0x04),
    RELAY(0x05),
    DELIVERY_REPORT(0x06),
    FIND_PEER(0x07),
    FIND_PEER_ACK(0x08),

    // File Transfer (0x10-0x12)
    FILE_START(0x10),
    FILE_CHUNK(0x11),
    FILE_END(0x12),

    // Group Messaging (0x20-0x25)
    GROUP_CREATE(0x20),
    GROUP_MSG(0x21),
    GROUP_ADD_MEMBER(0x22),
    GROUP_REMOVE_MEMBER(0x23),
    GROUP_KEY_DIST(0x24),
    GROUP_LEAVE(0x25),

    // Voice Message (0x30)
    VOICE_MSG(0x30),

    // Double Ratchet (0x40-0x41)
    RATCHET_INIT(0x40),
    RATCHET_MSG(0x41);

    companion object {
        fun fromCode(code: Byte): MessageType? = entries.firstOrNull { it.code == code }
    }
}
```

---

# IMPLEMENTATION SCHEDULE

## Week 1: Foundation
- Day 1-2: BLE notification chunking fix (Phase 0)
- Day 3-5: Double Ratchet implementation (Feature 4)
- Day 6-7: Double Ratchet integration into RoutingEngine

## Week 2: File Sharing
- Day 1-2: FileTransfer.kt + MediaCompressor.kt
- Day 3-4: MethodChannel integration + Flutter file picker
- Day 5-7: Chat UI updates (media bubbles, preview, progress)

## Week 3: Voice + Groups
- Day 1-2: Voice recording/encoding (Kotlin)
- Day 3-4: Voice UI widgets (Flutter)
- Day 5-7: Group messaging (GroupStore + UI)

## Week 4: Network Map + Polish
- Day 1-3: Network map (force-directed graph)
- Day 4-5: Testing and edge cases
- Day 6-7: Performance optimization and final testing

---

# TESTING STRATEGY

## Unit Tests
- `DoubleRatchetTest.kt`: encrypt/decrypt round-trip, out-of-order, skipped messages, serialize/deserialize
- `FileTransferTest.kt`: chunk generation, assembly, progress tracking, error cases
- `GroupStoreTest.kt`: CRUD operations, key management
- `VoiceEncoderTest.kt`: PCM→AAC→PCM round-trip

## Widget Tests
- `FileBubbleTest`: renders image/file correctly
- `VoiceMessageBubbleTest`: play/pause states
- `NetworkMapPainterTest`: renders nodes and edges

## Integration Tests
- File transfer: pick → compress → encrypt → chunk → transfer → decrypt → save → preview
- Voice: record → encode → send → receive → decode → play
- Group: create → add member → send message → receive message
- Network map: peers discovered → nodes appear → edges drawn → tap shows info

## Manual Testing Checklist
- [ ] BLE notification chunking: send 1KB file over BLE, verify all chunks arrive
- [ ] Double Ratchet: 10 messages each direction, verify decryption
- [ ] Image sharing: pick 5MB image, compress, send, receive, view full screen
- [ ] File sharing: send PDF/ZIP, verify progress, open received file
- [ ] Voice: record 30s, send, play back, verify audio quality
- [ ] Group: create with 3 members, send messages, verify all receive
- [ ] Network map: 5+ peers, verify force layout, pan/zoom, tap info
- [ ] Offline: send file to offline peer, verify delivery when online
- [ ] Backward compat: old client (TEXT only) + new client (RATCHET_MSG) interop

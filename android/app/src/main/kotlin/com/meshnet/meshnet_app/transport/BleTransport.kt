package com.meshnet.meshnet_app.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.meshnet.meshnet_app.protocol.MeshFrame
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BLE transport: advertising + scanning + GATT server/receive + GATT client/send.
 *
 * Discovery model:
 *  - Each node advertises itself with manufacturer data:
 *      MFG_ID(0x4D4E) + marker(0x4D 0x4E) + deviceId(16-byte UUID)
 *    Why isn't service UUID in the advertisement? Advertisement size in legacy is
 *    limited to 31 bytes — 128-bit service UUID (18B) + manufacturer (20B) doesn't fit.
 *    Device identification is obtained through manufacturer data, GATT service
 *    however is opened after connection.
 *
 * Message exchange:
 *  - Receive: GATT server write to RX characteristic.
 *  - Send: write to peer's GATT server RX characteristic (client).
 *  - Lazy connection: connect only when needed to send; LRU pool,
 *    cap for Android BLE concurrent connection limit (~7-9).
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val deviceName: String,
    private val deviceId: String,
) {
    companion object {
        private const val TAG = "BleTransport"
        val SERVICE_UUID: UUID = UUID.fromString("6a4e9f01-1d5b-4f1a-8f2b-2e75a4b8c0d1")
        val TX_CHAR_UUID: UUID = UUID.fromString("6a4e9f02-1d5b-4f1a-8f2b-2e75a4b8c0d1")
        val RX_CHAR_UUID: UUID = UUID.fromString("6a4e9f03-1d5b-4f1a-8f2b-2e75a4b8c0d1")
        // Max BLE message size (ATT MTU 512 requested, 512 safe chunk)
        const val MAX_PAYLOAD = 512

        // Discovery: manufacturer data (for advertisement size limit)
        const val MFG_ID = 0x4D4E // "MN"
        private val MARKER = byteArrayOf(0x4D, 0x4E)

        // Limit of simultaneously open GATT client connections
        const val MAX_GATT_CLIENTS = 6

        // Multi-chunk header marker: [0x4D 0x4E 0xFF] — regular frame version
        // byte is 0x01, marker is 0xFF, therefore no collision.
        const val CHUNK_MARKER: Byte = 0xFF.toByte()
        const val CHUNK_HEADER_SIZE = 7

        // BLE CCC descriptor (Client Characteristic Configuration)
        val CCC_DESCRIPTOR_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    interface Listener {
        fun onFrameReceived(frame: MeshFrame)
        fun onPeerDiscovered(deviceId: String, displayName: String, rssi: Int)
        fun onPeerLost(deviceId: String)
        fun onError(message: String)
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // GATT server: simple var to avoid null in lazy init (retried)
    private var gattServer: BluetoothGattServer? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Re-register service when Bluetooth is turned on. */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
                if (state == BluetoothAdapter.STATE_ON) {
                    Log.i(TAG, "Bluetooth turned on — reconfiguring GATT server")
                    registerService()
                }
            }
        }
    }

    private fun ensureGattServer(): BluetoothGattServer? {
        if (gattServer != null) return gattServer
        gattServer = adapter?.let { a ->
            try {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                    .openGattServer(context, gattServerCallback)
            } catch (e: Exception) {
                Log.e(TAG, "openGattServer error: ${e.message}")
                null
            }
        }
        if (gattServer == null) {
            Log.e(TAG, "GATT server didn't open (openGattServer null) — retrying")
        }
        return gattServer
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private var advertising = false
    private var stopped = false
    private var receiverRegistered = false
    private var serverRetryCount = 0
    private val listenerList = CopyOnWriteArrayList<Listener>()

    // BLE address <-> mesh deviceId (UUID) — bidirectional map
    private val deviceIdByAddress = mutableMapOf<String, String>()
    private val addressByDeviceId = mutableMapOf<String, String>()

    // Peers connected to server: address -> BluetoothDevice
    private val connectedServerDevices = ConcurrentHashMap<String, BluetoothDevice>()

    // Link quality tracking: address -> RSSI history + packet statistics
    private val peerRssiHistory = ConcurrentHashMap<String, MutableList<Int>>()
    private val peerPacketSuccess = ConcurrentHashMap<String, Int>()
    private val peerPacketTotal = ConcurrentHashMap<String, Int>()

    // GATT client pool (lazy connections)
    private class GattClient(val address: String, val gatt: BluetoothGatt) {
        var rxCharacteristic: BluetoothGattCharacteristic? = null
        var connected = false
        var connecting = false
        var mtu: Int = 23
        var lastUsed = System.currentTimeMillis()
        val pending = ArrayDeque<Pair<ByteArray, (Boolean) -> Unit>>()
        var writing = false
        var discoverAttempts = 0
        var subscribedToTx = false // whether subscribed to TX notification
    }

    private val gattClients = LinkedHashMap<String, GattClient>()

    private class PendingWrite(
        val client: GattClient,
        val char: BluetoothGattCharacteristic,
        val data: ByteArray,
        val nextOffset: Int,
        val onSent: (Boolean) -> Unit,
    )

    private val pendingWrites = mutableMapOf<String, PendingWrite>()

    /** Assembling multi-chunk reception (server side): address -> assembly in progress.
     *  Header: [0x4D 0x4E 0xFF] + len(4) + chunks. */
    private class Assembly(val expected: Int, val buffer: ByteArrayOutputStream)

    // address -> current assembly
    private val assembling = mutableMapOf<String, Assembly>()

    fun addListener(l: Listener) { listenerList.add(l) }
    fun removeListener(l: Listener) { listenerList.remove(l) }

    /** Address↔deviceId mapping learned from received frame or other transport. */
    fun rememberAddress(deviceId: String, address: String) {
        Log.d(TAG, "rememberAddress: $deviceId -> $address")
        deviceIdByAddress[address] = deviceId
        addressByDeviceId[deviceId] = address
    }

    /** Number of known BLE peers (for debugging). */
    fun knownPeerCount(): Int = addressByDeviceId.size

    /** Get link quality: 0-100 (based on RSSI + success rate). */
    fun getLinkQuality(address: String): Int {
        val rssiHistory = peerRssiHistory[address]
        val success = peerPacketSuccess[address] ?: 0
        val total = peerPacketTotal[address] ?: 0

        // RSSI-based quality (0-100): -90=0, -30=100
        val avgRssi = if (rssiHistory != null && rssiHistory.isNotEmpty()) {
            rssiHistory.sum() / rssiHistory.size
        } else 0
        val rssiQuality = ((avgRssi + 90) * 100 / 60).coerceIn(0, 100)

        // Success rate (0-100)
        val successRate = if (total > 0) (success * 100 / total) else 50

        // Weighted average: 60% RSSI, 40% success rate
        return (rssiQuality * 60 + successRate * 40) / 100
    }

    /** Track RSSI (from scan result). */
    private fun trackRssi(address: String, rssi: Int) {
        val history = peerRssiHistory.getOrPut(address) { mutableListOf() }
        history.add(rssi)
        if (history.size > 20) history.removeAt(0) // last 20 RSSI values
    }

    /** When a packet is sent. */
    fun trackPacketSent(address: String) {
        peerPacketTotal.compute(address) { _, v -> (v ?: 0) + 1 }
    }

    /** When a packet is successfully received. */
    fun trackPacketSuccess(address: String) {
        peerPacketSuccess.compute(address) { _, v -> (v ?: 0) + 1 }
    }

    /** When a packet fails. */
    fun trackPacketFailure(address: String) {
        peerPacketTotal.compute(address) { _, v -> (v ?: 0) + 1 }
    }

    /** Pre-connect when peer found — ready to send messages. */
    @SuppressLint("MissingPermission")
    fun preConnect(targetDeviceId: String) {
        val address = addressByDeviceId[targetDeviceId] ?: return
        synchronized(gattClients) {
            val existing = gattClients[address]
            if (existing != null && (existing.connected || existing.connecting)) return
            // exists but disconnected — reconnect
            if (existing != null) {
                existing.connecting = true
                try { existing.gatt.connect() } catch (_: Exception) {}
                return
            }
        }
        // new connection
        val device = try { adapter?.getRemoteDevice(address) } catch (_: Exception) { null }
        if (device == null) return
        val gatt = try {
            device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: Exception) { null }
        if (gatt == null) return
        val client = GattClient(address, gatt)
        client.connecting = true
        synchronized(gattClients) { gattClients[address] = client }
        Log.d(TAG, "Pre-connect: $address ($targetDeviceId)")
    }

    // ---------------- Advertising ----------------

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (advertising) return
        val advertiser: BluetoothLeAdvertiser
        try {
            advertiser = adapter?.bluetoothLeAdvertiser ?: run {
                listenerList.forEach { it.onError("BLE advertiser not available") }
                return
            }
        } catch (e: SecurityException) {
            // Android 12+: falls through here when BLUETOOTH_ADVERTISE permission not requested
            // App won't crash — Flutter side banner will display it.
            Log.e(TAG, "BLE advertise permission missing", e)
            listenerList.forEach { it.onError("BLUETOOTH_ADVERTISE permission missing") }
            return
        }
        this.advertiser = advertiser

        val mfgPayload = MARKER + uuidToBytes(deviceId)
        val advertiseData = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(true)
            .addManufacturerData(MFG_ID, mfgPayload)
            .build()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                advertising = true
                Log.i(TAG, "BLE advertising started: $deviceName")
            }

            override fun onStartFailure(errorCode: Int) {
                advertising = false
                Log.e(TAG, "BLE advertising failed: code=$errorCode")
                listenerList.forEach { it.onError("BLE advertising didn't start (code=$errorCode)") }
            }
        }
        advertiseCallback?.let {
            try { advertiser.stopAdvertising(it) } catch (_: Exception) {}
        }
        this.advertiseCallback = callback
        advertiser.startAdvertising(settings, advertiseData, callback)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        advertiseCallback?.let {
            try { advertiser?.stopAdvertising(it) } catch (_: Exception) {}
        }
        advertiseCallback = null
        advertising = false
    }

    // ---------------- Scanning ----------------

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner: BluetoothLeScanner
        try {
            scanner = adapter?.bluetoothLeScanner ?: run {
                listenerList.forEach { it.onError("BLE scanner not available") }
                return
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan permission missing", e)
            listenerList.forEach { it.onError("BLUETOOTH_SCAN permission missing") }
            return
        }
        this.scanner = scanner

        val scanFilter = ScanFilter.Builder()
            .setManufacturerData(MFG_ID, MARKER, byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan error: code=$errorCode")
                listenerList.forEach { it.onError("BLE scan failed (code=$errorCode)") }
            }
        }
        this.scanCallback = cb
        try {
            scanner.startScan(listOf(scanFilter), settings, cb)
            Log.i(TAG, "BLE scan started")
        } catch (e: SecurityException) {
            // Android 12+: BLUETOOTH_PRIVILEGED permission missing — with filter
            // scan not possible, falling back to no filter.
            Log.w(TAG, "BLE scan error with filter ($e), falling back to no filter")
            try {
                scanner.startScan(null, settings, cb)
                Log.i(TAG, "BLE scan started (no filter)")
            } catch (e2: SecurityException) {
                Log.e(TAG, "BLE scan permission missing (even without filter): ${e2.message}")
                listenerList.forEach { it.onError("BLUETOOTH_SCAN permission missing") }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = scanCallback ?: return
        try { scanner?.stopScan(cb) } catch (_: Exception) {}
        scanCallback = null
    }

    private fun handleScanResult(result: ScanResult) {
        val address = result.device.address

        var peerId: String? = null
        val mfg = result.scanRecord?.manufacturerSpecificData?.get(MFG_ID)
        if (mfg != null && mfg.size >= 2 &&
            mfg[0] == MARKER[0] && mfg[1] == MARKER[1] && mfg.size >= 18
        ) {
            peerId = bytesToUuid(mfg.copyOfRange(2, 18))
        }
        if (peerId == null) peerId = deviceIdByAddress[address]
        if (peerId == null || peerId == deviceId) return // self or unknown

        deviceIdByAddress[address] = peerId
        addressByDeviceId[peerId] = address

        val name = result.device.name ?: "MeshNet device"
        // RSSI tracking (for link quality)
        trackRssi(address, result.rssi)
        listenerList.forEach { it.onPeerDiscovered(peerId, name, result.rssi) }

        // Pre-connect: connect immediately when peer found — messages will be ready
        preConnect(peerId)
    }

    // ---------------- Sending messages (GATT client) ----------------

    fun sendFrame(targetDeviceId: String, frame: MeshFrame, onSent: (Boolean) -> Unit) {
        try {
            val address = addressByDeviceId[targetDeviceId]
            if (address == null) { onSent(false); return }
            val wire = MeshFrame.encode(frame)
            enqueueWrite(address, wire) { ok ->
                if (ok) {
                    onSent(true)
                } else {
                    Log.d(TAG, "enqueueWrite failed, notifyChunks fallback: $address")
                    notifyChunks(address, wire) { ok2 -> onSent(ok2) }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "sendFrame: permission missing ($targetDeviceId)", e)
            onSent(false)
        } catch (e: Exception) {
            Log.e(TAG, "sendFrame error ($targetDeviceId): ${e.message}")
            onSent(false)
        }
    }

    fun sendToAll(frame: MeshFrame) {
        val targets = synchronized(gattClients) { addressByDeviceId.entries.toList() }
        Log.d(TAG, "sendToAll: ${targets.size} peers, target=${frame.targetId}, type=${frame.type}")
        if (targets.isEmpty()) return
        val wire = MeshFrame.encode(frame)
        targets.forEach { (targetDeviceId, address) ->
            try {
                enqueueWrite(address, wire) { ok ->
                    if (!ok) {
                        Log.w(TAG, "sendToAll: enqueueWrite failed ($targetDeviceId), retrying with notifyChunks")
                        notifyChunks(address, wire) {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendToAll error ($targetDeviceId): ${e.message}")
            }
        }
    }

    /** Sends notification via TX characteristic to a peer connected to the server.
     *  @return true if successful. */
    private fun notifyDevice(address: String, data: ByteArray, onResult: (Boolean) -> Unit = {}) {
        notifyChunks(address, data, onResult)
    }

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

        val capacity = MAX_PAYLOAD
        if (data.size <= capacity) {
            val ok = sendSingleNotify(server, device, txChar, data)
            onDone(ok)
            return
        }

        val header = ByteArray(7).apply {
            this[0] = 0x4D
            this[1] = 0x4E
            this[2] = CHUNK_MARKER
            val len = data.size
            this[3] = (len ushr 24).toByte()
            this[4] = (len ushr 16).toByte()
            this[5] = (len ushr 8).toByte()
            this[6] = len.toByte()
        }
        val wire = header + data

        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < wire.size) {
            val end = minOf(offset + capacity, wire.size)
            chunks.add(wire.copyOfRange(offset, end))
            offset = end
        }

        mainHandler.post {
            notifyChunkParallel(server, device, txChar, chunks, onDone)
        }
    }

    /** Parallel chunk sending: max 3 chunks at a time. */
    @SuppressLint("MissingPermission")
    private fun notifyChunkParallel(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        txChar: BluetoothGattCharacteristic,
        chunks: List<ByteArray>,
        onDone: (Boolean) -> Unit,
    ) {
        if (chunks.isEmpty()) { onDone(true); return }
        val maxConcurrent = 3
        val batch = chunks.take(maxConcurrent)
        var completed = 0
        var failed = false

        for (chunk in batch) {
            mainHandler.post {
                val ok = sendSingleNotify(server, device, txChar, chunk)
                synchronized(this@BleTransport) {
                    completed++
                    if (!ok) failed = true
                    if (completed >= batch.size) {
                        if (failed || chunks.size <= maxConcurrent) {
                            onDone(!failed)
                        } else {
                            notifyChunkParallel(server, device, txChar, chunks.drop(maxConcurrent), onDone)
                        }
                    }
                }
            }
        }
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
            Log.e(TAG, "Single notify error: ${device.address} — ${e.message}")
            false
        }
    }

    /** Wire format for notification: no multi-chunk needed, but still
     *  must be safe for large payloads as well. */
    private fun wireBytesForNotification(data: ByteArray): ByteArray {
        if (data.size <= MAX_PAYLOAD) return data
        // Large payload: with multi-chunk header
        val header = ByteArray(7).apply {
            this[0] = MeshFrame.MAGIC1
            this[1] = MeshFrame.MAGIC2
            this[2] = 0xFF.toByte()
            val len = data.size
            this[3] = (len ushr 24).toByte()
            this[4] = (len ushr 16).toByte()
            this[5] = (len ushr 8).toByte()
            this[6] = len.toByte()
        }
        return header + data
    }

    /** Subscribes to notifications on the peer's TX characteristic.
     *  Notifications sent by the server are received via clientCallback.onCharacteristicChanged
     *  callback. */
    @SuppressLint("MissingPermission")
    private fun subscribeToTxNotification(gatt: BluetoothGatt, txChar: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(txChar, true)
            val descriptor = txChar.getDescriptor(CCC_DESCRIPTOR_UUID)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
                Log.d(TAG, "TX notification subscription requested: ${gatt.device.address}")
            } else {
                Log.w(TAG, "TX CCC descriptor not found: ${gatt.device.address}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "TX notification subscription error: ${e.message}")
        }
    }

    private fun enqueueWrite(address: String, data: ByteArray, onSent: (Boolean) -> Unit) {
        synchronized(gattClients) {
            val existing = gattClients[address]
            if (existing != null) {
                existing.pending.addLast(Pair(data, onSent))
                existing.lastUsed = System.currentTimeMillis()
                if (existing.connected && existing.rxCharacteristic != null) {
                    drain(existing)
                } else if (existing.connecting) {
                    // Connection in progress — duplicate connect() in Android BLE
                    // causes churn (connection dropped and reopened). Additional
                    // connect() not called — pending request is queued.
                } else if (existing.connected) {
                    // Connected, but service not found yet — discovery re-requested
                    Log.w(TAG, "enqueueWrite: connected but no rx — re-requesting discovery")
                    try { existing.gatt.discoverServices() } catch (_: Exception) {}
                } else {
                    // Connection dead/disconnected — attempting to reconnect
                    Log.w(TAG, "enqueueWrite: client disconnected — attempting reconnect ($address)")
                    existing.connecting = true
                    try { existing.gatt.connect() } catch (_: Exception) {}
                }
                return
            }

            val device: BluetoothDevice
            try {
                device = adapter?.getRemoteDevice(address) ?: run {
                    Log.w(TAG, "enqueueWrite: getRemoteDevice null ($address)")
                    onSent(false)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "enqueueWrite: getRemoteDevice error ($address): ${e.message}")
                onSent(false)
                return
            }
            val gatt: BluetoothGatt
            try {
                gatt = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                // Android 12+: BLUETOOTH_CONNECT permission missing — to prevent
                // exception and false "failed" message.
                Log.e(TAG, "connectGatt: permission missing ($address)", e)
                onSent(false)
                return
            } catch (e: Exception) {
                Log.w(TAG, "connectGatt error ($address): ${e.message}")
                onSent(false)
                return
            }
            if (gatt == null) {
                Log.w(TAG, "connectGatt returned null ($address)")
                onSent(false)
                return
            }
            val client = GattClient(address, gatt)
            client.connecting = true
            gattClients[address] = client
            client.pending.addLast(Pair(data, onSent))

            // LRU: if over limit, close the oldest idle connection
            while (gattClients.size > MAX_GATT_CLIENTS) {
                val oldest = gattClients.values.minByOrNull { it.lastUsed } ?: break
                if (oldest.pending.isNotEmpty()) break
                gattClients.remove(oldest.address)
                try { oldest.gatt.disconnect() } catch (_: Exception) {}
                try { oldest.gatt.close() } catch (_: Exception) {}
            }
            Log.d(TAG, "GATT client connecting: $address")
        }
    }

    private fun drain(client: GattClient) {
        val char = client.rxCharacteristic ?: return
        if (client.writing) return
        val next = client.pending.removeFirstOrNull() ?: return
        client.writing = true
        client.lastUsed = System.currentTimeMillis()
        // Wire bytes (if frame doesn't fit in single write — multi-chunk
        // with prefix). If it fits (≤244 and matching MTU), regular single-write.
        val wire = wireBytes(client, next.first)
        writeChunks(client, char, wire, 0, next.second)
    }

    /** Frame -> on-air bytes. Small frame in single write (per protocol),
     *  large frame is chunked with 7-byte header:
     *  [0x4D 0x4E 0xFF] + [len(4, big-endian)] + chunks.
     *  Server (receiver) switches to assembly mode via this marker. */
    private fun wireBytes(client: GattClient, data: ByteArray): ByteArray {
        val capacity = maxOf(20, minOf(MAX_PAYLOAD, client.mtu - 3))
        if (data.size <= capacity) return data
        val header = ByteArray(7).apply {
            this[0] = MeshFrame.MAGIC1
            this[1] = MeshFrame.MAGIC2
            this[2] = 0xFF.toByte()
            val len = data.size
            this[3] = (len ushr 24).toByte()
            this[4] = (len ushr 16).toByte()
            this[5] = (len ushr 8).toByte()
            this[6] = len.toByte()
        }
        return header + data
    }

    /** Safe chunk size for ATT payload to fit in a single write.
     *  3 bytes of attribute header (opcode+handle) are subtracted from MTU. */
    private fun chunkCapacity(mtu: Int): Int =
        maxOf(20, minOf(MAX_PAYLOAD, mtu - 3))

    @SuppressLint("MissingPermission")
    private fun writeChunks(
        client: GattClient,
        char: BluetoothGattCharacteristic,
        data: ByteArray,
        offset: Int,
        onSent: (Boolean) -> Unit,
    ) {
        val capacity = chunkCapacity(client.mtu)
        val end = minOf(offset + capacity, data.size)
        val chunk = data.copyOfRange(offset, end)
        val ok: Boolean = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                client.gatt.writeCharacteristic(char, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                char.value = chunk
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                client.gatt.writeCharacteristic(char)
            }
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            Log.e(TAG, "BLE write failed: ${client.address}")
            client.writing = false
            onSent(false)
            drain(client)
            return
        }
        pendingWrites[client.address] = PendingWrite(client, char, data, end, onSent)
    }

    private val clientCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT client connected: $address")
                    synchronized(gattClients) {
                        val c = gattClients[address]
                        c?.connected = true
                        c?.connecting = false
                        c?.discoverAttempts = 0
                    }
                    try { gatt.requestMtu(512) } catch (_: Exception) { gatt.discoverServices() }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT client disconnected: $address")
                    synchronized(gattClients) {
                        gattClients.remove(address)?.pending?.forEach { it.second(false) }
                    }
                    try { gatt.close() } catch (_: Exception) {}
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val address = gatt.device.address
            synchronized(gattClients) {
                gattClients[address]?.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            }
            Log.d(TAG, "MTU: $address -> $mtu (status=$status)")
            try { gatt.discoverServices() } catch (_: Exception) {}
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            if (status != BluetoothGatt.GATT_SUCCESS) {
                synchronized(gattClients) {
                    val client = gattClients[address]
                    if (client != null && client.discoverAttempts < 2) {
                        client.discoverAttempts++
                        Log.w(TAG, "Service not found: $address ($status) — attempt ${client.discoverAttempts}")
                        try { gatt.discoverServices() } catch (_: Exception) {}
                    } else {
                        Log.e(TAG, "Service not found: $address ($status)")
                    }
                }
                return
            }
            val rx = gatt.getService(SERVICE_UUID)?.getCharacteristic(RX_CHAR_UUID)
            if (rx == null) {
                synchronized(gattClients) {
                    val client = gattClients[address]
                    if (client != null && client.discoverAttempts < 2) {
                        client.discoverAttempts++
                        Log.w(TAG, "MeshNet service/char missing: $address (attempt ${client.discoverAttempts}). " +
                            "Found: ${gatt.services.joinToString { it.uuid.toString() }}")
                        try { gatt.discoverServices() } catch (_: Exception) {}
                    } else {
                        Log.e(TAG, "MeshNet service/char missing: $address (2 attempts). " +
                            "Found: ${gatt.services.joinToString { it.uuid.toString() }}")
                    }
                }
                return
            }

            // TX notification subscription: receiving notifications from peer
            val tx = gatt.getService(SERVICE_UUID)?.getCharacteristic(TX_CHAR_UUID)

            synchronized(gattClients) {
                val client = gattClients[address] ?: return
                client.discoverAttempts = 0
                client.rxCharacteristic = rx

                // TX notification subscription
                if (tx != null && !client.subscribedToTx) {
                    client.subscribedToTx = true
                    mainHandler.post {
                        subscribeToTxNotification(gatt, tx)
                    }
                }

                drain(client)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val address = gatt.device.address
            if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                Log.d(TAG, "TX notification subscription: $address (status=$status)")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val address = gatt.device.address
            val pw = pendingWrites.remove(address)
            val client = gattClients[address]
            if (pw == null || client == null) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "BLE write status: $status (${pw.nextOffset}/${pw.data.size})")
                client.writing = false
                pw.onSent(false)
                drain(client)
                return
            }
            if (pw.nextOffset < pw.data.size) {
                writeChunks(client, pw.char, pw.data, pw.nextOffset, pw.onSent)
            } else {
                client.writing = false
                pw.onSent(true)
                drain(client)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                val address = gatt.device.address
                val value = characteristic.value
                if (value != null && value.isNotEmpty()) {
                    Log.d(TAG, "TX notification received: $address (${value.size}B)")
                    try {
                        val frame = handleIncomingWrite(address, value)
                        if (frame != null) {
                            rememberAddress(frame.senderId, address)
                            listenerList.forEach { it.onFrameReceived(frame) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "TX notification decode error", e)
                    }
                }
            }
        }
    }

    // ---------------- GATT server (receive) ----------------

    @SuppressLint("MissingPermission")
    fun registerService() {
        ensureReceiverRegistered()
        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val txChar = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val rxChar = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(txChar)
        service.addCharacteristic(rxChar)
        val server = ensureGattServer()
        if (server == null) {
            // Bluetooth not ready yet (BLE_ON state) — retrying
            if (serverRetryCount >= 20) {
                Log.e(TAG, "registerService: GATT server still didn't open after 20 attempts")
                return
            }
            serverRetryCount++
            Log.w(TAG, "registerService: server not yet available — retrying in 3 seconds")
            mainHandler.postDelayed({
                if (!stopped) registerService()
            }, 3_000L)
            return
        }
        try {
            server.addService(service)
            Log.d(TAG, "registerService: addService requested (server=${server != null})")
        } catch (e: Exception) {
            Log.e(TAG, "registerService: addService error: ${e.message}")
            listenerList.forEach { it.onError("GATT service connection error: ${e.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    private val gattServerCallback = object : BluetoothGattServerCallback() {

        private var serviceRetryDone = false

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.d(TAG, "GATT server service: ${service.uuid} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS && !serviceRetryDone) {
                // On some devices the first addService may fail — retrying
                serviceRetryDone = true
                Log.w(TAG, "GATT server service not added (status=$status) — retrying")
                try { gattServer?.addService(service) } catch (_: Exception) {}
            }
        }

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int,
        ) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT server connected: $address")
                connectedServerDevices[address] = device
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedServerDevices.remove(address)
                synchronized(assembling) { assembling.remove(address) }
                val peerId = deviceIdByAddress[address]
                if (peerId != null) {
                    listenerList.forEach { it.onPeerLost(peerId) }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            Log.d(TAG, "GATT server write request: ${characteristic.uuid} (${value.size}B, $device)")
            if (characteristic.uuid == RX_CHAR_UUID) {
                try {
                    val frame = handleIncomingWrite(device.address, value)
                    if (frame != null) {
                        rememberAddress(frame.senderId, device.address)
                        listenerList.forEach { it.onFrameReceived(frame) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Frame decode error", e)
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    // ---------------- Cleanup ----------------

    @SuppressLint("MissingPermission")
    fun closeAll() {
        stopped = true
        mainHandler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothStateReceiver)
            } catch (_: Exception) {}
            receiverRegistered = false
        }
        stopAdvertising()
        stopScan()
        synchronized(gattClients) {
            gattClients.values.forEach {
                try { it.gatt.disconnect() } catch (_: Exception) {}
                try { it.gatt.close() } catch (_: Exception) {}
            }
            gattClients.clear()
        }
        pendingWrites.clear()
        synchronized(assembling) { assembling.clear() }
        connectedServerDevices.clear()
        peerRssiHistory.clear()
        peerPacketSuccess.clear()
        peerPacketTotal.clear()
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
    }

    /** Listen for Bluetooth state changes (reconfigure when BT is turned on). */
    private fun ensureReceiverRegistered() {
        if (receiverRegistered) return
        try {
            context.registerReceiver(
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            )
            receiverRegistered = true
        } catch (_: Exception) {}
    }

    // ---------------- Receive (server) assembly logic ----------------

    /** Converts incoming write bytes on RX into a complete frame.
     *  Simple (single) frame -> decoded immediately. Multi-chunk -> assembled in buffer,
     *  decoded when complete. */
    private fun handleIncomingWrite(address: String, value: ByteArray): MeshFrame? {
        // If current assembly is in progress — append chunk
        synchronized(assembling) {
            val inProgress = assembling[address]
            if (inProgress != null) {
                inProgress.buffer.write(value)
                if (inProgress.buffer.size() < inProgress.expected) return null
                assembling.remove(address)
                val raw = inProgress.buffer.toByteArray()
                val complete = if (raw.size > inProgress.expected) {
                    raw.copyOfRange(0, inProgress.expected)
                } else {
                    raw
                }
                return MeshFrame.decode(complete)
            }
        }

        // New entry: does it have a multi-chunk header?
        if (value.size >= CHUNK_HEADER_SIZE &&
            value[0] == MeshFrame.MAGIC1 &&
            value[1] == MeshFrame.MAGIC2 &&
            value[2] == CHUNK_MARKER
        ) {
            val len = ((value[3].toInt() and 0xFF) shl 24) or
                ((value[4].toInt() and 0xFF) shl 16) or
                ((value[5].toInt() and 0xFF) shl 8) or
                (value[6].toInt() and 0xFF)
            if (len < CHUNK_HEADER_SIZE || len > 64 * 1024) {
                Log.w(TAG, "Invalid chunk length: $len ($address)")
                return null
            }
            val firstChunk = value.copyOfRange(CHUNK_HEADER_SIZE, value.size)
            if (firstChunk.size >= len) {
                return MeshFrame.decode(firstChunk.copyOfRange(0, len))
            }
            val bos = ByteArrayOutputStream()
            bos.write(firstChunk)
            assembling[address] = Assembly(len, bos)
            return null
        }

        // Simple single-write frame
        return MeshFrame.decode(value)
    }

    // ---------------- Helpers ----------------

    private fun uuidToBytes(uuid: String): ByteArray {
        return try {
            val u = UUID.fromString(uuid)
            ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                .putLong(u.mostSignificantBits)
                .putLong(u.leastSignificantBits)
                .array()
        } catch (e: Exception) {
            ByteArray(16)
        }
    }

    private fun bytesToUuid(raw: ByteArray): String? {
        return try {
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            UUID(bb.long, bb.long).toString()
        } catch (e: Exception) {
            null
        }
    }
}

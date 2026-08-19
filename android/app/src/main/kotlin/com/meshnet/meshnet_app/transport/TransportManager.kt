package com.meshnet.meshnet_app.transport

import android.util.Log
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType

/**
 * TransportManager: manages multiple transports with bandwidth-aware routing.
 *
 * Strategy:
 *  - BLE: text, control frames, routing packets (< 512 bytes)
 *  - WiFi Direct: file transfers, voice messages, group messages (data-heavy)
 *  - Both: for broadcast/flood (dedup in RoutingEngine handles duplicates)
 *
 * Priority for text: BLE (lower power, wider range).
 * Priority for data: WiFi Direct (higher throughput).
 */
class TransportManager(
    private val ble: BleTransport,
    private val wifi: WifiDirectTransport,
) {

    companion object {
        private const val TAG = "TransportManager"
        /** Maximum payload size for BLE transport (conservative, below ATT MTU) */
        const val BLE_MAX_PAYLOAD = 400
    }

    fun interface RouteListener {
        fun onFrameReceived(frame: MeshFrame)
    }

    interface PeerListener {
        fun onPeerDiscovered(deviceId: String, displayName: String, rssi: Int, transport: String)
        fun onPeerLost(deviceId: String)
    }

    private var routeListener: RouteListener? = null
    private var peerListener: PeerListener? = null

    private val bleListener = object : BleTransport.Listener {
        override fun onFrameReceived(frame: MeshFrame) {
            routeListener?.onFrameReceived(frame)
        }

        override fun onPeerDiscovered(deviceId: String, displayName: String, rssi: Int) {
            peerListener?.onPeerDiscovered(deviceId, displayName, rssi, "ble")
        }

        override fun onPeerLost(deviceId: String) {
            peerListener?.onPeerLost(deviceId)
        }

        override fun onError(message: String) {
            Log.e(TAG, "BLE error: $message")
        }
    }

    private val wifiListener = object : WifiDirectTransport.Listener {
        override fun onFrameReceived(frame: MeshFrame) {
            routeListener?.onFrameReceived(frame)
        }

        override fun onPeerDiscovered(deviceId: String, displayName: String, rssi: Int) {
            peerListener?.onPeerDiscovered(deviceId, displayName, rssi, "wifi")
        }

        override fun onPeerLost(deviceId: String) {
            peerListener?.onPeerLost(deviceId)
        }

        override fun onError(message: String) {
            Log.e(TAG, "WiFi error: $message")
        }
    }

    init {
        ble.addListener(bleListener)
        wifi.addListener(wifiListener)
    }

    fun setRouteListener(l: RouteListener) {
        this.routeListener = l
    }

    fun setPeerListener(l: PeerListener) {
        this.peerListener = l
    }

    /** Start both transports. */
    fun start() {
        ble.registerService()
        ble.startAdvertising()
        ble.startScan()
        wifi.start()
    }

    fun stop() {
        ble.closeAll()
        wifi.stop()
    }

    /**
     * Send frame with bandwidth-aware routing:
     *  - Control/text frames → BLE first (lower power, wider range)
     *  - Data-heavy frames → WiFi Direct first (higher throughput)
     *  - Fallback to alternate transport if primary fails
     */
    fun sendFrame(
        peerKey: String,
        frame: MeshFrame,
        transport: Transport = Transport.WIFI,
        onSent: (Boolean) -> Unit,
    ) {
        val preferred = when {
            transport != Transport.AUTO -> transport
            isDataHeavy(frame) -> Transport.WIFI
            else -> Transport.BLE
        }

        when (preferred) {
            Transport.WIFI -> {
                wifi.sendFrame(peerKey, frame) { sent ->
                    if (!sent) {
                        // Fallback to BLE for control frames
                        if (!isDataHeavy(frame)) {
                            ble.sendFrame(peerKey, frame, onSent)
                        } else {
                            onSent(false)
                        }
                    } else {
                        onSent(true)
                    }
                }
            }
            Transport.BLE -> {
                ble.sendFrame(peerKey, frame) { sent ->
                    if (!sent) {
                        // Fallback to WiFi for data-heavy frames
                        if (isDataHeavy(frame)) {
                            wifi.sendFrame(peerKey, frame, onSent)
                        } else {
                            onSent(false)
                        }
                    } else {
                        onSent(true)
                    }
                }
            }
            Transport.AUTO -> {
                // Should not reach here (resolved above), but fallback to WiFi
                wifi.sendFrame(peerKey, frame, onSent)
            }
        }
    }

    /**
     * Controlled flooding: frame is sent to all transports and all known peers.
     * RoutingEngine dedup protection stops duplicate copies.
     * Data-heavy frames go over WiFi only (BLE too slow for large payloads).
     */
    fun flood(frame: MeshFrame) {
        if (isDataHeavy(frame)) {
            // Data-heavy: WiFi Direct only
            wifi.sendToAll(frame)
        } else {
            // Control/text: both transports for maximum reach
            wifi.sendToAll(frame)
            ble.sendToAll(frame)
        }
    }

    /** Known peer count (for debugging/topology). */
    fun peerCount(): Int {
        val wifiKeys = runCatching { wifi.socketCount() }.getOrDefault(0)
        val bleKeys = runCatching { ble.knownPeerCount() }.getOrDefault(0)
        return wifiKeys + bleKeys
    }

    /**
     * Determine if a frame is data-heavy and should use WiFi Direct.
     * File transfers, voice messages, and group messages are bandwidth-intensive.
     */
    private fun isDataHeavy(frame: MeshFrame): Boolean {
        return when (frame.type) {
            MessageType.FILE_START,
            MessageType.FILE_CHUNK,
            MessageType.FILE_END,
            MessageType.VOICE_MSG,
            MessageType.GROUP_MSG,
            MessageType.GROUP_KEY_DIST -> true
            MessageType.RELAY -> {
                // Check inner frame type for relay
                val inner = MeshFrame.decode(frame.payload)
                inner != null && isDataHeavy(inner)
            }
            else -> false
        }
    }

    enum class Transport {
        WIFI,
        BLE,
        AUTO, // Let TransportManager decide based on frame type
    }
}

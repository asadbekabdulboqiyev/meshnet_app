package com.meshnet.meshnet_app.transport

import android.util.Log
import com.meshnet.meshnet_app.protocol.MeshFrame

/**
 * TransportManager: manages multiple transports.
 * Priority: Wi-Fi Direct > BLE (for speed).
 *
 * Balance between transports:
 *  - Incoming frames are forwarded to the RoutingEngine.
 *  - Discovery/lost events go to MeshEngine via `PeerListener`
 *    (which updates PeerStore and emits events to Flutter).
 */
class TransportManager(
    private val ble: BleTransport,
    private val wifi: WifiDirectTransport,
) {

    companion object {
        private const val TAG = "TransportManager"
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
     * Send frame. Wi-Fi Direct first (faster), falls back to
     * BLE if unsuccessful. The transport argument allows selecting a specific transport.
     */
    fun sendFrame(
        peerKey: String,
        frame: MeshFrame,
        transport: Transport = Transport.WIFI,
        onSent: (Boolean) -> Unit,
    ) {
        when (transport) {
            Transport.WIFI -> {
                wifi.sendFrame(peerKey, frame) { sent ->
                    if (!sent) {
                        ble.sendFrame(peerKey, frame, onSent)
                    } else {
                        onSent(true)
                    }
                }
            }

            Transport.BLE -> ble.sendFrame(peerKey, frame, onSent)
        }
    }

    /** Controlled flooding: frame is sent to all transports and all known peers.
     *  RoutingEngine dedup protection stops duplicate copies. */
    fun flood(frame: MeshFrame) {
        wifi.sendToAll(frame)
        ble.sendToAll(frame)
    }

    /** Known peer count (for debugging/topology). */
    fun peerCount(): Int {
        val wifiKeys = runCatching { wifi.socketCount() }.getOrDefault(0)
        val bleKeys = runCatching { ble.knownPeerCount() }.getOrDefault(0)
        return wifiKeys + bleKeys
    }

    enum class Transport {
        WIFI, BLE
    }
}

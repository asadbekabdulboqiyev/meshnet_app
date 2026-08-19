package com.meshnet.meshnet_app.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.net.wifi.p2p.WifiP2pManager.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meshnet.meshnet_app.protocol.MeshFrame
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Wi-Fi Direct transport: P2P proximity, group, socket connection.
 *
 * Message sending: via socket (TCP) - faster than BLE, suitable for real-time messages.
 * Port 4864 - see MESH_PROTOCOL.md.
 */
@SuppressLint("MissingPermission")
class WifiDirectTransport(private val context: Context) {

    companion object {
        private const val TAG = "WifiDirectTransport"
        const val PORT = 4864
    }

    interface Listener {
        fun onFrameReceived(frame: MeshFrame)
        fun onPeerDiscovered(deviceId: String, displayName: String, rssi: Int)
        fun onPeerLost(deviceId: String)
        fun onError(message: String)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val executor = Executors.newCachedThreadPool()
    private var manager: WifiP2pManager? = null
    private var channel: Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isGroupOwner = false
    private var serverSocket: ServerSocket? = null

    // peer address -> Socket
    private val peerSockets = ConcurrentHashMap<String, Socket>()

    // mesh deviceId -> Socket (learned from frames)
    private val socketByDeviceId = ConcurrentHashMap<String, Socket>()

    private val deviceName: String by lazy {
        "MeshNet"
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** Starts P2P: discovery + registers broadcast receiver. */
    fun start() {
        // Before Android 6 (API 23) WiFi P2P is unreliable
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "WiFi Direct does not work before Android 6 (API ${Build.VERSION.SDK_INT})")
            return
        }
        val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        this.manager = mgr
        this.channel = try {
            mgr.initialize(context, Looper.getMainLooper(), null)
        } catch (e: SecurityException) {
            Log.e(TAG, "WifiP2pManager.initialize permission missing", e)
            null
        }

        // Note: WiFi P2P requires ACCESS_FINE_LOCATION and NEARBY_WIFI_DEVICES.
        // If permission is missing SecurityException is thrown — each step is guarded
        // separately to avoid breaking BLE (Flutter banner shows the reason).
        try {
            startDiscovery()
        } catch (e: SecurityException) {
            Log.e(TAG, "WiFi P2P discovery permission missing", e)
        }
        try {
            registerReceiver()
        } catch (e: SecurityException) {
            Log.e(TAG, "WiFi P2P receiver permission missing", e)
        }

        // Create group (so other devices can join)
        try {
            createGroupForAccepting()
        } catch (e: SecurityException) {
            Log.e(TAG, "WiFi P2P group permission missing", e)
        }
    }

    private fun startDiscovery() {
        val mgr = manager ?: return
        mgr.discoverPeers(channel, object : ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct discovery started")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "discoverPeers error: $reason")
                listeners.forEach { it.onError("Wi-Fi Direct discovery failed (code=$reason)") }
            }
        })
    }

    /** Create group (Group Owner). Other nodes (clients) connect via socket. */
    private fun createGroupForAccepting() {
        val mgr = manager ?: return
        mgr.createGroup(channel, object : ActionListener {
            override fun onSuccess() {
                isGroupOwner = true
                Log.i(TAG, "P2P group created (Group Owner)")
                startServerSocket()
            }

            override fun onFailure(reason: Int) {
                // System may already be in a group — request groupInfo
                // to continue operating in that case.
                Log.w(TAG, "createGroup error $reason - connecting to existing group")
                mgr.requestGroupInfo(channel) { group ->
                    if (group != null) startServerSocket()
                }
            }
        })
    }

    private fun startServerSocket() {
        executor.execute {
            try {
                serverSocket = ServerSocket(PORT)
                while (!Thread.currentThread().isInterrupted) {
                    val socket = serverSocket!!.accept()
                    handleClientSocket(socket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket closed: ${e.message}")
            }
        }
    }

    private fun handleClientSocket(socket: Socket) {
        executor.execute {
            try {
                val input = DataInputStream(socket.getInputStream())
                while (true) {
                    val len = input.readInt()
                    if (len <= 0 || len > 64 * 1024) break
                    val frameBytes = ByteArray(len)
                    input.readFully(frameBytes)
                    val frame = MeshFrame.decode(frameBytes)
                    if (frame != null) {
                        // Learn peer identity from incoming frame
                        socketByDeviceId[frame.senderId] = socket
                        listeners.forEach { it.onFrameReceived(frame) }
                    }
                }
            } catch (e: Exception) {
                // socket closed
            } finally {
                socket.close()
            }
        }
    }

    /** Send message to peer via socket (peerKey = mesh deviceId or IP). */
    fun sendFrame(peerKey: String, frame: MeshFrame, onSent: (Boolean) -> Unit) {
        sendFrameInternal(peerKey, frame, onSent, retryCount = 0)
    }

    private fun sendFrameInternal(peerKey: String, frame: MeshFrame, onSent: (Boolean) -> Unit, retryCount: Int) {
        executor.execute {
            try {
                val socket = socketByDeviceId[peerKey] ?: peerSockets[peerKey]
                if (socket == null || socket.isClosed) {
                    // Try to reconnect (once)
                    if (retryCount < 1 && peerSockets.containsKey(peerKey)) {
                        peerSockets.remove(peerKey)
                        Log.w(TAG, "WiFi socket disconnected — reconnecting: $peerKey")
                        sendFrameInternal(peerKey, frame, onSent, retryCount + 1)
                        return@execute
                    }
                    onSent(false)
                    return@execute
                }
                val out = DataOutputStream(socket.getOutputStream())
                val data = MeshFrame.encode(frame)
                out.writeInt(data.size)
                out.write(data)
                out.flush()
                onSent(true)
            } catch (e: Exception) {
                Log.e(TAG, "WiFi send error: ${e.message}")
                if (retryCount < 1) {
                    peerSockets.remove(peerKey)
                    sendFrameInternal(peerKey, frame, onSent, retryCount + 1)
                } else {
                    onSent(false)
                }
            }
        }
    }

    /** Send frame to all open sockets (flood). */
    fun sendToAll(frame: MeshFrame) {
        val targets = (socketByDeviceId.keys + peerSockets.keys).toSet()
        if (targets.isEmpty()) return
        Log.d(TAG, "WiFi flood: ${targets.size} peers")
        targets.forEach { target ->
            sendFrame(target, frame) {}
        }
    }

    /** Group owner. */
    var groupOwnerAddress: String? = null

    /** Open socket count (for debugging). */
    fun socketCount(): Int = socketByDeviceId.size + peerSockets.size

    private fun registerReceiver() {
        val mgr = manager ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        mgr.requestPeers(channel) { peerList ->
                            peersChanged(peerList)
                        }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        mgr.requestConnectionInfo(channel) { info ->
                            connectionChanged(info)
                        }
                    }
                }
            }
        }
        this.receiver = receiver
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun peersChanged(list: WifiP2pDeviceList) {
        list.deviceList.forEach { device ->
            val name = device.deviceName
            val address = device.deviceAddress
            when (device.status) {
                WifiP2pDevice.AVAILABLE -> {
                    listeners.forEach { it.onPeerDiscovered(address, name, 0) }
                }
                WifiP2pDevice.UNAVAILABLE -> {
                    listeners.forEach { it.onPeerLost(address) }
                }
            }
        }
    }

    private fun connectionChanged(info: WifiP2pInfo) {
        if (info.groupFormed) {
            if (info.isGroupOwner) {
                isGroupOwner = true
            } else {
                isGroupOwner = false
                // Client: connect to Group Owner socket
                val ownerAddress = info.groupOwnerAddress?.hostAddress
                if (ownerAddress != null) {
                    groupOwnerAddress = ownerAddress
                    connectToOwner(ownerAddress)
                }
            }
        }
    }

    private fun connectToOwner(ownerAddress: String) {
        executor.execute {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ownerAddress, PORT), 5000)
                val peerKey = ownerAddress
                peerSockets[peerKey] = socket
                handleClientSocket(socket)
            } catch (e: Exception) {
                Log.e(TAG, "Group Owner connection error: ${e.message}")
            }
        }
    }

    fun stop() {
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        peerSockets.values.forEach { runCatching { it.close() } }
        peerSockets.clear()
        try {
            manager?.removeGroup(channel, object : ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "removeGroup permission missing", e)
        }
    }
}
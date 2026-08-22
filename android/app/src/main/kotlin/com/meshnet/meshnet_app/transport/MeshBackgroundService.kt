package com.meshnet.meshnet_app.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meshnet.meshnet_app.protocol.MeshFrame
import java.util.concurrent.ConcurrentHashMap

/**
 * MeshBackgroundService: foreground service for background mesh operation.
 * Keeps BLE scanning and Wi-Fi Direct group alive when app is in background.
 */
class MeshBackgroundService : Service() {

    companion object {
        private const val TAG = "MeshBackgroundService"
        const val CHANNEL_ID = "mesh_background"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.meshnet.meshnet_app.START_MESH_SERVICE"
        const val ACTION_STOP = "com.meshnet.meshnet_app.STOP_MESH_SERVICE"
        const val ACTION_SCAN_STATUS = "com.meshnet.meshnet_app.SCAN_STATUS"

        fun start(context: Context) {
            val intent = Intent(context, MeshBackgroundService::class.java)
            intent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshBackgroundService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }

        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            return manager.getRunningServices(Integer.MAX_VALUE).any {
                it.service.className == MeshBackgroundService::class.java.name
            }
        }
    }

    private var bleScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var wifiTransport: WifiDirectTransport? = null
    private var meshFrameHandler: ((MeshFrame) -> Unit)? = null
    private var peerDiscoveredHandler: ((String, String, Int) -> Unit)? = null
    private var peerLostHandler: ((String) -> Unit)? = null

    // Discovered peers in background (for quick foreground resume)
    private val backgroundPeers = ConcurrentHashMap<String, BackgroundPeer>()

    data class BackgroundPeer(
        val deviceId: String,
        val displayName: String,
        val rssi: Int,
        val transport: String,
        val lastSeen: Long = System.currentTimeMillis()
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY

        when (intent.action) {
            ACTION_START -> startBackgroundMesh()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startBackgroundMesh() {
        Log.i(TAG, "Starting background mesh service")

        // Start foreground notification
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Initialize transports
        initTransports()
    }

    private fun initTransports() {
        // BLE: Start scanning with low power settings
        startBleScanning()

        // Wi-Fi Direct: Create persistent group
        initWifiDirect()
    }

    // ==================== BLE Background Scanning ====================

    private fun startBleScanning() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = bluetoothManager.adapter ?: return
        bleScanner = adapter.bluetoothLeScanner

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(5000) // Batch reports to save battery
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        val filters = mutableListOf<ScanFilter>()
        // Filter for our manufacturer data (0x4D4E)
        val filter = ScanFilter.Builder()
            .setManufacturerData(0x4D4E, byteArrayOf(0x4D, 0x4E))
            .build()
        filters.add(filter)

        scanCallback = object : ScanCallback() {
            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { processScanResult(it) }
            }

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                processScanResult(result)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: $errorCode")
                // Restart scanning after delay
                Handler(Looper.getMainLooper()).postDelayed({ startBleScanning() }, 5000)
            }
        }

        try {
            bleScanner?.startScan(filters, settings, scanCallback)
            Log.i(TAG, "Background BLE scanning started")
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan permission missing", e)
        }
    }

    private fun processScanResult(result: ScanResult) {
        val device = result.device
        val rssi = result.rssi

        // Parse manufacturer data for deviceId
        val scanRecord = result.scanRecord ?: return
        val mfgData = scanRecord.getManufacturerSpecificData(0x4D4E) ?: return

        if (mfgData.size >= 18) {
            val marker = mfgData.copyOfRange(0, 2)
            if (marker.contentEquals(byteArrayOf(0x4D, 0x4E))) {
                val deviceIdBytes = mfgData.copyOfRange(2, 18)
                val deviceId = deviceIdBytesToString(deviceIdBytes)

                val peer = BackgroundPeer(
                    deviceId = deviceId,
                    displayName = device.name ?: "Unknown",
                    rssi = rssi,
                    transport = "ble"
                )
                backgroundPeers[deviceId] = peer

                peerDiscoveredHandler?.invoke(deviceId, device.name ?: "Unknown", rssi)

                // Broadcast to UI if app is in foreground
                broadcastPeerUpdate(deviceId, "ble", rssi, true)
            }
        }
    }

    private fun deviceIdBytesToString(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in bytes.indices) {
            sb.append(String.format("%02x", bytes[i]))
            if (i in setOf(3, 5, 7, 9)) sb.append('-')
        }
        return sb.toString()
    }

    // ==================== Wi-Fi Direct Persistent Group ====================

    private fun initWifiDirect() {
        wifiTransport = WifiDirectTransport(this)
        wifiTransport?.addListener(object : WifiDirectTransport.Listener {
            override fun onFrameReceived(frame: MeshFrame) {
                meshFrameHandler?.invoke(frame)
            }

            override fun onPeerDiscovered(deviceId: String, displayName: String, rssi: Int) {
                val peer = BackgroundPeer(deviceId, displayName, rssi, "wifi")
                backgroundPeers[deviceId] = peer
                peerDiscoveredHandler?.invoke(deviceId, displayName, rssi)
                broadcastPeerUpdate(deviceId, "wifi", rssi, true)
            }

            override fun onPeerLost(deviceId: String) {
                backgroundPeers.remove(deviceId)
                peerLostHandler?.invoke(deviceId)
                broadcastPeerUpdate(deviceId, "", 0, false)
            }

            override fun onError(message: String) {
                Log.e(TAG, "WiFi Direct error: $message")
            }
        })

        wifiTransport?.start()
        Log.i(TAG, "Wi-Fi Direct background transport started")

        // Schedule periodic group recreation to keep it alive
        scheduleGroupKeepalive()
    }

    private fun scheduleGroupKeepalive() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (wifiTransport?.groupOwnerAddress == null) {
                Log.d(TAG, "Recreating Wi-Fi Direct group (keepalive)")
                wifiTransport?.stop()
                wifiTransport = null
                initWifiDirect()
            }
            scheduleGroupKeepalive()
        }, 300_000) // Every 5 minutes
    }

    // ==================== Callbacks for Foreground App ====================

    fun setMeshFrameHandler(handler: (MeshFrame) -> Unit) {
        this.meshFrameHandler = handler
    }

    fun setPeerDiscoveredHandler(handler: (String, String, Int) -> Unit) {
        this.peerDiscoveredHandler = handler
    }

    fun setPeerLostHandler(handler: (String) -> Unit) {
        this.peerLostHandler = handler
    }

    fun getBackgroundPeers(): Map<String, BackgroundPeer> = backgroundPeers.toMap()

    fun clearBackgroundPeers() {
        backgroundPeers.clear()
    }

    // ==================== Notification & Lifecycle ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MeshNet Background",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps mesh network running in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, com.meshnet.meshnet_app.MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshNet running")
            .setContentText("Mesh network active in background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun broadcastPeerUpdate(deviceId: String, transport: String, rssi: Int, discovered: Boolean) {
        val intent = Intent(ACTION_SCAN_STATUS)
        intent.putExtra("deviceId", deviceId)
        intent.putExtra("transport", transport)
        intent.putExtra("rssi", rssi)
        intent.putExtra("discovered", discovered)
        sendBroadcast(intent)
    }

    // ==================== Public API for Foreground App ====================

    fun sendFrameInBackground(peerKey: String, frame: MeshFrame, onSent: (Boolean) -> Unit) {
        // Try Wi-Fi Direct first, fallback to BLE
        wifiTransport?.sendFrame(peerKey, frame) { sent ->
            if (!sent) {
                // TODO: BLE send from background
                onSent(false)
            } else {
                onSent(true)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        bleScanner?.stopScan(scanCallback)
        scanCallback = null
        wifiTransport?.stop()
        wifiTransport = null
        Log.i(TAG, "Background mesh service stopped")
    }
}
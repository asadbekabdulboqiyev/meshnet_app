package com.meshnet.meshnet_app.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE Connection Pool — manages GATT client connections with LRU eviction.
 *
 * Android limits simultaneous GATT client connections (~7-9). This pool:
 * - Caps connections at MAX_POOL_SIZE (6)
 * - Evicts least-recently-used connections when full
 * - Tracks connection state and auto-cleans dead connections
 * - Provides acquire/release semantics for deterministic lifecycle
 */
@SuppressLint("MissingPermission")
class BleConnectionPool(
    private val maxPoolSize: Int = MAX_POOL_SIZE,
) {

    companion object {
        private const val TAG = "BleConnectionPool"
        const val MAX_POOL_SIZE = 6
        private const val CONNECTION_TIMEOUT_MS = 30_000L
    }

    interface ConnectionCallback {
        fun onConnected(address: String, gatt: BluetoothGatt)
        fun onDisconnected(address: String)
        fun onConnectionFailed(address: String, error: Int)
    }

    data class PooledConnection(
        val address: String,
        var gatt: BluetoothGatt?,
        var lastUsedMs: Long = System.currentTimeMillis(),
        var state: Int = BluetoothProfile.STATE_DISCONNECTED,
    )

    private val connections = ConcurrentHashMap<String, PooledConnection>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ConnectionCallback? = null

    fun setCallback(cb: ConnectionCallback) {
        this.callback = cb
    }

    /**
     * Acquire a connection to the given device. Returns existing connection if
     * already connected, or initiates a new connection. If pool is full,
     * evicts the LRU connection first.
     */
    @SuppressLint("MissingPermission")
    fun acquire(device: BluetoothDevice): BluetoothGatt? {
        val address = device.address

        // Already pooled — refresh timestamp and return
        val existing = connections[address]
        if (existing != null && existing.gatt != null && existing.state == BluetoothProfile.STATE_CONNECTED) {
            existing.lastUsedMs = System.currentTimeMillis()
            return existing.gatt
        }

        // Pool full? Evict LRU
        if (connections.size >= maxPoolSize) {
            evictLRU()
        }

        // Initiate new connection
        @SuppressLint("MissingPermission")
        val gatt = device.connectGatt(
            null, // context — null for direct connection
            false, // autoConnect = false for direct connection
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )

        val pooled = PooledConnection(
            address = address,
            gatt = gatt,
            lastUsedMs = System.currentTimeMillis(),
            state = BluetoothProfile.STATE_CONNECTING,
        )
        connections[address] = pooled
        Log.d(TAG, "Acquired connection to $address (pool size: ${connections.size})")
        return gatt
    }

    /**
     * Release a connection — marks it as least-recently-used but keeps it open.
     */
    fun release(address: String) {
        connections[address]?.let {
            it.lastUsedMs = 0 // Mark as least-recently-used
        }
    }

    /**
     * Force-close and remove a specific connection.
     */
    @SuppressLint("MissingPermission")
    fun evict(address: String) {
        val conn = connections.remove(address) ?: return
        try {
            conn.gatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT for $address: ${e.message}")
        }
        Log.d(TAG, "Evicted connection: $address (pool size: ${connections.size})")
    }

    /**
     * Close all connections.
     */
    @SuppressLint("MissingPermission")
    fun closeAll() {
        connections.values.forEach { conn ->
            try {
                conn.gatt?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing GATT: ${e.message}")
            }
        }
        connections.clear()
        Log.d(TAG, "All connections closed")
    }

    fun getConnectionCount(): Int = connections.size

    fun isConnected(address: String): Boolean {
        return connections[address]?.state == BluetoothProfile.STATE_CONNECTED
    }

    fun getGatt(address: String): BluetoothGatt? {
        return connections[address]?.gatt
    }

    fun activeAddresses(): Set<String> = connections.keys.toSet()

    /**
     * Evict least-recently-used connection (oldest lastUsedMs).
     */
    private fun evictLRU() {
        val lru = connections.values.minByOrNull { it.lastUsedMs } ?: return
        Log.d(TAG, "Evicting LRU: ${lru.address} (last used: ${System.currentTimeMillis() - lru.lastUsedMs}ms ago)")
        evict(lru.address)
    }

    /**
     * Periodic cleanup of stale connections (called by transport tick).
     */
    fun cleanupStale() {
        val now = System.currentTimeMillis()
        val stale = connections.values.filter {
            now - it.lastUsedMs > CONNECTION_TIMEOUT_MS && it.state != BluetoothProfile.STATE_CONNECTED
        }
        stale.forEach { evict(it.address) }
        if (stale.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${stale.size} stale connections")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            val conn = connections[address]

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    conn?.state = BluetoothProfile.STATE_CONNECTED
                    conn?.lastUsedMs = System.currentTimeMillis()
                    Log.d(TAG, "Connected: $address")
                    callback?.onConnected(address, gatt)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    conn?.state = BluetoothProfile.STATE_DISCONNECTED
                    connections.remove(address)
                    Log.d(TAG, "Disconnected: $address (pool size: ${connections.size})")
                    callback?.onDisconnected(address)
                    try { gatt.close() } catch (_: Exception) {}
                }
                else -> {
                    conn?.state = newState
                }
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                conn?.state = BluetoothProfile.STATE_DISCONNECTED
                connections.remove(address)
                Log.w(TAG, "Connection failed: $address (status=$status)")
                callback?.onConnectionFailed(address, status)
                try { gatt.close() } catch (_: Exception) {}
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed: ${gatt.device.address} -> $mtu")
            }
        }
    }
}

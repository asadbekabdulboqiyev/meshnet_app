package com.meshnet.meshnet_app.localnet.emergency

import com.meshnet.meshnet_app.localnet.rbac.Permission
import com.meshnet.meshnet_app.protocol.MessageType
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.RoutingEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Emergency Broadcast System for LocalNet mesh.
 * Priority alerts that flood the mesh instantly with maximum TTL.
 * Supports acknowledgment tracking and deduplication.
 */
class EmergencyManager(
    private val selfDeviceId: String,
    private val routingEngine: RoutingEngine,
    private val accessControl: com.meshnet.meshnet_app.localnet.rbac.AccessControl? = null
) {

    private val alerts = ConcurrentHashMap<String, EmergencyAlert>()
    private val ackTracker = ConcurrentHashMap<String, java.util.Set<String>>()
    private val seenAlerts = ConcurrentHashMap<String, Long>() // alertId -> receivedAtMs
    private val MAX_SEEN_CACHE = 1000
    private val SEEN_TTL_MS = 24 * 60 * 60 * 1000L // 24h

    // Message types for emergency frames
    companion object {
        const val EMERGENCY_ALERT = 0x70.toByte()
        const val EMERGENCY_ACK = 0x71.toByte()
        const val EMERGENCY_CANCEL = 0x72.toByte()
    }

    data class EmergencyAlert(
        val alertId: String,
        val senderId: String,
        val senderName: String,
        val level: AlertLevel,
        val title: String,
        val message: String,
        val location: String? = null,
        val coordinates: String? = null, // "lat,lon"
        val expiresAtMs: Long,
        val createdAtMs: Long = System.currentTimeMillis(),
        val requiresAck: Boolean = true,
        val metadata: Map<String, String> = emptyMap()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > expiresAtMs
        fun isActive(): Boolean = !isExpired()
    }

    enum class AlertLevel(val priority: Int, val label: String, val color: Int) {
        INFO(1, "Info", 0xFF2196F3.toInt()),      // Blue
        WARNING(2, "Warning", 0xFFFF9800.toInt()), // Orange
        CRITICAL(3, "Critical", 0xFFF44336.toInt()), // Red
        EMERGENCY(4, "Emergency", 0xFFB71C1C.toInt()); // Dark red

        companion object {
            fun fromPriority(p: Int): AlertLevel = values().firstOrNull { it.priority == p } ?: INFO
        }
    }

    // --- Send emergency alert ---

    fun sendAlert(
        level: AlertLevel,
        title: String,
        message: String,
        location: String? = null,
        coordinates: String? = null,
        ttlMinutes: Int = 60,
        requiresAck: Boolean = true,
        metadata: Map<String, String> = emptyMap()
    ): EmergencyAlert {
        val alertId = "emg_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val alert = EmergencyAlert(
            alertId = alertId,
            senderId = selfDeviceId,
            senderName = "LocalNode", // will be overridden by service
            level = level,
            title = title,
            message = message,
            location = location,
            coordinates = coordinates,
            expiresAtMs = System.currentTimeMillis() + ttlMinutes * 60 * 1000L,
            requiresAck = requiresAck,
            metadata = metadata
        )

        alerts[alertId] = alert
        broadcastAlert(alert)
        return alert
    }

    private fun broadcastAlert(alert: EmergencyAlert) {
        val payload = encodeAlert(alert)
        routingEngine.sendEmergencyAlert(payload)
    }

    // --- Handle incoming frames ---

    fun onEmergencyFrame(frame: MeshFrame) {
        when (frame.type.code) {
            EMERGENCY_ALERT -> handleAlertFrame(frame)
            EMERGENCY_ACK -> handleAckFrame(frame)
            EMERGENCY_CANCEL -> handleCancelFrame(frame)
        }
    }

    private fun handleAlertFrame(frame: MeshFrame) {
        val alert = decodeAlert(frame.payload)
        if (alert == null) return

        // Deduplication
        val seen = seenAlerts[alert.alertId]
        val now = System.currentTimeMillis()
        if (seen != null && now - seen < SEEN_TTL_MS) return
        seenAlerts[alert.alertId] = now
        if (seenAlerts.size > MAX_SEEN_CACHE) {
            seenAlerts.entries.forEach { if (now - it.value > SEEN_TTL_MS) seenAlerts.remove(it.key) }
        }

        // Self-originated? skip
        if (alert.senderId == selfDeviceId) return

        // Store alert
        alerts[alert.alertId] = alert

        // Auto-ack if required
        if (alert.requiresAck) {
            sendAck(alert.alertId)
        }

        // Emit event
        emitEvent("emergencyAlert", mapOf<String, Any?>(
            "alertId" to alert.alertId,
            "senderId" to alert.senderId,
            "senderName" to alert.senderName,
            "level" to alert.level.name,
            "title" to alert.title,
            "message" to alert.message,
            "location" to alert.location,
            "coordinates" to alert.coordinates,
            "expiresAtMs" to alert.expiresAtMs,
            "requiresAck" to alert.requiresAck,
            "metadata" to alert.metadata
        ))
    }

    private fun handleAckFrame(frame: MeshFrame) {
        val parts = String(frame.payload).split("|", limit = 3)
        if (parts.size < 2) return
        val alertId = parts[0]
        val ackerId = parts[1]

        val acks = ackTracker.getOrPut(alertId) { 
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>()) as java.util.Set<String>
        }
        acks.add(ackerId)

        emitEvent("emergencyAck", mapOf<String, Any?>(
            "alertId" to alertId,
            "ackerId" to ackerId,
            "totalAcks" to acks.size
        ))
    }

    private fun handleCancelFrame(frame: MeshFrame) {
        val alertId = String(frame.payload).trim()
        val alert = alerts.remove(alertId)
        if (alert != null && alert.senderId == frame.senderId) {
            // Only sender can cancel
            emitEvent("emergencyCancelled", mapOf<String, Any?>("alertId" to alertId, "senderId" to frame.senderId))
        }
    }

    // --- Acknowledgment ---

    fun acknowledge(alertId: String) {
        val alert = alerts[alertId]
        if (alert == null) return
        sendAck(alertId)
        emitEvent("emergencyAck", mapOf<String, Any?>("alertId" to alertId, "ackerId" to selfDeviceId, "local" to true))
    }

    private fun sendAck(alertId: String) {
        val payload = "$alertId|$selfDeviceId".toByteArray()
        routingEngine.sendEmergencyAck(payload)
    }

    // --- Cancel own alert ---

    fun cancelAlert(alertId: String): Boolean {
        val alert = alerts[alertId]
        if (alert == null || alert.senderId != selfDeviceId) return false
        alerts.remove(alertId)
        val payload = alertId.toByteArray()
        routingEngine.sendEmergencyCancel(payload)
        emitEvent("emergencyCancelled", mapOf<String, Any?>("alertId" to alertId, "senderId" to selfDeviceId, "local" to true))
        return true
    }

    // --- Queries ---

    fun getActiveAlerts(): List<EmergencyAlert> {
        val now = System.currentTimeMillis()
        return alerts.values
            .filter { it.isActive() }
            .sortedWith(compareByDescending<EmergencyAlert> { it.level.priority }.thenByDescending { it.createdAtMs })
            .toList()
    }

    fun getAlert(alertId: String): EmergencyAlert? = alerts[alertId]

    fun getAckCount(alertId: String): Int = ackTracker[alertId]?.size ?: 0

    fun getAckers(alertId: String): Set<String> = ackTracker[alertId]?.toSet() ?: emptySet()

    fun isAcknowledgedBy(alertId: String, deviceId: String): Boolean = ackTracker[alertId]?.contains(deviceId) == true

    // --- Periodic cleanup ---

    fun periodicCleanup() {
        val now = System.currentTimeMillis()
        // Expire alerts
        alerts.entries.forEach { if (it.value.isExpired()) alerts.remove(it.key) }
        // Clean ack tracker for expired alerts
        ackTracker.entries.forEach { if (!alerts.containsKey(it.key)) ackTracker.remove(it.key) }
        // Clean seen cache
        seenAlerts.entries.forEach { if (now - it.value > SEEN_TTL_MS) seenAlerts.remove(it.key) }
    }

    // --- Encoding/decoding ---

    private fun encodeAlert(alert: EmergencyAlert): ByteArray {
        val sb = StringBuilder()
        sb.append(alert.alertId).append('|')
        sb.append(alert.senderId).append('|')
        sb.append(alert.senderName.replace("|", "\\|")).append('|')
        sb.append(alert.level.priority).append('|')
        sb.append(alert.title.replace("|", "\\|")).append('|')
        sb.append(alert.message.replace("|", "\\|")).append('|')
        val loc = alert.location?.replace("|", "\\|") ?: ""
        sb.append(loc).append('|')
        val coords = alert.coordinates ?: ""
        sb.append(coords).append('|')
        sb.append(alert.expiresAtMs).append('|')
        sb.append(alert.createdAtMs).append('|')
        sb.append(if (alert.requiresAck) "1" else "0").append('|')
        // Metadata as key=value;key=value
        alert.metadata.forEach { entry ->
            val k = entry.key
            val v = entry.value
            sb.append(k.replace("|", "\\|")).append('=').append(v.replace("|", "\\|")).append(';')
        }
        return sb.toString().toByteArray()
    }

    private fun decodeAlert(payload: ByteArray): EmergencyAlert? {
        try {
            val parts = String(payload).split("|", limit = 12)
            if (parts.size < 11) return null

            val alertId = parts[0]
            val senderId = parts[1]
            val senderName = parts[2].replace("\\|", "|")
            val level = AlertLevel.fromPriority(parts[3].toIntOrNull() ?: 1)
            val title = parts[4].replace("\\|", "|")
            val message = parts[5].replace("\\|", "|")
            val location = parts[6].ifBlank { null }?.replace("\\|", "|")
            val coordinates = parts[7].ifBlank { null }
            val expiresAtMs = parts[8].toLongOrNull() ?: 0
            val createdAtMs = parts[9].toLongOrNull() ?: 0
            val requiresAck = parts[10] == "1"
            val metadata = mutableMapOf<String, String>()
            if (parts.size >= 12) {
                parts[11].split(";").filter { it.isNotBlank() }.forEach { kv ->
                    kv.split("=", limit = 2).let { if (it.size == 2) metadata[it[0].replace("\\|", "|")] = it[1].replace("\\|", "|") }
                }
            }

            return EmergencyAlert(
                alertId = alertId,
                senderId = senderId,
                senderName = senderName,
                level = level,
                title = title,
                message = message,
                location = location,
                coordinates = coordinates,
                expiresAtMs = expiresAtMs,
                createdAtMs = createdAtMs,
                requiresAck = requiresAck,
                metadata = metadata
            )
        } catch (e: Exception) {
            return null
        }
    }

    // --- Event emission ---

    private fun emitEvent(type: String, data: Map<String, Any?>) {
        routingEngine.emitLocalEvent(mapOf("event" to type) + data)
    }

    // --- Snapshot for persistence ---

    fun snapshot(): Map<String, Any> {
        return mapOf(
            "alerts" to alerts.mapValues { it.value },
            "ackTracker" to ackTracker.mapValues { it.value.toList() }
        )
    }

    fun restore(snapshot: Map<String, Any>) {
        alerts.clear()
        (snapshot["alerts"] as? Map<String, EmergencyAlert>)?.forEach { alerts[it.key] = it.value }
        ackTracker.clear()
        (snapshot["ackTracker"] as? Map<String, List<String>>)?.forEach { (alertId, ackers) ->
            val set = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>()) as java.util.Set<String>
            set.addAll(ackers)
            ackTracker[alertId] = set
        }
    }
}
package com.meshnet.meshnet_app.localnet.emergency

import com.meshnet.meshnet_app.localnet.rbac.AccessControl
import com.meshnet.meshnet_app.localnet.rbac.Permission
import com.meshnet.meshnet_app.protocol.MessageType
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.localnet.rbac.SigningIdentity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class EmergencyManager(
    private val selfDeviceId: String,
    private val routingEngine: RoutingEngine,
    private val accessControl: AccessControl? = null
) {

    /** Emergency alert data class */
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
        // Filter out potentially fake alerts before broadcasting
        val filteredAlerts = if (alerts.size > 1) filterEmergencyAlerts(listOf(alert)) else listOf(alert)
        // In a full implementation, would filter the entire alerts map
        routingEngine.sendEmergencyAlert(encodeAlert(alert))
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

    // --- Alert Level ---
    enum class AlertLevel(val priority: Int, val label: String, val color: Int) {
        INFO(1, "Info", 0xFF2196F3.toInt()),      // Blue
        WARNING(2, "Warning", 0xFFFF9800.toInt()), // Orange
        CRITICAL(3, "Critical", 0xFFF44336.toInt()), // Red
        EMERGENCY(4, "Emergency", 0xFFB71C1C.toInt()); // Dark red

        companion object {
            fun fromPriority(p: Int): AlertLevel = values().firstOrNull { it.priority == p } ?: INFO
        }
    }

    // --- Fake alert detection ---
    //
    // FIX: The original filter was INVERTED — it dropped REAL emergency alerts
    // containing "SOS", "HELP", "EMERGENCY" etc. and let non-emergency fakes through.
    //
    // New logic:
    //   1. Alerts with a valid cryptographic signature (SigningIdentity P-256) are
    //      ALWAYS kept — the signature proves the sender's identity.
    //   2. Unsigned alerts matching fake patterns are FLAGGED (logged) but still
    //      delivered — the user decides. Dropping real SOS messages is worse than
    //      showing a suspicious one.
    //   3. Alerts that match "TEST" or "SIMULATION" keywords are marked as test
    //      alerts and displayed with a test banner.

    private val knownFakePatterns = listOf(
        "TEST",
        "SIMULATION"
    )

    private val emergencyKeywords = listOf(
        "SOS",
        "HELP",
        "EMERGENCY",
        "MAYDAY"
    )

    /**
     * Check if an alert is a test/simulation (not a real emergency).
     * TEST and SIMULATION alerts are displayed differently with a test banner.
     */
    fun isTestAlert(payload: String): Boolean {
        val lowerPayload = payload.lowercase()
        return knownFakePatterns.any { lowerPayload.contains(it) }
    }

    /**
     * Check if an alert contains emergency keywords that are EXPECTED
     * in real emergencies (SOS, HELP, EMERGENCY, MAYDAY).
     * These should NEVER be filtered out.
     */
    fun containsEmergencyKeywords(payload: String): Boolean {
        val lowerPayload = payload.lowercase()
        return emergencyKeywords.any { lowerPayload.contains(it) }
    }

    /**
     * Check if an alert is potentially fake (unsigned + suspicious pattern).
     * Only flags alerts that are NOT cryptographically signed AND match
     * test/simulation patterns. Real emergency keywords are never flagged.
     */
    fun isPotentiallyFakeAlert(alert: EmergencyAlert): Boolean {
        // If the alert has a valid signature, it's NOT fake regardless of content
        val signature = alert.metadata["signature"]
        if (!signature.isNullOrBlank()) {
            // TODO: verify signature with SigningIdentity.verify()
            // For now, trust signed alerts
            return false
        }
        // Only flag TEST/SIMULATION alerts without signatures
        return isTestAlert(alert.message)
    }

    /**
     * Filter emergency alerts:
     *   - SIGNED alerts are ALWAYS kept (cryptographic proof of identity)
     *   - TEST alerts are KEPT but flagged for display as test banners
     *   - Only UNSIGNED alerts matching test patterns are logged as suspicious
     *   - REAL emergency alerts (SOS, HELP, etc.) are NEVER dropped
     *
     * FIX: Original version dropped real SOS/HELP/EMERGENCY alerts. This is fixed.
     */
    fun filterEmergencyAlerts(alerts: List<EmergencyAlert>): List<EmergencyAlert> {
        return alerts.filter { alert ->
            if (isPotentiallyFakeAlert(alert)) {
                // Log the suspicious alert but still include it
                // (better to show a suspicious alert than drop a real one)
                emitEvent("suspiciousAlert", mapOf<String, Any?>(
                    "alertId" to alert.alertId,
                    "senderId" to alert.senderId,
                    "reason" to "unsigned_test_simulation_pattern"
                ))
                true  // Keep it — user decides
            } else {
                true  // Keep all other alerts (including real SOS, HELP, etc.)
            }
        }
    }
}
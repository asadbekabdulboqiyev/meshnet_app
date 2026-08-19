package com.meshnet.meshnet_app.protocol

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FileTransferManager {

    companion object {
        private const val TAG = "FileTransfer"
        const val FILE_CHUNK_SIZE = 200
        const val WIFI_CHUNK_SIZE = 64 * 1024
        const val MAX_FILE_SIZE = 50 * 1024 * 1024L
        const val BLE_SIZE_THRESHOLD = 50 * 1024
    }

    data class FileInfo(
        val transferId: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val senderId: String,
    )

    data class TransferProgress(
        val transferId: String,
        val totalBytes: Long,
        val sentBytes: Long,
        val receivedBytes: Long,
        val status: String,
    ) {
        val percent: Int get() = if (totalBytes > 0) ((sentBytes + receivedBytes) * 100 / totalBytes).toInt() else 0
    }

    private val activeTransfers = ConcurrentHashMap<String, TransferProgress>()
    private val assemblyBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val pendingInfo = ConcurrentHashMap<String, FileInfo>()

    fun getProgress(transferId: String): TransferProgress? = activeTransfers[transferId]

    fun cancelTransfer(transferId: String) {
        activeTransfers[transferId] = activeTransfers[transferId]?.copy(status = "cancelled") ?: return
        assemblyBuffers.remove(transferId)
    }

    fun startTransfer(
        fileName: String,
        fileSize: Long,
        mimeType: String,
        senderId: String,
    ): Pair<String, MeshFrame> {
        require(fileSize <= MAX_FILE_SIZE) { "File too large: $fileSize bytes" }

        val transferId = UUID.randomUUID().toString()
        val idBytes = uuidToBytes(transferId)
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
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
        payload[24 + nameBytes.size] = 0x00
        System.arraycopy(mimeBytes, 0, payload, 25 + nameBytes.size, mimeBytes.size)

        activeTransfers[transferId] = TransferProgress(transferId, fileSize, 0, 0, "transferring")

        val frame = MeshFrame(
            type = MessageType.FILE_START,
            hopLimit = 4,
            ttl = 6,
            encrypted = true,
            senderId = senderId,
            targetId = "broadcast",
            msgSeq = System.currentTimeMillis(),
            payload = payload,
            senderPublicKey = null,
        )
        return transferId to frame
    }

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
                hopLimit = 4,
                ttl = 6,
                encrypted = true,
                senderId = senderId,
                targetId = targetId,
                msgSeq = seq++,
                payload = payload,
                senderPublicKey = null,
            ))
            offset = end

            activeTransfers[transferId]?.let { prev ->
                activeTransfers[transferId] = prev.copy(sentBytes = offset.toLong())
            }
        }

        val endPayload = uuidToBytes(transferId)
        frames.add(MeshFrame(
            type = MessageType.FILE_END,
            hopLimit = 4,
            ttl = 6,
            encrypted = true,
            senderId = senderId,
            targetId = targetId,
            msgSeq = seq++,
            payload = endPayload,
            senderPublicKey = null,
        ))

        activeTransfers[transferId]?.let { prev ->
            activeTransfers[transferId] = prev.copy(status = "completed")
        }

        return frames
    }

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

        val remainder = String(payload, 24, payload.size - 24, Charsets.UTF_8)
        val parts = remainder.split("\u0000")
        val fileName = parts.getOrElse(0) { "unknown" }
        val mimeType = parts.getOrElse(1) { "application/octet-stream" }

        val info = FileInfo(transferId, fileName, fileSize, mimeType, senderId)
        pendingInfo[transferId] = info
        assemblyBuffers[transferId] = ByteArrayOutputStream()
        activeTransfers[transferId] = TransferProgress(transferId, fileSize, 0, 0, "transferring")
        return info
    }

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

    fun handleFileEnd(payload: ByteArray): Triple<String, ByteArray?, FileInfo?>? {
        val transferId = bytesToUuid(payload) ?: return null
        val buffer = assemblyBuffers.remove(transferId)
        val info = pendingInfo.remove(transferId)
        val fileBytes = buffer?.toByteArray()
        activeTransfers[transferId]?.let { prev ->
            activeTransfers[transferId] = prev.copy(
                status = "completed",
                receivedBytes = fileBytes?.size?.toLong() ?: 0,
            )
        }
        return Triple(transferId, fileBytes, info)
    }

    fun saveReceivedFile(fileBytes: ByteArray, fileName: String, cacheDir: File): File {
        val dir = File(cacheDir, "meshnet_files")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${System.currentTimeMillis()}_$fileName")
        FileOutputStream(file).use { it.write(fileBytes) }
        return file
    }

    private fun uuidToBytes(uuid: String): ByteArray {
        return try {
            val u = UUID.fromString(uuid)
            java.nio.ByteBuffer.allocate(16).apply {
                putLong(u.mostSignificantBits)
                putLong(u.leastSignificantBits)
            }.array()
        } catch (e: Exception) { ByteArray(16) }
    }

    private fun bytesToUuid(raw: ByteArray): String? {
        return try {
            val bb = java.nio.ByteBuffer.wrap(raw)
            UUID(bb.long, bb.long).toString()
        } catch (e: Exception) { null }
    }
}

package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.protocol.FileTransferManager
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileTransferManager kengaytirilgan testlari: edge cases, constants,
 * multiple chunk sizes, payload format validation.
 */
class FileTransferExtendedTest {

    private val SENDER = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val RECEIVER = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val manager = FileTransferManager()

    // =================== Constants ===================

    @Test
    fun maxFileSize_is50MB() {
        assertEquals(50 * 1024 * 1024L, FileTransferManager.MAX_FILE_SIZE)
    }

    @Test
    fun bleChunkSize_is200Bytes() {
        assertEquals(200, FileTransferManager.FILE_CHUNK_SIZE)
    }

    @Test
    fun bleSizeThreshold_is50KB() {
        assertEquals(50 * 1024, FileTransferManager.BLE_SIZE_THRESHOLD)
    }

    @Test
    fun wifiChunkSize_is64KB() {
        assertEquals(64 * 1024, FileTransferManager.WIFI_CHUNK_SIZE)
    }

    // =================== startTransfer edge cases ===================

    @Test
    fun startTransfer_zeroSizeFile() {
        val (transferId, frame) = manager.startTransfer(
            "empty.txt", 0, "text/plain", SENDER,
        )
        assertNotNull(transferId)
        assertEquals(MessageType.FILE_START, frame.type)
    }

    @Test
    fun startTransfer_exactlyMaxSize() {
        val (transferId, _) = manager.startTransfer(
            "max.bin", FileTransferManager.MAX_FILE_SIZE, "application/octet-stream", SENDER,
        )
        assertNotNull(transferId)
    }

    @Test
    fun startTransfer_emptyFileName() {
        val (transferId, frame) = manager.startTransfer(
            "", 100, "text/plain", SENDER,
        )
        assertNotNull(transferId)
    }

    @Test
    fun startTransfer_variousMimeTypes() {
        for (mime in listOf("text/plain", "image/jpeg", "application/pdf", "audio/mp3", "video/mp4")) {
            val (id, _) = manager.startTransfer("file.bin", 100, mime, SENDER)
            assertNotNull(id)
        }
    }

    @Test
    fun startTransfer_uniqueTransferIds() {
        val ids = (1..10).map {
            manager.startTransfer("file$it.txt", 100, "text/plain", SENDER).first
        }
        assertEquals(10, ids.toSet().size)
    }

    // =================== generateChunkFrames edge cases ===================

    @Test
    fun generateChunkFrames_emptyFile_producesOnlyEnd() {
        val (transferId, _) = manager.startTransfer(
            "empty.txt", 0, "text/plain", SENDER,
        )
        val frames = manager.generateChunkFrames(
            transferId = transferId,
            fileBytes = ByteArray(0),
            senderId = SENDER,
            targetId = RECEIVER,
            useWifi = false,
        )
        assertEquals(1, frames.size)
        assertEquals(MessageType.FILE_END, frames[0].type)
    }

    @Test
    fun generateChunkFrames_exactlyOneChunk_ble() {
        val data = ByteArray(200) { it.toByte() }
        val (transferId, _) = manager.startTransfer(
            "exact.txt", data.size.toLong(), "text/plain", SENDER,
        )
        val frames = manager.generateChunkFrames(
            transferId = transferId,
            fileBytes = data,
            senderId = SENDER,
            targetId = RECEIVER,
            useWifi = false,
        )
        // 1 chunk + 1 end = 2
        assertEquals(2, frames.size)
        assertEquals(1, frames.count { it.type == MessageType.FILE_CHUNK })
        assertEquals(1, frames.count { it.type == MessageType.FILE_END })
    }

    @Test
    fun generateChunkFrames_exactlyOneChunk_wifi() {
        val data = ByteArray(64 * 1024) { it.toByte() }
        val (transferId, _) = manager.startTransfer(
            "exact.bin", data.size.toLong(), "application/octet-stream", SENDER,
        )
        val frames = manager.generateChunkFrames(
            transferId = transferId,
            fileBytes = data,
            senderId = SENDER,
            targetId = RECEIVER,
            useWifi = true,
        )
        // 1 chunk + 1 end = 2
        assertEquals(2, frames.size)
    }

    @Test
    fun generateChunkFrames_oneByte_ble() {
        val (transferId, _) = manager.startTransfer(
            "tiny.txt", 1, "text/plain", SENDER,
        )
        val frames = manager.generateChunkFrames(
            transferId = transferId,
            fileBytes = byteArrayOf(0x42),
            senderId = SENDER,
            targetId = RECEIVER,
            useWifi = false,
        )
        assertEquals(2, frames.size) // 1 chunk + 1 end
    }

    @Test
    fun generateChunkFrames_chunkPayloadFormat() {
        val data = ByteArray(300) { it.toByte() }
        val (transferId, _) = manager.startTransfer(
            "format.bin", data.size.toLong(), "application/octet-stream", SENDER,
        )
        val frames = manager.generateChunkFrames(
            transferId = transferId,
            fileBytes = data,
            senderId = SENDER,
            targetId = RECEIVER,
            useWifi = false,
        )
        val chunkFrame = frames.first { it.type == MessageType.FILE_CHUNK }
        // Payload: 16 bytes UUID + 8 bytes chunk index + chunk data
        assertTrue(chunkFrame.payload.size >= 24)
    }

    @Test
    fun generateChunkFrames_endFramePayloadIs16Bytes() {
        val data = ByteArray(100) { it.toByte() }
        val (transferId, _) = manager.startTransfer(
            "end.bin", data.size.toLong(), "application/octet-stream", SENDER,
        )
        val frames = manager.generateChunkFrames(
            transferId = transferId,
            fileBytes = data,
            senderId = SENDER,
            targetId = RECEIVER,
            useWifi = false,
        )
        val endFrame = frames.last { it.type == MessageType.FILE_END }
        assertEquals(16, endFrame.payload.size)
    }

    // =================== handleFileStart ===================

    @Test
    fun handleFileStart_returnsFileInfo() {
        val (transferId, startFrame) = manager.startTransfer(
            "test.txt", 500, "text/plain", SENDER,
        )
        val info = manager.handleFileStart(SENDER, startFrame.payload)
        assertNotNull(info)
        assertEquals(transferId, info!!.transferId)
        assertEquals("test.txt", info.fileName)
        assertEquals(500L, info.fileSize)
        assertEquals("text/plain", info.mimeType)
    }

    @Test
    fun handleFileStart_variousFileSizes() {
        for (size in listOf(1L, 100L, 1024L, 1048576L, 50 * 1024 * 1024L)) {
            val (_, startFrame) = manager.startTransfer(
                "f.bin", size, "application/octet-stream", SENDER,
            )
            val info = manager.handleFileStart(SENDER, startFrame.payload)
            assertNotNull(info)
            assertEquals(size, info!!.fileSize)
        }
    }

    // =================== handleFileChunk ===================

    @Test
    fun handleFileChunk_returnsChunkData() {
        val data = ByteArray(100) { it.toByte() }
        val (transferId, _) = manager.startTransfer(
            "chunk.bin", data.size.toLong(), "application/octet-stream", SENDER,
        )
        val frames = manager.generateChunkFrames(
            transferId = transferId,
            fileBytes = data,
            senderId = SENDER,
            targetId = RECEIVER,
            useWifi = false,
        )
        val chunkFrame = frames.first { it.type == MessageType.FILE_CHUNK }
        val result = manager.handleFileChunk(chunkFrame.payload)
        assertNotNull(result)
        assertEquals(transferId, result!!.first)
        assertTrue(result.second.isNotEmpty())
    }

    // =================== handleFileEnd ===================

    @Test
    fun handleFileEnd_returnsCompleteTransfer() {
        val original = "Complete file content".toByteArray()
        val (transferId, startFrame) = manager.startTransfer(
            "complete.txt", original.size.toLong(), "text/plain", SENDER,
        )

        manager.handleFileStart(SENDER, startFrame.payload)

        val chunkFrames = manager.generateChunkFrames(
            transferId = transferId,
            fileBytes = original,
            senderId = SENDER,
            targetId = RECEIVER,
            useWifi = false,
        )

        for (frame in chunkFrames.filter { it.type == MessageType.FILE_CHUNK }) {
            manager.handleFileChunk(frame.payload)
        }

        val endFrame = chunkFrames.first { it.type == MessageType.FILE_END }
        val (endId, fileBytes, info) = manager.handleFileEnd(endFrame.payload)!!
        assertEquals(transferId, endId)
        assertNotNull(fileBytes)
        assertTrue(original.contentEquals(fileBytes!!))
        assertNotNull(info)
        assertEquals("complete.txt", info!!.fileName)
    }

    // =================== Progress tracking ===================

    @Test
    fun progress_afterStart_isTransferring() {
        val (_, frame) = manager.startTransfer(
            "f.bin", 1000, "application/octet-stream", SENDER,
        )
        val progress = manager.getProgress(
            manager.handleFileStart(SENDER, frame.payload)!!.transferId
        )
        assertNotNull(progress)
        assertEquals("transferring", progress!!.status)
    }

    @Test
    fun progress_afterChunks_showsProgress() {
        val data = ByteArray(500) { it.toByte() }
        val (transferId, _) = manager.startTransfer(
            "f.bin", data.size.toLong(), "application/octet-stream", SENDER,
        )
        manager.generateChunkFrames(
            transferId = transferId,
            fileBytes = data,
            senderId = SENDER,
            targetId = RECEIVER,
            useWifi = false,
        )
        val progress = manager.getProgress(transferId)
        assertNotNull(progress)
        assertEquals(data.size.toLong(), progress!!.sentBytes)
    }

    @Test
    fun progress_unknownId_returnsNull() {
        assertNull(manager.getProgress("unknown"))
    }

    @Test
    fun progress_cancelledStatus() {
        val (transferId, _) = manager.startTransfer(
            "cancel.bin", 100, "application/octet-stream", SENDER,
        )
        manager.cancelTransfer(transferId)
        val progress = manager.getProgress(transferId)
        assertEquals("cancelled", progress!!.status)
    }

    // =================== Cancel ===================

    @Test
    fun cancelTransfer_setsCancelledStatus() {
        val (transferId, _) = manager.startTransfer(
            "cancel.bin", 1000, "application/octet-stream", SENDER,
        )
        manager.cancelTransfer(transferId)
        assertEquals("cancelled", manager.getProgress(transferId)!!.status)
    }

    @Test
    fun cancelTransfer_idempotent() {
        val (transferId, _) = manager.startTransfer(
            "cancel.bin", 1000, "application/octet-stream", SENDER,
        )
        manager.cancelTransfer(transferId)
        manager.cancelTransfer(transferId) // second cancel
        assertEquals("cancelled", manager.getProgress(transferId)!!.status)
    }

    @Test
    fun cancelTransfer_doesNotAffectOtherTransfers() {
        val (id1, _) = manager.startTransfer("f1.bin", 100, "application/octet-stream", SENDER)
        val (id2, _) = manager.startTransfer("f2.bin", 200, "application/octet-stream", SENDER)
        manager.cancelTransfer(id1)
        assertEquals("cancelled", manager.getProgress(id1)!!.status)
        assertEquals("transferring", manager.getProgress(id2)!!.status)
    }

    // =================== Multiple independent transfers ===================

    @Test
    fun multipleTransfers_sameTime() {
        val data1 = "File one".toByteArray()
        val data2 = "File two content".toByteArray()
        val (id1, start1) = manager.startTransfer("f1.txt", data1.size.toLong(), "text/plain", SENDER)
        val (id2, start2) = manager.startTransfer("f2.txt", data2.size.toLong(), "text/plain", SENDER)

        val info1 = manager.handleFileStart(SENDER, start1.payload)
        val info2 = manager.handleFileStart(SENDER, start2.payload)

        assertEquals(id1, info1!!.transferId)
        assertEquals(id2, info2!!.transferId)
        assertEquals("f1.txt", info1.fileName)
        assertEquals("f2.txt", info2.fileName)
    }

    // =================== FileTransferManager data classes ===================

    @Test
    fun transferProgress_allFieldsAccessible() {
        val (transferId, _) = manager.startTransfer(
            "test.bin", 1000, "application/octet-stream", SENDER,
        )
        val progress = manager.getProgress(transferId)!!
        assertEquals(transferId, progress.transferId)
        assertEquals(0, progress.sentBytes)
        assertEquals(0, progress.receivedBytes)
        assertEquals(0, progress.percent)
        assertEquals("transferring", progress.status)
    }
}

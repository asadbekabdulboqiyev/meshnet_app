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

class FileTransferStressTest {

    private val SENDER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val RECEIVER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val manager = FileTransferManager()

    @Test
    fun startTransfer_returnsUniqueTransferId() {
        val (id1, _) = manager.startTransfer("a.txt", 100L, "text/plain", SENDER_ID)
        val (id2, _) = manager.startTransfer("b.txt", 100L, "text/plain", SENDER_ID)
        assertFalse(id1 == id2)
    }

    @Test
    fun startTransfer_initialProgressIsZero() {
        val (transferId, _) = manager.startTransfer("t.txt", 1000L, "text/plain", SENDER_ID)
        val progress = manager.getProgress(transferId)!!
        assertEquals(0, progress.sentBytes)
        assertEquals(0, progress.receivedBytes)
        assertEquals("transferring", progress.status)
    }

    @Test
    fun startTransfer_percentIsZeroInitially() {
        val (transferId, _) = manager.startTransfer("t.txt", 1000L, "text/plain", SENDER_ID)
        val progress = manager.getProgress(transferId)!!
        assertEquals(0, progress.percent)
    }

    @Test
    fun generateChunkFrames_progressUpdatesOnEachChunk() {
        val fileBytes = ByteArray(600) { it.toByte() }
        val (transferId, _) = manager.startTransfer("t.bin", fileBytes.size.toLong(), "application/octet-stream", SENDER_ID)
        manager.generateChunkFrames(transferId, fileBytes, SENDER_ID, RECEIVER_ID, false)
        val progress = manager.getProgress(transferId)!!
        assertEquals(fileBytes.size.toLong(), progress.sentBytes)
        assertEquals("completed", progress.status)
    }

    @Test
    fun generateChunkFrames_percentIs100AfterComplete() {
        val fileBytes = ByteArray(400) { it.toByte() }
        val (transferId, _) = manager.startTransfer("t.bin", fileBytes.size.toLong(), "application/octet-stream", SENDER_ID)
        manager.generateChunkFrames(transferId, fileBytes, SENDER_ID, RECEIVER_ID, false)
        assertEquals(100, manager.getProgress(transferId)!!.percent)
    }

    @Test
    fun cancelTransfer_setsCancelledStatus() {
        val (transferId, _) = manager.startTransfer("t.bin", 500L, "application/octet-stream", SENDER_ID)
        manager.cancelTransfer(transferId)
        assertEquals("cancelled", manager.getProgress(transferId)!!.status)
    }

    @Test
    fun cancelTransfer_nonExistent_doesNotCrash() {
        manager.cancelTransfer("nonexistent-id")
    }

    @Test
    fun generateChunkFrames_bleChunkSize() {
        val fileBytes = ByteArray(600) { it.toByte() }
        val (transferId, _) = manager.startTransfer("t.bin", fileBytes.size.toLong(), "application/octet-stream", SENDER_ID)
        val frames = manager.generateChunkFrames(transferId, fileBytes, SENDER_ID, RECEIVER_ID, false)
        val chunkFrames = frames.filter { it.type == MessageType.FILE_CHUNK }
        assertEquals(3, chunkFrames.size)
    }

    @Test
    fun generateChunkFrames_wifiChunkSize() {
        val fileBytes = ByteArray(128_000) { it.toByte() }
        val (transferId, _) = manager.startTransfer("t.bin", fileBytes.size.toLong(), "application/octet-stream", SENDER_ID)
        val frames = manager.generateChunkFrames(transferId, fileBytes, SENDER_ID, RECEIVER_ID, true)
        val chunkFrames = frames.filter { it.type == MessageType.FILE_CHUNK }
        assertEquals(2, chunkFrames.size)
    }

    @Test
    fun generateChunkFrames_lastFrameIsFileEnd() {
        val fileBytes = ByteArray(300) { it.toByte() }
        val (transferId, _) = manager.startTransfer("t.bin", fileBytes.size.toLong(), "application/octet-stream", SENDER_ID)
        val frames = manager.generateChunkFrames(transferId, fileBytes, SENDER_ID, RECEIVER_ID, false)
        assertEquals(MessageType.FILE_END, frames.last().type)
    }

    @Test
    fun generateChunkFrames_fileEndHas16BytePayload() {
        val fileBytes = ByteArray(300) { it.toByte() }
        val (transferId, _) = manager.startTransfer("t.bin", fileBytes.size.toLong(), "application/octet-stream", SENDER_ID)
        val frames = manager.generateChunkFrames(transferId, fileBytes, SENDER_ID, RECEIVER_ID, false)
        val endFrame = frames.last { it.type == MessageType.FILE_END }
        assertEquals(16, endFrame.payload.size)
    }

    @Test
    fun handleFileStart_returnsFileInfo() {
        val (_, startFrame) = manager.startTransfer("test.txt", 100L, "text/plain", SENDER_ID)
        val info = manager.handleFileStart(SENDER_ID, startFrame.payload)
        assertNotNull(info)
        assertEquals("test.txt", info!!.fileName)
        assertEquals(100L, info.fileSize)
    }

    @Test
    fun handleFileChunk_returnsChunkData() {
        val (_, startFrame) = manager.startTransfer("test.bin", 200L, "application/octet-stream", SENDER_ID)
        manager.handleFileStart(SENDER_ID, startFrame.payload)
        val chunkFrames = manager.generateChunkFrames(
            manager.getProgress(manager.getProgress("")?.transferId ?: "")?.transferId ?: "",
            ByteArray(200), SENDER_ID, RECEIVER_ID, false
        )
        val chunkFrame = chunkFrames.firstOrNull { it.type == MessageType.FILE_CHUNK }
        if (chunkFrame != null) {
            val result = manager.handleFileChunk(chunkFrame.payload)
            assertNotNull(result)
        }
    }

    @Test
    fun handleFileEnd_returnsTriple() {
        val (transferId, startFrame) = manager.startTransfer("test.txt", 50L, "text/plain", SENDER_ID)
        manager.handleFileStart(SENDER_ID, startFrame.payload)
        val chunkFrames = manager.generateChunkFrames(transferId, ByteArray(50) { it.toByte() }, SENDER_ID, RECEIVER_ID, false)
        for (f in chunkFrames.filter { it.type == MessageType.FILE_CHUNK }) {
            manager.handleFileChunk(f.payload)
        }
        val endFrame = chunkFrames.first { it.type == MessageType.FILE_END }
        val result = manager.handleFileEnd(endFrame.payload)
        assertNotNull(result)
        assertEquals(transferId, result!!.first)
        assertNotNull(result.second)
    }

    @Test
    fun multipleTransfers_allHaveProgress() {
        val ids = (1..5).map { i ->
            val (id, _) = manager.startTransfer("file$i.txt", (i * 100).toLong(), "text/plain", SENDER_ID)
            id
        }
        for (id in ids) {
            assertNotNull(manager.getProgress(id))
            assertEquals("transferring", manager.getProgress(id)!!.status)
        }
    }

    @Test
    fun multipleTransfers_cancelOneOthersUnaffected() {
        val (id1, _) = manager.startTransfer("a.txt", 100L, "text/plain", SENDER_ID)
        val (id2, _) = manager.startTransfer("b.txt", 200L, "text/plain", SENDER_ID)
        manager.cancelTransfer(id1)
        assertEquals("cancelled", manager.getProgress(id1)!!.status)
        assertEquals("transferring", manager.getProgress(id2)!!.status)
    }

    @Test
    fun startTransfer_fileSizeAtMax_works() {
        val (id, frame) = manager.startTransfer("max.bin", FileTransferManager.MAX_FILE_SIZE, "application/octet-stream", SENDER_ID)
        assertNotNull(id)
        assertNotNull(frame)
    }

    @Test
    fun startTransfer_fileSizeZero_works() {
        val (id, frame) = manager.startTransfer("zero.txt", 0L, "text/plain", SENDER_ID)
        assertNotNull(id)
        assertNotNull(frame)
    }

    @Test
    fun handleFileStart_shortPayload_returnsNull() {
        assertNull(manager.handleFileStart(SENDER_ID, ByteArray(5)))
    }

    @Test
    fun handleFileChunk_shortPayload_returnsNull() {
        assertNull(manager.handleFileChunk(ByteArray(5)))
    }

    @Test
    fun handleFileEnd_shortPayload_returnsNull() {
        assertNull(manager.handleFileEnd(ByteArray(5)))
    }

    @Test
    fun fileTransferManager_constants() {
        assertEquals(200, FileTransferManager.FILE_CHUNK_SIZE)
        assertEquals(64 * 1024, FileTransferManager.WIFI_CHUNK_SIZE)
        assertEquals(50 * 1024 * 1024L, FileTransferManager.MAX_FILE_SIZE)
        assertEquals(50 * 1024, FileTransferManager.BLE_SIZE_THRESHOLD)
    }

    @Test
    fun generateChunkFrames_allChunksEncrypted() {
        val fileBytes = ByteArray(500) { it.toByte() }
        val (transferId, _) = manager.startTransfer("t.bin", fileBytes.size.toLong(), "application/octet-stream", SENDER_ID)
        val frames = manager.generateChunkFrames(transferId, fileBytes, SENDER_ID, RECEIVER_ID, false)
        for (frame in frames) {
            assertTrue(frame.encrypted)
        }
    }

    @Test
    fun generateChunkFrames_allFramesHaveHopLimit4() {
        val fileBytes = ByteArray(500) { it.toByte() }
        val (transferId, _) = manager.startTransfer("t.bin", fileBytes.size.toLong(), "application/octet-stream", SENDER_ID)
        val frames = manager.generateChunkFrames(transferId, fileBytes, SENDER_ID, RECEIVER_ID, false)
        for (frame in frames) {
            assertEquals(4, frame.hopLimit)
        }
    }

    @Test
    fun fullRoundTrip_assembleFileFromChunks() {
        val original = "Salom bu test fayli. Unda turli belgilar mavjud: abc123!@#".toByteArray(Charsets.UTF_8)
        val (transferId, startFrame) = manager.startTransfer("roundtrip.txt", original.size.toLong(), "text/plain", SENDER_ID)
        manager.handleFileStart(SENDER_ID, startFrame.payload)
        val chunks = manager.generateChunkFrames(transferId, original, SENDER_ID, RECEIVER_ID, false)
        for (c in chunks.filter { it.type == MessageType.FILE_CHUNK }) {
            manager.handleFileChunk(c.payload)
        }
        val (_, fileBytes, _) = manager.handleFileEnd(chunks.first { it.type == MessageType.FILE_END }.payload)!!
        assertTrue(original.contentEquals(fileBytes!!))
    }
}

package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.chunk.Chunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.random.Random

/**
 * Chunker testlari: bo'lak hajmlari, content hash, stream/bytes pariteti,
 * chekka holatlar (bo'sh fayl, aynan bo'linadigan hajm).
 */
class ChunkerTest {

    @Test
    fun emptyInputProducesNoChunks() {
        assertTrue(Chunker().split(ByteArray(0)).isEmpty())
        assertTrue(Chunker().split(ByteArrayInputStream(ByteArray(0))).isEmpty())
    }

    @Test
    fun smallFileSingleChunk() {
        val data = ByteArray(1000) { it.toByte() }
        val chunks = Chunker().split(data)
        assertEquals(1, chunks.size)
        assertEquals(data.size, chunks[0].data.size)
        assertEquals(data.toList(), chunks[0].data.toList())
    }

    @Test
    fun exactMultipleOfChunkSize() {
        val data = ByteArray(64 * 1024 * 3) { (it % 251).toByte() }
        val chunks = Chunker().split(data)
        assertEquals(3, chunks.size)
        assertEquals(data.toList(), chunks.flatMap { it.data.toList() })
    }

    @Test
    fun remainderChunkSmaller() {
        val data = ByteArray(64 * 1024 + 1234)
        Random(42).nextBytes(data)
        val chunks = Chunker().split(data)
        assertEquals(2, chunks.size)
        assertEquals(1234, chunks[1].data.size)
        // Offsets recorded correctly
        assertEquals(0L, chunks[0].offset)
        assertEquals(64 * 1024L, chunks[1].offset)
    }

    @Test
    fun hashesAreSha256HexAndDeterministic() {
        val data = ByteArray(5000) { it.toByte() }
        val c1 = Chunker().split(data)[0]
        val c2 = Chunker().split(data)[0]
        assertEquals(64, c1.hash.length)
        assertTrue(c1.hash.all { it.isDigit() || it in 'a'..'f' })
        assertEquals(c1.hash, c2.hash)
    }

    @Test
    fun differentContentDifferentHash() {
        val h1 = Chunker().split(ByteArray(100) { 1 })[0].hash
        val h2 = Chunker().split(ByteArray(100) { 2 })[0].hash
        assertNotEquals(h1, h2)
    }

    @Test
    fun identicalChunksAcrossFilesShareHash() {
        // Dedup foundation: with fixed-size chunking, an ALIGNED 64KB block
        // shared by two files yields an identical first-chunk hash.
        val shared = ByteArray(Chunker.DEFAULT_CHUNK_SIZE) { (it * 7).toByte() }
        val fileA = shared + ByteArray(500) { 1 }
        val fileB = shared + ByteArray(900) { 2 }
        val hashA = Chunker().split(fileA)[0].hash // first chunk == shared block
        val hashB = Chunker().split(fileB)[0].hash
        assertEquals(hashA, hashB)
        // Honest limitation check: UNALIGNED placement does NOT dedup
        // (fixed-size chunking; content-defined chunking is future work)
        val fileC = ByteArray(300) { 9 } + shared
        assertNotEquals(hashA, Chunker().split(fileC)[0].hash)
    }

    @Test
    fun streamSplitMatchesBytesSplit() {
        val data = ByteArray(200_000) { Random(it).nextInt().toByte() }
        val fromBytes = Chunker().split(data)
        val fromStream = Chunker().split(ByteArrayInputStream(data))
        assertEquals(fromBytes.map { it.hash }, fromStream.map { it.hash })
        assertEquals(fromBytes.map { it.offset }, fromStream.map { it.offset })
    }

    @Test
    fun customChunkSizeRespected() {
        val data = ByteArray(10_000)
        val chunks = Chunker(chunkSize = 4096).split(data)
        assertEquals(3, chunks.size)
        assertEquals(listOf(4096, 4096, 1808), chunks.map { it.data.size })
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooSmallChunkSizeRejected() {
        Chunker(chunkSize = 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooLargeChunkSizeRejected() {
        Chunker(chunkSize = 2 * 1024 * 1024)
    }

    @Test
    fun splitFromFileWorks() {
        val tmp = File.createTempFile("chunker", ".bin")
        try {
            val data = ByteArray(70_000) { (it % 256).toByte() }
            tmp.writeBytes(data)
            val chunks = Chunker().split(tmp)
            assertEquals(data.toList(), chunks.flatMap { it.data.toList() })
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun joinReconstructsOriginal() {
        val data = ByteArray(150_000) { (it * 31 % 256).toByte() }
        val joined = Chunker().join(Chunker().split(data))
        assertEquals(data.toList(), joined.toList())
    }
}

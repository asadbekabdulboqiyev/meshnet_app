package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.chunk.ChunkStore
import com.meshnet.meshnet_app.localnet.chunk.Chunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * ChunkStore testlari: content-addressed saqlash, dedup, integrity tekshiruv,
 * diskda qolish (persistence), buzuq chunk aniqlash.
 */
class ChunkStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: ChunkStore

    @Before
    fun setUp() {
        store = ChunkStore(tmp.newFolder())
    }

    @Test
    fun putReturnsSha256Hash() {
        val data = ByteArray(1000) { it.toByte() }
        val hash = store.put(data)
        assertEquals(Chunker.sha256Hex(data), hash)
    }

    @Test
    fun getRoundTrip() {
        val data = ByteArray(5000) { Random(it).nextInt().toByte() }
        val hash = store.put(data)
        assertEquals(data.toList(), store.get(hash)?.toList())
    }

    @Test
    fun dedupSameContentStoredOnce() {
        val data = ByteArray(2000) { 7 }
        val h1 = store.put(data)
        val h2 = store.put(data.copyOf()) // equal content, different array
        assertEquals(h1, h2)
        assertEquals(1, store.chunkCount())
        assertEquals(data.size.toLong(), store.totalBytes())
    }

    @Test
    fun differentContentDifferentFiles() {
        store.put(ByteArray(100) { 1 })
        store.put(ByteArray(100) { 2 })
        assertEquals(2, store.chunkCount())
    }

    @Test
    fun missReturnsNull() {
        assertNull(store.get("ab".repeat(32)))
    }

    @Test
    fun invalidHashReturnsNullSafely() {
        assertNull(store.get(""))
        assertNull(store.get("xyz"))
        assertNull(store.get("g".repeat(64))) // not hex
        assertNull(store.get("a".repeat(63))) // wrong length
        assertFalse(store.has("../etc/passwd"))
    }

    @Test
    fun hasReflectsPresence() {
        val hash = store.put(ByteArray(10) { 5 })
        assertTrue(store.has(hash))
        assertFalse(store.has("ff".repeat(32)))
    }

    @Test
    fun corruptedChunkDetectedAsMiss() {
        val hash = store.put(ByteArray(100) { 3 })
        // Simulate bit rot: overwrite the stored file with garbage of same size
        val files = tmp.root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }
        val chunkFile = files.first()
        chunkFile.writeBytes(ByteArray(100) { 9 })
        assertNull(store.get(hash))
        assertFalse(store.has(hash))
    }

    @Test
    fun truncatedChunkDetectedAsMiss() {
        val hash = store.put(ByteArray(100) { 3 })
        val chunkFile = tmp.root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.first()
        chunkFile.writeBytes(ByteArray(50) { 3 }) // half the bytes
        assertNull(store.get(hash))
    }

    @Test
    fun persistsAcrossInstances() {
        val dir = tmp.newFolder()
        val s1 = ChunkStore(dir)
        val data = ByteArray(1234) { (it * 13).toByte() }
        val hash = s1.put(data)
        val s2 = ChunkStore(dir) // "app restart"
        assertEquals(data.toList(), s2.get(hash)?.toList())
        assertEquals(1, s2.chunkCount())
    }

    @Test
    fun cleanCorruptedRemovesTempFiles() {
        val hash = store.put(ByteArray(50) { 1 })
        assertNotNull(store.get(hash))
        // Simulate leftover temp file from a crash mid-write
        val chunkFile = tmp.root.walkTopDown().filter { it.isFile }.first()
        java.io.File(chunkFile.parentFile, "${chunkFile.name}.tmp").writeBytes(ByteArray(10))
        assertEquals(1, store.cleanCorrupted())
        assertEquals(50, store.get(hash)!!.size)
    }
}

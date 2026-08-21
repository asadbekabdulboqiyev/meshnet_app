package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.chunk.ChunkStore
import com.meshnet.meshnet_app.localnet.chunk.Chunker
import com.meshnet.meshnet_app.localnet.chunk.FileAssembler
import com.meshnet.meshnet_app.localnet.chunk.FileManifest
import com.meshnet.meshnet_app.localnet.chunk.SyncPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * SyncPlanner + FileAssembler testlari: incremental sync mantiqi va
 * hash-verified yig'ish.
 */
class SyncAssembleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildManifest(data: ByteArray): Pair<FileManifest, ChunkStore> {
        val store = ChunkStore(tmp.newFolder())
        val chunks = Chunker(chunkSize = 1024).split(data)
        val manifest = FileManifest.fromChunks("test.bin", "application/octet-stream", chunks, 1024, "dev", 0)
        return manifest to store
    }

    // ---------------- SyncPlanner ----------------

    @Test
    fun allMissingWhenStoreEmpty() {
        val (manifest, store) = buildManifest(ByteArray(5000))
        assertEquals(manifest.chunks.size, SyncPlanner.missingChunks(manifest, store).size)
        assertFalse(SyncPlanner.isComplete(manifest, store))
        assertEquals(0, SyncPlanner.haveCount(manifest, store))
    }

    @Test
    fun noneMissingAfterFullStore() {
        val (manifest, store) = buildManifest(ByteArray(5000) { it.toByte() })
        // Fill properly using chunker output
        Chunker(1024).split(ByteArray(5000) { it.toByte() }).forEach { store.put(it.data) }
        assertTrue(SyncPlanner.missingChunks(manifest, store).isEmpty())
        assertTrue(SyncPlanner.isComplete(manifest, store))
        assertEquals(manifest.chunks.size, SyncPlanner.haveCount(manifest, store))
        assertEquals(0L, SyncPlanner.remainingBytes(manifest, store))
    }

    @Test
    fun partialFillReportsOnlyMissing() {
        val data = ByteArray(5000) { (it % 251).toByte() }
        val chunks = Chunker(1024).split(data)
        val manifest = FileManifest.fromChunks("f", "m", chunks, 1024, "d", 0)
        val store = ChunkStore(tmp.newFolder())
        // Store only the second chunk (simulates previous partial download)
        store.put(chunks[1].data)
        val missing = SyncPlanner.missingChunks(manifest, store)
        assertEquals(chunks.filterIndexed { i, _ -> i != 1 }.map { it.hash }, missing)
        assertEquals(1, SyncPlanner.haveCount(manifest, store))
        assertEquals(
            chunks.filterIndexed { i, _ -> i != 1 }.sumOf { it.data.size }.toLong(),
            SyncPlanner.remainingBytes(manifest, store),
        )
    }

    @Test
    fun dedupAcrossFilesSkipsKnownChunks() {
        // Two files sharing a 2048-byte block: planner for file B must not
        // list the shared chunk if file A's block is already stored.
        val sharedBlock = ByteArray(2048) { (it * 3).toByte() }
        val fileA = sharedBlock + ByteArray(100) { 1 }
        val fileB = ByteArray(100) { 2 } + sharedBlock
        val store = ChunkStore(tmp.newFolder())
        Chunker().split(fileA).forEach { store.put(it.data) }

        val manifestB = FileManifest.fromChunks(
            "b.bin", "m", Chunker().split(fileB), Chunker.DEFAULT_CHUNK_SIZE, "d", 0,
        )
        val missing = SyncPlanner.missingChunks(manifestB, store)
        // Only the unique 100-byte head of B needs transfer — dedup works
        assertEquals(1, missing.size)
        assertNotEquals(Chunker.sha256Hex(sharedBlock), missing[0])
    }

    // ---------------- FileAssembler ----------------

    @Test
    fun assembleRebuildsOriginalBytes() {
        val data = ByteArray(10_000) { Random(it).nextInt().toByte() }
        val chunks = Chunker(1024).split(data)
        val manifest = FileManifest.fromChunks("out.bin", "m", chunks, 1024, "d", 0)
        val store = ChunkStore(tmp.newFolder())
        chunks.forEach { store.put(it.data) }

        val out = FileAssembler.assemble(manifest, store, tmp.newFolder())
        assertNotNull(out)
        assertEquals(data.toList(), out!!.readBytes().toList())
        assertTrue(FileAssembler.verifySize(manifest, out))
    }

    @Test
    fun assembleFailsOnMissingChunk() {
        val data = ByteArray(5000)
        val chunks = Chunker(1024).split(data)
        val manifest = FileManifest.fromChunks("f", "m", chunks, 1024, "d", 0)
        val store = ChunkStore(tmp.newFolder())
        chunks.dropLast(1).forEach { store.put(it.data) } // one short
        assertNull(FileAssembler.assemble(manifest, store, tmp.newFolder()))
    }

    @Test
    fun assembleFailsOnCorruptedChunk() {
        val data = ByteArray(5000) { 4 }
        val chunks = Chunker(1024).split(data)
        val manifest = FileManifest.fromChunks("f", "m", chunks, 1024, "d", 0)
        val store = ChunkStore(tmp.newFolder())
        chunks.forEach { store.put(it.data) }
        // Corrupt one stored chunk directly on disk
        val storedFile = tmp.root.walkTopDown()
            .filter { it.isFile && it.length() == 1024L }
            .first()
        storedFile.writeBytes(ByteArray(1024) { 99 })
        assertNull(FileAssembler.assemble(manifest, store, tmp.newFolder()))
    }

    @Test
    fun safeNameNeutralizesPathSeparators() {
        val chunks = Chunker(1024).split(ByteArray(10))
        val manifest = FileManifest.fromChunks("../../etc/passwd", "m", chunks, 1024, "d", 0)
        val store = ChunkStore(tmp.newFolder())
        chunks.forEach { store.put(it.data) }
        val outDir = tmp.newFolder()
        val out = FileAssembler.assemble(manifest, store, outDir)
        assertNotNull(out)
        assertEquals(outDir, out!!.parentFile) // never escapes the downloads dir
    }
}

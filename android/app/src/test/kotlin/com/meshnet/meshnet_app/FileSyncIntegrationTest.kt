package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.HttpClient
import com.meshnet.meshnet_app.localnet.LocalHttpServer
import com.meshnet.meshnet_app.localnet.LocalNetService
import com.meshnet.meshnet_app.localnet.chunk.ChunkStore
import com.meshnet.meshnet_app.localnet.chunk.Chunker
import com.meshnet.meshnet_app.localnet.chunk.FileAssembler
import com.meshnet.meshnet_app.localnet.chunk.FileManifest
import com.meshnet.meshnet_app.localnet.chunk.SyncPlanner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import android.content.Context
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.storage.MeshDatabase
import com.meshnet.meshnet_app.storage.PeerStore
import kotlin.random.Random

/**
 * Phase 2 integratsiya testlari: real HTTP server + real ChunkStore bilan
 * to'liq share -> list -> manifest -> chunk fetch -> assemble zanjiri.
 * Bu LocalNet fayl almashinuvininng "P2P Dropbox" yo'li.
 */
class FileSyncIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var serverService: LocalNetService
    private lateinit var clientStore: ChunkStore
    private lateinit var clientDownloads: java.io.File
    private val http = HttpClient(connectTimeoutMs = 2000, readTimeoutMs = 5000)

    private var port = -1
    private val hostIp = "127.0.0.1"

    companion object {
        private const val ID_SERVER = "99999999-9999-9999-9999-999999999999"
        private const val ID_CLIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    }

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
        val routing = RoutingEngine(mock(Context::class.java), ID_SERVER, ByteArray(32), PeerStore(mock(Context::class.java)))
        serverService = LocalNetService(
            selfDeviceId = ID_SERVER,
            selfDisplayName = "Server Node",
            routing = routing,
            baseDir = tmp.newFolder(),
        )
        // Port 0 -> OS free port (parallel-safe)
        val server = LocalHttpServer(0, object : LocalHttpServer.ContentProvider {
            override fun deviceName() = "Server Node"
            override fun deviceId() = ID_SERVER
            override fun knownHostsJson() = "{}"
        }, object : LocalHttpServer.FileProvider {
            override fun sharedFileIds() = serverService.sharedFiles().map { it.fileId }
            override fun manifestText(fileId: String) = serverService.manifestById(fileId)?.serialize()
            override fun chunkData(hash: String) = serverService.chunkStore.get(hash)
        })
        assertTrue(server.start())
        port = server.boundPort

        clientStore = ChunkStore(tmp.newFolder())
        clientDownloads = tmp.newFolder("downloads")
    }

    @After
    fun tearDown() {
        serverService.stop()
        MeshDatabase.resetInstance()
    }

    private fun bigTestFile(size: Int): java.io.File {
        val f = tmp.newFile("mesh-backup.bin")
        f.writeBytes(ByteArray(size) { Random(it * 7).nextInt().toByte() })
        return f
    }

    @Test
    fun fullRoundTrip_shareListFetchAssemble() {
        val original = bigTestFile(300_000) // ~5 chunks of 64KB
        val manifest = serverService.shareFile(original.absolutePath)!!
        org.junit.Assert.assertNotNull(manifest)

        // 1. Client lists remote files over HTTP
        val idsText = http.getText(hostIp, port, "/files")!!
        assertEquals(listOf(manifest.fileId), idsText.lines().filter { it.isNotBlank() })

        // 2. Client fetches the manifest
        val fetchedManifest = FileManifest.parse(http.getText(hostIp, port, "/manifest/${manifest.fileId}")!!)!!
        assertEquals(manifest, fetchedManifest)

        // 3. Client downloads every missing chunk
        for (hash in SyncPlanner.missingChunks(fetchedManifest, clientStore)) {
            val data = http.getBytes(hostIp, port, "/chunk/$hash")
            org.junit.Assert.assertNotNull(data)
            assertEquals(hash, Chunker.sha256Hex(data!!))
            clientStore.put(data)
        }

        // 4. Assemble and compare byte-for-byte
        val assembled = FileAssembler.assemble(fetchedManifest, clientStore, clientDownloads)!!
        org.junit.Assert.assertNotNull(assembled)
        assertEquals(original.length(), assembled.length())
        assertTrue(original.readBytes().contentEquals(assembled.readBytes()))
    }

    @Test
    fun incrementalSync_onlyMissingChunksTransferred() {
        val original = bigTestFile(200_000)
        val manifest = serverService.shareFile(original.absolutePath)!!
        org.junit.Assert.assertNotNull(manifest)
        val chunks = Chunker().split(original)

        // Client already has the FIRST TWO chunks (previous partial sync)
        clientStore.put(chunks[0].data)
        clientStore.put(chunks[1].data)
        val haveBefore = SyncPlanner.haveCount(manifest, clientStore)
        assertEquals(2, haveBefore)

        // Planner must only request the rest
        val missing = SyncPlanner.missingChunks(manifest, clientStore)
        assertEquals(chunks.size - 2, missing.size)

        var transferred = 0
        for (hash in missing) {
            val data = http.getBytes(hostIp, port, "/chunk/$hash")!!
            transferred += data.size
            clientStore.put(data)
        }
        // Transferred bytes exclude the two locally-present chunks
        assertEquals(manifest.fileSize - chunks[0].data.size - chunks[1].data.size, transferred.toLong())

        val assembled = FileAssembler.assemble(manifest, clientStore, clientDownloads)!!
        org.junit.Assert.assertNotNull(assembled)
        assertTrue(original.readBytes().contentEquals(assembled.readBytes()))
    }

    @Test
    fun dedup_identicalFileSharedTwiceStoresOnce() {
        val original = bigTestFile(150_000)
        val m1 = serverService.shareFile(original.absolutePath)!!
        // Same content re-shared under a different name -> different fileId,
        // but identical chunk hashes -> zero new storage.
        val renamed = java.io.File(original.parent, "copy-of-mesh-backup.bin")
        renamed.writeBytes(original.readBytes())
        val before = serverService.chunkStore.totalBytes()
        val m2 = serverService.shareFile(renamed.absolutePath)!!
        assertEquals(m1.chunks.map { it.hash }, m2.chunks.map { it.hash })
        assertEquals(before, serverService.chunkStore.totalBytes())
    }

    @Test
    fun unknownManifestAndChunkReturn404Null() {
        assertNull(http.getText(hostIp, port, "/manifest/${"a".repeat(40)}"))
        assertNull(http.getBytes(hostIp, port, "/chunk/${"b".repeat(64)}"))
        // Malformed ids rejected with 400 -> null from client
        assertNull(http.getText(hostIp, port, "/manifest/short"))
        assertNull(http.getBytes(hostIp, port, "/chunk/not-a-hash"))
    }
}

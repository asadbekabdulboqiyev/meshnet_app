package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.LocalHttpServer
import com.meshnet.meshnet_app.localnet.LocalNetService
import com.meshnet.meshnet_app.localnet.apps.AppRepository
import com.meshnet.meshnet_app.localnet.apps.ApkMetadataExtractor
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.storage.MeshDatabase
import com.meshnet.meshnet_app.storage.PeerStore
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 4 integratsiya: real HTTP orqali to'liq app distribution zanjiri —
 * server APK share -> client hostApps listing -> fetchFile (chunked,
 * hash-verified) -> onDownloadCompleted metadata -> downloadedPath install.
 */
class AppDistributionIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var serverService: LocalNetService
    private lateinit var clientService: LocalNetService
    private lateinit var clientRepo: AppRepository
    private var port = -1
    private val hostIp = "127.0.0.1"

    companion object {
        private const val ID_SERVER = "77777777-7777-7777-7777-777777777777"
        private const val ID_CLIENT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        private const val HOSTNAME = "appserver"
    }

    private val fakeExtractor = ApkMetadataExtractor { path ->
        if (File(path).readText().contains("PKG=com.techcorp.meshdemo")) {
            ApkMetadataExtractor.PackageInfo("com.techcorp.meshdemo", "2.0.0", 20)
        } else {
            null
        }
    }

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
        serverService = makeService(ID_SERVER, "App Server")
        clientService = makeService(ID_CLIENT, "App Client")
        clientRepo = AppRepository(clientService, fakeExtractor)

        val server = LocalHttpServer(0, object : LocalHttpServer.ContentProvider {
            override fun deviceName() = "App Server"
            override fun deviceId() = ID_SERVER
            override fun knownHostsJson() = "{}"
        }, object : LocalHttpServer.FileProvider {
            override fun sharedFileIds() = serverService.sharedFiles().map { it.fileId }
            override fun manifestText(fileId: String) = serverService.manifestById(fileId)?.serialize()
            override fun chunkData(hash: String) = serverService.chunkStore.get(hash)
        })
        assertTrue(server.start())
        port = server.boundPort

        // Client learns the server's endpoint exactly like a DNS_ANNOUNCE would teach it
        assertTrue(
            clientService.dns.handleAnnounce(HOSTNAME, ID_SERVER, "App Server", 1_000L, port, hostIp),
        )
    }

    @After
    fun tearDown() {
        serverService.stop()
        clientService.stop()
        MeshDatabase.resetInstance()
    }

    private fun makeService(selfId: String, name: String): LocalNetService {
        val routing = RoutingEngine(mock(Context::class.java), selfId, ByteArray(32), PeerStore(mock(Context::class.java)))
        return LocalNetService(
            selfDeviceId = selfId,
            selfDisplayName = name,
            routing = routing,
            baseDir = tmp.newFolder(),
        )
    }

    private fun fakeApkFile(name: String, sizeKb: Int): File {
        val f = tmp.newFile(name)
        // Small enough for one chunk; content marks it as our package
        val body = ByteArray(sizeKb * 1024) { (it % 256).toByte() }
        "PKG=com.techcorp.meshdemo".toByteArray().copyInto(body)
        f.writeBytes(body)
        return f
    }

    @Test
    fun fullLoop_shareListFetchInstall() {
        val apk = fakeApkFile("meshdemo.apk", 96)
        val manifest = serverService.shareFile(apk.absolutePath)
        assertNotNull(manifest)

        // 1. Client lists the host's apps over HTTP (manifest-only metadata)
        val remoteApps = clientRepo.hostApps(HOSTNAME)
        assertEquals(1, remoteApps.size)
        assertEquals("meshdemo.apk", remoteApps[0].fileName)
        assertNull(remoteApps[0].packageName) // honest: unknown until downloaded

        // 2. Fetch through the real chunked transport
        assertTrue(clientService.fetchFile(HOSTNAME, manifest!!.fileId))
        val done = CountDownLatch(1)
        var assembledPath = ""
        clientService.addListener(object : LocalNetService.Listener {
            override fun onFileSyncProgress(fileId: String, fileName: String, have: Int, total: Int, state: String, filePath: String) {
                if (fileId == manifest.fileId && state == "done") {
                    assembledPath = filePath
                    done.countDown()
                }
            }
        })
        // Listener added after start may miss the event on fast loopback — poll as fallback
        clientService.fetchFile(HOSTNAME, manifest.fileId)
        assertTrue(done.await(10, TimeUnit.SECONDS) || waitUntilAssembled(manifest.fileId))

        // 3. Download completed -> parse real package info from the assembled file
        val path = if (assembledPath.isNotBlank()) assembledPath else clientRepo.downloadedPath(manifest.fileId)!!
        val meta = clientRepo.onDownloadCompleted(manifest.fileId, path)
        assertNotNull(meta)
        assertEquals("com.techcorp.meshdemo", meta!!.packageName)
        assertEquals("2.0.0", meta.versionName)
        assertEquals(20L, meta.versionCode)

        // 4. Install handoff finds the assembled file with exact size
        // Give a moment for the file to be fully registered
        var downloadedPath: String? = null
        for (i in 1..10) {
            downloadedPath = clientRepo.downloadedPath(manifest.fileId)
            if (downloadedPath != null) break
            Thread.sleep(50)
        }
        assertNotNull(downloadedPath)
        assertEquals(File(clientService.downloadsDir, "meshdemo.apk").absolutePath, downloadedPath)
    }

    private fun waitUntilAssembled(fileId: String): Boolean {
        repeat(50) {
            if (clientRepo.downloadedPath(fileId) != null) return true
            Thread.sleep(100)
        }
        return false
    }

    @Test
    fun hostApps_ignoresNonApkFiles() {
        val apk = fakeApkFile("store-app.apk", 64)
        val txt = tmp.newFile("readme.txt").apply { writeText("hello") }
        assertNotNull(serverService.shareFile(apk.absolutePath))
        assertNotNull(serverService.shareFile(txt.absolutePath))

        val apps = clientRepo.hostApps(HOSTNAME)
        assertEquals(1, apps.size)
        assertEquals("store-app.apk", apps[0].fileName)
    }

    @Test
    fun hostApps_unknownHostReturnsEmpty() {
        assertTrue(clientRepo.hostApps("ghost-host").isEmpty())
    }
}

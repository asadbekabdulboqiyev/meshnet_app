package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.LocalNetService
import com.meshnet.meshnet_app.localnet.apps.AppRepository
import com.meshnet.meshnet_app.localnet.apps.ApkMetadataExtractor
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.storage.MeshDatabase
import com.meshnet.meshnet_app.storage.PeerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * Phase 4 unit testlari: AppRepository mantig'i (APK filtrlash, metadata
 * extraction, kesh, download path). PackageManager o'rniga fake extractor.
 */
class AppRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var service: LocalNetService
    private lateinit var repo: AppRepository

    private var extractCalls = 0
    private var lastExtractedPath: String? = null

    private val fakeExtractor = ApkMetadataExtractor { path ->
        extractCalls++
        lastExtractedPath = path
        if (File(path).name.startsWith("real")) {
            ApkMetadataExtractor.PackageInfo("com.techcorp.meshdemo", "1.4.2", 14)
        } else {
            null
        }
    }

    companion object {
        private const val SELF_ID = "44444444-4444-4444-4444-444444444444"
    }

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
        val routing = RoutingEngine(mock(Context::class.java), SELF_ID, ByteArray(32), PeerStore(mock(Context::class.java)))
        service = LocalNetService(
            selfDeviceId = SELF_ID,
            selfDisplayName = "Test Node",
            routing = routing,
            baseDir = tmp.newFolder(),
        )
        repo = AppRepository(service, fakeExtractor)
    }

    @After
    fun tearDown() {
        service.stop()
        MeshDatabase.resetInstance()
    }

    private fun makeApk(name: String, size: Int = 1024): File {
        val f = tmp.newFile(name)
        f.writeBytes(ByteArray(size) { (it % 251).toByte() })
        return f
    }

    @Test
    fun localApps_filtersOnlyApkMime() {
        val apk = makeApk("game.apk")
        val txt = makeApk("notes.txt")
        assertNotNull(service.shareFile(apk.absolutePath))
        assertNotNull(service.shareFile(txt.absolutePath))

        val apps = repo.localApps()
        assertEquals(1, apps.size)
        assertEquals("game.apk", apps[0].fileName)
        assertEquals(AppRepository.APK_MIME, service.manifestById(apps[0].fileId)?.mimeType)
    }

    @Test
    fun localApps_parsesPackageInfoFromOriginFile() {
        val apk = makeApk("realgame.apk")
        val manifest = service.shareFile(apk.absolutePath)!!
        // Origin path was recorded at share time -> extractor sees it
        assertEquals(apk.absolutePath, service.sharedOriginPaths[manifest.fileId])

        val apps = repo.localApps()
        assertEquals(1, apps.size)
        assertTrue(apps[0].hasPackageInfo)
        assertEquals("com.techcorp.meshdemo", apps[0].packageName)
        assertEquals("1.4.2", apps[0].versionName)
        assertEquals(14L, apps[0].versionCode)
    }

    @Test
    fun localApps_unreadableApkStillListedWithoutPackageInfo() {
        val apk = makeApk("broken.apk") // name does not start with "real" -> extractor null
        service.shareFile(apk.absolutePath)

        val apps = repo.localApps()
        assertEquals(1, apps.size)
        assertFalse(apps[0].hasPackageInfo)
        assertNull(apps[0].packageName)
    }

    @Test
    fun appFor_unknownOrNonApkReturnsNull() {
        assertNull(repo.appFor("no-such-file"))
        val txt = makeApk("plain.txt")
        val m = service.shareFile(txt.absolutePath)!!
        assertNull(repo.appFor(m.fileId))
    }

    @Test
    fun onDownloadCompleted_parsesProvidedFileAndCaches() {
        val apk = makeApk("realstore.apk")
        val manifest = service.shareFile(apk.absolutePath)!!

        extractCalls = 0
        val meta = repo.onDownloadCompleted(manifest.fileId, apk.absolutePath)
        assertNotNull(meta)
        assertEquals("com.techcorp.meshdemo", meta!!.packageName)
        assertEquals(1, extractCalls)
        assertEquals(apk.absolutePath, lastExtractedPath)

        // Second lookup is served from cache (no new extraction)
        val again = repo.appFor(manifest.fileId)
        assertEquals("com.techcorp.meshdemo", again?.packageName)
        assertEquals(1, extractCalls)
    }

    @Test
    fun onDownloadCompleted_unknownFileOrNonApkReturnsNull() {
        assertNull(repo.onDownloadCompleted("ghost-id", "/tmp/x.apk"))
        val txt = makeApk("doc.txt")
        val m = service.shareFile(txt.absolutePath)!!
        assertNull(repo.onDownloadCompleted(m.fileId, txt.absolutePath))
    }

    @Test
    fun downloadedPath_verifiesExistenceAndSize() {
        val apk = makeApk("realpkg.apk", size = 2048)
        val manifest = service.shareFile(apk.absolutePath)!!

        // Not assembled yet -> null
        assertNull(repo.downloadedPath(manifest.fileId))

        // Assembled with exact size -> path returned
        val assembled = File(service.downloadsDir, manifest.fileName).apply {
            writeBytes(apk.readBytes())
        }
        assertEquals(assembled.absolutePath, repo.downloadedPath(manifest.fileId))

        // Wrong size (partial/corrupt assembly) -> rejected
        assembled.writeBytes(ByteArray(10))
        assertNull(repo.downloadedPath(manifest.fileId))
    }
}

package com.meshnet.meshnet_app.localnet.apps

import android.util.Log
import com.meshnet.meshnet_app.localnet.LocalNetService
import com.meshnet.meshnet_app.localnet.chunk.FileManifest
import java.util.concurrent.ConcurrentHashMap

/**
 * AppRepository - LocalNet Phase 4 "offline app store" logic.
 *
 * The transport is exactly Phase 2 file sharing (chunked, hash-verified,
 * incremental); this class adds APK-awareness on top:
 *   - localApps()  : our shared files filtered to APKs + parsed metadata
 *   - hostApps()   : a remote host's shared APKs (metadata limited to the
 *                    manifest until downloaded — honest limitation)
 *   - appFor()     : metadata for one fileId (cache-first)
 *
 * HONEST LIMITS: no signature verification of third-party APKs beyond the
 * chunk hashes proving transfer integrity; Android itself verifies installer
 * signatures at install time. No version ranking / update channels — this is
 * a distribution channel, not a marketplace.
 */
class AppRepository(
    private val localNet: LocalNetService,
    private val extractor: ApkMetadataExtractor,
) {

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val TAG = "AppRepository"

        fun isApk(manifest: FileManifest): Boolean = manifest.mimeType == APK_MIME
    }

    // fileId -> parsed metadata cache
    private val metadataCache = ConcurrentHashMap<String, ApkMetadata>()

    /** All locally shared APKs with best-effort package metadata. */
    fun localApps(): List<ApkMetadata> =
        localNet.sharedFiles().filter { isApk(it) }.map { metadataFor(it) }

    /** Metadata for one shared file id (cache-first; null if not an APK). */
    fun appFor(fileId: String): ApkMetadata? {
        metadataCache[fileId]?.let { return it }
        val manifest = localNet.manifestById(fileId)
            ?: localNet.downloadedManifestById(fileId)
            ?: return null
        if (!isApk(manifest)) return null
        return metadataFor(manifest)
    }

    /**
     * A remote host's shared APKs. Metadata comes from the manifest only;
     * package name/version are unknown until the APK is downloaded.
     * Blocking network I/O — call off the UI thread.
     */
    fun hostApps(hostname: String): List<ApkMetadata> =
        localNet.fetchHostFileList(hostname)
            .filter { isApk(it) }
            .map { m ->
                ApkMetadata(
                    fileId = m.fileId,
                    fileName = m.fileName,
                    fileSize = m.fileSize,
                    packageName = null,
                    versionName = null,
                    versionCode = null,
                    senderDeviceId = m.senderDeviceId,
                )
            }

    /**
     * Called after a download completes: parse the fetched APK and cache its
     * real metadata so the UI can show an "Install" entry with package info.
     */
    fun onDownloadCompleted(fileId: String, filePath: String): ApkMetadata? {
        val manifest = localNet.downloadedManifestById(fileId)
            ?: localNet.manifestById(fileId)
            ?: return null
        if (!isApk(manifest)) return null
        val info = extractor.extract(filePath) ?: run {
            Log.w(TAG, "onDownloadCompleted: not a readable APK: $filePath")
            return null
        }
        val meta = ApkMetadata(
            fileId = fileId,
            fileName = manifest.fileName,
            fileSize = manifest.fileSize,
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = info.versionCode,
            senderDeviceId = manifest.senderDeviceId,
        )
        metadataCache[fileId] = meta
        return meta
    }

    /** Path of a completed download for [fileId], if we have it assembled. */
    fun downloadedPath(fileId: String): String? {
        val manifest = localNet.downloadedManifestById(fileId)
            ?: localNet.manifestById(fileId)
            ?: return null
        val f = java.io.File(localNet.downloadsDir, manifest.fileName)
        return if (f.exists() && f.length() == manifest.fileSize) f.absolutePath else null
    }

    private fun metadataFor(manifest: FileManifest): ApkMetadata {
        metadataCache[manifest.fileId]?.let { return it }
        // Best effort: if we still have the original shared file on disk,
        // parse real package info from it right now.
        val originPath = localNet.sharedOriginPaths[manifest.fileId]
        val info = originPath?.let { extractor.extract(it) }
        val meta = ApkMetadata(
            fileId = manifest.fileId,
            fileName = manifest.fileName,
            fileSize = manifest.fileSize,
            packageName = info?.packageName,
            versionName = info?.versionName,
            versionCode = info?.versionCode,
            senderDeviceId = manifest.senderDeviceId,
        )
        metadataCache[manifest.fileId] = meta
        return meta
    }
}

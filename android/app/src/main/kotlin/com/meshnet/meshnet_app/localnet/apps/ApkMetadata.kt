package com.meshnet.meshnet_app.localnet.apps

import android.content.Context
import android.content.pm.PackageManager

/**
 * Metadata about an installable Android package (APK) in the LocalNet
 * app repository (Phase 4).
 *
 * For LOCALLY shared APKs we can parse real package info via PackageManager.
 * For REMOTE apps we only know the manifest data until the file is
 * downloaded — that is an honest limitation of a serverless store.
 */
data class ApkMetadata(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val senderDeviceId: String,
) {
    /** True when real package info was parsed from the APK itself. */
    val hasPackageInfo: Boolean get() = packageName != null

    fun toMap(): Map<String, Any?> = mapOf(
        "fileId" to fileId,
        "fileName" to fileName,
        "fileSize" to fileSize,
        "packageName" to packageName,
        "versionName" to versionName,
        "versionCode" to versionCode,
        "senderDeviceId" to senderDeviceId,
        "hasPackageInfo" to hasPackageInfo,
    )
}

/**
 * Extracts package metadata from an APK file. Abstracted so unit tests can
 * inject a fake (PackageManager is an Android runtime dependency).
 */
fun interface ApkMetadataExtractor {
    /**
     * Returns [ApkMetadata.PackageInfo] for a local APK file, or null when
     * the file is not a readable Android package.
     */
    fun extract(apkPath: String): PackageInfo?

    data class PackageInfo(val packageName: String, val versionName: String?, val versionCode: Long)
}

/** Production extractor backed by Android's PackageManager. */
class PackageManagerApkExtractor(private val context: Context) : ApkMetadataExtractor {

    override fun extract(apkPath: String): ApkMetadataExtractor.PackageInfo? = try {
        val pm = context.packageManager
        val archiveInfo = pm.getPackageArchiveInfo(apkPath, 0) ?: return null
        if (archiveInfo.packageName.isNullOrBlank()) {
            null
        } else {
            ApkMetadataExtractor.PackageInfo(
                packageName = archiveInfo.packageName,
                versionName = archiveInfo.versionName,
                versionCode = archiveInfo.longVersionCode,
            )
        }
    } catch (_: Exception) {
        null
    }
}

package com.meshnet.meshnet_app.localnet.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider

/**
 * Hands a downloaded APK to the Android system package installer.
 *
 * Thin glue on purpose: the actual install flow (user consent dialog,
 * signature verification, "install unknown apps" permission) is owned by
 * Android itself — we only launch ACTION_VIEW with the APK URI.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    /**
     * Launches the system installer for [apkPath]. Must be called with a
     * real [Context]; posts to the main thread internally.
     *
     * Returns false when no installer activity is available (rare) or the
     * intent cannot be started.
     */
    fun install(context: Context, apkPath: String): Boolean {
        return try {
            val file = java.io.File(apkPath)
            if (!file.exists()) {
                Log.w(TAG, "install: file missing: $apkPath")
                return false
            }
            val uri: Uri = FileProvider.getUriForFile(
                context,
                context.packageName + AUTHORITY_SUFFIX,
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Handler(Looper.getMainLooper()).post {
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "install: startActivity failed: ${e.message}")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "install failed: ${e.message}")
            false
        }
    }
}

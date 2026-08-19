package com.meshnet.meshnet_app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private lateinit var meshEngine: MeshEngine

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        meshEngine = MeshEngine(applicationContext)

        // Method channel
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            MeshEngine.METHOD_CHANNEL
        ).setMethodCallHandler(meshEngine.handler)

        // Event channel
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            MeshEngine.EVENT_CHANNEL
        ).setStreamHandler(meshEngine.eventListener)

        // Foreground service - so mesh runs continuously when app opens
        startMeshService()
    }

    @SuppressLint("MissingPermission")
    private fun startMeshService() {
        val intent = Intent(this, MeshService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        // engine is stopped (service continues)
        if (::meshEngine.isInitialized) {
            meshEngine.stop()
        }
        super.onDestroy()
    }
}
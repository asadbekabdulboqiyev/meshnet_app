package com.meshnet.meshnet_app.protocol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class VoiceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val MAX_DURATION_MS = 120_000
    }

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null
    private val pcmBuffer = ByteArrayOutputStream()

    fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission missing")
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        )

        pcmBuffer.reset()
        isRecording = true
        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ByteArray(4096)
            val startTime = System.currentTimeMillis()
            while (isRecording && (System.currentTimeMillis() - startTime) < MAX_DURATION_MS) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    synchronized(pcmBuffer) {
                        pcmBuffer.write(buffer, 0, read)
                    }
                }
            }
        }.also { it.start() }
    }

    fun stopRecording(): ByteArray? {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Stop error: ${e.message}")
        }
        audioRecord = null
        recordingThread?.join(2000)
        recordingThread = null

        synchronized(pcmBuffer) {
            return if (pcmBuffer.size() > 0) pcmBuffer.toByteArray() else null
        }
    }

    fun getRecordingDurationMs(): Long {
        val bytesPerSecond = SAMPLE_RATE * 2
        return synchronized(pcmBuffer) {
            (pcmBuffer.size().toLong() * 1000 / bytesPerSecond)
        }
    }

    fun savePcmToFile(pcmData: ByteArray): File {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.pcm")
        FileOutputStream(file).use { it.write(pcmData) }
        return file
    }

    fun isCurrentlyRecording(): Boolean = isRecording
}

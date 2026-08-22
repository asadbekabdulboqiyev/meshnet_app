package com.meshnet.meshnet_app.protocol

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Voice message playback: encoded file (Opus/AAC raw MediaCodec output)
 * -> PCM decode -> AudioTrack.
 *
 * One active playback at a time (chat UX standard): starting another
 * message stops the previous one. Play on the same message toggles
 * pause/resume. Speed control via PlaybackParams (API 23+, minSdk 26).
 */
class VoicePlayer(private val encoder: VoiceEncoder) {

    interface Listener {
        fun onPlaybackStateChanged(
            messageId: String,
            isPlaying: Boolean,
            positionMs: Long,
            durationMs: Long,
            finished: Boolean,
        )
    }

    var listener: Listener? = null

    private class Session(
        val messageId: String,
        val track: AudioTrack,
        val totalFrames: Int,
        val durationMs: Long,
        var speed: Float,
    )

    @Volatile
    private var current: Session? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val session = current ?: return
            val head = try {
                session.track.playbackHeadPosition
            } catch (_: Exception) {
                0
            }
            val positionMs = framesToMs(head)
            val finished = head >= session.totalFrames
            listener?.onPlaybackStateChanged(
                session.messageId,
                isPlaying = !finished && session.track.playState == AudioTrack.PLAYSTATE_PLAYING,
                positionMs = if (finished) session.durationMs else positionMs,
                durationMs = session.durationMs,
                finished = finished,
            )
            if (finished) {
                releaseCurrent()
            } else {
                mainHandler.postDelayed(this, TICK_MS)
            }
        }
    }

    /** Play or toggle. Returns false if the file cannot be loaded/decoded. */
    fun play(messageId: String, filePath: String): Boolean {
        // Same message -> toggle pause/resume
        val cur = current
        if (cur != null && cur.messageId == messageId) {
            return if (cur.track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                pause(messageId)
                true
            } else {
                resume(messageId)
                true
            }
        }
        stop()
        return loadAndStart(messageId, filePath)
    }

    fun pause(messageId: String): Boolean {
        val cur = current ?: return false
        if (cur.messageId != messageId) return false
        return try {
            cur.track.pause()
            emitState(cur, isPlaying = false, finished = false)
            true
        } catch (e: Exception) {
            Log.w(TAG, "pause failed: ${e.message}")
            false
        }
    }

    fun resume(messageId: String): Boolean {
        val cur = current ?: return false
        if (cur.messageId != messageId) return false
        return try {
            applySpeed(cur, cur.speed)
            cur.track.play()
            startTicker()
            emitState(cur, isPlaying = true, finished = false)
            true
        } catch (e: Exception) {
            Log.w(TAG, "resume failed: ${e.message}")
            false
        }
    }

    /** Stop whatever is playing (any message). */
    fun stop() {
        if (current != null) {
            val cur = current!!
            emitState(cur, isPlaying = false, finished = false)
            releaseCurrent()
        }
    }

    fun setSpeed(messageId: String, speed: Float): Boolean {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val cur = current ?: return false
        if (cur.messageId != messageId) return false
        cur.speed = clamped
        return try {
            applySpeed(cur, clamped)
            true
        } catch (e: Exception) {
            Log.w(TAG, "setSpeed failed: ${e.message}")
            false
        }
    }

    fun isPlaying(messageId: String): Boolean {
        val cur = current ?: return false
        return cur.messageId == messageId &&
            cur.track.playState == AudioTrack.PLAYSTATE_PLAYING
    }

    fun release() {
        releaseCurrent()
    }

    // ---------- internals ----------

    private fun loadAndStart(messageId: String, filePath: String): Boolean {
        val file = File(filePath)
        if (!file.exists()) {
            Log.w(TAG, "voice file missing: $filePath")
            return false
        }
        // Decode off the main thread; AudioTrack must be driven from main.
        Thread {
            try {
                val encoded = file.readBytes()
                val codec = codecFromPath(filePath)
                val pcm = encoder.decode(encoded, codec)
                if (pcm.isEmpty()) {
                    notifyLoadFailed(messageId)
                    return@Thread
                }
                mainHandler.post { startSession(messageId, pcm) }
            } catch (e: Exception) {
                Log.w(TAG, "decode failed ($filePath): ${e.message}")
                notifyLoadFailed(messageId)
            }
        }.start()
        return true
    }

    private fun notifyLoadFailed(messageId: String) {
        mainHandler.post {
            listener?.onPlaybackStateChanged(messageId, false, 0, 0, finished = true)
        }
    }

    private fun startSession(messageId: String, pcm: ByteArray) {
        val minBuf = AudioTrack.getMinBufferSize(
            VoiceEncoder.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(VoiceEncoder.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            pcm.size.coerceAtLeast(minBuf),
            AudioTrack.MODE_STATIC,
            minBuf,
        )
        track.write(pcm, 0, pcm.size)
        val totalFrames = pcm.size / BYTES_PER_SAMPLE
        val session = Session(
            messageId = messageId,
            track = track,
            totalFrames = totalFrames,
            durationMs = computeDurationMs(pcm.size),
            speed = DEFAULT_SPEED,
        )
        current = session
        track.play()
        startTicker()
        emitState(session, isPlaying = true, finished = false)
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.post(tickRunnable)
    }

    private fun applySpeed(session: Session, speed: Float) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            session.track.playbackParams = session.track.playbackParams.setSpeed(speed)
        }
    }

    private fun emitState(session: Session, isPlaying: Boolean, finished: Boolean) {
        val head = try {
            session.track.playbackHeadPosition
        } catch (_: Exception) {
            0
        }
        listener?.onPlaybackStateChanged(
            session.messageId,
            isPlaying,
            framesToMs(head),
            session.durationMs,
            finished,
        )
    }

    private fun releaseCurrent() {
        mainHandler.removeCallbacks(tickRunnable)
        val cur = current ?: return
        current = null
        try {
            cur.track.stop()
        } catch (_: Exception) {}
        try {
            cur.track.release()
        } catch (_: Exception) {}
    }

    private fun framesToMs(frames: Int): Long =
        frames * 1000L / VoiceEncoder.SAMPLE_RATE

    companion object {
        private const val TAG = "VoicePlayer"
        private const val TICK_MS = 100L
        private const val BYTES_PER_SAMPLE = 2 // 16-bit PCM
        const val DEFAULT_SPEED = 1.0f
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f

        /** Duration of 16-bit mono PCM at the encoder sample rate. */
        fun computeDurationMs(pcmSizeBytes: Int): Long =
            pcmSizeBytes / BYTES_PER_SAMPLE * 1000L / VoiceEncoder.SAMPLE_RATE

        /** Codec name from recorded/received file extension (.opus else aac). */
        fun codecFromPath(path: String): String =
            if (path.lowercase().endsWith(".opus")) "opus" else "aac"
    }
}

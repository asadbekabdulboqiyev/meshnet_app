package com.meshnet.meshnet_app.protocol

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class VoiceEncoder {

    companion object {
        private const val TAG = "VoiceEncoder"
        const val AAC_MIME = "audio/mp4a-latm"
        const val OPUS_MIME = "audio/opus"
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val AAC_BIT_RATE = 32000
        const val OPUS_BIT_RATE = 24000
    }

    /** Check Opus availability. */
    private fun isOpusAvailable(): Boolean {
        return try {
            val codec = MediaCodec.createEncoderByType(OPUS_MIME)
            codec.release()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** PCM -> encoded audio. Opus preferred, AAC fallback. */
    fun encode(pcmData: ByteArray, preferOpus: Boolean = true): Pair<ByteArray, String> {
        if (preferOpus && isOpusAvailable()) {
            return try {
                encodePcmToOpus(pcmData) to "opus"
            } catch (e: Exception) {
                Log.w(TAG, "Opus encoding error, falling back to AAC: ${e.message}")
                encodePcmToAac(pcmData) to "aac"
            }
        }
        return encodePcmToAac(pcmData) to "aac"
    }

    /** Decoding: based on codec name. */
    fun decode(encodedData: ByteArray, codec: String): ByteArray {
        return when (codec) {
            "opus" -> decodeOpusToPcm(encodedData)
            else -> decodeAacToPcm(encodedData)
        }
    }

    fun encodePcmToOpus(pcmData: ByteArray): ByteArray {
        val format = MediaFormat.createAudioFormat(OPUS_MIME, SAMPLE_RATE, CHANNELS)
        format.setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BIT_RATE)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcmData.size)

        val codec = MediaCodec.createEncoderByType(OPUS_MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val output = encodeWithCodec(codec, pcmData)
        codec.stop()
        codec.release()
        return output
    }

    fun decodeOpusToPcm(opusData: ByteArray): ByteArray {
        val format = MediaFormat.createAudioFormat(OPUS_MIME, SAMPLE_RATE, CHANNELS)
        val codec = MediaCodec.createDecoderByType(OPUS_MIME)
        codec.configure(format, null, null, 0)
        codec.start()

        val output = decodeWithCodec(codec, opusData)
        codec.stop()
        codec.release()
        return output
    }

    fun encodePcmToAac(pcmData: ByteArray): ByteArray {
        val format = MediaFormat.createAudioFormat(AAC_MIME, SAMPLE_RATE, CHANNELS)
        format.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcmData.size)
        format.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC
        )

        val codec = MediaCodec.createEncoderByType(AAC_MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val output = encodeWithCodec(codec, pcmData)
        codec.stop()
        codec.release()
        return output
    }

    fun decodeAacToPcm(aacData: ByteArray): ByteArray {
        val format = MediaFormat.createAudioFormat(AAC_MIME, SAMPLE_RATE, CHANNELS)
        val codec = MediaCodec.createDecoderByType(AAC_MIME)
        codec.configure(format, null, null, 0)
        codec.start()

        val output = decodeWithCodec(codec, aacData)
        codec.stop()
        codec.release()
        return output
    }

    private fun encodeWithCodec(codec: MediaCodec, pcmData: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputOffset = 0
        var outputDone = false

        while (!outputDone) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuf = codec.getInputBuffer(inputIndex) ?: continue
                val remaining = minOf(4096, pcmData.size - inputOffset)
                if (remaining > 0) {
                    inputBuf.clear()
                    inputBuf.put(pcmData, inputOffset, remaining)
                    codec.queueInputBuffer(
                        inputIndex, 0, remaining,
                        inputOffset * 1000L / (SAMPLE_RATE * 2), 0
                    )
                    inputOffset += remaining
                } else {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex >= 0) {
                val outputBuf = codec.getOutputBuffer(outputIndex) ?: continue
                val data = ByteArray(bufferInfo.size)
                outputBuf.get(data)
                output.write(data)
                codec.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }
        return output.toByteArray()
    }

    private fun decodeWithCodec(codec: MediaCodec, encodedData: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputOffset = 0
        var outputDone = false

        while (!outputDone) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuf = codec.getInputBuffer(inputIndex) ?: continue
                val remaining = minOf(4096, encodedData.size - inputOffset)
                if (remaining > 0) {
                    inputBuf.clear()
                    inputBuf.put(encodedData, inputOffset, remaining)
                    codec.queueInputBuffer(inputIndex, 0, remaining, 0, 0)
                    inputOffset += remaining
                } else {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex >= 0) {
                val outputBuf = codec.getOutputBuffer(outputIndex) ?: continue
                val data = ByteArray(bufferInfo.size)
                outputBuf.get(data)
                output.write(data)
                codec.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }
        return output.toByteArray()
    }
}

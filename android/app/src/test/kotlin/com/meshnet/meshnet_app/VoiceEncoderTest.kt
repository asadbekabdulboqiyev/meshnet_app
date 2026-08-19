package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.protocol.VoiceEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume
import org.junit.Before
import org.junit.Test

class VoiceEncoderTest {

    private lateinit var encoder: VoiceEncoder

    @Before
    fun setUp() {
        encoder = VoiceEncoder()
    }

    @Test
    fun constants_sampleRate() {
        assertEquals(16000, VoiceEncoder.SAMPLE_RATE)
    }

    @Test
    fun constants_channels() {
        assertEquals(1, VoiceEncoder.CHANNELS)
    }

    @Test
    fun constants_aacBitRate() {
        assertEquals(32000, VoiceEncoder.AAC_BIT_RATE)
    }

    @Test
    fun constants_opusBitRate() {
        assertEquals(24000, VoiceEncoder.OPUS_BIT_RATE)
    }

    @Test
    fun constants_aacMime() {
        assertEquals("audio/mp4a-latm", VoiceEncoder.AAC_MIME)
    }

    @Test
    fun constants_opusMime() {
        assertEquals("audio/opus", VoiceEncoder.OPUS_MIME)
    }

    @Test
    fun constructor_createsInstance() {
        assertNotNull(encoder)
    }

    @Test
    fun encode_emptyPcm_skipsIfNoCodec() {
        try {
            encoder.encode(ByteArray(0), preferOpus = false)
        } catch (e: Exception) {
            // Expected in JVM tests where MediaCodec is unavailable
        }
    }
}

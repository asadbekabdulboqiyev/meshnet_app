package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.chunk.Chunker
import com.meshnet.meshnet_app.localnet.chunk.FileManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FileManifest testlari: deterministik fileId, serialize/parse roundtrip,
 * maxsus belgili nomlar, buzilgan manifest rad etilishi.
 */
class FileManifestTest {

    private fun sampleChunks(count: Int = 3): List<Chunker.Chunk> =
        (0 until count).map { i ->
            val data = ByteArray(100) { (it + i).toByte() }
            Chunker.Chunk(Chunker.sha256Hex(data), data, (i * 100).toLong())
        }

    @Test
    fun fileIdIsDeterministic() {
        val chunks = sampleChunks()
        val m1 = FileManifest.fromChunks("report.pdf", "application/pdf", chunks, 65536, "device-a", 1000)
        val m2 = FileManifest.fromChunks("report.pdf", "application/pdf", chunks, 65536, "device-a", 2000)
        assertEquals(m1.fileId, m2.fileId)
    }

    @Test
    fun fileIdChangesWithContent() {
        val m1 = FileManifest.fromChunks("f.bin", "app/oct", sampleChunks(), 65536, "d", 0)
        val m2 = FileManifest.fromChunks("f.bin", "app/oct", sampleChunks(count = 4), 65536, "d", 0)
        assertNotEquals(m1.fileId, m2.fileId)
    }

    @Test
    fun fileSizeIsSumOfChunks() {
        val m = FileManifest.fromChunks("f", "mime", sampleChunks(3), 65536, "d", 0)
        assertEquals(300L, m.fileSize)
    }

    @Test
    fun serializeParseRoundtrip() {
        val original = FileManifest.fromChunks(
            "hisob-kitob hisobi.txt", "text/plain; charset=utf-8",
            sampleChunks(5), 65536, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 1234567890,
        )
        val parsed = FileManifest.parse(original.serialize())
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun specialCharactersInNameSurvive() {
        val tricky = "fayl \"quoted\"=with=equals\nnewline & ünïcode.mesh"
        val original = FileManifest.fromChunks(tricky, "mime/with=special", sampleChunks(), 1024, "dev=ice", 1)
        val parsed = FileManifest.parse(original.serialize())
        assertEquals(original, parsed)
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(FileManifest.parse(""))
        assertNull(FileManifest.parse("hello world"))
        assertNull(FileManifest.parse("LNMANIFEST 1\nno-equals-line\n"))
        assertNull(FileManifest.parse("LNMANIFEST 2\nid=x\n")) // wrong version magic line still needs fields
    }

    @Test
    fun parseRejectsMissingFields() {
        val base = FileManifest.fromChunks("f", "m", sampleChunks(), 65536, "d", 0)
        val lines = base.serialize().lines()
        // Drop the chunks line -> inconsistent manifest rejected
        val withoutChunks = lines.filter { !it.startsWith("chunks=") }.joinToString("\n")
        assertNull(FileManifest.parse(withoutChunks))
    }

    @Test
    fun parseRejectsTamperedSize() {
        val base = FileManifest.fromChunks("f", "m", sampleChunks(), 65536, "d", 0)
        val tampered = base.serialize().lines()
            .joinToString("\n") { if (it.startsWith("size=")) "size=99999" else it }
        assertNull(FileManifest.parse(tampered))
    }

    @Test
    fun parseRejectsBadChunkHash() {
        val base = FileManifest.fromChunks("f", "m", sampleChunks(), 65536, "d", 0)
        val tampered = base.serialize().lines()
            .joinToString("\n") { line ->
                if (line.startsWith("chunks=")) {
                    "chunks=" + line.removePrefix("chunks=").substringBefore(":") + "_bad:100"
                } else line
            }
        assertNull(FileManifest.parse(tampered))
    }

    @Test
    fun parseRejectsWrongFileId() {
        val base = FileManifest.fromChunks("f", "m", sampleChunks(), 65536, "d", 0)
        val tampered = base.serialize().lines()
            .joinToString("\n") { if (it.startsWith("id=")) "id=${"a".repeat(40)}" else it }
        assertNull(FileManifest.parse(tampered))
    }
}

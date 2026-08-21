package com.meshnet.meshnet_app.localnet.chunk

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Chunker - splits files into fixed-size chunks with SHA-256 content hashes.
 *
 * Content addressing is the foundation of dedup: identical chunks (within a
 * file, across files, across devices) collapse to a single stored copy.
 */
class Chunker(private val chunkSize: Int = DEFAULT_CHUNK_SIZE) {

    companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024 // 64 KB
        const val MIN_CHUNK_SIZE = 1024          // 1 KB
        const val MAX_CHUNK_SIZE = 1024 * 1024   // 1 MB

        fun sha256Hex(data: ByteArray, offset: Int = 0, length: Int = data.size): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(data, offset, length)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    data class Chunk(
        val hash: String,
        val data: ByteArray,
        /** Offset of this chunk inside the source file. */
        val offset: Long,
    )

    init {
        require(chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) {
            "chunkSize must be $MIN_CHUNK_SIZE..$MAX_CHUNK_SIZE"
        }
    }

    fun split(data: ByteArray): List<Chunk> {
        if (data.isEmpty()) return emptyList()
        val chunks = ArrayList<Chunk>(data.size / chunkSize + 1)
        var offset = 0
        while (offset < data.size) {
            val len = minOf(chunkSize, data.size - offset)
            val slice = data.copyOfRange(offset, offset + len)
            chunks.add(Chunk(sha256Hex(slice), slice, offset.toLong()))
            offset += len
        }
        return chunks
    }

    /** Streaming variant for large files — reads in chunks, never the whole file. */
    fun split(input: InputStream): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        val buf = ByteArray(chunkSize)
        var offset = 0L
        while (true) {
            var read = 0
            while (read < chunkSize) {
                val r = input.read(buf, read, chunkSize - read)
                if (r < 0) break
                read += r
            }
            if (read == 0) break
            chunks.add(Chunk(sha256Hex(buf, 0, read), buf.copyOf(read), offset))
            offset += read
            if (read < chunkSize) break
        }
        return chunks
    }

    fun split(file: File): List<Chunk> = file.inputStream().buffered().use { split(it) }

    /** Rebuild raw bytes from chunks (test helper / small files). */
    fun join(chunks: List<Chunk>): ByteArray {
        val out = ByteArrayOutputStream(chunks.sumOf { it.data.size })
        chunks.forEach { out.write(it.data) }
        return out.toByteArray()
    }
}

package com.meshnet.meshnet_app.localnet.chunk

import java.io.File

/**
 * ChunkStore - disk-backed content-addressed chunk storage.
 *
 * Layout: <root>/<hash[0..1]>/<hash[2..3]>/<hash>  (two-level fan-out)
 *
 * Dedup is structural: put() is keyed by SHA-256 of the content, so storing
 * the same chunk twice writes one file. Integrity: get() verifies the hash
 * and treats mismatches (bit rot, truncated writes) as a miss.
 */
class ChunkStore(private val rootDir: File) {

    companion object {
        const val HASH_LEN = 64 // sha256 hex length

        fun isValidHash(hash: String): Boolean =
            hash.length == HASH_LEN && hash.all { it.isDigit() || it in 'a'..'f' }
    }

    init {
        rootDir.mkdirs()
    }

    /** Store chunk; returns its hash. Writing the same content twice is a no-op. */
    fun put(data: ByteArray): String {
        val hash = Chunker.sha256Hex(data)
        val file = pathFor(hash)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            // Write-then-rename so a crash never leaves a half-written chunk
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                file.writeBytes(data)
            }
        }
        return hash
    }

    /** Fetch chunk bytes; verifies integrity, returns null on miss/corruption. */
    fun get(hash: String): ByteArray? {
        if (!isValidHash(hash)) return null
        val file = pathFor(hash)
        if (!file.exists()) return null
        val data = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }
        if (Chunker.sha256Hex(data) != hash) return null
        return data
    }

    fun has(hash: String): Boolean = get(hash) != null

    /** Number of unique chunks stored. */
    fun chunkCount(): Int = allChunkFiles().size

    /** Total stored bytes (unique chunks only — dedup already applied). */
    fun totalBytes(): Long = allChunkFiles().sumOf { it.length() }

    /** Remove corrupted / temp files; returns count removed. */
    fun cleanCorrupted(): Int {
        var removed = 0
        allChunkFiles().filter { it.name.endsWith(".tmp") }.forEach {
            if (it.delete()) removed++
        }
        return removed
    }

    private fun allChunkFiles(): List<File> =
        rootDir.listFiles()?.flatMap { level1 ->
            level1.listFiles()?.flatMap { level2 ->
                level2.listFiles()?.toList() ?: emptyList()
            } ?: emptyList()
        } ?: emptyList()

    private fun pathFor(hash: String): File =
        File(File(File(rootDir, hash.substring(0, 2)), hash.substring(2, 4)), hash)
}

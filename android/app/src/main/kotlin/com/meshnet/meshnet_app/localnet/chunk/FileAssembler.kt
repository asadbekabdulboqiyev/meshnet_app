package com.meshnet.meshnet_app.localnet.chunk

import java.io.File
import java.io.FileOutputStream

/**
 * FileAssembler - rebuilds a file from its chunks with full integrity checks.
 *
 * Every chunk is hash-verified while writing; a single bad/missing chunk
 * aborts the assembly (no silently corrupted output).
 */
object FileAssembler {

    /**
     * Assemble [manifest] from [store] into [outputDir].
     * Returns the written file, or null if any chunk is missing or corrupt.
     */
    fun assemble(manifest: FileManifest, store: ChunkStore, outputDir: File): File? {
        if (!SyncPlanner.isComplete(manifest, store)) return null
        outputDir.mkdirs()
        val safeName = manifest.fileName.replace(Regex("[/\\\\]"), "_").ifBlank { "file" }
        val out = File(outputDir, safeName)
        try {
            FileOutputStream(out).use { fos ->
                for (ref in manifest.chunks) {
                    val data = store.get(ref.hash) ?: return null
                    // Double-check: size and content hash must match the manifest
                    if (data.size != ref.size) return null
                    if (Chunker.sha256Hex(data) != ref.hash) return null
                    fos.write(data)
                }
            }
        } catch (_: Exception) {
            out.delete()
            return null
        }
        return out
    }

    /** Verify an assembled file matches the manifest size (cheap final check). */
    fun verifySize(manifest: FileManifest, file: File): Boolean =
        file.exists() && file.length() == manifest.fileSize
}

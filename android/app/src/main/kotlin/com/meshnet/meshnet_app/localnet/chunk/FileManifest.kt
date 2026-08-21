package com.meshnet.meshnet_app.localnet.chunk

import java.util.Base64

/**
 * FileManifest - describes a shared file as an ordered list of content chunks.
 *
 * fileId is DETERMINISTIC: sha256 of (name, size, all chunk hashes). Two
 * devices sharing the same file derive the same id -> file-level dedup, and
 * a peer that already has the file needs zero transfers.
 *
 * Wire format ("LNMANIFEST", line-based, values base64 where free-form):
 *   LNMANIFEST 1
 *   id=<hex>
 *   name=<b64>
 *   size=<long>
 *   mime=<b64>
 *   chunkSize=<int>
 *   sender=<deviceId>
 *   created=<ms>
 *   chunks=<hash>:<size>,<hash>:<size>,...
 */
data class FileManifest(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val chunkSize: Int,
    val chunks: List<ChunkRef>,
    val createdAtMs: Long,
    val senderDeviceId: String,
) {

    data class ChunkRef(val hash: String, val size: Int)

    fun serialize(): String = buildString {
        append("LNMANIFEST 1\n")
        append("id=$fileId\n")
        append("name=${b64(fileName)}\n")
        append("size=$fileSize\n")
        append("mime=${b64(mimeType)}\n")
        append("chunkSize=$chunkSize\n")
        append("sender=${b64(senderDeviceId)}\n")
        append("created=$createdAtMs\n")
        append("chunks=${chunks.joinToString(",") { "${it.hash}:${it.size}" }}\n")
    }

    companion object {
        const val MAGIC = "LNMANIFEST"
        const val VERSION = 1

        /** Deterministic content-based file id. */
        fun computeFileId(fileName: String, fileSize: Long, chunkHashes: List<String>): String {
            val material = "LN1|$fileName|$fileSize|${chunkHashes.joinToString(",")}"
            return Chunker.sha256Hex(material.toByteArray(Charsets.UTF_8)).substring(0, 40)
        }

        fun fromChunks(
            fileName: String,
            mimeType: String,
            chunks: List<Chunker.Chunk>,
            chunkSize: Int,
            senderDeviceId: String,
            createdAtMs: Long,
        ): FileManifest {
            val refs = chunks.map { ChunkRef(it.hash, it.data.size) }
            return FileManifest(
                fileId = computeFileId(fileName, refs.sumOf { it.size }.toLong(), refs.map { it.hash }),
                fileName = fileName,
                fileSize = refs.sumOf { it.size }.toLong(),
                mimeType = mimeType,
                chunkSize = chunkSize,
                chunks = refs,
                createdAtMs = createdAtMs,
                senderDeviceId = senderDeviceId,
            )
        }

        fun parse(text: String): FileManifest? {
            val lines = text.trim().lines()
            if (lines.isEmpty() || !lines[0].startsWith("$MAGIC ")) return null
            val fields = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isBlank()) continue
                val idx = line.indexOf('=')
                if (idx <= 0) return null
                fields[line.substring(0, idx)] = line.substring(idx + 1)
            }
            val id = fields["id"] ?: return null
            val name = unb64(fields["name"]) ?: return null
            val size = fields["size"]?.toLongOrNull() ?: return null
            val mime = unb64(fields["mime"]) ?: ""
            val chunkSize = fields["chunkSize"]?.toIntOrNull() ?: return null
            val sender = unb64(fields["sender"]) ?: return null
            val created = fields["created"]?.toLongOrNull() ?: return null
            val chunksLine = fields["chunks"] ?: return null
            if (chunkSize !in Chunker.MIN_CHUNK_SIZE..Chunker.MAX_CHUNK_SIZE) return null
            if (chunksLine.isEmpty()) return null
            val chunks = ArrayList<ChunkRef>()
            for (part in chunksLine.split(",")) {
                val pieces = part.split(":")
                if (pieces.size != 2) return null
                val hash = pieces[0]
                val sz = pieces[1].toIntOrNull() ?: return null
                if (!ChunkStore.isValidHash(hash)) return null
                if (sz <= 0) return null
                chunks.add(ChunkRef(hash, sz))
            }
            // Manifest must be internally consistent
            if (chunks.sumOf { it.size }.toLong() != size) return null
            if (computeFileId(name, size, chunks.map { it.hash }) != id) return null
            return FileManifest(id, name, size, mime, chunkSize, chunks, created, sender)
        }

        private fun b64(s: String): String =
            Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

        private fun unb64(s: String?): String? {
            if (s == null) return null
            return try {
                String(Base64.getDecoder().decode(s), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }
}

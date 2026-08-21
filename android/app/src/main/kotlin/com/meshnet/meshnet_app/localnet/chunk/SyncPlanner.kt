package com.meshnet.meshnet_app.localnet.chunk

/**
 * SyncPlanner - incremental sync logic.
 *
 * Given a remote manifest and the local ChunkStore, decides exactly which
 * chunks are missing. This is what makes re-syncing a file cheap: chunks
 * already present (from a previous partial fetch, an older version of the
 * file, or any other file sharing the same content) are never transferred.
 */
object SyncPlanner {

    /** Missing chunk hashes in manifest order. */
    fun missingChunks(manifest: FileManifest, store: ChunkStore): List<String> =
        manifest.chunks.filter { !store.has(it.hash) }.map { it.hash }

    /** How many chunks of the manifest are already available locally. */
    fun haveCount(manifest: FileManifest, store: ChunkStore): Int =
        manifest.chunks.count { store.has(it.hash) }

    fun totalCount(manifest: FileManifest): Int = manifest.chunks.size

    /** True when every chunk is present locally and verified. */
    fun isComplete(manifest: FileManifest, store: ChunkStore): Boolean =
        manifest.chunks.all { store.has(it.hash) }

    /** Bytes still to transfer for this manifest. */
    fun remainingBytes(manifest: FileManifest, store: ChunkStore): Long =
        manifest.chunks.filter { !store.has(it.hash) }.sumOf { it.size }.toLong()
}

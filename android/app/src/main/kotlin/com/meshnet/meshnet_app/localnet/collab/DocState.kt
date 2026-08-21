package com.meshnet.meshnet_app.localnet.collab

import java.util.Base64

/**
 * DocState - collaborative text document with LAST-WRITER-WINS merging (Phase 3).
 *
 * HONEST DESIGN: this is NOT a CRDT. Each edit carries a monotonically
 * increasing revision. An incoming edit is accepted when:
 *   - its revision is higher, OR
 *   - the revision ties and its senderId sorts lexicographically higher.
 * This guarantees every device converges to the same final text without a
 * server — at the cost of possibly discarding concurrent edits made in the
 * same instant by different peers. For shared notes on a mesh that is an
 * acceptable trade; true CRDT merging is future work.
 *
 * Snapshot format ("LNDOC", line-based text):
 *   LNDOC <docId>
 *   T <title-b64>
 *   R <rev>
 *   E <updatedAtMs>|<lastEditorId>
 *   B <text-b64>
 */
class DocState(
    val docId: String,
    var title: String,
) {

    companion object {
        const val MAX_TEXT_BYTES = 256 * 1024
        const val MAGIC = "LNDOC"

        fun isValidDocId(id: String): Boolean =
            id.length in 1..32 && id.all { it.isLowerCase() || it.isDigit() || it == '-' }

        fun b64(text: String): String =
            Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

        fun unb64(data: String): String? = try {
            String(Base64.getDecoder().decode(data), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }

        /** Parse a snapshot produced by [serialize]. Returns null if malformed. */
        fun parse(text: String): DocState? {
            val lines = text.lines().filter { it.isNotBlank() }
            val header = lines.firstOrNull() ?: return null
            if (!header.startsWith("$MAGIC ")) return null
            val docId = header.removePrefix("$MAGIC ").trim()
            if (!isValidDocId(docId)) return null
            var title = ""
            var rev = 0
            var updatedAt = 0L
            var editor = ""
            var body = ""
            for (line in lines.drop(1)) {
                when {
                    line.startsWith("T ") -> title = unb64(line.removePrefix("T ")) ?: return null
                    line.startsWith("R ") -> rev = line.removePrefix("R ").trim().toIntOrNull() ?: return null
                    line.startsWith("E ") -> {
                        val parts = line.removePrefix("E ").split("|")
                        if (parts.size != 2) return null
                        updatedAt = parts[0].toLongOrNull() ?: return null
                        editor = parts[1]
                    }
                    line.startsWith("B ") -> body = unb64(line.removePrefix("B ")) ?: return null
                }
            }
            val doc = DocState(docId, title)
            doc.rev = rev
            doc.updatedAtMs = updatedAt
            doc.lastEditorId = editor
            doc.text = body
            return doc
        }
    }

    /** Monotonic revision; bumped on every accepted edit. */
    var rev: Int = 0
        private set

    var text: String = ""
        private set

    var updatedAtMs: Long = 0
        private set

    var lastEditorId: String = ""
        private set

    /**
     * Apply a remote edit under LWW rules.
     * Returns true if this edit won (state changed).
     */
    @Synchronized
    fun applyEdit(incomingRev: Int, senderId: String, newText: String, atMs: Long): Boolean {
        if (newText.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return false
        val wins = incomingRev > rev || (incomingRev == rev && senderId > lastEditorId)
        if (!wins) return false
        rev = incomingRev
        text = newText
        updatedAtMs = atMs
        lastEditorId = senderId
        return true
    }

    /**
     * Local edit: caller passes the next revision (usually [rev] + 1).
     * Returns the new revision, or -1 if rejected (too large / stale rev).
     */
    @Synchronized
    fun editLocal(nextRev: Int, editorId: String, newText: String, atMs: Long): Int {
        if (nextRev <= rev) return -1
        if (newText.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return -1
        rev = nextRev
        text = newText
        updatedAtMs = atMs
        lastEditorId = editorId
        return rev
    }

    fun serialize(): String = buildString {
        append(MAGIC).append(' ').append(docId).append('\n')
        append("T ").append(b64(title)).append('\n')
        append("R ").append(rev).append('\n')
        append("E ").append(updatedAtMs).append('|').append(lastEditorId).append('\n')
        append("B ").append(b64(text)).append('\n')
    }
}

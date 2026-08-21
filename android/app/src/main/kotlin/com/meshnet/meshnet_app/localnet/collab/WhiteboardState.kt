package com.meshnet.meshnet_app.localnet.collab

/**
 * WhiteboardState - shared drawing canvas state for one room (Phase 3).
 *
 * A board is an ordered list of strokes. Every stroke has a unique id, so
 * re-delivered frames (mesh flooding duplicates) are applied exactly once.
 *
 * Wire/snapshot format ("LNBOARD", line-based text):
 *   LNBOARD <roomId>
 *   S <strokeId>|<authorId>|<colorArgb>|<width>|<createdAtMs>|x1,y1;x2,y2;...
 *
 * HONEST LIMITS:
 *   - MAX_STROKES caps memory; when exceeded the OLDEST stroke is dropped
 *     (peers that still hold it will simply render it until their cap hits).
 *   - Coordinates are floats rounded to 0.1 px — fine for sketching,
 *     not for pixel-perfect vector work.
 */
class WhiteboardState(val roomId: String) {

    companion object {
        const val MAX_STROKES = 2000
        const val MAX_POINTS_PER_STROKE = 512
        const val MAGIC = "LNBOARD"

        fun isValidRoomId(id: String): Boolean =
            id.length in 1..32 && id.all { it.isLowerCase() || it.isDigit() || it == '-' }

        /** Parse a snapshot produced by [serialize]. Returns null if malformed. */
        fun parse(text: String): WhiteboardState? {
            val lines = text.lines().filter { it.isNotBlank() }
            val header = lines.firstOrNull() ?: return null
            if (!header.startsWith("$MAGIC ")) return null
            val roomId = header.removePrefix("$MAGIC ").trim()
            if (!isValidRoomId(roomId)) return null
            val board = WhiteboardState(roomId)
            for (line in lines.drop(1)) {
                parseStrokeLine(line)?.let { board.put(it) }
            }
            return board
        }

        private fun parseStrokeLine(line: String): Stroke? {
            if (!line.startsWith("S ")) return null
            val parts = line.removePrefix("S ").split("|")
            if (parts.size != 6) return null
            val strokeId = parts[0]
            val authorId = parts[1]
            val color = parts[2].toIntOrNull() ?: return null
            val width = parts[3].toFloatOrNull() ?: return null
            val ts = parts[4].toLongOrNull() ?: return null
            val points = mutableListOf<Point>()
            for (p in parts[5].split(';')) {
                if (p.isBlank()) continue
                val xy = p.split(',')
                if (xy.size != 2) return null
                val x = xy[0].toFloatOrNull() ?: return null
                val y = xy[1].toFloatOrNull() ?: return null
                points.add(Point(x, y))
                if (points.size > MAX_POINTS_PER_STROKE) return null
            }
            if (points.isEmpty()) return null
            return Stroke(strokeId, authorId, color, width, ts, points)
        }
    }

    data class Point(val x: Float, val y: Float)

    data class Stroke(
        val strokeId: String,
        val authorId: String,
        val color: Int,
        val width: Float,
        val createdAtMs: Long,
        val points: List<Point>,
    )

    private val strokes = LinkedHashMap<String, Stroke>()

    val size: Int get() = strokes.size

    fun all(): List<Stroke> = synchronized(strokes) { strokes.values.toList() }

    /**
     * Apply a stroke. Returns true if it was NEW (false = duplicate).
     * Enforces the stroke cap by dropping the oldest entries.
     */
    fun put(stroke: Stroke): Boolean {
        if (!isValidRoomId(roomId)) return false
        synchronized(strokes) {
            if (strokes.containsKey(stroke.strokeId)) return false
            strokes[stroke.strokeId] = stroke
            while (strokes.size > MAX_STROKES) {
                strokes.remove(strokes.keys.first())
            }
            return true
        }
    }

    fun contains(strokeId: String): Boolean = synchronized(strokes) { strokes.containsKey(strokeId) }

    /** Remove every stroke (BOARD_CLEAR). Returns how many were removed. */
    fun clearAll(): Int = synchronized(strokes) {
        val n = strokes.size
        strokes.clear()
        n
    }

    fun serialize(): String = buildString {
        append(MAGIC).append(' ').append(roomId).append('\n')
        all().forEach { s ->
            append("S ").append(s.strokeId).append('|').append(s.authorId).append('|')
                .append(s.color).append('|').append(s.width).append('|').append(s.createdAtMs).append('|')
                .append(s.points.joinToString(";") { "${it.x},${it.y}" })
                .append('\n')
        }
    }
}

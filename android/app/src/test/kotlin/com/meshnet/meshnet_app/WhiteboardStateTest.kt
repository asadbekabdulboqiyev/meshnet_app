package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.collab.WhiteboardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * WhiteboardState testlari: stroke dedup, cap (eng eskisi tashlanadi),
 * clear va LNBOARD snapshot roundtrip.
 */
class WhiteboardStateTest {

    private fun stroke(
        id: String,
        author: String = "dev-a",
        points: List<WhiteboardState.Point> = listOf(
            WhiteboardState.Point(1f, 2f),
            WhiteboardState.Point(3f, 4f),
        ),
        ts: Long = 100,
    ) = WhiteboardState.Stroke(id, author, 0xFFFF0000.toInt(), 3f, ts, points)

    // ---------------- Id validation ----------------

    @Test
    fun roomIdValidation() {
        assertTrue(WhiteboardState.isValidRoomId("team-board"))
        assertTrue(WhiteboardState.isValidRoomId("a"))
        assertFalse(WhiteboardState.isValidRoomId(""))
        assertFalse(WhiteboardState.isValidRoomId("UPPER"))
        assertFalse(WhiteboardState.isValidRoomId("has space"))
        assertFalse(WhiteboardState.isValidRoomId("x".repeat(33)))
    }

    // ---------------- Stroke handling ----------------

    @Test
    fun putNewStrokeReturnsTrue() {
        val board = WhiteboardState("room")
        assertTrue(board.put(stroke("s1")))
        assertEquals(1, board.size)
    }

    @Test
    fun duplicateStrokeIgnored() {
        val board = WhiteboardState("room")
        assertTrue(board.put(stroke("s1")))
        assertFalse(board.put(stroke("s1")))
        assertEquals(1, board.size) // mesh flooding duplicates are free
    }

    @Test
    fun capDropsOldestStrokes() {
        val board = WhiteboardState("room")
        for (i in 1..WhiteboardState.MAX_STROKES + 50) {
            board.put(stroke("s$i", ts = i.toLong()))
        }
        assertEquals(WhiteboardState.MAX_STROKES, board.size)
        assertFalse(board.contains("s1")) // oldest dropped
        assertTrue(board.contains("s${WhiteboardState.MAX_STROKES + 50}")) // newest kept
    }

    @Test
    fun clearAllRemovesEverything() {
        val board = WhiteboardState("room")
        board.put(stroke("s1"))
        board.put(stroke("s2"))
        assertEquals(2, board.clearAll())
        assertEquals(0, board.size)
    }

    // ---------------- Snapshot roundtrip ----------------

    @Test
    fun serializeParseRoundtrip() {
        val board = WhiteboardState("design")
        board.put(stroke("s1", points = listOf(WhiteboardState.Point(0.5f, 10.25f))))
        board.put(stroke("s2", author = "dev-b", ts = 999))
        val parsed = WhiteboardState.parse(board.serialize())
        assertNotNull(parsed)
        assertEquals("design", parsed?.roomId)
        assertEquals(2, parsed?.size)
        val s1 = parsed?.all()?.first { it.strokeId == "s1" }
        assertEquals(0xFFFF0000.toInt(), s1?.color)
        assertEquals(3f, s1?.width)
        assertEquals(listOf(0.5f, 10.25f), s1?.points?.map { listOf(it.x, it.y) }?.first())
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(WhiteboardState.parse(""))
        assertNull(WhiteboardState.parse("NOTMAGIC room"))
        assertNull(WhiteboardState.parse("LNBOARD BAD ROOM!"))
        // Invalid STROKE lines are skipped tolerantly -> empty board survives
        assertEquals(0, WhiteboardState.parse("LNBOARD room\nS only|four|parts")?.size)
        assertEquals(0, WhiteboardState.parse("LNBOARD room\nS s1|a|red|3|100|1,2")?.size)
        assertEquals(0, WhiteboardState.parse("LNBOARD room\nS s1|a|5|3|100|1,2;oops")?.size)
    }

    @Test
    fun parseSkipsTooManyPointsLine() {
        val pts = (1..WhiteboardState.MAX_POINTS_PER_STROKE + 1).joinToString(";") { "$it,$it" }
        assertEquals(0, WhiteboardState.parse("LNBOARD room\nS s1|a|5|3|100|$pts")?.size)
    }

    @Test
    fun persistedFileRoundtripThroughDisk() {
        // Simulates CollabService persistence: write -> read -> parse
        val dir = TemporaryFolder()
        dir.create()
        val board = WhiteboardState("disk-room")
        board.put(stroke("s9"))
        val f = dir.newFile("board_disk-room.lnboard")
        f.writeText(board.serialize())
        val loaded = WhiteboardState.parse(f.readText())
        assertEquals("s9", loaded?.all()?.first()?.strokeId)
        dir.delete()
    }
}

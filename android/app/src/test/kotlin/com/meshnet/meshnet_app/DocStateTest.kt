package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.collab.DocState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DocState testlari: LWW merge qoidalasi (rev + senderId tie-break),
 * hajm limiti va LNDOC snapshot roundtrip.
 */
class DocStateTest {

    // ---------------- Local edits ----------------

    @Test
    fun editLocalBumpsRevision() {
        val doc = DocState("notes", "Team Notes")
        assertEquals(1, doc.editLocal(1, "dev-a", "hello", 100))
        assertEquals("hello", doc.text)
        assertEquals(1, doc.rev)
        assertEquals(-1, doc.editLocal(1, "dev-a", "stale", 200)) // rev must increase
        assertEquals("hello", doc.text)
    }

    @Test
    fun editLocalRejectsOversizedText() {
        val doc = DocState("notes", "n")
        val big = "x".repeat(DocState.MAX_TEXT_BYTES + 1)
        assertEquals(-1, doc.editLocal(1, "dev-a", big, 0))
    }

    // ---------------- LWW merge ----------------

    @Test
    fun higherRevisionWins() {
        val doc = DocState("notes", "n")
        doc.editLocal(1, "dev-a", "v1", 100)
        assertTrue(doc.applyEdit(5, "dev-b", "v5", 200))
        assertEquals("v5", doc.text)
        assertFalse(doc.applyEdit(3, "dev-c", "v3-old", 300)) // older rev loses
        assertEquals("v5", doc.text)
    }

    @Test
    fun revisionTieBrokenBySenderId() {
        val doc = DocState("notes", "n")
        doc.editLocal(1, "dev-mmm", "from-mmm", 100)
        // Same rev, lexicographically GREATER sender wins
        assertTrue(doc.applyEdit(1, "dev-zzz", "from-zzz", 200))
        assertEquals("from-zzz", doc.text)
        // Same rev, smaller sender loses
        assertFalse(doc.applyEdit(1, "dev-aaa", "from-aaa", 300))
        assertEquals("from-zzz", doc.text)
    }

    @Test
    fun applyEditRejectsOversizedText() {
        val doc = DocState("notes", "n")
        val big = "x".repeat(DocState.MAX_TEXT_BYTES + 1)
        assertFalse(doc.applyEdit(9, "dev-a", big, 0))
        assertEquals(0, doc.rev) // state untouched
    }

    @Test
    fun convergenceAllOrdersSameResult() {
        // Three devices apply the same two conflicting edits in different
        // orders; LWW rules must converge to one identical state.
        val editA = Triple(2, "dev-a", "text-A")
        val editB = Triple(2, "dev-f", "text-B")
        fun finalText(first: Triple<Int, String, String>, second: Triple<Int, String, String>): String {
            val d = DocState("notes", "n")
            d.editLocal(1, "seed", "seed", 0)
            d.applyEdit(first.first, first.second, first.third, 0)
            d.applyEdit(second.first, second.second, second.third, 0)
            return d.text
        }
        assertEquals(finalText(editA, editB), finalText(editB, editA))
        assertEquals("text-B", finalText(editA, editB)) // dev-f > dev-a
    }

    // ---------------- Snapshot roundtrip ----------------

    @Test
    fun serializeParseRoundtrip() {
        val doc = DocState("shared", "Rasmda yozuv")
        doc.editLocal(3, "dev-a", "Salom dunyo\nIkkinchi qator", 12345)
        val parsed = DocState.parse(doc.serialize())
        assertNotNull(parsed)
        assertEquals("shared", parsed?.docId)
        assertEquals("Rasmda yozuv", parsed?.title)
        assertEquals(3, parsed?.rev)
        assertEquals("Salom dunyo\nIkkinchi qator", parsed?.text)
        assertEquals("dev-a", parsed?.lastEditorId)
        assertEquals(12345L, parsed?.updatedAtMs)
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(DocState.parse(""))
        assertNull(DocState.parse("NOTMAGIC x"))
        assertNull(DocState.parse("LNDOC BAD_ID!"))
        assertNull(DocState.parse("LNDOC ok\nR notanumber"))
        assertNull(DocState.parse("LNDOC ok\nT !!!not-base64!!!"))
    }

    @Test
    fun unicodeSurvivesBase64() {
        val doc = DocState("d", "t")
        doc.editLocal(1, "a", "emoji 🚀 va o'zbekcha matn", 0)
        assertEquals("emoji 🚀 va o'zbekcha matn", DocState.parse(doc.serialize())?.text)
    }
}

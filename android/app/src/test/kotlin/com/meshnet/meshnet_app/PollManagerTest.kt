package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.collab.PollManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PollManager testlari: poll yaratish, bir ovozchi qoidasi (oxirgi ovoz
 * hisoblanadi), tally va LNPOLLS snapshot roundtrip.
 */
class PollManagerTest {

    private fun poll(id: String = "p1", options: List<String> = listOf("ha", "yoq")) =
        PollManager.Poll(id, "dev-a", 100, "Ketamizmi?", options)

    // ---------------- Creation ----------------

    @Test
    fun createPollAcceptsValid() {
        val pm = PollManager()
        assertTrue(pm.createPoll(poll()))
        assertEquals("Ketamizmi?", pm.getPoll("p1")?.question)
    }

    @Test
    fun duplicatePollIdRejected() {
        val pm = PollManager()
        assertTrue(pm.createPoll(poll()))
        assertFalse(pm.createPoll(poll())) // flooding duplicate is a no-op
        assertEquals(1, pm.all().size)
    }

    @Test
    fun badPollsRejected() {
        val pm = PollManager()
        assertFalse(pm.createPoll(poll(id = "BAD ID")))
        assertFalse(pm.createPoll(poll(options = listOf("only-one"))))
        assertFalse(pm.createPoll(poll(options = List(PollManager.MAX_OPTIONS + 1) { "o$it" })))
    }

    @Test
    fun pollCapDropsOldest() {
        val pm = PollManager()
        for (i in 1..PollManager.MAX_POLLS + 5) {
            pm.createPoll(poll(id = "poll-$i"))
        }
        assertEquals(PollManager.MAX_POLLS, pm.all().size)
        assertNull(pm.getPoll("poll-1")) // oldest evicted
        assertNotNull(pm.getPoll("poll-${PollManager.MAX_POLLS + 5}"))
    }

    // ---------------- Voting ----------------

    @Test
    fun voteRecordedAndTallied() {
        val pm = PollManager()
        pm.createPoll(poll())
        assertTrue(pm.recordVote("p1", "voter-1", 0))
        assertTrue(pm.recordVote("p1", "voter-2", 1))
        assertEquals(mapOf(0 to 1, 1 to 1), pm.tally("p1"))
        assertEquals(2, pm.voteCount("p1"))
    }

    @Test
    fun revoteReplacesPreviousChoice() {
        val pm = PollManager()
        pm.createPoll(poll())
        pm.recordVote("p1", "voter-1", 0)
        pm.recordVote("p1", "voter-1", 1) // changed mind — latest counts
        assertEquals(mapOf(1 to 1), pm.tally("p1"))
        assertEquals(1, pm.voteCount("p1"))
    }

    @Test
    fun invalidVotesRejected() {
        val pm = PollManager()
        pm.createPoll(poll())
        assertFalse(pm.recordVote("unknown", "v", 0)) // no such poll
        assertFalse(pm.recordVote("p1", "v", 5)) // out of range
        assertFalse(pm.recordVote("p1", "v", -1))
        assertFalse(pm.recordVote("p1", "", 0)) // blank voter
        assertEquals(0, pm.voteCount("p1"))
    }

    // ---------------- Snapshot roundtrip ----------------

    @Test
    fun serializeParseRoundtrip() {
        val pm = PollManager()
        pm.createPoll(poll())
        pm.createPoll(
            PollManager.Poll("lunch", "dev-b", 200, "Nima yeymiz?", listOf("osh", "lagmon", "shashlik")),
        )
        pm.recordVote("p1", "voter-1", 1)
        pm.recordVote("lunch", "voter-2", 2)
        pm.recordVote("lunch", "voter-3", 0)

        val parsed = PollManager.parse(pm.serialize())
        assertNotNull(parsed)
        assertEquals(2, parsed?.all()?.size)
        assertEquals(mapOf(1 to 1), parsed?.tally("p1"))
        assertEquals(mapOf(2 to 1, 0 to 1), parsed?.tally("lunch"))

        // mergeFrom path (used by CollabService restore)
        val live = PollManager()
        live.mergeFrom(parsed!!)
        assertEquals(2, live.all().size)
        assertEquals(2, live.voteCount("lunch"))
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(PollManager.parse(""))
        assertNull(PollManager.parse("WRONG"))
        assertNull(PollManager.parse("LNPOLLS\nP p1|dev|notats|q|a|b"))
        assertNull(PollManager.parse("LNPOLLS\nV p1|voter|NaN"))
    }
}

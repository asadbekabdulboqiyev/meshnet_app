package com.meshnet.meshnet_app.localnet.collab

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * PollManager - mesh-wide polls with one vote per device (Phase 3).
 *
 * A voter may CHANGE their vote; the latest vote per voter counts.
 * Tallying is fully local: every device that has seen all votes computes
 * the same result (mesh flooding guarantees eventual delivery while links
 * are up — partitions can produce divergent tallies until reconnection,
 * which is the honest cost of serverless voting).
 *
 * Snapshot format ("LNPOLLS", line-based text):
 *   LNPOLLS
 *   P <pollId>|<creatorId>|<createdAtMs>|<question-b64>|<opt1-b64>|...
 *   V <pollId>|<voterId>|<optionIndex>
 */
class PollManager {

    companion object {
        const val MAX_POLLS = 50
        const val MAX_OPTIONS = 10
        const val MAGIC = "LNPOLLS"

        fun isValidPollId(id: String): Boolean =
            id.length in 1..32 && id.all { it.isLowerCase() || it.isDigit() || it == '-' }

        private fun b64(s: String): String =
            Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

        private fun unb64(s: String): String? = try {
            String(Base64.getDecoder().decode(s), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }

        /** Parse a snapshot produced by [serialize]. Returns null if malformed. */
        fun parse(text: String): PollManager? {
            val lines = text.lines().filter { it.isNotBlank() }
            if (lines.firstOrNull() != MAGIC) return null
            val pm = PollManager()
            for (line in lines.drop(1)) {
                when {
                    line.startsWith("P ") -> pm.putPoll(parsePollLine(line.removePrefix("P ")) ?: return null)
                    line.startsWith("V ") -> {
                        val parts = line.removePrefix("V ").split("|")
                        if (parts.size != 3) return null
                        val idx = parts[2].toIntOrNull() ?: return null
                        pm.recordVote(parts[0], parts[1], idx)
                    }
                }
            }
            return pm
        }

        private fun parsePollLine(data: String): Poll? {
            val parts = data.split("|")
            if (parts.size < 5) return null
            val (pollId, creatorId, tsStr) = parts
            if (!isValidPollId(pollId)) return null
            val ts = tsStr.toLongOrNull() ?: return null
            val question = unb64(parts[3]) ?: return null
            val options = parts.drop(4).map { unb64(it) ?: return null }
            if (options.size !in 2..MAX_OPTIONS) return null
            return Poll(pollId, creatorId, ts, question, options)
        }
    }

    data class Poll(
        val pollId: String,
        val creatorId: String,
        val createdAtMs: Long,
        val question: String,
        val options: List<String>,
    )

    private val polls = LinkedHashMap<String, Poll>()
    private val votes = ConcurrentHashMap<String, MutableMap<String, Int>>() // pollId -> voterId -> optionIndex

    @Synchronized
    private fun putPoll(poll: Poll): Boolean {
        if (polls.containsKey(poll.pollId)) return false
        polls[poll.pollId] = poll
        // Cap: drop oldest poll beyond limit
        while (polls.size > MAX_POLLS) {
            val oldest = polls.keys.first()
            polls.remove(oldest)
            votes.remove(oldest)
        }
        return true
    }

    /** Register a new poll. Returns false for duplicate id or bad shape. */
    @Synchronized
    fun createPoll(poll: Poll): Boolean {
        if (!isValidPollId(poll.pollId)) return false
        if (poll.options.size !in 2..MAX_OPTIONS) return false
        return putPoll(poll)
    }

    fun getPoll(pollId: String): Poll? = polls[pollId]

    fun all(): List<Poll> = synchronized(polls) { polls.values.toList() }

    /**
     * Record/replace a vote. Returns true if accepted (poll exists,
     * option index valid). One vote per voter — later votes replace earlier.
     */
    fun recordVote(pollId: String, voterId: String, optionIndex: Int): Boolean {
        val poll = polls[pollId] ?: return false
        if (optionIndex !in poll.options.indices) return false
        if (voterId.isBlank() || voterId.length > 64) return false
        votes.getOrPut(pollId) { ConcurrentHashMap() }[voterId] = optionIndex
        return true
    }

    /** Votes for a poll: optionIndex -> count. */
    fun tally(pollId: String): Map<Int, Int> {
        val pollVotes = votes[pollId] ?: return emptyMap()
        return pollVotes.values.groupingBy { it }.eachCount()
    }

    /** Total ballots cast for a poll. */
    fun voteCount(pollId: String): Int = votes[pollId]?.size ?: 0

    /** Raw votes for a poll (voterId -> optionIndex); used for state restore. */
    fun votesFor(pollId: String): Map<String, Int> = votes[pollId]?.toMap() ?: emptyMap()

    /** Merge every poll and vote from [other] into this manager (state restore). */
    @Synchronized
    fun mergeFrom(other: PollManager) {
        other.all().forEach { createPoll(it) }
        other.all().forEach { p ->
            other.votesFor(p.pollId).forEach { (voter, idx) -> recordVote(p.pollId, voter, idx) }
        }
    }

    fun serialize(): String = buildString {
        append(MAGIC).append('\n')
        all().forEach { p ->
            append("P ").append(p.pollId).append('|').append(p.creatorId).append('|')
                .append(p.createdAtMs).append('|').append(b64(p.question))
            p.options.forEach { o -> append('|').append(b64(o)) }
            append('\n')
        }
        votes.forEach { (pollId, vs) ->
            vs.forEach { (voter, idx) ->
                append("V ").append(pollId).append('|').append(voter).append('|').append(idx).append('\n')
            }
        }
    }
}

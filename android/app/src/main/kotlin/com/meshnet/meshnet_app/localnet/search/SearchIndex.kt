package com.meshnet.meshnet_app.localnet.search

import com.meshnet.meshnet_app.localnet.rbac.Permission
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Mesh-wide distributed search index.
 * Each node maintains a local inverted index of content it knows about.
 * Queries can be local or distributed (flooded to peers).
 */
class SearchIndex(
    private val selfDeviceId: String,
    private val routingEngine: com.meshnet.meshnet_app.protocol.RoutingEngine,
    private val accessControl: com.meshnet.meshnet_app.localnet.rbac.AccessControl? = null,
    private val mainHandler: android.os.Handler = android.os.Handler(android.os.Looper.getMainLooper())
) {

    // term -> Set<DocumentRef>
    private val invertedIndex = ConcurrentHashMap<String, ConcurrentHashMap<String, DocumentRef>>()

    // docId -> Document
    private val documents = ConcurrentHashMap<String, Document>()

    // Pending queries: queryId -> (query, callback)
    private val pendingQueries = ConcurrentHashMap<String, PendingQuery>()

    // Seen query IDs for deduplication
    private val seenQueries = ConcurrentHashMap<String, Long>()

    companion object {
        // Message types for search frames
        const val SEARCH_QUERY = 0x73.toByte()
        const val SEARCH_RESULT = 0x74.toByte()
        const val SEARCH_INDEX_SYNC = 0x75.toByte()

        const val MAX_QUERY_TTL = 4
        const val QUERY_TIMEOUT_MS = 10000
        const val MAX_RESULTS_PER_NODE = 50
        const val INDEX_SYNC_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    }

    data class Document(
        val docId: String,
        val resourceType: String, // "file", "board", "doc", "poll", "app", "host"
        val resourceId: String,
        val ownerId: String,
        val title: String,
        val content: String, // searchable text content
        val tags: List<String> = emptyList(),
        val createdAtMs: Long = System.currentTimeMillis(),
        val updatedAtMs: Long = System.currentTimeMillis(),
        val metadata: Map<String, String> = emptyMap()
    ) {
        fun getSearchableText(): String {
            return "$title $content ${tags.joinToString(" ")}".lowercase()
        }
    }

    data class DocumentRef(
        val docId: String,
        val resourceType: String,
        val ownerId: String,
        val title: String,
        val snippet: String,
        val score: Double = 1.0,
        val metadata: Map<String, String> = emptyMap()
    )

    data class SearchQuery(
        val queryId: String,
        val requesterId: String,
        val terms: List<String>,
        val resourceTypes: Set<String> = emptySet(),
        val maxResults: Int = 20,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class SearchResult(
        val queryId: String,
        val responderId: String,
        val results: List<DocumentRef>,
        val totalHits: Int,
        val tookMs: Long
    )

    data class PendingQuery(
        val query: SearchQuery,
        val startTime: Long,
        val results: MutableList<DocumentRef> = mutableListOf(),
        val responders: MutableSet<String> = mutableSetOf()
    )

    // --- Indexing ---

    fun indexDocument(doc: Document) {
        documents[doc.docId] = doc
        val terms = tokenize(doc.getSearchableText())
        terms.forEach { term ->
            val ref = DocumentRef(
                docId = doc.docId,
                resourceType = doc.resourceType,
                ownerId = doc.ownerId,
                title = doc.title,
                snippet = generateSnippet(doc.content, terms),
                metadata = doc.metadata
            )
            invertedIndex.computeIfAbsent(term) { ConcurrentHashMap() }[doc.docId] = ref
        }
    }

    fun removeDocument(docId: String) {
        val doc = documents.remove(docId)
        if (doc != null) {
            val terms = tokenize(doc.getSearchableText())
            terms.forEach { term ->
                invertedIndex[term]?.remove(docId)
                if (invertedIndex[term]?.isEmpty() == true) invertedIndex.remove(term)
            }
        }
    }

    fun updateDocument(docId: String, newTitle: String? = null, newContent: String? = null, newTags: List<String>? = null) {
        val doc = documents[docId] ?: return
        removeDocument(docId)
        val updated = doc.copy(
            title = newTitle ?: doc.title,
            content = newContent ?: doc.content,
            tags = newTags ?: doc.tags,
            updatedAtMs = System.currentTimeMillis()
        )
        indexDocument(updated)
    }

    // --- Local search ---

    fun searchLocal(queryTerms: List<String>, resourceTypes: Set<String> = emptySet(), maxResults: Int = 20): List<DocumentRef> {
        val normalizedTerms = queryTerms.map { it.lowercase() }.filter { it.isNotBlank() }
        if (normalizedTerms.isEmpty()) return emptyList()

        val scored = mutableMapOf<String, Double>()
        val refs = mutableMapOf<String, DocumentRef>()

        normalizedTerms.forEach { term ->
            val matches = invertedIndex[term]?.values ?: emptySet()
            matches.forEach { ref ->
                if (resourceTypes.isNotEmpty() && ref.resourceType !in resourceTypes) return@forEach
                // Simple TF scoring: count term frequency
                val doc = documents[ref.docId]
                if (doc == null) return@forEach
                val tf = countTermFrequency(doc.getSearchableText(), term)
                val score = scored.getOrDefault(ref.docId, 0.0) + tf
                scored[ref.docId] = score
                if (!refs.containsKey(ref.docId) || ref.score > refs[ref.docId]!!.score) {
                    refs[ref.docId] = ref.copy(score = score)
                }
            }
        }

        return refs.values
            .sortedByDescending { it.score }
            .take(maxResults)
            .toList()
    }

    // --- Distributed search ---

    fun searchDistributed(
        terms: List<String>,
        resourceTypes: Set<String> = emptySet(),
        maxResults: Int = 20,
        onResult: (SearchResult) -> Unit
    ): String {
        val queryId = "qry_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val query = SearchQuery(
            queryId = queryId,
            requesterId = selfDeviceId,
            terms = terms.map { it.lowercase() }.filter { it.isNotBlank() },
            resourceTypes = resourceTypes,
            maxResults = maxResults
        )

        val pending = PendingQuery(query = query, startTime = System.currentTimeMillis())
        pendingQueries[queryId] = pending

        // Search locally first
        val localResults = searchLocal(query.terms, query.resourceTypes, query.maxResults)
        onResult(SearchResult(
            queryId = queryId,
            responderId = selfDeviceId,
            results = localResults,
            totalHits = localResults.size,
            tookMs = System.currentTimeMillis() - pending.startTime
        ))

        // Flood query to mesh
        broadcastQuery(query)

        // Timeout cleanup
        mainHandler.postDelayed({ cleanupQuery(queryId) }, QUERY_TIMEOUT_MS.toLong())

        return queryId
    }

    private fun cleanupQuery(queryId: String) {
        pendingQueries.remove(queryId)
    }

    private fun broadcastQuery(query: SearchQuery) {
        val payload = encodeQuery(query)
        val msgType = com.meshnet.meshnet_app.protocol.MessageType.fromCode(SEARCH_QUERY) ?: return
        val frame = com.meshnet.meshnet_app.protocol.MeshFrame(
            type = msgType,
            hopLimit = com.meshnet.meshnet_app.protocol.RoutingEngine.MAX_HOP,
            ttl = MAX_QUERY_TTL,
            encrypted = false,
            senderId = selfDeviceId,
            targetId = com.meshnet.meshnet_app.protocol.MeshFrame.BROADCAST,
            msgSeq = System.currentTimeMillis(),
            payload = payload,
            senderPublicKey = null
        )
        routingEngine.emitForSend(frame, null)
    }

    fun onSearchFrame(frame: com.meshnet.meshnet_app.protocol.MeshFrame) {
        when (frame.type.code) {
            SEARCH_QUERY -> handleQueryFrame(frame)
            SEARCH_RESULT -> handleResultFrame(frame)
            SEARCH_INDEX_SYNC -> handleIndexSyncFrame(frame)
        }
    }

    private fun handleQueryFrame(frame: com.meshnet.meshnet_app.protocol.MeshFrame) {
        val query = decodeQuery(frame.payload)
        if (query == null || query.requesterId == selfDeviceId) return

        // Deduplication
        val seen = seenQueries[query.queryId]
        val now = System.currentTimeMillis()
        if (seen != null && now - seen < QUERY_TIMEOUT_MS) return
        seenQueries[query.queryId] = now
        if (seenQueries.size > 1000) {
            seenQueries.entries.forEach { if (now - it.value > QUERY_TIMEOUT_MS) seenQueries.remove(it.key) }
        }

        // Execute local search
        val results = searchLocal(query.terms, query.resourceTypes, query.maxResults)

        // Send results back to requester
        val result = SearchResult(
            queryId = query.queryId,
            responderId = selfDeviceId,
            results = results,
            totalHits = results.size,
            tookMs = System.currentTimeMillis() - query.timestamp
        )
        sendResult(query.requesterId, result)
    }

    private fun handleResultFrame(frame: com.meshnet.meshnet_app.protocol.MeshFrame) {
        val result = decodeResult(frame.payload)
        if (result == null) return

        val pending = pendingQueries[result.queryId]
        if (pending == null) return

        // Filter results by access control if available
        val filtered = if (accessControl != null) {
            result.results.filter { ref ->
                accessControl.canAccess(pending.query.requesterId, ref.resourceType, ref.docId, Permission.SEARCH_QUERY)
            }
        } else {
            result.results
        }

        synchronized(pending) {
            pending.results.addAll(filtered)
            pending.responders.add(result.responderId)
        }
    }

    private fun handleIndexSyncFrame(frame: com.meshnet.meshnet_app.protocol.MeshFrame) {
        // Periodic index synchronization (optional, for bootstrap)
        val docs = decodeIndexSync(frame.payload)
        docs.forEach { (docId, doc) ->
            if (!documents.containsKey(docId)) {
                indexDocument(doc)
            }
        }
    }

    private fun sendResult(targetId: String, result: SearchResult) {
        val payload = encodeResult(result)
        val msgType = com.meshnet.meshnet_app.protocol.MessageType.fromCode(SEARCH_RESULT) ?: return
        val frame = com.meshnet.meshnet_app.protocol.MeshFrame(
            type = msgType,
            hopLimit = com.meshnet.meshnet_app.protocol.RoutingEngine.MAX_HOP,
            ttl = 4,
            encrypted = false,
            senderId = selfDeviceId,
            targetId = targetId,
            msgSeq = System.currentTimeMillis(),
            payload = payload,
            senderPublicKey = null
        )
        routingEngine.emitForSend(frame, null)
    }

    // --- Periodic tasks ---

    fun periodicSync() {
        // Broadcast index sync (lightweight - only new docs since last sync)
        // For now, skip to reduce bandwidth
    }

    fun periodicCleanup() {
        val now = System.currentTimeMillis()
        // Clean up timed-out queries
        pendingQueries.entries.forEach { if (now - it.value.startTime > QUERY_TIMEOUT_MS) pendingQueries.remove(it.key) }
        // Clean seen queries
        seenQueries.entries.forEach { if (now - it.value > QUERY_TIMEOUT_MS) seenQueries.remove(it.key) }
    }

    // --- Tokenization & scoring ---

    private fun tokenize(text: String): Set<String> {
        return text.split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.length >= 2 && it.length <= 50 }
            .toSet()
    }

    private fun countTermFrequency(text: String, term: String): Int {
        var count = 0
        var index = text.indexOf(term)
        while (index >= 0) {
            count++
            index = text.indexOf(term, index + term.length)
        }
        return count
    }

    private fun generateSnippet(content: String, terms: Set<String>): String {
        val lower = content.lowercase()
        var bestPos = 0
        var bestScore = 0
        terms.forEach { term ->
            var pos = lower.indexOf(term)
            while (pos >= 0) {
                val score = terms.count { lower.substring(pos, min(pos + 100, lower.length)).contains(it) }
                if (score > bestScore) {
                    bestScore = score
                    bestPos = max(0, pos - 50)
                }
                pos = lower.indexOf(term, pos + 1)
            }
        }
        val end = min(bestPos + 200, content.length)
        val snippet = content.substring(bestPos, end)
        return if (bestPos > 0) "...$snippet..." else snippet
    }

    // --- Encoding/decoding ---

    private fun encodeQuery(query: SearchQuery): ByteArray {
        val sb = StringBuilder()
        sb.append(query.queryId).append('|')
        sb.append(query.requesterId).append('|')
        sb.append(query.terms.joinToString(",")).append('|')
        sb.append(query.resourceTypes.joinToString(",")).append('|')
        sb.append(query.maxResults).append('|')
        sb.append(query.timestamp)
        return sb.toString().toByteArray()
    }

    private fun decodeQuery(payload: ByteArray): SearchQuery? {
        try {
            val parts = String(payload).split("|", limit = 6)
            if (parts.size < 6) return null
            return SearchQuery(
                queryId = parts[0],
                requesterId = parts[1],
                terms = parts[2].split(",").filter { it.isNotBlank() },
                resourceTypes = parts[3].split(",").filter { it.isNotBlank() }.toSet(),
                maxResults = parts[4].toIntOrNull() ?: 20,
                timestamp = parts[5].toLongOrNull() ?: System.currentTimeMillis()
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun encodeResult(result: SearchResult): ByteArray {
        val sb = StringBuilder()
        sb.append(result.queryId).append('|')
        sb.append(result.responderId).append('|')
        sb.append(result.tookMs).append('|')
        sb.append(result.totalHits).append('|')
        result.results.forEach { ref ->
            sb.append('\n').append(ref.docId).append('|')
            sb.append(ref.resourceType).append('|')
            sb.append(ref.ownerId).append('|')
            sb.append(ref.title.replace("\n", " ").replace("|", "\\|")).append('|')
            sb.append(ref.snippet.replace("\n", " ").replace("|", "\\|")).append('|')
            sb.append(ref.score).append('|')
            ref.metadata.forEach { (k, v) -> sb.append(k).append('=').append(v).append(';') }
        }
        return sb.toString().toByteArray()
    }

    private fun decodeResult(payload: ByteArray): SearchResult? {
        try {
            val lines = String(payload).split("\n")
            if (lines.isEmpty()) return null
            val header = lines[0].split("|", limit = 5)
            if (header.size < 5) return null
            val results = mutableListOf<DocumentRef>()
            lines.drop(1).forEach { line ->
                val parts = line.split("|", limit = 7)
                if (parts.size >= 6) {
                    val metadata = mutableMapOf<String, String>()
                    if (parts.size >= 7) {
                        parts[6].split(";").filter { it.isNotBlank() }.forEach { kv ->
                            kv.split("=", limit = 2).let { if (it.size == 2) metadata[it[0]] = it[1] }
                        }
                    }
                    results.add(DocumentRef(
                        docId = parts[0],
                        resourceType = parts[1],
                        ownerId = parts[2],
                        title = parts[3].replace("\\|", "|"),
                        snippet = parts[4].replace("\\|", "|"),
                        score = parts[5].toDoubleOrNull() ?: 1.0,
                        metadata = metadata
                    ))
                }
            }
            return SearchResult(
                queryId = header[0],
                responderId = header[1],
                results = results,
                totalHits = header[3].toIntOrNull() ?: results.size,
                tookMs = header[2].toLongOrNull() ?: 0
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun encodeIndexSync(docs: Map<String, Document>): ByteArray {
        val sb = StringBuilder()
        docs.forEach { (id, doc) ->
            sb.append(id).append('|')
            sb.append(doc.resourceType).append('|')
            sb.append(doc.ownerId).append('|')
            sb.append(doc.title.replace("|", "\\|")).append('|')
            sb.append(doc.content.replace("|", "\\|")).append('|')
            sb.append(doc.tags.joinToString(",")).append('|')
            sb.append(doc.createdAtMs).append('|')
            sb.append(doc.updatedAtMs).append('|')
            doc.metadata.forEach { (k, v) -> sb.append(k).append('=').append(v).append(';') }
            sb.append('\n')
        }
        return sb.toString().toByteArray()
    }

    private fun decodeIndexSync(payload: ByteArray): Map<String, Document> {
        val map = mutableMapOf<String, Document>()
        String(payload).lines().forEach { line ->
            val parts = line.split("|", limit = 9)
            if (parts.size >= 8) {
                val metadata = mutableMapOf<String, String>()
                if (parts.size >= 9) {
                    parts[8].split(";").filter { it.isNotBlank() }.forEach { kv ->
                        kv.split("=", limit = 2).let { if (it.size == 2) metadata[it[0]] = it[1] }
                    }
                }
                map[parts[0]] = Document(
                    docId = parts[0],
                    resourceType = parts[1],
                    resourceId = parts[2], // reuse ownerId as resourceId for compat
                    ownerId = parts[2],
                    title = parts[3].replace("\\|", "|"),
                    content = parts[4].replace("\\|", "|"),
                    tags = parts[5].split(",").filter { it.isNotBlank() },
                    createdAtMs = parts[6].toLongOrNull() ?: 0,
                    updatedAtMs = parts[7].toLongOrNull() ?: 0,
                    metadata = metadata
                )
            }
        }
        return map
    }

    // --- Stats ---

    fun getStats(): Map<String, Any> {
        return mapOf(
            "documents" to documents.size,
            "terms" to invertedIndex.size,
            "pendingQueries" to pendingQueries.size
        )
    }

    // --- Snapshot for persistence ---

    fun snapshot(): Map<String, Any> {
        return mapOf(
            "documents" to documents.mapValues { it.value },
            "invertedIndex" to invertedIndex.mapValues { (_, v) -> v.mapValues { it.value } }
        )
    }

    fun restore(snapshot: Map<String, Any>) {
        documents.clear()
        invertedIndex.clear()
        (snapshot["documents"] as? Map<String, Document>)?.forEach { documents[it.key] = it.value }
        (snapshot["invertedIndex"] as? Map<String, Map<String, DocumentRef>>)?.forEach { (term, refs) ->
            invertedIndex[term] = ConcurrentHashMap(refs)
        }
    }
}
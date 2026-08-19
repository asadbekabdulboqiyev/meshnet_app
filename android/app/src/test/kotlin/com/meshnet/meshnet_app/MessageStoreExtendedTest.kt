package com.meshnet.meshnet_app

import android.content.Context
import com.meshnet.meshnet_app.storage.MeshDatabase
import com.meshnet.meshnet_app.storage.MessageStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class MessageStoreExtendedTest {

    private lateinit var mockContext: Context
    private lateinit var store: MessageStore

    private val SENDER = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val TARGET = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
        mockContext = mock(Context::class.java)
        store = MessageStore(mockContext)
    }

    @After
    fun tearDown() {
        MeshDatabase.resetInstance()
    }

    @Test
    fun addIncoming_emptyMessage_works() {
        store.addIncoming(MessageStore.IncomingMessage("m1", SENDER, ""))
        assertEquals("", store.loadIncoming()[0].message)
    }

    @Test
    fun addIncoming_singleMessage_loadsOne() {
        store.addIncoming(MessageStore.IncomingMessage("m1", SENDER, "Hello"))
        assertEquals(1, store.loadIncoming().size)
    }

    @Test
    fun addIncoming_newestFirst() {
        store.addIncoming(MessageStore.IncomingMessage("m1", SENDER, "First"))
        store.addIncoming(MessageStore.IncomingMessage("m2", SENDER, "Second"))
        assertEquals("m2", store.loadIncoming()[0].messageId)
        assertEquals("m1", store.loadIncoming()[1].messageId)
    }

    @Test
    fun addIncoming_exactly200_allKept() {
        for (i in 1..200) {
            store.addIncoming(MessageStore.IncomingMessage("m$i", SENDER, "msg$i"))
        }
        assertEquals(200, store.loadIncoming().size)
    }

    @Test
    fun addIncoming_201_oldestDropped() {
        for (i in 1..201) {
            store.addIncoming(MessageStore.IncomingMessage("m$i", SENDER, "msg$i"))
        }
        val msgs = store.loadIncoming()
        assertEquals(200, msgs.size)
        assertEquals("m201", msgs[0].messageId)
        assertTrue(msgs.none { it.messageId == "m1" })
    }

    @Test
    fun addIncoming_preservesReceivedAtTimestamp() {
        val ts = 1234567890000L
        store.addIncoming(MessageStore.IncomingMessage("m1", SENDER, "Hi", ts))
        assertEquals(ts, store.loadIncoming()[0].receivedAtMs)
    }

    @Test
    fun addIncoming_defaultTimestamp_isRecent() {
        val before = System.currentTimeMillis()
        store.addIncoming(MessageStore.IncomingMessage("m1", SENDER, "Hi"))
        val after = System.currentTimeMillis()
        val ts = store.loadIncoming()[0].receivedAtMs
        assertTrue(ts >= before - 100)
        assertTrue(ts <= after + 100)
    }

    @Test
    fun addIncoming_specialCharacters_preserved() {
        val text = "<>&\"'\\/\n\t"
        store.addIncoming(MessageStore.IncomingMessage("m1", SENDER, text))
        assertEquals(text, store.loadIncoming()[0].message)
    }

    @Test
    fun addIncoming_unicode_preserved() {
        val text = "Yangi yil muborak! O'zbekiston"
        store.addIncoming(MessageStore.IncomingMessage("m1", SENDER, text))
        assertEquals(text, store.loadIncoming()[0].message)
    }

    @Test
    fun addIncoming_largeMessage_preserved() {
        val text = "X".repeat(50_000)
        store.addIncoming(MessageStore.IncomingMessage("m1", SENDER, text))
        assertEquals(text, store.loadIncoming()[0].message)
    }

    @Test
    fun loadIncoming_emptyStore_returnsEmptyList() {
        assertTrue(store.loadIncoming().isEmpty())
    }

    @Test
    fun saveOutbox_emptyList_clears() {
        store.saveOutbox(listOf(MessageStore.OutboxMessage("m1", TARGET, "f1")))
        store.saveOutbox(emptyList())
        assertTrue(store.loadOutbox().isEmpty())
    }

    @Test
    fun saveOutbox_multipleMessages_allLoaded() {
        val msgs = (1..10).map { MessageStore.OutboxMessage("m$it", TARGET, "f$it") }
        store.saveOutbox(msgs)
        assertEquals(10, store.loadOutbox().size)
    }

    @Test
    fun saveOutbox_overwritesPrevious() {
        store.saveOutbox(listOf(MessageStore.OutboxMessage("old", TARGET, "old")))
        store.saveOutbox(listOf(MessageStore.OutboxMessage("new", TARGET, "new")))
        val loaded = store.loadOutbox()
        assertEquals(1, loaded.size)
        assertEquals("new", loaded[0].messageId)
    }

    @Test
    fun outboxMessage_defaultRetryCount_isZero() {
        val msg = MessageStore.OutboxMessage("m1", TARGET, "frame")
        assertEquals(0, msg.retryCount)
    }

    @Test
    fun outboxMessage_withRetryCount() {
        val msg = MessageStore.OutboxMessage("m1", TARGET, "frame", 1000L, 3)
        assertEquals(3, msg.retryCount)
    }

    @Test
    fun outboxMessage_copyPreservesOriginal() {
        val original = MessageStore.OutboxMessage("m1", TARGET, "frame", 1000L, 2)
        val copy = original.copy(retryCount = 5)
        assertEquals(5, copy.retryCount)
        assertEquals("m1", copy.messageId)
        assertEquals(TARGET, copy.targetDeviceId)
    }

    @Test
    fun incomingMessage_copyWorks() {
        val original = MessageStore.IncomingMessage("m1", SENDER, "Hi", 1000L)
        val copy = original.copy(message = "Bye")
        assertEquals("Bye", copy.message)
        assertEquals("m1", copy.messageId)
        assertEquals(SENDER, copy.fromDeviceId)
        assertEquals(1000L, copy.receivedAtMs)
    }

    @Test
    fun outboxMessage_defaultCreatedAt_isReasonable() {
        val before = System.currentTimeMillis()
        val msg = MessageStore.OutboxMessage("m1", TARGET, "frame")
        val after = System.currentTimeMillis()
        assertTrue(msg.createdAtMs >= before - 100)
        assertTrue(msg.createdAtMs <= after + 100)
    }

    @Test
    fun addIncoming_300Messages_only200Kept() {
        for (i in 1..300) {
            store.addIncoming(MessageStore.IncomingMessage("m$i", SENDER, "msg$i"))
        }
        val msgs = store.loadIncoming()
        assertEquals(200, msgs.size)
        assertEquals("m300", msgs[0].messageId)
        assertEquals("m101", msgs.last().messageId)
    }

    @Test
    fun outboxMessage_emptyFrame_works() {
        store.saveOutbox(listOf(MessageStore.OutboxMessage("m1", TARGET, "")))
        assertEquals("", store.loadOutbox()[0].encodedFrame)
    }

    @Test
    fun incomingMessage_fromDifferentSenders() {
        val sender2 = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        store.addIncoming(MessageStore.IncomingMessage("m1", SENDER, "from A"))
        store.addIncoming(MessageStore.IncomingMessage("m2", sender2, "from C"))
        assertEquals(2, store.loadIncoming().size)
    }

    @Test
    fun concurrentAddIncoming_noException() {
        val threads = (1..10).map { i ->
            Thread {
                store.addIncoming(MessageStore.IncomingMessage("m$i", SENDER, "msg$i"))
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(2000) }
    }

    @Test
    fun saveOutbox_loadOutbox_100Messages() {
        val msgs = (1..100).map { MessageStore.OutboxMessage("m$it", TARGET, "frame-$it") }
        store.saveOutbox(msgs)
        val loaded = store.loadOutbox()
        assertEquals(100, loaded.size)
        for (i in 1..100) {
            assertEquals("m$i", loaded[i - 1].messageId)
        }
    }
}

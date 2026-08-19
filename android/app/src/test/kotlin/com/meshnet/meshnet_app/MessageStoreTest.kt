package com.meshnet.meshnet_app

import android.content.Context
import android.content.SharedPreferences
import com.meshnet.meshnet_app.storage.MessageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/**
 * MessageStore testlari: addIncoming, loadIncoming, saveOutbox, loadOutbox,
 * 200-message cap, persistence.
 */
class MessageStoreTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var storage: MutableMap<String, String?>
    private lateinit var store: MessageStore

    private val SENDER = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val TARGET = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)
        storage = mutableMapOf()

        `when`(mockContext.getSharedPreferences("meshnet_messages", Context.MODE_PRIVATE))
            .thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)

        `when`(mockEditor.putString(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<Any>(0).toString()
            val value = invocation.getArgument<Any>(1)?.toString()
            storage[key] = value
            mockEditor
        }

        `when`(mockEditor.remove(any())).thenAnswer { invocation ->
            val key = invocation.getArgument<Any>(0).toString()
            storage.remove(key)
            mockEditor
        }

        `when`(mockPrefs.getString(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<Any>(0).toString()
            val defValue = invocation.getArgument<Any>(1)
            storage[key] ?: defValue
        }

        store = MessageStore(mockContext)
    }

    // =================== loadIncoming ===================

    @Test
    fun loadIncoming_returnsEmptyListInitially() {
        val messages = store.loadIncoming()
        assertTrue(messages.isEmpty())
    }

    // =================== addIncoming ===================

    @Test
    fun addIncoming_addsMessageToInbox() {
        val msg = MessageStore.IncomingMessage("msg-1", SENDER, "Salom!")
        store.addIncoming(msg)
        val messages = store.loadIncoming()
        assertEquals(1, messages.size)
        assertEquals("msg-1", messages[0].messageId)
        assertEquals(SENDER, messages[0].fromDeviceId)
        assertEquals("Salom!", messages[0].message)
    }

    @Test
    fun addIncoming_newMessageGoesToTop() {
        store.addIncoming(MessageStore.IncomingMessage("msg-1", SENDER, "Birinchi"))
        store.addIncoming(MessageStore.IncomingMessage("msg-2", SENDER, "Ikkinchi"))
        val messages = store.loadIncoming()
        assertEquals(2, messages.size)
        assertEquals("msg-2", messages[0].messageId)
        assertEquals("msg-1", messages[1].messageId)
    }

    @Test
    fun addIncoming_enforces200MessageCap() {
        for (i in 1..210) {
            store.addIncoming(MessageStore.IncomingMessage("msg-$i", SENDER, "Xabar $i"))
        }
        val messages = store.loadIncoming()
        assertEquals(200, messages.size)
        // Yangi xabarlar saqlanishi kerak (201..210)
        assertEquals("msg-210", messages[0].messageId)
        // Eski xabarlar tashlab ketilgan (1..10)
        assertTrue(messages.none { it.messageId == "msg-1" })
    }

    @Test
    fun addIncoming_exactly200Messages_allSaved() {
        for (i in 1..200) {
            store.addIncoming(MessageStore.IncomingMessage("msg-$i", SENDER, "Xabar $i"))
        }
        val messages = store.loadIncoming()
        assertEquals(200, messages.size)
    }

    @Test
    fun addIncoming_201stMessage_removesOldest() {
        for (i in 1..201) {
            store.addIncoming(MessageStore.IncomingMessage("msg-$i", SENDER, "Xabar $i"))
        }
        val messages = store.loadIncoming()
        assertEquals(200, messages.size)
        assertEquals("msg-201", messages[0].messageId)
        assertTrue(messages.none { it.messageId == "msg-1" })
        assertEquals("msg-2", messages.last().messageId)
    }

    @Test
    fun addIncoming_preservesReceivedAtMs() {
        val ts = 1700000000000L
        val msg = MessageStore.IncomingMessage("msg-1", SENDER, "Salom", ts)
        store.addIncoming(msg)
        val loaded = store.loadIncoming()
        assertEquals(ts, loaded[0].receivedAtMs)
    }

    @Test
    fun addIncoming_defaultReceivedAtMs_isReasonable() {
        val before = System.currentTimeMillis()
        store.addIncoming(MessageStore.IncomingMessage("msg-1", SENDER, "Salom"))
        val after = System.currentTimeMillis()
        val ts = store.loadIncoming()[0].receivedAtMs
        assertTrue(ts >= before - 1000)
        assertTrue(ts <= after + 1000)
    }

    @Test
    fun addIncoming_emptyMessage_works() {
        store.addIncoming(MessageStore.IncomingMessage("msg-1", SENDER, ""))
        val messages = store.loadIncoming()
        assertEquals(1, messages.size)
        assertEquals("", messages[0].message)
    }

    @Test
    fun addIncoming_longMessage_works() {
        val longText = "X".repeat(10000)
        store.addIncoming(MessageStore.IncomingMessage("msg-1", SENDER, longText))
        assertEquals(longText, store.loadIncoming()[0].message)
    }

    @Test
    fun addIncoming_specialCharacters_works() {
        val text = "O'zbekiston & maxsus belgilar <tag> \"quote\" \\slash"
        store.addIncoming(MessageStore.IncomingMessage("msg-1", SENDER, text))
        assertEquals(text, store.loadIncoming()[0].message)
    }

    @Test
    fun addIncoming_unicode_works() {
        val text = "Salom dunyo! Yangi yil muborak"
        store.addIncoming(MessageStore.IncomingMessage("msg-1", SENDER, text))
        assertEquals(text, store.loadIncoming()[0].message)
    }

    // =================== IncomingMessage data class ===================

    @Test
    fun incomingMessage_defaultReceivedAtMs_isReasonable() {
        val before = System.currentTimeMillis()
        val msg = MessageStore.IncomingMessage("msg-1", SENDER, "Salom")
        val after = System.currentTimeMillis()
        assertTrue(msg.receivedAtMs >= before - 1000)
        assertTrue(msg.receivedAtMs <= after + 1000)
    }

    @Test
    fun incomingMessage_copyWorks() {
        val original = MessageStore.IncomingMessage("m1", SENDER, "Hi", 1000L)
        val copy = original.copy(message = "Hello")
        assertEquals("Hello", copy.message)
        assertEquals("m1", copy.messageId)
        assertEquals(SENDER, copy.fromDeviceId)
        assertEquals(1000L, copy.receivedAtMs)
    }

    // =================== saveOutbox / loadOutbox ===================

    @Test
    fun loadOutbox_returnsEmptyListInitially() {
        assertTrue(store.loadOutbox().isEmpty())
    }

    @Test
    fun saveOutbox_loadOutbox_roundtrip() {
        val outboxMessages = listOf(
            MessageStore.OutboxMessage("msg-1", TARGET, "base64frame1"),
            MessageStore.OutboxMessage("msg-2", TARGET, "base64frame2"),
        )
        store.saveOutbox(outboxMessages)
        val loaded = store.loadOutbox()
        assertEquals(2, loaded.size)
        assertEquals("msg-1", loaded[0].messageId)
        assertEquals(TARGET, loaded[0].targetDeviceId)
        assertEquals("base64frame1", loaded[0].encodedFrame)
        assertEquals("msg-2", loaded[1].messageId)
    }

    @Test
    fun saveOutbox_overwritesExisting() {
        store.saveOutbox(listOf(
            MessageStore.OutboxMessage("old", TARGET, "old-frame"),
        ))
        store.saveOutbox(listOf(
            MessageStore.OutboxMessage("new", TARGET, "new-frame"),
        ))
        val loaded = store.loadOutbox()
        assertEquals(1, loaded.size)
        assertEquals("new", loaded[0].messageId)
    }

    @Test
    fun saveOutbox_emptyList_clearsOutbox() {
        store.saveOutbox(listOf(
            MessageStore.OutboxMessage("msg-1", TARGET, "frame"),
        ))
        assertTrue(store.loadOutbox().isNotEmpty())
        store.saveOutbox(emptyList())
        assertTrue(store.loadOutbox().isEmpty())
    }

    @Test
    fun saveOutbox_manyMessages() {
        val messages = (1..100).map {
            MessageStore.OutboxMessage("msg-$it", TARGET, "frame-$it")
        }
        store.saveOutbox(messages)
        val loaded = store.loadOutbox()
        assertEquals(100, loaded.size)
        assertEquals("msg-1", loaded[0].messageId)
        assertEquals("msg-100", loaded[99].messageId)
    }

    // =================== OutboxMessage data class ===================

    @Test
    fun outboxMessage_defaultRetryCount_isZero() {
        val msg = MessageStore.OutboxMessage("m1", TARGET, "frame")
        assertEquals(0, msg.retryCount)
    }

    @Test
    fun outboxMessage_defaultCreatedAtMs_isReasonable() {
        val before = System.currentTimeMillis()
        val msg = MessageStore.OutboxMessage("m1", TARGET, "frame")
        val after = System.currentTimeMillis()
        assertTrue(msg.createdAtMs >= before - 1000)
        assertTrue(msg.createdAtMs <= after + 1000)
    }

    @Test
    fun outboxMessage_withRetryCount() {
        val msg = MessageStore.OutboxMessage("m1", TARGET, "frame", 1000L, 5)
        assertEquals(5, msg.retryCount)
        assertEquals(1000L, msg.createdAtMs)
    }

    @Test
    fun outboxMessage_copyWorks() {
        val original = MessageStore.OutboxMessage("m1", TARGET, "frame", 1000L, 2)
        val copy = original.copy(retryCount = 3, targetDeviceId = "new-target")
        assertEquals(3, copy.retryCount)
        assertEquals("new-target", copy.targetDeviceId)
        assertEquals("m1", copy.messageId)
    }

    // =================== Persistence ===================

    @Test
    fun addIncoming_persistsToStorage() {
        store.addIncoming(MessageStore.IncomingMessage("msg-1", SENDER, "Salom"))
        assertTrue(storage.containsKey("inbox_json"))
        assertNotNull(storage["inbox_json"])
    }

    @Test
    fun saveOutbox_persistsToStorage() {
        store.saveOutbox(listOf(
            MessageStore.OutboxMessage("msg-1", TARGET, "frame"),
        ))
        assertTrue(storage.containsKey("outbox_json"))
        assertNotNull(storage["outbox_json"])
    }

    @Test
    fun emptyStringMessage_works() {
        store.addIncoming(MessageStore.IncomingMessage("msg-1", SENDER, ""))
        assertEquals("", store.loadIncoming()[0].message)
    }

    @Test
    fun emptyEncodedFrame_works() {
        store.saveOutbox(listOf(
            MessageStore.OutboxMessage("m1", TARGET, ""),
        ))
        assertEquals("", store.loadOutbox()[0].encodedFrame)
    }

    private fun assertNotNull(value: Any?) {
        org.junit.Assert.assertNotNull(value)
    }
}

package com.osori.server.talk

import com.osori.proto.v1.Envelope
import com.osori.server.db.TalkRepository
import com.osori.server.session.ClientSession
import com.osori.server.testutil.FakeClientSession
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class TalkOutboxFlusherTest {

    @Test
    fun `flush returns 0 when outbox is empty`() = runBlocking {
        val repository = FakeOutboxStore(entries = emptyList())
        val flusher = TalkOutboxFlusher(repository)
        val session = FakeClientSession().apply { userId = "alice" }

        val result = flusher.flush(session, "alice")

        assertEquals(0, result)
        assertEquals(0, session.sentFrames.size)
        assertEquals(0, repository.deliveredIds.size)
    }

    @Test
    fun `flush delivers all entries and marks each delivered`() = runBlocking {
        val entries = listOf(
            outboxEntry(101L, "alice", "bob", "one"),
            outboxEntry(102L, "alice", "bob", "two"),
            outboxEntry(103L, "alice", "bob", "three"),
        )
        val repository = FakeOutboxStore(entries = entries)
        val flusher = TalkOutboxFlusher(repository)
        val session = FakeClientSession().apply { userId = "bob" }

        val result = flusher.flush(session, "bob")

        assertEquals(3, result)
        assertEquals(3, session.sentFrames.size)
        session.sentFrames.forEach { frame ->
            assertEquals(FrameType.BINARY, frame.frameType)
            val envelope = Envelope.parseFrom((frame as Frame.Binary).readBytes())
            assertEquals(Envelope.PayloadCase.TALK_RECEIVED, envelope.payloadCase)
        }
        assertEquals(listOf(101L, 102L, 103L), repository.deliveredIds)
    }

    @Test
    fun `flush continues when one delivery fails`() = runBlocking {
        val entries = listOf(
            outboxEntry(201L, "alice", "bob", "first"),
            outboxEntry(202L, "alice", "bob", "second"),
            outboxEntry(203L, "alice", "bob", "third"),
        )
        val repository = FakeOutboxStore(entries = entries)
        val flusher = TalkOutboxFlusher(repository)
        val session = ThrowingClientSession(failOnSendIndex = 2).apply { userId = "bob" }

        val result = flusher.flush(session, "bob")

        assertEquals(2, result)
        assertEquals(2, session.sentFrames.size)
        assertEquals(listOf(201L, 203L), repository.deliveredIds)
    }

    private fun outboxEntry(
        id: Long,
        fromUser: String,
        toUser: String,
        text: String,
    ): TalkRepository.OutboxEntry =
        TalkRepository.OutboxEntry(
            id = id,
            message = TalkRepository.MessageRow(
                messageId = UUID.randomUUID(),
                fromUser = fromUser,
                toUser = toUser,
                text = text,
                serverTs = Instant.parse("2026-04-13T00:00:00Z"),
            ),
        )

    private class FakeOutboxStore(
        private val entries: List<TalkRepository.OutboxEntry>,
    ) : OutboxStore {
        val deliveredIds = mutableListOf<Long>()

        override fun fetchOutbox(toUser: String, limit: Int): List<TalkRepository.OutboxEntry> =
            entries.take(limit)

        override fun markDelivered(outboxId: Long) {
            deliveredIds += outboxId
        }
    }

    private class ThrowingClientSession(
        private val failOnSendIndex: Int,
        override val id: Long = 20_000L,
    ) : ClientSession {
        override var userId: String? = null
        override var lastSeenAtMillis: Long = System.currentTimeMillis()
        val sentFrames = mutableListOf<Frame>()
        private var sendCount = 0

        override suspend fun send(frame: Frame) {
            sendCount += 1
            if (sendCount == failOnSendIndex) {
                throw IllegalStateException("send failure at index=$failOnSendIndex")
            }
            sentFrames += frame
        }

        override suspend fun close(reason: CloseReason) = Unit
    }
}

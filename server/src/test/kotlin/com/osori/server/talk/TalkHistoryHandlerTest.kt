package com.osori.server.talk

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.TalkHistoryRequest
import com.osori.server.db.TalkRepository
import com.osori.server.testutil.FakeClientSession
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TalkHistoryHandlerTest {

    @Test
    fun `미인증 세션 요청은 빈 응답만 반환`() = runBlocking {
        val handler = TalkHistoryHandler(talkRepository = null)
        val session = FakeClientSession() // userId = null

        val req = TalkHistoryRequest.newBuilder().setSinceTs(0).setLimit(10).build()
        handler.handleHistory(session, sequence = 7L, req)

        assertEquals(1, session.sentFrames.size)
        val sent = Envelope.parseFrom((session.sentFrames.single() as Frame.Binary).readBytes())
        assertEquals(7L, sent.sequence)
        assertEquals(Envelope.PayloadCase.TALK_HISTORY_RES, sent.payloadCase)
        assertEquals(0, sent.talkHistoryRes.talksCount)
        assertFalse(sent.talkHistoryRes.hasMore)
    }

    @Test
    fun `repository 가 null 이면 (메모리 모드) 빈 응답`() = runBlocking {
        val handler = TalkHistoryHandler(talkRepository = null)
        val session = FakeClientSession().apply { userId = "alice" }

        val req = TalkHistoryRequest.newBuilder().setSinceTs(0).setLimit(10).build()
        handler.handleHistory(session, sequence = 1L, req)

        val sent = Envelope.parseFrom((session.sentFrames.single() as Frame.Binary).readBytes())
        assertEquals(0, sent.talkHistoryRes.talksCount)
        assertFalse(sent.talkHistoryRes.hasMore)
    }

    @Test
    fun `limit 범위 내에서 has_more false`() = runBlocking {
        val repo = FakeMessageRepo(
            rows = listOf(
                row("alice", "bob", "hi-1", 100),
                row("alice", "bob", "hi-2", 200),
            ),
        )
        val handler = TalkHistoryHandler(store = repo)
        val session = FakeClientSession().apply { userId = "bob" }

        val req = TalkHistoryRequest.newBuilder().setSinceTs(0).setLimit(10).build()
        handler.handleHistory(session, sequence = 3L, req)

        val sent = Envelope.parseFrom((session.sentFrames.single() as Frame.Binary).readBytes())
        assertEquals(2, sent.talkHistoryRes.talksCount)
        assertFalse(sent.talkHistoryRes.hasMore)
        assertEquals("hi-1", sent.talkHistoryRes.talksList[0].text)
        assertEquals("hi-2", sent.talkHistoryRes.talksList[1].text)
        // 서버가 limit + 1 = 11 건 요청했는지 확인
        assertEquals(11, repo.lastLimit)
        assertEquals(0L, repo.lastSince)
        assertEquals("bob", repo.lastUserId)
    }

    @Test
    fun `limit 초과 시 잘리고 has_more true`() = runBlocking {
        val repo = FakeMessageRepo(
            rows = listOf(
                row("a", "bob", "m1", 1),
                row("a", "bob", "m2", 2),
                row("a", "bob", "m3", 3),
            ),
        )
        val handler = TalkHistoryHandler(store = repo)
        val session = FakeClientSession().apply { userId = "bob" }

        val req = TalkHistoryRequest.newBuilder().setSinceTs(0).setLimit(2).build()
        handler.handleHistory(session, sequence = 9L, req)

        val sent = Envelope.parseFrom((session.sentFrames.single() as Frame.Binary).readBytes())
        assertEquals(2, sent.talkHistoryRes.talksCount)
        assertTrue(sent.talkHistoryRes.hasMore)
        // 초과분은 가장 오래된 쪽(m1)을 잘라내고 최신 2건(m2, m3)이 남아야 함 —
        // 과거엔 take(limit) 라 반대로 최신 메시지가 잘려나가는 버그였음.
        assertEquals("m2", sent.talkHistoryRes.talksList[0].text)
        assertEquals("m3", sent.talkHistoryRes.talksList[1].text)
        assertEquals(3, repo.lastLimit) // limit + 1
    }

    @Test
    fun `limit 0 이면 DEFAULT_LIMIT 적용`() = runBlocking {
        val repo = FakeMessageRepo(rows = emptyList())
        val handler = TalkHistoryHandler(store = repo)
        val session = FakeClientSession().apply { userId = "bob" }

        val req = TalkHistoryRequest.newBuilder().setSinceTs(0).setLimit(0).build()
        handler.handleHistory(session, sequence = 1L, req)

        assertEquals(TalkHistoryHandler.DEFAULT_LIMIT + 1, repo.lastLimit)
    }

    @Test
    fun `limit 이 MAX_LIMIT 넘으면 MAX_LIMIT 로 캡`() = runBlocking {
        val repo = FakeMessageRepo(rows = emptyList())
        val handler = TalkHistoryHandler(store = repo)
        val session = FakeClientSession().apply { userId = "bob" }

        val req = TalkHistoryRequest.newBuilder().setSinceTs(0).setLimit(9_999).build()
        handler.handleHistory(session, sequence = 1L, req)

        assertEquals(TalkHistoryHandler.MAX_LIMIT + 1, repo.lastLimit)
    }

    private fun row(from: String, to: String, text: String, tsMillis: Long) =
        TalkRepository.MessageRow(
            messageId = UUID.randomUUID(),
            fromUser = from,
            toUser = to,
            text = text,
            serverTs = Instant.ofEpochMilli(tsMillis),
        )

    /** TalkHistoryHandler 테스트용 HistoryStore 페이크. 인자 기록 + 고정 응답. */
    private class FakeMessageRepo(
        private val rows: List<TalkRepository.MessageRow>,
    ) : HistoryStore {
        var lastUserId: String? = null
        var lastSince: Long = -1
        var lastLimit: Int = -1

        override fun fetchRecentForUser(
            userId: String,
            sinceMillis: Long,
            limit: Int,
        ): List<TalkRepository.MessageRow> {
            lastUserId = userId
            lastSince = sinceMillis
            lastLimit = limit
            return rows.take(limit)
        }
    }
}

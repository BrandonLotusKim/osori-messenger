package com.osori.server.talk

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.TalkSearchRequest
import com.osori.proto.v1.TalkSearchResponse
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

class TalkSearchHandlerTest {

    private fun fakeStore(rows: List<TalkRepository.MessageRow> = emptyList(), throwOnSearch: Boolean = false) =
        object : TalkSearchStore {
            override fun searchMessages(userId: String, keyword: String, sinceMillis: Long, untilMillis: Long, limit: Int): List<TalkRepository.MessageRow> {
                if (throwOnSearch) error("DB down")
                return rows.take(limit)
            }
        }

    private fun row(msg: String, from: String = "bob", to: String = "alice", ts: Long = 1_000L) = TalkRepository.MessageRow(
        messageId = UUID.randomUUID(),
        fromUser = from,
        toUser = to,
        text = msg,
        serverTs = Instant.ofEpochMilli(ts),
    )

    private fun session(userId: String?): FakeClientSession {
        val s = FakeClientSession()
        s.userId = userId
        return s
    }

    private fun decodeRes(session: FakeClientSession): TalkSearchResponse {
        assertEquals(1, session.sentFrames.size)
        val frame = session.sentFrames.single() as Frame.Binary
        val env = Envelope.parseFrom(frame.readBytes())
        assertTrue(env.hasTalkSearchRes())
        return env.talkSearchRes
    }

    @Test
    fun `미인증 세션 SERVER_ERROR + clientReqId echo`() = runBlocking {
        val h = TalkSearchHandler(fakeStore())
        val s = session(null)
        h.handle(s, 1L, TalkSearchRequest.newBuilder().setClientReqId("r1").setKeyword("hi").build())
        val res = decodeRes(s)
        assertEquals(TalkSearchResponse.Result.SERVER_ERROR, res.result)
        assertEquals("r1", res.clientReqId)
    }

    @Test
    fun `빈 keyword — OK 빈 결과`() = runBlocking {
        val h = TalkSearchHandler(fakeStore(listOf(row("hello"))))
        val s = session("alice")
        h.handle(s, 1L, TalkSearchRequest.newBuilder().setClientReqId("r2").setKeyword("").build())
        val res = decodeRes(s)
        assertEquals(TalkSearchResponse.Result.OK, res.result)
        assertEquals(0, res.matchedCount)
        assertEquals(0, res.talksCount)
    }

    @Test
    fun `2건 반환 + hasMore false`() = runBlocking {
        val h = TalkSearchHandler(fakeStore(listOf(row("hello world"), row("hello there"))))
        val s = session("alice")
        h.handle(s, 1L, TalkSearchRequest.newBuilder().setClientReqId("r3").setKeyword("hello").setLimit(10).build())
        val res = decodeRes(s)
        assertEquals(TalkSearchResponse.Result.OK, res.result)
        assertEquals(2, res.matchedCount)
        assertFalse(res.hasMore)
        assertEquals("hello world", res.talksList[0].text)
    }

    @Test
    fun `limit+1 조회로 hasMore 판정`() = runBlocking {
        val rows = (1..6).map { row("msg $it") }
        val h = TalkSearchHandler(fakeStore(rows))
        val s = session("alice")
        // limit = 5 → store 는 limit+1 = 6 반환하고 handler 가 5만 take + hasMore=true
        h.handle(s, 1L, TalkSearchRequest.newBuilder().setClientReqId("r4").setKeyword("msg").setLimit(5).build())
        val res = decodeRes(s)
        assertEquals(5, res.matchedCount)
        assertTrue(res.hasMore)
    }

    @Test
    fun `limit 0 이면 DEFAULT_LIMIT 50 적용`() = runBlocking {
        val rows = (1..60).map { row("m$it") }
        val h = TalkSearchHandler(fakeStore(rows))
        val s = session("alice")
        h.handle(s, 1L, TalkSearchRequest.newBuilder().setClientReqId("r5").setKeyword("m").setLimit(0).build())
        val res = decodeRes(s)
        assertEquals(50, res.matchedCount)
        assertTrue(res.hasMore) // store 가 51 반환
    }

    @Test
    fun `Store 예외 SERVER_ERROR`() = runBlocking {
        val h = TalkSearchHandler(fakeStore(throwOnSearch = true))
        val s = session("alice")
        h.handle(s, 1L, TalkSearchRequest.newBuilder().setClientReqId("r6").setKeyword("x").build())
        val res = decodeRes(s)
        assertEquals(TalkSearchResponse.Result.SERVER_ERROR, res.result)
        assertEquals("r6", res.clientReqId)
    }

    @Test
    fun `null Repo 생성자 — EMPTY_STORE 로 빈 결과 OK`() = runBlocking {
        val h = TalkSearchHandler(null as TalkRepository?)
        val s = session("alice")
        h.handle(s, 1L, TalkSearchRequest.newBuilder().setClientReqId("r7").setKeyword("anything").build())
        val res = decodeRes(s)
        assertEquals(TalkSearchResponse.Result.OK, res.result)
        assertEquals(0, res.matchedCount)
    }
}

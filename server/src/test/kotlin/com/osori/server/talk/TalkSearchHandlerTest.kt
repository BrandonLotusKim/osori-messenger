package com.osori.server.talk

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.TalkSearchRequest
import com.osori.proto.v1.TalkSearchResponse
import com.osori.server.db.TalkRepository
import com.osori.server.db.TalkRoomRepository
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

    private fun roomStore(rows: List<TalkRoomRepository.RoomSearchRow>, legacy: List<TalkRepository.MessageRow> = emptyList()) =
        object : TalkSearchStore {
            override fun searchMessages(userId: String, keyword: String, sinceMillis: Long, untilMillis: Long, limit: Int) =
                legacy.take(limit)
            override val supportsRooms: Boolean get() = true
            override fun searchRoomTalks(userId: String, keyword: String, sinceMillis: Long, untilMillis: Long, limit: Int) =
                rows.take(limit)
        }

    private fun roomRow(text: String, roomId: UUID = UUID.randomUUID()) = TalkRoomRepository.RoomSearchRow(
        serverTalkId = UUID.randomUUID(),
        roomId = roomId,
        roomName = "alice, bob",
        nameIsCustom = false,
        memberIds = listOf("alice", "bob"),
        fromUserId = "bob",
        text = text,
        serverTs = Instant.ofEpochMilli(2_000L),
    )

    @Test
    fun `방 검색 지원 시 hits 에 방 ID 포함, 옛 1대1 결과는 섞지 않음`() = runBlocking {
        val roomId = UUID.randomUUID()
        val h = TalkSearchHandler(roomStore(listOf(roomRow("배포 일정", roomId)), legacy = listOf(row("배포 옛날"))))
        val s = session("alice")
        h.handle(s, 1L, TalkSearchRequest.newBuilder().setClientReqId("r8").setKeyword("배포").build())
        val res = decodeRes(s)
        assertEquals(TalkSearchResponse.Result.OK, res.result)
        assertEquals(1, res.matchedCount)
        assertEquals(0, res.talksCount)
        val hit = res.hitsList.single()
        assertEquals(roomId.toString(), hit.roomId)
        assertEquals("배포 일정", hit.text)
        assertEquals("bob", hit.fromUserId)
        assertEquals(listOf("alice", "bob"), hit.memberIdsList)
        assertEquals(2_000L, hit.serverTs)
    }

    @Test
    fun `방 검색 limit+1 로 hasMore 판정`() = runBlocking {
        val h = TalkSearchHandler(roomStore((1..4).map { roomRow("m$it") }))
        val s = session("alice")
        h.handle(s, 1L, TalkSearchRequest.newBuilder().setClientReqId("r9").setKeyword("m").setLimit(3).build())
        val res = decodeRes(s)
        assertEquals(3, res.hitsCount)
        assertTrue(res.hasMore)
    }
}

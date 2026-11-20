package com.osori.server.talk

import com.osori.proto.v1.*
import com.osori.server.db.Database
import com.osori.server.db.TalkRoomRepository
import com.osori.server.testutil.FakeClientSession
import io.ktor.websocket.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * TalkRoomHistoryHandler 단위 테스트 — 권한 분기 / has_more / DB 실패 등.
 */
class TalkRoomHistoryHandlerTest {

    private fun newRepoStub(): TalkRoomRepository = spyk(TalkRoomRepository(mockk<Database>(relaxed = true)))

    @Test
    fun `미인증 세션 SERVER_ERROR`() = runBlocking {
        val handler = TalkRoomHistoryHandler(talkRoomRepository = null)
        val session = FakeClientSession()
        val req = TalkRoomHistoryRequest.newBuilder()
            .setRoomId(UUID.randomUUID().toString())
            .setClientReqId("r").setLimit(10).build()
        handler.handleRoomHistory(session, sequence = 1L, req)
        val env = parseAck(session)
        assertEquals(TalkRoomHistoryResponse.Status.SERVER_ERROR, env.talkRoomHistoryRes.status)
    }

    @Test
    fun `talkRoomRepository null SERVER_ERROR`() = runBlocking {
        val handler = TalkRoomHistoryHandler(talkRoomRepository = null)
        val session = FakeClientSession().apply { userId = "alice" }
        val req = TalkRoomHistoryRequest.newBuilder()
            .setRoomId(UUID.randomUUID().toString())
            .setClientReqId("r").setLimit(10).build()
        handler.handleRoomHistory(session, sequence = 1L, req)
        val env = parseAck(session)
        assertEquals(TalkRoomHistoryResponse.Status.SERVER_ERROR, env.talkRoomHistoryRes.status)
    }

    @Test
    fun `잘못된 UUID 형식 ROOM_NOT_FOUND`() = runBlocking {
        val repo = newRepoStub()
        val handler = TalkRoomHistoryHandler(talkRoomRepository = repo)
        val session = FakeClientSession().apply { userId = "alice" }
        val req = TalkRoomHistoryRequest.newBuilder()
            .setRoomId("not-a-uuid")
            .setClientReqId("r").setLimit(10).build()
        handler.handleRoomHistory(session, sequence = 1L, req)
        val env = parseAck(session)
        assertEquals(TalkRoomHistoryResponse.Status.ROOM_NOT_FOUND, env.talkRoomHistoryRes.status)
    }

    @Test
    fun `비멤버 NOT_A_MEMBER`() = runBlocking {
        val repo = newRepoStub()
        val roomId = UUID.randomUUID()
        every { repo.isMember(eq(roomId), eq("charlie")) } returns false
        val handler = TalkRoomHistoryHandler(talkRoomRepository = repo)
        val session = FakeClientSession().apply { userId = "charlie" }
        val req = TalkRoomHistoryRequest.newBuilder()
            .setRoomId(roomId.toString())
            .setClientReqId("r").setLimit(10).build()
        handler.handleRoomHistory(session, sequence = 1L, req)
        val env = parseAck(session)
        assertEquals(TalkRoomHistoryResponse.Status.NOT_A_MEMBER, env.talkRoomHistoryRes.status)
    }

    @Test
    fun `멤버 + 메시지 3건 OK + has_more false`() = runBlocking {
        val repo = newRepoStub()
        val roomId = UUID.randomUUID()
        every { repo.isMember(eq(roomId), eq("alice")) } returns true
        val rows = (1..3).map {
            TalkRoomRepository.TalkRoomRow(
                serverTalkId = UUID.randomUUID(),
                roomId = roomId,
                fromUserId = "alice",
                text = "msg-$it",
                serverTs = Instant.ofEpochMilli(it.toLong() * 1000),
            )
        }
        every { repo.fetchRoomHistory(eq(roomId), any(), eq(51)) } returns rows
        val handler = TalkRoomHistoryHandler(talkRoomRepository = repo)
        val session = FakeClientSession().apply { userId = "alice" }
        val req = TalkRoomHistoryRequest.newBuilder()
            .setRoomId(roomId.toString())
            .setClientReqId("r").setLimit(50).build()
        handler.handleRoomHistory(session, sequence = 1L, req)

        val env = parseAck(session)
        assertEquals(TalkRoomHistoryResponse.Status.OK, env.talkRoomHistoryRes.status)
        assertEquals(3, env.talkRoomHistoryRes.talksCount)
        assertEquals(roomId.toString(), env.talkRoomHistoryRes.roomId)
        assertEquals("msg-1", env.talkRoomHistoryRes.talksList[0].text)
        assertFalse(env.talkRoomHistoryRes.hasMore)
    }

    @Test
    fun `limit 50 + 51건 returned → has_more true`() = runBlocking {
        val repo = newRepoStub()
        val roomId = UUID.randomUUID()
        every { repo.isMember(eq(roomId), eq("alice")) } returns true
        val rows = (1..51).map {
            TalkRoomRepository.TalkRoomRow(
                serverTalkId = UUID.randomUUID(),
                roomId = roomId,
                fromUserId = "alice",
                text = "msg-$it",
                serverTs = Instant.ofEpochMilli(it.toLong() * 1000),
            )
        }
        every { repo.fetchRoomHistory(eq(roomId), any(), eq(51)) } returns rows
        val handler = TalkRoomHistoryHandler(talkRoomRepository = repo)
        val session = FakeClientSession().apply { userId = "alice" }
        val req = TalkRoomHistoryRequest.newBuilder()
            .setRoomId(roomId.toString())
            .setClientReqId("r").setLimit(50).build()
        handler.handleRoomHistory(session, sequence = 1L, req)

        val env = parseAck(session)
        assertEquals(TalkRoomHistoryResponse.Status.OK, env.talkRoomHistoryRes.status)
        assertEquals(50, env.talkRoomHistoryRes.talksCount)
        assertTrue(env.talkRoomHistoryRes.hasMore)
    }

    @Test
    fun `limit 0 → DEFAULT_LIMIT(50)`() = runBlocking {
        val repo = newRepoStub()
        val roomId = UUID.randomUUID()
        every { repo.isMember(eq(roomId), any()) } returns true
        every { repo.fetchRoomHistory(eq(roomId), any(), eq(51)) } returns emptyList()
        val handler = TalkRoomHistoryHandler(talkRoomRepository = repo)
        val session = FakeClientSession().apply { userId = "alice" }
        val req = TalkRoomHistoryRequest.newBuilder().setRoomId(roomId.toString()).setClientReqId("r").setLimit(0).build()
        handler.handleRoomHistory(session, sequence = 1L, req)
        val env = parseAck(session)
        assertEquals(TalkRoomHistoryResponse.Status.OK, env.talkRoomHistoryRes.status)
        // 호출 인자가 limit+1 = 51 이어야
        io.mockk.verify { repo.fetchRoomHistory(eq(roomId), any(), eq(51)) }
    }

    @Test
    fun `limit 500 초과 시 MAX_LIMIT(500) 으로 cap`() = runBlocking {
        val repo = newRepoStub()
        val roomId = UUID.randomUUID()
        every { repo.isMember(eq(roomId), any()) } returns true
        every { repo.fetchRoomHistory(eq(roomId), any(), eq(501)) } returns emptyList()
        val handler = TalkRoomHistoryHandler(talkRoomRepository = repo)
        val session = FakeClientSession().apply { userId = "alice" }
        val req = TalkRoomHistoryRequest.newBuilder().setRoomId(roomId.toString()).setClientReqId("r").setLimit(9999).build()
        handler.handleRoomHistory(session, sequence = 1L, req)
        // limit 가 500 으로 cap → fetch 호출 인자가 501
        io.mockk.verify { repo.fetchRoomHistory(eq(roomId), any(), eq(501)) }
    }

    @Test
    fun `fetch 실패 시 SERVER_ERROR`() = runBlocking {
        val repo = newRepoStub()
        val roomId = UUID.randomUUID()
        every { repo.isMember(eq(roomId), any()) } returns true
        every { repo.fetchRoomHistory(eq(roomId), any(), any()) } throws RuntimeException("DB 죽음")
        val handler = TalkRoomHistoryHandler(talkRoomRepository = repo)
        val session = FakeClientSession().apply { userId = "alice" }
        val req = TalkRoomHistoryRequest.newBuilder().setRoomId(roomId.toString()).setClientReqId("r").setLimit(50).build()
        handler.handleRoomHistory(session, sequence = 1L, req)
        val env = parseAck(session)
        assertEquals(TalkRoomHistoryResponse.Status.SERVER_ERROR, env.talkRoomHistoryRes.status)
    }

    private fun parseAck(session: FakeClientSession): Envelope {
        assertTrue(session.sentFrames.isNotEmpty())
        return Envelope.parseFrom((session.sentFrames.last() as Frame.Binary).readBytes())
    }
}

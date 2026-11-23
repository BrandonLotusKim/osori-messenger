package com.osori.server.message

import com.osori.proto.v1.*
import com.osori.server.db.Database
import com.osori.server.db.MessageRepository
import com.osori.server.session.SessionManager
import com.osori.server.testutil.FakeClientSession
import io.ktor.websocket.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.justRun
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MessageHandler 단위 테스트 (mockk 로 MessageRepository / Database stub).
 * 통합 / smoke 와 별개로 분기별 로직(권한/오프라인/중복 recipient/NOT_OWNER 등)을 빠르게 검증.
 */
class MessageHandlerTest {

    private fun newRepoStub(): MessageRepository {
        val mockDb = mockk<Database>(relaxed = true)
        return spyk(MessageRepository(mockDb))
    }

    // ============== handleSend ==============

    @Test
    fun `미인증 세션 send INVALID_REQUEST`() = runBlocking {
        val handler = MessageHandler(repo = null, sessions = SessionManager())
        val session = FakeClientSession()
        val req = MessageSend.newBuilder().setClientMessageId("c1").setTitle("t").addRecipientIds("bob").build()
        handler.handleSend(session, sequence = 1L, req)
        val res = parseAck(session)
        assertEquals(MessageSendAck.Result.INVALID_REQUEST, res.messageSendAck.result)
    }

    @Test
    fun `빈 recipient INVALID_REQUEST`() = runBlocking {
        val handler = MessageHandler(repo = null, sessions = SessionManager())
        val session = FakeClientSession().apply { userId = "alice" }
        val req = MessageSend.newBuilder().setClientMessageId("c1").setTitle("t").build()
        handler.handleSend(session, sequence = 1L, req)
        val res = parseAck(session)
        assertEquals(MessageSendAck.Result.INVALID_REQUEST, res.messageSendAck.result)
    }

    @Test
    fun `빈 title INVALID_REQUEST`() = runBlocking {
        val handler = MessageHandler(repo = null, sessions = SessionManager())
        val session = FakeClientSession().apply { userId = "alice" }
        val req = MessageSend.newBuilder().setClientMessageId("c1").addRecipientIds("bob").build()
        handler.handleSend(session, sequence = 1L, req)
        val res = parseAck(session)
        assertEquals(MessageSendAck.Result.INVALID_REQUEST, res.messageSendAck.result)
    }

    @Test
    fun `repo null SERVER_ERROR`() = runBlocking {
        val handler = MessageHandler(repo = null, sessions = SessionManager())
        val session = FakeClientSession().apply { userId = "alice" }
        val req = MessageSend.newBuilder().setClientMessageId("c1").setTitle("hi").addRecipientIds("bob").build()
        handler.handleSend(session, sequence = 1L, req)
        val res = parseAck(session)
        assertEquals(MessageSendAck.Result.SERVER_ERROR, res.messageSendAck.result)
    }

    @Test
    fun `남이 올린 업로드를 첨부하면 INVALID_REQUEST, 본인 업로드는 통과`() = runBlocking {
        val repo = newRepoStub()
        justRun { repo.saveMemo(any(), any(), any(), any(), any(), any(), any()) }
        every { repo.markDelivered(any(), any()) } returns 1
        val owned = mapOf("up-alice" to "alice", "up-bob" to "bob")
        val handler = MessageHandler(repo = repo, sessions = SessionManager(), attachmentUsableBy = { id, user -> owned[id] == user })
        val sender = FakeClientSession().apply { userId = "alice" }

        handler.handleSend(sender, 1L, MessageSend.newBuilder().setClientMessageId("c1").setTitle("t")
            .addRecipientIds("carol").setAttachmentUploadId("up-bob").build())
        assertEquals(MessageSendAck.Result.INVALID_REQUEST, parseAck(sender).messageSendAck.result)
        verify(exactly = 0) { repo.saveMemo(any(), any(), any(), any(), any(), any(), any()) }

        sender.sentFrames.clear()
        handler.handleSend(sender, 2L, MessageSend.newBuilder().setClientMessageId("c2").setTitle("t")
            .addRecipientIds("carol").addRecipientIds("dave").setAttachmentUploadId("up-alice").build())
        assertEquals(MessageSendAck.Result.OK, parseAck(sender).messageSendAck.result)
        verify(exactly = 1) { repo.saveMemo(any(), "alice", any(), any(), any(), any(), "up-alice") }
    }

    @Test
    fun `정상 발송 + 온라인 수신자 push + markDelivered + ack OK`() = runBlocking {
        val repo = newRepoStub()
        justRun { repo.saveMemo(any(), any(), any(), any(), any(), any(), any()) }
        every { repo.markDelivered(any(), any()) } returns 1

        val sessions = SessionManager()
        val sender = FakeClientSession().apply { userId = "alice" }
        val recipient = FakeClientSession().apply { userId = "bob" }
        sessions.bind("alice", sender)
        sessions.bind("bob", recipient)

        val handler = MessageHandler(repo = repo, sessions = sessions)
        val req = MessageSend.newBuilder()
            .setClientMessageId("c-1").setTitle("회의").setBody("3시 회의 잡혔습니다")
            .addRecipientIds("bob").build()
        handler.handleSend(sender, sequence = 5L, req)

        val ack = parseAck(sender)
        assertEquals(5L, ack.sequence)
        assertEquals(MessageSendAck.Result.OK, ack.messageSendAck.result)
        assertEquals("c-1", ack.messageSendAck.clientMessageId)
        assertTrue(ack.messageSendAck.serverMessageId.isNotEmpty())
        assertEquals(1, ack.messageSendAck.recipientResultsCount)
        assertEquals("bob", ack.messageSendAck.recipientResultsList[0].recipientId)
        assertTrue(ack.messageSendAck.recipientResultsList[0].delivered)

        // 수신자에게 push
        assertEquals(1, recipient.sentFrames.size)
        val push = Envelope.parseFrom((recipient.sentFrames.single() as Frame.Binary).readBytes())
        assertEquals(Envelope.PayloadCase.MESSAGE_RECEIVED, push.payloadCase)
        assertEquals("alice", push.messageReceived.fromUserId)
        assertEquals("회의", push.messageReceived.title)
        assertEquals("bob", push.messageReceived.recipientId)

        // 저장 + 배달 표시
        verify(exactly = 1) { repo.saveMemo(any(), eq("alice"), eq(listOf("bob")), eq("회의"), eq("3시 회의 잡혔습니다"), any(), isNull()) }
        verify(exactly = 1) { repo.markDelivered(any(), eq("bob")) }
    }

    @Test
    fun `오프라인 수신자는 markDelivered 호출 안 함`() = runBlocking {
        val repo = newRepoStub()
        justRun { repo.saveMemo(any(), any(), any(), any(), any(), any(), any()) }

        val sessions = SessionManager()
        val sender = FakeClientSession().apply { userId = "alice" }
        sessions.bind("alice", sender)
        // bob 은 바인드 안 함 (오프라인)

        val handler = MessageHandler(repo = repo, sessions = sessions)
        val req = MessageSend.newBuilder().setClientMessageId("c").setTitle("hi").addRecipientIds("bob").build()
        handler.handleSend(sender, sequence = 1L, req)

        val ack = parseAck(sender)
        assertEquals(MessageSendAck.Result.OK, ack.messageSendAck.result)
        // 오프라인이라 markDelivered 호출 X — 다음 로그인 시 flushUndelivered 가 처리
        verify(exactly = 0) { repo.markDelivered(any(), any()) }
        verify(exactly = 1) { repo.saveMemo(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `중복 recipient distinct 처리`() = runBlocking {
        val repo = newRepoStub()
        justRun { repo.saveMemo(any(), any(), any(), any(), any(), any(), any()) }
        val sessions = SessionManager()
        val sender = FakeClientSession().apply { userId = "alice" }
        sessions.bind("alice", sender)
        val handler = MessageHandler(repo = repo, sessions = sessions)
        val req = MessageSend.newBuilder()
            .setClientMessageId("c").setTitle("hi")
            .addRecipientIds("bob").addRecipientIds("bob").addRecipientIds("carol")
            .build()
        handler.handleSend(sender, sequence = 1L, req)

        // 첫 bob 만 reach — distinct + 비어있는 ID skip 동작 검증
        verify(exactly = 1) { repo.saveMemo(any(), any(), match { it == listOf("bob", "carol") }, any(), any(), any(), any()) }
    }

    // ============== handleList ==============

    @Test
    fun `LIST 미인증 SERVER_ERROR`() = runBlocking {
        val handler = MessageHandler(repo = null, sessions = SessionManager())
        val session = FakeClientSession()
        val req = MessageListRequest.newBuilder().setClientReqId("r").setBox(MessageListRequest.Box.INBOX).build()
        handler.handleList(session, sequence = 1L, req)
        val env = parseAck(session)
        assertEquals(MessageListResponse.Status.SERVER_ERROR, env.messageListRes.status)
    }

    @Test
    fun `LIST INBOX 51 행 returned → limit 50 + has_more true`() = runBlocking {
        val repo = newRepoStub()
        val rows = (1..51).map { i ->
            MessageRepository.MemoRow(
                serverMessageId = "m-$i", fromUserId = "alice", recipientIds = listOf("bob"),
                title = "t$i", body = "b$i", sentAtMillis = i.toLong() * 1000,
                readAtMillis = null, hasAttachment = false, attachmentUploadId = null,
            )
        }
        every { repo.fetchInbox(eq("bob"), any(), eq(51)) } returns rows
        val handler = MessageHandler(repo = repo, sessions = SessionManager())
        val session = FakeClientSession().apply { userId = "bob" }
        val req = MessageListRequest.newBuilder()
            .setClientReqId("r").setBox(MessageListRequest.Box.INBOX).setLimit(50).build()
        handler.handleList(session, sequence = 1L, req)

        val env = parseAck(session)
        assertEquals(MessageListResponse.Status.OK, env.messageListRes.status)
        assertEquals(50, env.messageListRes.entriesCount)
        assertTrue(env.messageListRes.hasMore)
    }

    @Test
    fun `첨부 쪽지는 목록·실시간 푸시에 파일 이름과 크기를 싣는다`() = runBlocking {
        val repo = newRepoStub()
        every { repo.fetchInbox(eq("bob"), any(), any()) } returns listOf(
            MessageRepository.MemoRow(
                serverMessageId = "m-1", fromUserId = "alice", recipientIds = listOf("bob", "carol"),
                title = "t", body = "b", sentAtMillis = 1000, readAtMillis = null,
                hasAttachment = true, attachmentUploadId = "up-1",
                attachmentFilename = "report.pdf", attachmentSize = 2048,
            ),
        )
        justRun { repo.saveMemo(any(), any(), any(), any(), any(), any(), any()) }
        every { repo.markDelivered(any(), any()) } returns 1

        val sessions = SessionManager()
        val alice = FakeClientSession().apply { userId = "alice" }
        val bob = FakeClientSession().apply { userId = "bob" }
        sessions.bind("alice", alice)
        sessions.bind("bob", bob)
        val handler = MessageHandler(
            repo = repo, sessions = sessions,
            attachmentInfo = { id -> if (id == "up-2") "photo.png" to 512L else null },
        )

        handler.handleList(bob, 1L, MessageListRequest.newBuilder().setClientReqId("r").setBox(MessageListRequest.Box.INBOX).build())
        val entry = parseAck(bob).messageListRes.entriesList.single()
        assertEquals("report.pdf", entry.attachmentFilename)
        assertEquals(2048L, entry.attachmentSize)
        bob.sentFrames.clear()

        handler.handleSend(alice, 2L, MessageSend.newBuilder().setClientMessageId("c").setTitle("t")
            .addRecipientIds("bob").setAttachmentUploadId("up-2").build())
        val push = Envelope.parseFrom((bob.sentFrames.single() as Frame.Binary).readBytes()).messageReceived
        assertEquals("photo.png", push.attachmentFilename)
        assertEquals(512L, push.attachmentSize)
    }

    // ============== handleMarkRead ==============

    @Test
    fun `MARK_READ OK 경로`() = runBlocking {
        val repo = newRepoStub()
        every { repo.markRead(eq("smid"), eq("bob")) } returns 1734567890L
        val handler = MessageHandler(repo = repo, sessions = SessionManager())
        val session = FakeClientSession().apply { userId = "bob" }
        val req = MessageMarkRead.newBuilder().setClientReqId("r").setServerMessageId("smid").build()
        handler.handleMarkRead(session, sequence = 1L, req)
        val env = parseAck(session)
        assertEquals(MessageMarkReadAck.Result.OK, env.messageMarkReadAck.result)
        assertEquals(1734567890L, env.messageMarkReadAck.readAt)
    }

    @Test
    fun `MARK_READ NOT_FOUND 경로`() = runBlocking {
        val repo = newRepoStub()
        every { repo.markRead(any(), any()) } returns null
        val handler = MessageHandler(repo = repo, sessions = SessionManager())
        val session = FakeClientSession().apply { userId = "bob" }
        val req = MessageMarkRead.newBuilder().setClientReqId("r").setServerMessageId("ghost").build()
        handler.handleMarkRead(session, sequence = 1L, req)
        val env = parseAck(session)
        assertEquals(MessageMarkReadAck.Result.NOT_FOUND, env.messageMarkReadAck.result)
    }

    // ============== handleDelete ==============

    @Test
    fun `DELETE 발신자로 성공`() = runBlocking {
        val repo = newRepoStub()
        every { repo.softDeleteBySender(any(), any()) } returns 1
        every { repo.softDeleteByRecipient(any(), any()) } returns 0
        val handler = MessageHandler(repo = repo, sessions = SessionManager())
        val session = FakeClientSession().apply { userId = "alice" }
        val req = MessageDelete.newBuilder().setClientReqId("r").setServerMessageId("smid").build()
        handler.handleDelete(session, sequence = 1L, req)
        val env = parseAck(session)
        assertEquals(MessageDeleteAck.Result.OK, env.messageDeleteAck.result)
        // 발신자 성공이면 수신자 시도 X
        verify(exactly = 0) { repo.softDeleteByRecipient(any(), any()) }
    }

    @Test
    fun `DELETE 발신자 실패 → 수신자 fallback 성공`() = runBlocking {
        val repo = newRepoStub()
        every { repo.softDeleteBySender(any(), any()) } returns 0
        every { repo.softDeleteByRecipient(any(), any()) } returns 1
        val handler = MessageHandler(repo = repo, sessions = SessionManager())
        val session = FakeClientSession().apply { userId = "bob" }
        val req = MessageDelete.newBuilder().setClientReqId("r").setServerMessageId("smid").build()
        handler.handleDelete(session, sequence = 1L, req)
        val env = parseAck(session)
        assertEquals(MessageDeleteAck.Result.OK, env.messageDeleteAck.result)
        verify(exactly = 1) { repo.softDeleteBySender(any(), any()) }
        verify(exactly = 1) { repo.softDeleteByRecipient(any(), any()) }
    }

    @Test
    fun `DELETE 둘 다 0 이면 NOT_OWNER`() = runBlocking {
        val repo = newRepoStub()
        every { repo.softDeleteBySender(any(), any()) } returns 0
        every { repo.softDeleteByRecipient(any(), any()) } returns 0
        val handler = MessageHandler(repo = repo, sessions = SessionManager())
        val session = FakeClientSession().apply { userId = "charlie" }
        val req = MessageDelete.newBuilder().setClientReqId("r").setServerMessageId("smid").build()
        handler.handleDelete(session, sequence = 1L, req)
        val env = parseAck(session)
        assertEquals(MessageDeleteAck.Result.NOT_OWNER, env.messageDeleteAck.result)
    }

    // ============== flushUndelivered ==============

    @Test
    fun `flushUndelivered — 미배달 2건 push + markDelivered`() = runBlocking {
        val repo = newRepoStub()
        val rows = listOf(
            MessageRepository.MemoRow("m1", "alice", listOf("bob"), "t1", "b1", 1000L, null, false, null),
            MessageRepository.MemoRow("m2", "carol", listOf("bob"), "t2", "b2", 2000L, null, true, "upl-1"),
        )
        every { repo.fetchUndeliveredForUser(eq("bob"), any()) } returns rows
        every { repo.markDelivered(any(), eq("bob")) } returns 1

        val sessions = SessionManager()
        val bobSession = FakeClientSession().apply { userId = "bob" }
        sessions.bind("bob", bobSession)
        val handler = MessageHandler(repo = repo, sessions = sessions)
        handler.flushUndelivered("bob")

        assertEquals(2, bobSession.sentFrames.size)
        val push1 = Envelope.parseFrom((bobSession.sentFrames[0] as Frame.Binary).readBytes())
        assertEquals(Envelope.PayloadCase.MESSAGE_RECEIVED, push1.payloadCase)
        assertEquals("m1", push1.messageReceived.serverMessageId)

        verify(exactly = 1) { repo.markDelivered("m1", "bob") }
        verify(exactly = 1) { repo.markDelivered("m2", "bob") }
    }

    @Test
    fun `flushUndelivered — 사용자 오프라인이면 아무것도 안 함`() = runBlocking {
        val repo = newRepoStub()
        val handler = MessageHandler(repo = repo, sessions = SessionManager())
        handler.flushUndelivered("bob") // bob 미접속

        verify(exactly = 0) { repo.fetchUndeliveredForUser(any(), any()) }
        verify(exactly = 0) { repo.markDelivered(any(), any()) }
    }

    private fun parseAck(session: FakeClientSession): Envelope {
        assertTrue(session.sentFrames.isNotEmpty(), "sent frame 없음")
        return Envelope.parseFrom((session.sentFrames.last() as Frame.Binary).readBytes())
    }
}

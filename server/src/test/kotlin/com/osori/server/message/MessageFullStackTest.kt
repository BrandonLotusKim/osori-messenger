package com.osori.server.message

import com.osori.proto.v1.*
import com.osori.server.app.configureServer
import com.osori.server.auth.AuthMode
import com.osori.server.db.FileRepository
import com.osori.server.db.MessageRepository
import com.osori.server.db.TalkRepository
import com.osori.server.db.OrganizationRepository
import com.osori.server.db.PushTokenRepository
import com.osori.server.db.TalkRoomRepository
import com.osori.server.db.UserRepository
import com.osori.server.testutil.PostgresTestContainer
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 풀스택: testApplication + 실제 Postgres(osori_test) + 라운드트립.
 * 단위 테스트(mockk)는 분기를 빠르게, 이 테스트는 SQL 까지 실제로 도는 happy path 를 보호.
 */
class MessageFullStackTest {

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        val db = PostgresTestContainer.database()
        // 사용자 사전 생성 — LoginHandler 가 ensureExists 하지만 명시 안전
        UserRepository(db).apply {
            createIfAbsent("test1", "Alice")
            createIfAbsent("test2", "Bob")
            createIfAbsent("test3", "Carol")
        }
    }

    @Test
    fun `alice → bob 쪽지 발송 + bob 받은쪽지함 1건 + markRead OK (DB happy path)`() = testApplication {
        application {
            val db = PostgresTestContainer.database()
            configureServer(
                userRepository = UserRepository(db),
                talkRepository = TalkRepository(db),
                talkRoomRepository = TalkRoomRepository(db),
                orgRepository = OrganizationRepository(db),
                pushTokenRepository = PushTokenRepository(db),
                messageRepository = MessageRepository(db),
                authMode = AuthMode.OPEN,
            )
        }
        val client = createClient { install(ClientWebSockets) }

        // alice 로 발송
        var sentMemoId = ""
        client.webSocket("/ws") {
            login("test1")
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setMessageSend(
                MessageSend.newBuilder().setClientMessageId("c-1").setTitle("회의").setBody("3시 회의").addRecipientIds("test2").build()
            ).build().toByteArray()))
            // LOGIN_RES 다음에 노이즈(presence broadcast 등)가 끼어들 수 있어 MESSAGE_SEND_ACK 까지 drain.
            var ack = withTimeout(5_000) { receiveEnvelope() }
            while (ack.payloadCase != Envelope.PayloadCase.MESSAGE_SEND_ACK) {
                ack = withTimeout(5_000) { receiveEnvelope() }
            }
            assertEquals(MessageSendAck.Result.OK, ack.messageSendAck.result, "messageSendAck: $ack")
            sentMemoId = ack.messageSendAck.serverMessageId
            assertTrue(sentMemoId.isNotEmpty())
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
        assertTrue(sentMemoId.isNotEmpty(), "sentMemoId 가 webSocket 블록 안에서 갱신되지 않음")

        // bob 으로 받은쪽지함 조회
        client.webSocket("/ws") {
            login("test2")
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setMessageListReq(
                MessageListRequest.newBuilder().setClientReqId("l-1").setBox(MessageListRequest.Box.INBOX).setLimit(50).build()
            ).build().toByteArray()))
            val list = receiveUntil(Envelope.PayloadCase.MESSAGE_LIST_RES)
            assertEquals(MessageListResponse.Status.OK, list.messageListRes.status)
            assertEquals(1, list.messageListRes.entriesCount)
            val entry = list.messageListRes.entriesList[0]
            assertEquals(sentMemoId, entry.serverMessageId)
            assertEquals("test1", entry.fromUserId)
            assertEquals("회의", entry.title)
            assertEquals(0L, entry.readAt) // 아직 안 읽음

            // 읽음 처리
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(3).setMessageMarkRead(
                MessageMarkRead.newBuilder().setClientReqId("mr-1").setServerMessageId(sentMemoId).build()
            ).build().toByteArray()))
            val readAck = receiveUntil(Envelope.PayloadCase.MESSAGE_MARK_READ_ACK)
            assertEquals(MessageMarkReadAck.Result.OK, readAck.messageMarkReadAck.result)
            assertTrue(readAck.messageMarkReadAck.readAt > 0)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }

        // alice 보낸쪽지함 — 발송 메모 보임
        client.webSocket("/ws") {
            login("test1")
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setMessageListReq(
                MessageListRequest.newBuilder().setClientReqId("ls-1").setBox(MessageListRequest.Box.SENT).setLimit(50).build()
            ).build().toByteArray()))
            val sent = receiveUntil(Envelope.PayloadCase.MESSAGE_LIST_RES)
            assertEquals(MessageListResponse.Status.OK, sent.messageListRes.status)
            assertEquals(1, sent.messageListRes.entriesCount)
            assertEquals(sentMemoId, sent.messageListRes.entriesList[0].serverMessageId)
            // recipients 가 string_agg 로 채워졌는지
            assertEquals(listOf("test2"), sent.messageListRes.entriesList[0].recipientIdsList)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }

    @Test
    fun `bob 오프라인 시 발송 + bob 로그인 후 flushUndelivered 자동 푸시`() = testApplication {
        application {
            val db = PostgresTestContainer.database()
            configureServer(
                userRepository = UserRepository(db),
                talkRepository = TalkRepository(db),
                talkRoomRepository = TalkRoomRepository(db),
                orgRepository = OrganizationRepository(db),
                pushTokenRepository = PushTokenRepository(db),
                messageRepository = MessageRepository(db),
                authMode = AuthMode.OPEN,
            )
        }
        val client = createClient { install(ClientWebSockets) }

        // alice 발송 (bob 미접속)
        client.webSocket("/ws") {
            login("test1")
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setMessageSend(
                MessageSend.newBuilder().setClientMessageId("c").setTitle("점심 약속").addRecipientIds("test2").build()
            ).build().toByteArray()))
            val ack = receiveUntil(Envelope.PayloadCase.MESSAGE_SEND_ACK)
            assertEquals(MessageSendAck.Result.OK, ack.messageSendAck.result)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }

        // bob 이 로그인 → flushUndelivered 가 즉시 푸시
        client.webSocket("/ws") {
            login("test2")
            // LoginHandler.flushUndelivered 가 발화 — 다음 프레임은 MessageReceived 여야
            val push = withTimeout(3_000) { receiveEnvelope() }
            assertEquals(Envelope.PayloadCase.MESSAGE_RECEIVED, push.payloadCase)
            assertEquals("test1", push.messageReceived.fromUserId)
            assertEquals("점심 약속", push.messageReceived.title)
            assertEquals("test2", push.messageReceived.recipientId)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }

    @Test
    fun `다중 수신자 쪽지 발송 시 받은쪽지함 entry 의 recipient_ids 에 전체 수신자가 채워진다 (전체회신용)`() = testApplication {
        application {
            val db = PostgresTestContainer.database()
            configureServer(
                userRepository = UserRepository(db),
                talkRepository = TalkRepository(db),
                talkRoomRepository = TalkRoomRepository(db),
                orgRepository = OrganizationRepository(db),
                pushTokenRepository = PushTokenRepository(db),
                messageRepository = MessageRepository(db),
                authMode = AuthMode.OPEN,
            )
        }
        val client = createClient { install(ClientWebSockets) }

        // alice → bob, carol 동시 발송
        client.webSocket("/ws") {
            login("test1")
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setMessageSend(
                MessageSend.newBuilder().setClientMessageId("c-multi").setTitle("팀 공지").setBody("내일 워크샵")
                    .addRecipientIds("test2").addRecipientIds("test3").build()
            ).build().toByteArray()))
            val ack = receiveUntil(Envelope.PayloadCase.MESSAGE_SEND_ACK)
            assertEquals(MessageSendAck.Result.OK, ack.messageSendAck.result)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }

        // bob 받은쪽지함 조회 — recipient_ids 에 bob, carol 둘 다 있어야 전체회신 대상 계산 가능
        client.webSocket("/ws") {
            login("test2")
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setMessageListReq(
                MessageListRequest.newBuilder().setClientReqId("l-multi").setBox(MessageListRequest.Box.INBOX).setLimit(50).build()
            ).build().toByteArray()))
            val list = receiveUntil(Envelope.PayloadCase.MESSAGE_LIST_RES)
            assertEquals(1, list.messageListRes.entriesCount)
            val entry = list.messageListRes.entriesList[0]
            assertEquals("test1", entry.fromUserId)
            assertEquals(listOf("test2", "test3"), entry.recipientIdsList.sorted())
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }

    @Test
    fun `받은 쪽지의 첨부는 전달할 수 있고 관계없는 사람은 붙일 수 없다 + 미배달 푸시에 전체 수신자가 실린다`() = testApplication {
        val db = PostgresTestContainer.database()
        UserRepository(db).createIfAbsent("test4", "Dave")
        val files = FileRepository(db)
        val uploadId = files.createUpload(fromUserId = "test1", toUserId = "", filename = "note.txt",
            mimeType = "text/plain", totalSize = 10, storagePath = "unused")
        files.markComplete(uploadId)
        application {
            configureServer(
                userRepository = UserRepository(db),
                talkRepository = TalkRepository(db),
                talkRoomRepository = TalkRoomRepository(db),
                fileRepository = files,
                orgRepository = OrganizationRepository(db),
                pushTokenRepository = PushTokenRepository(db),
                messageRepository = MessageRepository(db),
                authMode = AuthMode.OPEN,
            )
        }
        val client = createClient { install(ClientWebSockets) }

        // 관계없는 test4 가 남의 업로드를 붙이면 거절
        client.webSocket("/ws") {
            login("test4")
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setMessageSend(
                MessageSend.newBuilder().setClientMessageId("c-x").setTitle("가로채기").addRecipientIds("test3")
                    .setAttachmentUploadId(uploadId.toString()).build()
            ).build().toByteArray()))
            val ack = receiveUntil(Envelope.PayloadCase.MESSAGE_SEND_ACK)
            assertEquals(MessageSendAck.Result.INVALID_REQUEST, ack.messageSendAck.result)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }

        // test1 → test2, test3 첨부 쪽지 (둘 다 오프라인)
        client.webSocket("/ws") {
            login("test1")
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setMessageSend(
                MessageSend.newBuilder().setClientMessageId("c-a").setTitle("자료").addRecipientIds("test3").addRecipientIds("test2")
                    .setAttachmentUploadId(uploadId.toString()).build()
            ).build().toByteArray()))
            assertEquals(MessageSendAck.Result.OK, receiveUntil(Envelope.PayloadCase.MESSAGE_SEND_ACK).messageSendAck.result)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }

        // test2 로그인 → 미배달 푸시에 전체 수신자, 이어서 같은 첨부로 test4 에게 전달
        client.webSocket("/ws") {
            login("test2")
            val push = receiveUntil(Envelope.PayloadCase.MESSAGE_RECEIVED)
            assertEquals(listOf("test2", "test3"), push.messageReceived.recipientIdsList)
            assertEquals("note.txt", push.messageReceived.attachmentFilename)
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(3).setMessageSend(
                MessageSend.newBuilder().setClientMessageId("c-f").setTitle("전달: 자료").addRecipientIds("test4")
                    .setAttachmentUploadId(uploadId.toString()).build()
            ).build().toByteArray()))
            assertEquals(MessageSendAck.Result.OK, receiveUntil(Envelope.PayloadCase.MESSAGE_SEND_ACK).messageSendAck.result)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }

        // test4 는 전달받은 쪽지로 첨부를 받을 권한이 생긴다
        client.webSocket("/ws") {
            login("test4")
            val push = receiveUntil(Envelope.PayloadCase.MESSAGE_RECEIVED)
            assertEquals("test2", push.messageReceived.fromUserId)
            assertEquals(uploadId.toString(), push.messageReceived.attachmentUploadId)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
        assertTrue(files.isMessageAttachmentParticipant(uploadId, "test4"))
    }

    // ───── helpers ─────

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.login(userId: String) {
        send(Frame.Binary(true, Envelope.newBuilder().setSequence(1).setLoginReq(
            LoginRequest.newBuilder().setUserId(userId).setDeviceId("itest-memo-$userId").setClientVersion("itest").build()
        ).build().toByteArray()))
        // LOGIN_RES 가 나올 때까지 다른 프레임(presence/memo push 등) skip
        var res = withTimeout(5_000) { receiveEnvelope() }
        while (res.payloadCase != Envelope.PayloadCase.LOGIN_RES) {
            res = withTimeout(5_000) { receiveEnvelope() }
        }
        assertEquals(LoginResponse.Status.OK, res.loginRes.status,
            "login 실패 userId=$userId payload=$res")
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveEnvelope(): Envelope {
        while (true) {
            val frame = incoming.receive()
            if (frame is Frame.Binary) return Envelope.parseFrom(frame.readBytes())
        }
    }

    /** 노이즈(presence/memo push 등) 를 skip 하고 특정 payload 가 나올 때까지 drain. */
    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveUntil(
        target: Envelope.PayloadCase,
    ): Envelope {
        var env = withTimeout(5_000) { receiveEnvelope() }
        while (env.payloadCase != target) {
            env = withTimeout(5_000) { receiveEnvelope() }
        }
        return env
    }
}

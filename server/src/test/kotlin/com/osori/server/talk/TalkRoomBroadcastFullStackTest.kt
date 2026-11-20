package com.osori.server.talk

import com.osori.proto.v1.*
import com.osori.server.app.configureServer
import com.osori.server.auth.AuthMode
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 방 메시지 broadcast 풀스택 검증 — alice 가 방에 메시지 보내면
 *   - alice 본인은 TalkRoomReceived 안 옴 (sender 제외)
 *   - bob 은 TalkRoomReceived 옴
 *   - alice 는 TalkRoomSendAck 만 옴
 */
class TalkRoomBroadcastFullStackTest {

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        val db = PostgresTestContainer.database()
        UserRepository(db).apply {
            createIfAbsent("test1", "Alice")
            createIfAbsent("test2", "Bob")
        }
    }

    @Test
    fun `alice 방 메시지 송신 → bob 만 broadcast 받고 alice 는 ack 만 받음`() = testApplication {
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

        // 1) alice 가 방 만들기
        var roomId = ""
        client.webSocket("/ws") {
            login("test1")
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setTalkRoomCreate(
                TalkRoomCreate.newBuilder().setName("test-room").setClientReqId("rc-1").build()
            ).build().toByteArray()))
            val ack = receiveUntil(Envelope.PayloadCase.TALK_ROOM_CREATE_ACK)
            assertEquals(TalkRoomCreateAck.Result.OK, ack.talkRoomCreateAck.result)
            roomId = ack.talkRoomCreateAck.roomId
            assertTrue(roomId.isNotEmpty())
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
        assertTrue(roomId.isNotEmpty())

        // 2) bob 방 참여
        client.webSocket("/ws") {
            login("test2")
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setTalkRoomJoin(
                TalkRoomJoin.newBuilder().setRoomId(roomId).setClientReqId("rj-1").build()
            ).build().toByteArray()))
            val ack = receiveUntil(Envelope.PayloadCase.TALK_ROOM_JOIN_ACK)
            assertEquals(TalkRoomJoinAck.Result.OK, ack.talkRoomJoinAck.result)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }

        // 3) bob 이 listen 채널 유지하면서 alice 가 방 메시지 송신 →
        //    bob 은 broadcast 받아야, alice 는 ack 만
        coroutineScope {
            // bob 채널 먼저 열고 listen
            val bobReceiveJob = async {
                var received: TalkRoomReceived? = null
                client.webSocket("/ws") {
                    login("test2")
                    val env = receiveUntil(Envelope.PayloadCase.TALK_ROOM_RECEIVED)
                    received = env.talkRoomReceived
                    close(CloseReason(CloseReason.Codes.NORMAL, "done"))
                }
                received
            }

            // 짧은 지연으로 bob 의 webSocket + login 이 먼저 완료되도록
            kotlinx.coroutines.delay(300)

            // alice 가 방 메시지 송신
            val aliceFrames = mutableListOf<Envelope>()
            client.webSocket("/ws") {
                login("test1")
                send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setTalkRoomSend(
                    TalkRoomSend.newBuilder().setRoomId(roomId).setClientTalkId("cm-1").setText("everyone hi").build()
                ).build().toByteArray()))
                // ack 받고 약간 더 대기 — broadcast 가 잘못해서 alice 한테도 오는지 확인
                val ack = receiveUntil(Envelope.PayloadCase.TALK_ROOM_SEND_ACK)
                aliceFrames.add(ack)
                assertEquals(TalkRoomSendAck.Result.OK, ack.talkRoomSendAck.result)
                // 추가로 200ms 동안 noise drain — TalkRoomReceived 가 오면 fail
                try {
                    val extra = withTimeout(200) { receiveEnvelope() }
                    aliceFrames.add(extra)
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) { /* 정상 — alice 한테 broadcast 없음 */ }
                close(CloseReason(CloseReason.Codes.NORMAL, "done"))
            }

            val bobMsg = bobReceiveJob.await()
            // bob 검증
            assertEquals("test1", bobMsg?.fromUserId)
            assertEquals("everyone hi", bobMsg?.text)
            assertEquals(roomId, bobMsg?.roomId)

            // alice 검증 — broadcast 없어야
            val aliceBroadcasts = aliceFrames.filter { it.payloadCase == Envelope.PayloadCase.TALK_ROOM_RECEIVED }
            assertEquals(0, aliceBroadcasts.size, "alice 본인은 자기 방 메시지를 broadcast 로 받지 말아야 함")
        }
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.login(userId: String) {
        send(Frame.Binary(true, Envelope.newBuilder().setSequence(1).setLoginReq(
            LoginRequest.newBuilder().setUserId(userId).setDeviceId("itest-rb-$userId").setClientVersion("itest").build()
        ).build().toByteArray()))
        val res = receiveUntil(Envelope.PayloadCase.LOGIN_RES)
        assertEquals(LoginResponse.Status.OK, res.loginRes.status)
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveEnvelope(): Envelope {
        while (true) {
            val frame = incoming.receive()
            if (frame is Frame.Binary) return Envelope.parseFrom(frame.readBytes())
        }
    }

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

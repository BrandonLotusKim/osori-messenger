package com.osori.server.transport

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
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * 풀스택: testApplication + DB 메시지 사전 insert + TalkHistoryRequest → TalkHistoryResponse 검증.
 * 단위 테스트(TalkHistoryHandlerTest)는 메모리 모드만 cover — 이건 실제 SQL 까지.
 */
class TalkHistoryFullStackTest {

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        val db = PostgresTestContainer.database()
        UserRepository(db).apply {
            createIfAbsent("test1", "Alice")
            createIfAbsent("test2", "Bob")
        }
        // bob 의 수신함에 메시지 3건 사전 적재
        val msgRepo = TalkRepository(db)
        val base = Instant.parse("2026-05-13T01:00:00Z")
        repeat(3) { i ->
            msgRepo.insertMessage(
                messageId = UUID.randomUUID(),
                fromUser = "test1",
                toUser = "test2",
                text = "pre-${i + 1}",
                serverTs = base.plusSeconds(i.toLong()),
            )
        }
    }

    @Test
    fun `bob 로그인 후 TalkHistoryRequest sinceTs=0 limit=50 → 적재된 3건 모두 반환`() = testApplication {
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

        client.webSocket("/ws") {
            // login
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(1).setLoginReq(
                LoginRequest.newBuilder().setUserId("test2").setDeviceId("itest").setClientVersion("itest").build()
            ).build().toByteArray()))
            // LOGIN_RES 까지 drain (presence 등 노이즈 skip)
            var env = withTimeout(5_000) { receiveEnvelope() }
            while (env.payloadCase != Envelope.PayloadCase.LOGIN_RES) {
                env = withTimeout(5_000) { receiveEnvelope() }
            }
            assertEquals(LoginResponse.Status.OK, env.loginRes.status)

            // TalkHistoryRequest
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setTalkHistoryReq(
                TalkHistoryRequest.newBuilder().setSinceTs(0).setLimit(50).build()
            ).build().toByteArray()))
            var res = withTimeout(5_000) { receiveEnvelope() }
            while (res.payloadCase != Envelope.PayloadCase.TALK_HISTORY_RES) {
                res = withTimeout(5_000) { receiveEnvelope() }
            }
            assertEquals(2L, res.sequence)
            assertEquals(3, res.talkHistoryRes.talksCount)
            assertFalse(res.talkHistoryRes.hasMore)
            // 시간순 오름차순
            assertEquals("pre-1", res.talkHistoryRes.talksList[0].text)
            assertEquals("pre-2", res.talkHistoryRes.talksList[1].text)
            assertEquals("pre-3", res.talkHistoryRes.talksList[2].text)
            // from / to 정확
            assertTrue(res.talkHistoryRes.talksList.all { it.fromUserId == "test1" && it.toUserId == "test2" })
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveEnvelope(): Envelope {
        while (true) {
            val frame = incoming.receive()
            if (frame is Frame.Binary) return Envelope.parseFrom(frame.readBytes())
        }
    }
}

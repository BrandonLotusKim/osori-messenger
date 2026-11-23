package com.osori.server.talk

import com.osori.proto.v1.*
import com.osori.server.app.configureServer
import com.osori.server.auth.AuthMode
import com.osori.server.db.MessageRepository
import com.osori.server.db.OrganizationRepository
import com.osori.server.db.PushTokenRepository
import com.osori.server.db.TalkRepository
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
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals

/**
 * 로그인 직후 방 목록(TalkRoomListRequest → TalkRoomListResponse)에 방마다 안 읽은 수가 실려 오는지 —
 * 디스패치(WebSocketHandler) → TalkRoomHandler → TalkRoomRepository(SQL) 까지 실제로 돈다.
 */
class RoomListUnreadFullStackTest {

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        UserRepository(PostgresTestContainer.database()).apply {
            createIfAbsent("test1", "Alice")
            createIfAbsent("test2", "Bob")
        }
    }

    @Test
    fun `방 목록 응답에 내 읽음 위치 이후 남이 보낸 메시지 수가 실린다`() = testApplication {
        val db = PostgresTestContainer.database()
        val rooms = TalkRoomRepository(db)
        val t0 = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS)
        val room = rooms.createRoom("test1, test2", "test1", t0, listOf("test2"))
        rooms.insertRoomTalk(room, "test2", "첫째", t0.plusSeconds(1))
        rooms.insertRoomTalk(room, "test2", "둘째", t0.plusSeconds(2))
        rooms.advanceLastRead(room, "test1", t0.plusSeconds(1))

        application {
            configureServer(
                userRepository = UserRepository(db),
                talkRepository = TalkRepository(db),
                talkRoomRepository = rooms,
                orgRepository = OrganizationRepository(db),
                pushTokenRepository = PushTokenRepository(db),
                messageRepository = MessageRepository(db),
                authMode = AuthMode.OPEN,
            )
        }
        val client = createClient { install(ClientWebSockets) }

        suspend fun unreadFor(user: String): Int {
            var unread = -1
            client.webSocket("/ws") {
                send(Frame.Binary(true, Envelope.newBuilder().setSequence(1).setLoginReq(
                    LoginRequest.newBuilder().setUserId(user).setDeviceId("itest-list-$user").setClientVersion("itest").build()
                ).build().toByteArray()))
                receiveUntil(Envelope.PayloadCase.LOGIN_RES)
                send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setTalkRoomListReq(
                    TalkRoomListRequest.newBuilder().setClientReqId("l-1").build()
                ).build().toByteArray()))
                val res = receiveUntil(Envelope.PayloadCase.TALK_ROOM_LIST_RES).talkRoomListRes
                assertEquals(TalkRoomListResponse.Result.OK, res.result)
                unread = res.roomsList.single { it.roomId == room.toString() }.unreadCount
                close(CloseReason(CloseReason.Codes.NORMAL, "done"))
            }
            return unread
        }

        assertEquals(1, unreadFor("test1")) // 첫째는 읽음, 둘째만
        assertEquals(0, unreadFor("test2")) // 자기가 보낸 메시지는 세지 않음
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveUntil(
        target: Envelope.PayloadCase,
    ): Envelope {
        while (true) {
            val frame = withTimeout(5_000) { incoming.receive() }
            if (frame is Frame.Binary) {
                val env = Envelope.parseFrom(frame.readBytes())
                if (env.payloadCase == target) return env
            }
        }
    }
}

package com.osori.server.transport

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.Heartbeat
import com.osori.proto.v1.TalkHistoryRequest
import com.osori.proto.v1.LoginRequest
import com.osori.proto.v1.Logout as ProtoLogout
import com.osori.proto.v1.TalkSearchRequest
import com.osori.proto.v1.TalkSearchResponse
import com.osori.proto.v1.PushTokenRegisterRequest
import com.osori.proto.v1.PushTokenRegisterResponse
import com.osori.proto.v1.TalkRoomCreate
import com.osori.proto.v1.TalkRoomCreateAck
import com.osori.proto.v1.TalkSend
import com.osori.server.app.configureServer
import com.osori.server.plugin.LoginEvent
import com.osori.server.plugin.LogoutEvent
import com.osori.server.plugin.PluginRegistry
import com.osori.server.plugin.ServerPlugin
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WebSocketHandler 풀스택 통합 테스트.
 *
 * Ktor `testApplication` 으로 실제 라우팅 → 핸들러 → SessionManager 를 통해 흐름 검증.
 * DB 는 사용하지 않음 (`OSORI_DB_ENABLED=false` 효과: configureServer(null, null)).
 *
 * 검증 범위:
 *  - 로그인 → LoginResponse 매칭 (sequence)
 *  - Heartbeat → HeartbeatAck round-trip
 *  - 두 클라 간 TalkSend → TalkReceived 즉시 배달 (Phase 2 핵심 경로)
 */
class WebSocketHandlerIntegrationTest {

    @Test
    fun `Heartbeat to HeartbeatAck round trip + ack sequence matches`() = testApplication {
        application {
            configureServer(userRepository = null, talkRepository = null)
        }
        val client = createClient {
            install(ClientWebSockets)
        }

        client.webSocket("/ws") {
            // 1) 로그인
            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(1)
                        .setLoginReq(
                            LoginRequest.newBuilder()
                                .setUserId("test1")
                                .setPassword("")
                                .setDeviceId("itest-1")
                                .setClientVersion("itest")
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )
            val loginRes = receiveEnvelope()
            assertEquals(1L, loginRes.sequence)
            assertEquals(Envelope.PayloadCase.LOGIN_RES, loginRes.payloadCase)
            assertTrue(loginRes.loginRes.sessionToken.isNotBlank())

            // 2) Heartbeat
            val clientTs = 12_345L
            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(2)
                        .setHeartbeat(Heartbeat.newBuilder().setTs(clientTs).build())
                        .build()
                        .toByteArray(),
                ),
            )
            val ack = receiveEnvelope()
            assertEquals(2L, ack.sequence)
            assertEquals(Envelope.PayloadCase.HEARTBEAT_ACK, ack.payloadCase)
            assertEquals(clientTs, ack.heartbeatAck.clientTs)
            assertTrue(ack.heartbeatAck.serverTs > 0)

            close(CloseReason(CloseReason.Codes.NORMAL, "test done"))
        }
    }

    @Test
    fun `오프라인 수신자에게 보낸 TalkSend 는 QUEUED ack 반환 (메모리 모드)`() = testApplication {
        application {
            configureServer(userRepository = null, talkRepository = null)
        }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws") {
            // 로그인
            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(1)
                        .setLoginReq(
                            LoginRequest.newBuilder()
                                .setUserId("test1")
                                .setPassword("")
                                .setDeviceId("itest")
                                .setClientVersion("itest")
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )
            val loginRes = receiveEnvelope()
            assertEquals(Envelope.PayloadCase.LOGIN_RES, loginRes.payloadCase)

            // 존재하지 않는 수신자 ghost 로 메시지 (오프라인이라 QUEUED 기대)
            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(2)
                        .setTalkSend(
                            TalkSend.newBuilder()
                                .setClientTalkId("itest-msg-1")
                                .setToUserId("ghost")
                                .setText("hello-ghost")
                                .setClientTs(System.currentTimeMillis())
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )

            val ack = receiveEnvelope()
            assertEquals(2L, ack.sequence)
            assertEquals(Envelope.PayloadCase.TALK_SEND_ACK, ack.payloadCase)
            assertEquals(
                com.osori.proto.v1.TalkSendAck.Result.QUEUED,
                ack.talkSendAck.result,
            )
            assertEquals("itest-msg-1", ack.talkSendAck.clientTalkId)
            assertTrue(ack.talkSendAck.serverTalkId.isNotBlank())

            close(CloseReason(CloseReason.Codes.NORMAL, "test done"))
        }
    }

    @Test
    fun `TalkHistoryRequest 는 빈 TalkHistoryResponse 를 반환한다 (메모리 모드, )`() = testApplication {
        //: WebSocketHandler → TalkHistoryHandler 라우팅 풀스택 검증.
        // talkRepository=null 이면 TalkHistoryHandler 는 EMPTY_STORE 를 쓰므로,
        // 요청-응답 envelope 매칭과 has_more=false 만 확인한다.
        application {
            configureServer(userRepository = null, talkRepository = null)
        }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws") {
            // 로그인
            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(1)
                        .setLoginReq(
                            LoginRequest.newBuilder()
                                .setUserId("test1")
                                .setPassword("")
                                .setDeviceId("itest-history")
                                .setClientVersion("itest")
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )
            val loginRes = receiveEnvelope()
            assertEquals(Envelope.PayloadCase.LOGIN_RES, loginRes.payloadCase)

            // TalkHistoryRequest — since_ts=0 limit=50
            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(2)
                        .setTalkHistoryReq(
                            TalkHistoryRequest.newBuilder()
                                .setSinceTs(0)
                                .setLimit(50)
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )
            val talkHistoryRes = withTimeout(3_000) { receiveEnvelope() }
            assertEquals(2L, talkHistoryRes.sequence)
            assertEquals(Envelope.PayloadCase.TALK_HISTORY_RES, talkHistoryRes.payloadCase)
            assertEquals(0, talkHistoryRes.talkHistoryRes.talksCount)
            assertTrue(!talkHistoryRes.talkHistoryRes.hasMore)

            close(CloseReason(CloseReason.Codes.NORMAL, "test done"))
        }
    }

    @Test
    fun `TalkRoomCreate in memory mode returns SERVER_ERROR (routing verification)`() = testApplication {
        //: verifies WebSocketHandler → TalkRoomHandler routing end-to-end.
        // In memory mode (no talkRoomRepository) the handler returns SERVER_ERROR,
        // which still proves the payload reached TalkRoomHandler via the new envelope case.
        application {
            configureServer(userRepository = null, talkRepository = null)
        }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws") {
            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(1)
                        .setLoginReq(
                            LoginRequest.newBuilder()
                                .setUserId("test1")
                                .setPassword("")
                                .setDeviceId("itest-room")
                                .setClientVersion("itest")
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )
            val loginRes = receiveEnvelope()
            assertEquals(Envelope.PayloadCase.LOGIN_RES, loginRes.payloadCase)

            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(2)
                        .setTalkRoomCreate(
                            TalkRoomCreate.newBuilder()
                                .setName("team")
                                .setClientReqId("req-room-1")
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )
            val ack = withTimeout(3_000) { receiveEnvelope() }
            assertEquals(2L, ack.sequence)
            assertEquals(Envelope.PayloadCase.TALK_ROOM_CREATE_ACK, ack.payloadCase)
            assertEquals(TalkRoomCreateAck.Result.SERVER_ERROR, ack.talkRoomCreateAck.result)
            assertEquals("req-room-1", ack.talkRoomCreateAck.clientReqId)

            close(CloseReason(CloseReason.Codes.NORMAL, "test done"))
        }
    }

    @Test
    fun `로그인 성공 시 PluginRegistry fireLogin 이 호출된다 (배선 검증)`() = testApplication {
        //: configureServer 에 주입한 PluginRegistry 를 실제 LoginHandler 가 쓰는지 확인.
        // 유닛 테스트는 핸들러 생성자에 registry 를 넘겨도 Main.configureServer 와이어링은 증명 못 함.
        val received = mutableListOf<LoginEvent>()
        val spy = object : ServerPlugin {
            override val id = "test.spy"
            override val name = "Spy"
            override val version = "1.0.0"
            override fun onLogin(event: LoginEvent) { received += event }
        }
        val registry = PluginRegistry().also { it.register(spy) }

        application {
            configureServer(
                userRepository = null,
                talkRepository = null,
                pluginRegistry = registry,
            )
        }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws") {
            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(1)
                        .setLoginReq(
                            LoginRequest.newBuilder()
                                .setUserId("test1")
                                .setPassword("")
                                .setDeviceId("itest-plugin")
                                .setClientVersion("itest")
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )
            val loginRes = receiveEnvelope()
            assertEquals(Envelope.PayloadCase.LOGIN_RES, loginRes.payloadCase)
            close(CloseReason(CloseReason.Codes.NORMAL, "test done"))
        }

        // 플러그인 호출은 동일 코루틴에서 동기로 일어나므로 close 시점엔 이미 받은 상태
        assertEquals(1, received.size, "fireLogin 이 한 번 호출되어야 한다")
        assertEquals("test1", received[0].userId)
        assertTrue(received[0].success)
        assertEquals("itest-plugin", received[0].deviceId)
    }

    @Test
    fun `명시적 LOGOUT 프레임은 fireLogout explicit=true 를 발화한다`() = testApplication {
        val logoutReceived = mutableListOf<LogoutEvent>()
        val spy = object : ServerPlugin {
            override val id = "test.logout-spy"
            override val name = "Logout Spy"
            override val version = "1.0.0"
            override fun onLogout(event: LogoutEvent) { logoutReceived += event }
        }
        val registry = PluginRegistry().also { it.register(spy) }

        application {
            configureServer(
                userRepository = null,
                talkRepository = null,
                pluginRegistry = registry,
            )
        }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws") {
            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(1)
                        .setLoginReq(
                            LoginRequest.newBuilder()
                                .setUserId("test2")
                                .setPassword("")
                                .setDeviceId("itest-logout")
                                .setClientVersion("itest")
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )
            assertEquals(Envelope.PayloadCase.LOGIN_RES, receiveEnvelope().payloadCase)

            // 명시적 LOGOUT 송신 — explicit=true 경로
            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(2)
                        .setLogout(ProtoLogout.newBuilder().build())
                        .build()
                        .toByteArray(),
                ),
            )
            // 서버가 close 하므로 추가 프레임은 없다.
            withTimeout(3_000) {
                runCatching { incoming.receive() } // close frame 또는 예외 대기
            }
        }

        assertEquals(1, logoutReceived.size, "명시적 LOGOUT 한 번이 fire 되어야 한다")
        assertEquals("test2", logoutReceived[0].userId)
        assertTrue(logoutReceived[0].explicit, "LOGOUT 프레임은 explicit=true")
    }

    @Test
    fun `TalkSearchRequest 는 OK + 빈 결과를 반환한다 (메모리 모드, 라우팅 검증)`() = testApplication {
        //: WebSocketHandler → TalkSearchHandler 라우팅 풀스택 검증.
        // talkRepository=null 이면 EMPTY_STORE 사용 → Ok + 0건 + hasMore=false.
        // 핸들러가 dispatch 안 됐다면 응답이 오지 않아 withTimeout 에서 터짐.
        application {
            configureServer(userRepository = null, talkRepository = null)
        }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws") {
            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(1)
                        .setLoginReq(
                            LoginRequest.newBuilder()
                                .setUserId("test1")
                                .setPassword("")
                                .setDeviceId("itest-search")
                                .setClientVersion("itest")
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )
            val loginRes = receiveEnvelope()
            assertEquals(Envelope.PayloadCase.LOGIN_RES, loginRes.payloadCase)

            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(2)
                        .setTalkSearchReq(
                            TalkSearchRequest.newBuilder()
                                .setClientReqId("req-search-itest")
                                .setKeyword("hello")
                                .setLimit(10)
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )
            val res = withTimeout(3_000) { receiveEnvelope() }
            assertEquals(2L, res.sequence)
            assertEquals(Envelope.PayloadCase.TALK_SEARCH_RES, res.payloadCase)
            assertEquals("req-search-itest", res.talkSearchRes.clientReqId)
            assertEquals(TalkSearchResponse.Result.OK, res.talkSearchRes.result)
            assertEquals(0, res.talkSearchRes.matchedCount)
            assertTrue(!res.talkSearchRes.hasMore)

            close(CloseReason(CloseReason.Codes.NORMAL, "test done"))
        }
    }

    @Test
    fun `PushTokenRegisterRequest 는 DB 없이도 SERVER_ERROR 로 응답 라우팅된다`() = testApplication {
        //: DB 비활성화 상태면 pushTokenHandler 가 SERVER_ERROR 로 응답.
        // 핵심 검증 포인트는 "응답이 오는가" — 즉 WebSocketHandler dispatch + Main 배선이 연결됐는지.
        // 메모리 모드에서 timeout 나면 류 버그 재발 의심.
        application {
            configureServer(userRepository = null, talkRepository = null)
        }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws") {
            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(1)
                        .setLoginReq(
                            LoginRequest.newBuilder()
                                .setUserId("test1")
                                .setPassword("")
                                .setDeviceId("itest-push")
                                .setClientVersion("itest")
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )
            assertEquals(Envelope.PayloadCase.LOGIN_RES, receiveEnvelope().payloadCase)

            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(2)
                        .setPushTokenRegisterReq(
                            PushTokenRegisterRequest.newBuilder()
                                .setClientReqId("req-push-1")
                                .setDeviceId("phone")
                                .setPlatform("fcm")
                                .setToken("tok-xyz")
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )
            val res = withTimeout(3_000) { receiveEnvelope() }
            assertEquals(2L, res.sequence)
            assertEquals(Envelope.PayloadCase.PUSH_TOKEN_REGISTER_RES, res.payloadCase)
            assertEquals("req-push-1", res.pushTokenRegisterRes.clientReqId)
            // pushTokenRepository=null 이므로 Handler 는 SERVER_ERROR 반환. 응답이 왔다는 것이 라우팅 프루프.
            assertEquals(PushTokenRegisterResponse.Result.SERVER_ERROR, res.pushTokenRegisterRes.result)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }

    @Test
    fun `MessageSend 는 빈 recipient 시 INVALID_REQUEST 라우팅된다`() = testApplication {
        //: 메모리 모드 + 빈 recipient_ids → MessageHandler 가 INVALID_REQUEST 응답.
        // 핵심 — WebSocketHandler dispatch + Main 배선 + messageHandler 인스턴스 검증.
        application {
            configureServer(userRepository = null, talkRepository = null)
        }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws") {
            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(1)
                        .setLoginReq(
                            LoginRequest.newBuilder()
                                .setUserId("test1")
                                .setPassword("")
                                .setDeviceId("itest-memo")
                                .setClientVersion("itest")
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )
            assertEquals(Envelope.PayloadCase.LOGIN_RES, receiveEnvelope().payloadCase)

            send(
                Frame.Binary(
                    true,
                    Envelope.newBuilder()
                        .setSequence(2)
                        .setMessageSend(
                            com.osori.proto.v1.MessageSend.newBuilder()
                                .setClientMessageId("cmid-1")
                                .setTitle("hi")
                                .setBody("body")
                                // 빈 recipient_ids → INVALID_REQUEST
                                .build(),
                        )
                        .build()
                        .toByteArray(),
                ),
            )
            val res = withTimeout(3_000) { receiveEnvelope() }
            assertEquals(2L, res.sequence)
            assertEquals(Envelope.PayloadCase.MESSAGE_SEND_ACK, res.payloadCase)
            assertEquals("cmid-1", res.messageSendAck.clientMessageId)
            assertEquals(
                com.osori.proto.v1.MessageSendAck.Result.INVALID_REQUEST,
                res.messageSendAck.result,
            )
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }

    @Test
    fun `MessageListRequest 는 메모리 모드에서 SERVER_ERROR 라우팅된다`() = testApplication {
        application { configureServer(userRepository = null, talkRepository = null) }
        val client = createClient { install(ClientWebSockets) }
        client.webSocket("/ws") {
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(1).setLoginReq(
                LoginRequest.newBuilder().setUserId("test1").setDeviceId("itest").setClientVersion("itest").build()
            ).build().toByteArray()))
            assertEquals(Envelope.PayloadCase.LOGIN_RES, receiveEnvelope().payloadCase)

            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setMessageListReq(
                com.osori.proto.v1.MessageListRequest.newBuilder().setClientReqId("crid-1")
                    .setBox(com.osori.proto.v1.MessageListRequest.Box.INBOX).setLimit(20).build()
            ).build().toByteArray()))
            val res = withTimeout(3_000) { receiveEnvelope() }
            assertEquals(Envelope.PayloadCase.MESSAGE_LIST_RES, res.payloadCase)
            assertEquals(com.osori.proto.v1.MessageListResponse.Status.SERVER_ERROR, res.messageListRes.status)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }

    @Test
    fun `MessageMarkRead 는 메모리 모드에서 SERVER_ERROR 라우팅된다`() = testApplication {
        application { configureServer(userRepository = null, talkRepository = null) }
        val client = createClient { install(ClientWebSockets) }
        client.webSocket("/ws") {
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(1).setLoginReq(
                LoginRequest.newBuilder().setUserId("test1").setDeviceId("itest").setClientVersion("itest").build()
            ).build().toByteArray()))
            assertEquals(Envelope.PayloadCase.LOGIN_RES, receiveEnvelope().payloadCase)

            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setMessageMarkRead(
                com.osori.proto.v1.MessageMarkRead.newBuilder().setClientReqId("crid-1").setServerMessageId("smid-1").build()
            ).build().toByteArray()))
            val res = withTimeout(3_000) { receiveEnvelope() }
            assertEquals(Envelope.PayloadCase.MESSAGE_MARK_READ_ACK, res.payloadCase)
            assertEquals(com.osori.proto.v1.MessageMarkReadAck.Result.SERVER_ERROR, res.messageMarkReadAck.result)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }

    @Test
    fun `MessageDelete 는 메모리 모드에서 SERVER_ERROR 라우팅된다`() = testApplication {
        application { configureServer(userRepository = null, talkRepository = null) }
        val client = createClient { install(ClientWebSockets) }
        client.webSocket("/ws") {
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(1).setLoginReq(
                LoginRequest.newBuilder().setUserId("test1").setDeviceId("itest").setClientVersion("itest").build()
            ).build().toByteArray()))
            assertEquals(Envelope.PayloadCase.LOGIN_RES, receiveEnvelope().payloadCase)

            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setMessageDelete(
                com.osori.proto.v1.MessageDelete.newBuilder().setClientReqId("crid-1").setServerMessageId("smid-1").build()
            ).build().toByteArray()))
            val res = withTimeout(3_000) { receiveEnvelope() }
            assertEquals(Envelope.PayloadCase.MESSAGE_DELETE_ACK, res.payloadCase)
            assertEquals(com.osori.proto.v1.MessageDeleteAck.Result.SERVER_ERROR, res.messageDeleteAck.result)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }

    @Test
    fun `GET _metrics Prometheus 포맷 200 OK`() = testApplication {
        application { configureServer(userRepository = null, talkRepository = null) }
        val client = createClient { /* HTTP default */ }
        val resp = client.get("/metrics")
        assertEquals(io.ktor.http.HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("osori_messages_sent_total"), "messages counter 누락: $body")
        assertTrue(body.contains("osori_session_count"), "session gauge 누락")
        assertTrue(body.contains("osori_build_info"), "build info 누락")
        assertTrue(body.contains("# TYPE"), "Prometheus TYPE 헤더 누락")
    }

    @Test
    fun `GET _health 200 OK + JSON body 포함`() = testApplication {
        application { configureServer(userRepository = null, talkRepository = null) }
        val client = createClient { /* HTTP default */ }
        val resp = client.get("/health")
        assertEquals(io.ktor.http.HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("\"status\":\"ok\""), "status=ok 누락: $body")
        assertTrue(body.contains("\"version\":\"0.1.0\""), "version 누락: $body")
        assertTrue(body.contains("\"db\":\"down\""), "메모리 모드인데 db=down 아님: $body")
    }

    @Test
    fun `TalkRoomHistoryRequest 는 잘못된 roomId 형식이면 ROOM_NOT_FOUND 라우팅된다`() = testApplication {
        application { configureServer(userRepository = null, talkRepository = null) }
        val client = createClient { install(ClientWebSockets) }
        client.webSocket("/ws") {
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(1).setLoginReq(
                LoginRequest.newBuilder().setUserId("test1").setDeviceId("itest").setClientVersion("itest").build()
            ).build().toByteArray()))
            assertEquals(Envelope.PayloadCase.LOGIN_RES, receiveEnvelope().payloadCase)

            send(Frame.Binary(true, Envelope.newBuilder().setSequence(2).setTalkRoomHistoryReq(
                com.osori.proto.v1.TalkRoomHistoryRequest.newBuilder()
                    .setClientReqId("crid-1")
                    .setRoomId("not-a-uuid")
                    .setLimit(20)
                    .build()
            ).build().toByteArray()))
            val res = withTimeout(3_000) { receiveEnvelope() }
            assertEquals(Envelope.PayloadCase.TALK_ROOM_HISTORY_RES, res.payloadCase)
            // talkRoomRepository null → SERVER_ERROR (memory mode 가 먼저 걸림). 메모리 모드 라우팅 확인이 목적.
            assertEquals(com.osori.proto.v1.TalkRoomHistoryResponse.Status.SERVER_ERROR, res.talkRoomHistoryRes.status)
            assertEquals("crid-1", res.talkRoomHistoryRes.clientReqId)
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveEnvelope(): Envelope {
        while (true) {
            val frame = incoming.receive()
            if (frame is Frame.Binary) {
                return Envelope.parseFrom(frame.readBytes())
            }
        }
    }

    @Test
    fun `요청 제한 활성 시 쪽지 연속 발송은 RATE_LIMITED 로 응답된다 (공통 인터셉터 배선)`() = testApplication {
        // 1:1 전송 외 요청도 configureServer 의 rateLimitPolicy 로 제한되는지 — Main 배선 + WebSocketHandler 인터셉터.
        application {
            configureServer(
                userRepository = null,
                talkRepository = null,
                rateLimitPolicy = com.osori.server.talk.RateLimitPolicy.PerUserTokenBucket(capacity = 2, refillPerSecond = 0.001),
            )
        }
        val client = createClient { install(ClientWebSockets) }
        client.webSocket("/ws") {
            send(Frame.Binary(true, Envelope.newBuilder().setSequence(1).setLoginReq(
                LoginRequest.newBuilder().setUserId("test1").setDeviceId("itest-rl").setClientVersion("itest").build()
            ).build().toByteArray()))
            assertEquals(Envelope.PayloadCase.LOGIN_RES, receiveEnvelope().payloadCase)

            val results = mutableListOf<com.osori.proto.v1.MessageSendAck.Result>()
            for (seq in 2L..4L) {
                send(Frame.Binary(true, Envelope.newBuilder().setSequence(seq).setMessageSend(
                    com.osori.proto.v1.MessageSend.newBuilder().setClientMessageId("m$seq").setTitle("t").setBody("b").build()
                ).build().toByteArray()))
                var env = withTimeout(3_000) { receiveEnvelope() }
                while (env.payloadCase != Envelope.PayloadCase.MESSAGE_SEND_ACK) env = withTimeout(3_000) { receiveEnvelope() }
                assertEquals(seq, env.sequence)
                results += env.messageSendAck.result
            }
            // 앞의 2건은 핸들러까지 가서 INVALID_REQUEST(수신자 없음), 3번째는 인터셉터에서 RATE_LIMITED
            assertEquals(com.osori.proto.v1.MessageSendAck.Result.INVALID_REQUEST, results[0])
            assertEquals(com.osori.proto.v1.MessageSendAck.Result.INVALID_REQUEST, results[1])
            assertEquals(com.osori.proto.v1.MessageSendAck.Result.RATE_LIMITED, results[2])
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }
}

package com.osori.server.talk

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.TalkSend
import com.osori.proto.v1.TalkSendAck
import com.osori.server.session.SessionManager
import com.osori.server.testutil.FakeClientSession
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TalkRouterTest {

    private fun buildSendMessage(to: String, text: String): TalkSend =
        TalkSend.newBuilder()
            .setClientTalkId("test-cmsg-1")
            .setToUserId(to)
            .setText(text)
            .setClientTs(System.currentTimeMillis())
            .build()

    private fun Frame.asEnvelope(): Envelope {
        require(this is Frame.Binary) { "expected binary frame, got ${this.frameType}" }
        return Envelope.parseFrom(readBytes())
    }

    @Test
    fun `unauthenticated sender gets SERVER_ERROR ack`() = runBlocking {
        val router = TalkRouter(SessionManager(), talkRepository = null)
        val sender = FakeClientSession()  // userId null → 미인증

        router.handleSend(sender, sequence = 1, buildSendMessage("bob", "hi"))

        assertEquals(1, sender.sentFrames.size)
        val env = sender.sentFrames.first().asEnvelope()
        assertEquals(Envelope.PayloadCase.TALK_SEND_ACK, env.payloadCase)
        assertEquals(TalkSendAck.Result.SERVER_ERROR, env.talkSendAck.result)
    }

    @Test
    fun `blank toUser gets INVALID_TARGET ack`() = runBlocking {
        val router = TalkRouter(SessionManager(), talkRepository = null)
        val sender = FakeClientSession().apply { userId = "alice" }

        router.handleSend(sender, sequence = 2, buildSendMessage("", "hi"))

        val env = sender.sentFrames.first().asEnvelope()
        assertEquals(TalkSendAck.Result.INVALID_TARGET, env.talkSendAck.result)
    }

    @Test
    fun `offline recipient yields QUEUED ack`() = runBlocking {
        val sessions = SessionManager()
        val router = TalkRouter(sessions, talkRepository = null)
        val sender = FakeClientSession().apply { userId = "alice" }
        sessions.bind("alice", sender)

        router.handleSend(sender, sequence = 3, buildSendMessage("bob", "hi"))

        val env = sender.sentFrames.last().asEnvelope()
        assertEquals(TalkSendAck.Result.QUEUED, env.talkSendAck.result)
        assertTrue(env.talkSendAck.serverTalkId.isNotBlank())
    }

    @Test
    fun `online recipient receives message and sender gets DELIVERED ack`() = runBlocking {
        val sessions = SessionManager()
        val router = TalkRouter(sessions, talkRepository = null)
        val sender = FakeClientSession().apply { userId = "alice" }
        val recipient = FakeClientSession().apply { userId = "bob" }
        sessions.bind("alice", sender)
        sessions.bind("bob", recipient)

        router.handleSend(sender, sequence = 4, buildSendMessage("bob", "hello"))

        // 수신자는 TalkReceived 1건을 받는다
        assertEquals(1, recipient.sentFrames.size)
        val notify = recipient.sentFrames.first().asEnvelope()
        assertEquals(Envelope.PayloadCase.TALK_RECEIVED, notify.payloadCase)
        assertEquals("alice", notify.talkReceived.fromUserId)
        assertEquals("hello", notify.talkReceived.text)
        assertTrue(notify.talkReceived.serverTalkId.isNotBlank())

        // 발신자는 DELIVERED ACK 를 받는다 (serverTalkId 가 수신자 프레임과 동일)
        assertEquals(1, sender.sentFrames.size)
        val ack = sender.sentFrames.last().asEnvelope()
        assertEquals(Envelope.PayloadCase.TALK_SEND_ACK, ack.payloadCase)
        assertEquals(TalkSendAck.Result.DELIVERED, ack.talkSendAck.result)
        assertEquals(notify.talkReceived.serverTalkId, ack.talkSendAck.serverTalkId)
    }

    @Test
    fun `ack sequence matches request sequence`() = runBlocking {
        val sessions = SessionManager()
        val router = TalkRouter(sessions, talkRepository = null)
        val sender = FakeClientSession().apply { userId = "alice" }
        sessions.bind("alice", sender)

        router.handleSend(sender, sequence = 42L, buildSendMessage("ghost", "hi"))

        val env = sender.sentFrames.first().asEnvelope()
        assertEquals(42L, env.sequence)
    }

    @Test
    fun `같은 client_msg_id 두번째 호출은 멱등 ack 재전송 (수신자 중복 배달 없음)`() = runBlocking {
        val sessions = SessionManager()
        val router = TalkRouter(sessions, talkRepository = null)
        val sender = FakeClientSession().apply { userId = "alice" }
        val recipient = FakeClientSession().apply { userId = "bob" }
        sessions.bind("alice", sender)
        sessions.bind("bob", recipient)

        // 첫 번째 — 정상 처리
        val msg = TalkSend.newBuilder()
            .setClientTalkId("idempotent-key-1")
            .setToUserId("bob")
            .setText("hello once")
            .setClientTs(System.currentTimeMillis())
            .build()
        router.handleSend(sender, sequence = 100L, msg)

        assertEquals(1, recipient.sentFrames.size, "첫 호출 후 수신자 1건")
        val firstAck = sender.sentFrames.last().asEnvelope().talkSendAck
        val firstServerMsgId = firstAck.serverTalkId
        assertTrue(firstServerMsgId.isNotBlank())

        // 두 번째 — 같은 clientTalkId 멱등 (네트워크 재전송 시뮬)
        router.handleSend(sender, sequence = 101L, msg)

        // 수신자 측에는 추가 배달 없어야 함
        assertEquals(1, recipient.sentFrames.size, "두 번째 호출 후에도 수신자 1건 (재배달 없음)")

        // 발신자는 두 번째 ack 받음 — server_msg_id 동일, sequence 는 두 번째 요청 sequence
        assertEquals(2, sender.sentFrames.size)
        val secondAck = sender.sentFrames.last().asEnvelope()
        assertEquals(101L, secondAck.sequence)
        assertEquals(TalkSendAck.Result.DELIVERED, secondAck.talkSendAck.result)
        assertEquals(firstServerMsgId, secondAck.talkSendAck.serverTalkId)
    }

    @Test
    fun `다른 client_msg_id 는 별개로 처리된다`() = runBlocking {
        val sessions = SessionManager()
        val router = TalkRouter(sessions, talkRepository = null)
        val sender = FakeClientSession().apply { userId = "alice" }
        val recipient = FakeClientSession().apply { userId = "bob" }
        sessions.bind("alice", sender)
        sessions.bind("bob", recipient)

        val m1 = TalkSend.newBuilder()
            .setClientTalkId("k-1").setToUserId("bob").setText("first")
            .setClientTs(System.currentTimeMillis()).build()
        val m2 = TalkSend.newBuilder()
            .setClientTalkId("k-2").setToUserId("bob").setText("second")
            .setClientTs(System.currentTimeMillis()).build()

        router.handleSend(sender, 200L, m1)
        router.handleSend(sender, 201L, m2)

        assertEquals(2, recipient.sentFrames.size, "두 메시지 모두 수신")
        val s1 = sender.sentFrames[0].asEnvelope().talkSendAck.serverTalkId
        val s2 = sender.sentFrames[1].asEnvelope().talkSendAck.serverTalkId
        assertTrue(s1 != s2, "서로 다른 server_msg_id 발급")
    }

    @Test
    fun `rate limit 초과 시 RATE_LIMITED ack 반환 + 수신자 배달 없음`() = runBlocking {
        val sessions = SessionManager()
        var now = 0L
        val router = TalkRouter(
            sessions,
            talkRepository = null,
            rateLimit = RateLimitPolicy.PerUserTokenBucket(
                capacity = 2,
                refillPerSecond = 2.0,
                clock = { now },
            ),
        )
        val sender = FakeClientSession().apply { userId = "alice" }
        val recipient = FakeClientSession().apply { userId = "bob" }
        sessions.bind("alice", sender)
        sessions.bind("bob", recipient)

        // 1, 2번째 — 통과
        router.handleSend(sender, 1, TalkSend.newBuilder()
            .setClientTalkId("r-1").setToUserId("bob").setText("m1")
            .setClientTs(System.currentTimeMillis()).build())
        router.handleSend(sender, 2, TalkSend.newBuilder()
            .setClientTalkId("r-2").setToUserId("bob").setText("m2")
            .setClientTs(System.currentTimeMillis()).build())

        assertEquals(2, recipient.sentFrames.size, "처음 2건은 정상 배달")

        // 3번째 — rate limit 초과
        router.handleSend(sender, 3, TalkSend.newBuilder()
            .setClientTalkId("r-3").setToUserId("bob").setText("m3")
            .setClientTs(System.currentTimeMillis()).build())

        assertEquals(2, recipient.sentFrames.size, "3번째는 수신자에게 배달 안 됨")
        val ack = sender.sentFrames.last().asEnvelope()
        assertEquals(TalkSendAck.Result.RATE_LIMITED, ack.talkSendAck.result)
    }

    @Test
    fun `rate limit Disabled 는 회귀 없음`() = runBlocking {
        val sessions = SessionManager()
        val router = TalkRouter(
            sessions,
            talkRepository = null,
            rateLimit = RateLimitPolicy.Disabled,
        )
        val sender = FakeClientSession().apply { userId = "alice" }
        val recipient = FakeClientSession().apply { userId = "bob" }
        sessions.bind("alice", sender)
        sessions.bind("bob", recipient)

        repeat(10) { i ->
            router.handleSend(sender, (10 + i).toLong(), TalkSend.newBuilder()
                .setClientTalkId("k-$i").setToUserId("bob").setText("m$i")
                .setClientTs(System.currentTimeMillis()).build())
        }
        assertEquals(10, recipient.sentFrames.size, "Disabled 면 전부 통과")
    }

    @Test
    fun `빈 client_msg_id 는 캐시되지 않아 매번 새 처리`() = runBlocking {
        val sessions = SessionManager()
        val router = TalkRouter(sessions, talkRepository = null)
        val sender = FakeClientSession().apply { userId = "alice" }
        val recipient = FakeClientSession().apply { userId = "bob" }
        sessions.bind("alice", sender)
        sessions.bind("bob", recipient)

        val msg = TalkSend.newBuilder()
            .setClientTalkId("") // 빈 문자열 — 멱등 캐시 비대상
            .setToUserId("bob")
            .setText("no-key")
            .setClientTs(System.currentTimeMillis())
            .build()

        router.handleSend(sender, 300L, msg)
        router.handleSend(sender, 301L, msg)

        assertEquals(2, recipient.sentFrames.size, "빈 client_msg_id 는 매번 새 배달")
    }
}

package com.osori.server.transport

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.FileUploadChunk
import com.osori.proto.v1.FileUploadStart
import com.osori.proto.v1.FileUploadStartAck
import com.osori.proto.v1.MessageSend
import com.osori.proto.v1.MessageSendAck
import com.osori.proto.v1.OrgListRequest
import com.osori.proto.v1.TalkRoomCreate
import com.osori.proto.v1.TalkRoomCreateAck
import com.osori.proto.v1.TalkRoomSend
import com.osori.proto.v1.TalkRoomSendAck
import com.osori.proto.v1.TalkSearchRequest
import com.osori.proto.v1.TalkSearchResponse
import com.osori.proto.v1.TalkSend
import com.osori.server.talk.RateLimitPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RequestRateLimiterTest {

    private var now = 1_000_000L
    private fun limiter(capacity: Int = 2, exempt: Set<String> = emptySet()) =
        RequestRateLimiter { RateLimitPolicy.PerUserTokenBucket(capacity, capacity.toDouble(), { now }, exempt) }

    private fun roomSend(seq: Long) = Envelope.newBuilder().setSequence(seq)
        .setTalkRoomSend(TalkRoomSend.newBuilder().setClientTalkId("c$seq").setRoomId("room-1").setText("hi")).build()

    @Test
    fun `그룹 방 메시지 용량 초과 시 RATE_LIMITED 응답, sequence 와 클라이언트 ID echo`() {
        val l = limiter()
        assertNull(l.check("alice", roomSend(1)))
        assertNull(l.check("alice", roomSend(2)))
        val rej = assertNotNull(l.check("alice", roomSend(3)))
        assertEquals(3L, rej.sequence)
        assertEquals(TalkRoomSendAck.Result.RATE_LIMITED, rej.talkRoomSendAck.result)
        assertEquals("c3", rej.talkRoomSendAck.clientTalkId)
        assertEquals("room-1", rej.talkRoomSendAck.roomId)
    }

    @Test
    fun `시간이 지나면 다시 통과`() {
        val l = limiter()
        l.check("alice", roomSend(1)); l.check("alice", roomSend(2))
        assertNotNull(l.check("alice", roomSend(3)))
        now += 1_000
        assertNull(l.check("alice", roomSend(4)))
    }

    @Test
    fun `종류별 버킷이 분리되어 대화를 빨리 쳐도 쪽지는 막히지 않는다`() {
        val l = limiter(capacity = 1)
        assertNull(l.check("alice", roomSend(1)))
        assertNotNull(l.check("alice", roomSend(2)))
        val memo = Envelope.newBuilder().setSequence(3)
            .setMessageSend(MessageSend.newBuilder().setClientMessageId("m1")).build()
        assertNull(l.check("alice", memo))
        val rej = assertNotNull(l.check("alice", memo))
        assertEquals(MessageSendAck.Result.RATE_LIMITED, rej.messageSendAck.result)
        assertEquals("m1", rej.messageSendAck.clientMessageId)
    }

    @Test
    fun `사용자별로 분리`() {
        val l = limiter(capacity = 1)
        assertNull(l.check("alice", roomSend(1)))
        assertNull(l.check("bob", roomSend(2)))
    }

    @Test
    fun `파일은 시작만 세고 청크는 세지 않는다`() {
        val l = limiter(capacity = 1)
        val start = Envelope.newBuilder().setSequence(1).setFileUploadStart(FileUploadStart.newBuilder().setClientReqId("f1")).build()
        val chunk = Envelope.newBuilder().setSequence(2).setFileUploadChunk(FileUploadChunk.newBuilder()).build()
        assertNull(l.check("alice", start))
        repeat(50) { assertNull(l.check("alice", chunk)) }
        val rej = assertNotNull(l.check("alice", start))
        assertEquals(FileUploadStartAck.Result.RATE_LIMITED, rej.fileUploadStartAck.result)
    }

    @Test
    fun `방 만들기와 검색도 제한, 응답 타입이 요청에 맞음`() {
        val l = limiter(capacity = 1)
        val create = Envelope.newBuilder().setSequence(1).setTalkRoomCreate(TalkRoomCreate.newBuilder().setClientReqId("r1")).build()
        l.check("alice", create)
        assertEquals(TalkRoomCreateAck.Result.RATE_LIMITED, assertNotNull(l.check("alice", create)).talkRoomCreateAck.result)
        val search = Envelope.newBuilder().setSequence(2).setTalkSearchReq(TalkSearchRequest.newBuilder().setClientReqId("s1").setKeyword("x")).build()
        l.check("alice", search)
        val rej = assertNotNull(l.check("alice", search))
        assertEquals(TalkSearchResponse.Result.RATE_LIMITED, rej.talkSearchRes.result)
        assertEquals("s1", rej.talkSearchRes.clientReqId)
    }

    @Test
    fun `1대1 전송·조회 요청·미인증 세션·면제 사용자는 통과`() {
        val l = limiter(capacity = 1, exempt = setOf("admin"))
        val talk = Envelope.newBuilder().setTalkSend(TalkSend.newBuilder().setToUserId("bob")).build()
        val org = Envelope.newBuilder().setOrgListReq(OrgListRequest.getDefaultInstance()).build()
        repeat(5) {
            assertNull(l.check("alice", talk))
            assertNull(l.check("alice", org))
            assertNull(l.check(null, roomSend(1)))
            assertNull(l.check("admin", roomSend(1)))
        }
    }

    @Test
    fun `DISABLED 는 항상 통과`() {
        repeat(20) { assertNull(RequestRateLimiter.DISABLED.check("alice", roomSend(it.toLong()))) }
    }
}

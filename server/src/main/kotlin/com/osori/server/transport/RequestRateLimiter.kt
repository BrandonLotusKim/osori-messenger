package com.osori.server.transport

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.FileUploadStartAck
import com.osori.proto.v1.MessageSendAck
import com.osori.proto.v1.TalkRoomCreateAck
import com.osori.proto.v1.TalkRoomInviteAck
import com.osori.proto.v1.TalkRoomLeaveAck
import com.osori.proto.v1.TalkRoomRenameAck
import com.osori.proto.v1.TalkRoomSendAck
import com.osori.proto.v1.TalkSearchResponse
import com.osori.server.talk.RateLimitPolicy

/**
 * 요청 제한 공통 인터셉터 — 메시지 디스패치 앞단(WebSocketHandler)에서 요청 종류별 버킷을 적용한다.
 *
 * 예전에는 1:1 전송(TalkRouter)에만 제한이 걸려 있어 그룹 방 메시지·쪽지·파일 업로드·방 관리 요청으로
 * 우회하거나 서버에 부하를 줄 수 있었다. 종류별로 버킷을 나눠, 대화를 빨리 치는 동안에도 쪽지나
 * 파일 전송이 막히지 않게 한다.
 *
 * - 1:1 전송(TALK_SEND)은 TalkRouter 가 같은 정책으로 이미 검사하므로 여기서는 세지 않는다(이중 차감 방지).
 * - 파일 업로드는 시작(FILE_UPLOAD_START)만 센다. 청크는 한 파일에 수십~수백 개라 세면 정상 업로드가 막힌다.
 * - 조회성 요청(목록, 히스토리, 조직도, 하트비트)은 제한하지 않는다.
 * - 로그인 전 세션(userId 없음)은 각 핸들러가 거부하므로 여기서는 통과시킨다.
 */
class RequestRateLimiter(policyFactory: (Kind) -> RateLimitPolicy) {

    enum class Kind { ROOM_SEND, MESSAGE_SEND, FILE_UPLOAD, ROOM_ADMIN, SEARCH }

    private val policies: Map<Kind, RateLimitPolicy> = Kind.values().associateWith(policyFactory)

    /** 제한 대상이 아니면 null. */
    fun kindOf(envelope: Envelope): Kind? = when (envelope.payloadCase) {
        Envelope.PayloadCase.TALK_ROOM_SEND -> Kind.ROOM_SEND
        Envelope.PayloadCase.MESSAGE_SEND -> Kind.MESSAGE_SEND
        Envelope.PayloadCase.FILE_UPLOAD_START -> Kind.FILE_UPLOAD
        Envelope.PayloadCase.TALK_ROOM_CREATE,
        Envelope.PayloadCase.TALK_ROOM_INVITE,
        Envelope.PayloadCase.TALK_ROOM_RENAME,
        Envelope.PayloadCase.TALK_ROOM_LEAVE -> Kind.ROOM_ADMIN
        Envelope.PayloadCase.TALK_SEARCH_REQ -> Kind.SEARCH
        else -> null
    }

    /**
     * 요청을 통과시킬지 검사한다. 통과면 null, 막히면 클라이언트에 보낼 RATE_LIMITED 응답 Envelope.
     */
    fun check(userId: String?, envelope: Envelope): Envelope? {
        if (userId.isNullOrBlank()) return null
        val kind = kindOf(envelope) ?: return null
        val policy = policies.getValue(kind)
        if (policy.check(userId)) return null
        return rejection(envelope)
    }

    companion object {
        /** 모든 요청 통과(제한 비활성). */
        val DISABLED = RequestRateLimiter { RateLimitPolicy.Disabled }

        /** 요청에 맞는 RATE_LIMITED 응답(요청 sequence·클라이언트 ID 그대로 echo). 대상 아님이면 null. */
        fun rejection(envelope: Envelope): Envelope? {
            val now = System.currentTimeMillis()
            val b = Envelope.newBuilder().setSequence(envelope.sequence)
            when (envelope.payloadCase) {
                Envelope.PayloadCase.TALK_ROOM_SEND -> b.setTalkRoomSendAck(
                    TalkRoomSendAck.newBuilder()
                        .setClientTalkId(envelope.talkRoomSend.clientTalkId)
                        .setRoomId(envelope.talkRoomSend.roomId)
                        .setServerTs(now)
                        .setResult(TalkRoomSendAck.Result.RATE_LIMITED),
                )
                Envelope.PayloadCase.MESSAGE_SEND -> b.setMessageSendAck(
                    MessageSendAck.newBuilder()
                        .setClientMessageId(envelope.messageSend.clientMessageId)
                        .setSentAt(now)
                        .setResult(MessageSendAck.Result.RATE_LIMITED),
                )
                Envelope.PayloadCase.FILE_UPLOAD_START -> b.setFileUploadStartAck(
                    FileUploadStartAck.newBuilder()
                        .setClientReqId(envelope.fileUploadStart.clientReqId)
                        .setServerTs(now)
                        .setResult(FileUploadStartAck.Result.RATE_LIMITED),
                )
                Envelope.PayloadCase.TALK_ROOM_CREATE -> b.setTalkRoomCreateAck(
                    TalkRoomCreateAck.newBuilder()
                        .setClientReqId(envelope.talkRoomCreate.clientReqId)
                        .setServerTs(now)
                        .setResult(TalkRoomCreateAck.Result.RATE_LIMITED),
                )
                Envelope.PayloadCase.TALK_ROOM_INVITE -> b.setTalkRoomInviteAck(
                    TalkRoomInviteAck.newBuilder()
                        .setClientReqId(envelope.talkRoomInvite.clientReqId)
                        .setRoomId(envelope.talkRoomInvite.roomId)
                        .setServerTs(now)
                        .setResult(TalkRoomInviteAck.Result.RATE_LIMITED),
                )
                Envelope.PayloadCase.TALK_ROOM_RENAME -> b.setTalkRoomRenameAck(
                    TalkRoomRenameAck.newBuilder()
                        .setClientReqId(envelope.talkRoomRename.clientReqId)
                        .setRoomId(envelope.talkRoomRename.roomId)
                        .setResult(TalkRoomRenameAck.Result.RATE_LIMITED),
                )
                Envelope.PayloadCase.TALK_ROOM_LEAVE -> b.setTalkRoomLeaveAck(
                    TalkRoomLeaveAck.newBuilder()
                        .setClientReqId(envelope.talkRoomLeave.clientReqId)
                        .setRoomId(envelope.talkRoomLeave.roomId)
                        .setResult(TalkRoomLeaveAck.Result.RATE_LIMITED),
                )
                Envelope.PayloadCase.TALK_SEARCH_REQ -> b.setTalkSearchRes(
                    TalkSearchResponse.newBuilder()
                        .setClientReqId(envelope.talkSearchReq.clientReqId)
                        .setServerTs(now)
                        .setResult(TalkSearchResponse.Result.RATE_LIMITED),
                )
                else -> return null
            }
            return b.build()
        }
    }
}

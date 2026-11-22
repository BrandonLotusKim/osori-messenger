package com.osori.server.talk

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.TalkRoomHistoryRequest
import com.osori.proto.v1.TalkRoomHistoryResponse
import com.osori.proto.v1.TalkRoomReceived
import com.osori.server.db.TalkRoomRepository
import com.osori.server.session.ClientSession
import io.ktor.websocket.*
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("com.osori.server.TalkRoomHistoryHandler")

/**
 * 그룹 채팅 방 히스토리 동기화.
 *
 * 1:1 [TalkHistoryHandler] 와 동일한 페이징 패턴(`since_ts` exclusive + `limit` + `has_more`).
 * 본인이 멤버가 아닌 방은 `NOT_A_MEMBER` 반환 — 외부 사용자가 임의 방 메시지 열람 금지.
 *
 * DB 미활성 / talkRoomRepository=null 이면 SERVER_ERROR.
 */
class TalkRoomHistoryHandler(
    private val talkRoomRepository: TalkRoomRepository?,
) {
    suspend fun handleRoomHistory(
        session: ClientSession,
        sequence: Long,
        req: TalkRoomHistoryRequest,
    ) {
        val userId = session.userId
        if (userId.isNullOrBlank()) {
            logger.warn("미인증 세션의 방 히스토리 요청 거부 session={}", session.id)
            send(session, sequence, req.clientReqId, req.roomId, TalkRoomHistoryResponse.Status.SERVER_ERROR, emptyList(), false)
            return
        }

        val repo = talkRoomRepository
        if (repo == null) {
            send(session, sequence, req.clientReqId, req.roomId, TalkRoomHistoryResponse.Status.SERVER_ERROR, emptyList(), false)
            return
        }

        val roomUuid = runCatching { UUID.fromString(req.roomId) }.getOrNull()
        if (roomUuid == null) {
            send(session, sequence, req.clientReqId, req.roomId, TalkRoomHistoryResponse.Status.ROOM_NOT_FOUND, emptyList(), false)
            return
        }

        val isMember = runCatching { repo.isMember(roomUuid, userId) }.getOrElse {
            logger.error("방 멤버 확인 실패 roomId={} userId={}", req.roomId, userId, it)
            send(session, sequence, req.clientReqId, req.roomId, TalkRoomHistoryResponse.Status.SERVER_ERROR, emptyList(), false)
            return
        }
        if (!isMember) {
            send(session, sequence, req.clientReqId, req.roomId, TalkRoomHistoryResponse.Status.NOT_A_MEMBER, emptyList(), false)
            return
        }

        val limit = when {
            req.limit <= 0 -> DEFAULT_LIMIT
            req.limit > MAX_LIMIT -> MAX_LIMIT
            else -> req.limit
        }
        val sinceMillis = req.sinceTs.coerceAtLeast(0)

        val rows = runCatching { repo.fetchRoomHistory(roomUuid, sinceMillis, limit + 1) }.getOrElse {
            logger.error("방 히스토리 조회 실패 roomId={} userId={}", req.roomId, userId, it)
            send(session, sequence, req.clientReqId, req.roomId, TalkRoomHistoryResponse.Status.SERVER_ERROR, emptyList(), false)
            return
        }

        val hasMore = rows.size > limit
        // rows 는 오래된 순 정렬 + "최신 limit+1건" 이므로 초과분은 가장 오래된 쪽에서 잘라낸다.
        val taken = if (hasMore) rows.takeLast(limit) else rows
        val messages = taken.map { row ->
            TalkRoomReceived.newBuilder()
                .setServerTalkId(row.serverTalkId.toString())
                .setRoomId(row.roomId.toString())
                .setFromUserId(row.fromUserId)
                .setText(row.text)
                .setServerTs(row.serverTs.toEpochMilli())
                .setAttachmentUploadId(row.attachmentUploadId ?: "")
                .build()
        }
        // 읽음 위치 — 실패해도 히스토리 자체는 내려준다(읽음 표시만 빠짐).
        val readStates = runCatching { repo.listReadStates(roomUuid) }.getOrDefault(emptyMap()).map { (uid, ts) ->
            com.osori.proto.v1.TalkRoomReadState.newBuilder().setUserId(uid).setLastReadTs(ts.toEpochMilli()).build()
        }
        send(session, sequence, req.clientReqId, req.roomId, TalkRoomHistoryResponse.Status.OK, messages, hasMore, readStates)
        logger.info("방 히스토리 응답 roomId={} userId={} since={} returned={} hasMore={}",
            req.roomId, userId, sinceMillis, taken.size, hasMore)
    }

    private suspend fun send(
        session: ClientSession, sequence: Long, clientReqId: String, roomId: String,
        status: TalkRoomHistoryResponse.Status, messages: List<TalkRoomReceived>, hasMore: Boolean,
        readStates: List<com.osori.proto.v1.TalkRoomReadState> = emptyList(),
    ) {
        val res = TalkRoomHistoryResponse.newBuilder()
            .setClientReqId(clientReqId)
            .setStatus(status)
            .setRoomId(roomId)
            .addAllTalks(messages)
            .setHasMore(hasMore)
            .addAllReadStates(readStates)
            .build()
        val env = Envelope.newBuilder().setSequence(sequence).setTalkRoomHistoryRes(res).build()
        session.send(Frame.Binary(true, env.toByteArray()))
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
    }
}

package com.osori.server.talk

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.TalkHistoryRequest
import com.osori.proto.v1.TalkHistoryResponse
import com.osori.proto.v1.TalkReceived
import com.osori.server.db.TalkRepository
import com.osori.server.session.ClientSession
import io.ktor.websocket.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.osori.server.TalkHistoryHandler")

interface HistoryStore {
    fun fetchRecentForUser(userId: String, sinceMillis: Long, limit: Int): List<TalkRepository.MessageRow>
}

/**
 * 재접속 후 히스토리 동기화 요청 처리.
 *
 * 현재 MVP 정책:
 *  - 본인이 발신자 또는 수신자였던 메시지 모두 반환 (`messages.to_user = ? OR messages.from_user = ?`)
 *  - `since_ts` 는 epoch millis. 0 이면 전체 범위 (주의: 대량 리턴 가능 → limit 으로 제약)
 *  - `limit` 기본 50, 상한 500. 0 또는 음수면 기본값 적용
 *  - 반환 대상은 sinceMillis 이후 메시지 중 "가장 최근" limit 건 (오래된 게 아니라 최신)
 *  - 정렬: 반환 리스트 자체는 `server_ts` 오래된 순 (클라가 UI 에 시간순 누적하기 쉬움)
 *  - `has_more` 는 서버가 limit+1 을 조회해서 초과분이 있는지로 판정 (초과분은 takeLast 로
 *    가장 오래된 쪽을 잘라냄 — take 를 쓰면 최신 메시지가 잘려나가는 버그가 됨)
 *
 * Phase 6 이후 확장 시 대화방 단위 페이징으로 전환할 수 있다.
 * TalkRepository 가 null (DB 비활성 모드) 이면 빈 응답 반환.
 */
class TalkHistoryHandler(
    private val store: HistoryStore,
) {

    constructor(talkRepository: TalkRepository?) : this(
        if (talkRepository != null) {
            object : HistoryStore {
                override fun fetchRecentForUser(
                    userId: String,
                    sinceMillis: Long,
                    limit: Int,
                ): List<TalkRepository.MessageRow> =
                    talkRepository.fetchRecentForUser(userId, sinceMillis, limit)
            }
        } else {
            EMPTY_STORE
        },
    )

    suspend fun handleHistory(
        session: ClientSession,
        sequence: Long,
        req: TalkHistoryRequest,
    ) {
        val userId = session.userId
        if (userId.isNullOrBlank()) {
            logger.warn("미인증 세션의 히스토리 요청 거부 session={}", session.id)
            sendEmpty(session, sequence)
            return
        }

        val limit = when {
            req.limit <= 0 -> DEFAULT_LIMIT
            req.limit > MAX_LIMIT -> MAX_LIMIT
            else -> req.limit
        }
        val sinceMillis = req.sinceTs.coerceAtLeast(0)

        val rows = try {
            store.fetchRecentForUser(userId, sinceMillis, limit + 1)
        } catch (t: Throwable) {
            logger.error("히스토리 조회 실패 userId={}", userId, t)
            sendEmpty(session, sequence)
            return
        }

        val hasMore = rows.size > limit
        // rows 는 오래된 순으로 정렬돼 있고 fetchRecentForUser 는 "최신 limit+1건"을 반환하므로,
        // 초과분(가장 오래된 1건)을 앞에서 잘라내야 한다. take(limit)로 자르면 정반대로
        // 가장 최근 메시지가 잘려나가는 버그가 됨.
        val taken = if (hasMore) rows.takeLast(limit) else rows

        val respBuilder = TalkHistoryResponse.newBuilder().setHasMore(hasMore)
        for (row in taken) {
            respBuilder.addTalks(
                TalkReceived.newBuilder()
                    .setServerTalkId(row.messageId.toString())
                    .setFromUserId(row.fromUser)
                    .setToUserId(row.toUser)
                    .setText(row.text)
                    .setServerTs(row.serverTs.toEpochMilli())
                    .setDelivered(row.delivered)
                    .build(),
            )
        }

        val env = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkHistoryRes(respBuilder.build())
            .build()
        session.send(Frame.Binary(true, env.toByteArray()))
        logger.info(
            "히스토리 응답 userId={} since={} returned={} hasMore={}",
            userId, sinceMillis, taken.size, hasMore,
        )
    }

    private suspend fun sendEmpty(session: ClientSession, sequence: Long) {
        val env = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkHistoryRes(TalkHistoryResponse.newBuilder().setHasMore(false).build())
            .build()
        session.send(Frame.Binary(true, env.toByteArray()))
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500

        private val EMPTY_STORE: HistoryStore = object : HistoryStore {
            override fun fetchRecentForUser(
                userId: String,
                sinceMillis: Long,
                limit: Int,
            ): List<TalkRepository.MessageRow> = emptyList()
        }
    }
}

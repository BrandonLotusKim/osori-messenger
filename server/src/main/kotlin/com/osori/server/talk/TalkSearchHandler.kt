package com.osori.server.talk

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.TalkReceived
import com.osori.proto.v1.TalkSearchRequest
import com.osori.proto.v1.TalkSearchResponse
import com.osori.server.db.TalkRepository
import com.osori.server.session.ClientSession
import io.ktor.websocket.*
import org.slf4j.LoggerFactory
import java.time.Instant

private val logger = LoggerFactory.getLogger("com.osori.server.TalkSearchHandler")

interface TalkSearchStore {
    fun searchMessages(userId: String, keyword: String, sinceMillis: Long, untilMillis: Long, limit: Int): List<TalkRepository.MessageRow>
}

/**
 * 1:1 메시지 본문 키워드 검색 핸들러.
 *
 * 정책:
 *   - 인증된 세션만 허용
 *   - keyword 길이 1 미만 → OK + 빈 결과 (UI 가 상태 표시 쉽게)
 *   - limit 기본 50, 상한 500
 *   - has_more 는 limit+1 조회 방식
 *   - Repository null (DB 비활성) 시 EMPTY_STORE
 */
class TalkSearchHandler(private val store: TalkSearchStore) {

    constructor(repo: TalkRepository?) : this(
        if (repo != null) {
            object : TalkSearchStore {
                override fun searchMessages(
                    userId: String,
                    keyword: String,
                    sinceMillis: Long,
                    untilMillis: Long,
                    limit: Int,
                ): List<TalkRepository.MessageRow> =
                    repo.searchMessages(userId, keyword, sinceMillis, untilMillis, limit)
            }
        } else {
            EMPTY_STORE
        },
    )

    suspend fun handle(session: ClientSession, sequence: Long, req: TalkSearchRequest) {
        val userId = session.userId
        if (userId.isNullOrBlank()) {
            logger.warn("미인증 세션 검색 거부 session={}", session.id)
            sendError(session, sequence, req.clientReqId)
            return
        }

        val keyword = req.keyword.trim()
        if (keyword.isEmpty()) {
            sendEmpty(session, sequence, req.clientReqId)
            return
        }

        val limit = when {
            req.limit <= 0 -> DEFAULT_LIMIT
            req.limit > MAX_LIMIT -> MAX_LIMIT
            else -> req.limit
        }

        val rows = try {
            store.searchMessages(userId, keyword, req.sinceTs, req.untilTs, limit + 1)
        } catch (t: Throwable) {
            logger.error("메시지 검색 실패 userId={} keyword='{}'", userId, keyword, t)
            sendError(session, sequence, req.clientReqId)
            return
        }

        val hasMore = rows.size > limit
        val taken = if (hasMore) rows.take(limit) else rows

        val resp = TalkSearchResponse.newBuilder()
            .setClientReqId(req.clientReqId)
            .setMatchedCount(taken.size)
            .setHasMore(hasMore)
            .setServerTs(Instant.now().toEpochMilli())
            .setResult(TalkSearchResponse.Result.OK)
        for (row in taken) {
            resp.addTalks(
                TalkReceived.newBuilder()
                    .setServerTalkId(row.messageId.toString())
                    .setFromUserId(row.fromUser)
                    .setToUserId(row.toUser)
                    .setText(row.text)
                    .setServerTs(row.serverTs.toEpochMilli())
                    .build(),
            )
        }

        val env = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkSearchRes(resp.build())
            .build()
        session.send(Frame.Binary(true, env.toByteArray()))
        logger.info(
            "메시지 검색 userId={} keyword='{}' returned={} hasMore={}",
            userId, keyword, taken.size, hasMore,
        )
    }

    private suspend fun sendEmpty(session: ClientSession, sequence: Long, clientReqId: String) {
        val env = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkSearchRes(
                TalkSearchResponse.newBuilder()
                    .setClientReqId(clientReqId)
                    .setMatchedCount(0)
                    .setHasMore(false)
                    .setServerTs(Instant.now().toEpochMilli())
                    .setResult(TalkSearchResponse.Result.OK)
                    .build(),
            )
            .build()
        session.send(Frame.Binary(true, env.toByteArray()))
    }

    private suspend fun sendError(session: ClientSession, sequence: Long, clientReqId: String) {
        val env = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkSearchRes(
                TalkSearchResponse.newBuilder()
                    .setClientReqId(clientReqId)
                    .setResult(TalkSearchResponse.Result.SERVER_ERROR)
                    .setServerTs(Instant.now().toEpochMilli())
                    .build(),
            )
            .build()
        session.send(Frame.Binary(true, env.toByteArray()))
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500

        private val EMPTY_STORE: TalkSearchStore = object : TalkSearchStore {
            override fun searchMessages(
                userId: String,
                keyword: String,
                sinceMillis: Long,
                untilMillis: Long,
                limit: Int,
            ): List<TalkRepository.MessageRow> = emptyList()
        }
    }
}

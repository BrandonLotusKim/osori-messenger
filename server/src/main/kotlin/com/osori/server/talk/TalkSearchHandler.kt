package com.osori.server.talk

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.TalkReceived
import com.osori.proto.v1.TalkSearchHit
import com.osori.proto.v1.TalkSearchRequest
import com.osori.proto.v1.TalkSearchResponse
import com.osori.server.db.TalkRepository
import com.osori.server.db.TalkRoomRepository
import com.osori.server.session.ClientSession
import io.ktor.websocket.*
import org.slf4j.LoggerFactory
import java.time.Instant

private val logger = LoggerFactory.getLogger("com.osori.server.TalkSearchHandler")

interface TalkSearchStore {
    fun searchMessages(userId: String, keyword: String, sinceMillis: Long, untilMillis: Long, limit: Int): List<TalkRepository.MessageRow>

    /** 대화방 검색을 지원하면 true — 이 경우 방 메시지(talk_room_messages)만 검색한다. */
    val supportsRooms: Boolean get() = false

    fun searchRoomTalks(userId: String, keyword: String, sinceMillis: Long, untilMillis: Long, limit: Int): List<TalkRoomRepository.RoomSearchRow> =
        emptyList()
}

/**
 * 대화 본문 키워드 검색 핸들러.
 *
 * 1:1 대화도 방(Room)으로 통합된 뒤 모든 대화는 talk_room_messages 에 쌓인다. 그래서 방 저장소가
 * 있으면 "요청자가 현재 멤버인 방"의 메시지를 검색해 hits(방 ID 포함)로 돌려준다. 옛 1:1 테이블
 * (talk_messages)은 이관 후 원본 보존용이라 함께 검색하면 같은 대화가 두 번 나오므로 방 저장소가
 * 없을 때(테스트/구성 누락)만 쓴다.
 *
 * 정책:
 *   - 인증된 세션만 허용
 *   - keyword 길이 1 미만 → OK + 빈 결과 (UI 가 상태 표시 쉽게)
 *   - limit 기본 50, 상한 500
 *   - has_more 는 limit+1 조회 방식
 *   - Repository null (DB 비활성) 시 EMPTY_STORE
 */
class TalkSearchHandler(private val store: TalkSearchStore) {

    constructor(repo: TalkRepository?, roomRepo: TalkRoomRepository? = null) : this(
        if (repo != null || roomRepo != null) {
            object : TalkSearchStore {
                override fun searchMessages(
                    userId: String,
                    keyword: String,
                    sinceMillis: Long,
                    untilMillis: Long,
                    limit: Int,
                ): List<TalkRepository.MessageRow> =
                    repo?.searchMessages(userId, keyword, sinceMillis, untilMillis, limit) ?: emptyList()

                override val supportsRooms: Boolean get() = roomRepo != null

                override fun searchRoomTalks(
                    userId: String,
                    keyword: String,
                    sinceMillis: Long,
                    untilMillis: Long,
                    limit: Int,
                ): List<TalkRoomRepository.RoomSearchRow> =
                    roomRepo?.searchRoomTalks(userId, keyword, sinceMillis, untilMillis, limit) ?: emptyList()
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

        val resp = TalkSearchResponse.newBuilder()
            .setClientReqId(req.clientReqId)
            .setServerTs(Instant.now().toEpochMilli())
            .setResult(TalkSearchResponse.Result.OK)
        val returned: Int
        val hasMore: Boolean
        try {
            if (store.supportsRooms) {
                val rows = store.searchRoomTalks(userId, keyword, req.sinceTs, req.untilTs, limit + 1)
                hasMore = rows.size > limit
                val taken = if (hasMore) rows.take(limit) else rows
                for (row in taken) {
                    resp.addHits(
                        TalkSearchHit.newBuilder()
                            .setServerTalkId(row.serverTalkId.toString())
                            .setRoomId(row.roomId.toString())
                            .setRoomName(row.roomName)
                            .setRoomNameIsCustom(row.nameIsCustom)
                            .addAllMemberIds(row.memberIds)
                            .setFromUserId(row.fromUserId)
                            .setText(row.text)
                            .setServerTs(row.serverTs.toEpochMilli())
                            .build(),
                    )
                }
                returned = taken.size
            } else {
                val rows = store.searchMessages(userId, keyword, req.sinceTs, req.untilTs, limit + 1)
                hasMore = rows.size > limit
                val taken = if (hasMore) rows.take(limit) else rows
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
                returned = taken.size
            }
        } catch (t: Throwable) {
            logger.error("메시지 검색 실패 userId={} keyword='{}'", userId, keyword, t)
            sendError(session, sequence, req.clientReqId)
            return
        }
        resp.setMatchedCount(returned).setHasMore(hasMore)

        val env = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkSearchRes(resp.build())
            .build()
        session.send(Frame.Binary(true, env.toByteArray()))
        logger.info(
            "메시지 검색 userId={} keyword='{}' returned={} hasMore={}",
            userId, keyword, returned, hasMore,
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

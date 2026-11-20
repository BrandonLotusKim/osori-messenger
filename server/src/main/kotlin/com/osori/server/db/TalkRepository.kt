package com.osori.server.db

import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

private val logger = LoggerFactory.getLogger("com.osori.server.TalkRepository")

/**
 * 메시지 영속화 Repository.
 *
 * 관계:
 *   - messages        = 메시지 본문 테이블 (모든 메시지)
 *   - message_outbox  = 수신자가 오프라인일 때만 추가 적재 (재접속 시 flush)
 *
 * 온라인 배달 시  → insertMessage 만 호출
 * 오프라인 배달 시 → insertMessage + insertOutbox (트랜잭션)
 * 재접속 flush   → fetchOutbox → 각 메시지 배달 → markDelivered(id)
 */
class TalkRepository(private val db: Database) {

    data class MessageRow(
        val messageId: UUID,
        val fromUser: String,
        val toUser: String,
        val text: String,
        val serverTs: Instant,
        /** 히스토리 조회 전용 — outbox 에 미배달 상태로 남아있으면 false. fetchOutbox 등 다른 조회에선 안 씀. */
        val delivered: Boolean = true,
    )

    data class OutboxEntry(
        val id: Long,
        val message: MessageRow,
    )

    /** 메시지 본문 저장. */
    fun insertMessage(
        messageId: UUID,
        fromUser: String,
        toUser: String,
        text: String,
        serverTs: Instant,
    ) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO talk_messages (talk_id, from_user, to_user, text, server_ts)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, messageId)
                ps.setString(2, fromUser)
                ps.setString(3, toUser)
                ps.setString(4, text)
                ps.setTimestamp(5, Timestamp.from(serverTs))
                ps.executeUpdate()
            }
        }
    }

    /**
     * 오프라인 수신자용 아웃박스 엔트리 추가.
     * 호출 전 반드시 insertMessage 로 메시지 본문이 먼저 저장되어 있어야 한다 (FK 제약).
     * 온라인/오프라인 분기 전체를 트랜잭션으로 묶고 싶다면 saveOfflineMessage 사용.
     */
    fun insertOutbox(messageId: UUID, toUser: String) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO talk_outbox (talk_id, to_user, delivered, created_at)
                VALUES (?, ?, false, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, messageId)
                ps.setString(2, toUser)
                ps.setTimestamp(3, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
    }

    /**
     * 오프라인 수신자에게 보낼 메시지를 본문+아웃박스 동시 저장.
     * 호출자가 트랜잭션 경계 신경쓰지 않도록 내부에서 inTransaction 처리.
     */
    fun saveOfflineMessage(
        messageId: UUID,
        fromUser: String,
        toUser: String,
        text: String,
        serverTs: Instant,
    ) {
        db.inTransaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO talk_messages (talk_id, from_user, to_user, text, server_ts)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, messageId)
                ps.setString(2, fromUser)
                ps.setString(3, toUser)
                ps.setString(4, text)
                ps.setTimestamp(5, Timestamp.from(serverTs))
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO talk_outbox (talk_id, to_user, delivered, created_at)
                VALUES (?, ?, false, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, messageId)
                ps.setString(2, toUser)
                ps.setTimestamp(3, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
    }

    /** 지정 수신자의 미배달 아웃박스 메시지 목록 (오래된 순). */
    fun fetchOutbox(toUser: String, limit: Int = 100): List<OutboxEntry> =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT o.id, m.talk_id, m.from_user, m.to_user, m.text, m.server_ts
                FROM talk_outbox o
                JOIN talk_messages m ON m.talk_id = o.talk_id
                WHERE o.to_user = ? AND o.delivered = false
                ORDER BY o.created_at ASC
                LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, toUser)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    val result = mutableListOf<OutboxEntry>()
                    while (rs.next()) {
                        result += OutboxEntry(
                            id = rs.getLong(1),
                            message = MessageRow(
                                messageId = rs.getObject(2) as UUID,
                                fromUser = rs.getString(3),
                                toUser = rs.getString(4),
                                text = rs.getString(5),
                                serverTs = rs.getTimestamp(6).toInstant(),
                            ),
                        )
                    }
                    result
                }
            }
        }

    /** 아웃박스 엔트리를 배달 완료로 표시. */
    fun markDelivered(outboxId: Long) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE talk_outbox SET delivered = true WHERE id = ?",
            ).use { ps ->
                ps.setLong(1, outboxId)
                val updated = ps.executeUpdate()
                if (updated == 0) {
                    logger.warn("아웃박스 항목 없음 id={}", outboxId)
                }
            }
        }
    }

    /**
     * 해당 사용자가 주고받은(발신+수신) 메시지 중 가장 최근 `limit` 건을 조회 (히스토리 sync 용).
     *
     * 과거 버그 이력:
     *  1) `to_user = ?` 만으로 조회해 본인이 보낸 메시지가 히스토리 재동기화 시 누락됨
     *     → from/to 양쪽 다 포함하도록 수정.
     *  2) `ORDER BY server_ts ASC LIMIT ?` 로 가장 오래된 N건만 반환했음. 클라이언트는 항상
     *     sinceMillis=0 으로 호출하므로, 총 메시지 수가 limit(기본 50)을 넘는 순간부터
     *     "가장 최근" 메시지(방금 보낸 것 포함)가 결과에서 영원히 빠지는 심각한 버그였다
     *     (대화창을 닫았다 다시 열면 최근에 보낸 메시지가 안 보임).
     *     → 최신순으로 limit 건을 먼저 뽑은 뒤 오래된 순으로 재정렬해서 반환하도록 수정.
     *
     * @param userId 조회 대상 사용자 ID (발신자 또는 수신자)
     * @param sinceMillis 이 시각 이후 (exclusive). 0 이면 전체 범위
     * @param limit 반환 최대 건수. 호출자는 has_more 판정용으로 limit+1 을 넘겨도 무방.
     *   초과분이 있으면 반환 리스트에서 가장 오래된 항목부터 잘라내야 한다(takeLast).
     * @return sinceMillis 이후 메시지 중 최신 `limit` 건, 오래된 순으로 정렬해서 반환
     */
    fun fetchRecentForUser(userId: String, sinceMillis: Long, limit: Int): List<MessageRow> =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT talk_id, from_user, to_user, text, server_ts, delivered
                FROM (
                    SELECT m.talk_id, m.from_user, m.to_user, m.text, m.server_ts,
                           CASE WHEN o.id IS NOT NULL AND o.delivered = false THEN false ELSE true END AS delivered
                    FROM talk_messages m
                    LEFT JOIN talk_outbox o ON o.talk_id = m.talk_id
                    WHERE (m.to_user = ? OR m.from_user = ?) AND m.server_ts > ?
                    ORDER BY m.server_ts DESC
                    LIMIT ?
                ) recent
                ORDER BY server_ts ASC
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, userId)
                ps.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(sinceMillis)))
                ps.setInt(4, limit)
                ps.executeQuery().use { rs ->
                    val result = mutableListOf<MessageRow>()
                    while (rs.next()) {
                        result += MessageRow(
                            messageId = rs.getObject(1) as UUID,
                            fromUser = rs.getString(2),
                            toUser = rs.getString(3),
                            text = rs.getString(4),
                            serverTs = rs.getTimestamp(5).toInstant(),
                            delivered = rs.getBoolean(6),
                        )
                    }
                    result
                }
            }
        }

    /**
     * 본문 키워드 검색.
     * userId 가 송신자 또는 수신자인 메시지 중 text 에 keyword 포함(ILIKE) 인 것만.
     * sinceMillis/untilMillis = 0 이면 무한대 취급. 정렬은 server_ts DESC (최근 우선).
     */
    fun searchMessages(
        userId: String,
        keyword: String,
        sinceMillis: Long,
        untilMillis: Long,
        limit: Int,
    ): List<MessageRow> = db.withConnection { conn ->
        val pattern = "%${keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        val sinceClause = if (sinceMillis > 0) "AND server_ts >= ? " else ""
        val untilClause = if (untilMillis > 0) "AND server_ts <= ? " else ""
        val sql = """
            SELECT talk_id, from_user, to_user, text, server_ts
            FROM talk_messages
            WHERE (from_user = ? OR to_user = ?)
              AND text ILIKE ? ESCAPE '\'
              $sinceClause
              $untilClause
            ORDER BY server_ts DESC
            LIMIT ?
            """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setString(idx++, userId)
            ps.setString(idx++, userId)
            ps.setString(idx++, pattern)
            if (sinceMillis > 0) {
                ps.setTimestamp(idx++, Timestamp.from(Instant.ofEpochMilli(sinceMillis)))
            }
            if (untilMillis > 0) {
                ps.setTimestamp(idx++, Timestamp.from(Instant.ofEpochMilli(untilMillis)))
            }
            ps.setInt(idx, limit)
            ps.executeQuery().use { rs ->
                val result = mutableListOf<MessageRow>()
                while (rs.next()) {
                    result += MessageRow(
                        messageId = rs.getObject(1) as UUID,
                        fromUser = rs.getString(2),
                        toUser = rs.getString(3),
                        text = rs.getString(4),
                        serverTs = rs.getTimestamp(5).toInstant(),
                    )
                }
                result
            }
        }
    }

    /** 테스트/관리용 메시지 총수. */
    fun countMessages(): Long = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM talk_messages").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }
}

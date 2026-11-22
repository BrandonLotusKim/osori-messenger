package com.osori.server.audit

import com.osori.server.db.Database
import org.postgresql.util.PGobject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

private val logger = LoggerFactory.getLogger("com.osori.server.AuditRepository")

/**
 * 감사 로그 Repository.
 *
 * 정책:
 *   - **FK 없음** — 감사 경로와 데이터 경로 분리. 추후 별도 DB 인스턴스로 이관 가능.
 *   - `event_type` 은 `도메인.동사.세부` 3단 네임스페이스 (예: `auth.login.success`).
 *   - `payload` 는 JSONB — 스키마 변경 없이 이벤트별 부가 정보 확장.
 *   - 모든 호출은 **best-effort** — Repository 내부에서 Throwable 을 잡아 warning 로그만 남기고
 *     호출자(핸들러) 로 전파하지 않는다. 감사 로그 실패가 본 로직을 막으면 안 된다.
 */
open class AuditRepository(private val db: Database) {

    data class EventRow(
        val eventId: UUID,
        val actorUserId: String?,
        val eventType: String,
        val target: String?,
        val payloadJson: String,
        val serverTs: Instant,
    )

    /**
     * 이벤트 기록. payloadJson 은 유효한 JSON 문자열이어야 한다 (빈 객체 `"{}"` OK).
     * 실패해도 예외 전파하지 않는다.
     */
    open fun record(
        eventType: String,
        actorUserId: String? = null,
        target: String? = null,
        payloadJson: String = "{}",
    ): UUID? {
        val id = UUID.randomUUID()
        return try {
            db.withConnection { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO log_events (event_id, actor_user_id, event_type, target, payload, server_ts)
                    VALUES (?, ?, ?, ?, ?::jsonb, now())
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, id)
                    if (actorUserId == null) ps.setNull(2, java.sql.Types.VARCHAR) else ps.setString(2, actorUserId)
                    ps.setString(3, eventType)
                    if (target == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, target)
                    val pg = PGobject().apply { type = "jsonb"; value = payloadJson }
                    ps.setObject(5, pg)
                    ps.executeUpdate()
                }
            }
            id
        } catch (t: Throwable) {
            logger.warn(
                "AuditRepository.record 실패 — eventType={}, actor={}, 이유={}",
                eventType, actorUserId, t.message,
            )
            null
        }
    }

    // ─────────── 편의 메서드 (주요 이벤트) ───────────

    open fun recordLoginSuccess(userId: String, sessionId: String) =
        record("auth.login.success", actorUserId = userId, target = sessionId,
            payloadJson = """{"sessionId":"${jsonEscape(sessionId)}"}""")

    open fun recordLoginFailed(userId: String?, reason: String) =
        record("auth.login.failed", actorUserId = userId,
            payloadJson = """{"reason":"${jsonEscape(reason)}"}""")

    open fun recordLogout(userId: String) =
        record("auth.logout", actorUserId = userId)

    open fun recordMessageSent(fromUserId: String, toUserId: String, messageId: String, bytes: Int) =
        record("msg.sent", actorUserId = fromUserId, target = messageId,
            payloadJson = """{"toUserId":"${jsonEscape(toUserId)}","messageId":"${jsonEscape(messageId)}","bytes":$bytes}""")

    open fun recordFileUploaded(fromUserId: String, uploadId: String, filename: String, totalBytes: Long) =
        record("file.uploaded", actorUserId = fromUserId, target = uploadId,
            payloadJson = """{"uploadId":"${jsonEscape(uploadId)}","filename":"${jsonEscape(filename)}","totalBytes":$totalBytes}""")

    open fun recordFileDownloaded(userId: String, uploadId: String) =
        record("file.downloaded", actorUserId = userId, target = uploadId,
            payloadJson = """{"uploadId":"${jsonEscape(uploadId)}"}""")

    open fun recordRoomCreated(ownerUserId: String, roomId: String, name: String) =
        record("room.created", actorUserId = ownerUserId, target = roomId,
            payloadJson = """{"roomId":"${jsonEscape(roomId)}","name":"${jsonEscape(name)}"}""")

    /** 같은 계정의 새 로그인으로 기존 세션이 끊김. target = 끊긴 세션 ID. */
    open fun recordSessionSuperseded(userId: String, oldSessionId: String, newSessionId: String) =
        record("auth.session.superseded", actorUserId = userId, target = oldSessionId,
            payloadJson = """{"oldSessionId":"${jsonEscape(oldSessionId)}","newSessionId":"${jsonEscape(newSessionId)}"}""")

    // 대화방 이벤트 — 메시지 본문·방 이름 외 개인 내용은 남기지 않는다(메시지는 ID 와 크기만).

    open fun recordRoomMessageSent(fromUserId: String, roomId: String, messageId: String, bytes: Int, recipients: Int) =
        record("room.msg.sent", actorUserId = fromUserId, target = messageId,
            payloadJson = """{"roomId":"${jsonEscape(roomId)}","messageId":"${jsonEscape(messageId)}","bytes":$bytes,"recipients":$recipients}""")

    open fun recordRoomInvited(actorUserId: String, roomId: String, invitedUserIds: List<String>) =
        record("room.invited", actorUserId = actorUserId, target = roomId,
            payloadJson = """{"roomId":"${jsonEscape(roomId)}","invited":[${invitedUserIds.joinToString(",") { "\"${jsonEscape(it)}\"" }}]}""")

    open fun recordRoomRenamed(actorUserId: String, roomId: String, newName: String) =
        record("room.renamed", actorUserId = actorUserId, target = roomId,
            payloadJson = """{"roomId":"${jsonEscape(roomId)}","name":"${jsonEscape(newName)}"}""")

    open fun recordRoomLeft(userId: String, roomId: String, roomDeleted: Boolean) =
        record("room.left", actorUserId = userId, target = roomId,
            payloadJson = """{"roomId":"${jsonEscape(roomId)}","roomDeleted":$roomDeleted}""")

    // ─────────── 조회 (테스트·운영 도구용) ───────────

    open fun count(): Long = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM log_events").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    open fun findLatestByType(eventType: String, limit: Int = 50): List<EventRow> =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT event_id, actor_user_id, event_type, target, payload::text, server_ts
                FROM log_events
                WHERE event_type = ?
                ORDER BY server_ts DESC
                LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, eventType)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<EventRow>()
                    while (rs.next()) {
                        out.add(
                            EventRow(
                                eventId = rs.getObject(1) as UUID,
                                actorUserId = rs.getString(2),
                                eventType = rs.getString(3),
                                target = rs.getString(4),
                                payloadJson = rs.getString(5) ?: "{}",
                                serverTs = rs.getTimestamp(6).toInstant(),
                            ),
                        )
                    }
                    out
                }
            }
        }

    open fun deleteAll() {
        db.withConnection { conn ->
            conn.createStatement().use { st -> st.execute("DELETE FROM log_events") }
        }
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

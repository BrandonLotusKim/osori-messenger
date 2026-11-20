package com.osori.server.db

import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.time.Instant

private val logger = LoggerFactory.getLogger("com.osori.server.MessageRepository")

/**
 * 쪽지(Memo) Repository —/222.
 *
 * 모델:
 *   - memos          : 본문 1행 (server_message_id PK, from_user, title, body, sent_at, attachment_upload_id, deleted_at_by_sender)
 *   - message_recipients: 1:N 수신자 (server_message_id+recipient_id PK, delivered_at, read_at, deleted_at)
 *
 * 흐름:
 *   - 발송: saveMemo(inTransaction { insertMemo + insertRecipients(N) })
 *   - 온라인 수신자: 푸시 후 markDelivered(serverMessageId, recipientId)
 *   - 오프라인: delivered_at=null 상태로 누적. 다음 로그인 시 fetchUndeliveredForUser → 푸시 → markDelivered
 *   - 읽음: markRead
 *   - 삭제: 발신자 deleted_at_by_sender / 수신자 deleted_at 분리
 */
open class MessageRepository(private val db: Database) {

    data class MemoRow(
        val serverMessageId: String,
        val fromUserId: String,
        val recipientIds: List<String>,
        val title: String,
        val body: String,
        val sentAtMillis: Long,
        val readAtMillis: Long?,
        val hasAttachment: Boolean,
        val attachmentUploadId: String?,
    )

    open fun saveMemo(
        serverMessageId: String,
        fromUserId: String,
        recipientIds: List<String>,
        title: String,
        body: String,
        sentAt: Instant,
        attachmentUploadId: String?,
    ) {
        db.inTransaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO messages (server_message_id, from_user_id, title, body, sent_at, attachment_upload_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, serverMessageId)
                ps.setString(2, fromUserId)
                ps.setString(3, title)
                ps.setString(4, body)
                ps.setTimestamp(5, Timestamp.from(sentAt))
                if (attachmentUploadId.isNullOrEmpty()) ps.setNull(6, java.sql.Types.VARCHAR)
                else ps.setString(6, attachmentUploadId)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO message_recipients (server_message_id, recipient_id)
                VALUES (?, ?)
                """.trimIndent(),
            ).use { ps ->
                for (rid in recipientIds) {
                    ps.setString(1, serverMessageId)
                    ps.setString(2, rid)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    open fun markDelivered(serverMessageId: String, recipientId: String): Int =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                UPDATE message_recipients SET delivered_at = now()
                WHERE server_message_id = ? AND recipient_id = ? AND delivered_at IS NULL
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, serverMessageId)
                ps.setString(2, recipientId)
                ps.executeUpdate()
            }
        }

    open fun markRead(serverMessageId: String, recipientId: String): Long? =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                UPDATE message_recipients SET read_at = now()
                WHERE server_message_id = ? AND recipient_id = ? AND read_at IS NULL AND deleted_at IS NULL
                RETURNING read_at
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, serverMessageId)
                ps.setString(2, recipientId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1).time else null }
            }
        }

    open fun softDeleteBySender(serverMessageId: String, fromUserId: String): Int =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                UPDATE messages SET deleted_at_by_sender = now()
                WHERE server_message_id = ? AND from_user_id = ? AND deleted_at_by_sender IS NULL
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, serverMessageId)
                ps.setString(2, fromUserId)
                ps.executeUpdate()
            }
        }

    open fun softDeleteByRecipient(serverMessageId: String, recipientId: String): Int =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                UPDATE message_recipients SET deleted_at = now()
                WHERE server_message_id = ? AND recipient_id = ? AND deleted_at IS NULL
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, serverMessageId)
                ps.setString(2, recipientId)
                ps.executeUpdate()
            }
        }

    /** 받은쪽지함 — userId 가 수신자, 삭제 안 됨, sentAt > sinceTs. recipientIds 는 해당 쪽지의 전체 수신자(전체회신용, string_agg). */
    open fun fetchInbox(userId: String, sinceTsMillis: Long, limit: Int): List<MemoRow> =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT m.server_message_id, m.from_user_id, m.title, m.body,
                       extract(epoch from m.sent_at)*1000 AS sent_ms,
                       extract(epoch from mr.read_at)*1000 AS read_ms,
                       m.attachment_upload_id,
                       (SELECT string_agg(mr2.recipient_id, ',' ORDER BY mr2.recipient_id)
                        FROM message_recipients mr2 WHERE mr2.server_message_id = m.server_message_id) AS all_recipients
                FROM message_recipients mr
                JOIN messages m ON m.server_message_id = mr.server_message_id
                WHERE mr.recipient_id = ?
                  AND mr.deleted_at IS NULL
                  AND extract(epoch from m.sent_at)*1000 > ?
                ORDER BY m.sent_at DESC
                LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, userId)
                ps.setLong(2, sinceTsMillis)
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<MemoRow>()
                    while (rs.next()) {
                        val attachId = rs.getString(7)
                        val readMs = rs.getObject(6) as? Number
                        val recipientsCsv = rs.getString(8) ?: ""
                        out.add(
                            MemoRow(
                                serverMessageId = rs.getString(1),
                                fromUserId = rs.getString(2),
                                recipientIds = if (recipientsCsv.isEmpty()) listOf(userId) else recipientsCsv.split(','),
                                title = rs.getString(3),
                                body = rs.getString(4),
                                sentAtMillis = (rs.getObject(5) as Number).toLong(),
                                readAtMillis = readMs?.toLong(),
                                hasAttachment = !attachId.isNullOrEmpty(),
                                attachmentUploadId = attachId,
                            ),
                        )
                    }
                    out
                }
            }
        }

    /** 보낸쪽지함 — userId 가 발송자, 발송자 삭제 X. recipientIds 는 다중 집계 (string_agg). */
    open fun fetchSent(userId: String, sinceTsMillis: Long, limit: Int): List<MemoRow> =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT m.server_message_id, m.from_user_id, m.title, m.body,
                       extract(epoch from m.sent_at)*1000 AS sent_ms,
                       m.attachment_upload_id,
                       string_agg(mr.recipient_id, ',' ORDER BY mr.recipient_id) AS recipients
                FROM messages m
                LEFT JOIN message_recipients mr ON mr.server_message_id = m.server_message_id
                WHERE m.from_user_id = ?
                  AND m.deleted_at_by_sender IS NULL
                  AND extract(epoch from m.sent_at)*1000 > ?
                GROUP BY m.server_message_id
                ORDER BY m.sent_at DESC
                LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, userId)
                ps.setLong(2, sinceTsMillis)
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<MemoRow>()
                    while (rs.next()) {
                        val attachId = rs.getString(6)
                        val recipientsCsv = rs.getString(7) ?: ""
                        out.add(
                            MemoRow(
                                serverMessageId = rs.getString(1),
                                fromUserId = rs.getString(2),
                                recipientIds = if (recipientsCsv.isEmpty()) emptyList() else recipientsCsv.split(','),
                                title = rs.getString(3),
                                body = rs.getString(4),
                                sentAtMillis = (rs.getObject(5) as Number).toLong(),
                                readAtMillis = null,
                                hasAttachment = !attachId.isNullOrEmpty(),
                                attachmentUploadId = attachId,
                            ),
                        )
                    }
                    out
                }
            }
        }

    /** 다음 로그인 시 푸시할 미배달 쪽지. */
    open fun fetchUndeliveredForUser(userId: String, limit: Int = 100): List<MemoRow> =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT m.server_message_id, m.from_user_id, m.title, m.body,
                       extract(epoch from m.sent_at)*1000 AS sent_ms,
                       m.attachment_upload_id
                FROM message_recipients mr
                JOIN messages m ON m.server_message_id = mr.server_message_id
                WHERE mr.recipient_id = ?
                  AND mr.delivered_at IS NULL
                  AND mr.deleted_at IS NULL
                ORDER BY m.sent_at ASC
                LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, userId)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<MemoRow>()
                    while (rs.next()) {
                        val attachId = rs.getString(6)
                        out.add(
                            MemoRow(
                                serverMessageId = rs.getString(1),
                                fromUserId = rs.getString(2),
                                recipientIds = listOf(userId),
                                title = rs.getString(3),
                                body = rs.getString(4),
                                sentAtMillis = (rs.getObject(5) as Number).toLong(),
                                readAtMillis = null,
                                hasAttachment = !attachId.isNullOrEmpty(),
                                attachmentUploadId = attachId,
                            ),
                        )
                    }
                    out
                }
            }
        }
}

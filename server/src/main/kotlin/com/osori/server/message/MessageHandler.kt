package com.osori.server.message

import com.osori.proto.v1.*
import com.osori.server.db.MessageRepository
import com.osori.server.session.ClientSession
import com.osori.server.session.SessionManager
import io.ktor.websocket.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

private val logger = LoggerFactory.getLogger("com.osori.server.MessageHandler")

/**
 * 쪽지(Memo) 핸들러 — .
 *
 * 책임:
 *   - MessageSend → 검증 + saveMemo + 온라인 수신자에게 MessageReceived 푸시 + MessageSendAck 응답
 *   - MessageListRequest → 받은쪽지함/보낸쪽지함 페이지네이션
 *   - MessageMarkRead → 본인 받은쪽지 read_at 갱신
 *   - MessageDelete → 발신자/수신자 소프트 삭제
 *   - 로그인 직후 fetchUndeliveredForUser 로 미배달 쪽지 일괄 푸시 + markDelivered
 */
class MessageHandler(
    private val repo: MessageRepository?,
    private val sessions: SessionManager,
    /** (uploadId, userId) → 그 사용자가 올린 업로드인지. null 이면 검사하지 않음(메모리 모드·옛 테스트). */
    private val attachmentOwnedBy: ((String, String) -> Boolean)? = null,
) {

    suspend fun handleSend(session: ClientSession, sequence: Long, req: MessageSend) {
        com.osori.server.metrics.Metrics.memoSent.incrementAndGet()
        val userId = session.userId
        if (userId.isNullOrBlank()) {
            sendSendAck(session, sequence, req.clientMessageId, "", 0, MessageSendAck.Result.INVALID_REQUEST, emptyList())
            return
        }
        if (req.recipientIdsList.isEmpty() || req.title.isBlank()) {
            sendSendAck(session, sequence, req.clientMessageId, "", 0, MessageSendAck.Result.INVALID_REQUEST, emptyList())
            return
        }
        // 길이 가드 — DB 안전 마진. 클라(Title MaxLength=200, Body=10000) 와 일치.
        if (req.title.length > 200 || req.body.length > 10_000) {
            sendSendAck(session, sequence, req.clientMessageId, "", 0, MessageSendAck.Result.INVALID_REQUEST, emptyList())
            return
        }
        val r = repo
        if (r == null) {
            sendSendAck(session, sequence, req.clientMessageId, "", 0, MessageSendAck.Result.SERVER_ERROR, emptyList())
            return
        }
        val serverMessageId = UUID.randomUUID().toString()
        val sentAt = Instant.now()
        val sentAtMs = sentAt.toEpochMilli()
        val attachId = req.attachmentUploadId.ifEmpty { null }
        // 첨부는 보낸 사람 본인이 올린 파일만 — 남의 업로드 ID 를 붙여 수신자에게 다운로드 권한을 넘기는 것을 막는다.
        if (attachId != null && attachmentOwnedBy != null && !attachmentOwnedBy.invoke(attachId, userId)) {
            sendSendAck(session, sequence, req.clientMessageId, "", 0, MessageSendAck.Result.INVALID_REQUEST, emptyList())
            return
        }

        val recipientResults = mutableListOf<MessageRecipientResult>()
        val deliverables = mutableListOf<String>()
        for (rid in req.recipientIdsList.distinct()) {
            if (rid.isBlank()) continue
            deliverables.add(rid)
            recipientResults.add(
                MessageRecipientResult.newBuilder()
                    .setRecipientId(rid)
                    .setDelivered(true)
                    .build(),
            )
        }

        val ok = runCatching {
            r.saveMemo(serverMessageId, userId, deliverables, req.title, req.body, sentAt, attachId)
        }.onFailure { logger.error("memo save 실패 from={} memoId={}", userId, serverMessageId, it) }
            .isSuccess

        if (!ok) {
            sendSendAck(session, sequence, req.clientMessageId, "", 0, MessageSendAck.Result.SERVER_ERROR, emptyList())
            return
        }

        // 온라인 수신자에게 MessageReceived 푸시 + markDelivered
        for (rid in deliverables) {
            val target = sessions.get(rid) ?: continue
            val pushSeq = System.currentTimeMillis()
            val push = Envelope.newBuilder()
                .setSequence(pushSeq)
                .setMessageReceived(
                    MessageReceived.newBuilder()
                        .setServerMessageId(serverMessageId)
                        .setFromUserId(userId)
                        .setRecipientId(rid)
                        .setTitle(req.title)
                        .setBody(req.body)
                        .setSentAt(sentAtMs)
                        .setAttachmentUploadId(attachId ?: "")
                        .setReadAt(0)
                        .build(),
                )
                .build()
            runCatching {
                target.send(Frame.Binary(true, push.toByteArray()))
                r.markDelivered(serverMessageId, rid)
                com.osori.server.metrics.Metrics.memoPushed.incrementAndGet()
            }.onFailure { logger.warn("memo push 실패 rid={} memoId={}", rid, serverMessageId, it) }
        }

        sendSendAck(session, sequence, req.clientMessageId, serverMessageId, sentAtMs, MessageSendAck.Result.OK, recipientResults)
        logger.info("memo 발송 from={} memoId={} recipients={}", userId, serverMessageId, deliverables.size)
    }

    suspend fun handleList(session: ClientSession, sequence: Long, req: MessageListRequest) {
        val userId = session.userId
        if (userId.isNullOrBlank()) {
            sendListResponse(session, sequence, req.clientReqId, MessageListResponse.Status.SERVER_ERROR, emptyList(), false)
            return
        }
        val r = repo
        if (r == null) {
            sendListResponse(session, sequence, req.clientReqId, MessageListResponse.Status.SERVER_ERROR, emptyList(), false)
            return
        }
        val limit = if (req.limit <= 0) 50 else req.limit.coerceAtMost(200)
        val rows = runCatching {
            if (req.box == MessageListRequest.Box.SENT) r.fetchSent(userId, req.sinceTs, limit + 1)
            else r.fetchInbox(userId, req.sinceTs, limit + 1)
        }.getOrElse {
            logger.error("memo list 실패 userId={} box={}", userId, req.box, it)
            sendListResponse(session, sequence, req.clientReqId, MessageListResponse.Status.SERVER_ERROR, emptyList(), false)
            return
        }
        val hasMore = rows.size > limit
        val trimmed = if (hasMore) rows.take(limit) else rows
        val entries = trimmed.map { row ->
            MessageEntry.newBuilder()
                .setServerMessageId(row.serverMessageId)
                .setFromUserId(row.fromUserId)
                .addAllRecipientIds(row.recipientIds)
                .setTitle(row.title)
                .setBody(row.body)
                .setSentAt(row.sentAtMillis)
                .setReadAt(row.readAtMillis ?: 0L)
                .setHasAttachment(row.hasAttachment)
                .setAttachmentUploadId(row.attachmentUploadId ?: "")
                .build()
        }
        sendListResponse(session, sequence, req.clientReqId, MessageListResponse.Status.OK, entries, hasMore)
    }

    suspend fun handleMarkRead(session: ClientSession, sequence: Long, req: MessageMarkRead) {
        val userId = session.userId
        if (userId.isNullOrBlank() || req.serverMessageId.isBlank()) {
            sendMarkReadAck(session, sequence, req.clientReqId, req.serverMessageId, 0, MessageMarkReadAck.Result.SERVER_ERROR)
            return
        }
        val r = repo
        if (r == null) {
            sendMarkReadAck(session, sequence, req.clientReqId, req.serverMessageId, 0, MessageMarkReadAck.Result.SERVER_ERROR)
            return
        }
        val readMs = runCatching { r.markRead(req.serverMessageId, userId) }.getOrNull()
        if (readMs == null) {
            sendMarkReadAck(session, sequence, req.clientReqId, req.serverMessageId, 0, MessageMarkReadAck.Result.NOT_FOUND)
            return
        }
        com.osori.server.metrics.Metrics.memoRead.incrementAndGet()
        sendMarkReadAck(session, sequence, req.clientReqId, req.serverMessageId, readMs, MessageMarkReadAck.Result.OK)
    }

    suspend fun handleDelete(session: ClientSession, sequence: Long, req: MessageDelete) {
        val userId = session.userId
        if (userId.isNullOrBlank() || req.serverMessageId.isBlank()) {
            sendDeleteAck(session, sequence, req.clientReqId, req.serverMessageId, MessageDeleteAck.Result.SERVER_ERROR)
            return
        }
        val r = repo
        if (r == null) {
            sendDeleteAck(session, sequence, req.clientReqId, req.serverMessageId, MessageDeleteAck.Result.SERVER_ERROR)
            return
        }
        // 발신자 시도 먼저, 실패 시 수신자 시도
        val asSender = runCatching { r.softDeleteBySender(req.serverMessageId, userId) }.getOrDefault(0)
        if (asSender > 0) {
            com.osori.server.metrics.Metrics.memoDeleted.incrementAndGet()
            sendDeleteAck(session, sequence, req.clientReqId, req.serverMessageId, MessageDeleteAck.Result.OK)
            return
        }
        val asRecipient = runCatching { r.softDeleteByRecipient(req.serverMessageId, userId) }.getOrDefault(0)
        val result = if (asRecipient > 0) {
            com.osori.server.metrics.Metrics.memoDeleted.incrementAndGet()
            MessageDeleteAck.Result.OK
        } else MessageDeleteAck.Result.NOT_OWNER
        sendDeleteAck(session, sequence, req.clientReqId, req.serverMessageId, result)
    }

    /** 로그인 직후 호출 — 오프라인 누적된 미배달 쪽지를 일괄 푸시 + markDelivered. */
    suspend fun flushUndelivered(userId: String) {
        val r = repo ?: return
        val target = sessions.get(userId) ?: return
        val rows = runCatching { r.fetchUndeliveredForUser(userId) }.getOrElse {
            logger.error("memo flushUndelivered 실패 userId={}", userId, it); return
        }
        for (row in rows) {
            val push = Envelope.newBuilder()
                .setSequence(System.currentTimeMillis())
                .setMessageReceived(
                    MessageReceived.newBuilder()
                        .setServerMessageId(row.serverMessageId)
                        .setFromUserId(row.fromUserId)
                        .setRecipientId(userId)
                        .setTitle(row.title)
                        .setBody(row.body)
                        .setSentAt(row.sentAtMillis)
                        .setAttachmentUploadId(row.attachmentUploadId ?: "")
                        .setReadAt(0)
                        .build(),
                )
                .build()
            runCatching {
                target.send(Frame.Binary(true, push.toByteArray()))
                r.markDelivered(row.serverMessageId, userId)
            }
        }
        if (rows.isNotEmpty()) {
            logger.info("memo 미배달 flush userId={} count={}", userId, rows.size)
        }
    }

    private suspend fun sendSendAck(
        session: ClientSession, sequence: Long, clientMessageId: String,
        serverMessageId: String, sentAtMs: Long, result: MessageSendAck.Result,
        recipientResults: List<MessageRecipientResult>,
    ) {
        val ack = MessageSendAck.newBuilder()
            .setClientMessageId(clientMessageId)
            .setServerMessageId(serverMessageId)
            .setSentAt(sentAtMs)
            .setResult(result)
            .addAllRecipientResults(recipientResults)
            .build()
        val env = Envelope.newBuilder().setSequence(sequence).setMessageSendAck(ack).build()
        session.send(Frame.Binary(true, env.toByteArray()))
    }

    private suspend fun sendListResponse(
        session: ClientSession, sequence: Long, clientReqId: String,
        status: MessageListResponse.Status, entries: List<MessageEntry>, hasMore: Boolean,
    ) {
        val res = MessageListResponse.newBuilder()
            .setClientReqId(clientReqId).setStatus(status).addAllEntries(entries).setHasMore(hasMore).build()
        val env = Envelope.newBuilder().setSequence(sequence).setMessageListRes(res).build()
        session.send(Frame.Binary(true, env.toByteArray()))
    }

    private suspend fun sendMarkReadAck(
        session: ClientSession, sequence: Long, clientReqId: String,
        serverMessageId: String, readAtMs: Long, result: MessageMarkReadAck.Result,
    ) {
        val ack = MessageMarkReadAck.newBuilder()
            .setClientReqId(clientReqId).setServerMessageId(serverMessageId).setReadAt(readAtMs).setResult(result).build()
        val env = Envelope.newBuilder().setSequence(sequence).setMessageMarkReadAck(ack).build()
        session.send(Frame.Binary(true, env.toByteArray()))
    }

    private suspend fun sendDeleteAck(
        session: ClientSession, sequence: Long, clientReqId: String,
        serverMessageId: String, result: MessageDeleteAck.Result,
    ) {
        val ack = MessageDeleteAck.newBuilder()
            .setClientReqId(clientReqId).setServerMessageId(serverMessageId).setResult(result).build()
        val env = Envelope.newBuilder().setSequence(sequence).setMessageDeleteAck(ack).build()
        session.send(Frame.Binary(true, env.toByteArray()))
    }
}

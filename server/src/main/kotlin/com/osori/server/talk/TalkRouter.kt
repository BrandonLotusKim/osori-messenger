package com.osori.server.talk

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.TalkReceived
import com.osori.proto.v1.TalkSend
import com.osori.proto.v1.TalkSendAck
import com.osori.server.db.TalkRepository
import com.osori.server.session.ClientSession
import com.osori.server.session.SessionManager
import io.ktor.websocket.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = LoggerFactory.getLogger("com.osori.server.TalkRouter")

/**
 * 1:1 메시지 라우팅.
 *
 * 흐름:
 *   1. 발신자 세션의 userId 확인
 *   2. 수신자 세션 조회
 *   3. 온라인 → messages INSERT + TalkReceived 즉시 배달 + DELIVERED ACK
 *   4. 오프라인 → messages + message_outbox INSERT (트랜잭션) + QUEUED ACK
 *
 * talkRepository 가 null 이면 메모리 전용 모드 (Phase 2 MVP 와 동일 동작, 영속화 없음).
 */
/**
 * 발신 빈도 제한 정책.
 */
sealed interface RateLimitPolicy {
    /** @return true = 통과, false = RATE_LIMITED */
    fun check(userId: String): Boolean

    /** 비활성 — 모든 요청 통과. 기본값 (기존 동작 그대로). */
    object Disabled : RateLimitPolicy {
        override fun check(userId: String): Boolean = true
    }

    /**
     * 사용자별 토큰 버킷.
     * @param capacity 버스트 허용량 (한 순간 최대 N 건)
     * @param refillPerSecond 초당 refill (지속 허용량)
     * @param exemptUserIds 차등 — 이 집합에 포함된 사용자는 항상 통과 (관리자/시스템 계정)
     */
    class PerUserTokenBucket(
        private val capacity: Int,
        private val refillPerSecond: Double,
        private val clock: () -> Long = System::currentTimeMillis,
        private val exemptUserIds: Set<String> = emptySet(),
    ) : RateLimitPolicy {
        private val buckets = java.util.concurrent.ConcurrentHashMap<String, TokenBucket>()

        override fun check(userId: String): Boolean {
            if (userId in exemptUserIds) return true
            return buckets.computeIfAbsent(userId) {
                TokenBucket(capacity, refillPerSecond, clock)
            }.tryConsume()
        }

        /** 같은 설정(용량·속도·면제)으로 버킷만 새로 갖는 정책 — 요청 종류별 제한용. */
        fun copyForNewBucket(): PerUserTokenBucket =
            PerUserTokenBucket(capacity, refillPerSecond, clock, exemptUserIds)
    }
}

class TalkRouter(
    private val sessions: SessionManager,
    private val talkRepository: TalkRepository? = null,
    private val rateLimit: RateLimitPolicy = RateLimitPolicy.Disabled,
    private val auditRepository: com.osori.server.audit.AuditRepository? = null, //
    private val pluginRegistry: com.osori.server.plugin.PluginRegistry? = null, //
) {

    // 서버가 능동적으로 보내는 프레임용 시퀀스
    private val serverSequence = AtomicLong(1_000_000)

    /**
     * 클라이언트가 발급한 client_msg_id 를 키로 한 멱등 캐시.
     *
     * 같은 (fromUser, clientTalkId) 가 두 번째 들어오면 기존 ack 를 그대로 재전송하고
     * DB / 수신자 배달 / SessionManager 호출은 건너뛴다. 메모리 한정 캐시이므로 서버 재기동
     * 시 사라진다 (분산/영속 멱등이 필요해지면 messages 테이블 unique 인덱스로 이관).
     *
     * 키: "fromUser|clientTalkId"
     */
    private val idempotencyCache = ConcurrentHashMap<String, CachedAck>()

    private data class CachedAck(
        val serverTalkId: String,
        val serverTs: Long,
        val result: TalkSendAck.Result,
    )

    private fun cacheKey(fromUser: String, clientTalkId: String): String =
        "$fromUser|$clientTalkId"

    suspend fun handleSend(
        sender: ClientSession,
        sequence: Long,
        req: TalkSend,
    ) {
        val fromUser = sender.userId
        if (fromUser == null) {
            logger.warn("미인증 세션의 메시지 전송 시도 session={}", sender.id)
            sendAck(sender, sequence, req.clientTalkId, TalkSendAck.Result.SERVER_ERROR)
            return
        }

        com.osori.server.metrics.Metrics.messagesSent.incrementAndGet()
        if (req.toUserId.isBlank()) {
            sendAck(sender, sequence, req.clientTalkId, TalkSendAck.Result.INVALID_TARGET)
            return
        }

        // 본문 최대 길이 가드 — DB text 컬럼 안전 마진. 클라 입력박스도 MaxLength=4000 으로 가드되지만
        // 직접 WS 프레임 조작 가능성에 대비.
        if (req.text.length > MAX_TEXT_LENGTH) {
            logger.warn("메시지 길이 초과 from={} length={}", fromUser, req.text.length)
            sendAck(sender, sequence, req.clientTalkId, TalkSendAck.Result.INVALID_TARGET)
            return
        }

        // rate limit: 비활성 시 항상 통과. 활성 시 per-user 토큰 버킷.
        if (!rateLimit.check(fromUser)) {
            logger.info("rate limit 초과 from={}", fromUser)
            com.osori.server.metrics.Metrics.messagesRateLimit.incrementAndGet()
            sendAck(sender, sequence, req.clientTalkId, TalkSendAck.Result.RATE_LIMITED)
            return
        }

        // 멱등 검사: 같은 (fromUser, clientTalkId) 가 이미 처리됐다면 기존 ack 재전송
        if (req.clientTalkId.isNotBlank()) {
            val cached = idempotencyCache[cacheKey(fromUser, req.clientTalkId)]
            if (cached != null) {
                logger.info(
                    "멱등 캐시 hit, ack 재전송 from={} clientTalkId={} serverTalkId={}",
                    fromUser, req.clientTalkId, cached.serverTalkId,
                )
                sendAck(
                    sender, sequence, req.clientTalkId,
                    cached.result, cached.serverTalkId, cached.serverTs,
                )
                return
            }
        }

        val serverMsgUuid = UUID.randomUUID()
        val serverTalkId = serverMsgUuid.toString()
        val serverTsMillis = System.currentTimeMillis()
        val serverTsInstant = Instant.ofEpochMilli(serverTsMillis)

        val recipient = sessions.get(req.toUserId)

        if (recipient == null) {
            // 오프라인 → 본문 + 아웃박스 저장 (DB 연동 시)
            val persisted = persistOffline(
                messageId = serverMsgUuid,
                fromUser = fromUser,
                toUser = req.toUserId,
                text = req.text,
                serverTs = serverTsInstant,
            )
            if (!persisted) {
                logger.info(
                    "수신자 오프라인 (메모리 모드) from={} to={} msgId={}",
                    fromUser, req.toUserId, serverTalkId,
                )
            }
            com.osori.server.metrics.Metrics.messagesQueued.incrementAndGet()
            sendAck(
                sender, sequence, req.clientTalkId,
                TalkSendAck.Result.QUEUED, serverTalkId, serverTsMillis,
            )
            cacheIfPresent(fromUser, req.clientTalkId, serverTalkId, serverTsMillis, TalkSendAck.Result.QUEUED)
            return
        }

        // 온라인 → 본문 저장 + 즉시 배달
        persistOnline(
            messageId = serverMsgUuid,
            fromUser = fromUser,
            toUser = req.toUserId,
            text = req.text,
            serverTs = serverTsInstant,
        )

        val notice = TalkReceived.newBuilder()
            .setServerTalkId(serverTalkId)
            .setFromUserId(fromUser)
            .setToUserId(req.toUserId)
            .setText(req.text)
            .setServerTs(serverTsMillis)
            .setReplyToTalkId(req.replyToTalkId)
            .build()

        val notifyEnvelope = Envelope.newBuilder()
            .setSequence(serverSequence.incrementAndGet())
            .setTalkReceived(notice)
            .build()

        recipient.send(Frame.Binary(true, notifyEnvelope.toByteArray()))
        com.osori.server.metrics.Metrics.messagesDelivered.incrementAndGet()

        sendAck(
            sender, sequence, req.clientTalkId,
            TalkSendAck.Result.DELIVERED, serverTalkId, serverTsMillis,
        )
        cacheIfPresent(fromUser, req.clientTalkId, serverTalkId, serverTsMillis, TalkSendAck.Result.DELIVERED)

        logger.info(
            "메시지 배달 완료 from={} to={} msgId={}",
            fromUser, req.toUserId, serverTalkId,
        )
    }

    private fun cacheIfPresent(
        fromUser: String,
        clientTalkId: String,
        serverTalkId: String,
        serverTs: Long,
        result: TalkSendAck.Result,
    ) {
        if (clientTalkId.isBlank()) return
        idempotencyCache[cacheKey(fromUser, clientTalkId)] =
            CachedAck(serverTalkId, serverTs, result)
    }

    companion object {
        /** 메시지 본문 최대 길이 — DB text 안전 마진. 클라(WPF MaxLength=4000) 와 일치. */
        const val MAX_TEXT_LENGTH = 4000
    }

    /** @return true 이면 DB 에 저장됨, false 이면 메모리 모드라 저장 생략 */
    private fun persistOnline(
        messageId: UUID,
        fromUser: String,
        toUser: String,
        text: String,
        serverTs: Instant,
    ): Boolean {
        val repo = talkRepository ?: return false
        val ok = runCatching {
            repo.insertMessage(messageId, fromUser, toUser, text, serverTs)
            true
        }.onFailure { t ->
            logger.error("온라인 메시지 저장 실패 msgId={}", messageId, t)
        }.getOrDefault(false)
        if (ok) {
            val bytes = text.toByteArray().size
            auditRepository?.recordMessageSent(fromUser, toUser, messageId.toString(), bytes)
            pluginRegistry?.fireMessageSent(
                com.osori.server.plugin.MessageSentEvent(
                    fromUserId = fromUser,
                    toUserId = toUser,
                    serverTalkId = messageId.toString(),
                    bytes = bytes,
                    queued = false,
                    timestamp = serverTs,
                ),
            )
        }
        return ok
    }

    private fun persistOffline(
        messageId: UUID,
        fromUser: String,
        toUser: String,
        text: String,
        serverTs: Instant,
    ): Boolean {
        val repo = talkRepository ?: return false
        val ok = runCatching {
            repo.saveOfflineMessage(messageId, fromUser, toUser, text, serverTs)
            true
        }.onFailure { t ->
            logger.error("오프라인 메시지 저장 실패 msgId={}", messageId, t)
        }.getOrDefault(false)
        if (ok) {
            val bytes = text.toByteArray().size
            auditRepository?.recordMessageSent(fromUser, toUser, messageId.toString(), bytes)
            pluginRegistry?.fireMessageSent(
                com.osori.server.plugin.MessageSentEvent(
                    fromUserId = fromUser,
                    toUserId = toUser,
                    serverTalkId = messageId.toString(),
                    bytes = bytes,
                    queued = true,
                    timestamp = serverTs,
                ),
            )
        }
        return ok
    }

    private suspend fun sendAck(
        sender: ClientSession,
        sequence: Long,
        clientTalkId: String,
        result: TalkSendAck.Result,
        serverTalkId: String = "",
        serverTs: Long = System.currentTimeMillis(),
    ) {
        val ack = TalkSendAck.newBuilder()
            .setClientTalkId(clientTalkId)
            .setServerTalkId(serverTalkId)
            .setServerTs(serverTs)
            .setResult(result)
            .build()

        val envelope = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkSendAck(ack)
            .build()

        sender.send(Frame.Binary(true, envelope.toByteArray()))
    }
}

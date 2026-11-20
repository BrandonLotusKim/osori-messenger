package com.osori.server.session

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.PresenceEvent
import io.ktor.websocket.*
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

private val logger = LoggerFactory.getLogger("com.osori.server.PresenceBroadcaster")

/**
 * 사용자 온라인/오프라인 변경을 다른 접속자에게 알린다.
 *
 * 책임 분리:
 *  - 로그인 성공 시 LoginHandler 가 `notifyOnline` 호출 + 신규 접속자에게 현재 온라인 목록 push
 *  - 세션 종료 시 WebSocketHandler 가 `notifyOffline` 호출
 *
 * 시퀀스: 서버 능동 푸시 영역(3_000_000+) 사용. TalkOutboxFlusher(2_000_000+) 와 분리.
 */
class PresenceBroadcaster(
    private val sessions: SessionManager,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sequence = AtomicLong(3_000_000L)

    /** userId 가 방금 온라인이 됐음을 다른 접속자들에게 알린다. */
    suspend fun notifyOnline(userId: String) {
        broadcast(buildEvent(userId, PresenceEvent.State.ONLINE), exceptUserId = userId)
    }

    /** userId 가 방금 오프라인이 됐음을 다른 접속자들에게 알린다. */
    suspend fun notifyOffline(userId: String) {
        broadcast(buildEvent(userId, PresenceEvent.State.OFFLINE), exceptUserId = userId)
    }

    /**
     * 신규 로그인 사용자에게 현재 온라인 사용자 목록을 PresenceEvent 시리즈로 전송.
     * 자기 자신은 제외.
     */
    suspend fun sendInitialSnapshot(target: ClientSession, userId: String) {
        val others = sessions.onlineUserIds().filter { it != userId }
        for (other in others) {
            val env = wrap(buildEvent(other, PresenceEvent.State.ONLINE))
            runCatching { target.send(Frame.Binary(true, env.toByteArray())) }
                .onFailure { logger.debug("초기 presence snapshot 송신 실패 to={}", userId, it) }
        }
        if (others.isNotEmpty()) {
            logger.debug("초기 presence snapshot 전송 to={} count={}", userId, others.size)
        }
    }

    private suspend fun broadcast(event: PresenceEvent, exceptUserId: String) {
        val bytes = wrap(event).toByteArray()
        var sent = 0
        for (s in sessions.snapshot()) {
            val uid = s.userId
            if (uid.isNullOrBlank() || uid == exceptUserId) continue
            runCatching {
                s.send(Frame.Binary(true, bytes))
                sent += 1
            }.onFailure { logger.debug("presence broadcast 실패 to={}", uid, it) }
        }
        if (sent > 0) {
            logger.info(
                "presence broadcast user={} state={} → {} 명",
                event.userId, event.state, sent,
            )
        }
    }

    private fun buildEvent(userId: String, state: PresenceEvent.State): PresenceEvent =
        PresenceEvent.newBuilder()
            .setUserId(userId)
            .setState(state)
            .setServerTs(clock())
            .build()

    private fun wrap(event: PresenceEvent): Envelope =
        Envelope.newBuilder()
            .setSequence(sequence.incrementAndGet())
            .setPresenceEvent(event)
            .build()
}

package com.osori.server.session

import io.ktor.websocket.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/**
 * 유휴 세션 리퍼.
 *
 * 주기적으로 세션 스냅샷을 순회해 `lastSeenAtMillis` 가 idleTimeoutMillis 를 초과하면
 * NORMAL close 로 강제 종료하고 SessionManager 에서 제거한다.
 *
 * lastSeenAtMillis 는 WebSocketHandler 가 매 프레임 수신 시 갱신하며, 이 갱신 경로는
 * 클라이언트가 heartbeat 를 주기적으로 보내는 것에 의존한다 (MVP 기본값 30s 간격 가정).
 *
 * 테스트 시엔 clock 과 coroutineScope 를 주입해 가상 시간 실험 가능.
 */
class SessionReaper(
    private val sessions: SessionManager,
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineName("osori-session-reaper"),
    ),
) {
    private val logger = LoggerFactory.getLogger("com.osori.server.SessionReaper")

    @Volatile
    private var job: Job? = null

    fun start() {
        if (job != null) {
            logger.debug("리퍼 이미 실행 중, start 무시")
            return
        }
        logger.info(
            "세션 리퍼 시작 idleTimeout={}ms interval={}ms",
            idleTimeoutMillis,
            intervalMillis,
        )
        job = scope.launch {
            while (isActive) {
                delay(intervalMillis)
                runCatching { reapOnce() }
                    .onFailure { logger.warn("리퍼 사이클 중 오류", it) }
            }
        }
    }

    /**
     * 즉시 1회 reap 사이클 수행. 테스트에서 직접 호출 가능.
     * @return 정리된 세션 수
     */
    suspend fun reapOnce(): Int {
        val now = clock()
        val victims = sessions.snapshot().filter { now - it.lastSeenAtMillis > idleTimeoutMillis }
        for (v in victims) {
            val idleMs = now - v.lastSeenAtMillis
            logger.info("유휴 세션 close userId={} idleMs={}", v.userId, idleMs)
            runCatching {
                v.close(CloseReason(CloseReason.Codes.NORMAL, "idle timeout"))
            }.onFailure { logger.debug("close 중 오류 무시 userId={}", v.userId, it) }
            sessions.remove(v)
        }
        return victims.size
    }

    fun stop() {
        job?.cancel()
        job = null
        logger.info("세션 리퍼 정지")
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MILLIS: Long = 90_000
        const val DEFAULT_INTERVAL_MILLIS: Long = 30_000
    }
}

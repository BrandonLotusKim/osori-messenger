package com.osori.server.talk

/**
 * 단순 토큰 버킷 — 송신 빈도 제한용.
 *
 * 고정 용량 + 초당 일정 속도로 refill. 매 tryConsume 시점에 elapsed 시간만큼
 * 선형 refill 계산 후 토큰 1개 차감 시도.
 *
 * 스레드 안전 (synchronized). 사용자당 1 인스턴스 권장.
 *
 * @param capacity 최대 토큰 수 (버스트 허용량)
 * @param refillPerSecond 초당 refill 되는 토큰 수 (float 허용, 예: 0.5 = 2초에 1개)
 * @param clock 시각 공급자 (테스트 주입용)
 */
class TokenBucket(
    private val capacity: Int,
    private val refillPerSecond: Double,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
        require(refillPerSecond > 0) { "refillPerSecond must be > 0, was $refillPerSecond" }
    }

    private var tokens: Double = capacity.toDouble()
    private var lastRefillMs: Long = clock()
    private val lock = Any()

    /** 토큰 1개 차감 시도. 남은 게 없으면 false. */
    fun tryConsume(): Boolean = synchronized(lock) {
        refillTokens()
        if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }

    /** 현재 남은 토큰 (소수점 가능, 테스트/모니터링용). */
    fun available(): Double = synchronized(lock) {
        refillTokens()
        tokens
    }

    private fun refillTokens() {
        val now = clock()
        val elapsedMs = now - lastRefillMs
        if (elapsedMs <= 0) return
        val add = (elapsedMs / 1000.0) * refillPerSecond
        tokens = (tokens + add).coerceAtMost(capacity.toDouble())
        lastRefillMs = now
    }
}

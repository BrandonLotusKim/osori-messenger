package com.osori.server.talk

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenBucketTest {

    @Test
    fun `초기 상태는 capacity 만큼의 토큰을 가진다`() {
        var now = 0L
        val bucket = TokenBucket(capacity = 5, refillPerSecond = 5.0, clock = { now })
        assertEquals(5.0, bucket.available(), 0.001)
    }

    @Test
    fun `capacity 만큼 연속 소비 가능, 이후는 거부`() {
        var now = 0L
        val bucket = TokenBucket(capacity = 3, refillPerSecond = 3.0, clock = { now })
        assertTrue(bucket.tryConsume())
        assertTrue(bucket.tryConsume())
        assertTrue(bucket.tryConsume())
        assertFalse(bucket.tryConsume(), "4번째는 거부")
    }

    @Test
    fun `시간 경과 후 refill 되면 다시 소비 가능`() {
        var now = 0L
        val bucket = TokenBucket(capacity = 2, refillPerSecond = 2.0, clock = { now })
        assertTrue(bucket.tryConsume())
        assertTrue(bucket.tryConsume())
        assertFalse(bucket.tryConsume())

        // 0.5초 경과 → 0.5 * 2 = 1 토큰 refill
        now = 500L
        assertTrue(bucket.tryConsume())
        assertFalse(bucket.tryConsume(), "1토큰만 refill 됐으므로 다음은 거부")
    }

    @Test
    fun `refill 누적은 capacity 상한을 초과하지 않는다`() {
        var now = 0L
        val bucket = TokenBucket(capacity = 2, refillPerSecond = 10.0, clock = { now })
        bucket.tryConsume()  // 1 소비
        bucket.tryConsume()  // 2 소비 → 0 남음

        now = 10_000L  // 10초 = 100 토큰 추가될 수 있지만 capacity=2 로 캡
        assertEquals(2.0, bucket.available(), 0.001)
    }

    @Test
    fun `PerUserTokenBucket 은 사용자마다 독립적`() {
        var now = 0L
        val policy = RateLimitPolicy.PerUserTokenBucket(
            capacity = 2,
            refillPerSecond = 2.0,
            clock = { now },
        )
        assertTrue(policy.check("alice"))
        assertTrue(policy.check("alice"))
        assertFalse(policy.check("alice"), "alice 소진")
        // bob 은 별개
        assertTrue(policy.check("bob"))
        assertTrue(policy.check("bob"))
        assertFalse(policy.check("bob"))
    }

    @Test
    fun `Disabled 정책은 항상 통과`() {
        val policy = RateLimitPolicy.Disabled
        repeat(1_000) {
            assertTrue(policy.check("anyone"))
        }
    }
}

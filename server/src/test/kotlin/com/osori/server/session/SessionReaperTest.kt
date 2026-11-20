package com.osori.server.session

import com.osori.server.testutil.FakeClientSession
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionReaperTest {

    @Test
    fun `reapOnce 빈 매니저이면 0 반환`() = runBlocking {
        val sessions = SessionManager()
        val now = 10_000L
        val reaper = SessionReaper(
            sessions = sessions,
            idleTimeoutMillis = 1_000L,
            intervalMillis = 60_000L,
            clock = { now },
        )
        assertEquals(0, reaper.reapOnce())
    }

    @Test
    fun `idle 초과한 세션만 close 되고 정상 세션은 유지`() = runBlocking {
        val sessions = SessionManager()

        val stale = FakeClientSession().apply { lastSeenAtMillis = 0L }
        val fresh = FakeClientSession().apply { lastSeenAtMillis = 9_500L }

        sessions.bind("stale-user", stale)
        sessions.bind("fresh-user", fresh)

        val now = 10_000L
        val reaper = SessionReaper(
            sessions = sessions,
            idleTimeoutMillis = 1_000L,
            intervalMillis = 60_000L,
            clock = { now },
        )

        val reaped = reaper.reapOnce()

        assertEquals(1, reaped)
        assertTrue(stale.closed, "stale 세션은 close 되어야 한다")
        assertFalse(fresh.closed, "fresh 세션은 유지되어야 한다")
        assertEquals(1, sessions.count(), "fresh 만 남아야 한다")
        assertEquals("fresh-user", sessions.onlineUserIds().single())
    }

    @Test
    fun `close 에서 예외가 나도 다음 세션 처리는 계속된다`() = runBlocking {
        val sessions = SessionManager()

        val throwing = ThrowingOnCloseSession().apply { lastSeenAtMillis = 0L }
        val alsoStale = FakeClientSession().apply { lastSeenAtMillis = 0L }

        sessions.bind("throwing-user", throwing)
        sessions.bind("also-stale-user", alsoStale)

        val now = 10_000L
        val reaper = SessionReaper(
            sessions = sessions,
            idleTimeoutMillis = 1_000L,
            intervalMillis = 60_000L,
            clock = { now },
        )

        val reaped = reaper.reapOnce()

        assertEquals(2, reaped, "두 세션 모두 reap 대상으로 카운트")
        assertTrue(alsoStale.closed, "정상 세션은 close 되어야 한다")
        assertEquals(0, sessions.count(), "둘 다 SessionManager 에서 제거")
    }

    private class ThrowingOnCloseSession : ClientSession {
        override val id: Long = 99_999L
        override var userId: String? = null
        override var lastSeenAtMillis: Long = System.currentTimeMillis()
        override suspend fun send(frame: io.ktor.websocket.Frame) = Unit
        override suspend fun close(reason: io.ktor.websocket.CloseReason) {
            throw IllegalStateException("boom")
        }
    }

    @Test
    fun `경계값 — 정확히 idleTimeout 은 reap 대상 아님`() = runBlocking {
        val sessions = SessionManager()
        val boundary = FakeClientSession().apply { lastSeenAtMillis = 9_000L }
        sessions.bind("boundary-user", boundary)

        val now = 10_000L
        val reaper = SessionReaper(
            sessions = sessions,
            idleTimeoutMillis = 1_000L, // 1000ms 딱 경과는 '초과' 아님
            intervalMillis = 60_000L,
            clock = { now },
        )

        assertEquals(0, reaper.reapOnce())
        assertFalse(boundary.closed)
        assertEquals(1, sessions.count())
    }
}

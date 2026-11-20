package com.osori.server.session

import com.osori.server.testutil.FakeClientSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SessionManagerTest {

    @Test
    fun `bind new user returns null`() {
        val sessions = SessionManager()
        val s = FakeClientSession()
        val prev = sessions.bind("alice", s)

        assertNull(prev)
        assertEquals("alice", s.userId)
        assertSame(s, sessions.get("alice"))
        assertEquals(1, sessions.count())
    }

    @Test
    fun `bind duplicate returns existing session`() {
        val sessions = SessionManager()
        val s1 = FakeClientSession()
        val s2 = FakeClientSession()
        sessions.bind("alice", s1)

        val prev = sessions.bind("alice", s2)

        assertSame(s1, prev)
        assertSame(s2, sessions.get("alice"))
        assertEquals(1, sessions.count())
    }

    @Test
    fun `remove drops session`() {
        val sessions = SessionManager()
        val s = FakeClientSession()
        sessions.bind("alice", s)

        sessions.remove(s)

        assertNull(sessions.get("alice"))
        assertEquals(0, sessions.count())
    }

    @Test
    fun `remove only affects current bound instance`() {
        val sessions = SessionManager()
        val s1 = FakeClientSession()
        val s2 = FakeClientSession()
        sessions.bind("alice", s1)
        // s2 는 같은 userId 를 가졌지만 sessions 에 바인드되지 않은 스트레이 세션
        s2.userId = "alice"

        sessions.remove(s2)

        assertSame(s1, sessions.get("alice"))
        assertEquals(1, sessions.count())
    }

    @Test
    fun `count tracks multiple users independently`() {
        val sessions = SessionManager()
        val a = FakeClientSession()
        val b = FakeClientSession()

        sessions.bind("alice", a)
        sessions.bind("bob", b)

        assertEquals(2, sessions.count())
        assertSame(a, sessions.get("alice"))
        assertSame(b, sessions.get("bob"))
    }

    @Test
    fun `get for unknown user returns null`() {
        val sessions = SessionManager()
        assertNull(sessions.get("ghost"))
        assertEquals(0, sessions.count())
    }
}

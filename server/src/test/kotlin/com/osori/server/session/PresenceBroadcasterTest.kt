package com.osori.server.session

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.PresenceEvent
import com.osori.server.testutil.FakeClientSession
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresenceBroadcasterTest {

    @Test
    fun `notifyOnline 은 자기 자신을 제외한 모든 접속자에게 ONLINE 송신`() = runBlocking {
        val sessions = SessionManager()
        val alice = FakeClientSession()
        val bob = FakeClientSession()
        val carol = FakeClientSession()
        sessions.bind("alice", alice)
        sessions.bind("bob", bob)
        sessions.bind("carol", carol)

        val broadcaster = PresenceBroadcaster(sessions, clock = { 1_000L })
        broadcaster.notifyOnline("alice")

        assertEquals(0, alice.sentFrames.size, "자기 자신은 제외")
        assertEquals(1, bob.sentFrames.size)
        assertEquals(1, carol.sentFrames.size)

        val env = Envelope.parseFrom((bob.sentFrames.single() as Frame.Binary).readBytes())
        assertEquals(Envelope.PayloadCase.PRESENCE_EVENT, env.payloadCase)
        assertEquals("alice", env.presenceEvent.userId)
        assertEquals(PresenceEvent.State.ONLINE, env.presenceEvent.state)
        assertEquals(1_000L, env.presenceEvent.serverTs)
    }

    @Test
    fun `notifyOffline 도 동일 규칙으로 OFFLINE 송신`() = runBlocking {
        val sessions = SessionManager()
        val alice = FakeClientSession()
        val bob = FakeClientSession()
        sessions.bind("alice", alice)
        sessions.bind("bob", bob)

        val broadcaster = PresenceBroadcaster(sessions, clock = { 5_000L })
        broadcaster.notifyOffline("alice")

        assertEquals(0, alice.sentFrames.size)
        val env = Envelope.parseFrom((bob.sentFrames.single() as Frame.Binary).readBytes())
        assertEquals(PresenceEvent.State.OFFLINE, env.presenceEvent.state)
        assertEquals("alice", env.presenceEvent.userId)
    }

    @Test
    fun `sendInitialSnapshot 은 다른 온라인 사용자 목록을 신규 접속자에게 전달`() = runBlocking {
        val sessions = SessionManager()
        val alice = FakeClientSession()
        val bob = FakeClientSession()
        val carol = FakeClientSession()
        sessions.bind("alice", alice)
        sessions.bind("bob", bob)
        sessions.bind("carol", carol)

        val broadcaster = PresenceBroadcaster(sessions, clock = { 9_000L })
        broadcaster.sendInitialSnapshot(alice, "alice")

        assertEquals(2, alice.sentFrames.size, "alice 본인은 다른 2명에 대한 ONLINE 이벤트 수신")
        val received = alice.sentFrames.map {
            Envelope.parseFrom((it as Frame.Binary).readBytes())
        }
        val users = received.map { it.presenceEvent.userId }.toSet()
        assertEquals(setOf("bob", "carol"), users)
        for (env in received) {
            assertEquals(PresenceEvent.State.ONLINE, env.presenceEvent.state)
            assertEquals(9_000L, env.presenceEvent.serverTs)
        }
        // 다른 사용자에게는 송신 안 함
        assertEquals(0, bob.sentFrames.size)
        assertEquals(0, carol.sentFrames.size)
    }

    @Test
    fun `1명만 접속 중일 때 sendInitialSnapshot 은 아무것도 보내지 않음`() = runBlocking {
        val sessions = SessionManager()
        val alice = FakeClientSession()
        sessions.bind("alice", alice)

        val broadcaster = PresenceBroadcaster(sessions)
        broadcaster.sendInitialSnapshot(alice, "alice")

        assertEquals(0, alice.sentFrames.size)
    }

    @Test
    fun `1명만 접속 중일 때 notifyOnline 은 아무한테도 송신 안 함`() = runBlocking {
        val sessions = SessionManager()
        val alice = FakeClientSession()
        sessions.bind("alice", alice)

        val broadcaster = PresenceBroadcaster(sessions)
        broadcaster.notifyOnline("alice")

        assertEquals(0, alice.sentFrames.size)
    }

    @Test
    fun `각 PresenceEvent 의 sequence 는 서버 능동 푸시 영역(3_000_000+) 에서 증가`() = runBlocking {
        val sessions = SessionManager()
        val alice = FakeClientSession()
        val bob = FakeClientSession()
        sessions.bind("alice", alice)
        sessions.bind("bob", bob)

        val broadcaster = PresenceBroadcaster(sessions)
        broadcaster.notifyOnline("alice")
        broadcaster.notifyOffline("alice")

        assertEquals(2, bob.sentFrames.size)
        val seqs = bob.sentFrames.map {
            Envelope.parseFrom((it as Frame.Binary).readBytes()).sequence
        }
        assertTrue(seqs[0] >= 3_000_000L)
        assertTrue(seqs[1] > seqs[0])
    }
}

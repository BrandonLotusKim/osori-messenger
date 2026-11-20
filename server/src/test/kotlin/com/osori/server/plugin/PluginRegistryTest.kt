package com.osori.server.plugin

import org.junit.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * PluginRegistry 유닛 테스트.
 * 등록/교체/idempotent 시작·종료/예외 격리/훅 전파 커버.
 */
class PluginRegistryTest {

    private fun ctx() = PluginContext(
        hostLogger = LoggerFactory.getLogger("test-host"),
        serverVersion = "0.0.1-test",
    )

    /** 이벤트 수신 내역을 쌓아두는 테스트용 플러그인. */
    private class RecordingPlugin(
        override val id: String = "test.recording",
        override val name: String = "Recording Plugin",
        override val version: String = "1.0.0",
    ) : ServerPlugin {
        var started = 0
        var stopped = 0
        val logins = mutableListOf<LoginEvent>()
        val logouts = mutableListOf<LogoutEvent>()
        val messages = mutableListOf<MessageSentEvent>()
        val rooms = mutableListOf<TalkRoomCreatedEvent>()
        val uploads = mutableListOf<FileUploadedEvent>()
        val downloads = mutableListOf<FileDownloadedEvent>()

        override fun onStart(ctx: PluginContext) { started++ }
        override fun onStop() { stopped++ }
        override fun onLogin(event: LoginEvent) { logins += event }
        override fun onLogout(event: LogoutEvent) { logouts += event }
        override fun onMessageSent(event: MessageSentEvent) { messages += event }
        override fun onTalkRoomCreated(event: TalkRoomCreatedEvent) { rooms += event }
        override fun onFileUploaded(event: FileUploadedEvent) { uploads += event }
        override fun onFileDownloaded(event: FileDownloadedEvent) { downloads += event }
    }

    @Test
    fun `register 는 플러그인을 추가하고 count 가 증가한다`() {
        val reg = PluginRegistry()
        assertEquals(0, reg.count())
        reg.register(RecordingPlugin())
        assertEquals(1, reg.count())
        reg.register(RecordingPlugin(id = "other"))
        assertEquals(2, reg.count())
    }

    @Test
    fun `동일 id 재등록 시 기존 플러그인을 교체한다`() {
        val reg = PluginRegistry()
        val oldP = RecordingPlugin(id = "dup", version = "1.0.0")
        val newP = RecordingPlugin(id = "dup", version = "2.0.0")
        reg.register(oldP)
        reg.register(newP)

        assertEquals(1, reg.count(), "중복 id 는 replace 라 count 는 1")
        assertSame(newP, reg.list().single(), "리스트에 새 인스턴스만 남음")
    }

    @Test
    fun `startAll stopAll 은 각 플러그인을 한 번씩 호출한다`() {
        val reg = PluginRegistry()
        val p = RecordingPlugin()
        reg.register(p)

        reg.startAll(ctx())
        assertEquals(1, p.started)
        reg.stopAll()
        assertEquals(1, p.stopped)
    }

    @Test
    fun `startAll 중복 호출은 무시되고 stopAll 도 마찬가지`() {
        val reg = PluginRegistry()
        val p = RecordingPlugin()
        reg.register(p)

        reg.startAll(ctx())
        reg.startAll(ctx()) // 2회차는 무시돼야 함
        assertEquals(1, p.started)

        reg.stopAll()
        reg.stopAll() // 2회차는 무시
        assertEquals(1, p.stopped)
    }

    @Test
    fun `fireLogin 은 모든 등록된 플러그인에 전파된다`() {
        val reg = PluginRegistry()
        val p1 = RecordingPlugin(id = "p1")
        val p2 = RecordingPlugin(id = "p2")
        reg.register(p1)
        reg.register(p2)

        val ev = LoginEvent(
            userId = "alice",
            deviceId = "dev1",
            clientVersion = "itest",
            success = true,
            failureReason = null,
            timestamp = Instant.now(),
        )
        reg.fireLogin(ev)

        assertEquals(1, p1.logins.size)
        assertEquals(1, p2.logins.size)
        assertEquals("alice", p1.logins[0].userId)
    }

    @Test
    fun `플러그인이 예외를 던져도 다른 플러그인은 계속 받는다`() {
        val reg = PluginRegistry()
        val throwing = object : ServerPlugin {
            override val id = "throwing"
            override val name = "Throwing"
            override val version = "1.0.0"
            override fun onMessageSent(event: MessageSentEvent) {
                throw RuntimeException("플러그인 내부 오류")
            }
        }
        val good = RecordingPlugin(id = "good")

        reg.register(throwing)
        reg.register(good)

        val ev = MessageSentEvent(
            fromUserId = "alice",
            toUserId = "bob",
            serverTalkId = "msg-1",
            bytes = 10,
            queued = false,
            timestamp = Instant.now(),
        )
        // 예외가 호출자에게 propagate 되면 이 호출이 실패함
        reg.fireMessageSent(ev)

        assertEquals(1, good.messages.size, "예외 플러그인 다음에 등록된 플러그인도 정상 수신")
    }

    @Test
    fun `모든 훅 타입을 한 번씩 전파하고 내용이 보존된다`() {
        val reg = PluginRegistry()
        val p = RecordingPlugin()
        reg.register(p)

        val t = Instant.now()
        reg.fireLogin(LoginEvent("u", "d", "v", true, null, t))
        reg.fireLogout(LogoutEvent("u", true, t))
        reg.fireMessageSent(MessageSentEvent("a", "b", "mid", 5, false, t))
        reg.fireTalkRoomCreated(TalkRoomCreatedEvent("a", "rid", "room", t))
        reg.fireFileUploaded(FileUploadedEvent("a", "uid", "f.txt", 100, t))
        reg.fireFileDownloaded(FileDownloadedEvent("a", "uid", t))

        assertEquals(1, p.logins.size)
        assertEquals(1, p.logouts.size)
        assertEquals(1, p.messages.size)
        assertEquals(1, p.rooms.size)
        assertEquals(1, p.uploads.size)
        assertEquals(1, p.downloads.size)
        assertEquals("mid", p.messages[0].serverTalkId)
        assertEquals("f.txt", p.uploads[0].filename)
    }

    @Test
    fun `onStart 에서 예외 던져도 등록은 유지되고 이후 이벤트는 계속 받는다`() {
        val reg = PluginRegistry()
        val bad = object : ServerPlugin {
            override val id = "bad-start"
            override val name = "Bad Start"
            override val version = "1.0.0"
            var received = 0
            override fun onStart(ctx: PluginContext) { throw IllegalStateException("start 실패") }
            override fun onLogin(event: LoginEvent) { received++ }
        }
        reg.register(bad)
        reg.startAll(ctx())

        reg.fireLogin(LoginEvent("u", "d", "v", true, null, Instant.now()))
        assertEquals(1, bad.received, "onStart 실패해도 후속 훅은 계속 받는다")
        assertTrue(reg.count() == 1)
    }

    @Test
    fun `LoggingPlugin 샘플이 모든 훅에 대해 예외 없이 호출된다`() {
        val reg = PluginRegistry()
        reg.register(LoggingPlugin())
        reg.startAll(ctx())

        val t = Instant.now()
        reg.fireLogin(LoginEvent("u", "d", "v", true, null, t))
        reg.fireLogin(LoginEvent("u", "d", "v", false, "bad password", t))
        reg.fireLogout(LogoutEvent("u", explicit = false, t))
        reg.fireMessageSent(MessageSentEvent("a", "b", "m", 1, queued = true, t))
        reg.fireTalkRoomCreated(TalkRoomCreatedEvent("a", "r", "n", t))
        reg.fireFileUploaded(FileUploadedEvent("a", "u", "f", 1, t))
        reg.fireFileDownloaded(FileDownloadedEvent("a", "u", t))

        reg.stopAll()
        // 예외 없이 여기까지 도달하면 OK
    }
}

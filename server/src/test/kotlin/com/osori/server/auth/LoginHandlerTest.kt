package com.osori.server.auth

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.LoginRequest
import com.osori.proto.v1.LoginResponse
import com.osori.server.db.Database
import com.osori.server.db.UserRepository
import com.osori.server.session.SessionManager
import com.osori.server.testutil.FakeClientSession
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoginHandlerTest {

    private fun loginReq(userId: String, password: String = ""): LoginRequest =
        LoginRequest.newBuilder()
            .setUserId(userId)
            .setPassword(password)
            .setDeviceId("dev-1")
            .setClientVersion("0.0.1")
            .build()

    /**
     * In-memory UserRepository — STRICT 모드 테스트용.
     * 원본 Database 는 실제 호출되지 않지만 생성자가 요구하므로 dummy 전달.
     */
    private class FakeUserRepository : UserRepository(Database(DummyDataSource())) {
        private val users = mutableMapOf<String, String>() // userId → password_hash ("" = 미설정)

        override fun findById(userId: String): UserRow? =
            if (userId in users) UserRow(userId, userId, Instant.EPOCH) else null

        override fun createIfAbsent(userId: String, displayName: String): Boolean =
            if (userId in users) false else { users[userId] = ""; true }

        override fun ensureExists(userId: String): UserRow {
            if (userId !in users) users[userId] = ""
            return UserRow(userId, userId, Instant.EPOCH)
        }

        override fun count(): Long = users.size.toLong()

        override fun getPasswordHash(userId: String): String? = users[userId]

        override fun setPasswordHash(userId: String, hash: String) {
            if (userId in users) users[userId] = hash
        }
    }

    /** UserRepository 생성자 요구용 dummy DataSource. 실제 호출은 fake 가 전부 차단. */
    private class DummyDataSource : javax.sql.DataSource {
        override fun getConnection() = error("dummy ds")
        override fun getConnection(u: String?, p: String?) = error("dummy ds")
        override fun getLogWriter() = error("dummy ds")
        override fun setLogWriter(out: java.io.PrintWriter?) = error("dummy ds")
        override fun setLoginTimeout(seconds: Int) = error("dummy ds")
        override fun getLoginTimeout() = 0
        override fun getParentLogger() = error("dummy ds")
        override fun <T : Any?> unwrap(iface: Class<T>?): T = error("dummy ds")
        override fun isWrapperFor(iface: Class<*>?) = false
    }

    /** 테스트용 고정 hasher/verifier (Argon2 대체). raw password 와 "H:"+pw 형태로 작동. */
    private val fakeHasher: (String) -> String = { "H:$it" }
    private val fakeVerifier: (String, String) -> Boolean = { stored, raw -> stored == "H:$raw" }

    private fun Frame.asEnvelope(): Envelope {
        require(this is Frame.Binary) { "expected binary frame, got ${this.frameType}" }
        return Envelope.parseFrom(readBytes())
    }

    @Test
    fun `blank userId gets INVALID_CREDENTIALS`() = runBlocking {
        val sessions = SessionManager()
        val handler = LoginHandler(sessions, userRepository = null)
        val session = FakeClientSession()

        handler.handleLogin(session, sequence = 1, loginReq(""))

        assertEquals(1, session.sentFrames.size)
        val env = session.sentFrames.first().asEnvelope()
        assertEquals(Envelope.PayloadCase.LOGIN_RES, env.payloadCase)
        assertEquals(LoginResponse.Status.INVALID_CREDENTIALS, env.loginRes.status)
        assertTrue(env.loginRes.sessionToken.isBlank())
        // 세션 매니저에는 바인드되지 않음
        assertEquals(0, sessions.count())
    }

    @Test
    fun `normal login gets OK with non-empty token`() = runBlocking {
        val sessions = SessionManager()
        val handler = LoginHandler(sessions, userRepository = null)
        val session = FakeClientSession()

        handler.handleLogin(session, sequence = 2, loginReq("test1"))

        val env = session.sentFrames.first().asEnvelope()
        assertEquals(LoginResponse.Status.OK, env.loginRes.status)
        assertTrue(env.loginRes.sessionToken.isNotBlank())
        assertEquals(2L, env.sequence)
        assertEquals("test1", session.userId)
        assertSame(session, sessions.get("test1"))
    }

    @Test
    fun `duplicate login sends SUPERSEDED notice to previous session before closing`() = runBlocking {
        val sessions = SessionManager()
        val handler = LoginHandler(sessions, userRepository = null)
        val first = FakeClientSession()
        val second = FakeClientSession()

        handler.handleLogin(first, sequence = 10, loginReq("test1"))
        val framesBefore = first.sentFrames.size
        handler.handleLogin(second, sequence = 11, loginReq("test1"))

        // 로그인 응답 이후 기존 세션에 추가로 간 프레임 = SessionClosed(SUPERSEDED) 1건
        val extra = first.sentFrames.drop(framesBefore).map { it.asEnvelope() }
        val notice = extra.single { it.payloadCase == Envelope.PayloadCase.SESSION_CLOSED }
        assertEquals(com.osori.proto.v1.SessionClosed.Reason.SUPERSEDED, notice.sessionClosed.reason)
        assertTrue(notice.sessionClosed.message.isNotBlank())
        assertTrue(first.closed)
        // 새 세션에는 종료 통지가 가지 않는다
        assertTrue(second.sentFrames.map { it.asEnvelope() }.none { it.payloadCase == Envelope.PayloadCase.SESSION_CLOSED })
    }

    @Test
    fun `duplicate login closes previous session and binds new`() = runBlocking {
        val sessions = SessionManager()
        val handler = LoginHandler(sessions, userRepository = null)
        val first = FakeClientSession()
        val second = FakeClientSession()

        handler.handleLogin(first, sequence = 10, loginReq("test1"))
        handler.handleLogin(second, sequence = 11, loginReq("test1"))

        // 기존 세션 강제 종료 확인
        assertTrue(first.closed, "기존 세션은 close() 가 호출되어야 한다")
        assertEquals(1, first.closeReasons.size)
        assertEquals(
            CloseReason.Codes.VIOLATED_POLICY,
            first.closeReasons.first().knownReason,
        )

        // 새 세션이 현재 바인드
        assertSame(second, sessions.get("test1"))
        assertEquals(1, sessions.count())

        // 두 번째 LoginRequest 는 OK 응답
        val secondEnv = second.sentFrames.last().asEnvelope()
        assertEquals(LoginResponse.Status.OK, secondEnv.loginRes.status)
        assertEquals(11L, secondEnv.sequence)
    }

    @Test
    fun `two different users bind independently`() = runBlocking {
        val sessions = SessionManager()
        val handler = LoginHandler(sessions, userRepository = null)
        val a = FakeClientSession()
        val b = FakeClientSession()

        handler.handleLogin(a, sequence = 1, loginReq("test1"))
        handler.handleLogin(b, sequence = 1, loginReq("test2"))

        assertEquals(2, sessions.count())
        assertSame(a, sessions.get("test1"))
        assertSame(b, sessions.get("test2"))

        // 어느 쪽도 close() 가 호출되지 않음
        assertTrue(!a.closed)
        assertTrue(!b.closed)
    }

    // ===== STRICT 모드 =====

    @Test
    fun `STRICT 모드 빈 password 즉시 INVALID_CREDENTIALS`() = runBlocking {
        val sessions = SessionManager()
        val repo = FakeUserRepository()
        val handler = LoginHandler(
            sessions = sessions,
            userRepository = repo,
            authMode = AuthMode.STRICT,
            hasher = fakeHasher,
            verifier = fakeVerifier,
        )
        val session = FakeClientSession()

        handler.handleLogin(session, sequence = 1, loginReq("test1", password = ""))

        val env = session.sentFrames.first().asEnvelope()
        assertEquals(LoginResponse.Status.INVALID_CREDENTIALS, env.loginRes.status)
        // 빈 password 는 repo 접근 전 차단되므로 사용자 생성 안 됨
        assertEquals(0L, repo.count())
        // 세션 바인드도 안 됨
        assertEquals(0, sessions.count())
    }

    @Test
    fun `STRICT 모드 신규 사용자는 첫 password 를 해시로 저장`() = runBlocking {
        val sessions = SessionManager()
        val repo = FakeUserRepository()
        val handler = LoginHandler(
            sessions = sessions,
            userRepository = repo,
            authMode = AuthMode.STRICT,
            hasher = fakeHasher,
            verifier = fakeVerifier,
        )
        val session = FakeClientSession()

        handler.handleLogin(session, sequence = 1, loginReq("test1", password = "secret"))

        val env = session.sentFrames.first().asEnvelope()
        assertEquals(LoginResponse.Status.OK, env.loginRes.status)
        assertTrue(env.loginRes.sessionToken.isNotBlank())
        // 해시가 저장돼야 함
        assertEquals("H:secret", repo.getPasswordHash("test1"))
        assertSame(session, sessions.get("test1"))
    }

    @Test
    fun `STRICT 모드 재로그인 올바른 password 는 통과`() = runBlocking {
        val sessions = SessionManager()
        val repo = FakeUserRepository()
        val handler = LoginHandler(
            sessions = sessions,
            userRepository = repo,
            authMode = AuthMode.STRICT,
            hasher = fakeHasher,
            verifier = fakeVerifier,
        )

        // 첫 로그인 — 해시 저장
        handler.handleLogin(FakeClientSession(), 1, loginReq("test1", "correct"))
        // 재로그인 — 검증 경로
        val second = FakeClientSession()
        handler.handleLogin(second, 2, loginReq("test1", "correct"))

        val env = second.sentFrames.last().asEnvelope()
        assertEquals(LoginResponse.Status.OK, env.loginRes.status)
        assertSame(second, sessions.get("test1"))
    }

    @Test
    fun `STRICT 모드 재로그인 잘못된 password 는 거부`() = runBlocking {
        val sessions = SessionManager()
        val repo = FakeUserRepository()
        val handler = LoginHandler(
            sessions = sessions,
            userRepository = repo,
            authMode = AuthMode.STRICT,
            hasher = fakeHasher,
            verifier = fakeVerifier,
        )

        // 첫 로그인
        handler.handleLogin(FakeClientSession(), 1, loginReq("test1", "correct"))

        // 틀린 password 로 재로그인
        val wrong = FakeClientSession()
        handler.handleLogin(wrong, 2, loginReq("test1", "wrong"))

        val env = wrong.sentFrames.last().asEnvelope()
        assertEquals(LoginResponse.Status.INVALID_CREDENTIALS, env.loginRes.status)
        // 틀린 password 는 세션 바인드 안 됨 — 첫 로그인 세션은 sessions 에서 이미 해제됐을 수 있음
        // 최소한 wrong 은 바인드 안 됐음을 확인
        assertFalse(wrong.closed)
        assertEquals(null, sessions.get("test1").takeIf { it === wrong })
    }

    @Test
    fun `OPEN 모드는 STRICT 처럼 password 검증하지 않음 — 회귀 방지`() = runBlocking {
        val sessions = SessionManager()
        val repo = FakeUserRepository()
        val handler = LoginHandler(
            sessions = sessions,
            userRepository = repo,
            authMode = AuthMode.OPEN,  // 기본값 명시
            hasher = fakeHasher,
            verifier = fakeVerifier,
        )

        // 빈 password 도 통과
        handler.handleLogin(FakeClientSession(), 1, loginReq("test1", ""))
        // 아무 password 로도 통과
        handler.handleLogin(FakeClientSession(), 2, loginReq("test1", "anything"))

        assertEquals(1L, repo.count())
        // OPEN 모드는 해시 저장 안 함
        assertEquals("", repo.getPasswordHash("test1"))
    }

    // ===== 2026-09-15 사용자 요구사항: 로그인 허용 계정 화이트리스트 =====

    @Test
    fun `화이트리스트 밖 계정은 STRICT 여부와 무관하게 즉시 거부`() = runBlocking {
        val sessions = SessionManager()
        val repo = FakeUserRepository()
        val handler = LoginHandler(
            sessions = sessions,
            userRepository = repo,
            authMode = AuthMode.OPEN,
            hasher = fakeHasher,
            verifier = fakeVerifier,
        )
        val session = FakeClientSession()

        handler.handleLogin(session, sequence = 1, loginReq("alice", "anything"))

        val env = session.sentFrames.first().asEnvelope()
        assertEquals(LoginResponse.Status.INVALID_CREDENTIALS, env.loginRes.status)
        // 화이트리스트 게이트가 ensureExists 보다 먼저 걸려서 계정 자동 생성조차 안 됨
        assertEquals(0L, repo.count())
        assertEquals(0, sessions.count())
    }

    @Test
    fun `화이트리스트 안 계정은 STRICT 비밀번호 검증을 그대로 통과한다`() = runBlocking {
        val sessions = SessionManager()
        val repo = FakeUserRepository()
        val handler = LoginHandler(
            sessions = sessions,
            userRepository = repo,
            authMode = AuthMode.STRICT,
            hasher = fakeHasher,
            verifier = fakeVerifier,
        )

        handler.handleLogin(FakeClientSession(), 1, loginReq("test1", "1234"))
        val wrong = FakeClientSession()
        handler.handleLogin(wrong, 2, loginReq("test1", "wrong"))
        val correct = FakeClientSession()
        handler.handleLogin(correct, 3, loginReq("test1", "1234"))

        assertEquals(
            LoginResponse.Status.INVALID_CREDENTIALS,
            wrong.sentFrames.last().asEnvelope().loginRes.status,
        )
        assertEquals(
            LoginResponse.Status.OK,
            correct.sentFrames.last().asEnvelope().loginRes.status,
        )
    }
}

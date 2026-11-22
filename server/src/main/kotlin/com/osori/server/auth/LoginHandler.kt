package com.osori.server.auth

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.LoginRequest
import com.osori.proto.v1.LoginResponse
import com.osori.proto.v1.SessionClosed
import com.osori.server.db.UserRepository
import com.osori.server.talk.TalkOutboxFlusher
import com.osori.server.session.ClientSession
import com.osori.server.session.PresenceBroadcaster
import com.osori.server.session.SessionManager
import io.ktor.websocket.*
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("com.osori.server.LoginHandler")

/**
 * 인증 모드.
 *
 * - OPEN : 기존 MVP 동작. password 무시, 사용자 없으면 자동 가입, 비밀번호 설정/검증 없음.
 * - STRICT: Argon2id 기반 실인증. 첫 로그인 시 password 로 해시 저장,
 *           이후 로그인은 저장된 해시와 비교. 빈 password 거부.
 */
enum class AuthMode {
    OPEN, STRICT;

    companion object {
        fun fromEnv(value: String?): AuthMode =
            when (value?.lowercase()) {
                "strict" -> STRICT
                else -> OPEN
            }
    }
}

class LoginHandler(
    private val sessions: SessionManager,
    private val userRepository: UserRepository? = null,
    private val talkOutboxFlusher: TalkOutboxFlusher? = null,
    private val presenceBroadcaster: PresenceBroadcaster? = null,
    private val authMode: AuthMode = AuthMode.OPEN,
    private val hasher: (String) -> String = PasswordHasher::hash,
    private val verifier: (String, String) -> Boolean = PasswordHasher::verify,
    private val auditRepository: com.osori.server.audit.AuditRepository? = null, //
    private val pluginRegistry: com.osori.server.plugin.PluginRegistry? = null, //
    private val messageHandler: com.osori.server.message.MessageHandler? = null, //
) {

    suspend fun handleLogin(
        session: ClientSession,
        sequence: Long,
        req: LoginRequest,
    ) {
        logger.info(
            "로그인 시도 userId={} device={} version={} authMode={}",
            req.userId, req.deviceId, req.clientVersion, authMode,
        )

        if (req.userId.isBlank()) {
            auditRepository?.recordLoginFailed(userId = null, reason = "blank userId")
            pluginRegistry?.fireLogin(
                com.osori.server.plugin.LoginEvent(
                    userId = "",
                    deviceId = req.deviceId,
                    clientVersion = req.clientVersion,
                    success = false,
                    failureReason = "blank userId",
                    timestamp = java.time.Instant.now(),
                ),
            )
            sendResponse(session, sequence, LoginResponse.Status.INVALID_CREDENTIALS)
            return
        }

        // 화이트리스트 게이트 (2026-09-15 사용자 요구사항): admin/test1~10 외 계정은
        // 존재 여부와 무관하게 즉시 거부. repo.ensureExists 보다 먼저 체크해서
        // 목록 밖 userId 로는 계정 자동 생성조차 안 되도록 한다.
        if (req.userId !in AllowedAccounts.IDS) {
            logger.info("허용되지 않은 계정 로그인 시도 userId={}", req.userId)
            auditRepository?.recordLoginFailed(userId = req.userId, reason = "not in allow-list")
            pluginRegistry?.fireLogin(
                com.osori.server.plugin.LoginEvent(
                    userId = req.userId,
                    deviceId = req.deviceId,
                    clientVersion = req.clientVersion,
                    success = false,
                    failureReason = "not in allow-list",
                    timestamp = java.time.Instant.now(),
                ),
            )
            com.osori.server.metrics.Metrics.loginFailure.incrementAndGet()
            sendResponse(session, sequence, LoginResponse.Status.INVALID_CREDENTIALS)
            return
        }

        val repo = userRepository

        // STRICT 모드: 빈 password 즉시 거부 (repo 가 있어야만 의미 있음 — 없으면 OPEN 처럼 동작)
        if (authMode == AuthMode.STRICT && repo != null) {
            if (req.password.isBlank()) {
                logger.info("STRICT 모드: 빈 password 거부 userId={}", req.userId)
                auditRepository?.recordLoginFailed(userId = req.userId, reason = "STRICT blank password")
                pluginRegistry?.fireLogin(
                    com.osori.server.plugin.LoginEvent(
                        userId = req.userId,
                        deviceId = req.deviceId,
                        clientVersion = req.clientVersion,
                        success = false,
                        failureReason = "STRICT blank password",
                        timestamp = java.time.Instant.now(),
                    ),
                )
                com.osori.server.metrics.Metrics.loginFailure.incrementAndGet()
                sendResponse(session, sequence, LoginResponse.Status.INVALID_CREDENTIALS)
                return
            }
        }

        if (repo != null) {
            runCatching { repo.ensureExists(req.userId) }
                .onFailure { t ->
                    logger.error("사용자 확인/생성 실패 userId={}", req.userId, t)
                    sendResponse(session, sequence, LoginResponse.Status.SERVER_ERROR)
                    return
                }

            // STRICT 모드: 저장된 해시 조회 → 없으면 첫 로그인(해시 저장), 있으면 verify
            if (authMode == AuthMode.STRICT) {
                val stored = runCatching { repo.getPasswordHash(req.userId) }
                    .onFailure { t ->
                        logger.error("password_hash 조회 실패 userId={}", req.userId, t)
                        sendResponse(session, sequence, LoginResponse.Status.SERVER_ERROR)
                        return
                    }
                    .getOrNull()

                if (stored.isNullOrBlank()) {
                    // 첫 로그인 — 현재 password 로 해시 저장
                    val newHash = runCatching { hasher(req.password) }
                        .onFailure { t ->
                            logger.error("Argon2 해시 생성 실패 userId={}", req.userId, t)
                            sendResponse(session, sequence, LoginResponse.Status.SERVER_ERROR)
                            return
                        }
                        .getOrThrow()
                    runCatching { repo.setPasswordHash(req.userId, newHash) }
                        .onFailure { t ->
                            logger.error("password_hash 저장 실패 userId={}", req.userId, t)
                            sendResponse(session, sequence, LoginResponse.Status.SERVER_ERROR)
                            return
                        }
                    logger.info("STRICT 모드 신규 비밀번호 설정 userId={}", req.userId)
                } else {
                    // 재로그인 — 검증
                    val match = runCatching { verifier(stored, req.password) }.getOrDefault(false)
                    if (!match) {
                        logger.info("STRICT 모드: 비밀번호 불일치 userId={}", req.userId)
                        auditRepository?.recordLoginFailed(userId = req.userId, reason = "STRICT password mismatch")
                        pluginRegistry?.fireLogin(
                            com.osori.server.plugin.LoginEvent(
                                userId = req.userId,
                                deviceId = req.deviceId,
                                clientVersion = req.clientVersion,
                                success = false,
                                failureReason = "STRICT password mismatch",
                                timestamp = java.time.Instant.now(),
                            ),
                        )
                        com.osori.server.metrics.Metrics.loginFailure.incrementAndGet()
                sendResponse(session, sequence, LoginResponse.Status.INVALID_CREDENTIALS)
                        return
                    }
                }
            }
        }

        val existing = sessions.bind(req.userId, session)
        if (existing != null && existing !== session) {
            logger.info("중복 로그인으로 기존 세션 종료 userId={}", req.userId)
            // 끊기 전에 사유를 알려 준다 — 사유 없이 끊으면 기존 클라이언트가 자동 재접속해서
            // 새 세션을 다시 끊고, 두 곳이 서로를 무한히 끊는 교대가 생긴다.
            runCatching {
                val notice = Envelope.newBuilder()
                    .setSessionClosed(
                        SessionClosed.newBuilder()
                            .setReason(SessionClosed.Reason.SUPERSEDED)
                            .setMessage("다른 곳에서 같은 계정으로 로그인되어 연결이 끊겼습니다.")
                            .setServerTs(java.time.Instant.now().toEpochMilli())
                            .build(),
                    )
                    .build()
                existing.send(Frame.Binary(true, notice.toByteArray()))
            }.onFailure { t -> logger.debug("SessionClosed 통지 실패 userId={}", req.userId, t) }
            auditRepository?.recordSessionSuperseded(req.userId, existing.id.toString(), session.id.toString())
            runCatching {
                existing.close(
                    CloseReason(CloseReason.Codes.VIOLATED_POLICY, "already-online"),
                )
            }
        }

        val token = UUID.randomUUID().toString()
        sendResponse(session, sequence, LoginResponse.Status.OK, token)

        // 신규 로그인자에게 현재 온라인 목록 push (자기 자신 제외)
        runCatching {
            presenceBroadcaster?.sendInitialSnapshot(session, req.userId)
        }.onFailure { t ->
            logger.warn("presence snapshot 송신 실패 userId={}", req.userId, t)
        }

        // 다른 온라인 사용자에게 ONLINE 알림
        runCatching {
            presenceBroadcaster?.notifyOnline(req.userId)
        }.onFailure { t ->
            logger.warn("presence ONLINE broadcast 실패 userId={}", req.userId, t)
        }

        runCatching {
            talkOutboxFlusher?.flush(session, req.userId)
        }.onFailure { t ->
            logger.warn("outbox flush 실패 userId={}", req.userId, t)
        }
        //: 오프라인 미배달 쪽지 일괄 푸시
        runCatching {
            messageHandler?.flushUndelivered(req.userId)
        }.onFailure { t ->
            logger.warn("memo flushUndelivered 실패 userId={}", req.userId, t)
        }
        com.osori.server.metrics.Metrics.loginSuccess.incrementAndGet()
        auditRepository?.recordLoginSuccess(userId = req.userId, sessionId = session.id.toString())
        pluginRegistry?.fireLogin(
            com.osori.server.plugin.LoginEvent(
                userId = req.userId,
                deviceId = req.deviceId,
                clientVersion = req.clientVersion,
                success = true,
                failureReason = null,
                timestamp = java.time.Instant.now(),
            ),
        )
        logger.info("로그인 성공 userId={} session={} (접속수={})", req.userId, session.id, sessions.count())
    }

    private suspend fun sendResponse(
        session: ClientSession,
        sequence: Long,
        status: LoginResponse.Status,
        token: String = "",
    ) {
        val res = LoginResponse.newBuilder()
            .setStatus(status)
            .setSessionToken(token)
            .setServerTs(System.currentTimeMillis())
            .build()

        val envelope = Envelope.newBuilder()
            .setSequence(sequence)
            .setLoginRes(res)
            .build()

        session.send(Frame.Binary(true, envelope.toByteArray()))
    }
}

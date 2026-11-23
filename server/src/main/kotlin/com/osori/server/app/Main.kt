package com.osori.server.app

import com.osori.server.auth.AuthMode
import com.osori.server.auth.LoginHandler
import com.osori.server.audit.AuditRepository
import com.osori.server.db.Database
import com.osori.server.db.FileRepository
import com.osori.server.db.TalkRepository
import com.osori.server.db.OrganizationRepository
import com.osori.server.db.PushTokenRepository
import com.osori.server.db.TalkRoomRepository
import com.osori.server.db.UserRepository
import com.osori.server.file.FileHandler
import com.osori.server.talk.TalkHistoryHandler
import com.osori.server.talk.TalkRouter
import com.osori.server.talk.TalkSearchHandler
import com.osori.server.talk.TalkOutboxFlusher
import com.osori.server.talk.RateLimitPolicy
import com.osori.server.talk.TalkRoomHandler
import com.osori.server.org.OrgListHandler
import com.osori.server.plugin.PluginContext
import com.osori.server.plugin.PluginRegistry
import com.osori.server.push.NoopPushNotifier
import com.osori.server.push.PushDeliveryPlugin
import com.osori.server.push.PushTokenHandler
import com.osori.server.session.PresenceBroadcaster
import com.osori.server.session.SessionManager
import com.osori.server.session.SessionReaper
import com.osori.server.transport.installWebSocketHandler
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyStore
import java.time.Duration

private val logger = LoggerFactory.getLogger("com.osori.server.Main")
private val serverStartMillis = System.currentTimeMillis()

fun main() {
    val port = System.getenv("OSORI_PORT")?.toIntOrNull() ?: 7070
    val host = System.getenv("OSORI_HOST") ?: "0.0.0.0"
    val dbEnabled = System.getenv("OSORI_DB_ENABLED")?.toBooleanStrictOrNull() ?: true

    val authMode = AuthMode.fromEnv(System.getenv("OSORI_AUTH_MODE"))

    val rateLimitEnabled =
        System.getenv("OSORI_RATE_LIMIT_ENABLED")?.toBooleanStrictOrNull() ?: false
    val rateLimitPerSec =
        System.getenv("OSORI_RATE_LIMIT_PER_USER_PER_SEC")?.toIntOrNull() ?: 5

    // D7 — 클라이언트 자동 업데이트. 설치 파일을 서버가 정적으로 서빙하고,
    // 클라는 시작 시 /client/version 으로 조회해 최신 버전과 비교한다.
    val clientLatestVersion = System.getenv("OSORI_CLIENT_LATEST_VERSION") ?: "0.1.0"
    val clientInstallerPath = System.getenv("OSORI_CLIENT_INSTALLER_PATH")

    val tlsEnabled = System.getenv("OSORI_TLS_ENABLED")?.toBooleanStrictOrNull() ?: false
    val tlsPort = System.getenv("OSORI_TLS_PORT")?.toIntOrNull() ?: 7443
    val keystorePath = System.getenv("OSORI_KEYSTORE_PATH")
    val keystorePass = System.getenv("OSORI_KEYSTORE_PASSWORD") ?: ""
    val keyAlias = System.getenv("OSORI_KEY_ALIAS") ?: "osori"
    val keyPass = System.getenv("OSORI_KEY_PASSWORD") ?: keystorePass

    logger.info(
        "오소리 메신저 서버 기동 준비 host={} port={} dbEnabled={} tlsEnabled={} tlsPort={} authMode={}",
        host, port, dbEnabled, tlsEnabled, tlsPort, authMode,
    )
    logger.info("Endpoints — ws://{}:{}/ws  health: http://{}:{}/health", host, port, host, port)

    val database: Database? = if (dbEnabled) {
        runCatching { Database.fromEnv() }
            .onFailure { logger.error("DB 초기화 실패, DB 없이 기동", it) }
            .getOrNull()
    } else {
        logger.info("OSORI_DB_ENABLED=false 로 DB 없이 기동 (메모리 전용)")
        null
    }
    // — 운영자가 schema.sql / v6 마이그레이션 적용을 빼먹고 기동했을 때 명확한 경고
    database?.let { db ->
        runCatching { db.verifyRequiredTables() }
            .onSuccess { missing ->
                if (missing.isEmpty()) {
                    logger.info("DB schema sanity OK — 모든 필수 테이블 존재")
                } else {
                    logger.error("🟠 DB schema 누락 테이블: {} — schema.sql 적용 또는 마이그레이션 필요", missing)
                }
            }
            .onFailure { logger.warn("schema sanity 체크 자체 실패 (information_schema 접근 권한?)", it) }
    }

    val userRepository = database?.let { UserRepository(it) }
    val talkRepository = database?.let { TalkRepository(it) }
    val talkRoomRepository = database?.let { TalkRoomRepository(it) }
    val fileRepository = database?.let { FileRepository(it) }
    val orgRepository = database?.let { OrganizationRepository(it) }
    val auditRepository = database?.let { AuditRepository(it) }
    val pushTokenRepository = database?.let { PushTokenRepository(it) }
    val messageRepository = database?.let { com.osori.server.db.MessageRepository(it) }
    val fileStorageRoot: Path = Paths.get(
        System.getenv("OSORI_FILE_STORAGE") ?: "storage/uploads"
    )

    val keystore: KeyStore? = if (tlsEnabled && keystorePath != null) {
        runCatching {
            val ks = KeyStore.getInstance("JKS")
            FileInputStream(keystorePath).use { stream ->
                ks.load(stream, keystorePass.toCharArray())
            }
            logger.info("TLS keystore 로드 성공 path={} alias={}", keystorePath, keyAlias)
            ks
        }.onFailure { logger.error("TLS keystore 로드 실패 path={}", keystorePath, it) }
            .getOrNull()
    } else {
        if (tlsEnabled) {
            logger.warn("OSORI_TLS_ENABLED=true 인데 OSORI_KEYSTORE_PATH 가 비어있음 — TLS 비활성")
        }
        null
    }

    val rateLimitPolicy: RateLimitPolicy =
        if (rateLimitEnabled) {
            logger.info(
                "Rate limit 활성 — capacity/refill = {}/sec per user",
                rateLimitPerSec,
            )
            // — OSORI_RATE_LIMIT_EXEMPT_USERS=alice,bob 형식. 공백/빈 토큰 무시.
            val exempt = System.getenv("OSORI_RATE_LIMIT_EXEMPT_USERS")
                ?.split(",", " ")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
            if (exempt.isNotEmpty()) logger.info("Rate limit 면제 사용자 {}", exempt)
            RateLimitPolicy.PerUserTokenBucket(
                capacity = rateLimitPerSec,
                refillPerSecond = rateLimitPerSec.toDouble(),
                exemptUserIds = exempt,
            )
        } else {
            RateLimitPolicy.Disabled
        }

    // Phase 3: 핫리로드 토글 (Ktor development mode + watchPaths). 기본 OFF — 운영 안전.
    // 켜는 법: OSORI_DEV_MODE=true 환경변수.
    val devMode = System.getenv("OSORI_DEV_MODE")?.equals("true", ignoreCase = true) ?: false
    if (devMode) {
        logger.info("개발 모드 ON — Autoreload 활성화 (watchPaths=classes,resources)")
    }

    val env = applicationEngineEnvironment {
        developmentMode = devMode
        if (devMode) {
            watchPaths = listOf("classes", "resources")
        }
        module { configureServer(userRepository, talkRepository, talkRoomRepository, fileRepository, orgRepository, auditRepository, pushTokenRepository, messageRepository, fileStorageRoot, authMode, rateLimitPolicy, clientLatestVersion = clientLatestVersion, clientInstallerPath = clientInstallerPath) }

        connector {
            this.host = host
            this.port = port
        }

        if (keystore != null && keystorePath != null) {
            sslConnector(
                keyStore = keystore,
                keyAlias = keyAlias,
                keyStorePassword = { keystorePass.toCharArray() },
                privateKeyPassword = { keyPass.toCharArray() },
            ) {
                this.host = host
                this.port = tlsPort
                this.keyStorePath = File(keystorePath)
            }
        }
    }

    val server = embeddedServer(Netty, env)

    // 첨부 보존 + 디스크 알람 — DB 활성 시에만 백그라운드 코루틴 기동
    val storageMaintenance = if (database != null && fileRepository != null) {
        com.osori.server.storage.StorageMaintenance(database, fileStorageRoot).also { it.start() }
    } else null

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info("SIGTERM/Ctrl+C 감지 — 오소리 메신저 서버 종료 시작")
            runCatching { storageMaintenance?.stop() }
                .onFailure { logger.warn("storageMaintenance stop 오류", it) }
            runCatching { server.stop(1_000, 5_000) }
                .onFailure { logger.warn("서버 stop 중 오류", it) }
            runCatching { database?.close() }
                .onFailure { logger.warn("DB 풀 close 중 오류", it) }
            logger.info("오소리 메신저 서버 종료 완료")
        },
    )

    server.start(wait = true)
}

fun Application.configureServer(
    userRepository: UserRepository?,
    talkRepository: TalkRepository?,
    talkRoomRepository: TalkRoomRepository? = null,
    fileRepository: FileRepository? = null,
    orgRepository: OrganizationRepository? = null,
    auditRepository: AuditRepository? = null, // 배선
    pushTokenRepository: PushTokenRepository? = null, //
    messageRepository: com.osori.server.db.MessageRepository? = null, //
    fileStorageRoot: Path = Paths.get("storage/uploads"),
    authMode: AuthMode = AuthMode.OPEN,
    rateLimitPolicy: RateLimitPolicy = RateLimitPolicy.Disabled,
    pluginRegistry: PluginRegistry = PluginRegistry(), // — 테스트에서 스파이 주입 가능
    clientLatestVersion: String = "0.1.0", // D7 자동 업데이트
    clientInstallerPath: String? = null, // D7 자동 업데이트 — null 이면 /client/download 404
) {
    install(CallLogging)
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(30)
        timeout = Duration.ofSeconds(60)
        maxFrameSize = 10L * 1024 * 1024
        masking = false
    }

    //: 플러그인 레지스트리. 호스트 기본은 빈 레지스트리 — 필요 시 여기에 register() 추가.
    // 예: pluginRegistry.register(com.osori.server.plugin.LoggingPlugin())
    //
    //: PushDeliveryPlugin — pushTokenRepository 가 있을 때만 등록.
    // 기본 NoopPushNotifier 는 로그만 남김. 에서 FCM HTTP v1 구현체로 교체 예정.
    if (pushTokenRepository != null) {
        pluginRegistry.register(PushDeliveryPlugin(pushTokenRepository, NoopPushNotifier()))
    }
    val pluginCtx = PluginContext(
        hostLogger = logger,
        serverVersion = "0.0.1",
    )
    pluginRegistry.startAll(pluginCtx)
    environment.monitor.subscribe(ApplicationStopped) {
        pluginRegistry.stopAll()
    }

    val sessions = SessionManager()
    val talkRouter = TalkRouter(sessions, talkRepository, rateLimitPolicy, auditRepository, pluginRegistry)
    // 1:1 외 요청(그룹 방 메시지·쪽지·파일 시작·방 관리·검색)도 같은 설정으로 종류별 버킷을 따로 둔다.
    val requestRateLimiter = requestRateLimiterFor(rateLimitPolicy)
    val talkOutboxFlusher = talkRepository?.let { TalkOutboxFlusher(it) }
    val presenceBroadcaster = PresenceBroadcaster(sessions)
    val messageHandler = com.osori.server.message.MessageHandler(
        messageRepository, sessions,
        attachmentUsableBy = fileRepository?.let { repo ->
            { uploadId: String, userId: String ->
                val id = runCatching { java.util.UUID.fromString(uploadId) }.getOrNull()
                id != null && repo.canAttachToMessage(id, userId)
            }
        },
        attachmentInfo = fileRepository?.let { repo ->
            { uploadId: String ->
                runCatching { java.util.UUID.fromString(uploadId) }.getOrNull()
                    ?.let { repo.getUpload(it) }
                    ?.let { it.filename to it.totalSize }
            }
        },
    )
    val talkRoomHistoryHandler = com.osori.server.talk.TalkRoomHistoryHandler(talkRoomRepository)
    val loginHandler = LoginHandler(
        sessions,
        userRepository,
        talkOutboxFlusher,
        presenceBroadcaster,
        authMode = authMode,
        auditRepository = auditRepository,
        pluginRegistry = pluginRegistry,
        messageHandler = messageHandler,
    )
    val talkHistoryHandler = TalkHistoryHandler(talkRepository)
    val talkRoomHandler = TalkRoomHandler(sessions, talkRoomRepository, auditRepository = auditRepository, pluginRegistry = pluginRegistry)
    val fileHandler = FileHandler(sessions, fileRepository, fileStorageRoot, auditRepository = auditRepository, pluginRegistry = pluginRegistry, roomRepository = talkRoomRepository)
    val orgListHandler = OrgListHandler(orgRepository)
    val talkSearchHandler = TalkSearchHandler(talkRepository, talkRoomRepository)
    val pushTokenHandler = PushTokenHandler(pushTokenRepository)

    val sessionReaper = SessionReaper(sessions)
    sessionReaper.start()
    environment.monitor.subscribe(ApplicationStopped) {
        sessionReaper.stop()
    }

    // maintenance 는 configureServer 인자로는 database 없음 → main() 에서 별도 기동.
    // 이 자리에는 sampler 만 등록.

    // 메트릭 sampler 배선
    com.osori.server.metrics.Metrics.sessionCountSampler = { sessions.count() }

    routing {
        installWebSocketHandler(sessions, loginHandler, talkRouter, talkHistoryHandler, presenceBroadcaster, talkRoomHandler, fileHandler, orgListHandler, talkSearchHandler, auditRepository, pluginRegistry, pushTokenHandler, messageHandler, talkRoomHistoryHandler, requestRateLimiter)
        // 운영 헬스체크 — 도커 컴포즈 / 로드밸런서 / k8s probe 가 200 으로 살아있다고 판단
        get("/health") {
            val sessionCount = sessions.count()
            val dbStatus = if (userRepository != null) "up" else "down"
            val uptimeSec = (System.currentTimeMillis() - serverStartMillis) / 1000
            val mem = Runtime.getRuntime()
            val memUsedMb = (mem.totalMemory() - mem.freeMemory()) / 1024 / 1024
            val memMaxMb = mem.maxMemory() / 1024 / 1024
            call.respondText(
                """{"status":"ok","sessions":$sessionCount,"db":"$dbStatus","version":"0.1.0","uptime_sec":$uptimeSec,"mem_used_mb":$memUsedMb,"mem_max_mb":$memMaxMb}""",
                contentType = ContentType.Application.Json,
            )
        }
        // Prometheus 메트릭 — text/plain v0.0.4
        get("/metrics") {
            call.respondText(
                com.osori.server.metrics.Metrics.render(),
                contentType = ContentType.parse("text/plain; version=0.0.4"),
            )
        }
        // D7 — 클라이언트 자동 업데이트: 최신 버전 조회
        get("/client/version") {
            call.respondText(
                """{"latestVersion":"$clientLatestVersion","downloadUrl":"/client/download"}""",
                contentType = ContentType.Application.Json,
            )
        }
        // D7 — 클라이언트 자동 업데이트: 설치 파일 다운로드
        get("/client/download") {
            val installer = clientInstallerPath?.let(::File)
            if (installer == null || !installer.exists()) {
                call.respond(HttpStatusCode.NotFound, "설치 파일이 준비되지 않았습니다")
                return@get
            }
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, installer.name).toString(),
            )
            call.respondFile(installer)
        }
    }

    logger.info("오소리 메신저 서버 구성 완료 (DB={})", if (userRepository != null) "ON" else "OFF")
}

/**
 * 1:1 전송용 정책 설정을 그대로 본떠 요청 종류별 버킷을 만든다(종류마다 별도 인스턴스 = 별도 버킷).
 * 비활성이면 모든 요청 통과.
 */
internal fun requestRateLimiterFor(base: RateLimitPolicy): com.osori.server.transport.RequestRateLimiter =
    when (base) {
        is RateLimitPolicy.PerUserTokenBucket -> com.osori.server.transport.RequestRateLimiter { base.copyForNewBucket() }
        else -> com.osori.server.transport.RequestRateLimiter.DISABLED
    }

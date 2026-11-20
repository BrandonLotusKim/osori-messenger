package com.osori.server.plugin

import org.slf4j.Logger
import java.time.Instant

/**
 * 오소리 메신저 서버 플러그인 인터페이스.
 *
 * **설계 원칙**
 *  - **비침습적(non-invasive)**: 플러그인 예외는 절대 호스트 처리 흐름을 깨지 않는다.
 *    `PluginRegistry` 가 모든 fire 를 runCatching 로 감싸서 로그만 남기고 삼킨다.
 *  - **최소 API**: 현 단계는 read-only 이벤트 훅만 제공. 메시지 변경/차단 같은
 *    "권한있는" 훅은 추후 별도 인터페이스(`MessageInterceptor` 등)로 분리.
 *  - **ID/version 은 필수**: 로그/감사 추적을 위해 모든 플러그인은 자기 식별이 가능해야 함.
 *  - **onStart/onStop 은 idempotent**: 호스트가 시작/종료 시 한 번씩 호출하지만
 *    실패시 재시도 안 함. 플러그인은 멱등하게 작성할 것.
 *
 * **왜 synchronous 한가**
 *  - audit/log 성격의 훅은 데이터 경로를 블록하면 안 되므로 **빠르게 리턴**해야 한다.
 *  - 장시간 I/O 가 필요한 플러그인은 스스로 백그라운드 큐를 만들어 디커플하라.
 *
 * **기본 구현**: 모든 메서드가 빈 디폴트. 플러그인은 관심있는 훅만 override.
 */
interface ServerPlugin {
    /** 고유 식별자. 역DNS 권장 (예: `com.osori.audit-forwarder`). */
    val id: String

    /** 사람 읽기용 이름. */
    val name: String

    /** 플러그인 자체 버전 (SemVer 권장). */
    val version: String

    /** 서버 기동 시 한 번 호출. 리소스 초기화. */
    fun onStart(ctx: PluginContext) {}

    /** 서버 종료 시 한 번 호출. 리소스 정리. */
    fun onStop() {}

    /** 로그인 시도 결과(성공/실패 모두). */
    fun onLogin(event: LoginEvent) {}

    /** 로그아웃 완료(명시적 LOGOUT 또는 세션 종료 공통). */
    fun onLogout(event: LogoutEvent) {}

    /** 1:1 메시지가 성공적으로 영속(혹은 outbox 큐잉)된 직후. */
    fun onMessageSent(event: MessageSentEvent) {}

    /** 그룹 방이 새로 만들어졌을 때. */
    fun onTalkRoomCreated(event: TalkRoomCreatedEvent) {}

    /** 파일 업로드가 완료됐을 때 (complete ack 직후). */
    fun onFileUploaded(event: FileUploadedEvent) {}

    /** 파일 다운로드가 승인돼 첫 바이트 전송 직전. */
    fun onFileDownloaded(event: FileDownloadedEvent) {}
}

/**
 * onStart 시 호스트가 주입하는 컨텍스트.
 * 플러그인이 호스트 버전/로거에 접근할 수 있게 한다. 나중에 DB 접근자나
 * 세션 이벤트 스트림이 필요해지면 여기에 추가.
 */
data class PluginContext(
    val hostLogger: Logger,
    val serverVersion: String,
)

// ---- 이벤트 타입 ----
// 모두 immutable data class. timestamp 는 호스트가 찍어서 넣는다.

data class LoginEvent(
    val userId: String,
    val deviceId: String,
    val clientVersion: String,
    val success: Boolean,
    /** 실패 사유(성공 시 null). */
    val failureReason: String? = null,
    val timestamp: Instant,
)

data class LogoutEvent(
    val userId: String,
    /** 명시적 LOGOUT 인지, 연결 종료인지 구분. */
    val explicit: Boolean,
    val timestamp: Instant,
)

data class MessageSentEvent(
    val fromUserId: String,
    val toUserId: String,
    val serverTalkId: String,
    /** 본문 UTF-8 바이트 수. */
    val bytes: Int,
    /** true 면 오프라인 큐잉, false 면 온라인 즉시 배달. */
    val queued: Boolean,
    val timestamp: Instant,
)

data class TalkRoomCreatedEvent(
    val ownerUserId: String,
    val roomId: String,
    val name: String,
    val timestamp: Instant,
)

data class FileUploadedEvent(
    val uploaderUserId: String,
    val uploadId: String,
    val filename: String,
    val totalBytes: Long,
    val timestamp: Instant,
)

data class FileDownloadedEvent(
    val downloaderUserId: String,
    val uploadId: String,
    val timestamp: Instant,
)

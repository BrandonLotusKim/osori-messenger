package com.osori.server.push

import org.slf4j.LoggerFactory

/**
 * 벤더 중립 푸시 발송 인터페이스.
 *
 * **설계 의도**
 *  - `PushNotifier` 는 FCM/APNs/테스트하네스 등 구현체를 plug 할 수 있도록 벤더 중립.
 *  - 호출자(`PushDeliveryPlugin`)는 토큰 문자열과 메시지만 알고, 벤더 HTTP 프로토콜은 신경 안 씀.
 *  - **실패 분류**: `PushResult` 로 호출자가 토큰 유효성 판단 → 영속 정리 가능.
 *  - **blocking 가정**: 메시지 경로를 막지 않도록 호출자 측에서 async / offload 한다 (이번 슬라이스는 동기 호출, 호출자가 queued=true 인 오프라인 경로에만 쓰므로 지연 허용).
 *
 * 실 FCM HTTP v1 호출은 에서 별도 구현. 이번 단계는 인터페이스 + Noop + Plugin 조립만.
 */
interface PushNotifier {
    /**
     * 단일 토큰으로 푸시 전송.
     * @param request 알림 본문 + 데이터 페이로드 + 목적 토큰
     * @return 성공/실패 구분. 실패 중 `InvalidToken` 은 호출자가 토큰 레코드를 제거해야 함.
     */
    fun push(request: PushNotificationRequest): PushResult
}

data class PushNotificationRequest(
    val platform: String,    // 'fcm' | 'apns' | 'test'
    val token: String,       // 벤더 발급 디바이스 토큰
    val title: String,
    val body: String,
    /** 앱이 탭했을 때 딥링크/라우팅용 커스텀 데이터 (예: { "conversation_id": "u/bob" }). */
    val data: Map<String, String> = emptyMap(),
    /** 알림 채널 — Android Notification Channel / iOS thread-id 매핑 키. */
    val channel: PushChannel = PushChannel.GENERAL,
)

/** 알림 채널 구분 — 사용자가 OS 알림 설정에서 개별 ON/OFF 가능하게. */
enum class PushChannel(val id: String, val displayName: String) {
    DIRECT_MESSAGE("osori.dm", "1:1 메시지"),
    ROOM_MESSAGE("osori.room", "그룹 채팅"),
    MEMO("osori.memo", "쪽지"),
    FILE("osori.file", "파일"),
    GENERAL("osori.general", "기타"),
}

/**
 * Push 결과 분류.
 *  - `Ok`: 벤더가 202/200 반환
 *  - `InvalidToken`: 토큰 만료/해지 (FCM 404 UNREGISTERED / 400 INVALID_ARGUMENT / APNs BadDeviceToken 등)
 *     — 호출자는 해당 토큰을 DB 에서 제거할 것
 *  - `TransientFailure`: 네트워크/레이트리밋/5xx — 재시도 여지 있음 (이번 슬라이스는 재시도 안 함)
 */
sealed class PushResult {
    object Ok : PushResult()
    data class InvalidToken(val reason: String) : PushResult()
    data class TransientFailure(val reason: String) : PushResult()
}

/**
 * 기본 Noop 구현. 운영 FCM credentials 가 없을 때 / 테스트에서 사용.
 * INFO 로 push 내용만 기록하고 항상 Ok 반환. 실제 네트워크 호출 없음.
 */
class NoopPushNotifier : PushNotifier {
    private val log = LoggerFactory.getLogger("com.osori.push.NoopPushNotifier")
    override fun push(request: PushNotificationRequest): PushResult {
        log.info(
            "[noop-push] platform={} channel={} token={} title='{}' body='{}' data={}",
            request.platform,
            request.channel.id,
            request.token.take(12) + "...",
            request.title,
            request.body,
            request.data,
        )
        return PushResult.Ok
    }
}

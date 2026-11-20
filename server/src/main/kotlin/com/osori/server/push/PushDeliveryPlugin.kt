package com.osori.server.push

import com.osori.server.db.PushTokenRepository
import com.osori.server.plugin.MessageSentEvent
import com.osori.server.plugin.PluginContext
import com.osori.server.plugin.ServerPlugin
import org.slf4j.LoggerFactory

/**
 * 오프라인 수신자에게 푸시를 보내는 서버 플러그인.
 *
 * **동작 흐름**
 *  1. `TalkRouter.persistOffline()` 성공 시 `MessageSentEvent(queued=true)` 가 발화됨.
 *  2. 본 플러그인은 `onMessageSent` 훅에서 `queued=true` 만 필터 (온라인 메시지는 이미 즉시 배달).
 *  3. 수신자(`toUserId`)의 모든 디바이스 토큰을 `PushTokenRepository.listByUser()` 로 조회.
 *  4. 각 토큰에 대해 `notifier.push()` 호출. 결과에 따라:
 *     - `Ok` → 토큰 `touch()` (last_seen_at 갱신)
 *     - `InvalidToken` → `deleteByToken()` 로 정리
 *     - `TransientFailure` → 재시도 안 함 (이번 슬라이스). 로그만 남김.
 *
 * **왜 플러그인으로?**
 *  - 핸들러 로직과 완전 분리 → TalkRouter 코드 변경 없음.
 *  - PluginRegistry 의 예외 격리로 push 실패가 메시지 경로를 깨지 않음.
 *  - 레지스트리에서 등록 해제만으로 비활성 가능 (운영 토글).
 *
 * **제한사항**
 *  - 동기 호출. 수신자 토큰이 여러 개면 순차 발송으로 약간의 레이턴시.
 *  - 재시도/backoff 없음. TransientFailure 는 손실 후보).
 *  - 메시지 본문을 그대로 body 에 넣음. 민감정보 마스킹은 추후 정책화.
 */
class PushDeliveryPlugin(
    private val tokenRepo: PushTokenRepository,
    private val notifier: PushNotifier,
    private val titleProvider: (fromUserId: String) -> String = { "$it 님의 메시지" },
) : ServerPlugin {

    override val id: String = "com.osori.plugin.push-delivery"
    override val name: String = "Push Delivery Plugin"
    override val version: String = "0.1.0"

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onStart(ctx: PluginContext) {
        log.info("PushDeliveryPlugin 시작 notifier={}", notifier.javaClass.simpleName)
    }

    override fun onMessageSent(event: MessageSentEvent) {
        if (!event.queued) {
            return // 온라인 즉시 배달 메시지는 push 불필요
        }
        val tokens = runCatching { tokenRepo.listByUser(event.toUserId) }
            .onFailure { log.warn("push 대상 토큰 조회 실패 userId={}", event.toUserId, it) }
            .getOrDefault(emptyList())

        if (tokens.isEmpty()) {
            log.debug("push skip — 등록 토큰 없음 userId={}", event.toUserId)
            return
        }

        val title = titleProvider(event.fromUserId)
        val body = "새 메시지가 도착했습니다." // MVP: 본문 마스킹
        val data = mapOf(
            "from_user_id" to event.fromUserId,
            "server_msg_id" to event.serverTalkId,
        )

        var ok = 0
        var invalid = 0
        var transient = 0
        for (tk in tokens) {
            val result = runCatching {
                notifier.push(
                    PushNotificationRequest(
                        platform = tk.platform,
                        token = tk.token,
                        title = title,
                        body = body,
                        data = data,
                        channel = PushChannel.DIRECT_MESSAGE,
                    ),
                )
            }.getOrElse { t ->
                log.warn("notifier 예외 token={} err={}", tk.token.take(8), t.message)
                PushResult.TransientFailure(t.message ?: "exception")
            }
            when (result) {
                is PushResult.Ok -> {
                    ok++
                    runCatching { tokenRepo.touch(tk.userId, tk.deviceId) }
                }
                is PushResult.InvalidToken -> {
                    invalid++
                    log.info("invalid token 제거 userId={} device={} reason={}", tk.userId, tk.deviceId, result.reason)
                    runCatching { tokenRepo.deleteByToken(tk.token) }
                }
                is PushResult.TransientFailure -> {
                    transient++
                    log.warn("push transient fail userId={} device={} reason={}", tk.userId, tk.deviceId, result.reason)
                }
            }
        }
        log.info(
            "push delivered to={} from={} msgId={} tokens={} ok={} invalid={} transient={}",
            event.toUserId, event.fromUserId, event.serverTalkId, tokens.size, ok, invalid, transient,
        )
    }
}

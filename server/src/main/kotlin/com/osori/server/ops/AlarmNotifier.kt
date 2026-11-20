package com.osori.server.ops

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 운영 알람 — webhook 채널로 임계 이벤트 통지.
 *
 * 기본 동작:
 *   - 환경변수 `OSORI_ALARM_SLACK_WEBHOOK` 가 있으면 Slack Incoming Webhook 으로 전송
 *   - 없으면 NoopAlarmNotifier 가 WARN 로그만 남김
 *   - 동일 알람 5분 dedup — 같은 키의 알람은 5분 안에 한 번만 송신 (시끄러움 방지)
 */
interface AlarmNotifier {
    fun fire(key: String, severity: Severity, title: String, body: String = "")

    enum class Severity { INFO, WARN, ERROR }

    companion object {
        fun fromEnv(): AlarmNotifier {
            val slack = System.getenv("OSORI_ALARM_SLACK_WEBHOOK")
            return if (!slack.isNullOrBlank()) SlackWebhookNotifier(slack)
            else NoopAlarmNotifier
        }
    }
}

object NoopAlarmNotifier : AlarmNotifier {
    private val log = LoggerFactory.getLogger("com.osori.ops.alarm")
    override fun fire(key: String, severity: AlarmNotifier.Severity, title: String, body: String) {
        log.warn("[alarm:{}] {} — {} {}", severity, key, title, body)
    }
}

class SlackWebhookNotifier(private val webhookUrl: String) : AlarmNotifier {
    private val log = LoggerFactory.getLogger("com.osori.ops.alarm.slack")
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val lastFiredKey = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val dedupWindowMs = 5 * 60 * 1000L

    override fun fire(key: String, severity: AlarmNotifier.Severity, title: String, body: String) {
        val now = System.currentTimeMillis()
        val last = lastFiredKey[key]
        if (last != null && now - last < dedupWindowMs) {
            log.debug("[alarm dedup] {} (last fired {} ms ago)", key, now - last)
            return
        }
        lastFiredKey[key] = now

        val emoji = when (severity) {
            AlarmNotifier.Severity.INFO -> ":information_source:"
            AlarmNotifier.Severity.WARN -> ":warning:"
            AlarmNotifier.Severity.ERROR -> ":rotating_light:"
        }
        // Slack JSON payload (escape minimal)
        val payload = """{"text":"$emoji *오소리 [$severity]* $title\n${body.escape()}"}"""
        val req = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8))
            .build()
        runCatching {
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("Slack webhook 응답 코드 {}, body={}", resp.statusCode(), resp.body().take(200))
            }
        }.onFailure { log.warn("Slack webhook 송신 실패", it) }
    }

    private fun String.escape(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
}

package com.osori.server.metrics

import java.util.concurrent.atomic.AtomicLong

/**
 * 메트릭 레지스트리. Micrometer 의존성 없이 직접 Prometheus exposition format 으로 노출.
 *
 * 사용:
 *   - 핫 패스에서 `Metrics.messagesSent.incrementAndGet()` 식 호출
 *   - `/metrics` endpoint 가 [render] 결과를 text/plain 으로 응답
 *   - 게이지 (현재 세션 수 등) 는 외부에서 매번 계산해서 [render] 호출 시점에 sampler 호출
 */
object Metrics {

    // ─── Counter (단조 증가) ───
    val messagesSent      = AtomicLong()
    val messagesDelivered = AtomicLong()
    val messagesQueued    = AtomicLong()
    val messagesRateLimit = AtomicLong()

    val memoSent     = AtomicLong()
    val memoPushed   = AtomicLong()
    val memoRead     = AtomicLong()
    val memoDeleted  = AtomicLong()

    val roomMessagesSent = AtomicLong()
    val roomBroadcasts   = AtomicLong()

    val filesUploaded   = AtomicLong()
    val filesDownloaded = AtomicLong()

    val loginSuccess = AtomicLong()
    val loginFailure = AtomicLong()

    // ─── Gauge (외부 sampler) ───
    @Volatile var sessionCountSampler: () -> Int = { 0 }
    @Volatile var dbPoolActiveSampler: () -> Int = { -1 }
    @Volatile var dbPoolIdleSampler:   () -> Int = { -1 }
    /** 디스크 잔여 비율 × 1000 (정수로). -1 = 미측정. */
    val diskUsableRatioMillis = AtomicLong(-1L)

    private val startMillis = System.currentTimeMillis()

    /** Prometheus exposition format (v0.0.4). 모든 메트릭을 text/plain 으로 직렬화. */
    fun render(): String = buildString {
        // 카운터
        line("osori_messages_sent_total",      messagesSent.get())
        line("osori_messages_delivered_total", messagesDelivered.get())
        line("osori_messages_queued_total",    messagesQueued.get())
        line("osori_messages_rate_limited_total", messagesRateLimit.get())

        line("osori_memo_sent_total",    memoSent.get())
        line("osori_memo_pushed_total",  memoPushed.get())
        line("osori_memo_read_total",    memoRead.get())
        line("osori_memo_deleted_total", memoDeleted.get())

        line("osori_room_messages_sent_total", roomMessagesSent.get())
        line("osori_room_broadcasts_total",    roomBroadcasts.get())

        line("osori_files_uploaded_total",   filesUploaded.get())
        line("osori_files_downloaded_total", filesDownloaded.get())

        line("osori_login_success_total", loginSuccess.get())
        line("osori_login_failure_total", loginFailure.get())

        // 게이지 (sampler 호출)
        gauge("osori_session_count", sessionCountSampler().toLong())
        val dbA = dbPoolActiveSampler()
        if (dbA >= 0) gauge("osori_db_pool_active", dbA.toLong())
        val dbI = dbPoolIdleSampler()
        if (dbI >= 0) gauge("osori_db_pool_idle", dbI.toLong())

        // 디스크
        val diskMillis = diskUsableRatioMillis.get()
        if (diskMillis >= 0) gauge("osori_disk_usable_ratio_per_mille", diskMillis)

        // 빌드 / 업타임
        gauge("osori_uptime_seconds", (System.currentTimeMillis() - startMillis) / 1000)
        // 빌드 info 라벨 — version=0.1.0
        appendLine("# HELP osori_build_info Build metadata")
        appendLine("# TYPE osori_build_info gauge")
        appendLine("""osori_build_info{version="0.1.0"} 1""")
    }

    private fun StringBuilder.line(name: String, value: Long) {
        appendLine("# TYPE $name counter")
        appendLine("$name $value")
    }

    private fun StringBuilder.gauge(name: String, value: Long) {
        appendLine("# TYPE $name gauge")
        appendLine("$name $value")
    }

    /** 테스트 / 운영자 명령으로 리셋. */
    fun reset() {
        listOf(messagesSent, messagesDelivered, messagesQueued, messagesRateLimit,
            memoSent, memoPushed, memoRead, memoDeleted,
            roomMessagesSent, roomBroadcasts,
            filesUploaded, filesDownloaded,
            loginSuccess, loginFailure)
            .forEach { it.set(0) }
    }
}

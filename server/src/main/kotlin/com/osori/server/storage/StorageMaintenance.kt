package com.osori.server.storage

import com.osori.server.db.Database
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 */411 스토리지 유지보수 백그라운드 코루틴.
 *
 *  - **첨부 보존**: 매 6시간 마다 `file_uploads.created_at` 이 `retentionDays` 초과한 행을
 *    DB 삭제 + 디스크 파일 unlink. status=open(미완료) 도 같이 정리 (orphan).
 *  - **디스크 알람**: 매 5분 마다 `storageRoot` 파일시스템의 usable 공간 체크.
 *    임계값(`alarmThresholdRatio`, 기본 5%) 미만이면 WARN.
 *
 *  운영자 트뢔블슈팅용 — 로그 모니터링 또는 Prometheus `osori_disk_usable_ratio` (gauge) 로 감시.
 */
class StorageMaintenance(
    private val db: Database?,
    private val storageRoot: Path,
    private val retentionDays: Int = System.getenv("OSORI_FILE_RETENTION_DAYS")?.toIntOrNull() ?: 90,
    private val alarmThresholdRatio: Double =
        (System.getenv("OSORI_DISK_ALARM_RATIO")?.toDoubleOrNull() ?: 0.05).coerceIn(0.0, 1.0),
    private val alarmNotifier: com.osori.server.ops.AlarmNotifier = com.osori.server.ops.AlarmNotifier.fromEnv(),
) {
    private val logger = LoggerFactory.getLogger("com.osori.server.StorageMaintenance")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cleanupJob: Job? = null
    private var alarmJob: Job? = null

    fun start() {
        cleanupJob = scope.launch {
            // 시작 직후 1회 + 6시간 주기
            runCatching { cleanupOnce() }.onFailure { logger.error("attachment cleanup 실패", it) }
            while (isActive) {
                delay(Duration.ofHours(6).toMillis())
                runCatching { cleanupOnce() }.onFailure { logger.error("attachment cleanup 실패", it) }
            }
        }
        alarmJob = scope.launch {
            while (isActive) {
                runCatching { checkDiskOnce() }.onFailure { logger.warn("disk alarm 체크 실패", it) }
                delay(Duration.ofMinutes(5).toMillis())
            }
        }
        logger.info(
            "StorageMaintenance 시작 root={} retention_days={} alarm_threshold_ratio={}",
            storageRoot, retentionDays, alarmThresholdRatio,
        )
    }

    fun stop() {
        cleanupJob?.cancel()
        alarmJob?.cancel()
    }

    /** 보존 기간 초과 첨부 정리. internal 로 테스트 노출. */
    internal fun cleanupOnce() {
        val database = db ?: return
        val cutoff = java.time.Instant.now().minus(Duration.ofDays(retentionDays.toLong()))
        val deleted = database.withConnection { conn ->
            // 1) 정리 대상 storage_path 수집
            val toDelete = mutableListOf<Pair<java.util.UUID, String>>()
            conn.prepareStatement(
                "SELECT upload_id, storage_path FROM file_uploads WHERE created_at < ?"
            ).use { ps ->
                ps.setTimestamp(1, java.sql.Timestamp.from(cutoff))
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        toDelete += (rs.getObject(1) as java.util.UUID) to rs.getString(2)
                    }
                }
            }
            // 2) 디스크 unlink
            var unlinked = 0
            for ((_, sp) in toDelete) {
                if (sp.isNullOrEmpty()) continue
                runCatching { Files.deleteIfExists(Path.of(sp)) }.onSuccess { if (it) unlinked++ }
            }
            // 3) DB 삭제
            val deletedRows = if (toDelete.isNotEmpty()) {
                conn.prepareStatement(
                    "DELETE FROM file_uploads WHERE created_at < ?"
                ).use { ps ->
                    ps.setTimestamp(1, java.sql.Timestamp.from(cutoff))
                    ps.executeUpdate()
                }
            } else 0
            logger.info(
                "첨부 정리 완료 candidates={} unlinked={} dbDeleted={} cutoff={}",
                toDelete.size, unlinked, deletedRows, cutoff,
            )
            deletedRows
        }
        com.osori.server.metrics.Metrics.filesUploaded // touch — counter 는 변하지 않음, 단순 통계
        if (deleted > 0) {
            logger.info(" 보존 기간 초과 첨부 {} 건 삭제", deleted)
        }
    }

    /** 디스크 잔여 공간 체크. usable / total 비율이 임계값 미만이면 WARN. */
    internal fun checkDiskOnce() {
        if (!Files.exists(storageRoot)) {
            runCatching { Files.createDirectories(storageRoot) }
        }
        val store = Files.getFileStore(storageRoot)
        val total = store.totalSpace
        val usable = store.usableSpace
        val ratio = if (total > 0) usable.toDouble() / total else 1.0
        // 메트릭에 게이지로 push — Metrics 의 sampler 패턴은 read-time 호출. 여기는 별도 atomic.
        com.osori.server.metrics.Metrics.diskUsableRatioMillis.set((ratio * 1000).toLong())
        if (ratio < alarmThresholdRatio) {
            val msg = "디스크 잔여 ${"%.2f".format(ratio * 100)}% (임계값 ${"%.0f".format(alarmThresholdRatio * 100)}%)"
            logger.warn("🟠 디스크 잔여 공간 임계값 미만 — usable={} bytes total={} bytes — {}", usable, total, msg)
            alarmNotifier.fire(
                key = "disk-low",
                severity = com.osori.server.ops.AlarmNotifier.Severity.WARN,
                title = "디스크 잔여 공간 부족",
                body = "$msg\nstorage_root=$storageRoot\nusable=$usable bytes / total=$total bytes",
            )
        }
    }
}

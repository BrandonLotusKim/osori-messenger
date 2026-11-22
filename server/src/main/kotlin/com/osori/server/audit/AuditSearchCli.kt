package com.osori.server.audit

import com.osori.server.db.Database
import kotlin.system.exitProcess

/**
 * 운영자용 감사 로그 검색 CLI.
 *
 * 사용:
 *   ./gradlew auditSearch --args="auth.login.failed 50"      # 최근 50건
 *   ./gradlew auditSearch --args="auth.logout.explicit 100"
 *
 * 출력: TSV — event_id \t server_ts \t actor \t target \t payload
 *        (운영자가 grep / awk 로 후처리 가능)
 */
object AuditSearchCli

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("사용법: auditSearch <event_type> [limit=50]")
        System.err.println("예: auditSearch auth.login.failed 100")
        System.err.println("주요 event_type:")
        System.err.println("  - auth.login.success / auth.login.failed")
        System.err.println("  - auth.logout / auth.session.superseded(다른 곳 로그인으로 끊김)")
        System.err.println("  - room.created / room.invited / room.renamed / room.left")
        System.err.println("  - room.msg.sent(대화방 메시지: ID·크기만, 본문 없음) / msg.sent(옛 1:1)")
        System.err.println("  - file.uploaded / file.downloaded")
        exitProcess(2)
    }
    val eventType = args[0]
    val limit = args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 5000) ?: 50

    val db = try { Database.fromEnv() } catch (e: Exception) {
        System.err.println("ERROR: DB 연결 실패 — OSORI_DB_URL/USER/PASS 환경변수 확인: ${e.message}")
        exitProcess(4)
    }
    try {
        val repo = AuditRepository(db)
        val rows = repo.findLatestByType(eventType, limit)
        if (rows.isEmpty()) {
            println(" event_type=$eventType 결과 없음")
            return
        }
        // TSV 헤더
        println("event_id\tserver_ts\tactor_user_id\ttarget\tpayload")
        for (r in rows) {
            println(buildString {
                append(r.eventId); append('\t')
                append(r.serverTs); append('\t')
                append(r.actorUserId ?: ""); append('\t')
                append(r.target ?: ""); append('\t')
                append(r.payloadJson.replace('\n', ' '))
            })
        }
        System.err.println(" ${rows.size}건 (limit=$limit)")
    } catch (e: Exception) {
        System.err.println("ERROR: 검색 실패: ${e.message}")
        e.printStackTrace()
        exitProcess(5)
    } finally {
        runCatching { db.close() }
    }
}

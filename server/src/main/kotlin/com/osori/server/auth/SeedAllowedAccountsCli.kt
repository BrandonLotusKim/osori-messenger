package com.osori.server.auth

import com.osori.server.db.Database
import kotlin.system.exitProcess

/**
 * 개발·시험 환경용 계정 시딩 CLI (운영에서는 사용 금지).
 *
 * [AllowedAccounts.IDS] (admin, test1~test10) 를 전부 존재 보장 + 비밀번호를 "1234" 로
 * 강제 재설정한다. [PasswordResetCli] 와 달리 8자 미만 비밀번호 가드를 두지 않는다 —
 * 개발용 시드라 의도적으로 우회.
 *
 * 사용법 (gradle):
 *   ./gradlew seedAllowedAccounts --console=plain
 *
 * 환경변수: OSORI_DB_URL / OSORI_DB_USER / OSORI_DB_PASS (서버와 동일)
 */
object SeedAllowedAccountsCli

private const val SEED_PASSWORD = "1234"

fun main() {
    val db = try {
        Database.fromEnv()
    } catch (e: Exception) {
        System.err.println("ERROR: DB 연결 실패 — OSORI_DB_URL/USER/PASS 환경변수 확인: ${e.message}")
        exitProcess(1)
    }

    val users = com.osori.server.db.UserRepository(db)
    try {
        val hash = PasswordHasher.hash(SEED_PASSWORD)
        for (userId in AllowedAccounts.IDS.sorted()) {
            users.ensureExists(userId)
            users.setPasswordHash(userId, hash)
            println("[seed] $userId -> password_hash 설정 완료")
        }
        println("[seed] 완료 — ${AllowedAccounts.IDS.size}개 계정, 비밀번호=\"$SEED_PASSWORD\"")
    } catch (e: Exception) {
        System.err.println("ERROR: 시딩 실패: ${e.message}")
        e.printStackTrace()
        exitProcess(2)
    } finally {
        runCatching { db.close() }
    }
}

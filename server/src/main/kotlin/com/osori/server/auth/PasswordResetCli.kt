package com.osori.server.auth

import com.osori.server.db.Database
import com.osori.server.db.UserRepository
import kotlin.system.exitProcess

/**
 * 운영자용 비밀번호 강제 재설정 CLI.
 *
 * 사용 예 (gradle):
 *   ./gradlew passwordReset --args="alice new-secure-password"
 *
 * 또는 직접 jar:
 *   java -cp ... com.osori.server.auth.PasswordResetCliKt alice new-secure-password
 *
 * 동작:
 *   1. 사용자가 STRICT 모드용 비밀번호를 잊어버렸을 때 운영자가 임시 비밀번호로 재설정
 *   2. Argon2id 해시 생성 후 auth_users.password_hash 갱신
 *   3. 사용자에게 임시 비밀번호 안내 → 다음 로그인 시 변경 권유 (변경 UI 는 v0.2)
 *
 * 환경변수: OSORI_DB_URL / OSORI_DB_USER / OSORI_DB_PASS (서버와 동일)
 */
object PasswordResetCli

fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("사용법: passwordReset <userId> <newPassword>")
        System.err.println("예: passwordReset alice TempPass123!")
        exitProcess(2)
    }
    val userId = args[0]
    val newPassword = args[1]
    if (userId.isBlank() || newPassword.length < 8) {
        System.err.println("ERROR: userId 가 비었거나 비밀번호가 8자 미만")
        exitProcess(3)
    }

    val db = try { Database.fromEnv() } catch (e: Exception) {
        System.err.println("ERROR: DB 연결 실패 — OSORI_DB_URL/USER/PASS 환경변수 확인: ${e.message}")
        exitProcess(4)
    }
    val users = UserRepository(db)
    try {
        users.ensureExists(userId)
        val hash = PasswordHasher.hash(newPassword)
        users.setPasswordHash(userId, hash)
        println(" 비밀번호 재설정 완료 — userId=$userId")
        println("           사용자에게 임시 비밀번호를 안전한 채널로 전달하고, 첫 로그인 후 변경 권유.")
        println("           이 명령의 실행 자체도 감사 로그에 별도 기록되지 않으므로 운영 SOP 에 따라 수기 기록 필수.")
    } catch (e: Exception) {
        System.err.println("ERROR: 재설정 실패: ${e.message}")
        e.printStackTrace()
        exitProcess(5)
    } finally {
        runCatching { db.close() }
    }
}

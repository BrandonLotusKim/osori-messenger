package com.osori.server.auth

import com.osori.server.db.Database
import com.osori.server.db.UserRepository
import kotlin.system.exitProcess

/**
 * 운영자용 display_name 변경 CLI.
 *
 * 사용:
 *   ./gradlew setDisplayName --args="alice 김알리"
 */
object SetDisplayNameCli

fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("사용법: setDisplayName <userId> <newDisplayName>")
        exitProcess(2)
    }
    val userId = args[0]
    val newName = args.drop(1).joinToString(" ") // 공백 포함 이름 처리
    if (userId.isBlank() || newName.isBlank()) {
        System.err.println("ERROR: 인자 비어있음")
        exitProcess(3)
    }
    val db = try { Database.fromEnv() } catch (e: Exception) {
        System.err.println("ERROR: DB 연결 실패: ${e.message}")
        exitProcess(4)
    }
    try {
        val updated = UserRepository(db).setDisplayName(userId, newName)
        if (updated == 0) {
            System.err.println("WARN: userId=$userId 사용자 없음 (먼저 ensureExists 필요)")
            exitProcess(6)
        }
        println(" display_name 변경 완료 — userId=$userId display_name='$newName'")
    } catch (e: Exception) {
        System.err.println("ERROR: 실패: ${e.message}")
        exitProcess(5)
    } finally {
        runCatching { db.close() }
    }
}

package com.osori.server.auth

import de.mkammerer.argon2.Argon2Factory
import de.mkammerer.argon2.Argon2Factory.Argon2Types
import org.slf4j.LoggerFactory

/**
 * Argon2id 기반 비밀번호 해시 래퍼.
 *
 * - 알고리즘: Argon2id (Argon2 저자 권장, OWASP 권장)
 * - 파라미터: iterations=3, memory=64MB(65536 KiB), parallelism=1
 *   (OWASP 2024 권장치의 보수적 설정. 서버 CPU 에 따라 tuning 필요)
 * - 반환 해시 문자열은 self-contained — salt + params 포함, verify 에 추가 인자 불필요
 *
 * Argon2Factory 는 내부적으로 JNA / jvm 백엔드 자동 선택. 싱글톤으로 재사용.
 */
object PasswordHasher {
    private val logger = LoggerFactory.getLogger("com.osori.server.PasswordHasher")
    private val argon2 = Argon2Factory.create(Argon2Types.ARGON2id)

    private const val ITERATIONS = 3
    private const val MEMORY_KIB = 65_536  // 64 MiB
    private const val PARALLELISM = 1

    /**
     * 평문 비밀번호를 해시. 호출 후 원본 char array 는 0 으로 덮어써진다.
     */
    fun hash(rawPassword: String): String {
        val chars = rawPassword.toCharArray()
        return try {
            argon2.hash(ITERATIONS, MEMORY_KIB, PARALLELISM, chars)
        } finally {
            argon2.wipeArray(chars)
        }
    }

    /**
     * 저장된 해시와 입력 평문을 비교.
     * @return true = 일치
     */
    fun verify(storedHash: String, rawPassword: String): Boolean {
        if (storedHash.isBlank()) return false
        val chars = rawPassword.toCharArray()
        return try {
            argon2.verify(storedHash, chars)
        } catch (t: Throwable) {
            // 해시 포맷 오류 등 — 로그만 남기고 실패로 간주
            logger.warn("Argon2 verify 오류: {}", t.message)
            false
        } finally {
            argon2.wipeArray(chars)
        }
    }
}

package com.osori.server.db

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

private val logger = LoggerFactory.getLogger("com.osori.server.UserRepository")

/**
 * 사용자 Repository.
 *
 * MVP 정책: 로그인 시도한 user_id 가 존재하지 않으면 자동 생성 (passwordHash 는 빈 문자열).
 * Phase 5 이후 실제 가입 플로우가 생기면 create 는 관리자/가입 엔드포인트에서만 호출되도록 제한.
 */
open class UserRepository(private val db: Database) {

    data class UserRow(
        val userId: String,
        val displayName: String,
        val createdAt: Instant,
    )

    open fun findById(userId: String): UserRow? = db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT user_id, display_name, created_at FROM auth_users WHERE user_id = ?",
        ).use { ps ->
            ps.setString(1, userId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    UserRow(
                        userId = rs.getString(1),
                        displayName = rs.getString(2),
                        createdAt = rs.getTimestamp(3).toInstant(),
                    )
                } else null
            }
        }
    }

    /** 존재하지 않으면 생성하고 true 리턴. 이미 존재하면 false. */
    open fun createIfAbsent(userId: String, displayName: String = userId): Boolean =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO auth_users (user_id, password_hash, display_name, created_at)
                VALUES (?, '', ?, ?)
                ON CONFLICT (user_id) DO NOTHING
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, displayName)
                ps.setTimestamp(3, Timestamp.from(Instant.now()))
                val inserted = ps.executeUpdate() > 0
                if (inserted) logger.info("신규 사용자 자동 생성 userId={}", userId)
                inserted
            }
        }

    /** MVP 용: 존재 확인 + 자동 생성. 매 로그인마다 호출. */
    open fun ensureExists(userId: String): UserRow {
        findById(userId)?.let { return it }
        createIfAbsent(userId)
        return findById(userId)
            ?: error("사용자 자동 생성 후 조회 실패 userId=$userId")
    }

    /** 테스트/관리용 총 사용자 수. */
    open fun count(): Long = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM auth_users").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    /**
     * 저장된 password_hash 조회. 인증 실구현.
     * @return null = 사용자 없음, 빈 문자열 = 비밀번호 미설정 (첫 로그인 대기), 그 외 = Argon2 해시
     */
    open fun getPasswordHash(userId: String): String? = db.withConnection { conn ->
        conn.prepareStatement("SELECT password_hash FROM auth_users WHERE user_id = ?").use { ps ->
            ps.setString(1, userId)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString(1) ?: "" else null
            }
        }
    }

    /**
     * password_hash 갱신. 호출자가 Argon2 해시 문자열을 전달해야 한다 (이 레이어는 해싱 안 함).
     */
    open fun setPasswordHash(userId: String, hash: String) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE auth_users SET password_hash = ? WHERE user_id = ?").use { ps ->
                ps.setString(1, hash)
                ps.setString(2, userId)
                val updated = ps.executeUpdate()
                if (updated == 0) {
                    logger.warn("setPasswordHash: 대상 사용자 없음 userId={}", userId)
                }
            }
        }
    }

    /** 운영자용 display_name 갱신. 사용자 본인 변경 UI 는 v0.2. @return 갱신된 행 수 (0 = 사용자 없음). */
    open fun setDisplayName(userId: String, displayName: String): Int =
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE auth_users SET display_name = ? WHERE user_id = ?").use { ps ->
                ps.setString(1, displayName)
                ps.setString(2, userId)
                ps.executeUpdate()
            }
        }
}

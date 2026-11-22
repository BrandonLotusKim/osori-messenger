package com.osori.server.db

import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.time.Instant

private val logger = LoggerFactory.getLogger("com.osori.server.PushTokenRepository")

/**
 * 푸시 알림 디바이스 토큰 Repository — `push_tokens` 테이블.
 *
 * 핵심 정책:
 *   - **(user_id, device_id) 가 PRIMARY KEY**. 한 사용자가 여러 디바이스 보유 가능.
 *   - platform 은 벤더 태그: 'fcm' | 'apns' | 'test' (자유 문자열).
 *   - 같은 디바이스에서 벤더가 토큰 회전하면 `upsertToken()` 으로 갱신 (멱등).
 *   - `last_seen_at` 은 stale 토큰 청소 배치용 (별도 OC 에서 구현).
 *   - FK 없음 — 푸시 브리지를 별도 DB 인스턴스로 이관할 여지.
 *
 * 이번 슬라이스는 DB 레이어만. proto/Handler/실 FCM 호출은 /c.
 */
open class PushTokenRepository(private val db: Database) {

    data class PushToken(
        val userId: String,
        val deviceId: String,
        val platform: String,
        val token: String,
        val lastSeenAt: Instant,
        val createdAt: Instant,
    )

    /**
     * 디바이스별 토큰 upsert. 같은 (user_id, device_id) 가 있으면 platform/token/last_seen_at 만 갱신.
     * 새 레코드는 created_at 이 now() 로 기본 설정됨.
     */
    open fun upsertToken(
        userId: String,
        deviceId: String,
        platform: String,
        token: String,
        now: Instant = Instant.now(),
    ): Unit = db.withConnection { conn ->
        conn.prepareStatement(
            """
            INSERT INTO push_tokens (user_id, device_id, platform, token, last_seen_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, device_id) DO UPDATE SET
                platform     = EXCLUDED.platform,
                token        = EXCLUDED.token,
                last_seen_at = EXCLUDED.last_seen_at
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, deviceId)
            ps.setString(3, platform)
            ps.setString(4, token)
            ps.setTimestamp(5, Timestamp.from(now))
            ps.setTimestamp(6, Timestamp.from(now))
            ps.executeUpdate()
        }
    }

    /** 같은 디바이스 unregister (로그아웃 시, 앱 삭제 시). */
    open fun deleteByDevice(userId: String, deviceId: String): Int = db.withConnection { conn ->
        conn.prepareStatement(
            "DELETE FROM push_tokens WHERE user_id = ? AND device_id = ?",
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, deviceId)
            ps.executeUpdate()
        }
    }

    /**
     * 벤더가 토큰을 무효로 판정(404/410 등)했을 때 토큰 문자열로 일괄 삭제.
     * 드물게 같은 token 이 여러 row 에 있을 수 있어 Int(삭제건수) 반환.
     */
    open fun deleteByToken(token: String): Int = db.withConnection { conn ->
        conn.prepareStatement(
            "DELETE FROM push_tokens WHERE token = ?",
        ).use { ps ->
            ps.setString(1, token)
            ps.executeUpdate()
        }
    }

    /** 특정 사용자의 모든 디바이스 토큰. 푸시 발송 시 수신자 fan-out 에 사용. */
    open fun listByUser(userId: String): List<PushToken> = db.withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT user_id, device_id, platform, token, last_seen_at, created_at
            FROM push_tokens WHERE user_id = ?
            ORDER BY last_seen_at DESC
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, userId)
            ps.executeQuery().use { rs ->
                val result = mutableListOf<PushToken>()
                while (rs.next()) {
                    result += PushToken(
                        userId = rs.getString(1),
                        deviceId = rs.getString(2),
                        platform = rs.getString(3),
                        token = rs.getString(4),
                        lastSeenAt = rs.getTimestamp(5).toInstant(),
                        createdAt = rs.getTimestamp(6).toInstant(),
                    )
                }
                result
            }
        }
    }

    /** last_seen_at 만 갱신 (토큰 유효성 확인 시). */
    open fun touch(userId: String, deviceId: String, now: Instant = Instant.now()): Int = db.withConnection { conn ->
        conn.prepareStatement(
            "UPDATE push_tokens SET last_seen_at = ? WHERE user_id = ? AND device_id = ?",
        ).use { ps ->
            ps.setTimestamp(1, Timestamp.from(now))
            ps.setString(2, userId)
            ps.setString(3, deviceId)
            ps.executeUpdate()
        }
    }

    /** 테스트/관리용 전체 토큰 개수. */
    open fun count(): Long = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM push_tokens").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    open fun deleteAll(): Int = db.withConnection { conn ->
        conn.createStatement().use { it.executeUpdate("DELETE FROM push_tokens") }
    }
}

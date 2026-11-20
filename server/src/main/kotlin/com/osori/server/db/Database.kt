package com.osori.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("com.osori.server.Database")

/**
 * 오소리 메신저 DB 커넥션 풀 + 순수 JDBC 래퍼.
 *
 * 환경변수:
 *   OSORI_DB_URL   (기본값: jdbc:postgresql://localhost:5432/osori)
 *   OSORI_DB_USER  (기본값: osori)
 *   OSORI_DB_PASS  (기본값: osori_dev_pw)
 *   OSORI_DB_POOL_SIZE (기본값: 10)
 *
 * MVP 는 순수 JDBC 로 가고, 스키마가 고정되면 추후 Exposed/jOOQ 이관 검토.
 * Repository 에는 Database 인스턴스를 주입한다.
 */
open class Database(val dataSource: DataSource) {

    /** 단일 커넥션 대여 후 자동 반납. 자동 커밋. */
    open fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use { conn -> block(conn) }

    /** 트랜잭션 헬퍼. 블록 안에서 예외 발생 시 롤백. */
    open fun <T> inTransaction(block: (Connection) -> T): T =
        dataSource.connection.use { conn ->
            val previous = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                runCatching { conn.autoCommit = previous }
            }
        }

    fun close() {
        (dataSource as? HikariDataSource)?.close()
    }

    /**
     * 서버 기동 시 호출 — 필수 테이블이 모두 존재하는지 검증.
     * 누락된 테이블이 있으면 운영자가 schema 적용을 깜빡한 것이므로 명확한 에러로 alarm.
     * @return 누락된 테이블 이름 리스트 (빈 리스트 = OK)
     */
    fun verifyRequiredTables(): List<String> {
        val required = listOf(
            "auth_users",
            "talk_messages", "talk_outbox",
            "talk_rooms", "talk_room_members", "talk_room_messages",
            "file_uploads",
            "org_units", "org_members",
            "log_events", "push_tokens",
            "messages", "message_recipients",
        )
        val present = mutableSetOf<String>()
        withConnection { conn ->
            conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) present += rs.getString(1)
                }
            }
        }
        return required.filterNot { it in present }
    }

    companion object {
        fun fromEnv(): Database {
            val url = System.getenv("OSORI_DB_URL")
                ?: "jdbc:postgresql://localhost:5432/osori"
            val user = System.getenv("OSORI_DB_USER") ?: "osori"
            val pass = System.getenv("OSORI_DB_PASS") ?: "osori_dev_pw"
            val poolSize = System.getenv("OSORI_DB_POOL_SIZE")?.toIntOrNull() ?: 10

            val config = HikariConfig().apply {
                jdbcUrl = url
                username = user
                password = pass
                maximumPoolSize = poolSize
                driverClassName = "org.postgresql.Driver"
                poolName = "osori-db-pool"
                connectionTimeout = 10_000
                validationTimeout = 5_000
            }

            logger.info("DB 풀 초기화 url={} user={} poolSize={}", url, user, poolSize)
            return Database(HikariDataSource(config))
        }
    }
}

package com.osori.server.testutil

import com.osori.server.db.Database
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 통합 테스트용 Postgres 접속 헬퍼.
 *
 * 이름은 "TestContainer" 로 남아있지만 현재 구현은 **외부에서 기동 중인 Postgres 인스턴스에 접속** 한다.
 * Docker Desktop/WSL2 가상화가 비활성인 개발 환경에서 Testcontainers 가 기동 불가였기 때문에 네이티브
 * Postgres 로 우회. 추후 Docker 환경이 복구되면 Testcontainers 싱글톤 버전으로 되돌릴 수 있다.
 *
 * 로컬 기동 가이드 (한 번만):
 *   initdb -D <data> -U postgres -E UTF8 --locale=C --auth-host=trust
 *   pg_ctl -D <data> -l <log> start
 *   createdb -U postgres osori_test
 *
 * 기본 URL/계정은 시스템 속성 또는 환경변수로 오버라이드 가능:
 *   -DOSORI_TEST_DB_URL=...  또는  OSORI_TEST_DB_URL
 *   -DOSORI_TEST_DB_USER=... 또는  OSORI_TEST_DB_USER
 *   -DOSORI_TEST_DB_PASS=... 또는  OSORI_TEST_DB_PASS
 */
object PostgresTestContainer {

    private val url: String =
        prop("OSORI_TEST_DB_URL") ?: "jdbc:postgresql://localhost:5432/osori_test"
    private val user: String =
        prop("OSORI_TEST_DB_USER") ?: "postgres"
    private val pass: String =
        prop("OSORI_TEST_DB_PASS") ?: ""

    private val schemaApplied = AtomicBoolean(false)

    private val dataSource: PGSimpleDataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(url)
            user = this@PostgresTestContainer.user
            password = this@PostgresTestContainer.pass
        }.also { ensureSchema(it) }
    }

    fun database(): Database = Database(dataSource)

    fun truncateAll() {
        database().withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "TRUNCATE TABLE message_recipients, messages, push_tokens, log_events, org_members, org_units, file_uploads, talk_room_messages, talk_room_members, talk_rooms, talk_outbox, talk_messages, auth_users RESTART IDENTITY CASCADE",
                )
            }
        }
    }

    private fun ensureSchema(ds: PGSimpleDataSource) {
        if (!schemaApplied.compareAndSet(false, true)) return
        val schemaPath = Paths.get("src/main/resources/db/schema.sql")
        if (!Files.exists(schemaPath)) return // 외부에서 이미 적용된 것으로 가정
        val sql = Files.readString(schemaPath)
        ds.connection.use { conn ->
            conn.createStatement().use { st -> st.execute(sql) }
        }
    }

    private fun prop(key: String): String? =
        System.getProperty(key) ?: System.getenv(key)
}

package com.osori.server.db

import com.osori.server.testutil.PostgresTestContainer
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PushTokenRepository 통합 테스트 (실 PostgreSQL).
 *
 * 커버:
 *  - upsert 새 토큰 삽입
 *  - upsert 토큰 회전 (platform/token 갱신, created_at 변경 없음)
 *  - deleteByDevice
 *  - deleteByToken (drop 판정 토큰 일괄 삭제)
 *  - listByUser 다중 디바이스, DESC 정렬
 *  - touch 가 last_seen_at 만 갱신
 */
class PushTokenRepositoryIntegrationTest {

    private lateinit var repo: PushTokenRepository

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        val db = PostgresTestContainer.database()
        repo = PushTokenRepository(db)
    }

    @Test
    fun `upsert 는 새 토큰을 삽입하고 count 가 증가한다`() {
        repo.upsertToken("alice", "dev1", "fcm", "tok-abc")
        assertEquals(1L, repo.count())

        val rows = repo.listByUser("alice")
        assertEquals(1, rows.size)
        assertEquals("dev1", rows[0].deviceId)
        assertEquals("fcm", rows[0].platform)
        assertEquals("tok-abc", rows[0].token)
    }

    @Test
    fun `upsert 는 같은 device 의 토큰 회전 시 platform token 만 갱신`() {
        val t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        repo.upsertToken("alice", "dev1", "fcm", "tok-old", t0)
        val firstCreated = repo.listByUser("alice")[0].createdAt

        val t1 = t0.plusSeconds(60)
        repo.upsertToken("alice", "dev1", "fcm", "tok-new", t1)

        assertEquals(1L, repo.count(), "upsert 이므로 row 는 1")
        val rows = repo.listByUser("alice")
        assertEquals("tok-new", rows[0].token)
        assertEquals(firstCreated, rows[0].createdAt, "created_at 은 upsert 시 유지")
        assertTrue(rows[0].lastSeenAt > firstCreated, "last_seen_at 은 갱신됨")
    }

    @Test
    fun `listByUser 는 다중 디바이스를 last_seen_at DESC 로 반환`() {
        val t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        repo.upsertToken("alice", "phone", "fcm", "t1", t0)
        repo.upsertToken("alice", "tablet", "fcm", "t2", t0.plusSeconds(10))
        repo.upsertToken("alice", "desktop", "fcm", "t3", t0.plusSeconds(5))

        val rows = repo.listByUser("alice")
        assertEquals(3, rows.size)
        assertEquals("tablet", rows[0].deviceId, "가장 최근 last_seen_at 이 맨 위")
        assertEquals("desktop", rows[1].deviceId)
        assertEquals("phone", rows[2].deviceId)
    }

    @Test
    fun `listByUser 는 다른 사용자의 토큰을 반환하지 않는다`() {
        repo.upsertToken("alice", "dev1", "fcm", "aaa")
        repo.upsertToken("bob", "dev1", "fcm", "bbb")
        repo.upsertToken("bob", "dev2", "fcm", "ccc")

        assertEquals(1, repo.listByUser("alice").size)
        assertEquals(2, repo.listByUser("bob").size)
        assertEquals(0, repo.listByUser("ghost").size)
    }

    @Test
    fun `deleteByDevice 는 해당 디바이스 레코드만 제거한다`() {
        repo.upsertToken("alice", "phone", "fcm", "t1")
        repo.upsertToken("alice", "tablet", "fcm", "t2")
        repo.upsertToken("bob", "phone", "fcm", "t3")

        val deleted = repo.deleteByDevice("alice", "phone")
        assertEquals(1, deleted)

        val aliceRows = repo.listByUser("alice")
        assertEquals(1, aliceRows.size)
        assertEquals("tablet", aliceRows[0].deviceId)
        assertEquals(1, repo.listByUser("bob").size, "다른 사용자 영향 없음")
    }

    @Test
    fun `deleteByToken 은 토큰 문자열로 검색해 일괄 제거`() {
        // FCM 이 동일 토큰을 다른 사용자에게 부여할 일은 없지만,
        // 벤더 404/410 판정 시 정확한 토큰 문자열로 제거하는 경로 검증.
        repo.upsertToken("alice", "dev1", "fcm", "invalid-token")
        repo.upsertToken("alice", "dev2", "fcm", "valid-token")
        repo.upsertToken("bob", "dev1", "fcm", "invalid-token")

        val deleted = repo.deleteByToken("invalid-token")
        assertEquals(2, deleted)
        assertEquals(1L, repo.count())
        assertEquals("valid-token", repo.listByUser("alice").single().token)
    }

    @Test
    fun `touch 는 last_seen_at 만 갱신하고 토큰은 건드리지 않는다`() {
        val t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        repo.upsertToken("alice", "dev1", "fcm", "tok", t0)
        val before = repo.listByUser("alice").single()

        val t1 = t0.plusSeconds(30)
        val updated = repo.touch("alice", "dev1", t1)
        assertEquals(1, updated)

        val after = repo.listByUser("alice").single()
        assertEquals(before.token, after.token, "토큰 불변")
        assertEquals(before.platform, after.platform)
        assertEquals(before.createdAt, after.createdAt)
        assertTrue(after.lastSeenAt > before.lastSeenAt, "last_seen_at 만 증가")
    }

    @Test
    fun `touch 가 없는 디바이스엔 0을 반환한다`() {
        val updated = repo.touch("ghost", "nope")
        assertEquals(0, updated, "해당 레코드 없으면 0")
    }

    @Test
    fun `platform 은 자유 문자열이라 'apns' 'test' 등도 허용된다`() {
        repo.upsertToken("alice", "iphone", "apns", "apns-tok")
        repo.upsertToken("alice", "harness", "test", "test-tok")

        val rows = repo.listByUser("alice").sortedBy { it.deviceId }
        assertEquals(2, rows.size)
        assertNotNull(rows.find { it.platform == "apns" })
        assertNotNull(rows.find { it.platform == "test" })
    }
}

package com.osori.server.audit

import com.osori.server.testutil.PostgresTestContainer
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AuditRepository 통합 테스트.
 */
class AuditRepositoryIntegrationTest {

    private lateinit var repo: AuditRepository

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        repo = AuditRepository(PostgresTestContainer.database())
    }

    @Test
    fun `record 최소 인자로 이벤트 기록되고 count 증가`() {
        assertEquals(0L, repo.count())
        val id = repo.record("test.simple")
        assertNotNull(id)
        assertEquals(1L, repo.count())
    }

    @Test
    fun `recordLoginSuccess 편의 메서드 — actor target payload 반영`() {
        repo.recordLoginSuccess(userId = "alice", sessionId = "sess-123")

        val rows = repo.findLatestByType("auth.login.success", limit = 5)
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals("auth.login.success", row.eventType)
        assertEquals("alice", row.actorUserId)
        assertEquals("sess-123", row.target)
        assertTrue(row.payloadJson.contains("\"sessionId\""))
        assertTrue(row.payloadJson.contains("sess-123"))
    }

    @Test
    fun `recordLoginFailed — actor null 허용 + reason payload`() {
        repo.recordLoginFailed(userId = null, reason = "invalid credentials")

        val rows = repo.findLatestByType("auth.login.failed")
        assertEquals(1, rows.size)
        assertNull(rows[0].actorUserId)
        assertTrue(rows[0].payloadJson.contains("invalid credentials"))
    }

    @Test
    fun `recordMessageSent — JSON payload 에 숫자 필드 직렬화`() {
        repo.recordMessageSent(fromUserId = "bob", toUserId = "alice", messageId = "msg-1", bytes = 1024)

        val rows = repo.findLatestByType("msg.sent")
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals("bob", row.actorUserId)
        assertEquals("msg-1", row.target)
        // JSONB 는 저장 시 공백/순서가 달라질 수 있어 부분 문자열 검사만.
        assertTrue(row.payloadJson.contains("\"toUserId\""))
        assertTrue(row.payloadJson.contains("alice"))
        assertTrue(row.payloadJson.contains("\"bytes\""))
        assertTrue(row.payloadJson.contains("1024"))
    }

    @Test
    fun `findLatestByType — 최신순 정렬 + limit 적용`() {
        repo.recordLoginSuccess("alice", "s1")
        repo.recordLoginSuccess("alice", "s2")
        repo.recordLoginSuccess("alice", "s3")

        val rows = repo.findLatestByType("auth.login.success", limit = 2)
        assertEquals(2, rows.size)
        // DESC 정렬이므로 s3 먼저
        assertEquals("s3", rows[0].target)
        assertEquals("s2", rows[1].target)
    }

    @Test
    fun `payload JSON 이스케이프 — 따옴표와 백슬래시`() {
        repo.recordRoomCreated(ownerUserId = "alice", roomId = "room-1", name = "회의실 \"A\\B\"")

        val rows = repo.findLatestByType("room.created")
        assertEquals(1, rows.size)
        // JSON 이 깨지지 않고 JSONB 로 저장되면 query 가 성공한 것 자체가 검증
        assertTrue(rows[0].payloadJson.contains("room-1"))
    }

    @Test
    fun `deleteAll — 전체 삭제`() {
        repo.recordLoginSuccess("a", "s1")
        repo.recordLoginSuccess("b", "s2")
        assertEquals(2L, repo.count())

        repo.deleteAll()

        assertEquals(0L, repo.count())
    }
}

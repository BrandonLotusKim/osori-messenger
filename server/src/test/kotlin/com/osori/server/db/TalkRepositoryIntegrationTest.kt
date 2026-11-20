package com.osori.server.db

import com.osori.server.testutil.PostgresTestContainer
import org.junit.Before
import org.junit.Test
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessageRepositoryIntegrationTest {

    private lateinit var userRepo: UserRepository
    private lateinit var repo: TalkRepository

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        val db = PostgresTestContainer.database()
        userRepo = UserRepository(db)
        repo = TalkRepository(db)
        userRepo.createIfAbsent("alice", "Alice")
        userRepo.createIfAbsent("bob", "Bob")
        userRepo.createIfAbsent("carol", "Carol")
    }

    @Test
    fun `insertMessage 후 countMessages 는 1`() {
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "hi", Instant.now())
        assertEquals(1L, repo.countMessages())
    }

    @Test
    fun `saveOfflineMessage 는 본문과 outbox 를 같이 적재한다`() {
        val id = UUID.randomUUID()
        repo.saveOfflineMessage(id, "alice", "bob", "hello offline", Instant.now())

        assertEquals(1L, repo.countMessages())
        val outbox = repo.fetchOutbox("bob")
        assertEquals(1, outbox.size)
        assertEquals("hello offline", outbox[0].message.text)
        assertEquals("alice", outbox[0].message.fromUser)
        assertEquals(id, outbox[0].message.messageId)
    }

    @Test
    fun `fetchOutbox 는 오래된 순으로 반환하고 limit 을 지킨다`() {
        val now = Instant.now()
        repo.saveOfflineMessage(UUID.randomUUID(), "alice", "bob", "first", now)
        Thread.sleep(20)
        repo.saveOfflineMessage(UUID.randomUUID(), "alice", "bob", "second", now.plusMillis(1))
        Thread.sleep(20)
        repo.saveOfflineMessage(UUID.randomUUID(), "alice", "bob", "third", now.plusMillis(2))

        val top2 = repo.fetchOutbox("bob", limit = 2)
        assertEquals(2, top2.size)
        assertEquals("first", top2[0].message.text)
        assertEquals("second", top2[1].message.text)
    }

    @Test
    fun `fetchOutbox 는 수신자별로 분리된다`() {
        repo.saveOfflineMessage(UUID.randomUUID(), "alice", "bob", "to-bob", Instant.now())
        repo.saveOfflineMessage(UUID.randomUUID(), "alice", "carol", "to-carol-1", Instant.now())
        repo.saveOfflineMessage(UUID.randomUUID(), "alice", "carol", "to-carol-2", Instant.now())

        assertEquals(1, repo.fetchOutbox("bob").size)
        assertEquals(2, repo.fetchOutbox("carol").size)
    }

    @Test
    fun `markDelivered 된 항목은 fetchOutbox 에서 제외되지만 메시지 본문은 유지된다`() {
        repo.saveOfflineMessage(UUID.randomUUID(), "alice", "bob", "m1", Instant.now())
        Thread.sleep(20)
        repo.saveOfflineMessage(UUID.randomUUID(), "alice", "bob", "m2", Instant.now())

        val initial = repo.fetchOutbox("bob")
        assertEquals(2, initial.size)

        repo.markDelivered(initial[0].id)

        val remaining = repo.fetchOutbox("bob")
        assertEquals(1, remaining.size)
        assertEquals("m2", remaining[0].message.text)
        assertEquals(2L, repo.countMessages())
    }

    @Test
    fun `fetchRecentForUser sinceMillis 0 은 전체를 오래된 순으로 반환`() {
        val t0 = Instant.now()
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "old-1", t0)
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "old-2", t0.plusMillis(10))
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "old-3", t0.plusMillis(20))

        val rows = repo.fetchRecentForUser("bob", sinceMillis = 0, limit = 100)
        assertEquals(3, rows.size)
        assertEquals(listOf("old-1", "old-2", "old-3"), rows.map { it.text })
    }

    @Test
    fun `fetchRecentForUser sinceMillis 이후만 반환 (exclusive)`() {
        // ms 정밀도로 정렬해야 cutoff vs sinceMillis 비교가 정확히 exclusive 가 됨
        val cutoff = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        // cutoff 이전은 절대 포함 안 되어야 함
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "before", cutoff.minusMillis(100))
        // cutoff 정확히 같은 시점은 exclusive 라 제외 (server_ts > sinceMillis)
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "boundary", cutoff)
        // cutoff 이후는 포함
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "after-1", cutoff.plusMillis(50))
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "after-2", cutoff.plusMillis(100))

        val rows = repo.fetchRecentForUser("bob", sinceMillis = cutoff.toEpochMilli(), limit = 100)
        val texts = rows.map { it.text }
        assertTrue("after-1" in texts)
        assertTrue("after-2" in texts)
        assertTrue("before" !in texts)
        assertTrue("boundary" !in texts, "boundary 정확히 cutoff = exclusive 라 제외")
    }

    @Test
    fun `fetchRecentForUser limit 으로 잘리면 가장 최근 것들이 남는다`() {
        // 과거엔 ORDER BY ASC LIMIT 이라 가장 오래된 N건이 남는 버그였음 — 최신 N건이 남아야 정상.
        val t0 = Instant.now()
        repeat(5) { i ->
            repo.insertMessage(UUID.randomUUID(), "alice", "bob", "m$i", t0.plusMillis(i.toLong() * 10))
        }
        val rows = repo.fetchRecentForUser("bob", sinceMillis = 0, limit = 3)
        assertEquals(3, rows.size)
        assertEquals(listOf("m2", "m3", "m4"), rows.map { it.text })
    }

    @Test
    fun `fetchRecentForUser 다른 상대방의 메시지는 배제하되 본인이 발신자인 것은 포함`() {
        val now = Instant.now()
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "to-bob", now)
        repo.insertMessage(UUID.randomUUID(), "alice", "carol", "to-carol", now)
        repo.insertMessage(UUID.randomUUID(), "bob", "carol", "bob-to-carol", now)

        // bob 은 "to-bob"(수신자) + "bob-to-carol"(발신자) 둘 다 봐야 함 — "to-carol"(무관)은 배제
        val bobRows = repo.fetchRecentForUser("bob", 0, 100)
        assertEquals(2, bobRows.size)
        assertEquals(setOf("to-bob", "bob-to-carol"), bobRows.map { it.text }.toSet())

        // carol 은 "to-carol" + "bob-to-carol" 둘 다 수신자 입장으로 봄
        val carolRows = repo.fetchRecentForUser("carol", 0, 100)
        assertEquals(2, carolRows.size)
    }

    @Test
    fun `searchMessages 는 송수신자 OR ILIKE 로 매칭되는 본문을 반환한다`() {
        val t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "hello world", t0)
        repo.insertMessage(UUID.randomUUID(), "bob", "alice", "HELLO again", t0.plusMillis(10))
        repo.insertMessage(UUID.randomUUID(), "alice", "carol", "something else", t0.plusMillis(20))
        repo.insertMessage(UUID.randomUUID(), "bob", "carol", "bob-to-carol hello", t0.plusMillis(30))

        val rows = repo.searchMessages("alice", "hello", 0, 0, 100)
        val texts = rows.map { it.text }

        assertEquals(2, rows.size, "alice 가 참여한 대화 중 hello 포함 2건")
        assertTrue("hello world" in texts)
        assertTrue("HELLO again" in texts, "ILIKE 는 대소문자 무관")
        assertTrue("bob-to-carol hello" !in texts, "alice 가 참여하지 않은 대화는 제외")
    }

    @Test
    fun `searchMessages 는 서버 시각 DESC 순서로 반환한다`() {
        val t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "hit-1", t0)
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "hit-2", t0.plusMillis(10))
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "hit-3", t0.plusMillis(20))

        val rows = repo.searchMessages("alice", "hit", 0, 0, 100)
        assertEquals(listOf("hit-3", "hit-2", "hit-1"), rows.map { it.text })
    }

    @Test
    fun `searchMessages limit 으로 자른다`() {
        val t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        repeat(5) { i ->
            repo.insertMessage(UUID.randomUUID(), "alice", "bob", "hit-$i", t0.plusMillis(i.toLong()))
        }
        val rows = repo.searchMessages("alice", "hit", 0, 0, 3)
        assertEquals(3, rows.size)
    }

    @Test
    fun `searchMessages sinceMillis until Millis 경계`() {
        val base = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "before keyword", base.minusMillis(100))
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "inside keyword", base.plusMillis(50))
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "after keyword", base.plusMillis(200))

        val rows = repo.searchMessages(
            userId = "alice",
            keyword = "keyword",
            sinceMillis = base.toEpochMilli(),
            untilMillis = base.plusMillis(100).toEpochMilli(),
            limit = 100,
        )
        assertEquals(1, rows.size)
        assertEquals("inside keyword", rows[0].text)
    }

    @Test
    fun `searchMessages 는 퍼센트 와일드카드 리터럴로 이스케이프한다`() {
        val t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "discount 50% off", t0)
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "no match plain", t0.plusMillis(10))

        val rows = repo.searchMessages("alice", "50%", 0, 0, 100)
        assertEquals(1, rows.size)
        assertEquals("discount 50% off", rows[0].text)
    }

    @Test
    fun `searchMessages 언더스코어 와일드카드도 리터럴로 이스케이프`() {
        val t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "snake_case_var", t0)
        repo.insertMessage(UUID.randomUUID(), "alice", "bob", "snakeXcaseYvar", t0.plusMillis(10))

        val rows = repo.searchMessages("alice", "snake_case", 0, 0, 100)
        assertEquals(1, rows.size)
        assertEquals("snake_case_var", rows[0].text)
    }

    @Test
    fun `searchMessages 는 내가 참여하지 않은 대화를 절대 반환하지 않는다`() {
        val t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        repo.insertMessage(UUID.randomUUID(), "bob", "carol", "secret keyword leak", t0)

        val rows = repo.searchMessages("alice", "keyword", 0, 0, 100)
        assertTrue(rows.isEmpty(), "alice 는 이 대화에 참여하지 않음 — 결과 0 건이어야 함")
    }

    @Test
    fun `insertOutbox 는 본문이 없으면 FK 제약으로 실패한다`() {
        val ghostMessageId = UUID.randomUUID()
        val thrown = assertFailsWith<SQLException> {
            repo.insertOutbox(ghostMessageId, "bob")
        }
        val text = (thrown.message.orEmpty() + " " + thrown.cause?.message.orEmpty()).lowercase()
        assertTrue(
            text.contains("foreign key") ||
                text.contains("msg_outbox_message_id_fkey") ||
                text.contains("violates"),
            "expected FK violation, got: ${thrown.message}",
        )
    }
}

package com.osori.server.db

import com.osori.server.testutil.PostgresTestContainer
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TalkRoomRepositoryIntegrationTest {

    private lateinit var userRepo: UserRepository
    private lateinit var repo: TalkRoomRepository

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        val db = PostgresTestContainer.database()
        userRepo = UserRepository(db)
        repo = TalkRoomRepository(db)
        userRepo.createIfAbsent("alice", "Alice")
        userRepo.createIfAbsent("bob", "Bob")
        userRepo.createIfAbsent("carol", "Carol")
    }

    @Test
    fun `createRoom adds owner as first member`() {
        val roomId = repo.createRoom("team", "alice", Instant.now())
        assertTrue(repo.isMember(roomId, "alice"))
        assertEquals(listOf("alice"), repo.listMembers(roomId))
    }

    @Test
    fun `getRoom returns stored row`() {
        val createdAt = Instant.now()
        val roomId = repo.createRoom("qa", "alice", createdAt)
        val row = repo.getRoom(roomId)
        assertNotNull(row)
        assertEquals("qa", row!!.name)
        assertEquals("alice", row.ownerUserId)
        assertEquals(roomId, row.roomId)
    }

    @Test
    fun `getRoom returns null for unknown id`() {
        val random = UUID.randomUUID()
        assertNull(repo.getRoom(random))
    }

    @Test
    fun `addMember returns OK for new and ALREADY_MEMBER for duplicate`() {
        val roomId = repo.createRoom("team", "alice", Instant.now())
        val first = repo.addMember(roomId, "bob", Instant.now())
        val second = repo.addMember(roomId, "bob", Instant.now())
        assertEquals(TalkRoomRepository.AddMemberResult.OK, first)
        assertEquals(TalkRoomRepository.AddMemberResult.ALREADY_MEMBER, second)
        val members = repo.listMembers(roomId)
        assertEquals(setOf("alice", "bob"), members.toSet())
    }

    @Test
    fun `addMember returns ROOM_NOT_FOUND when room missing`() {
        val ghost = UUID.randomUUID()
        val outcome = repo.addMember(ghost, "alice", Instant.now())
        assertEquals(TalkRoomRepository.AddMemberResult.ROOM_NOT_FOUND, outcome)
    }

    @Test
    fun `insertRoomTalk persists and count reflects inserts`() {
        val roomId = repo.createRoom("team", "alice", Instant.now())
        repo.addMember(roomId, "bob", Instant.now())
        repo.insertRoomTalk(roomId, "alice", "hello", Instant.now())
        repo.insertRoomTalk(roomId, "bob", "hi back", Instant.now())
        assertEquals(2L, repo.countRoomTalks(roomId))
    }

    @Test
    fun `fetchRoomHistory returns ascending by serverTs and respects limit + sinceTs`() {
        val roomId = repo.createRoom("team", "alice", Instant.now())
        repo.addMember(roomId, "bob", Instant.now())
        val base = Instant.parse("2026-05-12T10:00:00Z")
        // 5건 삽입 (1초 간격)
        repo.insertRoomTalk(roomId, "alice", "m1", base)
        repo.insertRoomTalk(roomId, "bob",   "m2", base.plusSeconds(1))
        repo.insertRoomTalk(roomId, "alice", "m3", base.plusSeconds(2))
        repo.insertRoomTalk(roomId, "bob",   "m4", base.plusSeconds(3))
        repo.insertRoomTalk(roomId, "alice", "m5", base.plusSeconds(4))

        // sinceTs=0 → 전체 5건 오름차순
        val all = repo.fetchRoomHistory(roomId, 0L, 50)
        assertEquals(5, all.size)
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), all.map { it.text })

        // limit=2 → 가장 최근 2건 (과거엔 ORDER BY ASC LIMIT 이라 가장 오래된 2건이 남는 버그였음)
        val lastTwo = repo.fetchRoomHistory(roomId, 0L, 2)
        assertEquals(listOf("m4", "m5"), lastTwo.map { it.text })

        // sinceTs=m2.serverTs exclusive → m3 부터
        val afterM2 = repo.fetchRoomHistory(roomId, base.plusSeconds(1).toEpochMilli(), 50)
        assertEquals(listOf("m3", "m4", "m5"), afterM2.map { it.text })
    }

    @Test
    fun `fetchRoomHistory 빈 결과 — 모르는 roomId`() {
        val ghost = UUID.randomUUID()
        assertEquals(0, repo.fetchRoomHistory(ghost, 0L, 50).size)
    }

    @Test
    fun `createRoom with initial memberIds adds everyone at once`() {
        val roomId = repo.createRoom("team", "alice", Instant.now(), listOf("bob", "carol"))
        assertEquals(setOf("alice", "bob", "carol"), repo.listMembers(roomId).toSet())
    }

    @Test
    fun `findRoomByMemberSet finds a room regardless of member order`() {
        val roomId = repo.createRoom("team", "alice", Instant.now(), listOf("bob", "carol"))
        assertEquals(roomId, repo.findRoomByMemberSet(listOf("carol", "alice", "bob")))
        assertEquals(roomId, repo.findRoomByMemberSet(listOf("bob", "carol", "alice")))
    }

    @Test
    fun `findRoomByMemberSet returns null when no exact match exists`() {
        repo.createRoom("team", "alice", Instant.now(), listOf("bob"))
        // 부분집합/상위집합은 매치가 아니다 — 정확히 같은 구성원만 매치.
        assertNull(repo.findRoomByMemberSet(listOf("alice", "bob", "carol")))
        assertNull(repo.findRoomByMemberSet(listOf("alice")))
    }

    @Test
    fun `addMembers skips already-members and returns both lists`() {
        val roomId = repo.createRoom("team", "alice", Instant.now(), listOf("bob"))
        val outcome = repo.addMembers(roomId, listOf("bob", "carol"), Instant.now())
        assertTrue(outcome.roomFound)
        assertEquals(listOf("carol"), outcome.added)
        assertEquals(listOf("bob"), outcome.alreadyMembers)
        assertEquals(setOf("alice", "bob", "carol"), repo.listMembers(roomId).toSet())
    }

    @Test
    fun `addMembers on missing room reports roomFound=false`() {
        val outcome = repo.addMembers(UUID.randomUUID(), listOf("bob"), Instant.now())
        assertEquals(false, outcome.roomFound)
    }

    @Test
    fun `removeMember removes only that member and keeps the room when others remain`() {
        val roomId = repo.createRoom("team", "alice", Instant.now(), listOf("bob", "carol"))
        val outcome = repo.removeMember(roomId, "bob")
        assertEquals(TalkRoomRepository.RemoveMemberResult.OK, outcome.result)
        assertEquals(false, outcome.roomDeleted)
        assertEquals(setOf("alice", "carol"), outcome.remainingMembers.toSet())
        assertEquals(setOf("alice", "carol"), repo.listMembers(roomId).toSet())
    }

    @Test
    fun `removeMember deletes the room once membership reaches zero but keeps message history`() {
        val roomId = repo.createRoom("team", "alice", Instant.now())
        repo.insertRoomTalk(roomId, "alice", "last words", Instant.now())

        val outcome = repo.removeMember(roomId, "alice")

        assertEquals(TalkRoomRepository.RemoveMemberResult.OK, outcome.result)
        assertEquals(true, outcome.roomDeleted)
        assertNull(repo.getRoom(roomId))
        // 방이 삭제되면 메시지의 room_id 는 SET NULL 로 끊기므로 countRoomTalks(roomId) 로는
        // 더 이상 못 찾는다(예상된 동작) — 행 자체가 안 지워졌는지는 테이블 전체 카운트로 확인.
        val totalRows = PostgresTestContainer.database().withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM talk_room_messages").use { ps ->
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
        assertEquals(1L, totalRows)
    }

    @Test
    fun `removeMember on non-member or missing room reports the right result`() {
        val roomId = repo.createRoom("team", "alice", Instant.now())
        assertEquals(TalkRoomRepository.RemoveMemberResult.NOT_MEMBER, repo.removeMember(roomId, "bob").result)
        assertEquals(
            TalkRoomRepository.RemoveMemberResult.ROOM_NOT_FOUND,
            repo.removeMember(UUID.randomUUID(), "alice").result,
        )
    }

    @Test
    fun `renameRoom updates the name and reports false for unknown room`() {
        val roomId = repo.createRoom("old-name", "alice", Instant.now())
        assertTrue(repo.renameRoom(roomId, "new-name"))
        assertEquals("new-name", repo.getRoom(roomId)!!.name)
        assertEquals(false, repo.renameRoom(UUID.randomUUID(), "whatever"))
    }

    @Test
    fun `addMembers auto-renames the room when name was never manually set`() {
        // 2026-09-17 사용자 요구사항: 자동 명명된 방은 인원이 늘어나면 이름도 같이 갱신된다.
        val roomId = repo.createRoom("bob", "alice", Instant.now(), memberIds = listOf("bob"))
        assertEquals("bob", repo.getRoom(roomId)!!.name)
        repo.addMembers(roomId, listOf("carol"), Instant.now())
        assertEquals("bob, carol", repo.getRoom(roomId)!!.name)
    }

    @Test
    fun `removeMember auto-renames the remaining room when name was never manually set`() {
        userRepo.createIfAbsent("dave", "Dave")
        val roomId = repo.createRoom(
            "bob, carol, dave",
            "alice",
            Instant.now(),
            memberIds = listOf("bob", "carol", "dave"),
        )
        assertEquals("bob, carol, dave", repo.getRoom(roomId)!!.name)
        repo.removeMember(roomId, "dave")
        assertEquals("bob, carol", repo.getRoom(roomId)!!.name)
    }

    @Test
    fun `manually renamed room keeps its name across membership changes`() {
        val roomId = repo.createRoom("bob", "alice", Instant.now(), memberIds = listOf("bob"))
        assertTrue(repo.renameRoom(roomId, "우리 방"))
        repo.addMembers(roomId, listOf("carol"), Instant.now())
        assertEquals("우리 방", repo.getRoom(roomId)!!.name)
        repo.removeMember(roomId, "carol")
        assertEquals("우리 방", repo.getRoom(roomId)!!.name)
    }

    @Test
    fun `computeAutoRoomName follows the solo, small-group and large-group rules`() {
        assertEquals("나와의 대화", TalkRoomRepository.computeAutoRoomName(listOf("alice"), "alice"))
        assertEquals(
            "bob, carol",
            TalkRoomRepository.computeAutoRoomName(listOf("alice", "bob", "carol"), "alice"),
        )
        // 2026-09-17 사용자 신고 버그 재현: 전체 6명(alice+5명)이면 "외 5명"이어야 한다
        // (예전 버그는 이 자리에서 "외 4명"으로 1명 적게 나왔음).
        assertEquals(
            "bob 외 5명",
            TalkRoomRepository.computeAutoRoomName(
                listOf("alice", "bob", "carol", "dave", "eve", "frank"),
                "alice",
            ),
        )
    }

    @Test
    fun `computeAutoRoomName matches the user-reported example of 8 total members needing 외 7명`() {
        val eight = listOf("alice", "bob", "carol", "dave", "eve", "frank", "grace", "heidi")
        assertEquals("bob 외 7명", TalkRoomRepository.computeAutoRoomName(eight, "alice"))
    }

    @Test
    fun `searchRoomTalks finds only rooms the user currently belongs to`() {
        val t0 = Instant.now()
        val ab = repo.createRoom("alice, bob", "alice", t0, listOf("bob"))
        val bc = repo.createRoom("bob, carol", "bob", t0, listOf("carol"))
        repo.insertRoomTalk(ab, "bob", "내일 배포 일정 공유합니다", t0.plusSeconds(1))
        repo.insertRoomTalk(ab, "alice", "점심 뭐 먹죠", t0.plusSeconds(2))
        repo.insertRoomTalk(bc, "carol", "배포는 carol 방에서만", t0.plusSeconds(3))

        val hits = repo.searchRoomTalks("alice", "배포", 0, 0, 10)
        assertEquals(1, hits.size)
        assertEquals(ab, hits[0].roomId)
        assertEquals("bob", hits[0].fromUserId)
        assertEquals(setOf("alice", "bob"), hits[0].memberIds.toSet())

        // 대소문자 무시 + 특수문자(%) 는 문자 그대로
        repo.insertRoomTalk(ab, "bob", "Release 100% done", t0.plusSeconds(4))
        assertEquals(1, repo.searchRoomTalks("alice", "release 100%", 0, 0, 10).size)
        assertEquals(0, repo.searchRoomTalks("alice", "%%", 0, 0, 10).size)

        // 나가면 그 방 메시지는 더 이상 검색되지 않는다
        repo.removeMember(ab, "alice")
        assertEquals(0, repo.searchRoomTalks("alice", "배포", 0, 0, 10).size)
    }

    @Test
    fun `searchRoomTalks orders newest first and honors limit`() {
        val t0 = Instant.now()
        val ab = repo.createRoom("alice, bob", "alice", t0, listOf("bob"))
        (1..5).forEach { repo.insertRoomTalk(ab, "bob", "공지 $it", t0.plusSeconds(it.toLong())) }
        val hits = repo.searchRoomTalks("bob", "공지", 0, 0, 3)
        assertEquals(listOf("공지 5", "공지 4", "공지 3"), hits.map { it.text })
    }
}

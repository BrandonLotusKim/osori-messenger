package com.osori.server.db

import com.osori.server.testutil.PostgresTestContainer
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * OrganizationRepository 통합 테스트.
 *
 * 검증 범위:
 *   - org_units upsert / find / list / 트리(자식 조회)
 *   - org_members upsert (FK = auth_users + org_units)
 *   - upsert 멱등성 (같은 path/user_id 두 번)
 *   - parent CASCADE 동작 (부모 삭제 시 자식 삭제)
 *
 * 전제: PostgresTestContainer 가 외부 osori_test DB 에 접속하고 schema.sql 의
 *      v3 (org_units / org_members) 가 적용되어 있어야 한다.
 */
class OrganizationRepositoryIntegrationTest {

    private lateinit var orgRepo: OrganizationRepository
    private lateinit var userRepo: UserRepository

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        val db = PostgresTestContainer.database()
        orgRepo = OrganizationRepository(db)
        userRepo = UserRepository(db)
    }

    // ──────────────────────────── org_units ────────────────────────────

    @Test
    fun `upsertUnit ROOT 노드 생성`() {
        orgRepo.upsertUnit(
            OrganizationRepository.OrgUnit(
                path = "오소리랩",
                parentPath = null,
                name = "오소리랩",
                depth = 0,
                sortOrder = 0,
            ),
        )

        assertEquals(1L, orgRepo.countUnits())
        val found = orgRepo.findUnit("오소리랩")
        assertNotNull(found)
        assertEquals("오소리랩", found.path)
        assertNull(found.parentPath)
        assertEquals(0, found.depth)
    }

    @Test
    fun `upsertUnit 트리 3 단계 생성 후 list 정렬 확인`() {
        orgRepo.upsertUnit(unit("오소리랩", null, "오소리랩", 0, 0))
        orgRepo.upsertUnit(unit("오소리랩/연구개발본부", "오소리랩", "연구개발본부", 1, 0))
        orgRepo.upsertUnit(unit("오소리랩/사업본부", "오소리랩", "사업본부", 1, 1))
        orgRepo.upsertUnit(unit("오소리랩/연구개발본부/플랫폼개발팀", "오소리랩/연구개발본부", "플랫폼개발팀", 2, 0))

        assertEquals(4L, orgRepo.countUnits())

        val all = orgRepo.listAllUnits()
        // ORDER BY depth, sort_order, path
        assertEquals("오소리랩", all[0].path)
        assertEquals(0, all[0].depth)
        assertEquals("오소리랩/연구개발본부", all[1].path)
        assertEquals(1, all[1].depth)
        assertEquals("오소리랩/사업본부", all[2].path)
        assertEquals(1, all[2].depth)
        assertEquals("오소리랩/연구개발본부/플랫폼개발팀", all[3].path)
        assertEquals(2, all[3].depth)
    }

    @Test
    fun `listChildren 부모로 직속 자식만 필터`() {
        orgRepo.upsertUnit(unit("오소리랩", null, "오소리랩", 0, 0))
        orgRepo.upsertUnit(unit("오소리랩/RND", "오소리랩", "RND", 1, 0))
        orgRepo.upsertUnit(unit("오소리랩/SALES", "오소리랩", "SALES", 1, 1))
        orgRepo.upsertUnit(unit("오소리랩/RND/PLAT", "오소리랩/RND", "PLAT", 2, 0))

        val roots = orgRepo.listChildren(null)
        assertEquals(1, roots.size)
        assertEquals("오소리랩", roots[0].path)

        val topUnits = orgRepo.listChildren("오소리랩")
        assertEquals(2, topUnits.size)
        assertEquals("오소리랩/RND", topUnits[0].path)
        assertEquals("오소리랩/SALES", topUnits[1].path)

        val rnd = orgRepo.listChildren("오소리랩/RND")
        assertEquals(1, rnd.size)
        assertEquals("오소리랩/RND/PLAT", rnd[0].path)
    }

    @Test
    fun `upsertUnit 같은 path 두번 부르면 멱등이고 name 갱신됨`() {
        orgRepo.upsertUnit(unit("오소리랩", null, "오소리랩(구)", 0, 0))
        orgRepo.upsertUnit(unit("오소리랩", null, "오소리랩(신)", 0, 5))

        assertEquals(1L, orgRepo.countUnits())
        val found = orgRepo.findUnit("오소리랩")
        assertNotNull(found)
        assertEquals("오소리랩(신)", found.name)
        assertEquals(5, found.sortOrder)
    }

    @Test
    fun `parent 부서 삭제 시 자식 CASCADE 삭제`() {
        orgRepo.upsertUnit(unit("오소리랩", null, "오소리랩", 0, 0))
        orgRepo.upsertUnit(unit("오소리랩/RND", "오소리랩", "RND", 1, 0))
        orgRepo.upsertUnit(unit("오소리랩/RND/PLAT", "오소리랩/RND", "PLAT", 2, 0))

        // 부모 직접 DELETE (Repository 에 deleteUnit 은 아직 없으므로 raw SQL)
        PostgresTestContainer.database().withConnection { conn ->
            conn.prepareStatement("DELETE FROM org_units WHERE path = ?").use { ps ->
                ps.setString(1, "오소리랩/RND")
                ps.executeUpdate()
            }
        }

        // 자식까지 CASCADE 삭제됐어야 함
        assertNull(orgRepo.findUnit("오소리랩/RND"))
        assertNull(orgRepo.findUnit("오소리랩/RND/PLAT"))
        assertNotNull(orgRepo.findUnit("오소리랩"))
    }

    // ──────────────────────────── org_members ────────────────────────────

    @Test
    fun `upsertMember 사용자가 부서 소속으로 등록됨`() {
        userRepo.createIfAbsent("kjm", "홍길동")
        orgRepo.upsertUnit(unit("오소리랩/RND", null, "RND", 0, 0))

        orgRepo.upsertMember(
            OrganizationRepository.OrgMember(
                userId = "kjm",
                orgPath = "오소리랩/RND",
                email = "kjm@example.com",
                phone = "02-1234-5678",
                mobile = "010-1111-2222",
                title = "선임연구원",
                sortOrder = 1,
            ),
        )

        assertEquals(1L, orgRepo.countMembers())
        val found = orgRepo.findMember("kjm")
        assertNotNull(found)
        assertEquals("kjm", found.userId)
        assertEquals("오소리랩/RND", found.orgPath)
        assertEquals("kjm@example.com", found.email)
        assertEquals("010-1111-2222", found.mobile)
        assertEquals("선임연구원", found.title)
    }

    @Test
    fun `listMembersByOrg 부서별 멤버 sort_order 순`() {
        userRepo.createIfAbsent("a", "에이")
        userRepo.createIfAbsent("b", "비")
        userRepo.createIfAbsent("c", "씨")
        orgRepo.upsertUnit(unit("팀1", null, "팀1", 0, 0))
        orgRepo.upsertUnit(unit("팀2", null, "팀2", 0, 1))

        orgRepo.upsertMember(member("a", "팀1", "a@x", sort = 2))
        orgRepo.upsertMember(member("b", "팀1", "b@x", sort = 1))
        orgRepo.upsertMember(member("c", "팀2", "c@x", sort = 1))

        val team1 = orgRepo.listMembersByOrg("팀1")
        assertEquals(2, team1.size)
        // sort_order 1 인 b 가 먼저
        assertEquals("b", team1[0].userId)
        assertEquals("a", team1[1].userId)

        val team2 = orgRepo.listMembersByOrg("팀2")
        assertEquals(1, team2.size)
        assertEquals("c", team2[0].userId)
    }

    @Test
    fun `upsertMember 같은 userId 두번 부르면 멱등이고 갱신됨`() {
        userRepo.createIfAbsent("kjm", "홍길동")
        orgRepo.upsertUnit(unit("팀1", null, "팀1", 0, 0))
        orgRepo.upsertUnit(unit("팀2", null, "팀2", 0, 1))

        orgRepo.upsertMember(member("kjm", "팀1", "old@x", title = "선임"))
        orgRepo.upsertMember(member("kjm", "팀2", "new@x", title = "팀장"))

        assertEquals(1L, orgRepo.countMembers())
        val found = orgRepo.findMember("kjm")
        assertNotNull(found)
        assertEquals("팀2", found.orgPath)
        assertEquals("new@x", found.email)
        assertEquals("팀장", found.title)
    }

    @Test
    fun `org_units CASCADE 시 org_members orgPath 가 NULL 로 SET NULL`() {
        userRepo.createIfAbsent("kjm", "홍길동")
        orgRepo.upsertUnit(unit("팀1", null, "팀1", 0, 0))
        orgRepo.upsertMember(member("kjm", "팀1", "kjm@x"))

        // org_units DELETE → ON DELETE SET NULL
        PostgresTestContainer.database().withConnection { conn ->
            conn.prepareStatement("DELETE FROM org_units WHERE path = ?").use { ps ->
                ps.setString(1, "팀1")
                ps.executeUpdate()
            }
        }

        // 멤버는 살아있고 org_path 만 NULL
        val found = orgRepo.findMember("kjm")
        assertNotNull(found)
        assertNull(found.orgPath)
    }

    // ──────────────────────────── helpers ────────────────────────────

    private fun unit(
        path: String,
        parent: String?,
        name: String,
        depth: Int,
        sort: Int,
    ) = OrganizationRepository.OrgUnit(
        path = path,
        parentPath = parent,
        name = name,
        depth = depth,
        sortOrder = sort,
    )

    private fun member(
        userId: String,
        orgPath: String?,
        email: String,
        phone: String = "",
        mobile: String = "",
        title: String = "",
        sort: Int = 0,
    ) = OrganizationRepository.OrgMember(
        userId = userId,
        orgPath = orgPath,
        email = email,
        phone = phone,
        mobile = mobile,
        title = title,
        sortOrder = sort,
    )
}

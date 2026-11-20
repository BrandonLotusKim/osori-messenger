package com.osori.server.org

import com.osori.server.db.OrganizationRepository
import com.osori.server.db.UserRepository
import com.osori.server.testutil.PostgresTestContainer
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * OrgImportService 통합 테스트.
 *
 * - 파싱: 헤더 검증 / 빈 줄·주석 무시 / 컬럼 수 / 필수 필드
 * - 트리 빌드: 중간 부서 자동 생성 / depth 정렬
 * - upsert 멱등성 (같은 CSV 두 번)
 * - 사용자 자동 생성 (auth_users)
 * - 갱신 (같은 user_id 가 다른 부서로 이동)
 */
class OrgImportServiceIntegrationTest {

    private lateinit var importer: OrgImportService
    private lateinit var orgRepo: OrganizationRepository
    private lateinit var userRepo: UserRepository

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        val db = PostgresTestContainer.database()
        orgRepo = OrganizationRepository(db)
        userRepo = UserRepository(db)
        importer = OrgImportService(db, orgRepo, userRepo)
    }

    // ──────────────────────────── 파싱 ────────────────────────────

    @Test
    fun `expandAncestors 슬래시 단계별 분해`() {
        val out = importer.expandAncestors("오소리랩/연구개발본부/플랫폼개발팀")
        assertEquals(
            listOf("오소리랩", "오소리랩/연구개발본부", "오소리랩/연구개발본부/플랫폼개발팀"),
            out,
        )
    }

    @Test
    fun `parseRows 헤더 검증 실패시 예외`() {
        val csv = """
            org_path,user_id,name,email,phone,mobile,title,sort_order
            팀1,u1,이름,e@x,,,,1
        """.trimIndent()
        val ex = assertFailsWith<OrgImportService.ImportException> {
            importer.importFromString(csv, "<test>")
        }
        assert(ex.message!!.contains("CSV 헤더가 다릅니다")) { "msg=${ex.message}" }
    }

    @Test
    fun `parseRows 빈 줄과 주석 무시`() {
        val csv = """
            org_path,user_id,display_name,email,phone,mobile,title,sort_order
            # 주석 라인은 무시

            팀1,u1,이름,e@x,,,팀장,1
        """.trimIndent()
        val res = importer.importFromString(csv, "<test>")
        assertEquals(1, res.rowsRead)
        assertEquals(1, res.unitsUpserted)
        assertEquals(1, res.membersUpserted)
    }

    @Test
    fun `parseRows 컬럼 수 부족시 예외`() {
        val csv = """
            org_path,user_id,display_name,email,phone,mobile,title,sort_order
            팀1,u1,이름,e@x
        """.trimIndent()
        val ex = assertFailsWith<OrgImportService.ImportException> {
            importer.importFromString(csv, "<test>")
        }
        assert(ex.message!!.contains("컬럼 수가 8")) { "msg=${ex.message}" }
    }

    // ──────────────────────────── 트리 빌드 + upsert ────────────────────────────

    @Test
    fun `중간 부서 자동 생성 — 한 줄에서 3 노드 만들어짐`() {
        val csv = """
            org_path,user_id,display_name,email,phone,mobile,title,sort_order
            오소리랩/RND/PLAT,kjm,홍길동,kjm@x,,010-1,선임,1
        """.trimIndent()
        val res = importer.importFromString(csv, "<test>")
        assertEquals(1, res.rowsRead)
        // 오소리랩 / 오소리랩/RND / 오소리랩/RND/PLAT 세 노드
        assertEquals(3, res.unitsUpserted)
        assertEquals(3L, orgRepo.countUnits())

        val root = orgRepo.findUnit("오소리랩")
        assertNotNull(root)
        assertEquals(0, root.depth)
        assertNull(root.parentPath)

        val mid = orgRepo.findUnit("오소리랩/RND")
        assertNotNull(mid)
        assertEquals(1, mid.depth)
        assertEquals("오소리랩", mid.parentPath)

        val leaf = orgRepo.findUnit("오소리랩/RND/PLAT")
        assertNotNull(leaf)
        assertEquals(2, leaf.depth)
        assertEquals("오소리랩/RND", leaf.parentPath)

        // 멤버 등록 + 사용자 자동 생성
        val mem = orgRepo.findMember("kjm")
        assertNotNull(mem)
        assertEquals("오소리랩/RND/PLAT", mem.orgPath)
        assertEquals("선임", mem.title)
        assertEquals(1, res.usersAutoCreated)
        assertNotNull(userRepo.findById("kjm"))
    }

    @Test
    fun `여러 줄 임포트 + 같은 부서 공유`() {
        val csv = """
            org_path,user_id,display_name,email,phone,mobile,title,sort_order
            오소리랩/RND/PLAT,kjm,홍길동,kjm@x,,010-1,선임,1
            오소리랩/RND/PLAT,sjh,이영희,sjh@x,,010-2,팀장,2
            오소리랩/SALES,jh,제이,jh@x,,010-3,영업,1
        """.trimIndent()
        val res = importer.importFromString(csv, "<test>")
        assertEquals(3, res.rowsRead)
        // 부서: 오소리랩 / 오소리랩/RND / 오소리랩/RND/PLAT / 오소리랩/SALES = 4
        assertEquals(4L, orgRepo.countUnits())
        assertEquals(3L, orgRepo.countMembers())
        assertEquals(3, res.usersAutoCreated)

        val plat = orgRepo.listMembersByOrg("오소리랩/RND/PLAT")
        assertEquals(2, plat.size)
        // sort_order 1 이 먼저
        assertEquals("kjm", plat[0].userId)
        assertEquals("sjh", plat[1].userId)

        val sales = orgRepo.listMembersByOrg("오소리랩/SALES")
        assertEquals(1, sales.size)
        assertEquals("jh", sales[0].userId)
    }

    @Test
    fun `같은 CSV 두 번 임포트해도 멱등 — 사용자 갱신 없음`() {
        val csv = """
            org_path,user_id,display_name,email,phone,mobile,title,sort_order
            팀1,u1,이름1,a@x,,,팀장,1
            팀1,u2,이름2,b@x,,,팀원,2
        """.trimIndent()

        val r1 = importer.importFromString(csv, "<test>")
        val r2 = importer.importFromString(csv, "<test>")

        // 두 번째 실행에서도 unitsUpserted 와 membersUpserted 는 같음 (멱등)
        assertEquals(r1.rowsRead, r2.rowsRead)
        assertEquals(r1.unitsUpserted, r2.unitsUpserted)
        assertEquals(r1.membersUpserted, r2.membersUpserted)
        // 두 번째에는 사용자 자동 생성 0 이어야 함 (이미 존재)
        assertEquals(0, r2.usersAutoCreated)
        // DB 상태는 1 회 실행 결과와 동일
        assertEquals(1L, orgRepo.countUnits())
        assertEquals(2L, orgRepo.countMembers())
    }

    @Test
    fun `사용자 부서 이동 — 같은 user_id 다른 org_path 로 갱신`() {
        val csv1 = """
            org_path,user_id,display_name,email,phone,mobile,title,sort_order
            팀1,u1,이름,a@x,,,팀장,1
        """.trimIndent()
        val csv2 = """
            org_path,user_id,display_name,email,phone,mobile,title,sort_order
            팀2,u1,이름,a@x,,,선임,5
        """.trimIndent()

        importer.importFromString(csv1, "<test1>")
        val mem1 = orgRepo.findMember("u1")
        assertNotNull(mem1)
        assertEquals("팀1", mem1.orgPath)

        importer.importFromString(csv2, "<test2>")
        val mem2 = orgRepo.findMember("u1")
        assertNotNull(mem2)
        // 사용자는 그대로, 부서만 이동
        assertEquals("팀2", mem2.orgPath)
        assertEquals("선임", mem2.title)
        assertEquals(5, mem2.sortOrder)

        // 사용자 수는 1, 부서는 2 (팀1, 팀2 둘 다 살아있음)
        assertEquals(1L, orgRepo.countMembers())
        assertEquals(2L, orgRepo.countUnits())
    }
}

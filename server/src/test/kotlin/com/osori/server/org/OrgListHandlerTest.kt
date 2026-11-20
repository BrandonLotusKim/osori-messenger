package com.osori.server.org

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.OrgListRequest
import com.osori.proto.v1.OrgListResponse
import com.osori.server.db.OrganizationRepository
import com.osori.server.testutil.FakeClientSession
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OrgListHandler 단위 테스트.
 * Repository 가짜 구현으로 Kotlin 레이어만 검증 (DB 없음).
 */
class OrgListHandlerTest {

    private fun fakeStore(
        units: List<OrganizationRepository.OrgUnit> = emptyList(),
        members: List<OrganizationRepository.OrgMember> = emptyList(),
        throwOnList: Boolean = false,
    ) = object : OrgSnapshotStore {
        override fun listAllUnits(): List<OrganizationRepository.OrgUnit> {
            if (throwOnList) error("DB down")
            return units
        }

        override fun listAllMembers(): List<OrganizationRepository.OrgMember> {
            if (throwOnList) error("DB down")
            return members
        }
    }

    private fun session(userId: String?): FakeClientSession {
        val s = FakeClientSession()
        s.userId = userId
        return s
    }

    private fun decodeRes(session: FakeClientSession): OrgListResponse {
        assertEquals(1, session.sentFrames.size)
        val frame = session.sentFrames.single() as Frame.Binary
        val env = Envelope.parseFrom(frame.readBytes())
        assertTrue(env.hasOrgListRes())
        return env.orgListRes
    }

    @Test
    fun `인증 안된 세션은 SERVER_ERROR 반환`() = runBlocking {
        val handler = OrgListHandler(fakeStore())
        val sess = session(userId = null)

        handler.handle(sess, sequence = 7L, OrgListRequest.newBuilder().setClientReqId("req-1").build())

        val res = decodeRes(sess)
        assertEquals(OrgListResponse.Result.SERVER_ERROR, res.result)
        assertEquals("req-1", res.clientReqId)
        assertEquals(0, res.unitsCount)
    }

    @Test
    fun `빈 조직도도 OK 로 반환`() = runBlocking {
        val handler = OrgListHandler(fakeStore())
        val sess = session(userId = "alice")

        handler.handle(sess, sequence = 1L, OrgListRequest.newBuilder().setClientReqId("req-2").build())

        val res = decodeRes(sess)
        assertEquals(OrgListResponse.Result.OK, res.result)
        assertEquals("req-2", res.clientReqId)
        assertEquals(0, res.unitsCount)
        assertEquals(0, res.membersCount)
        assertEquals(0, res.unitCount)
        assertEquals(0, res.memberCount)
    }

    @Test
    fun `단위 3개 + 멤버 2명 정상 반환 — parent_path null 은 빈 문자열로 매핑`() = runBlocking {
        val units = listOf(
            OrganizationRepository.OrgUnit(path = "오소리랩", parentPath = null, name = "오소리랩", depth = 0, sortOrder = 0),
            OrganizationRepository.OrgUnit(path = "오소리랩/RND", parentPath = "오소리랩", name = "RND", depth = 1, sortOrder = 0),
            OrganizationRepository.OrgUnit(path = "오소리랩/RND/PLAT", parentPath = "오소리랩/RND", name = "PLAT", depth = 2, sortOrder = 0),
        )
        val members = listOf(
            OrganizationRepository.OrgMember(
                userId = "kjm",
                orgPath = "오소리랩/RND/PLAT",
                email = "kjm@example.com",
                phone = "",
                mobile = "010-1",
                title = "선임연구원",
                sortOrder = 1,
            ),
            OrganizationRepository.OrgMember(
                userId = "solo",
                orgPath = null,
                email = "s@x",
                phone = "",
                mobile = "",
                title = "",
                sortOrder = 0,
            ),
        )
        val handler = OrgListHandler(fakeStore(units, members))
        val sess = session(userId = "alice")

        handler.handle(sess, sequence = 9L, OrgListRequest.newBuilder().setClientReqId("req-3").build())

        val res = decodeRes(sess)
        assertEquals(OrgListResponse.Result.OK, res.result)
        assertEquals("req-3", res.clientReqId)
        assertEquals(3, res.unitsCount)
        assertEquals(2, res.membersCount)
        assertEquals(3, res.unitCount)
        assertEquals(2, res.memberCount)
        assertTrue(res.serverTs > 0)

        val root = res.unitsList.first { it.path == "오소리랩" }
        assertEquals("", root.parentPath) // null → 빈 문자열
        assertEquals(0, root.depth)

        val leaf = res.unitsList.first { it.path == "오소리랩/RND/PLAT" }
        assertEquals("오소리랩/RND", leaf.parentPath)
        assertEquals(2, leaf.depth)

        val kjm = res.membersList.first { it.userId == "kjm" }
        assertEquals("오소리랩/RND/PLAT", kjm.orgPath)
        assertEquals("kjm@example.com", kjm.email)
        assertEquals("선임연구원", kjm.title)

        val solo = res.membersList.first { it.userId == "solo" }
        assertEquals("", solo.orgPath) // null → 빈 문자열
    }

    @Test
    fun `Repository 예외 시 SERVER_ERROR 반환 + client_req_id echo`() = runBlocking {
        val handler = OrgListHandler(fakeStore(throwOnList = true))
        val sess = session(userId = "alice")

        handler.handle(sess, sequence = 2L, OrgListRequest.newBuilder().setClientReqId("req-err").build())

        val res = decodeRes(sess)
        assertEquals(OrgListResponse.Result.SERVER_ERROR, res.result)
        assertEquals("req-err", res.clientReqId)
    }

    @Test
    fun `null Repository 생성자로 만들면 EMPTY_STORE 로 0건 OK 응답`() = runBlocking {
        val handler = OrgListHandler(null as OrganizationRepository?)
        val sess = session(userId = "alice")

        handler.handle(sess, sequence = 3L, OrgListRequest.newBuilder().setClientReqId("req-empty").build())

        val res = decodeRes(sess)
        assertEquals(OrgListResponse.Result.OK, res.result)
        assertEquals(0, res.unitsCount)
        assertEquals(0, res.membersCount)
    }
}

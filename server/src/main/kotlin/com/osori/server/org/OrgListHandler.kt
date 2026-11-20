package com.osori.server.org

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.OrgListRequest
import com.osori.proto.v1.OrgListResponse
import com.osori.proto.v1.OrgMember as ProtoMember
import com.osori.proto.v1.OrgUnit as ProtoUnit
import com.osori.server.db.OrganizationRepository
import com.osori.server.session.ClientSession
import io.ktor.websocket.*
import org.slf4j.LoggerFactory
import java.time.Instant

private val logger = LoggerFactory.getLogger("com.osori.server.OrgListHandler")

interface OrgSnapshotStore {
    fun listAllUnits(): List<OrganizationRepository.OrgUnit>
    fun listAllMembers(): List<OrganizationRepository.OrgMember>
}

/**
 * 조직도 전체 스냅샷 반환.
 *
 * 정책:
 *   - 인증된 세션만 허용 (userId 없으면 SERVER_ERROR 반환)
 *   - 전체 트리 + 전체 멤버를 한 번에 반환 (MVP — 페이징 없음)
 *   - parentPath 가 null 인 ROOT 노드는 proto 필드에서 빈 문자열로 매핑
 *   - Repository 가 null 이면 EMPTY_STORE → 0건 응답 (DB 비활성 모드 안전)
 *   - 에러 시에도 client_req_id 는 echo back 해서 클라가 요청-응답 매칭 가능
 */
class OrgListHandler(private val store: OrgSnapshotStore) {

    constructor(repo: OrganizationRepository?) : this(
        if (repo != null) {
            object : OrgSnapshotStore {
                override fun listAllUnits() = repo.listAllUnits()
                override fun listAllMembers() = repo.listAllMembers()
            }
        } else {
            EMPTY_STORE
        },
    )

    suspend fun handle(session: ClientSession, sequence: Long, req: OrgListRequest) {
        val userId = session.userId
        if (userId.isNullOrBlank()) {
            logger.warn("미인증 세션의 조직도 요청 거부 session={}", session.id)
            sendError(session, sequence, req.clientReqId)
            return
        }

        val (units, members) = try {
            store.listAllUnits() to store.listAllMembers()
        } catch (t: Throwable) {
            logger.error("조직도 조회 실패 userId={}", userId, t)
            sendError(session, sequence, req.clientReqId)
            return
        }

        val resp = OrgListResponse.newBuilder()
            .setClientReqId(req.clientReqId)
            .setUnitCount(units.size)
            .setMemberCount(members.size)
            .setServerTs(Instant.now().toEpochMilli())
            .setResult(OrgListResponse.Result.OK)

        units.forEach { u ->
            resp.addUnits(
                ProtoUnit.newBuilder()
                    .setPath(u.path)
                    .setParentPath(u.parentPath ?: "")
                    .setName(u.name)
                    .setDepth(u.depth)
                    .setSortOrder(u.sortOrder)
                    .build(),
            )
        }
        members.forEach { m ->
            resp.addMembers(
                ProtoMember.newBuilder()
                    .setUserId(m.userId)
                    .setOrgPath(m.orgPath ?: "")
                    .setEmail(m.email)
                    .setPhone(m.phone)
                    .setMobile(m.mobile)
                    .setTitle(m.title)
                    .setSortOrder(m.sortOrder)
                    .build(),
            )
        }

        val env = Envelope.newBuilder()
            .setSequence(sequence)
            .setOrgListRes(resp.build())
            .build()
        session.send(Frame.Binary(true, env.toByteArray()))
        logger.info(
            "조직도 응답 userId={} units={} members={}",
            userId, units.size, members.size,
        )
    }

    private suspend fun sendError(session: ClientSession, sequence: Long, clientReqId: String) {
        val env = Envelope.newBuilder()
            .setSequence(sequence)
            .setOrgListRes(
                OrgListResponse.newBuilder()
                    .setClientReqId(clientReqId)
                    .setResult(OrgListResponse.Result.SERVER_ERROR)
                    .setServerTs(Instant.now().toEpochMilli())
                    .build(),
            )
            .build()
        session.send(Frame.Binary(true, env.toByteArray()))
    }

    companion object {
        private val EMPTY_STORE: OrgSnapshotStore = object : OrgSnapshotStore {
            override fun listAllUnits() = emptyList<OrganizationRepository.OrgUnit>()
            override fun listAllMembers() = emptyList<OrganizationRepository.OrgMember>()
        }
    }
}

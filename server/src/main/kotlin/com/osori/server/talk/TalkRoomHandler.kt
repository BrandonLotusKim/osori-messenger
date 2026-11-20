package com.osori.server.talk

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.TalkRoomCreate
import com.osori.proto.v1.TalkRoomCreateAck
import com.osori.proto.v1.TalkRoomInfoRequest
import com.osori.proto.v1.TalkRoomInfoResponse
import com.osori.proto.v1.TalkRoomInvite
import com.osori.proto.v1.TalkRoomInviteAck
import com.osori.proto.v1.TalkRoomJoin
import com.osori.proto.v1.TalkRoomJoinAck
import com.osori.proto.v1.TalkRoomLeave
import com.osori.proto.v1.TalkRoomLeaveAck
import com.osori.proto.v1.TalkRoomListItem
import com.osori.proto.v1.TalkRoomListRequest
import com.osori.proto.v1.TalkRoomListResponse
import com.osori.proto.v1.TalkRoomMemberEvent
import com.osori.proto.v1.TalkRoomRename
import com.osori.proto.v1.TalkRoomRenameAck
import com.osori.proto.v1.TalkRoomRenamed
import com.osori.proto.v1.TalkRoomSend
import com.osori.proto.v1.TalkRoomSendAck
import com.osori.proto.v1.TalkRoomReceived
import com.osori.server.db.TalkRoomRepository
import com.osori.server.session.ClientSession
import com.osori.server.session.SessionManager
import io.ktor.websocket.Frame
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Room (group chat) request handler — Phase 6, 멤버 관리 확장(2026-09-16).
 *
 * "방장" 없음 — owner_user_id 는 최초 생성자 기록일 뿐, 초대/나가기/이름변경은 전부
 * "현재 이 방의 멤버인지" 만 확인한다(누구나 가능).
 *
 * If talkRoomRepository is null the handler returns SERVER_ERROR for all writes (memory-only mode).
 */
class TalkRoomHandler(
    private val sessions: SessionManager,
    private val talkRoomRepository: TalkRoomRepository?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val auditRepository: com.osori.server.audit.AuditRepository? = null, //
    private val pluginRegistry: com.osori.server.plugin.PluginRegistry? = null, //
) {
    private val logger = LoggerFactory.getLogger(TalkRoomHandler::class.java)
    private val serverSequence = AtomicLong(2_000_000)

    suspend fun handleRoomCreate(sender: ClientSession, sequence: Long, req: TalkRoomCreate) {
        val senderUserId = sender.userId
        if (senderUserId == null) {
            respond(sender, sequence, ackFailure(req.clientReqId, "", TalkRoomCreateAck.Result.SERVER_ERROR))
            return
        }
        if (req.name.isBlank() || req.name.length > 255) {
            respond(sender, sequence, ackFailure(req.clientReqId, "", TalkRoomCreateAck.Result.INVALID_NAME))
            return
        }
        val repo = talkRoomRepository
        if (repo == null) {
            logger.warn("TalkRoomCreate rejected: no persistence")
            respond(sender, sequence, ackFailure(req.clientReqId, "", TalkRoomCreateAck.Result.SERVER_ERROR))
            return
        }
        val initialMembers = req.memberIdsList.filter { it.isNotBlank() && it != senderUserId }.distinct()

        val result = runCatching {
            if (!req.force) {
                val existing = repo.findRoomByMemberSet(initialMembers + senderUserId)
                if (existing != null) {
                    return@runCatching TalkRoomCreateAck.newBuilder()
                        .setClientReqId(req.clientReqId)
                        .setServerTs(nowMillis())
                        .setResult(TalkRoomCreateAck.Result.DUPLICATE_MEMBERS)
                        .setExistingRoomId(existing.toString())
                        .build()
                }
            }
            val roomId = repo.createRoom(req.name, senderUserId, Instant.ofEpochMilli(nowMillis()), initialMembers)
            auditRepository?.recordRoomCreated(senderUserId, roomId.toString(), req.name)
            pluginRegistry?.fireTalkRoomCreated(
                com.osori.server.plugin.TalkRoomCreatedEvent(
                    ownerUserId = senderUserId,
                    roomId = roomId.toString(),
                    name = req.name,
                    timestamp = Instant.ofEpochMilli(nowMillis()),
                ),
            )
            // 생성과 동시에 초대된 사람들도 이 방의 존재를 실시간으로 알아야 자기 목록에 반영할 수 있다.
            if (initialMembers.isNotEmpty()) {
                broadcastMemberEvent(
                    roomId = roomId,
                    eventType = TalkRoomMemberEvent.EventType.MEMBERS_ADDED,
                    actorUserId = senderUserId,
                    affectedUserIds = initialMembers,
                    memberIds = listOf(senderUserId) + initialMembers,
                    roomName = req.name,
                    nameIsCustom = false,
                )
            }
            TalkRoomCreateAck.newBuilder()
                .setClientReqId(req.clientReqId)
                .setRoomId(roomId.toString())
                .setServerTs(nowMillis())
                .setResult(TalkRoomCreateAck.Result.OK)
                .build()
        }.getOrElse { t ->
            logger.error("TalkRoomCreate failed owner={}", senderUserId, t)
            ackFailure(req.clientReqId, "", TalkRoomCreateAck.Result.SERVER_ERROR)
        }
        respond(sender, sequence, result)
    }

    suspend fun handleRoomJoin(sender: ClientSession, sequence: Long, req: TalkRoomJoin) {
        val senderUserId = sender.userId
        if (senderUserId == null) {
            respond(sender, sequence, joinAck(req.clientReqId, req.roomId, TalkRoomJoinAck.Result.SERVER_ERROR))
            return
        }
        val repo = talkRoomRepository
        if (repo == null) {
            respond(sender, sequence, joinAck(req.clientReqId, req.roomId, TalkRoomJoinAck.Result.SERVER_ERROR))
            return
        }
        val roomUuid = parseRoomId(req.roomId)
        if (roomUuid == null) {
            respond(sender, sequence, joinAck(req.clientReqId, req.roomId, TalkRoomJoinAck.Result.ROOM_NOT_FOUND))
            return
        }
        val result = runCatching {
            val outcome = repo.addMember(roomUuid, senderUserId, Instant.ofEpochMilli(nowMillis()))
            val mapped = when (outcome) {
                TalkRoomRepository.AddMemberResult.OK -> TalkRoomJoinAck.Result.OK
                TalkRoomRepository.AddMemberResult.ROOM_NOT_FOUND -> TalkRoomJoinAck.Result.ROOM_NOT_FOUND
                TalkRoomRepository.AddMemberResult.ALREADY_MEMBER -> TalkRoomJoinAck.Result.ALREADY_MEMBER
            }
            joinAck(req.clientReqId, req.roomId, mapped)
        }.getOrElse { t ->
            logger.error("TalkRoomJoin failed user={} room={}", senderUserId, req.roomId, t)
            joinAck(req.clientReqId, req.roomId, TalkRoomJoinAck.Result.SERVER_ERROR)
        }
        respond(sender, sequence, result)
    }

    /** 방 안 누구나 다른 사람을 초대할 수 있다(방장 개념 없음). 이미 멤버인 대상은 조용히 건너뛴다. */
    suspend fun handleRoomInvite(sender: ClientSession, sequence: Long, req: TalkRoomInvite) {
        val senderUserId = sender.userId
        if (senderUserId == null) {
            respond(sender, sequence, inviteAckFailure(req.clientReqId, req.roomId, TalkRoomInviteAck.Result.SERVER_ERROR))
            return
        }
        val repo = talkRoomRepository
        if (repo == null) {
            respond(sender, sequence, inviteAckFailure(req.clientReqId, req.roomId, TalkRoomInviteAck.Result.SERVER_ERROR))
            return
        }
        val roomUuid = parseRoomId(req.roomId)
        if (roomUuid == null) {
            respond(sender, sequence, inviteAckFailure(req.clientReqId, req.roomId, TalkRoomInviteAck.Result.ROOM_NOT_FOUND))
            return
        }
        if (!repo.isMember(roomUuid, senderUserId)) {
            respond(sender, sequence, inviteAckFailure(req.clientReqId, req.roomId, TalkRoomInviteAck.Result.NOT_MEMBER))
            return
        }
        val targets = req.targetUserIdsList.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) {
            respond(sender, sequence, inviteAckFailure(req.clientReqId, req.roomId, TalkRoomInviteAck.Result.NO_VALID_TARGETS))
            return
        }
        val result = runCatching {
            val outcome = repo.addMembers(roomUuid, targets, Instant.ofEpochMilli(nowMillis()))
            if (!outcome.roomFound) {
                return@runCatching inviteAckFailure(req.clientReqId, req.roomId, TalkRoomInviteAck.Result.ROOM_NOT_FOUND)
            }
            if (outcome.added.isEmpty()) {
                return@runCatching TalkRoomInviteAck.newBuilder()
                    .setClientReqId(req.clientReqId)
                    .setRoomId(req.roomId)
                    .addAllAlreadyMemberUserIds(outcome.alreadyMembers)
                    .setServerTs(nowMillis())
                    .setResult(TalkRoomInviteAck.Result.NO_VALID_TARGETS)
                    .build()
            }
            val fullMembers = repo.listMembers(roomUuid)
            val roomRow = repo.getRoom(roomUuid)
            broadcastMemberEvent(
                roomId = roomUuid,
                eventType = TalkRoomMemberEvent.EventType.MEMBERS_ADDED,
                actorUserId = senderUserId,
                affectedUserIds = outcome.added,
                memberIds = fullMembers,
                roomName = roomRow?.name ?: "",
                nameIsCustom = roomRow?.nameIsCustom ?: false,
            )
            TalkRoomInviteAck.newBuilder()
                .setClientReqId(req.clientReqId)
                .setRoomId(req.roomId)
                .addAllInvitedUserIds(outcome.added)
                .addAllAlreadyMemberUserIds(outcome.alreadyMembers)
                .setServerTs(nowMillis())
                .setResult(TalkRoomInviteAck.Result.OK)
                .build()
        }.getOrElse { t ->
            logger.error("TalkRoomInvite failed user={} room={}", senderUserId, req.roomId, t)
            inviteAckFailure(req.clientReqId, req.roomId, TalkRoomInviteAck.Result.SERVER_ERROR)
        }
        respond(sender, sequence, result)
    }

    /**
     * 나가기 — 본인만 방에서 제외된다. 0명이 되면 방/멤버십은 삭제되지만 대화 기록은 보존된다
     * (talk_room_messages.room_id ON DELETE SET NULL). 1:1(2인) 방이었는지는 클라이언트가
     * member_ids 개수로 판단해 "나갔습니다" 시스템 메시지 표시 여부를 스스로 결정한다.
     */
    suspend fun handleRoomLeave(sender: ClientSession, sequence: Long, req: TalkRoomLeave) {
        val senderUserId = sender.userId
        if (senderUserId == null) {
            respond(sender, sequence, leaveAckFailure(req.clientReqId, req.roomId, TalkRoomLeaveAck.Result.SERVER_ERROR))
            return
        }
        val repo = talkRoomRepository
        if (repo == null) {
            respond(sender, sequence, leaveAckFailure(req.clientReqId, req.roomId, TalkRoomLeaveAck.Result.SERVER_ERROR))
            return
        }
        val roomUuid = parseRoomId(req.roomId)
        if (roomUuid == null) {
            respond(sender, sequence, leaveAckFailure(req.clientReqId, req.roomId, TalkRoomLeaveAck.Result.ROOM_NOT_FOUND))
            return
        }

        val result = runCatching {
            val outcome = repo.removeMember(roomUuid, senderUserId)
            val mapped = when (outcome.result) {
                TalkRoomRepository.RemoveMemberResult.OK -> TalkRoomLeaveAck.Result.OK
                TalkRoomRepository.RemoveMemberResult.ROOM_NOT_FOUND -> TalkRoomLeaveAck.Result.ROOM_NOT_FOUND
                TalkRoomRepository.RemoveMemberResult.NOT_MEMBER -> TalkRoomLeaveAck.Result.NOT_MEMBER
            }
            if (mapped == TalkRoomLeaveAck.Result.OK && !outcome.roomDeleted) {
                // 클라는 "나가기 직전 인원수" 를 member_ids.size + affected_user_ids.size(=1) 로
                // 재구성해서 1:1(2인) 방이었는지 판단하므로 별도 필드가 필요 없다.
                val roomRow = repo.getRoom(roomUuid)
                broadcastMemberEvent(
                    roomId = roomUuid,
                    eventType = TalkRoomMemberEvent.EventType.MEMBER_LEFT,
                    actorUserId = senderUserId,
                    affectedUserIds = listOf(senderUserId),
                    memberIds = outcome.remainingMembers,
                    roomName = roomRow?.name ?: "",
                    nameIsCustom = roomRow?.nameIsCustom ?: false,
                )
            }
            TalkRoomLeaveAck.newBuilder()
                .setClientReqId(req.clientReqId)
                .setRoomId(req.roomId)
                .setRoomDeleted(outcome.roomDeleted)
                .setServerTs(nowMillis())
                .setResult(mapped)
                .build()
        }.getOrElse { t ->
            logger.error("TalkRoomLeave failed user={} room={}", senderUserId, req.roomId, t)
            leaveAckFailure(req.clientReqId, req.roomId, TalkRoomLeaveAck.Result.SERVER_ERROR)
        }
        respond(sender, sequence, result)
    }

    /** 이름 변경 — 방 안 누구나 가능. */
    suspend fun handleRoomRename(sender: ClientSession, sequence: Long, req: TalkRoomRename) {
        val senderUserId = sender.userId
        if (senderUserId == null) {
            respond(sender, sequence, renameAckFailure(req.clientReqId, req.roomId, TalkRoomRenameAck.Result.SERVER_ERROR))
            return
        }
        val repo = talkRoomRepository
        if (repo == null) {
            respond(sender, sequence, renameAckFailure(req.clientReqId, req.roomId, TalkRoomRenameAck.Result.SERVER_ERROR))
            return
        }
        if (req.newName.isBlank() || req.newName.length > 255) {
            respond(sender, sequence, renameAckFailure(req.clientReqId, req.roomId, TalkRoomRenameAck.Result.INVALID_NAME))
            return
        }
        val roomUuid = parseRoomId(req.roomId)
        if (roomUuid == null) {
            respond(sender, sequence, renameAckFailure(req.clientReqId, req.roomId, TalkRoomRenameAck.Result.ROOM_NOT_FOUND))
            return
        }
        if (!repo.isMember(roomUuid, senderUserId)) {
            respond(sender, sequence, renameAckFailure(req.clientReqId, req.roomId, TalkRoomRenameAck.Result.NOT_MEMBER))
            return
        }
        val result = runCatching {
            val renamed = repo.renameRoom(roomUuid, req.newName)
            if (!renamed) {
                return@runCatching renameAckFailure(req.clientReqId, req.roomId, TalkRoomRenameAck.Result.ROOM_NOT_FOUND)
            }
            val members = repo.listMembers(roomUuid)
            broadcastRenamed(roomUuid, req.newName, members)
            TalkRoomRenameAck.newBuilder()
                .setClientReqId(req.clientReqId)
                .setRoomId(req.roomId)
                .setResult(TalkRoomRenameAck.Result.OK)
                .build()
        }.getOrElse { t ->
            logger.error("TalkRoomRename failed user={} room={}", senderUserId, req.roomId, t)
            renameAckFailure(req.clientReqId, req.roomId, TalkRoomRenameAck.Result.SERVER_ERROR)
        }
        respond(sender, sequence, result)
    }

    suspend fun handleRoomInfo(sender: ClientSession, sequence: Long, req: TalkRoomInfoRequest) {
        val repo = talkRoomRepository
        if (repo == null) {
            respond(sender, sequence, buildRoomInfoFailure(req.clientReqId, req.roomId, TalkRoomInfoResponse.Result.SERVER_ERROR))
            return
        }
        val roomUuid = parseRoomId(req.roomId)
        if (roomUuid == null) {
            respond(sender, sequence, buildRoomInfoFailure(req.clientReqId, req.roomId, TalkRoomInfoResponse.Result.ROOM_NOT_FOUND))
            return
        }
        val result = runCatching {
            val row = repo.getRoom(roomUuid)
            if (row == null) {
                buildRoomInfoFailure(req.clientReqId, req.roomId, TalkRoomInfoResponse.Result.ROOM_NOT_FOUND)
            } else {
                val members = repo.listMembers(roomUuid)
                TalkRoomInfoResponse.newBuilder()
                    .setClientReqId(req.clientReqId)
                    .setRoomId(row.roomId.toString())
                    .setName(row.name)
                    .setOwnerUserId(row.ownerUserId)
                    .setMemberCount(members.size)
                    .addAllMemberIds(members)
                    .setServerTs(nowMillis())
                    .setResult(TalkRoomInfoResponse.Result.OK)
                    .setNameIsCustom(row.nameIsCustom)
                    .build()
            }
        }.getOrElse { t ->
            logger.error("RoomInfo failed room={}", req.roomId, t)
            buildRoomInfoFailure(req.clientReqId, req.roomId, TalkRoomInfoResponse.Result.SERVER_ERROR)
        }
        respond(sender, sequence, result)
    }

    /** 로그인 직후 사이드바 복원용 — 현재 이 사용자가 멤버인 모든 방을 반환한다. */
    suspend fun handleRoomList(sender: ClientSession, sequence: Long, req: TalkRoomListRequest) {
        val repo = talkRoomRepository
        val userId = sender.userId
        if (repo == null || userId == null) {
            respond(
                sender,
                sequence,
                TalkRoomListResponse.newBuilder()
                    .setClientReqId(req.clientReqId)
                    .setResult(TalkRoomListResponse.Result.SERVER_ERROR)
                    .build(),
            )
            return
        }
        val result = runCatching {
            val rooms = repo.findRoomsForUser(userId)
            TalkRoomListResponse.newBuilder()
                .setClientReqId(req.clientReqId)
                .addAllRooms(
                    rooms.map {
                        TalkRoomListItem.newBuilder()
                            .setRoomId(it.roomId.toString())
                            .setName(it.name)
                            .setMemberCount(it.memberCount)
                            .setLastMessageText(it.lastMessageText ?: "")
                            .setLastMessageTs(it.lastMessageTs?.toEpochMilli() ?: 0L)
                            .setNameIsCustom(it.nameIsCustom)
                            .addAllMemberIds(it.memberIds)
                            .build()
                    },
                )
                .setResult(TalkRoomListResponse.Result.OK)
                .build()
        }.getOrElse { t ->
            logger.error("RoomList failed user={}", userId, t)
            TalkRoomListResponse.newBuilder()
                .setClientReqId(req.clientReqId)
                .setResult(TalkRoomListResponse.Result.SERVER_ERROR)
                .build()
        }
        respond(sender, sequence, result)
    }

    suspend fun handleRoomSend(sender: ClientSession, sequence: Long, req: TalkRoomSend) {
        com.osori.server.metrics.Metrics.roomMessagesSent.incrementAndGet()
        val senderUserId = sender.userId
        if (senderUserId == null) {
            respond(sender, sequence, messageAckFailure(req.clientTalkId, req.roomId, TalkRoomSendAck.Result.SERVER_ERROR))
            return
        }
        val repo = talkRoomRepository
        if (repo == null) {
            respond(sender, sequence, messageAckFailure(req.clientTalkId, req.roomId, TalkRoomSendAck.Result.SERVER_ERROR))
            return
        }
        val roomUuid = parseRoomId(req.roomId)
        if (roomUuid == null || repo.getRoom(roomUuid) == null) {
            respond(sender, sequence, messageAckFailure(req.clientTalkId, req.roomId, TalkRoomSendAck.Result.ROOM_NOT_FOUND))
            return
        }
        if (!repo.isMember(roomUuid, senderUserId)) {
            respond(sender, sequence, messageAckFailure(req.clientTalkId, req.roomId, TalkRoomSendAck.Result.NOT_MEMBER))
            return
        }
        val serverTsMillis = nowMillis()
        val serverTsInstant = Instant.ofEpochMilli(serverTsMillis)
        val roomMsgUuid = runCatching {
            repo.insertRoomTalk(roomUuid, senderUserId, req.text, serverTsInstant)
        }.getOrElse { t ->
            logger.error("TalkRoomSend insert failed user={} room={}", senderUserId, req.roomId, t)
            respond(sender, sequence, messageAckFailure(req.clientTalkId, req.roomId, TalkRoomSendAck.Result.SERVER_ERROR))
            return
        }

        val ack = TalkRoomSendAck.newBuilder()
            .setClientTalkId(req.clientTalkId)
            .setServerTalkId(roomMsgUuid.toString())
            .setRoomId(req.roomId)
            .setServerTs(serverTsMillis)
            .setResult(TalkRoomSendAck.Result.OK)
            .build()
        respond(sender, sequence, ack)

        val broadcast = TalkRoomReceived.newBuilder()
            .setServerTalkId(roomMsgUuid.toString())
            .setRoomId(req.roomId)
            .setFromUserId(senderUserId)
            .setText(req.text)
            .setServerTs(serverTsMillis)
            .build()

        val members = runCatching { repo.listMembers(roomUuid) }.getOrDefault(emptyList())
        members.forEach { memberId ->
            if (memberId == senderUserId) return@forEach
            val recipient = sessions.get(memberId) ?: return@forEach
            val envelope = Envelope.newBuilder()
                .setSequence(serverSequence.incrementAndGet())
                .setTalkRoomReceived(broadcast)
                .build()
            runCatching {
                recipient.send(Frame.Binary(true, envelope.toByteArray()))
                com.osori.server.metrics.Metrics.roomBroadcasts.incrementAndGet()
            }.onFailure { t -> logger.warn("broadcast to {} failed", memberId, t) }
        }
    }

    /**
     * 멤버 추가/삭제를 이벤트로 만들어 "이벤트 반영 후 이 방에 있는 사람들"(memberIds) 중
     * 현재 온라인인 사람 전원에게 브로드캐스트한다 — 새로 초대된 사람도 memberIds 에 포함되므로
     * 함께 받아서 자기 목록에 방이 나타난다. 나가기 이벤트에서 클라는 "나가기 직전 인원수" 를
     * memberIds.size + affectedUserIds.size(=1) 로 재구성해 1:1(2인) 방이었는지 판단한다
     * (그래서 서버가 별도로 "이전 인원수" 필드를 실어줄 필요가 없다).
     */
    private suspend fun broadcastMemberEvent(
        roomId: UUID,
        eventType: TalkRoomMemberEvent.EventType,
        actorUserId: String,
        affectedUserIds: List<String>,
        memberIds: List<String>,
        roomName: String = "",
        nameIsCustom: Boolean = false,
    ) {
        val event = TalkRoomMemberEvent.newBuilder()
            .setRoomId(roomId.toString())
            .setEventType(eventType)
            .setActorUserId(actorUserId)
            .addAllAffectedUserIds(affectedUserIds)
            .addAllMemberIds(memberIds)
            .setServerTs(nowMillis())
            .setRoomName(roomName)
            .setNameIsCustom(nameIsCustom)
            .build()
        for (memberId in memberIds) {
            val session = sessions.get(memberId) ?: continue
            val envelope = Envelope.newBuilder()
                .setSequence(serverSequence.incrementAndGet())
                .setTalkRoomMemberEvent(event)
                .build()
            runCatching { session.send(Frame.Binary(true, envelope.toByteArray())) }
                .onFailure { t -> logger.warn("member-event broadcast to {} failed", memberId, t) }
        }
    }

    private suspend fun broadcastRenamed(roomId: UUID, newName: String, memberIds: List<String>) {
        val event = TalkRoomRenamed.newBuilder()
            .setRoomId(roomId.toString())
            .setNewName(newName)
            .setServerTs(nowMillis())
            .build()
        for (memberId in memberIds) {
            val session = sessions.get(memberId) ?: continue
            val envelope = Envelope.newBuilder()
                .setSequence(serverSequence.incrementAndGet())
                .setTalkRoomRenamed(event)
                .build()
            runCatching { session.send(Frame.Binary(true, envelope.toByteArray())) }
                .onFailure { t -> logger.warn("rename broadcast to {} failed", memberId, t) }
        }
    }

    private suspend fun respond(sender: ClientSession, sequence: Long, ack: TalkRoomCreateAck) {
        val envelope = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkRoomCreateAck(ack)
            .build()
        sender.send(Frame.Binary(true, envelope.toByteArray()))
    }

    private suspend fun respond(sender: ClientSession, sequence: Long, ack: TalkRoomJoinAck) {
        val envelope = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkRoomJoinAck(ack)
            .build()
        sender.send(Frame.Binary(true, envelope.toByteArray()))
    }

    private suspend fun respond(sender: ClientSession, sequence: Long, ack: TalkRoomInviteAck) {
        val envelope = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkRoomInviteAck(ack)
            .build()
        sender.send(Frame.Binary(true, envelope.toByteArray()))
    }

    private suspend fun respond(sender: ClientSession, sequence: Long, ack: TalkRoomLeaveAck) {
        val envelope = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkRoomLeaveAck(ack)
            .build()
        sender.send(Frame.Binary(true, envelope.toByteArray()))
    }

    private suspend fun respond(sender: ClientSession, sequence: Long, ack: TalkRoomRenameAck) {
        val envelope = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkRoomRenameAck(ack)
            .build()
        sender.send(Frame.Binary(true, envelope.toByteArray()))
    }

    private suspend fun respond(sender: ClientSession, sequence: Long, ack: TalkRoomSendAck) {
        val envelope = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkRoomSendAck(ack)
            .build()
        sender.send(Frame.Binary(true, envelope.toByteArray()))
    }

    private suspend fun respond(sender: ClientSession, sequence: Long, ack: TalkRoomInfoResponse) {
        val envelope = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkRoomInfoRes(ack)
            .build()
        sender.send(Frame.Binary(true, envelope.toByteArray()))
    }

    private suspend fun respond(sender: ClientSession, sequence: Long, ack: TalkRoomListResponse) {
        val envelope = Envelope.newBuilder()
            .setSequence(sequence)
            .setTalkRoomListRes(ack)
            .build()
        sender.send(Frame.Binary(true, envelope.toByteArray()))
    }

    private fun buildRoomInfoFailure(clientReqId: String, roomId: String, result: TalkRoomInfoResponse.Result): TalkRoomInfoResponse =
        TalkRoomInfoResponse.newBuilder()
            .setClientReqId(clientReqId)
            .setRoomId(roomId)
            .setServerTs(nowMillis())
            .setResult(result)
            .build()

    private fun ackFailure(clientReqId: String, roomId: String, result: TalkRoomCreateAck.Result): TalkRoomCreateAck =
        TalkRoomCreateAck.newBuilder()
            .setClientReqId(clientReqId)
            .setRoomId(roomId)
            .setServerTs(nowMillis())
            .setResult(result)
            .build()

    private fun joinAck(clientReqId: String, roomId: String, result: TalkRoomJoinAck.Result): TalkRoomJoinAck =
        TalkRoomJoinAck.newBuilder()
            .setClientReqId(clientReqId)
            .setRoomId(roomId)
            .setServerTs(nowMillis())
            .setResult(result)
            .build()

    private fun inviteAckFailure(clientReqId: String, roomId: String, result: TalkRoomInviteAck.Result): TalkRoomInviteAck =
        TalkRoomInviteAck.newBuilder()
            .setClientReqId(clientReqId)
            .setRoomId(roomId)
            .setServerTs(nowMillis())
            .setResult(result)
            .build()

    private fun leaveAckFailure(clientReqId: String, roomId: String, result: TalkRoomLeaveAck.Result): TalkRoomLeaveAck =
        TalkRoomLeaveAck.newBuilder()
            .setClientReqId(clientReqId)
            .setRoomId(roomId)
            .setServerTs(nowMillis())
            .setResult(result)
            .build()

    private fun renameAckFailure(clientReqId: String, roomId: String, result: TalkRoomRenameAck.Result): TalkRoomRenameAck =
        TalkRoomRenameAck.newBuilder()
            .setClientReqId(clientReqId)
            .setRoomId(roomId)
            .setResult(result)
            .build()

    private fun messageAckFailure(clientTalkId: String, roomId: String, result: TalkRoomSendAck.Result): TalkRoomSendAck =
        TalkRoomSendAck.newBuilder()
            .setClientTalkId(clientTalkId)
            .setRoomId(roomId)
            .setServerTs(nowMillis())
            .setResult(result)
            .build()

    private fun parseRoomId(raw: String): UUID? =
        runCatching { UUID.fromString(raw) }.getOrNull()
}

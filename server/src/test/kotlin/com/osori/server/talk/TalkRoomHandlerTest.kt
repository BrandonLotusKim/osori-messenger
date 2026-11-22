package com.osori.server.talk

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.TalkRoomCreate
import com.osori.proto.v1.TalkRoomCreateAck
import com.osori.proto.v1.TalkRoomInvite
import com.osori.proto.v1.TalkRoomInviteAck
import com.osori.proto.v1.TalkRoomJoin
import com.osori.proto.v1.TalkRoomJoinAck
import com.osori.proto.v1.TalkRoomLeave
import com.osori.proto.v1.TalkRoomLeaveAck
import com.osori.proto.v1.TalkRoomMemberEvent
import com.osori.proto.v1.TalkRoomRename
import com.osori.proto.v1.TalkRoomRenameAck
import com.osori.proto.v1.TalkRoomSend
import com.osori.proto.v1.TalkRoomSendAck
import com.osori.server.db.TalkRoomRepository
import com.osori.server.db.UserRepository
import com.osori.server.session.SessionManager
import com.osori.server.testutil.FakeClientSession
import com.osori.server.testutil.PostgresTestContainer
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TalkRoomHandlerTest {

    private lateinit var roomRepo: TalkRoomRepository
    private lateinit var handler: TalkRoomHandler
    private lateinit var sessions: SessionManager

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        val db = PostgresTestContainer.database()
        val userRepo = UserRepository(db)
        userRepo.createIfAbsent("alice", "Alice")
        userRepo.createIfAbsent("bob", "Bob")
        userRepo.createIfAbsent("carol", "Carol")
        roomRepo = TalkRoomRepository(db)
        sessions = SessionManager()
        handler = TalkRoomHandler(sessions, roomRepo)
    }

    @Test
    fun `handleRoomCreate with valid name returns OK ack with room id`() = runBlocking {
        val sender = FakeClientSession().apply { userId = "alice" }
        handler.handleRoomCreate(
            sender,
            sequence = 1L,
            req = TalkRoomCreate.newBuilder().setName("team").setClientReqId("req-1").build(),
        )
        val ack = parseAck(sender, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck
        assertEquals(TalkRoomCreateAck.Result.OK, ack.result)
        assertEquals("req-1", ack.clientReqId)
        assertTrue(ack.roomId.isNotBlank())
    }

    @Test
    fun `read report advances only forward, broadcasts to other online members, ignores non members`() = runBlocking {
        val now = 1_800_000_000_000L
        val h = TalkRoomHandler(sessions, roomRepo, nowMillis = { now })
        val roomId = roomRepo.createRoom("g", "alice", java.time.Instant.ofEpochMilli(now - 10_000), listOf("bob", "carol"))
        val alice = FakeClientSession().apply { userId = "alice" }
        val bob = FakeClientSession().apply { userId = "bob" }
        val carol = FakeClientSession().apply { userId = "carol" }
        sessions.bind("alice", alice); sessions.bind("bob", bob); sessions.bind("carol", carol)

        h.handleReadReport(bob, com.osori.proto.v1.TalkRoomReadReport.newBuilder().setRoomId(roomId.toString()).setLastReadTs(now - 5_000).build())
        val upd = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_READ_UPDATE).talkRoomReadUpdate
        assertEquals("bob", upd.userId)
        assertEquals(now - 5_000, upd.lastReadTs)
        assertTrue(carol.sentFrames.isNotEmpty())
        assertTrue(bob.sentFrames.isEmpty(), "보고한 본인에게는 보내지 않음")

        // 과거 값 → 무시(방송 없음), 미래 값 → 서버 현재 시각으로 자름
        alice.sentFrames.clear()
        h.handleReadReport(bob, com.osori.proto.v1.TalkRoomReadReport.newBuilder().setRoomId(roomId.toString()).setLastReadTs(now - 9_000).build())
        assertTrue(alice.sentFrames.isEmpty())
        h.handleReadReport(bob, com.osori.proto.v1.TalkRoomReadReport.newBuilder().setRoomId(roomId.toString()).setLastReadTs(now + 60_000).build())
        assertEquals(now, parseAck(alice, Envelope.PayloadCase.TALK_ROOM_READ_UPDATE).talkRoomReadUpdate.lastReadTs)
        assertEquals(now, roomRepo.listReadStates(roomId)["bob"]?.toEpochMilli())

        // 멤버 아님 → 저장 안 함
        UserRepository(PostgresTestContainer.database()).createIfAbsent("dave", "Dave")
        val dave = FakeClientSession().apply { userId = "dave" }
        h.handleReadReport(dave, com.osori.proto.v1.TalkRoomReadReport.newBuilder().setRoomId(roomId.toString()).setLastReadTs(now).build())
        assertTrue(roomRepo.listReadStates(roomId)["dave"] == null)
    }

    @Test
    fun `sending a room message marks sender as read up to it`() = runBlocking {
        val now = 1_800_000_000_000L
        val h = TalkRoomHandler(sessions, roomRepo, nowMillis = { now })
        val roomId = roomRepo.createRoom("g", "alice", java.time.Instant.ofEpochMilli(now - 10_000), listOf("bob"))
        val alice = FakeClientSession().apply { userId = "alice" }
        h.handleRoomSend(alice, 1L, TalkRoomSend.newBuilder().setRoomId(roomId.toString()).setClientTalkId("c").setText("hi").build())
        assertEquals(now, roomRepo.listReadStates(roomId)["alice"]?.toEpochMilli())
    }

    @Test
    fun `handleRoomCreate with blank name returns INVALID_NAME`() = runBlocking {
        val sender = FakeClientSession().apply { userId = "alice" }
        handler.handleRoomCreate(
            sender,
            sequence = 2L,
            req = TalkRoomCreate.newBuilder().setName("   ").setClientReqId("req-2").build(),
        )
        val ack = parseAck(sender, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck
        assertEquals(TalkRoomCreateAck.Result.INVALID_NAME, ack.result)
    }

    @Test
    fun `handleRoomJoin on existing room adds user and returns OK`() = runBlocking {
        val owner = FakeClientSession().apply { userId = "alice" }
        handler.handleRoomCreate(
            owner,
            sequence = 1L,
            req = TalkRoomCreate.newBuilder().setName("team").setClientReqId("create").build(),
        )
        val roomId = parseAck(owner, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck.roomId

        val joiner = FakeClientSession().apply { userId = "bob" }
        handler.handleRoomJoin(
            joiner,
            sequence = 2L,
            req = TalkRoomJoin.newBuilder().setRoomId(roomId).setClientReqId("join").build(),
        )
        val joinAck = parseAck(joiner, Envelope.PayloadCase.TALK_ROOM_JOIN_ACK).talkRoomJoinAck
        assertEquals(TalkRoomJoinAck.Result.OK, joinAck.result)
        assertTrue(roomRepo.isMember(java.util.UUID.fromString(roomId), "bob"))
    }

    @Test
    fun `handleRoomSend rejects non-member with NOT_MEMBER`() = runBlocking {
        val owner = FakeClientSession().apply { userId = "alice" }
        handler.handleRoomCreate(
            owner,
            sequence = 1L,
            req = TalkRoomCreate.newBuilder().setName("team").setClientReqId("create").build(),
        )
        val roomId = parseAck(owner, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck.roomId

        val intruder = FakeClientSession().apply { userId = "carol" }
        handler.handleRoomSend(
            intruder,
            sequence = 5L,
            req = TalkRoomSend.newBuilder()
                .setClientTalkId("c-1")
                .setRoomId(roomId)
                .setText("hi")
                .setClientTs(System.currentTimeMillis())
                .build(),
        )
        val ack = parseAck(intruder, Envelope.PayloadCase.TALK_ROOM_SEND_ACK).talkRoomSendAck
        assertEquals(TalkRoomSendAck.Result.NOT_MEMBER, ack.result)
    }

    @Test
    fun `handleRoomSend broadcasts to other online members only`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        val bob = FakeClientSession().apply { userId = "bob" }
        val carol = FakeClientSession().apply { userId = "carol" }
        sessions.bind("alice", alice)
        sessions.bind("bob", bob)
        sessions.bind("carol", carol)

        handler.handleRoomCreate(
            alice,
            sequence = 1L,
            req = TalkRoomCreate.newBuilder().setName("team").setClientReqId("create").build(),
        )
        val roomId = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck.roomId
        handler.handleRoomJoin(
            bob,
            sequence = 2L,
            req = TalkRoomJoin.newBuilder().setRoomId(roomId).setClientReqId("jb").build(),
        )
        handler.handleRoomJoin(
            carol,
            sequence = 3L,
            req = TalkRoomJoin.newBuilder().setRoomId(roomId).setClientReqId("jc").build(),
        )
        alice.sentFrames.clear()
        bob.sentFrames.clear()
        carol.sentFrames.clear()

        handler.handleRoomSend(
            alice,
            sequence = 4L,
            req = TalkRoomSend.newBuilder()
                .setClientTalkId("c-1")
                .setRoomId(roomId)
                .setText("hi team")
                .setClientTs(System.currentTimeMillis())
                .build(),
        )

        val aliceAck = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_SEND_ACK).talkRoomSendAck
        assertEquals(TalkRoomSendAck.Result.OK, aliceAck.result)
        assertTrue(aliceAck.serverTalkId.isNotBlank())

        val bobBroadcast = parseLastBroadcast(bob)
        val carolBroadcast = parseLastBroadcast(carol)
        assertEquals("hi team", bobBroadcast.text)
        assertEquals("alice", bobBroadcast.fromUserId)
        assertEquals("hi team", carolBroadcast.text)

        // Sender should not receive the broadcast, only the ack
        val aliceOnlyAck = alice.sentFrames
            .filterIsInstance<Frame.Binary>()
            .map { Envelope.parseFrom(it.readBytes()) }
            .count { it.payloadCase == Envelope.PayloadCase.TALK_ROOM_RECEIVED }
        assertEquals(0, aliceOnlyAck)
    }

    @Test
    fun `handleRoomCreate with member_ids adds everyone and notifies invited members`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        val bob = FakeClientSession().apply { userId = "bob" }
        sessions.bind("alice", alice)
        sessions.bind("bob", bob)

        handler.handleRoomCreate(
            alice,
            sequence = 1L,
            req = TalkRoomCreate.newBuilder().setName("team").addMemberIds("bob").setClientReqId("create").build(),
        )
        val ack = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck
        assertEquals(TalkRoomCreateAck.Result.OK, ack.result)
        val roomId = java.util.UUID.fromString(ack.roomId)
        assertEquals(setOf("alice", "bob"), roomRepo.listMembers(roomId).toSet())

        val event = parseAck(bob, Envelope.PayloadCase.TALK_ROOM_MEMBER_EVENT).talkRoomMemberEvent
        assertEquals(TalkRoomMemberEvent.EventType.MEMBERS_ADDED, event.eventType)
        assertEquals(listOf("bob"), event.affectedUserIdsList)
    }

    @Test
    fun `handleRoomCreate detects a duplicate member set and force bypasses it`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        handler.handleRoomCreate(
            alice,
            sequence = 1L,
            req = TalkRoomCreate.newBuilder().setName("team").addMemberIds("bob").setClientReqId("c1").build(),
        )
        val firstRoomId = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck.roomId

        alice.sentFrames.clear()
        handler.handleRoomCreate(
            alice,
            sequence = 2L,
            req = TalkRoomCreate.newBuilder().setName("team again").addMemberIds("bob").setClientReqId("c2").build(),
        )
        val dupAck = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck
        assertEquals(TalkRoomCreateAck.Result.DUPLICATE_MEMBERS, dupAck.result)
        assertEquals(firstRoomId, dupAck.existingRoomId)

        alice.sentFrames.clear()
        handler.handleRoomCreate(
            alice,
            sequence = 3L,
            req = TalkRoomCreate.newBuilder().setName("team, take 2").addMemberIds("bob").setForce(true).setClientReqId("c3").build(),
        )
        val forcedAck = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck
        assertEquals(TalkRoomCreateAck.Result.OK, forcedAck.result)
        assertTrue(forcedAck.roomId != firstRoomId)
    }

    @Test
    fun `handleRoomInvite lets any member invite and reports already-members`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        val bob = FakeClientSession().apply { userId = "bob" }
        val carol = FakeClientSession().apply { userId = "carol" }
        sessions.bind("alice", alice)
        sessions.bind("bob", bob)
        sessions.bind("carol", carol)

        handler.handleRoomCreate(
            alice,
            sequence = 1L,
            req = TalkRoomCreate.newBuilder().setName("team").addMemberIds("bob").setClientReqId("create").build(),
        )
        val roomId = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck.roomId
        bob.sentFrames.clear()

        // 방장이 없으므로 생성자가 아닌 bob 도 초대할 수 있어야 한다.
        handler.handleRoomInvite(
            bob,
            sequence = 2L,
            req = TalkRoomInvite.newBuilder().setRoomId(roomId).addTargetUserIds("carol").addTargetUserIds("alice")
                .setClientReqId("inv").build(),
        )
        val ack = parseAck(bob, Envelope.PayloadCase.TALK_ROOM_INVITE_ACK).talkRoomInviteAck
        assertEquals(TalkRoomInviteAck.Result.OK, ack.result)
        assertEquals(listOf("carol"), ack.invitedUserIdsList)
        assertEquals(listOf("alice"), ack.alreadyMemberUserIdsList)
        assertEquals(setOf("alice", "bob", "carol"), roomRepo.listMembers(java.util.UUID.fromString(roomId)).toSet())

        val carolEvent = parseAck(carol, Envelope.PayloadCase.TALK_ROOM_MEMBER_EVENT).talkRoomMemberEvent
        assertEquals("bob", carolEvent.actorUserId)
    }

    @Test
    fun `handleRoomInvite rejects a non-member caller`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        handler.handleRoomCreate(
            alice,
            sequence = 1L,
            req = TalkRoomCreate.newBuilder().setName("team").setClientReqId("create").build(),
        )
        val roomId = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck.roomId

        val outsider = FakeClientSession().apply { userId = "carol" }
        handler.handleRoomInvite(
            outsider,
            sequence = 2L,
            req = TalkRoomInvite.newBuilder().setRoomId(roomId).addTargetUserIds("bob").setClientReqId("inv").build(),
        )
        val ack = parseAck(outsider, Envelope.PayloadCase.TALK_ROOM_INVITE_ACK).talkRoomInviteAck
        assertEquals(TalkRoomInviteAck.Result.NOT_MEMBER, ack.result)
    }

    @Test
    fun `handleRoomLeave removes only the caller and notifies remaining members`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        val bob = FakeClientSession().apply { userId = "bob" }
        sessions.bind("alice", alice)
        sessions.bind("bob", bob)

        handler.handleRoomCreate(
            alice,
            sequence = 1L,
            req = TalkRoomCreate.newBuilder().setName("team").addMemberIds("bob").setClientReqId("create").build(),
        )
        val roomId = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck.roomId
        bob.sentFrames.clear()

        handler.handleRoomLeave(
            alice,
            sequence = 2L,
            req = TalkRoomLeave.newBuilder().setRoomId(roomId).setClientReqId("leave").build(),
        )
        val ack = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_LEAVE_ACK).talkRoomLeaveAck
        assertEquals(TalkRoomLeaveAck.Result.OK, ack.result)
        assertEquals(false, ack.roomDeleted)
        assertEquals(listOf("bob"), roomRepo.listMembers(java.util.UUID.fromString(roomId)))

        val event = parseAck(bob, Envelope.PayloadCase.TALK_ROOM_MEMBER_EVENT).talkRoomMemberEvent
        assertEquals(TalkRoomMemberEvent.EventType.MEMBER_LEFT, event.eventType)
        assertEquals(listOf("alice"), event.affectedUserIdsList)
        assertEquals(listOf("bob"), event.memberIdsList)
    }

    @Test
    fun `handleRoomLeave deletes the room once the last member leaves`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        handler.handleRoomCreate(
            alice,
            sequence = 1L,
            req = TalkRoomCreate.newBuilder().setName("solo").setClientReqId("create").build(),
        )
        val roomId = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck.roomId

        handler.handleRoomLeave(
            alice,
            sequence = 2L,
            req = TalkRoomLeave.newBuilder().setRoomId(roomId).setClientReqId("leave").build(),
        )
        val ack = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_LEAVE_ACK).talkRoomLeaveAck
        assertEquals(TalkRoomLeaveAck.Result.OK, ack.result)
        assertEquals(true, ack.roomDeleted)
    }

    @Test
    fun `handleRoomRename updates the name and broadcasts to other members`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        val bob = FakeClientSession().apply { userId = "bob" }
        sessions.bind("alice", alice)
        sessions.bind("bob", bob)

        handler.handleRoomCreate(
            alice,
            sequence = 1L,
            req = TalkRoomCreate.newBuilder().setName("team").addMemberIds("bob").setClientReqId("create").build(),
        )
        val roomId = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck.roomId
        bob.sentFrames.clear()

        // 방장이 없으므로 생성자가 아닌 bob 도 이름을 바꿀 수 있어야 한다.
        handler.handleRoomRename(
            bob,
            sequence = 2L,
            req = TalkRoomRename.newBuilder().setRoomId(roomId).setNewName("새 이름").setClientReqId("rename").build(),
        )
        val ack = parseAck(bob, Envelope.PayloadCase.TALK_ROOM_RENAME_ACK).talkRoomRenameAck
        assertEquals(TalkRoomRenameAck.Result.OK, ack.result)
        assertEquals("새 이름", roomRepo.getRoom(java.util.UUID.fromString(roomId))!!.name)

        val renamed = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_RENAMED).talkRoomRenamed
        assertEquals("새 이름", renamed.newName)
    }

    @Test
    fun `room send invite rename leave are written to audit log without message body`() = runBlocking {
        val audit = com.osori.server.audit.AuditRepository(PostgresTestContainer.database())
        val h = TalkRoomHandler(sessions, roomRepo, auditRepository = audit)
        val alice = FakeClientSession().apply { userId = "alice" }
        h.handleRoomCreate(alice, 1L, TalkRoomCreate.newBuilder().setName("team").setClientReqId("c").build())
        val roomId = parseAck(alice, Envelope.PayloadCase.TALK_ROOM_CREATE_ACK).talkRoomCreateAck.roomId

        h.handleRoomInvite(alice, 2L, TalkRoomInvite.newBuilder().setRoomId(roomId).addTargetUserIds("bob").setClientReqId("i").build())
        h.handleRoomSend(alice, 3L, TalkRoomSend.newBuilder().setClientTalkId("t").setRoomId(roomId).setText("비밀 본문 내용").build())
        h.handleRoomRename(alice, 4L, TalkRoomRename.newBuilder().setRoomId(roomId).setNewName("배포 논의").setClientReqId("r").build())
        h.handleRoomLeave(alice, 5L, TalkRoomLeave.newBuilder().setRoomId(roomId).setClientReqId("l").build())

        val sent = audit.findLatestByType("room.msg.sent").single()
        assertEquals("alice", sent.actorUserId)
        assertTrue(sent.payloadJson.contains(roomId))
        assertTrue(sent.payloadJson.contains("\"bytes\""))
        assertTrue(!sent.payloadJson.contains("비밀 본문"), "본문은 감사 로그에 남기지 않는다")

        val invited = audit.findLatestByType("room.invited").single()
        assertEquals(roomId, invited.target)
        assertTrue(invited.payloadJson.contains("bob"))

        val renamed = audit.findLatestByType("room.renamed").single()
        assertTrue(renamed.payloadJson.contains("배포 논의"))

        val left = audit.findLatestByType("room.left").single()
        assertEquals("alice", left.actorUserId)
        assertTrue(left.payloadJson.contains("\"roomDeleted\": false") || left.payloadJson.contains("\"roomDeleted\":false"))
    }

    private fun parseAck(session: FakeClientSession, expected: Envelope.PayloadCase): Envelope {
        val envelope = session.sentFrames
            .filterIsInstance<Frame.Binary>()
            .map { Envelope.parseFrom(it.readBytes()) }
            .first { it.payloadCase == expected }
        return envelope
    }

    private fun parseLastBroadcast(session: FakeClientSession): com.osori.proto.v1.TalkRoomReceived {
        val envelope = session.sentFrames
            .filterIsInstance<Frame.Binary>()
            .map { Envelope.parseFrom(it.readBytes()) }
            .last { it.payloadCase == Envelope.PayloadCase.TALK_ROOM_RECEIVED }
        return envelope.talkRoomReceived
    }
}

package com.osori.server.file

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.FileAvailableNotification
import com.osori.proto.v1.FileDownloadRequest
import com.osori.proto.v1.FileDownloadStart
import com.osori.proto.v1.FileUploadChunk
import com.osori.proto.v1.FileUploadComplete
import com.osori.proto.v1.FileUploadCompleteAck
import com.osori.proto.v1.FileUploadStart
import com.osori.proto.v1.FileUploadStartAck
import com.osori.server.db.FileRepository
import com.osori.server.db.UserRepository
import com.osori.server.session.SessionManager
import com.osori.server.testutil.FakeClientSession
import com.osori.server.testutil.PostgresTestContainer
import com.google.protobuf.ByteString
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileHandlerTest {

    private lateinit var storageRoot: Path
    private lateinit var sessions: SessionManager
    private lateinit var fileRepo: FileRepository
    private lateinit var roomRepo: com.osori.server.db.TalkRoomRepository
    private lateinit var handler: FileHandler

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        val db = PostgresTestContainer.database()
        val userRepo = UserRepository(db)
        userRepo.createIfAbsent("alice", "Alice")
        userRepo.createIfAbsent("bob", "Bob")
        fileRepo = FileRepository(db)
        roomRepo = com.osori.server.db.TalkRoomRepository(db)
        userRepo.createIfAbsent("carol", "Carol")
        sessions = SessionManager()
        storageRoot = Files.createTempDirectory("osori-file-handler-test-")
        handler = FileHandler(sessions, fileRepo, storageRoot, roomRepository = roomRepo)
    }

    @After
    fun tearDown() {
        runCatching {
            Files.walk(storageRoot)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `happy path upload delivers notification to online recipient`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        val bob = FakeClientSession().apply { userId = "bob" }
        sessions.bind("alice", alice)
        sessions.bind("bob", bob)

        val payload = "hello file".toByteArray()
        handler.handleStart(
            alice, sequence = 1L,
            req = FileUploadStart.newBuilder()
                .setClientReqId("req-1")
                .setToUserId("bob")
                .setFilename("note.txt")
                .setTotalSize(payload.size.toLong())
                .setMimeType("text/plain")
                .build(),
        )
        val startAck = parseAck(alice, Envelope.PayloadCase.FILE_UPLOAD_START_ACK).fileUploadStartAck
        assertEquals(FileUploadStartAck.Result.OK, startAck.result)
        assertTrue(startAck.uploadId.isNotBlank())
        val uploadId = startAck.uploadId

        handler.handleChunk(
            alice, sequence = 2L,
            req = FileUploadChunk.newBuilder()
                .setUploadId(uploadId)
                .setSeq(0)
                .setData(ByteString.copyFrom(payload))
                .build(),
        )

        handler.handleComplete(
            alice, sequence = 3L,
            req = FileUploadComplete.newBuilder()
                .setUploadId(uploadId)
                .setClientReqId("req-1")
                .build(),
        )
        val completeAck = parseAck(alice, Envelope.PayloadCase.FILE_UPLOAD_COMPLETE_ACK).fileUploadCompleteAck
        assertEquals(FileUploadCompleteAck.Result.OK, completeAck.result)
        assertEquals(payload.size.toLong(), completeAck.totalBytes)

        val notice = parseAck(bob, Envelope.PayloadCase.FILE_AVAILABLE).fileAvailable
        assertEquals("alice", notice.fromUserId)
        assertEquals("note.txt", notice.filename)
        assertEquals(payload.size.toLong(), notice.totalSize)
        assertEquals(uploadId, notice.uploadId)

        val storedPath = storageRoot.resolve("$uploadId.bin")
        assertTrue(Files.exists(storedPath))
        assertEquals(payload.size.toLong(), Files.size(storedPath))
        assertEquals("hello file", Files.readString(storedPath))
    }

    @Test
    fun `start rejects oversized file`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        handler.handleStart(
            alice, sequence = 1L,
            req = FileUploadStart.newBuilder()
                .setClientReqId("req-big")
                .setToUserId("bob")
                .setFilename("big.bin")
                .setTotalSize(64L * 1024 * 1024)
                .build(),
        )
        val ack = parseAck(alice, Envelope.PayloadCase.FILE_UPLOAD_START_ACK).fileUploadStartAck
        assertEquals(FileUploadStartAck.Result.FILE_TOO_LARGE, ack.result)
        assertEquals("", ack.uploadId)
    }

    @Test
    fun `complete with size mismatch returns SIZE_MISMATCH`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        handler.handleStart(
            alice, sequence = 1L,
            req = FileUploadStart.newBuilder()
                .setClientReqId("req-mm")
                .setToUserId("bob")
                .setFilename("partial.txt")
                .setTotalSize(100L)
                .build(),
        )
        val uploadId = parseAck(alice, Envelope.PayloadCase.FILE_UPLOAD_START_ACK).fileUploadStartAck.uploadId

        handler.handleChunk(
            alice, sequence = 2L,
            req = FileUploadChunk.newBuilder()
                .setUploadId(uploadId)
                .setSeq(0)
                .setData(ByteString.copyFromUtf8("only-10-by"))
                .build(),
        )
        handler.handleComplete(
            alice, sequence = 3L,
            req = FileUploadComplete.newBuilder()
                .setUploadId(uploadId)
                .setClientReqId("req-mm")
                .build(),
        )
        val ack = parseAck(alice, Envelope.PayloadCase.FILE_UPLOAD_COMPLETE_ACK).fileUploadCompleteAck
        assertEquals(FileUploadCompleteAck.Result.SIZE_MISMATCH, ack.result)
    }

    @Test
    fun `download happy path returns chunks matching original bytes`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        val bob = FakeClientSession().apply { userId = "bob" }
        sessions.bind("alice", alice)
        sessions.bind("bob", bob)

        val payload = ByteArray(200_000) { i -> (i % 251).toByte() }
        val uploadId = performUpload(alice, "bob", "blob.bin", payload)

        // bob (the recipient) requests download
        bob.sentFrames.clear()
        handler.handleDownloadRequest(
            bob, sequence = 42L,
            req = FileDownloadRequest.newBuilder()
                .setUploadId(uploadId)
                .setClientReqId("dl-1")
                .build(),
        )

        val startFrame = parseAck(bob, Envelope.PayloadCase.FILE_DOWNLOAD_START).fileDownloadStart
        assertEquals(FileDownloadStart.Result.OK, startFrame.result)
        assertEquals("blob.bin", startFrame.filename)
        assertEquals(payload.size.toLong(), startFrame.totalSize)
        assertTrue(startFrame.totalChunks >= 1)

        // Collect chunks in order
        val chunks = bob.sentFrames
            .filterIsInstance<Frame.Binary>()
            .map { Envelope.parseFrom(it.readBytes()) }
            .filter { it.payloadCase == Envelope.PayloadCase.FILE_DOWNLOAD_CHUNK }
            .map { it.fileDownloadChunk }

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.last().isLast)
        val reassembled = chunks.fold(ByteArray(0)) { acc, chunk ->
            acc + chunk.data.toByteArray()
        }
        assertEquals(payload.size, reassembled.size)
        assertTrue(payload.contentEquals(reassembled), "reassembled bytes must match original payload")
    }

    @Test
    fun `download rejects unknown upload_id with NOT_FOUND`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        handler.handleDownloadRequest(
            alice, sequence = 1L,
            req = FileDownloadRequest.newBuilder()
                .setUploadId(java.util.UUID.randomUUID().toString())
                .setClientReqId("dl-ghost")
                .build(),
        )
        val start = parseAck(alice, Envelope.PayloadCase.FILE_DOWNLOAD_START).fileDownloadStart
        assertEquals(FileDownloadStart.Result.NOT_FOUND, start.result)
    }

    @Test
    fun `download rejects third party user with NOT_ALLOWED`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        val eve = FakeClientSession().apply { userId = "carol" }
        val payload = "hidden".toByteArray()
        val uploadId = performUpload(alice, "bob", "secret.txt", payload)

        val userRepo = UserRepository(PostgresTestContainer.database())
        userRepo.createIfAbsent("carol", "Carol")

        handler.handleDownloadRequest(
            eve, sequence = 9L,
            req = FileDownloadRequest.newBuilder()
                .setUploadId(uploadId)
                .setClientReqId("dl-eve")
                .build(),
        )
        val start = parseAck(eve, Envelope.PayloadCase.FILE_DOWNLOAD_START).fileDownloadStart
        assertEquals(FileDownloadStart.Result.NOT_ALLOWED, start.result)
    }

    @Test
    fun `start rejects blank recipient`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        handler.handleStart(
            alice, sequence = 1L,
            req = FileUploadStart.newBuilder()
                .setClientReqId("req-no-target")
                .setToUserId("")
                .setFilename("x.txt")
                .setTotalSize(10L)
                .build(),
        )
        val ack = parseAck(alice, Envelope.PayloadCase.FILE_UPLOAD_START_ACK).fileUploadStartAck
        assertEquals(FileUploadStartAck.Result.INVALID_TARGET, ack.result)
    }

    private suspend fun startRoomUpload(sender: FakeClientSession, roomId: String, payload: ByteArray): FileUploadStartAck {
        handler.handleStart(
            sender, sequence = 1L,
            req = FileUploadStart.newBuilder()
                .setClientReqId("room-up")
                .setRoomId(roomId)
                .setFilename("room.txt")
                .setTotalSize(payload.size.toLong())
                .build(),
        )
        return parseAck(sender, Envelope.PayloadCase.FILE_UPLOAD_START_ACK).fileUploadStartAck
    }

    private suspend fun download(user: FakeClientSession, uploadId: String): FileDownloadStart.Result {
        user.sentFrames.clear()
        handler.handleDownloadRequest(
            user, sequence = 9L,
            req = FileDownloadRequest.newBuilder().setUploadId(uploadId).setClientReqId("dl").build(),
        )
        return parseAck(user, Envelope.PayloadCase.FILE_DOWNLOAD_START).fileDownloadStart.result
    }

    @Test
    fun `group room upload notifies every other member and members can download`() = runBlocking {
        val userRepo = UserRepository(PostgresTestContainer.database())
        userRepo.createIfAbsent("dave", "Dave")
        val roomId = roomRepo.createRoom("g", "alice", java.time.Instant.now(), listOf("bob", "carol"))
        val alice = FakeClientSession().apply { userId = "alice" }
        val bob = FakeClientSession().apply { userId = "bob" }
        val carol = FakeClientSession().apply { userId = "carol" }
        val dave = FakeClientSession().apply { userId = "dave" }
        sessions.bind("alice", alice); sessions.bind("bob", bob); sessions.bind("carol", carol); sessions.bind("dave", dave)

        val payload = "group file".toByteArray()
        val ack = startRoomUpload(alice, roomId.toString(), payload)
        assertEquals(FileUploadStartAck.Result.OK, ack.result)
        handler.handleChunk(alice, 2L, FileUploadChunk.newBuilder().setUploadId(ack.uploadId).setSeq(0).setData(ByteString.copyFrom(payload)).build())
        handler.handleComplete(alice, 3L, FileUploadComplete.newBuilder().setUploadId(ack.uploadId).setClientReqId("room-up").build())

        for (m in listOf(bob, carol)) {
            val n = parseAck(m, Envelope.PayloadCase.FILE_AVAILABLE).fileAvailable
            assertEquals(roomId.toString(), n.roomId)
            assertEquals("alice", n.fromUserId)
            assertEquals(ack.uploadId, n.uploadId)
            // 대화 기록 메시지도 같은 ID 로 방송된다(파일 알림 뒤에).
            val r = parseAck(m, Envelope.PayloadCase.TALK_ROOM_RECEIVED).talkRoomReceived
            assertEquals(ack.uploadId, r.serverTalkId)
            assertEquals(ack.uploadId, r.attachmentUploadId)
            assertEquals("📎 room.txt (10 B)", r.text)
        }
        // 히스토리에도 남는다.
        val history = roomRepo.fetchRoomHistory(roomId, 0, 10)
        assertEquals(1, history.size)
        assertEquals(ack.uploadId, history[0].attachmentUploadId)
        assertEquals(ack.uploadId, history[0].serverTalkId.toString())
        assertEquals("alice", history[0].fromUserId)
        assertTrue(alice.sentFrames.filterIsInstance<Frame.Binary>().none { Envelope.parseFrom(it.readBytes()).payloadCase == Envelope.PayloadCase.FILE_AVAILABLE })
        assertTrue(dave.sentFrames.isEmpty())

        assertEquals(FileDownloadStart.Result.OK, download(bob, ack.uploadId))
        assertEquals(FileDownloadStart.Result.OK, download(carol, ack.uploadId))
        assertEquals(FileDownloadStart.Result.NOT_ALLOWED, download(dave, ack.uploadId))
    }

    @Test
    fun `room file message text formats size`() {
        assertEquals("📎 a.txt (512 B)", FileHandler.roomFileMessageText("a.txt", 512))
        assertEquals("📎 a.txt (1.5 KB)", FileHandler.roomFileMessageText("a.txt", 1536))
        assertEquals("📎 a.txt (2.0 MB)", FileHandler.roomFileMessageText("a.txt", 2L * 1024 * 1024))
    }

    @Test
    fun `room upload by non member or unknown room is rejected`() = runBlocking {
        val userRepo = UserRepository(PostgresTestContainer.database())
        userRepo.createIfAbsent("dave", "Dave")
        val roomId = roomRepo.createRoom("g", "alice", java.time.Instant.now(), listOf("bob"))
        val dave = FakeClientSession().apply { userId = "dave" }
        assertEquals(FileUploadStartAck.Result.INVALID_TARGET, startRoomUpload(dave, roomId.toString(), "x".toByteArray()).result)
        dave.sentFrames.clear()
        assertEquals(FileUploadStartAck.Result.INVALID_TARGET, startRoomUpload(dave, java.util.UUID.randomUUID().toString(), "x".toByteArray()).result)
        dave.sentFrames.clear()
        assertEquals(FileUploadStartAck.Result.INVALID_TARGET, startRoomUpload(dave, "not-a-uuid", "x".toByteArray()).result)
    }

    @Test
    fun `memo attachment can be downloaded by every recipient but not outsiders`() = runBlocking {
        val alice = FakeClientSession().apply { userId = "alice" }
        val bob = FakeClientSession().apply { userId = "bob" }
        val carol = FakeClientSession().apply { userId = "carol" }
        sessions.bind("bob", bob)
        // 쪽지 첨부용 보관 업로드: 자기 자신 지정 -> 알림 없음
        val uploadId = performUpload(alice, "alice", "memo.txt", "memo".toByteArray())
        assertTrue(bob.sentFrames.isEmpty())
        assertEquals(FileDownloadStart.Result.NOT_ALLOWED, download(bob, uploadId))

        val msgRepo = com.osori.server.db.MessageRepository(PostgresTestContainer.database())
        msgRepo.saveMemo("m1", "alice", listOf("bob", "carol"), "t", "b", java.time.Instant.now(), uploadId)
        assertEquals(FileDownloadStart.Result.OK, download(bob, uploadId))
        assertEquals(FileDownloadStart.Result.OK, download(carol, uploadId))
        val userRepo = UserRepository(PostgresTestContainer.database())
        userRepo.createIfAbsent("dave", "Dave")
        val dave = FakeClientSession().apply { userId = "dave" }
        assertEquals(FileDownloadStart.Result.NOT_ALLOWED, download(dave, uploadId))
    }

    private fun parseAck(session: FakeClientSession, expected: Envelope.PayloadCase): Envelope {
        return session.sentFrames
            .filterIsInstance<Frame.Binary>()
            .map { Envelope.parseFrom(it.readBytes()) }
            .first { it.payloadCase == expected }
    }

    private suspend fun performUpload(sender: FakeClientSession, toUserId: String, filename: String, payload: ByteArray): String {
        handler.handleStart(
            sender, sequence = 100L,
            req = FileUploadStart.newBuilder()
                .setClientReqId("upload-$filename")
                .setToUserId(toUserId)
                .setFilename(filename)
                .setTotalSize(payload.size.toLong())
                .setMimeType("application/octet-stream")
                .build(),
        )
        val startAck = parseAck(sender, Envelope.PayloadCase.FILE_UPLOAD_START_ACK).fileUploadStartAck
        assertEquals(FileUploadStartAck.Result.OK, startAck.result)
        val uploadId = startAck.uploadId

        // Split into 64KB chunks to exercise multi-chunk path
        val chunkSize = 64 * 1024
        var offset = 0
        var seq = 0
        while (offset < payload.size) {
            val size = minOf(chunkSize, payload.size - offset)
            handler.handleChunk(
                sender, sequence = 101L + seq,
                req = FileUploadChunk.newBuilder()
                    .setUploadId(uploadId)
                    .setSeq(seq)
                    .setData(com.google.protobuf.ByteString.copyFrom(payload, offset, size))
                    .build(),
            )
            offset += size
            seq++
        }

        handler.handleComplete(
            sender, sequence = 200L,
            req = FileUploadComplete.newBuilder()
                .setUploadId(uploadId)
                .setClientReqId("upload-$filename")
                .build(),
        )
        val complete = parseAck(sender, Envelope.PayloadCase.FILE_UPLOAD_COMPLETE_ACK).fileUploadCompleteAck
        assertEquals(FileUploadCompleteAck.Result.OK, complete.result)
        sender.sentFrames.clear()
        return uploadId
    }
}

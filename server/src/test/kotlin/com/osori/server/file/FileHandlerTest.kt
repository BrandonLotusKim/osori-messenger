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
    private lateinit var handler: FileHandler

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        val db = PostgresTestContainer.database()
        val userRepo = UserRepository(db)
        userRepo.createIfAbsent("alice", "Alice")
        userRepo.createIfAbsent("bob", "Bob")
        fileRepo = FileRepository(db)
        sessions = SessionManager()
        storageRoot = Files.createTempDirectory("osori-file-handler-test-")
        handler = FileHandler(sessions, fileRepo, storageRoot)
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

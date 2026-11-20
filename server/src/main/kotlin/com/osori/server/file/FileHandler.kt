package com.osori.server.file

import com.google.protobuf.ByteString
import com.osori.proto.v1.Envelope
import com.osori.proto.v1.FileAvailableNotification
import com.osori.proto.v1.FileDownloadChunk
import com.osori.proto.v1.FileDownloadRequest
import com.osori.proto.v1.FileDownloadStart
import com.osori.proto.v1.FileUploadChunk
import com.osori.proto.v1.FileUploadComplete
import com.osori.proto.v1.FileUploadCompleteAck
import com.osori.proto.v1.FileUploadStart
import com.osori.proto.v1.FileUploadStartAck
import com.osori.server.db.FileRepository
import com.osori.server.session.ClientSession
import com.osori.server.session.SessionManager
import io.ktor.websocket.Frame
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * File upload handler for Phase 6.
 *
 * Maintains in-memory open upload sessions (uploadId -> state) that track the disk path
 * and running byte count. On FileUploadComplete the file is finalized and the recipient
 * (if online) receives a FileAvailableNotification.
 *
 * If fileRepository is null the handler runs in memory-only mode (no DB row, still writes
 * chunks to disk so single-process development mode works without Postgres).
 */
class FileHandler(
    private val sessions: SessionManager,
    private val fileRepository: FileRepository?,
    private val storageRoot: Path,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxTotalBytes: Long = 32L * 1024 * 1024,
    private val maxChunkBytes: Int = 1 * 1024 * 1024,
    private val auditRepository: com.osori.server.audit.AuditRepository? = null, //
    private val pluginRegistry: com.osori.server.plugin.PluginRegistry? = null, //
) {
    private val logger = LoggerFactory.getLogger(FileHandler::class.java)
    private val serverSequence = AtomicLong(3_000_000)

    private data class UploadState(
        val uploadId: UUID,
        val fromUserId: String,
        val toUserId: String,
        val filename: String,
        val mimeType: String,
        val totalSize: Long,
        val storagePath: Path,
        @Volatile var bytesWritten: Long = 0L,
    )

    private val openUploads = ConcurrentHashMap<String, UploadState>()

    init {
        runCatching { Files.createDirectories(storageRoot) }
            .onFailure { t -> logger.warn("failed to ensure storage root {}", storageRoot, t) }
    }

    suspend fun handleStart(sender: ClientSession, sequence: Long, req: FileUploadStart) {
        val senderUserId = sender.userId
        if (senderUserId == null) {
            respond(sender, sequence, failStartAck(req.clientReqId, FileUploadStartAck.Result.SERVER_ERROR))
            return
        }
        if (req.toUserId.isBlank()) {
            respond(sender, sequence, failStartAck(req.clientReqId, FileUploadStartAck.Result.INVALID_TARGET))
            return
        }
        if (req.totalSize <= 0 || req.totalSize > maxTotalBytes) {
            respond(sender, sequence, failStartAck(req.clientReqId, FileUploadStartAck.Result.FILE_TOO_LARGE))
            return
        }

        val uploadId = UUID.randomUUID()
        val storagePath = storageRoot.resolve("$uploadId.bin")
        runCatching {
            Files.deleteIfExists(storagePath)
            Files.createFile(storagePath)
        }.onFailure { t ->
            logger.error("failed to create storage file {}", storagePath, t)
            respond(sender, sequence, failStartAck(req.clientReqId, FileUploadStartAck.Result.SERVER_ERROR))
            return
        }

        val repo = fileRepository
        if (repo != null) {
            val inserted = runCatching {
                repo.createUpload(
                    uploadId = uploadId,
                    fromUserId = senderUserId,
                    toUserId = req.toUserId,
                    filename = req.filename,
                    mimeType = req.mimeType,
                    totalSize = req.totalSize,
                    storagePath = storagePath.toString(),
                )
            }.onFailure { t ->
                logger.error("FileRepository.createUpload failed", t)
            }.getOrNull()
            if (inserted == null) {
                runCatching { Files.deleteIfExists(storagePath) }
                respond(sender, sequence, failStartAck(req.clientReqId, FileUploadStartAck.Result.SERVER_ERROR))
                return
            }
        }

        openUploads[uploadId.toString()] = UploadState(
            uploadId = uploadId,
            fromUserId = senderUserId,
            toUserId = req.toUserId,
            filename = req.filename,
            mimeType = req.mimeType,
            totalSize = req.totalSize,
            storagePath = storagePath,
        )

        val ack = FileUploadStartAck.newBuilder()
            .setClientReqId(req.clientReqId)
            .setUploadId(uploadId.toString())
            .setServerTs(nowMillis())
            .setResult(FileUploadStartAck.Result.OK)
            .build()
        respond(sender, sequence, ack)
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun handleChunk(sender: ClientSession, sequence: Long, req: FileUploadChunk) {
        val state = openUploads[req.uploadId]
        if (state == null) {
            logger.warn("chunk for unknown upload_id={}", req.uploadId)
            return
        }
        if (sender.userId != state.fromUserId) {
            logger.warn("chunk from unauthorized user={} upload={}", sender.userId, req.uploadId)
            return
        }
        val bytes = req.data.toByteArray()
        if (bytes.size > maxChunkBytes) {
            logger.warn("chunk too large size={} upload={}", bytes.size, req.uploadId)
            return
        }
        if (state.bytesWritten + bytes.size > state.totalSize) {
            logger.warn(
                "chunk would exceed totalSize written={} chunk={} total={} upload={}",
                state.bytesWritten, bytes.size, state.totalSize, req.uploadId,
            )
            return
        }

        runCatching {
            Files.write(state.storagePath, bytes, StandardOpenOption.APPEND)
            state.bytesWritten += bytes.size
            fileRepository?.recordChunkBytes(state.uploadId, bytes.size.toLong())
        }.onFailure { t ->
            logger.error("chunk write failed upload={}", req.uploadId, t)
        }
    }

    suspend fun handleComplete(sender: ClientSession, sequence: Long, req: FileUploadComplete) {
        val state = openUploads.remove(req.uploadId)
        if (state == null) {
            respond(sender, sequence, failCompleteAck(req.clientReqId, req.uploadId, 0, FileUploadCompleteAck.Result.NOT_FOUND))
            return
        }
        if (sender.userId != state.fromUserId) {
            openUploads[req.uploadId] = state
            respond(sender, sequence, failCompleteAck(req.clientReqId, req.uploadId, state.bytesWritten, FileUploadCompleteAck.Result.SERVER_ERROR))
            return
        }
        if (state.bytesWritten != state.totalSize) {
            respond(sender, sequence, failCompleteAck(req.clientReqId, req.uploadId, state.bytesWritten, FileUploadCompleteAck.Result.SIZE_MISMATCH))
            fileRepository?.markAborted(state.uploadId)
            return
        }

        fileRepository?.markComplete(state.uploadId)
        auditRepository?.recordFileUploaded(
            fromUserId = state.fromUserId,
            uploadId = state.uploadId.toString(),
            filename = state.filename,
            totalBytes = state.totalSize,
        )
        pluginRegistry?.fireFileUploaded(
            com.osori.server.plugin.FileUploadedEvent(
                uploaderUserId = state.fromUserId,
                uploadId = state.uploadId.toString(),
                filename = state.filename,
                totalBytes = state.totalSize,
                timestamp = java.time.Instant.ofEpochMilli(nowMillis()),
            ),
        )

        val ack = FileUploadCompleteAck.newBuilder()
            .setClientReqId(req.clientReqId)
            .setUploadId(state.uploadId.toString())
            .setTotalBytes(state.bytesWritten)
            .setServerTs(nowMillis())
            .setResult(FileUploadCompleteAck.Result.OK)
            .build()
        respond(sender, sequence, ack)
        com.osori.server.metrics.Metrics.filesUploaded.incrementAndGet()

        notifyRecipient(state)
    }

    suspend fun handleDownloadRequest(sender: ClientSession, sequence: Long, req: FileDownloadRequest) {
        val senderUserId = sender.userId
        if (senderUserId == null) {
            respondStart(sender, sequence, failDownloadStart(req.clientReqId, req.uploadId, FileDownloadStart.Result.SERVER_ERROR))
            return
        }
        val repo = fileRepository
        if (repo == null) {
            respondStart(sender, sequence, failDownloadStart(req.clientReqId, req.uploadId, FileDownloadStart.Result.SERVER_ERROR))
            return
        }
        val uploadUuid = runCatching { UUID.fromString(req.uploadId) }.getOrNull()
        if (uploadUuid == null) {
            respondStart(sender, sequence, failDownloadStart(req.clientReqId, req.uploadId, FileDownloadStart.Result.NOT_FOUND))
            return
        }
        val row = repo.getUpload(uploadUuid)
        if (row == null) {
            respondStart(sender, sequence, failDownloadStart(req.clientReqId, req.uploadId, FileDownloadStart.Result.NOT_FOUND))
            return
        }
        if (senderUserId != row.fromUserId && senderUserId != row.toUserId) {
            respondStart(sender, sequence, failDownloadStart(req.clientReqId, req.uploadId, FileDownloadStart.Result.NOT_ALLOWED))
            return
        }
        if (row.status != "complete") {
            respondStart(sender, sequence, failDownloadStart(req.clientReqId, req.uploadId, FileDownloadStart.Result.NOT_COMPLETE))
            return
        }

        val storagePath = Path.of(row.storagePath)
        val bytes = runCatching { Files.readAllBytes(storagePath) }.getOrElse { t ->
            logger.error("download read failed upload={}", req.uploadId, t)
            respondStart(sender, sequence, failDownloadStart(req.clientReqId, req.uploadId, FileDownloadStart.Result.SERVER_ERROR))
            return
        }

        val chunkSize = maxChunkBytes
        val totalChunks = if (bytes.isEmpty()) 1 else ((bytes.size + chunkSize - 1) / chunkSize)
        val startEnvelope = FileDownloadStart.newBuilder()
            .setClientReqId(req.clientReqId)
            .setUploadId(row.uploadId.toString())
            .setFilename(row.filename)
            .setMimeType(row.mimeType)
            .setTotalSize(row.totalSize)
            .setTotalChunks(totalChunks)
            .setServerTs(nowMillis())
            .setResult(FileDownloadStart.Result.OK)
            .build()
        respondStart(sender, sequence, startEnvelope)
        com.osori.server.metrics.Metrics.filesDownloaded.incrementAndGet()
        auditRepository?.recordFileDownloaded(senderUserId, row.uploadId.toString())
        pluginRegistry?.fireFileDownloaded(
            com.osori.server.plugin.FileDownloadedEvent(
                downloaderUserId = senderUserId,
                uploadId = row.uploadId.toString(),
                timestamp = java.time.Instant.ofEpochMilli(nowMillis()),
            ),
        )

        if (bytes.isEmpty()) {
            val finalChunk = FileDownloadChunk.newBuilder()
                .setUploadId(row.uploadId.toString())
                .setSeq(0)
                .setData(ByteString.EMPTY)
                .setIsLast(true)
                .build()
            pushChunk(sender, finalChunk)
            return
        }

        var seq = 0
        var offset = 0
        while (offset < bytes.size) {
            val size = minOf(chunkSize, bytes.size - offset)
            val isLast = offset + size >= bytes.size
            val chunk = FileDownloadChunk.newBuilder()
                .setUploadId(row.uploadId.toString())
                .setSeq(seq)
                .setData(ByteString.copyFrom(bytes, offset, size))
                .setIsLast(isLast)
                .build()
            pushChunk(sender, chunk)
            seq++
            offset += size
        }
    }

    private suspend fun pushChunk(sender: ClientSession, chunk: FileDownloadChunk) {
        val envelope = Envelope.newBuilder()
            .setSequence(serverSequence.incrementAndGet())
            .setFileDownloadChunk(chunk)
            .build()
        runCatching { sender.send(Frame.Binary(true, envelope.toByteArray())) }
            .onFailure { t -> logger.warn("download chunk send failed upload={}", chunk.uploadId, t) }
    }

    private suspend fun respondStart(sender: ClientSession, sequence: Long, ack: FileDownloadStart) {
        val envelope = Envelope.newBuilder()
            .setSequence(sequence)
            .setFileDownloadStart(ack)
            .build()
        sender.send(Frame.Binary(true, envelope.toByteArray()))
    }

    private fun failDownloadStart(clientReqId: String, uploadId: String, result: FileDownloadStart.Result): FileDownloadStart =
        FileDownloadStart.newBuilder()
            .setClientReqId(clientReqId)
            .setUploadId(uploadId)
            .setServerTs(nowMillis())
            .setResult(result)
            .build()

    private suspend fun notifyRecipient(state: UploadState) {
        val recipient = sessions.get(state.toUserId) ?: return
        val notice = FileAvailableNotification.newBuilder()
            .setUploadId(state.uploadId.toString())
            .setFromUserId(state.fromUserId)
            .setFilename(state.filename)
            .setTotalSize(state.totalSize)
            .setMimeType(state.mimeType)
            .setServerTs(nowMillis())
            .build()
        val envelope = Envelope.newBuilder()
            .setSequence(serverSequence.incrementAndGet())
            .setFileAvailable(notice)
            .build()
        runCatching { recipient.send(Frame.Binary(true, envelope.toByteArray())) }
            .onFailure { t -> logger.warn("file notify to {} failed", state.toUserId, t) }
    }

    private suspend fun respond(sender: ClientSession, sequence: Long, ack: FileUploadStartAck) {
        val envelope = Envelope.newBuilder()
            .setSequence(sequence)
            .setFileUploadStartAck(ack)
            .build()
        sender.send(Frame.Binary(true, envelope.toByteArray()))
    }

    private suspend fun respond(sender: ClientSession, sequence: Long, ack: FileUploadCompleteAck) {
        val envelope = Envelope.newBuilder()
            .setSequence(sequence)
            .setFileUploadCompleteAck(ack)
            .build()
        sender.send(Frame.Binary(true, envelope.toByteArray()))
    }

    private fun failStartAck(clientReqId: String, result: FileUploadStartAck.Result): FileUploadStartAck =
        FileUploadStartAck.newBuilder()
            .setClientReqId(clientReqId)
            .setUploadId("")
            .setServerTs(nowMillis())
            .setResult(result)
            .build()

    private fun failCompleteAck(clientReqId: String, uploadId: String, totalBytes: Long, result: FileUploadCompleteAck.Result): FileUploadCompleteAck =
        FileUploadCompleteAck.newBuilder()
            .setClientReqId(clientReqId)
            .setUploadId(uploadId)
            .setTotalBytes(totalBytes)
            .setServerTs(nowMillis())
            .setResult(result)
            .build()
}

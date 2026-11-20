package com.osori.server.db

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * File upload repository for Phase 6.
 *
 * Tracks upload metadata in the file_uploads table. Actual bytes are written to local disk
 * by FileHandler (this repo only stores paths and status).
 */
class FileRepository(private val db: Database) {

    data class UploadRow(
        val uploadId: UUID,
        val fromUserId: String,
        val toUserId: String,
        val filename: String,
        val mimeType: String,
        val totalSize: Long,
        val bytesWritten: Long,
        val status: String,
        val storagePath: String,
    )

    fun createUpload(
        uploadId: UUID = UUID.randomUUID(),
        fromUserId: String,
        toUserId: String,
        filename: String,
        mimeType: String,
        totalSize: Long,
        storagePath: String,
    ): UUID {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO file_uploads
                    (upload_id, from_user_id, to_user_id, filename, mime_type,
                     total_size, bytes_written, status, storage_path, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, 'open', ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, uploadId)
                ps.setString(2, fromUserId)
                ps.setString(3, toUserId)
                ps.setString(4, filename)
                ps.setString(5, mimeType)
                ps.setLong(6, totalSize)
                ps.setString(7, storagePath)
                ps.setTimestamp(8, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
        return uploadId
    }

    fun getUpload(uploadId: UUID): UploadRow? {
        return db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT upload_id, from_user_id, to_user_id, filename, mime_type,
                       total_size, bytes_written, status, storage_path
                FROM file_uploads
                WHERE upload_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, uploadId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@withConnection null
                    UploadRow(
                        uploadId = rs.getObject("upload_id", UUID::class.java),
                        fromUserId = rs.getString("from_user_id"),
                        toUserId = rs.getString("to_user_id"),
                        filename = rs.getString("filename"),
                        mimeType = rs.getString("mime_type"),
                        totalSize = rs.getLong("total_size"),
                        bytesWritten = rs.getLong("bytes_written"),
                        status = rs.getString("status"),
                        storagePath = rs.getString("storage_path"),
                    )
                }
            }
        }
    }

    fun recordChunkBytes(uploadId: UUID, deltaBytes: Long) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE file_uploads SET bytes_written = bytes_written + ? WHERE upload_id = ? AND status = 'open'",
            ).use { ps ->
                ps.setLong(1, deltaBytes)
                ps.setObject(2, uploadId)
                ps.executeUpdate()
            }
        }
    }

    fun markComplete(uploadId: UUID): Boolean {
        return db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE file_uploads SET status = 'complete', completed_at = ? WHERE upload_id = ? AND status = 'open'",
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(Instant.now()))
                ps.setObject(2, uploadId)
                ps.executeUpdate() > 0
            }
        }
    }

    fun markAborted(uploadId: UUID) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE file_uploads SET status = 'aborted', completed_at = ? WHERE upload_id = ?",
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(Instant.now()))
                ps.setObject(2, uploadId)
                ps.executeUpdate()
            }
        }
    }

    fun countCompleteForRecipient(toUserId: String): Long {
        return db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM file_uploads WHERE to_user_id = ? AND status = 'complete'",
            ).use { ps ->
                ps.setString(1, toUserId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
    }
}

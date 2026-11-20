package com.osori.server.db

import com.osori.server.testutil.PostgresTestContainer
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileRepositoryIntegrationTest {

    private lateinit var userRepo: UserRepository
    private lateinit var repo: FileRepository

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        val db = PostgresTestContainer.database()
        userRepo = UserRepository(db)
        repo = FileRepository(db)
        userRepo.createIfAbsent("alice", "Alice")
        userRepo.createIfAbsent("bob", "Bob")
    }

    @Test
    fun `createUpload returns id and row has open status`() {
        val uploadId = repo.createUpload(
            fromUserId = "alice",
            toUserId = "bob",
            filename = "report.txt",
            mimeType = "text/plain",
            totalSize = 1024L,
            storagePath = "/tmp/ignored.bin",
        )
        val row = repo.getUpload(uploadId)
        assertNotNull(row)
        assertEquals("alice", row!!.fromUserId)
        assertEquals("bob", row.toUserId)
        assertEquals("report.txt", row.filename)
        assertEquals(1024L, row.totalSize)
        assertEquals(0L, row.bytesWritten)
        assertEquals("open", row.status)
    }

    @Test
    fun `recordChunkBytes accumulates bytesWritten`() {
        val uploadId = repo.createUpload(fromUserId = "alice", toUserId = "bob", filename = "img.bin", mimeType = "application/octet-stream", totalSize = 500L, storagePath = "/tmp/ignored.bin")
        repo.recordChunkBytes(uploadId, 200L)
        repo.recordChunkBytes(uploadId, 150L)
        val row = repo.getUpload(uploadId)
        assertEquals(350L, row!!.bytesWritten)
    }

    @Test
    fun `markComplete flips status to complete`() {
        val uploadId = repo.createUpload(fromUserId = "alice", toUserId = "bob", filename = "doc.pdf", mimeType = "application/pdf", totalSize = 100L, storagePath = "/tmp/ignored.bin")
        assertTrue(repo.markComplete(uploadId))
        val row = repo.getUpload(uploadId)
        assertEquals("complete", row!!.status)
    }

    @Test
    fun `countCompleteForRecipient reflects complete rows only`() {
        val u1 = repo.createUpload(fromUserId = "alice", toUserId = "bob", filename = "a.txt", mimeType = "text/plain", totalSize = 10L, storagePath = "/tmp/a")
        val u2 = repo.createUpload(fromUserId = "alice", toUserId = "bob", filename = "b.txt", mimeType = "text/plain", totalSize = 20L, storagePath = "/tmp/b")
        repo.markComplete(u1)
        // u2 left open
        assertEquals(1L, repo.countCompleteForRecipient("bob"))
        repo.markComplete(u2)
        assertEquals(2L, repo.countCompleteForRecipient("bob"))
    }
}

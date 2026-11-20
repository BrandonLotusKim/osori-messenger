package com.osori.server.db

import com.osori.server.testutil.PostgresTestContainer
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserRepositoryIntegrationTest {

    private lateinit var repo: UserRepository

    @Before
    fun setUp() {
        PostgresTestContainer.truncateAll()
        repo = UserRepository(PostgresTestContainer.database())
    }

    @Test
    fun `findById 존재하지 않으면 null 반환`() {
        assertNull(repo.findById("nobody"))
    }

    @Test
    fun `createIfAbsent 신규사용자면 true 반환`() {
        val created = repo.createIfAbsent("user-1", "사용자1")

        assertTrue(created)
        assertEquals(1L, repo.count())

        val user = repo.findById("user-1")
        assertNotNull(user)
        assertEquals("user-1", user.userId)
        assertEquals("사용자1", user.displayName)
    }

    @Test
    fun `createIfAbsent 중복이면 false 반환`() {
        assertTrue(repo.createIfAbsent("user-1", "사용자1"))

        val createdAgain = repo.createIfAbsent("user-1", "다른이름")

        assertFalse(createdAgain)
        assertEquals(1L, repo.count())
    }

    @Test
    fun `ensureExists 빈 DB 이면 자동 생성 후 반환`() {
        val user = repo.ensureExists("user-2")

        assertEquals("user-2", user.userId)
        assertEquals("user-2", user.displayName)
        assertEquals(1L, repo.count())
    }

    @Test
    fun `ensureExists 이미 있으면 중복 생성 없음`() {
        val first = repo.ensureExists("user-3")
        val second = repo.ensureExists("user-3")

        assertEquals("user-3", first.userId)
        assertEquals("user-3", second.userId)
        assertEquals(1L, repo.count())
    }
}
package com.osori.server.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllowedAccountsTest {
    @Test
    fun `설정이 없으면 기본 목록만 허용`() {
        assertTrue(AllowedAccounts.isAllowed("test1", null))
        assertTrue(AllowedAccounts.isAllowed("admin", "  "))
        assertFalse(AllowedAccounts.isAllowed("alice", null))
    }

    @Test
    fun `별표면 모든 계정 허용`() {
        assertTrue(AllowedAccounts.isAllowed("alice", "*"))
    }

    @Test
    fun `쉼표 목록이면 그 계정만 허용`() {
        assertTrue(AllowedAccounts.isAllowed("bob", "alice, bob"))
        assertFalse(AllowedAccounts.isAllowed("test1", "alice,bob"))
    }
}

package com.osori.server.push

import com.osori.server.db.Database
import com.osori.server.db.PushTokenRepository
import com.osori.server.plugin.MessageSentEvent
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PushDeliveryPlugin 유닛 테스트.
 *
 * 핵심 검증:
 *  - queued=false (온라인) 이면 push 스킵
 *  - queued=true + 등록 토큰 없음 → push 스킵
 *  - 다중 토큰 fan-out
 *  - InvalidToken → deleteByToken 호출
 *  - TransientFailure → 삭제 없음 (재시도 여지)
 *  - Ok → touch 호출 (last_seen_at 갱신)
 *  - notifier 예외 → TransientFailure 로 간주
 *  - tokenRepo 조회 예외 → 플러그인은 안 터지고 스킵
 */
class PushDeliveryPluginTest {

    /** 인메모리 페이크 repo — Database 타입 필요한 open class 우회로 override. */
    private class FakeRepo : PushTokenRepository(FAKE_DB) {
        val store = mutableMapOf<Pair<String, String>, PushTokenRepository.PushToken>()
        val touches = mutableListOf<Pair<String, String>>()
        val deletesByToken = mutableListOf<String>()
        var throwOnList: Boolean = false

        override fun listByUser(userId: String): List<PushToken> {
            if (throwOnList) error("DB down")
            return store.values.filter { it.userId == userId }.sortedByDescending { it.lastSeenAt }
        }
        override fun touch(userId: String, deviceId: String, now: Instant): Int {
            touches += userId to deviceId; return 1
        }
        override fun deleteByToken(token: String): Int {
            deletesByToken += token
            val keys = store.entries.filter { it.value.token == token }.map { it.key }
            keys.forEach { store.remove(it) }
            return keys.size
        }

        fun seed(userId: String, deviceId: String, platform: String, token: String) {
            val now = Instant.now()
            store[userId to deviceId] = PushToken(userId, deviceId, platform, token, now, now)
        }

        companion object {
            // PushTokenRepository 생성자가 Database 를 요구하므로 사용되지 않는 인스턴스를 넘김
            // (override 한 메서드만 호출되므로 실 DB 접근 없음)
            private val FAKE_DB: Database = Database(org.postgresql.ds.PGSimpleDataSource().apply {
                serverNames = arrayOf("127.0.0.1"); portNumbers = intArrayOf(1); user = "x"; password = "x"; databaseName = "x"
            })
        }
    }

    private class RecordingNotifier(val result: (PushNotificationRequest) -> PushResult = { PushResult.Ok }) : PushNotifier {
        val calls = mutableListOf<PushNotificationRequest>()
        override fun push(request: PushNotificationRequest): PushResult {
            calls += request
            return result(request)
        }
    }

    private fun event(queued: Boolean, to: String = "bob") = MessageSentEvent(
        fromUserId = "alice",
        toUserId = to,
        serverTalkId = "msg-1",
        bytes = 10,
        queued = queued,
        timestamp = Instant.now(),
    )

    @Test
    fun `online 메시지(queued=false)는 push 보내지 않는다`() {
        val repo = FakeRepo().apply { seed("bob", "d1", "fcm", "tok-1") }
        val notifier = RecordingNotifier()
        val plugin = PushDeliveryPlugin(repo, notifier)
        plugin.onMessageSent(event(queued = false))
        assertTrue(notifier.calls.isEmpty())
    }

    @Test
    fun `등록 토큰 없으면 조용히 스킵한다`() {
        val repo = FakeRepo()
        val notifier = RecordingNotifier()
        val plugin = PushDeliveryPlugin(repo, notifier)
        plugin.onMessageSent(event(queued = true))
        assertTrue(notifier.calls.isEmpty())
    }

    @Test
    fun `다중 디바이스 토큰에 fan-out 한다`() {
        val repo = FakeRepo().apply {
            seed("bob", "phone", "fcm", "tok-phone")
            seed("bob", "tablet", "fcm", "tok-tablet")
            seed("bob", "desktop", "fcm", "tok-desktop")
        }
        val notifier = RecordingNotifier()
        val plugin = PushDeliveryPlugin(repo, notifier)
        plugin.onMessageSent(event(queued = true))
        assertEquals(3, notifier.calls.size)
        val tokens = notifier.calls.map { it.token }.toSet()
        assertEquals(setOf("tok-phone", "tok-tablet", "tok-desktop"), tokens)
    }

    @Test
    fun `Ok 결과는 touch 를 호출한다`() {
        val repo = FakeRepo().apply { seed("bob", "d1", "fcm", "tok-1") }
        val plugin = PushDeliveryPlugin(repo, RecordingNotifier())
        plugin.onMessageSent(event(queued = true))
        assertEquals(listOf("bob" to "d1"), repo.touches)
    }

    @Test
    fun `InvalidToken 결과는 deleteByToken 을 호출한다`() {
        val repo = FakeRepo().apply {
            seed("bob", "phone", "fcm", "tok-good")
            seed("bob", "old", "fcm", "tok-stale")
        }
        val notifier = RecordingNotifier { req ->
            if (req.token == "tok-stale") PushResult.InvalidToken("UNREGISTERED") else PushResult.Ok
        }
        val plugin = PushDeliveryPlugin(repo, notifier)
        plugin.onMessageSent(event(queued = true))
        assertEquals(listOf("tok-stale"), repo.deletesByToken)
        assertEquals(listOf("bob" to "phone"), repo.touches, "Ok 토큰만 touch")
    }

    @Test
    fun `TransientFailure 는 토큰 유지 (재시도 여지)`() {
        val repo = FakeRepo().apply { seed("bob", "d1", "fcm", "tok-1") }
        val notifier = RecordingNotifier { PushResult.TransientFailure("503") }
        val plugin = PushDeliveryPlugin(repo, notifier)
        plugin.onMessageSent(event(queued = true))
        assertTrue(repo.deletesByToken.isEmpty(), "transient 는 삭제 안 함")
        assertTrue(repo.touches.isEmpty(), "transient 는 touch 안 함")
    }

    @Test
    fun `notifier 예외는 TransientFailure 로 취급해 플러그인 흐름 유지`() {
        val repo = FakeRepo().apply {
            seed("bob", "d1", "fcm", "tok-1")
            seed("bob", "d2", "fcm", "tok-2")
        }
        var callCount = 0
        val notifier = object : PushNotifier {
            override fun push(request: PushNotificationRequest): PushResult {
                callCount++
                if (request.token == "tok-1") throw RuntimeException("boom")
                return PushResult.Ok
            }
        }
        val plugin = PushDeliveryPlugin(repo, notifier)
        plugin.onMessageSent(event(queued = true))
        assertEquals(2, callCount, "첫 토큰 예외 후에도 두 번째 토큰까지 시도")
    }

    @Test
    fun `tokenRepo 조회 예외는 플러그인이 조용히 스킵`() {
        val repo = FakeRepo().apply { throwOnList = true }
        val notifier = RecordingNotifier()
        val plugin = PushDeliveryPlugin(repo, notifier)
        plugin.onMessageSent(event(queued = true)) // 예외 propagate 하면 이 줄이 실패함
        assertTrue(notifier.calls.isEmpty())
    }

    @Test
    fun `NoopPushNotifier 는 항상 Ok 를 반환한다`() {
        val n = NoopPushNotifier()
        val r = n.push(PushNotificationRequest("fcm", "tok", "t", "b"))
        assertEquals(PushResult.Ok, r)
    }
}

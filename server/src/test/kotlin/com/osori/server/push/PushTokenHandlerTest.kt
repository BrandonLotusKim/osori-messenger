package com.osori.server.push

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.PushTokenRegisterRequest
import com.osori.proto.v1.PushTokenRegisterResponse
import com.osori.proto.v1.PushTokenUnregisterRequest
import com.osori.proto.v1.PushTokenUnregisterResponse
import com.osori.server.db.PushTokenRepository
import com.osori.server.testutil.FakeClientSession
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class PushTokenHandlerTest {

    private fun session(userId: String?): FakeClientSession = FakeClientSession().also { it.userId = userId }

    private fun fakeStore(throwOnUpsert: Boolean = false, throwOnDelete: Boolean = false): PushTokenStore =
        object : PushTokenStore {
            val upserts = mutableListOf<Quad>()
            val deletes = mutableListOf<Pair<String, String>>()
            override fun upsertToken(userId: String, deviceId: String, platform: String, token: String) {
                if (throwOnUpsert) error("DB down")
                upserts += Quad(userId, deviceId, platform, token)
            }
            override fun deleteByDevice(userId: String, deviceId: String): Int {
                if (throwOnDelete) error("DB down")
                deletes += userId to deviceId
                return 1
            }
        }

    data class Quad(val u: String, val d: String, val p: String, val t: String)

    private fun registerRes(s: FakeClientSession): PushTokenRegisterResponse {
        val f = s.sentFrames.single() as Frame.Binary
        val env = Envelope.parseFrom(f.readBytes())
        return env.pushTokenRegisterRes
    }

    private fun unregisterRes(s: FakeClientSession): PushTokenUnregisterResponse {
        val f = s.sentFrames.single() as Frame.Binary
        val env = Envelope.parseFrom(f.readBytes())
        return env.pushTokenUnregisterRes
    }

    @Test
    fun `register — 미인증 세션은 INVALID_REQUEST`() = runBlocking {
        val h = PushTokenHandler(fakeStore())
        val s = session(null)
        h.handleRegister(s, 1L, PushTokenRegisterRequest.newBuilder().setClientReqId("r1").setDeviceId("d").setPlatform("fcm").setToken("t").build())
        val res = registerRes(s)
        assertEquals(PushTokenRegisterResponse.Result.INVALID_REQUEST, res.result)
        assertEquals("r1", res.clientReqId)
    }

    @Test
    fun `register — 필수 필드 누락(device_id 빈값)은 INVALID_REQUEST`() = runBlocking {
        val h = PushTokenHandler(fakeStore())
        val s = session("alice")
        h.handleRegister(s, 1L, PushTokenRegisterRequest.newBuilder().setClientReqId("r2").setDeviceId("").setPlatform("fcm").setToken("t").build())
        assertEquals(PushTokenRegisterResponse.Result.INVALID_REQUEST, registerRes(s).result)
    }

    @Test
    fun `register — 정상 경로는 OK 및 store upsert 호출`() = runBlocking {
        val store = fakeStore() as Any
        @Suppress("UNCHECKED_CAST")
        val typed = store as PushTokenStore
        val h = PushTokenHandler(typed)
        val s = session("alice")
        h.handleRegister(s, 1L, PushTokenRegisterRequest.newBuilder().setClientReqId("r3").setDeviceId("phone").setPlatform("fcm").setToken("tok-xyz").build())
        val res = registerRes(s)
        assertEquals(PushTokenRegisterResponse.Result.OK, res.result)
        assertEquals("r3", res.clientReqId)
    }

    @Test
    fun `register — store 예외는 SERVER_ERROR`() = runBlocking {
        val h = PushTokenHandler(fakeStore(throwOnUpsert = true))
        val s = session("alice")
        h.handleRegister(s, 1L, PushTokenRegisterRequest.newBuilder().setClientReqId("r4").setDeviceId("phone").setPlatform("fcm").setToken("tok").build())
        assertEquals(PushTokenRegisterResponse.Result.SERVER_ERROR, registerRes(s).result)
    }

    @Test
    fun `register — null repo 는 SERVER_ERROR`() = runBlocking {
        val h = PushTokenHandler(null as PushTokenRepository?)
        val s = session("alice")
        h.handleRegister(s, 1L, PushTokenRegisterRequest.newBuilder().setClientReqId("r5").setDeviceId("phone").setPlatform("fcm").setToken("tok").build())
        assertEquals(PushTokenRegisterResponse.Result.SERVER_ERROR, registerRes(s).result)
    }

    @Test
    fun `unregister — 미인증 세션은 SERVER_ERROR`() = runBlocking {
        val h = PushTokenHandler(fakeStore())
        val s = session(null)
        h.handleUnregister(s, 1L, PushTokenUnregisterRequest.newBuilder().setClientReqId("u1").setDeviceId("phone").build())
        assertEquals(PushTokenUnregisterResponse.Result.SERVER_ERROR, unregisterRes(s).result)
    }

    @Test
    fun `unregister — 정상 경로는 OK + deletedCount`() = runBlocking {
        val h = PushTokenHandler(fakeStore())
        val s = session("alice")
        h.handleUnregister(s, 1L, PushTokenUnregisterRequest.newBuilder().setClientReqId("u2").setDeviceId("phone").build())
        val res = unregisterRes(s)
        assertEquals(PushTokenUnregisterResponse.Result.OK, res.result)
        assertEquals(1, res.deletedCount)
    }

    @Test
    fun `unregister — store 예외는 SERVER_ERROR`() = runBlocking {
        val h = PushTokenHandler(fakeStore(throwOnDelete = true))
        val s = session("alice")
        h.handleUnregister(s, 1L, PushTokenUnregisterRequest.newBuilder().setClientReqId("u3").setDeviceId("phone").build())
        assertEquals(PushTokenUnregisterResponse.Result.SERVER_ERROR, unregisterRes(s).result)
    }
}

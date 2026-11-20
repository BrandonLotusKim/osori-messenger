package com.osori.server.push

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.PushTokenRegisterRequest
import com.osori.proto.v1.PushTokenRegisterResponse
import com.osori.proto.v1.PushTokenUnregisterRequest
import com.osori.proto.v1.PushTokenUnregisterResponse
import com.osori.server.db.PushTokenRepository
import com.osori.server.session.ClientSession
import io.ktor.websocket.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.osori.server.PushTokenHandler")

/**
 * 푸시 토큰 등록/해제 핸들러.
 *
 * 정책:
 *   - 미인증 세션 → INVALID_REQUEST.
 *   - device_id / platform / token 빈 값 → INVALID_REQUEST.
 *   - Repository null (DB OFF) → SERVER_ERROR (푸시는 메모리 모드 없음).
 *   - upsert 는 멱등이므로 같은 토큰 재등록도 OK.
 *
 * 의존: `PushTokenStore` 인터페이스 추상으로 유닛 테스트에서 InMemory/Throwing 주입 가능.
 */
interface PushTokenStore {
    fun upsertToken(userId: String, deviceId: String, platform: String, token: String)
    fun deleteByDevice(userId: String, deviceId: String): Int
}

class PushTokenHandler(private val store: PushTokenStore?) {

    constructor(repo: PushTokenRepository?) : this(
        repo?.let {
            object : PushTokenStore {
                override fun upsertToken(userId: String, deviceId: String, platform: String, token: String) =
                    it.upsertToken(userId, deviceId, platform, token)

                override fun deleteByDevice(userId: String, deviceId: String): Int =
                    it.deleteByDevice(userId, deviceId)
            }
        },
    )

    suspend fun handleRegister(session: ClientSession, sequence: Long, req: PushTokenRegisterRequest) {
        val userId = session.userId
        if (userId.isNullOrBlank()) {
            logger.warn("미인증 push register 거부 session={}", session.id)
            sendRegisterResult(session, sequence, req.clientReqId, PushTokenRegisterResponse.Result.INVALID_REQUEST)
            return
        }
        if (req.deviceId.isBlank() || req.platform.isBlank() || req.token.isBlank()) {
            logger.warn(
                "push register 파라미터 누락 userId={} device='{}' platform='{}' tokenLen={}",
                userId, req.deviceId, req.platform, req.token.length,
            )
            sendRegisterResult(session, sequence, req.clientReqId, PushTokenRegisterResponse.Result.INVALID_REQUEST)
            return
        }
        val s = store
        if (s == null) {
            logger.error("push register 실패 — DB OFF 로 push 기능 불가")
            sendRegisterResult(session, sequence, req.clientReqId, PushTokenRegisterResponse.Result.SERVER_ERROR)
            return
        }
        val result = runCatching { s.upsertToken(userId, req.deviceId, req.platform, req.token) }
            .fold(
                onSuccess = { PushTokenRegisterResponse.Result.OK },
                onFailure = { t ->
                    logger.error("push register 실패 userId={} device={}", userId, req.deviceId, t)
                    PushTokenRegisterResponse.Result.SERVER_ERROR
                },
            )
        sendRegisterResult(session, sequence, req.clientReqId, result)
        if (result == PushTokenRegisterResponse.Result.OK) {
            logger.info("push token 등록 userId={} device={} platform={}", userId, req.deviceId, req.platform)
        }
    }

    suspend fun handleUnregister(session: ClientSession, sequence: Long, req: PushTokenUnregisterRequest) {
        val userId = session.userId
        if (userId.isNullOrBlank() || req.deviceId.isBlank()) {
            sendUnregisterResult(session, sequence, req.clientReqId, 0, PushTokenUnregisterResponse.Result.SERVER_ERROR)
            return
        }
        val s = store
        if (s == null) {
            sendUnregisterResult(session, sequence, req.clientReqId, 0, PushTokenUnregisterResponse.Result.SERVER_ERROR)
            return
        }
        val deleted = runCatching { s.deleteByDevice(userId, req.deviceId) }
            .getOrElse { t ->
                logger.error("push unregister 실패 userId={} device={}", userId, req.deviceId, t)
                sendUnregisterResult(session, sequence, req.clientReqId, 0, PushTokenUnregisterResponse.Result.SERVER_ERROR)
                return
            }
        sendUnregisterResult(session, sequence, req.clientReqId, deleted, PushTokenUnregisterResponse.Result.OK)
        logger.info("push token 해제 userId={} device={} deleted={}", userId, req.deviceId, deleted)
    }

    private suspend fun sendRegisterResult(
        session: ClientSession,
        sequence: Long,
        clientReqId: String,
        result: PushTokenRegisterResponse.Result,
    ) {
        val env = Envelope.newBuilder()
            .setSequence(sequence)
            .setPushTokenRegisterRes(
                PushTokenRegisterResponse.newBuilder()
                    .setClientReqId(clientReqId)
                    .setServerTs(System.currentTimeMillis())
                    .setResult(result)
                    .build(),
            )
            .build()
        session.send(Frame.Binary(true, env.toByteArray()))
    }

    private suspend fun sendUnregisterResult(
        session: ClientSession,
        sequence: Long,
        clientReqId: String,
        deleted: Int,
        result: PushTokenUnregisterResponse.Result,
    ) {
        val env = Envelope.newBuilder()
            .setSequence(sequence)
            .setPushTokenUnregisterRes(
                PushTokenUnregisterResponse.newBuilder()
                    .setClientReqId(clientReqId)
                    .setServerTs(System.currentTimeMillis())
                    .setDeletedCount(deleted)
                    .setResult(result)
                    .build(),
            )
            .build()
        session.send(Frame.Binary(true, env.toByteArray()))
    }
}

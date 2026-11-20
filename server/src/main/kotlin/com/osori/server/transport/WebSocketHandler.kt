package com.osori.server.transport

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.HeartbeatAck
import com.osori.server.auth.LoginHandler
import com.osori.server.file.FileHandler
import com.osori.server.talk.TalkHistoryHandler
import com.osori.server.talk.TalkRouter
import com.osori.server.audit.AuditRepository
import com.osori.server.talk.TalkSearchHandler
import com.osori.server.talk.TalkRoomHandler
import com.osori.server.org.OrgListHandler
import com.osori.server.plugin.LogoutEvent
import com.osori.server.plugin.PluginRegistry
import com.osori.server.push.PushTokenHandler
import com.osori.server.session.ClientSession
import com.osori.server.session.PresenceBroadcaster
import com.osori.server.session.SessionManager
import com.osori.server.session.WsClientSession
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.osori.server.WebSocketHandler")

/**
 * /ws 엔드포인트 등록.
 * 프레임 수신 → Envelope 파싱 → payload 종류별 핸들러 디스패치.
 */
fun Route.installWebSocketHandler(
    sessions: SessionManager,
    loginHandler: LoginHandler,
    talkRouter: TalkRouter,
    talkHistoryHandler: TalkHistoryHandler,
    presenceBroadcaster: PresenceBroadcaster? = null,
    talkRoomHandler: TalkRoomHandler? = null,
    fileHandler: FileHandler? = null,
    orgListHandler: OrgListHandler? = null,
    talkSearchHandler: TalkSearchHandler? = null,
    auditRepository: AuditRepository? = null, // — logout audit
    pluginRegistry: PluginRegistry? = null, // — logout fire
    pushTokenHandler: PushTokenHandler? = null, //
    messageHandler: com.osori.server.message.MessageHandler? = null, //
    talkRoomHistoryHandler: com.osori.server.talk.TalkRoomHistoryHandler? = null, //
) {
    webSocket("/ws") {
        val clientSession: ClientSession = WsClientSession(this)
        logger.info("신규 WebSocket 접속: {}", clientSession)

        try {
            for (frame in incoming) {
                if (frame !is Frame.Binary) {
                    logger.debug("바이너리 아닌 프레임 무시: type={}", frame.frameType)
                    continue
                }

                // 유휴 리퍼가 참조하는 lastSeen 을 매 프레임 수신 시 갱신
                clientSession.lastSeenAtMillis = System.currentTimeMillis()

                val envelope = try {
                    Envelope.parseFrom(frame.readBytes())
                } catch (e: Exception) {
                    logger.warn("Envelope 파싱 실패 session={}: {}", clientSession.id, e.message)
                    continue
                }

                when (envelope.payloadCase) {
                    Envelope.PayloadCase.LOGIN_REQ -> {
                        loginHandler.handleLogin(clientSession, envelope.sequence, envelope.loginReq)
                    }
                    Envelope.PayloadCase.TALK_SEND -> {
                        talkRouter.handleSend(clientSession, envelope.sequence, envelope.talkSend)
                    }
                    Envelope.PayloadCase.HEARTBEAT -> {
                        val now = System.currentTimeMillis()
                        logger.trace("heartbeat session={} ts={}", clientSession.id, envelope.heartbeat.ts)
                        val ack = HeartbeatAck.newBuilder()
                            .setClientTs(envelope.heartbeat.ts)
                            .setServerTs(now)
                            .build()
                        val respEnv = Envelope.newBuilder()
                            .setSequence(envelope.sequence)
                            .setHeartbeatAck(ack)
                            .build()
                        clientSession.send(Frame.Binary(true, respEnv.toByteArray()))
                    }
                    Envelope.PayloadCase.TALK_HISTORY_REQ -> {
                        talkHistoryHandler.handleHistory(clientSession, envelope.sequence, envelope.talkHistoryReq)
                    }
                    Envelope.PayloadCase.TALK_ROOM_CREATE -> {
                        talkRoomHandler?.handleRoomCreate(clientSession, envelope.sequence, envelope.talkRoomCreate)
                    }
                    Envelope.PayloadCase.TALK_ROOM_JOIN -> {
                        talkRoomHandler?.handleRoomJoin(clientSession, envelope.sequence, envelope.talkRoomJoin)
                    }
                    Envelope.PayloadCase.TALK_ROOM_SEND -> {
                        talkRoomHandler?.handleRoomSend(clientSession, envelope.sequence, envelope.talkRoomSend)
                    }
                    Envelope.PayloadCase.TALK_ROOM_INFO_REQ -> {
                        talkRoomHandler?.handleRoomInfo(clientSession, envelope.sequence, envelope.talkRoomInfoReq)
                    }
                    Envelope.PayloadCase.TALK_ROOM_LIST_REQ -> {
                        talkRoomHandler?.handleRoomList(clientSession, envelope.sequence, envelope.talkRoomListReq)
                    }
                    Envelope.PayloadCase.TALK_ROOM_INVITE -> {
                        talkRoomHandler?.handleRoomInvite(clientSession, envelope.sequence, envelope.talkRoomInvite)
                    }
                    Envelope.PayloadCase.TALK_ROOM_LEAVE -> {
                        talkRoomHandler?.handleRoomLeave(clientSession, envelope.sequence, envelope.talkRoomLeave)
                    }
                    Envelope.PayloadCase.TALK_ROOM_RENAME -> {
                        talkRoomHandler?.handleRoomRename(clientSession, envelope.sequence, envelope.talkRoomRename)
                    }
                    Envelope.PayloadCase.FILE_UPLOAD_START -> {
                        fileHandler?.handleStart(clientSession, envelope.sequence, envelope.fileUploadStart)
                    }
                    Envelope.PayloadCase.FILE_UPLOAD_CHUNK -> {
                        fileHandler?.handleChunk(clientSession, envelope.sequence, envelope.fileUploadChunk)
                    }
                    Envelope.PayloadCase.FILE_UPLOAD_COMPLETE -> {
                        fileHandler?.handleComplete(clientSession, envelope.sequence, envelope.fileUploadComplete)
                    }
                    Envelope.PayloadCase.FILE_DOWNLOAD_REQ -> {
                        fileHandler?.handleDownloadRequest(clientSession, envelope.sequence, envelope.fileDownloadReq)
                    }
                    Envelope.PayloadCase.ORG_LIST_REQ -> {
                        orgListHandler?.handle(clientSession, envelope.sequence, envelope.orgListReq)
                    }
                    Envelope.PayloadCase.TALK_SEARCH_REQ -> {
                        talkSearchHandler?.handle(clientSession, envelope.sequence, envelope.talkSearchReq)
                    }
                    Envelope.PayloadCase.PUSH_TOKEN_REGISTER_REQ -> {
                        pushTokenHandler?.handleRegister(clientSession, envelope.sequence, envelope.pushTokenRegisterReq)
                    }
                    Envelope.PayloadCase.PUSH_TOKEN_UNREGISTER_REQ -> {
                        pushTokenHandler?.handleUnregister(clientSession, envelope.sequence, envelope.pushTokenUnregisterReq)
                    }
                    Envelope.PayloadCase.MESSAGE_SEND -> {
                        messageHandler?.handleSend(clientSession, envelope.sequence, envelope.messageSend)
                    }
                    Envelope.PayloadCase.MESSAGE_LIST_REQ -> {
                        messageHandler?.handleList(clientSession, envelope.sequence, envelope.messageListReq)
                    }
                    Envelope.PayloadCase.MESSAGE_MARK_READ -> {
                        messageHandler?.handleMarkRead(clientSession, envelope.sequence, envelope.messageMarkRead)
                    }
                    Envelope.PayloadCase.MESSAGE_DELETE -> {
                        messageHandler?.handleDelete(clientSession, envelope.sequence, envelope.messageDelete)
                    }
                    Envelope.PayloadCase.TALK_ROOM_HISTORY_REQ -> {
                        talkRoomHistoryHandler?.handleRoomHistory(clientSession, envelope.sequence, envelope.talkRoomHistoryReq)
                    }
                    Envelope.PayloadCase.LOGOUT -> {
                        logger.info("로그아웃 요청 session={}", clientSession.id)
                        val uid = clientSession.userId
                        val removed = sessions.remove(clientSession)
                        if (removed && uid != null) {
                            runCatching { presenceBroadcaster?.notifyOffline(uid) }
                            auditRepository?.recordLogout(uid)
                            pluginRegistry?.fireLogout(
                                LogoutEvent(
                                    userId = uid,
                                    explicit = true,
                                    timestamp = java.time.Instant.now(),
                                ),
                            )
                        }
                        close(CloseReason(CloseReason.Codes.NORMAL, "logout"))
                        return@webSocket
                    }
                    else -> {
                        logger.warn(
                            "처리하지 않는 payload session={} case={}",
                            clientSession.id,
                            envelope.payloadCase,
                        )
                    }
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            logger.info("WebSocket 연결 정상 종료: {}", clientSession)
        } catch (t: Throwable) {
            logger.error("WebSocket 핸들러 오류 session={}", clientSession.id, t)
        } finally {
            val uid = clientSession.userId
            val removed = sessions.remove(clientSession)
            if (removed && uid != null) {
                runCatching { presenceBroadcaster?.notifyOffline(uid) }
                // 연결 끊김에 의한 암묵적 로그아웃 (explicit=false)
                auditRepository?.recordLogout(uid)
                pluginRegistry?.fireLogout(
                    LogoutEvent(
                        userId = uid,
                        explicit = false,
                        timestamp = java.time.Instant.now(),
                    ),
                )
            }
            logger.info("세션 정리 완료: {} (남은 접속자: {})", clientSession, sessions.count())
        }
    }
}

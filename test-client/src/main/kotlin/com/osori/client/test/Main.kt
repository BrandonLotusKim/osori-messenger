package com.osori.client.test

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.Heartbeat
import com.osori.proto.v1.TalkHistoryRequest
import com.osori.proto.v1.LoginRequest
import com.osori.proto.v1.TalkSend
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.X509TrustManager

// 오소리 메신저 테스트 CLI 클라이언트.
//
// 사용법: gradlew run --args="HOST PORT MY_ID PEER MSG [--history N] [--no-heartbeat] [--tls] [--pw PASSWORD] [--auto-reconnect] [--max-reconnect N]"
//   - PEER, MSG 는 위치 인자. 보내지 않으려면 - 으로 표시.
//   - --history N          로그인 직후 TalkHistoryRequest sinceTs=0 limit=N 송신 (첫 접속에서만)
//   - --no-heartbeat       자동 30초 주기 Heartbeat 송신을 끔 (기본은 켬)
//   - --tls                wss 접속 (자체 서명 인증서 검증 무시)
//   - --pw PASSWORD        LoginRequest.password 필드에 평문 password 전달 (STRICT 모드용, 반드시 wss 와 함께)
//   - --auto-reconnect     연결이 끊기면 지수 백오프로 자동 재접속 (기본은 끔)
//   - --max-reconnect N    --auto-reconnect 동반 사용, 최대 재시도 횟수 (기본 10)
//
// 예시:
//   gradlew run --args="localhost 7071 alice"                                    // 수신 대기 + 자동 heartbeat
//   gradlew run --args="localhost 7071 bob alice 안녕"                            // 1건 전송
//   gradlew run --args="localhost 7071 alice - - --history 50"                   // 히스토리 50건 요청
//   gradlew run --args="localhost 7443 alice - - --tls"                          // wss 접속
//   gradlew run --args="localhost 7071 alice - - --auto-reconnect"               // 끊기면 자동 재접속
fun main(args: Array<String>) = runBlocking {
    if (args.size < 3) {
        println("사용법: <호스트> <포트> <내 ID> [상대 ID] [메시지] [--history N] [--no-heartbeat] [--tls] [--auto-reconnect]")
        println("        [--memo \"r1,r2\" \"title\" \"body\"] 쪽지 발송")
        println("        [--room-history <ROOM_UUID> [LIMIT]] 방 히스토리 조회")
        return@runBlocking
    }

    val host = args[0]
    val port = args[1].toInt()
    val myId = args[2]
    val peerArg = args.getOrNull(3)
    val textArg = args.getOrNull(4)
    val peerId = peerArg?.takeUnless { it == "-" }
    val text = textArg?.takeUnless { it == "-" }

    val historyLimit: Int? = run {
        val idx = args.indexOf("--history")
        if (idx >= 0 && idx + 1 < args.size) args[idx + 1].toIntOrNull() else null
    }
    val heartbeatEnabled = "--no-heartbeat" !in args
    val tlsEnabled = "--tls" in args
    val passwordArg: String = run {
        val idx = args.indexOf("--pw")
        if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else ""
    }
    val autoReconnect = "--auto-reconnect" in args
    val maxReconnect: Int = run {
        val idx = args.indexOf("--max-reconnect")
        if (idx >= 0 && idx + 1 < args.size) args[idx + 1].toIntOrNull() ?: 10 else 10
    }
    // 쪽지 송신: --memo "recipient1,recipient2" "title" "body"
    val memoArgs: Triple<List<String>, String, String>? = run {
        val idx = args.indexOf("--memo")
        if (idx >= 0 && idx + 3 < args.size) {
            val recipients = args[idx + 1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
            Triple(recipients, args[idx + 2], args[idx + 3])
        } else null
    }
    // 방 히스토리 조회: --room-history ROOM_UUID [LIMIT]
    val roomHistoryArgs: Pair<String, Int>? = run {
        val idx = args.indexOf("--room-history")
        if (idx >= 0 && idx + 1 < args.size) {
            val roomId = args[idx + 1]
            val limit = args.getOrNull(idx + 2)?.toIntOrNull() ?: 50
            roomId to limit
        } else null
    }

    val client = HttpClient(CIO) {
        install(WebSockets)
        if (tlsEnabled) {
            engine {
                https {
                    trustManager = object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    }
                }
            }
        }
    }

    val urlString = if (tlsEnabled) "wss://$host:$port/ws" else "ws://$host:$port/ws"
    println("[$myId] $urlString 접속 시도 (auto-reconnect=$autoReconnect)")

    var attempt = 0
    var firstConnection = true
    try {
        while (true) {
            runSession(
                client = client,
                urlString = urlString,
                myId = myId,
                passwordArg = passwordArg,
                peerId = peerId,
                text = text,
                historyLimit = historyLimit,
                heartbeatEnabled = heartbeatEnabled,
                firstConnection = firstConnection,
                memoArgs = memoArgs,
                roomHistoryArgs = roomHistoryArgs,
            )
            firstConnection = false

            if (!autoReconnect) {
                break
            }

            attempt++
            if (attempt > maxReconnect) {
                println("[$myId] 최대 재시도 ($maxReconnect) 초과 — 종료")
                break
            }

            val delayMs = computeBackoffDelayMs(attempt - 1)
            println("[$myId] 재접속 대기 ${delayMs}ms (시도 $attempt/$maxReconnect)")
            delay(delayMs.toLong())
            println("[$myId] 재접속 시도 $attempt")
        }
    } finally {
        client.close()
    }
}

//: 지수 백오프 (C# 클라와 동일한 공식).
// attempt=0 → 1s, 1 → 2s, 2 → 4s, 3 → 8s, 4 → 16s, 5+ → 30s (cap)
internal fun computeBackoffDelayMs(attempt: Int): Int {
    val capMs = 30_000
    val baseMs = 1000
    if (attempt < 0) return baseMs
    if (attempt >= 5) return capMs
    return minOf(capMs, baseMs shl attempt)
}

/**
 * 한 번의 WebSocket 세션을 실행한다. 정상/비정상 종료 모두 return 으로 돌아온다.
 * firstConnection=true 일 때만 peer 메시지/히스토리 요청 같은 일회성 액션을 수행한다.
 */
private suspend fun runSession(
    client: HttpClient,
    urlString: String,
    myId: String,
    passwordArg: String,
    peerId: String?,
    text: String?,
    historyLimit: Int?,
    heartbeatEnabled: Boolean,
    firstConnection: Boolean,
    memoArgs: Triple<List<String>, String, String>? = null,
    roomHistoryArgs: Pair<String, Int>? = null,
) {
    val seqGen = AtomicLong(1)
    try {
        client.webSocket(urlString = urlString) {
            val loginReq = LoginRequest.newBuilder()
                .setUserId(myId)
                .setPassword(passwordArg)
                .setDeviceId("test-cli-${System.currentTimeMillis()}")
                .setClientVersion("0.0.1")
                .build()
            val loginEnv = Envelope.newBuilder()
                .setSequence(seqGen.getAndIncrement())
                .setLoginReq(loginReq)
                .build()
            send(Frame.Binary(true, loginEnv.toByteArray()))
            println("[$myId] 로그인 요청 전송 (first=$firstConnection)")

            if (firstConnection && peerId != null && text != null) {
                launch {
                    delay(500)
                    val msg = TalkSend.newBuilder()
                        .setClientTalkId(UUID.randomUUID().toString())
                        .setToUserId(peerId)
                        .setText(text)
                        .setClientTs(System.currentTimeMillis())
                        .build()
                    val env = Envelope.newBuilder()
                        .setSequence(seqGen.getAndIncrement())
                        .setTalkSend(msg)
                        .build()
                    send(Frame.Binary(true, env.toByteArray()))
                    println("[$myId] → $peerId : $text")
                }
            }

            if (firstConnection && historyLimit != null) {
                launch {
                    delay(700)
                    val req = TalkHistoryRequest.newBuilder()
                        .setSinceTs(0)
                        .setLimit(historyLimit)
                        .build()
                    val env = Envelope.newBuilder()
                        .setSequence(seqGen.getAndIncrement())
                        .setTalkHistoryReq(req)
                        .build()
                    send(Frame.Binary(true, env.toByteArray()))
                    println("[$myId] TalkHistoryRequest(limit=$historyLimit) 송신")
                }
            }

            // 쪽지 송신
            if (firstConnection && memoArgs != null) {
                launch {
                    delay(900)
                    val (recipients, title, body) = memoArgs
                    val memo = com.osori.proto.v1.MessageSend.newBuilder()
                        .setClientMessageId(UUID.randomUUID().toString())
                        .setTitle(title)
                        .setBody(body)
                        .addAllRecipientIds(recipients)
                        .build()
                    val env = Envelope.newBuilder()
                        .setSequence(seqGen.getAndIncrement())
                        .setMessageSend(memo)
                        .build()
                    send(Frame.Binary(true, env.toByteArray()))
                    println("[$myId] MessageSend → $recipients title='$title' 송신")
                }
            }

            // 방 히스토리 송신
            if (firstConnection && roomHistoryArgs != null) {
                launch {
                    delay(1100)
                    val (roomId, limit) = roomHistoryArgs
                    val req = com.osori.proto.v1.TalkRoomHistoryRequest.newBuilder()
                        .setRoomId(roomId)
                        .setSinceTs(0)
                        .setLimit(limit)
                        .setClientReqId(UUID.randomUUID().toString())
                        .build()
                    val env = Envelope.newBuilder()
                        .setSequence(seqGen.getAndIncrement())
                        .setTalkRoomHistoryReq(req)
                        .build()
                    send(Frame.Binary(true, env.toByteArray()))
                    println("[$myId] TalkRoomHistoryRequest(roomId=$roomId limit=$limit) 송신")
                }
            }

            val heartbeatJob = if (heartbeatEnabled) {
                launch {
                    while (isActive) {
                        delay(30_000L)
                        val hb = Heartbeat.newBuilder()
                            .setTs(System.currentTimeMillis())
                            .build()
                        val env = Envelope.newBuilder()
                            .setSequence(seqGen.getAndIncrement())
                            .setHeartbeat(hb)
                            .build()
                        runCatching { send(Frame.Binary(true, env.toByteArray())) }
                    }
                }
            } else null

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    val env = try {
                        Envelope.parseFrom(frame.readBytes())
                    } catch (e: Exception) {
                        println("[$myId] 파싱 오류: ${e.message}")
                        continue
                    }

                    when (env.payloadCase) {
                        Envelope.PayloadCase.LOGIN_RES -> {
                            val r = env.loginRes
                            val tokenPreview = r.sessionToken.take(8).ifEmpty { "(none)" }
                            println("[$myId] 로그인 응답: status=${r.status} token=$tokenPreview...")
                        }
                        Envelope.PayloadCase.TALK_SEND_ACK -> {
                            val a = env.talkSendAck
                            println("[$myId] 전송 확인: result=${a.result} serverTalkId=${a.serverTalkId}")
                        }
                        Envelope.PayloadCase.TALK_RECEIVED -> {
                            val m = env.talkReceived
                            println("[$myId] ← ${m.fromUserId} : ${m.text}")
                        }
                        Envelope.PayloadCase.HEARTBEAT_ACK -> {
                            val a = env.heartbeatAck
                            val rtt = System.currentTimeMillis() - a.clientTs
                            println("[$myId] heartbeat ack rtt=${rtt}ms serverTs=${a.serverTs}")
                        }
                        Envelope.PayloadCase.PRESENCE_EVENT -> {
                            val p = env.presenceEvent
                            println("[$myId] *** ${p.userId} ${p.state}")
                        }
                        Envelope.PayloadCase.TALK_HISTORY_RES -> {
                            val r = env.talkHistoryRes
                            println("[$myId] 히스토리 응답: ${r.talksCount} 건 (has_more=${r.hasMore})")
                            for (m in r.talksList) {
                                println("  · ${m.fromUserId} → ${m.text}")
                            }
                        }
                        Envelope.PayloadCase.TALK_ROOM_CREATE_ACK,
                        Envelope.PayloadCase.TALK_ROOM_JOIN_ACK,
                        Envelope.PayloadCase.TALK_ROOM_SEND_ACK,
                        Envelope.PayloadCase.TALK_ROOM_RECEIVED -> {
                            println("[$myId] room frame: ${env.payloadCase}")
                        }
                        // 그룹 히스토리 응답
                        Envelope.PayloadCase.TALK_ROOM_HISTORY_RES -> {
                            val r = env.talkRoomHistoryRes
                            println("[$myId] 방 히스토리 응답: status=${r.status} room=${r.roomId} ${r.talksCount} 건 (has_more=${r.hasMore})")
                            for (m in r.talksList) {
                                println("  · ${m.fromUserId} → ${m.text}")
                            }
                        }
                        // 쪽지 frames
                        Envelope.PayloadCase.MESSAGE_SEND_ACK -> {
                            val a = env.messageSendAck
                            println("[$myId] 쪽지 발송 ack: result=${a.result} serverMessageId=${a.serverMessageId} recipients=${a.recipientResultsCount}")
                        }
                        Envelope.PayloadCase.MESSAGE_RECEIVED -> {
                            val m = env.messageReceived
                            println("[$myId] ✉ 쪽지 도착: from=${m.fromUserId} title='${m.title}' body='${m.body}'")
                        }
                        Envelope.PayloadCase.MESSAGE_LIST_RES -> {
                            val r = env.messageListRes
                            println("[$myId] 쪽지함: status=${r.status} ${r.entriesCount} 건 (has_more=${r.hasMore})")
                            for (e in r.entriesList) {
                                println("  · ${e.serverMessageId} from=${e.fromUserId} '${e.title}' sentAt=${e.sentAt}")
                            }
                        }
                        Envelope.PayloadCase.MESSAGE_MARK_READ_ACK -> {
                            println("[$myId] 쪽지 읽음 ack: result=${env.messageMarkReadAck.result} readAt=${env.messageMarkReadAck.readAt}")
                        }
                        Envelope.PayloadCase.MESSAGE_DELETE_ACK -> {
                            println("[$myId] 쪽지 삭제 ack: result=${env.messageDeleteAck.result}")
                        }
                        else -> {
                            println("[$myId] 기타 프레임: ${env.payloadCase}")
                        }
                    }
                }
            } finally {
                heartbeatJob?.cancel()
            }
        }
        println("[$myId] 세션 정상 종료")
    } catch (t: Throwable) {
        println("[$myId] 세션 오류: ${t.message}")
    }
}

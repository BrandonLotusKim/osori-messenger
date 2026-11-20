package com.osori.server.session

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 추상 세션 인터페이스.
 *
 * 운영 코드는 WebSocket 기반 구현(WsClientSession)을 사용하고,
 * 단위 테스트는 페이크 구현으로 대체한다.
 */
interface ClientSession {
    val id: Long
    var userId: String?

    /** 마지막 프레임 수신 시각 (epoch millis). 유휴 세션 리퍼가 참조. */
    var lastSeenAtMillis: Long

    /** 클라이언트에 프레임 전송. */
    suspend fun send(frame: Frame)

    /** 연결 종료. */
    suspend fun close(reason: CloseReason)
}

/**
 * 실제 Ktor WebSocket 기반 ClientSession 구현.
 * 로그인 전에는 userId 가 null, 로그인 성공 시 세팅.
 */
class WsClientSession(
    private val webSocket: DefaultWebSocketServerSession,
) : ClientSession {

    override val id: Long = nextId.getAndIncrement()

    @Volatile
    override var userId: String? = null

    @Volatile
    override var lastSeenAtMillis: Long = System.currentTimeMillis()

    override suspend fun send(frame: Frame) {
        webSocket.send(frame)
    }

    override suspend fun close(reason: CloseReason) {
        webSocket.close(reason)
    }

    override fun toString(): String = "WsClientSession(id=$id, userId=$userId)"

    companion object {
        private val nextId = AtomicLong(1)
    }
}

/**
 * 메모리 기반 세션 매니저 (MVP).
 * 분산 환경으로 확장 시 Redis 기반 구현으로 교체 예정.
 */
class SessionManager {
    // userId → ClientSession
    private val byUser = ConcurrentHashMap<String, ClientSession>()

    /**
     * 로그인 성공 시 호출.
     * @return 같은 userId 로 이미 접속 중이던 기존 세션 (있다면 — 중복 로그인 케이스)
     */
    fun bind(userId: String, session: ClientSession): ClientSession? {
        session.userId = userId
        return byUser.put(userId, session)
    }

    fun get(userId: String): ClientSession? = byUser[userId]

    /**
     * 같은 인스턴스일 때만 제거 (CAS 유사). 실제 제거 여부를 반환해 호출자가
     * presence broadcast 등 follow-up 로직을 한 번만 실행할 수 있게 한다.
     */
    fun remove(session: ClientSession): Boolean {
        val uid = session.userId ?: return false
        return byUser.remove(uid, session)
    }

    fun count(): Int = byUser.size

    /** 현재 접속 중인 세션의 스냅샷. 리퍼/Presence 브로드캐스트에서 순회용. */
    fun snapshot(): List<ClientSession> = byUser.values.toList()

    /** 현재 온라인 userId 목록 (Presence 초기 동기화 등). */
    fun onlineUserIds(): List<String> = byUser.keys.toList()
}

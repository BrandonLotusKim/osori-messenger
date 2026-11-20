package com.osori.server.testutil

import com.osori.server.session.ClientSession
import io.ktor.websocket.*
import java.util.concurrent.atomic.AtomicLong

/**
 * 단위 테스트용 페이크 ClientSession.
 *
 * send() / close() 호출을 메모리에 기록하여 테스트가 프레임 송신과 종료 사유를 검증할 수 있게 한다.
 * 실제 DefaultWebSocketServerSession 은 사용하지 않으므로 Ktor/Netty 없이 돌아간다.
 */
class FakeClientSession(
    override val id: Long = nextId.getAndIncrement(),
) : ClientSession {

    @Volatile
    override var userId: String? = null

    @Volatile
    override var lastSeenAtMillis: Long = System.currentTimeMillis()

    val sentFrames: MutableList<Frame> = mutableListOf()
    val closeReasons: MutableList<CloseReason> = mutableListOf()

    @Volatile
    var closed: Boolean = false
        private set

    override suspend fun send(frame: Frame) {
        sentFrames += frame
    }

    override suspend fun close(reason: CloseReason) {
        closed = true
        closeReasons += reason
    }

    override fun toString(): String = "FakeClientSession(id=$id, userId=$userId)"

    companion object {
        private val nextId = AtomicLong(10_000)
    }
}

package com.osori.server.talk

import com.osori.proto.v1.Envelope
import com.osori.proto.v1.TalkReceived
import com.osori.server.db.TalkRepository
import com.osori.server.session.ClientSession
import io.ktor.websocket.*
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

private val logger = LoggerFactory.getLogger("com.osori.server.TalkOutboxFlusher")

interface OutboxStore {
    fun fetchOutbox(toUser: String, limit: Int = 100): List<TalkRepository.OutboxEntry>
    fun markDelivered(outboxId: Long)
}

class TalkOutboxFlusher(
    private val outboxStore: OutboxStore,
) {

    constructor(talkRepository: TalkRepository) : this(
        object : OutboxStore {
            override fun fetchOutbox(
                toUser: String,
                limit: Int,
            ): List<TalkRepository.OutboxEntry> = talkRepository.fetchOutbox(toUser, limit)

            override fun markDelivered(outboxId: Long) {
                talkRepository.markDelivered(outboxId)
            }
        },
    )

    private val serverSequence = AtomicLong(2_000_000)

    suspend fun flush(session: ClientSession, userId: String): Int {
        val entries = outboxStore.fetchOutbox(userId, limit = 100)
        var deliveredCount = 0

        for (entry in entries) {
            runCatching {
                val message = entry.message
                val notice = TalkReceived.newBuilder()
                    .setServerTalkId(message.messageId.toString())
                    .setFromUserId(message.fromUser)
                    .setToUserId(message.toUser)
                    .setText(message.text)
                    .setServerTs(message.serverTs.toEpochMilli())
                    .build()

                val envelope = Envelope.newBuilder()
                    .setSequence(serverSequence.incrementAndGet())
                    .setTalkReceived(notice)
                    .build()

                session.send(Frame.Binary(true, envelope.toByteArray()))
                outboxStore.markDelivered(entry.id)
                deliveredCount += 1
            }.onFailure { t ->
                logger.warn(
                    "outbox flush 실패 userId={} outboxId={} msgId={}",
                    userId,
                    entry.id,
                    entry.message.messageId,
                    t,
                )
            }
        }

        if (deliveredCount > 0) {
            logger.info("outbox flush 완료 userId={} delivered={}", userId, deliveredCount)
        }

        return deliveredCount
    }
}

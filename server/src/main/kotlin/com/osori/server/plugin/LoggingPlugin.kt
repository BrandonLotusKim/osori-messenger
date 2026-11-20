package com.osori.server.plugin

import org.slf4j.LoggerFactory

/**
 * 샘플 플러그인 — 모든 이벤트를 INFO 로 로깅.
 *
 * SDK 가 정상 작동하는지 확인하는 레퍼런스 구현. 운영 환경에서는 노이즈가
 * 클 수 있으니 기본 비활성. 필요 시 Main 에서 `registry.register(LoggingPlugin())` 호출.
 */
class LoggingPlugin : ServerPlugin {
    override val id: String = "com.osori.plugin.logging"
    override val name: String = "Osori Logging Plugin"
    override val version: String = "0.1.0"

    private val log = LoggerFactory.getLogger("com.osori.plugin.LoggingPlugin")

    override fun onStart(ctx: PluginContext) {
        log.info("LoggingPlugin 시작 serverVersion={}", ctx.serverVersion)
    }

    override fun onStop() {
        log.info("LoggingPlugin 종료")
    }

    override fun onLogin(event: LoginEvent) {
        log.info(
            "[plugin] login user={} device={} version={} success={} reason={}",
            event.userId, event.deviceId, event.clientVersion, event.success, event.failureReason,
        )
    }

    override fun onLogout(event: LogoutEvent) {
        log.info("[plugin] logout user={} explicit={}", event.userId, event.explicit)
    }

    override fun onMessageSent(event: MessageSentEvent) {
        log.info(
            "[plugin] msg.sent from={} to={} msgId={} bytes={} queued={}",
            event.fromUserId, event.toUserId, event.serverTalkId, event.bytes, event.queued,
        )
    }

    override fun onTalkRoomCreated(event: TalkRoomCreatedEvent) {
        log.info("[plugin] room.created owner={} roomId={} name={}", event.ownerUserId, event.roomId, event.name)
    }

    override fun onFileUploaded(event: FileUploadedEvent) {
        log.info(
            "[plugin] file.uploaded user={} uploadId={} filename={} bytes={}",
            event.uploaderUserId, event.uploadId, event.filename, event.totalBytes,
        )
    }

    override fun onFileDownloaded(event: FileDownloadedEvent) {
        log.info("[plugin] file.downloaded user={} uploadId={}", event.downloaderUserId, event.uploadId)
    }
}

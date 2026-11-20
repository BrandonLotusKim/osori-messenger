package com.osori.server.plugin

import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 플러그인 등록/발화 호스트.
 *
 * - 등록: 부팅 시 `register()` 로 정적 수집. 런타임 동적 로드는 추후 과제.
 * - 발화: `fireXxx()` 각 훅이 모든 등록 플러그인에 순차 호출.
 *   **각 플러그인 호출은 `runCatching` 으로 격리** — 예외는 warning 으로 로그만 남기고
 *   다음 플러그인으로 계속 진행. 호스트 흐름은 절대 깨지지 않는다.
 * - 시작/종료: `startAll()` / `stopAll()` 을 호스트 부팅/셧다운 시점에 호출.
 *   시작 실패한 플러그인도 등록 상태는 유지되지만 이후 이벤트는 계속 받는다
 *   (플러그인 스스로 disabled 플래그를 관리해야 함).
 *
 * **스레드 안전성**: `CopyOnWriteArrayList` 사용으로 fire 중 register 안전.
 * fire 는 snapshot iterator 라 register 순서가 중간에 바뀌어도 안전.
 */
class PluginRegistry {

    private val plugins = CopyOnWriteArrayList<ServerPlugin>()
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    fun register(plugin: ServerPlugin) {
        plugins.find { it.id == plugin.id }?.let {
            logger.warn("플러그인 ID 중복 — 기존을 교체: id={} old={} new={}", it.id, it.version, plugin.version)
            plugins.remove(it)
        }
        plugins.add(plugin)
        logger.info("플러그인 등록 id={} name='{}' version={}", plugin.id, plugin.name, plugin.version)
    }

    fun count(): Int = plugins.size

    /** 디버그용 — 등록된 플러그인의 불변 스냅샷. */
    fun list(): List<ServerPlugin> = plugins.toList()

    fun startAll(ctx: PluginContext) {
        if (!started.compareAndSet(false, true)) {
            logger.warn("startAll 중복 호출 무시")
            return
        }
        for (p in plugins) {
            runCatching { p.onStart(ctx) }
                .onFailure { logger.warn("플러그인 onStart 실패 id={} err={}", p.id, it.message, it) }
        }
    }

    fun stopAll() {
        if (!started.compareAndSet(true, false)) return
        for (p in plugins) {
            runCatching { p.onStop() }
                .onFailure { logger.warn("플러그인 onStop 실패 id={} err={}", p.id, it.message, it) }
        }
    }

    fun fireLogin(event: LoginEvent) = fireEach("onLogin") { it.onLogin(event) }
    fun fireLogout(event: LogoutEvent) = fireEach("onLogout") { it.onLogout(event) }
    fun fireMessageSent(event: MessageSentEvent) = fireEach("onMessageSent") { it.onMessageSent(event) }
    fun fireTalkRoomCreated(event: TalkRoomCreatedEvent) = fireEach("onTalkRoomCreated") { it.onTalkRoomCreated(event) }
    fun fireFileUploaded(event: FileUploadedEvent) = fireEach("onFileUploaded") { it.onFileUploaded(event) }
    fun fireFileDownloaded(event: FileDownloadedEvent) = fireEach("onFileDownloaded") { it.onFileDownloaded(event) }

    private inline fun fireEach(hookName: String, action: (ServerPlugin) -> Unit) {
        for (p in plugins) {
            runCatching { action(p) }
                .onFailure { logger.warn("플러그인 {} 실패 id={} err={}", hookName, p.id, it.message, it) }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger("com.osori.server.PluginRegistry")
    }
}

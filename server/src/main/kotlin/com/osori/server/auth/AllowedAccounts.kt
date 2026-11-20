package com.osori.server.auth

/**
 * 로그인 허용 계정 화이트리스트.
 *
 * 개발·시험 환경 정책: 로그인 가능한 계정을
 * admin / test1~test10 으로 한정한다. 이 목록에 없는 userId 는 계정 존재 여부와
 * 무관하게 무조건 로그인 거부 — [LoginHandler] 가 인증 시도 최초 단계에서 체크한다.
 *
 * [SeedAllowedAccountsCli] 가 이 목록을 그대로 읽어 DB 에 계정을 생성/비밀번호 재설정한다 —
 * 계정 목록이 두 군데서 따로 관리되며 어긋나는 일이 없도록 단일 소스로 유지.
 */
object AllowedAccounts {
    val IDS: Set<String> = (setOf("admin") + (1..10).map { "test$it" })
        .also { require(it.size == 11) }
}

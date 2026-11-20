# 오소리 메신저 — 개발자 운영 가이드

> 내일의 나를 위한 문서. 서버 기동, 클래스 구조, DB 접속까지 한 번에.
> 최초 작성: 2026-04-13 (Day 3 엔드투엔드 구동 검증 완료 시점)

---

## 1. 30초 요약

- 오소리 메신저는 **Kotlin + Ktor WebSocket 서버** + **Protobuf 와이어 프로토콜** + **PostgreSQL 16** 기반.
- 본체는 `server/`, 테스트 CLI 는 `test-client/`, C# WPF 클라는 `client-csharp/` (Phase 5 완료).
- **구동 검증 완료** — **서버 212개 단위/통합 테스트**(2026-09-11 기준) + A→B 온라인 배달 + 오프라인 outbox + 재접속 flush + Heartbeat ACK + Presence broadcast + 히스토리 sync + graceful shutdown + TLS 1.3 wss + client_msg_id 멱등 + Ktor `testApplication` 풀스택 + Argon2id 실인증 (OPEN/STRICT) + **사용자별 토큰 버킷 rate limit** 엔드투엔드 실증.
- 이 문서만 따라하면 다시 기동 → 메시지 주고받기 → DB 확인 → 종료까지 전 사이클 가능.
- 와이어 프로토콜 사양은 `PROTOCOL.md` 참조.

---

## 2. 선행 조건 (이미 세팅 완료)

| 항목 | 경로 / 값 | 비고 |
|---|---|---|
| JDK 17 | `<repo>\.tools\jdk-17.0.12+7` | `JAVA_HOME` 을 이 경로로 `setx` 해둔 상태 |
| Gradle 8.5 | 각 모듈의 `gradlew.bat` 가 자동 사용 | 별도 설치 불필요 |
| PostgreSQL 16.6 portable | `<repo>\.tools\pg16\pgsql\` | EDB 바이너리 배포본 압축 해제본 |
| Postgres 데이터 디렉토리 | `<repo>\.tools\pg16\data` | `initdb` 완료 상태, trust 인증, 포트 5432 |
| Postgres 로그 | `<repo>\.tools\pg16\log\server.log` | |
| IntelliJ IDEA | `C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.2` | 서버/테스트 클라 소스 열람·편집 |
| HeidiSQL | 시작메뉴 | PostgreSQL 지원 (libpq 모드) |

> **만약 PC 가 재부팅됐거나 Postgres 가 꺼져있다면** 아래 3.1 로 가서 먼저 기동할 것.

---

## 3. 서버 기동 (처음부터 끝까지)

### 3.1 PostgreSQL 기동 / 상태 확인 / 정지

`bat
:: 기동
<repo>\.tools\pg16\pgsql\bin\pg_ctl.exe -D <repo>\.tools\pg16\data -l <repo>\.tools\pg16\log\server.log start

:: 상태 확인 (포트 5432 listening?)
netstat -ano | findstr :5432

:: 핑 테스트
<repo>\.tools\pg16\pgsql\bin\psql.exe -U postgres -h localhost -d osori -c "SELECT now;"

:: 정지
<repo>\.tools\pg16\pgsql\bin\pg_ctl.exe -D <repo>\.tools\pg16\data stop
`

### 3.2 DB 초기화 (최초 1회만 — 이미 완료된 상태)

현재 이미 두 개 DB 가 준비돼 있으므로 **다시 실행할 필요 없음**. 파괴됐거나 재설치 시에만.

`bat
<repo>\.tools\pg16\pgsql\bin\createdb.exe -U postgres -h localhost osori
<repo>\.tools\pg16\pgsql\bin\createdb.exe -U postgres -h localhost osori_test

<repo>\.tools\pg16\pgsql\bin\psql.exe -U postgres -h localhost -d osori      -f <repo>\OsoriMessenger\server\src\main\resources\db\schema.sql
<repo>\.tools\pg16\pgsql\bin\psql.exe -U postgres -h localhost -d osori_test -f <repo>\OsoriMessenger\server\src\main\resources\db\schema.sql
`

### 3.3 서버 기동 — 방법 A: 터미널

가장 확실. 복사해서 PowerShell 또는 cmd 에 붙여넣기.

`bat
cd /d <repo>\OsoriMessenger\server

set JAVA_HOME=<repo>\.tools\jdk-17.0.12+7
set OSORI_DB_URL=jdbc:postgresql://localhost:5432/osori
set OSORI_DB_USER=postgres
set OSORI_DB_PASS=
set OSORI_PORT=7071
set OSORI_LOG_FORMAT=text

.\gradlew.bat run
`

**포트 주의** — 7070 은 AnyDesk 가 이미 쓰고 있음. 오소리는 7071 사용.

성공 로그 예시:
`
INFO  com.osori.server.Main - 오소리 메신저 서버 기동 준비 host=0.0.0.0 port=7071 dbEnabled=true
INFO  com.zaxxer.hikari.HikariDataSource - osori-db-pool - Start completed.
INFO  com.osori.server.Main - 오소리 메신저 서버 구성 완료 (DB=ON)
INFO  ktor.application - Application started in 0.126 seconds.
INFO  ktor.application - Responding at http://127.0.0.1:7071
`

### 3.4 서버 기동 — 방법 B: IntelliJ IDEA

1. IntelliJ 실행 → Open → `<repo>\OsoriMessenger\server` 선택
2. Gradle import 끝날 때까지 대기 (우하단 진행바)
3. `src/main/kotlin/com/osori/server/app/Main.kt` 열기
4. `fun main` 왼쪽 ▶ 버튼 클릭 → **Run 'MainKt'**
5. 최초 실행 시 Run Configuration 환경변수 세팅 (Run → Edit Configurations):
   - `OSORI_DB_URL` = `jdbc:postgresql://localhost:5432/osori`
   - `OSORI_DB_USER` = `postgres`
   - `OSORI_DB_PASS` = (빈 값)
   - `OSORI_PORT` = `7071`
   - `OSORI_LOG_FORMAT` = `text`
6. 한 번 세팅해두면 다음부터는 ▶ 만 누르면 됨.

### 3.5 서버 정상 구동 확인

`bat
:: 포트 listen?
netstat -ano | findstr :7071

:: HTTP 루트 ping (200/404 어떤 거든 오면 살아있음)
curl -v http://localhost:7071/
`

### 3.5.1 TLS (wss) 옵션 기동 —

자체 서명 keystore 가 `<repo>\.tools\osori-tls\osori.jks` 에 미리 생성되어 있음 (RSA 2048, SAN dns:localhost+ip:127.0.0.1, 365일 유효).

기동 시 환경변수 추가:
`bat
cd /d <repo>\OsoriMessenger\server

set JAVA_HOME=<repo>\.tools\jdk-17.0.12+7
set OSORI_DB_USER=postgres
set OSORI_DB_PASS=
set OSORI_PORT=7071
set OSORI_TLS_ENABLED=true
set OSORI_TLS_PORT=7443
set OSORI_KEYSTORE_PATH=<repo>\.tools\osori-tls\osori.jks
set OSORI_KEYSTORE_PASSWORD=osori_dev_pass
set OSORI_KEY_PASSWORD=osori_dev_pass

.\gradlew.bat run
`

성공 로그:
`
INFO  com.osori.server.Main - TLS keystore 로드 성공 path=... alias=osori
INFO  ktor.application - Responding at http://127.0.0.1:7071
INFO  ktor.application - Responding at https://127.0.0.1:7443
`

검증 명령:
`bat
:: TLS handshake (404 응답이 와도 OK — handshake 자체는 성공)
curl -k -v https://localhost:7443/

:: 인증서 정보 (openssl 있다면)
echo Q | openssl s_client -connect localhost:7443 -servername localhost
`

기대 결과:
- subject/issuer = `CN=localhost, OU=Osori, O=Osori, L=Seoul, C=KR`
- Protocol: TLSv1.3
- Cipher: TLS_AES_128_GCM_SHA256
- "self-signed certificate" 경고 — 정상 (자체 서명이라 의도된 결과)

**주의**: 현재 test-client 는 `ws://` 만 사용. wss 검증은 curl/openssl 또는 별도 wss 클라이언트 필요. test-client 의 wss 지원은 다음 작업 후보.

### 3.6 Graceful shutdown

- **터미널 기동한 경우**: 해당 창에서 `Ctrl+C` 한 번 → 로그에 아래가 순차적으로 찍혀야 정상.
  `
  INFO com.osori.server.Main - SIGTERM/Ctrl+C 감지 — 오소리 메신저 서버 종료 시작
  INFO ktor.application - Application stopping
  INFO ktor.application - Application stopped
  INFO HikariDataSource - osori-db-pool - Shutdown initiated
  INFO HikariDataSource - osori-db-pool - Shutdown completed
  INFO com.osori.server.Main - 오소리 메신저 서버 종료 완료
  `
  이 시퀀스가 `Main.kt` 의 `Runtime.getRuntime.addShutdownHook` 에서 나오는 것. 에서 검증됨.

- **IntelliJ 에서 기동한 경우**: Run 창 좌측의 🟥 **빨간 네모(Stop)** 버튼. 같은 로그가 나와야 정상.

- **강제 종료** (taskkill) 시에는 shutdown hook 이 실행되지 **않을 수 있으니** 가능하면 피할 것.

---

## 4. 테스트 클라이언트로 메시지 주고받기

Phase 2 성공 기준("CLI 2개로 A→B 메시지 전달") 재현 방법.

### 4.1 온라인 시나리오 — 기본

서버가 떠 있는 상태에서 **새 터미널 2개** 를 연다.

**터미널 1 (alice — 수신 대기)**
`bat
cd /d <repo>\OsoriMessenger\test-client
set JAVA_HOME=<repo>\.tools\jdk-17.0.12+7
.\gradlew.bat run --args="localhost 7071 alice"
`
이렇게 나와야 정상:
`
[alice] localhost:7071 접속 시도
[alice] 로그인 요청 전송
[alice] 로그인 응답: status=OK token=fa9f7f79...
`

**터미널 2 (bob — alice 에게 한 줄 전송)**
`bat
cd /d <repo>\OsoriMessenger\test-client
set JAVA_HOME=<repo>\.tools\jdk-17.0.12+7
.\gradlew.bat run --args="localhost 7071 bob alice hello-from-bob"
`
bob 쪽 로그:
`
[bob] 로그인 응답: status=OK token=...
[bob] → alice : hello-from-bob
[bob] 전송 확인: result=DELIVERED serverMsgId=1a3328e3-...
`
alice 쪽 로그 (수신 자동 출력):
`
[alice] ← bob : hello-from-bob
`

→ **이게 뜨면 온라인 배달 OK.**

### 4.1.1 Presence / Heartbeat 자동 동작/011 신기능)

위 기본 시나리오만 띄워도 다음이 자동으로 동작합니다:

- **Presence**: bob 이 로그인하는 순간 alice 콘솔에 `[alice] *** bob ONLINE`. alice 가 종료하면 bob 콘솔에 `[alice] *** alice OFFLINE`. (PresenceBroadcaster 가 발사)
- **Heartbeat ACK**: test-client 가 30 초 주기로 자동 Heartbeat 송신. 서버에서 즉시 `HeartbeatAck` 받아 `[alice] heartbeat ack rtt=14ms serverTs=...` 콘솔 출력. RTT 측정 가능.
- **유휴 세션 리퍼**: 클라가 90 초 이상 무신호이면 서버의 `SessionReaper` 가 자동 close (heartbeat 가 정상 동작 시 절대 reap 되지 않음).

수동으로 heartbeat 끄려면 `--no-heartbeat` 옵션:
`bat
.\gradlew.bat run --args="localhost 7071 alice - - --no-heartbeat"
`

### 4.1.2 히스토리 동기화 신기능)

`--history N` 옵션으로 로그인 직후 `HistoryRequest(since=0, limit=N)` 송신:
`bat
:: alice 가 받은 메시지 중 최근 50건
.\gradlew.bat run --args="localhost 7071 alice - - --history 50"
`

응답 콘솔 출력 예:
`
[alice] HistoryRequest(limit=50) 송신
[alice] 히스토리 응답: 1 건 (has_more=false)
  · bob → oc013-message-from-bob
`

서버 정책: 본인이 **수신자였던** 메시지만 (`to_user = alice`), `server_ts` 오래된 순, `limit` 기본 50 / 상한 500. 발신 메시지는 미포함 (Phase 6 확장 예정).

### 4.2 오프라인 + 재접속 flush 시나리오

1. alice 터미널 `Ctrl+C` 로 종료 (= alice 오프라인)
2. **새 터미널 (carol)** 에서 오프라인 alice 에게 전송
   `bat
   cd /d <repo>\OsoriMessenger\test-client
   .\gradlew.bat run --args="localhost 7071 carol alice offline-msg-from-carol"
   `
   carol 로그에 `result=QUEUED` 가 찍혀야 정상 (alice 가 오프라인이라 outbox 에만 적재됨).
3. HeidiSQL 또는 `psql` 로 확인:
   `sql
   SELECT to_user, delivered, created_at FROM message_outbox;
   -- alice | f | 2026-... 한 줄 나와야 함
   `
4. alice 다시 기동:
   `bat
   .\gradlew.bat run --args="localhost 7071 alice"
   `
   → alice 로그인 직후 자동으로:
   `
   [alice] ← carol : offline-msg-from-carol
   `
5. 다시 확인:
   `sql
   SELECT to_user, delivered FROM message_outbox;
   -- alice | t   ← delivered 가 true 로 바뀌어야 정상
   `

→ 이 경로가 `OutboxFlusher` + `LoginHandler` 통합 ~ 의 엔드투엔드 증거.

---

## 5. 서버 클래스 맵 (src/main/kotlin/com/osori/server)

**패키지 구조**
`
com.osori.server
├── app         ← Ktor 기동 / DI 배선 / shutdown hook
├── transport   ← WebSocket 프레임 ↔ Envelope 디스패치
├── auth        ← 로그인 플로우
├── message     ← 메시지 라우팅 + outbox flush
├── session     ← 접속 세션 관리
└── db          ← HikariCP + 순수 JDBC Repository
`

**파일별 한 줄 용도**

| 파일 | 용도 |
|---|---|
| `app/Main.kt` | 엔트리포인트. 환경변수 → DB 풀 초기화 → Repository/Handler 조립 → Ktor embeddedServer(Netty) 기동. ** graceful shutdown hook** + **SessionReaper start/stop hook** 여기. |
| `transport/WebSocketHandler.kt` | `/ws` 라우트. 매 프레임 수신 시 `lastSeenAtMillis` 갱신. `Envelope` 파싱 → `payloadCase` 에 따라 `loginHandler / messageRouter / historyHandler / heartbeat→ack / logout` 에 디스패치. logout/finally 에서 `presenceBroadcaster.notifyOffline` 호출. |
| `auth/LoginHandler.kt` | `LoginRequest` 처리. 빈 userId 거부 → STRICT: 빈 pw 거부 + 첫 로그인 해시 저장 / 재로그인 Argon2 검증) → UserRepository 자동 생성 → 기존 세션 있으면 "중복 로그인" 처리로 기존 세션 닫음 → SessionManager 바인딩 → `LoginResponse(status=OK, token=...)` 전송 → **PresenceBroadcaster.sendInitialSnapshot** + **notifyOnline** + **OutboxFlusher.flush(userId)** 호출. |
| `auth/PasswordHasher.kt` | Argon2id 싱글톤. `hash(rawPassword)` / `verify(storedHash, rawPassword)`. 파라미터: iterations=3, memory=64MiB, parallelism=1 (OWASP 2024 권장). 사용 후 raw password char array 는 0 으로 wipe. |
| `auth/AuthMode` | enum — `OPEN`(MVP 무인증) / `STRICT`(Argon2id 검증). `fromEnv(value)` 로 `OSORI_AUTH_MODE` 파싱. |
| `message/MessageRouter.kt` | `SendMessage` 처리. 비인증 / blank target 검사 → 수신자 온라인이면 `insertMessage` + 상대에게 `MessageReceived` push + 발신자에게 `SendMessageAck(result=DELIVERED)` / 오프라인이면 `saveOfflineMessage` 트랜잭션(본문+outbox) + `SendMessageAck(result=QUEUED)`. |
| `message/OutboxFlusher.kt` | 재접속 시 호출. `MessageRepository.fetchOutbox` 로 미배달 목록을 가져와 각 메시지를 `MessageReceived` 프레임으로 전송, 성공 시 `markDelivered`. 개별 전송 실패가 전체 플러시를 막지 않도록 try-catch 분리. 테스트용 `OutboxStore` 인터페이스 노출. 시퀀스 영역 `2_000_000+`. |
| `message/HistoryHandler.kt` | `HistoryRequest` 처리. `HistoryStore` 인터페이스를 통해 `MessageRepository.fetchRecentForUser` 호출. `limit` 보정 (default 50 / max 500) + `limit+1` 조회로 `has_more` 판정. 본인 수신 메시지만, 오래된 순. |
| `message/MessageRouter.kt` 의 `idempotencyCache` | `ConcurrentHashMap<"fromUser\|clientMsgId", CachedAck>` 멱등 캐시. 같은 키 두 번째 호출 시 기존 ack 그대로 재전송, DB/수신자 재배달 회피. 분산 환경에선 깨지므로 향후 unique 인덱스로 이관 예정 |
| `message/TokenBucket.kt` + `RateLimitPolicy` | 송신 빈도 제한. `TokenBucket` 은 capacity + refillPerSecond 기반 선형 refill + synchronized. `RateLimitPolicy` sealed interface → `Disabled` (기본, 회귀 없음) / `PerUserTokenBucket` (`ConcurrentHashMap<userId, TokenBucket>`). MessageRouter handleSend 진입 시 check → 초과 시 `RATE_LIMITED` ack |
| `session/SessionManager.kt` | `ConcurrentHashMap<String, ClientSession>` 으로 userId → 세션 매핑. `bind` / `get` / `remove(Boolean 반환)` / `count` / `snapshot` / `onlineUserIds`. `ClientSession` 인터페이스(`lastSeenAtMillis` 포함) + `WsClientSession` 실구현. |
| `session/SessionReaper.kt` | 백그라운드 coroutine. 30 초 주기로 sessions 스냅샷 순회, `now - lastSeen > idleTimeoutMillis(=90s)` 인 세션을 NORMAL close + remove. `Main.kt` 에서 `start` / `ApplicationStopped` 이벤트에 `stop` 등록. 테스트는 `clock` 주입으로 결정론적. |
| `session/PresenceBroadcaster.kt` | 사용자 온라인/오프라인 변경을 다른 접속자에게 브로드캐스트. `notifyOnline / notifyOffline / sendInitialSnapshot`. 자기 자신 제외. 시퀀스 영역 `3_000_000+`. |
| `db/Database.kt` | HikariCP 래퍼. `withConnection` / `inTransaction` 헬퍼. 환경변수 `OSORI_DB_URL/USER/PASS/POOL_SIZE` 로 `fromEnv` 팩토리. |
| `db/UserRepository.kt` | `auth_users` 테이블 CRUD. `findById`, `createIfAbsent(ON CONFLICT DO NOTHING)`, `ensureExists`(MVP 자동 가입), `count`, `getPasswordHash/setPasswordHash`. |
| `db/MessageRepository.kt` | `msg_messages` + `msg_outbox` 저장소. `insertMessage`, `insertOutbox`, `saveOfflineMessage`(트랜잭션), `fetchOutbox(toUser, limit)`, **`fetchRecentForUser(userId, sinceMillis, limit)`**, `markDelivered`, `countMessages`. |

**테스트 클래스 (src/test/kotlin)**

| 파일 | 테스트 수 | 검증 범위 |
|---|---|---|
| `session/SessionManagerTest` | 6 | bind / duplicate / remove / count / get |
| `session/SessionReaperTest` | 4 | 빈 매니저 / idle 초과만 reap / close 예외 무시 / 경계값 |
| `session/PresenceBroadcasterTest` | 6 | notifyOnline / notifyOffline / sendInitialSnapshot / 1인 케이스 / 시퀀스 영역 |
| `message/MessageRouterTest` | 5 | 비인증 / blank target / 오프라인 / 온라인 / sequence 매칭 |
| `message/OutboxFlusherTest` | 3 | 빈 아웃박스 / 전체 플러시 / 1건 실패해도 계속 |
| `message/HistoryHandlerTest` | 6 | 미인증 / null repo / has_more false / 잘림 / DEFAULT_LIMIT / MAX_LIMIT 캡 |
| `auth/LoginHandlerTest` | 4 | blank / 정상 / 중복로그인 / 다중 사용자 |
| `db/UserRepositoryIntegrationTest` | 5 | findById / createIfAbsent 중복 / ensureExists 자동생성 (실제 Postgres) |
| `db/MessageRepositoryIntegrationTest` | 10 | insertMessage / saveOfflineMessage 트랜잭션 / fetchOutbox 순서·limit / 수신자 분리 / markDelivered / FK 위반 / fetchRecentForUser 4 케이스 |
| `transport/WebSocketHandlerIntegrationTest` | 2 | Ktor testApplication 풀스택. Heartbeat→HeartbeatAck round-trip / 오프라인 SendMessage→QUEUED ack |
| **합계** | **54** | |

**와이어 스키마** — `proto/messenger.proto` (루트 하위). 9 메시지: `LoginRequest/Response`, `SendMessage/Ack`, `MessageReceived`, `Heartbeat / HeartbeatAck`, `Logout`, `PresenceEvent`, `HistoryRequest/Response`. 자세한 사양은 **`PROTOCOL.md`** 참조.

---

## 6. DB 접속 (HeidiSQL / psql)

### 6.1 HeidiSQL — PostgreSQL 계층 주의

**MariaDB 와 다른 점**: PostgreSQL 은 **database → schema → table** 3계층. HeidiSQL 에서 `pg_catalog / pg_toast / public / information_schema` 만 보이는 건 "지금 어느 DB 에 붙었고 그 안의 스키마들이 보이는" 상태. 우리 테이블은 **`osori` DB 의 `public` 스키마** 안에 있음.

**Session 설정 (F8 → Session Manager → New)**

| 필드 | 값 |
|---|---|
| Network type | **PostgreSQL (libpq)** |
| Hostname / IP | `localhost` |
| User | `postgres` |
| Password | (빈 값, trust 인증) |
| Port | `5432` |
| **Databases** | `osori;osori_test;postgres` (세미콜론, 비우면 전부 표시) |

저장 후 Open. 좌측 트리는 이렇게 보여야 함:
`
📁 세션명
 ├── 🗄 osori
 │    ├── information_schema
 │    ├── pg_catalog
 │    ├── pg_toast
 │    └── 📂 public          ← 클릭해서 펼치기
 │         ├── auth_users
 │         ├── auth_sessions
 │         ├── msg_messages
 │         └── msg_outbox
 ├── 🗄 osori_test
 │    └── public (같은 4 테이블)
 └── 🗄 postgres (기본 DB, 비어있음)
`

> **네이밍 컨벤션 (2026-04-14 v2 도입)**: `<도메인>_<복수형>`.
> `auth_*` = 인증/세션, `msg_*` = 1:1 쪽지 + outbox.
> 향후 예약: `talk_*` (그룹 채팅) / `file_*` (첨부) / `org_*` (조직도) / `log_*` (감사).

### 6.2 유용한 쿼리 10개

`sql
-- 1. 지금 내가 어느 DB 에 붙어있지?
SELECT current_database, current_schema;

-- 2. 사용자 전체
SELECT user_id, display_name, created_at FROM auth_users ORDER BY created_at;

-- 3. 오늘 오간 메시지 전부
SELECT from_user, to_user, text, server_ts
FROM msg_messages
ORDER BY server_ts DESC
LIMIT 50;

-- 4. 미배달 outbox (실시간 상태)
SELECT o.id, o.to_user, m.from_user, m.text, o.created_at
FROM msg_outbox o
JOIN msg_messages m ON m.message_id = o.message_id
WHERE o.delivered = false
ORDER BY o.created_at;

-- 5. 특정 사용자 수신함 (배달 여부 포함)
SELECT delivered, m.from_user, m.text, m.server_ts
FROM msg_outbox o
JOIN msg_messages m ON m.message_id = o.message_id
WHERE o.to_user = 'alice'
ORDER BY o.created_at DESC;

-- 6. 사용자별 보낸 메시지 수
SELECT from_user, COUNT(*) FROM msg_messages GROUP BY from_user;

-- 7. 사용자별 받은 메시지 수 (outbox 와 무관, 본문 기준)
SELECT to_user, COUNT(*) FROM msg_messages GROUP BY to_user;

-- 8. 최근 outbox 처리 속도 (배달 대기 시간)
SELECT to_user, delivered, created_at,
       EXTRACT(EPOCH FROM (now - created_at)) AS wait_seconds
FROM msg_outbox
ORDER BY created_at DESC LIMIT 20;

-- 9. 테이블 행 수 한눈에
SELECT 'auth_users' AS tbl, COUNT(*) FROM auth_users
UNION ALL SELECT 'auth_sessions', COUNT(*) FROM auth_sessions
UNION ALL SELECT 'msg_messages', COUNT(*) FROM msg_messages
UNION ALL SELECT 'msg_outbox', COUNT(*) FROM msg_outbox;

-- 10. 테스트 리셋용 (주의: 모든 데이터 삭제)
TRUNCATE TABLE msg_outbox, msg_messages, auth_sessions, auth_users RESTART IDENTITY CASCADE;
`

### 6.3 CLI 로 확인 (psql)

`bat
:: 운영 DB
<repo>\.tools\pg16\pgsql\bin\psql.exe -U postgres -h localhost -d osori

:: 테스트 DB
<repo>\.tools\pg16\pgsql\bin\psql.exe -U postgres -h localhost -d osori_test

:: 안에서 사용
\dt                  (테이블 목록)
\dt auth_*           (도메인별 — auth 테이블만)
\dt msg_*            (msg 테이블만)
\d auth_users        (스키마 상세)
SELECT * FROM auth_users;
\q                   (종료)
`

---

## 7. 환경변수 레퍼런스

서버 `Main.kt` 가 읽는 것들:

| 변수 | 기본값 | 의미 |
|---|---|---|
| `OSORI_PORT` | `7070` | WebSocket listen 포트. **7070 은 AnyDesk 충돌, 7071 권장** |
| `OSORI_HOST` | `0.0.0.0` | bind 주소 |
| `OSORI_DB_ENABLED` | `true` | `false` 면 메모리 전용 (DB 없이 기동) |
| `OSORI_DB_URL` | `jdbc:postgresql://localhost:5432/osori` | JDBC URL |
| `OSORI_DB_USER` | `osori` | 현재 세팅은 `postgres` 사용 (trust) |
| `OSORI_DB_PASS` | `osori_dev_pw` | 현재 세팅은 빈 값 (trust) |
| `OSORI_DB_POOL_SIZE` | `10` | HikariCP 최대 풀 크기 |
| `OSORI_LOG_FORMAT` | `text` | `json` 이면 logstash-logback-encoder 로 JSON 출력 |
| `OSORI_AUTH_MODE` | `open` | `strict` 면 Argon2id 기반 실인증 — 빈 password 거부, 첫 로그인 시 해시 저장, 이후 검증 |
| `OSORI_RATE_LIMIT_ENABLED` | `false` | `true` 면 사용자별 토큰 버킷 rate limit 활성화. **2026-09-11 부터 실제 운영 스크립트(`Tools/scripts/_env.cmd`)와 IntelliJ Run Config 기본값이 `true` 로 바뀜** — 코드 자체 기본값은 하위호환 위해 `false` 유지하지만, 이 저장소 표준 기동 경로로 띄우면 사실상 항상 활성 상태 (체크리스트 D2) |
| `OSORI_RATE_LIMIT_PER_USER_PER_SEC` | `5` | 활성 시 사용자당 capacity/refill 둘 다 이 값 (초당 N 건 + 버스트 N 건) |
| `OSORI_TLS_ENABLED` | `false` | `true` 면 wss 접속도 listen (ws 유지) |
| `OSORI_TLS_PORT` | `7443` | wss 포트 (TLS 활성 시) |
| `OSORI_KEYSTORE_PATH` | (없음) | JKS keystore 절대 경로. 기본: `<repo>\.tools\osori-tls\osori.jks` |
| `OSORI_KEYSTORE_PASSWORD` | (빈 값) | keystore 비밀번호. 개발용 기본: `osori_dev_pass` |
| `OSORI_KEY_ALIAS` | `osori` | keystore 안의 key alias |
| `OSORI_KEY_PASSWORD` | KEYSTORE 와 동일 | 개인 키 비밀번호 |

테스트에서만 쓰는 것 (`PostgresTestContainer.kt`):

| 변수 / 시스템 속성 | 기본값 |
|---|---|
| `OSORI_TEST_DB_URL` | `jdbc:postgresql://localhost:5432/osori_test` |
| `OSORI_TEST_DB_USER` | `postgres` |
| `OSORI_TEST_DB_PASS` | (빈 값) |

---

## 8. 자주 막히는 지점

| 증상 | 원인 | 해결 |
|---|---|---|
| `BindException: Address already in use` | **AnyDesk 가 7070 점유** 중 | `OSORI_PORT=7071` 로 변경 |
| `JAVA_HOME is set to an invalid directory: ...dk1.8.0_191` | JAVA_HOME 오타 (설치 당시 파일 경로 오타 상태로 남음) | `setx JAVA_HOME "<repo>\.tools\jdk-17.0.12+7"` 후 **새 터미널** 에서 재시도 |
| HeidiSQL 에 `pg_catalog / public / information_schema` 만 보임 | `postgres` 기본 DB 에 붙음, `osori` 아님 | Session Manager → Databases 필드에 `osori` 추가. 또는 상단 드롭다운에서 osori 선택. public 스키마 안에 우리 테이블 있음 |
| `docker compose up` 실패 (`hasNoVirtualization:true`) | BIOS VT-x/AMD-V 비활성 또는 WSL2/Hyper-V 기능 꺼짐 | **Docker 경로 자체 포기**. 이 프로젝트는 네이티브 Postgres portable 로 우회됨 (섹션 2 참조). 복구하려면 BIOS + `Enable-WindowsOptionalFeature` + 재부팅 필요. |
| `./gradlew test` 가 갑자기 느려짐 | Gradle daemon 충돌 또는 이전 프로세스 잔존 | `--no-daemon` 플래그 추가. 그래도 안 되면 `.\gradlew.bat --stop` 후 재시도 |
| `SQLException: FATAL: database "osori" does not exist` | 3.2 의 createdb 가 안 됐거나 Postgres 가 안 떠있음 | 3.1 로 가서 Postgres 기동 확인 → 3.2 실행 |
| 한글 로그가 `??? ????` 로 깨짐 | Windows cp949 콘솔 인코딩 (내용은 정상) | `chcp 65001` 로 UTF-8 콘솔 전환 또는 `OSORI_LOG_FORMAT=json` 으로 JSON 로그 사용 |

---

## 9. 다음 작업 후보 완료 이후 갱신)

- **Phase 3 잔여**: 코루틴 Mutex/Channel 체감 학습, Protobuf 스키마 변경 → 코드 재생성 흐름 자동화, 핫리로드 데모
- **Phase 5 C# 클라이언트**: Avalonia vs WPF 결정 후 `.NET 8` 프로젝트 스캐폴딩 → `Google.Protobuf` + `ClientWebSocket` 래퍼 → 로그인 창 + 단일 대화창. 이제 PROTOCOL.md 사양이 정리되어 클라이언트 책임이 명확.
- **인증 실구현**: 현재 MVP 는 무조건 로그인 OK. Argon2id 해시 + `password_hash` 검증 + 세션 토큰 재인증
- **TLS**: `ws://` → `wss://`. 자체 서명 keystore + Ktor `sslConnector` 또는 `applicationEngineEnvironment { sslConnector { ... } }`
- **proto 스키마 사용자 리뷰**: Phase 1 체크리스트에 미체크 상태. 에서 4개 추가됨 (`HeartbeatAck` / `PresenceEvent` / `HistoryRequest` / `HistoryResponse`)
- **메시지 동기화 확장**: HistoryRequest 는 현재 본인 수신만. 발신 메시지 + 대화방 단위 페이징 추가 (Phase 6)
- **Heartbeat 클라 자동 송신**: ✅ test-client 에 30s 주기 구현됨. C# 클라에도 동일 패턴 적용 필요
- **유휴 세션 리퍼**: ✅ SessionReaper 90s/30s 기본값으로 동작. 운영 환경에서 임계값 튜닝 필요
- **Docker 복구**: BIOS + WSL2 활성화 후 `PostgresTestContainer` 를 Testcontainers 싱글톤으로 되돌리기 (선택)
- **클라 멱등 키 (`client_msg_id`) 검증**: 재전송 시 중복 메시지 방지. MessageRepository 에 unique constraint 추가 검토

---

## 9.1 부록 — JSON 로그 모드 샘플/016)

`OSORI_LOG_FORMAT=json` 으로 기동하면 logback 의 `STDOUT_JSON` appender (`LogstashEncoder`) 가 활성. 한 줄 한 JSON. 실제 캡처 예 (한글은 콘솔 cp949 표시 시 깨질 수 있음, 파일/ELK 로 redirect 하면 정상):

`json
{"@timestamp":"2026-04-13T23:18:13.6273+09:00","@version":"1","message":"오소리 메신저 서버 기동 준비 host=0.0.0.0 port=7071 dbEnabled=true","logger_name":"com.osori.server.Main","thread_name":"main","level":"INFO","level_value":20000,"service":"osori-server"}
{"@timestamp":"2026-04-13T23:18:13.6588+09:00","@version":"1","message":"osori-db-pool - Starting...","logger_name":"com.zaxxer.hikari.HikariDataSource","thread_name":"main","level":"INFO","level_value":20000,"service":"osori-server"}
{"@timestamp":"2026-04-13T23:18:13.9294+09:00","@version":"1","message":"세션 리퍼 시작 idleTimeout=90000ms interval=30000ms","logger_name":"com.osori.server.SessionReaper","thread_name":"main","level":"INFO","level_value":20000,"service":"osori-server"}
{"@timestamp":"2026-04-13T23:18:13.9510+09:00","@version":"1","message":"오소리 메신저 서버 구성 완료 (DB=ON)","logger_name":"com.osori.server.Main","thread_name":"main","level":"INFO","level_value":20000,"service":"osori-server"}
{"@timestamp":"2026-04-13T23:18:14.5060+09:00","@version":"1","message":"Responding at http://127.0.0.1:7071","logger_name":"ktor.application","thread_name":"DefaultDispatcher-worker-1","level":"INFO","level_value":20000,"service":"osori-server"}
`

필드 의미:
- `@timestamp` — ISO 8601 + 타임존 (Asia/Seoul)
- `@version` — Logstash JSON 스키마 버전
- `message` — 로그 메시지 본문
- `logger_name` — 로거 이름 (`com.osori.server.*` 또는 외부 라이브러리)
- `thread_name` — 발생 스레드
- `level` / `level_value` — 로그 레벨 (INFO=20000, WARN=30000, ERROR=40000, DEBUG=10000)
- `service` — 커스텀 필드 (`logback.xml` 의 `customFields`). 다중 서비스 운영 시 ELK 로 필터링 가능

ELK 또는 Loki 로 보낼 때는 `STDOUT_JSON` 그대로 파일로 redirect 후 Filebeat 으로 수집하거나, `LogstashTcpSocketAppender` 로 직접 전송하는 옵션도 있음 (현재는 콘솔만 활성).

---

## 9.2 부록 — C# 클라이언트 빠른 레퍼런스 이후)

`client-csharp/` 아래에 **C# .NET 8 기반 클라이언트 스택**이 구성되어 있다. 세 프로젝트:

| 프로젝트 | 타입 | 역할 |
|---|---|---|
| `src/OsoriClient.Core` | 클래스 라이브러리 (net8.0) | `MessengerClient` WebSocket 래퍼 + Google.Protobuf 자동 생성 |
| `src/OsoriClient.Wpf` | WinExe (net8.0-windows) | 실전 WPF UI — LoginWindow + ChatWindow |
| `src/OsoriClient.SmokeTest` | Console (net8.0) | 엔드투엔드 검증 — alice/bob 로그인 + 송수신 + 히스토리 sync |

### 빌드
`powershell
cd <repo>\OsoriMessenger\client-csharp
& "C:\Program Files\dotnet\dotnet.exe" restore
& "C:\Program Files\dotnet\dotnet.exe" build
`


### WPF UI 실행
`powershell
cd <repo>\OsoriMessenger\client-csharp\src\OsoriClient.Wpf
& "C:\Program Files\dotnet\dotnet.exe" run
`
로그인 창 → 서버 호스트/포트/TLS 체크박스/사용자 ID/비밀번호 입력 → 접속 → ChatWindow 로 전환. 로그인 직후 자동 히스토리 sync (최근 50 건, `[HIST]` 접두사 표시).

### Smoke Test 실행
`powershell
cd <repo>\OsoriMessenger\client-csharp
& "C:\Program Files\dotnet\dotnet.exe" run --project src\OsoriClient.SmokeTest
`

검증 시나리오:
1. alice/bob 각자 WebSocket 연결 + 로그인
2. bob 이 `MessageReceived` 이벤트 구독
3. alice → bob 온라인 메시지 전송 (`DELIVERED` ack 확인)
4. bob 이 5초 내 실제 수신 확인
5. bob 로그아웃 + alice 가 offline bob 에게 메시지 전송 (`QUEUED` 확인)
6. bob 재로그인 + `GetHistoryAsync(0, 50)` 호출 → offline 메시지 복구 검증

Exit code: 0 = 성공 / 2 = 수신 실패 / 3 = 본문 불일치 / 4 = history 누락 / 5 = history 본문 불일치

### TLS (wss) 연결
WPF LoginWindow 에서 "wss 사용 (자체 서명 허용)" 체크 시 `wss://<호스트>:<포트>/ws` 로 접속. `ClientWebSocket.Options.RemoteCertificateValidationCallback` 이 자체 서명 인증서를 통과시키도록 설정됨. 운영 환경에서는 이 우회를 **제거**하고 유효한 인증서를 사용할 것.

### MessengerClient 주요 API
`csharp
public sealed class MessengerClient : IAsyncDisposable
{
    // 연결
    Task ConnectAsync(Uri serverUri, bool acceptSelfSignedTls, CancellationToken ct);

    // 인증
    Task<LoginResponse> LoginAsync(string userId, string password, string deviceId, CancellationToken ct);
    Task LogoutAsync(CancellationToken ct);

    // 메시지
    Task<SendMessageAck> SendMessageAsync(string toUserId, string text, CancellationToken ct);

    // 히스토리
    Task<HistoryResponse> GetHistoryAsync(long sinceMillis, int limit, CancellationToken ct);

    // Heartbeat (30초 주기)
    void StartHeartbeat;
    void StopHeartbeat;

    // 이벤트
    event HistoryReceivedEventHandler? HistoryReceived;     //
    event EventHandler<MessageReceivedEventArgs>? MessageReceived;
    event EventHandler<PresenceEvent>? PresenceChanged;
    event EventHandler<HeartbeatAck>? HeartbeatAckReceived;
}
`

### LastSyncStore — 로컬 증분 히스토리 pull 상태

`csharp
// 기본 경로: %LOCALAPPDATA%\OsoriClient\last_sync.json
var store = new LastSyncStore;            // 기본 경로
var customStore = new LastSyncStore("/tmp/osori-test.json");  // 테스트용

long lastTs = store.Get("alice");            // 없으면 0
store.Set("alice", msg.ServerTs);            // 단조 증가만 허용 — 과거 값 무시
store.Clear("alice");                        // 사용자 엔트리 제거
`

**특징**
- `System.Text.Json` 기반, UTF-8 JSON
- 파싱 실패 시 graceful recovery (빈 상태로 복구, 예외 전파 X)
- 단조 증가 가드로 동시성 순서 꼬임 방지
- 내부 `lock` 으로 멀티스레드 안전

**`ChatWindow` 통합**
`csharp
var chatWindow = new ChatWindow(messengerClient, userId, lastSyncStore);
// 또는
var chatWindow = new ChatWindow(messengerClient, userId);  // 기본 LastSyncStore 자동 생성
`

로그인 직후 `ChatWindow_OnLoaded` 가 store 기준 since 로 최대 10 페이지 증분 pull, `HasMore=true` 면 다음 페이지로. 실시간 수신 메시지도 자동으로 store 업데이트.

### 단위 테스트 프로젝트 →

`powershell
cd <repo>\OsoriMessenger\client-csharp
& "C:\Program Files\dotnet\dotnet.exe" test src\OsoriClient.Core.Tests\OsoriClient.Core.Tests.csproj
`

현재 테스트 (**18개**, 2026-04-14 기준):
- `LastSyncStoreTests` 4개: Get 초기 0 / Set+재로드 영속성 / Clear 제거 / 깨진 JSON graceful recovery
- `EnvelopeRoutingTests` 1개: HistoryResponse envelope → HistoryReceived 이벤트 sender/payload 검증
- `ReconnectBackoffTests` 8개: `ComputeBackoffDelayMs` — 5개 BeforeCap (1/2/4/8/16s) + 3개 AfterCap (30s)
- `SendRetryDelayTests` 5개: `ComputeRetryDelayMs` — 3개 BeforeCap (500/1000/2000ms) + 2개 AfterCap (3000ms)

`MessengerClient.DispatchEnvelopeCore` / `ComputeBackoffDelayMs` / `ComputeRetryDelayMs` 는 모두 `internal static` 순수 함수로 분리되어 WebSocket 없이 테스트 가능. `InternalsVisibleTo("OsoriClient.Core.Tests")` + `InternalsVisibleTo("OsoriClient.SmokeTest")` 에서 `ForceCloseSocketForTestAsync` 접근 목적).

### 자동 재연결 + 지수 백오프

`MessengerClient.AutoReconnectEnabled` (기본 true) 이면 WebSocket 수신 루프가 예외/Close 로 종료될 때 자동으로:
1. 새 `ClientWebSocket` 생성
2. `ConnectAsync` 재실행 (기존에 저장해둔 `_serverUri`/`_acceptSelfSignedTls`)
3. `LoginAsync` 재실행 (`_userId`/`_password`/`_deviceId`)
4. 성공 시 `Reconnected` 이벤트
5. 실패 시 지수 백오프: 1s → 2s → 4s → 8s → 16s → 30s (상한)
6. 최대 10회 후 `ReconnectFailed` 이벤트

`ChatWindow` 는 `Reconnected` 이벤트 구독해 `LastSyncStore` 기준 증분 히스토리 pull 자동 수행.

### SendMessage 자동 재시도 + 멱등성

`csharp
// 기본: 재시도 없음 (기존 호환)
var ack = await client.SendMessageAsync("bob", "hi", ct);

// 신규: 2회 재시도 + 동일 client_msg_id 유지
var ack = await client.SendMessageAsync("bob", "hi", maxRetries: 2, ct);
`

- 재시도 대상 예외: `WebSocketException`, `IOException`, 외부 ct 취소 아닌 `OperationCanceledException`
- 재시도 간 백오프: `ComputeRetryDelayMs(attempt)` = 500/1000/2000/3000(cap) ms
- 동일 `client_msg_id` 유지 → 서버 `MessageRouter` 의 멱등 캐시 (`ConcurrentHashMap<(fromUser, clientMsgId), CachedAck>`, 활용 → 중복 저장 방지

### Smoke Test 엔드투엔드 시나리오 →

`OsoriClient.SmokeTest/Program.cs` 는 서버가 떠 있는 상태에서 4단계 시나리오 검증:

1. **실시간 송수신** — alice/bob 로그인 + presence + alice→bob Delivered (exit 2/3: 수신 실패)
2. **Offline + 재접속 히스토리 복구** — bob 로그아웃 → alice→bob Queued → bob 재로그인 → `GetHistoryAsync(0, 50)` 결과에 offline 메시지 포함 (exit 4/5)
3. **LastSyncStore 영속성** — 임시 파일에 Get/Set/재로드/단조증가 보호/Clear/다른 사용자 비영향 검증 (exit 6)
4. **Reconnect 통합** — charlie 연결 + 로그인 → `ForceCloseSocketForTestAsync` 강제 종료 → 15초 내 `Reconnected` 이벤트 수신 → post-send Delivered 검증 (exit 7/8/9)

Exit code 0 = 전체 통과.

### 재접속 시 히스토리 sync 패턴
`csharp
// 로그인 직후
await client.ConnectAsync(uri, acceptSelfSigned, ct);
await client.LoginAsync(userId, password, deviceId, ct);
client.StartHeartbeat;

// 마지막 sync 시각을 로컬 저장 (예: %LOCALAPPDATA%\OsoriClient\last_sync.dat)
long lastSyncMillis = ReadLastSync ?? 0;
var history = await client.GetHistoryAsync(lastSyncMillis, 50, ct);

foreach (var msg in history.Messages)
{
    DisplayInChatWindow(prefix: "[HIST]", msg);
}

if (history.HasMore)
{
    // 50건 초과분 — 마지막 메시지 server_ts 기준으로 페이지네이션
    var nextFrom = history.Messages.Last.ServerTs;
    // 반복 호출...
}

// 완료 후 최신 server_ts 저장
WriteLastSync(history.Messages.LastOrDefault?.ServerTs ?? lastSyncMillis);
`

### 쪽지 답장 / 전체회신 / 전달 (2026-09 추가, 체크리스트 C4)

`MessageDetailWindow` 의 세 버튼은 **전부 클라이언트 전용 UI 편의 기능**이다 — 서버 프로토콜/DB 변경 없이 `MessageComposeWindow` 를 미리 채운 상태로 여는 것뿐이라 운영자가 별도로 신경 쓸 서버 설정은 없다.

- **답장** — 원본 발신자 1인을 수신자로 자동 채움 + 제목에 `답장: ` 접두
- **전체회신** — 원본이 1:N(그룹 쪽지)일 때만 활성화, 원본 수신자 전원(발신자 제외)을 수신자로 채움. 1:1 쪽지에서는 버튼 비활성화(`IsEnabled=false`)
- **전달** — 수신자는 비워둔 채 제목에 `전달: ` 접두 + 본문에 원본 발신자/시각/내용을 인용 형태로 삽입

세 기능 모두 `MessageComposeWindow` 생성자에 프리필 파라미터를 넘기는 방식으로 구현되어 있고, 실제 전송은 기존 `SendMemo` 경로를 그대로 탄다. 서버 로그/DB 관점에서는 신규 쪽지 발송과 구분되지 않는다.

### 트러블슈팅
| 증상 | 원인 | 해결 |
|---|---|---|
| `dotnet --version` 이 `TypeInitializationException` 으로 실패 | `Microsoft.TemplateSearch.Common.dll` 부재 | `dotnet --list-sdks` 로 대체하거나 `dotnet new/build/run` 직접 사용 (version 조회 skip) |
| PowerShell 5.1 에서 `Add-Type` 으로 OsoriClient.Core.dll 로드 실패 | PowerShell 5.1 은 .NET Framework, Core 는 .NET 8 | PowerShell 7+ 사용하거나 `dotnet run --project OsoriClient.SmokeTest` 로 검증 |
| WPF 로그인 후 ChatWindow 가 히스토리를 표시하지 않음 | 서버가 본인 수신함만 반환 MVP 정책) | 상대가 보낸 메시지는 상대 입장에선 발신 → 수신자인 나는 복구 됨. 내가 보낸 것은 Phase 6 에서 해결 |
| `wss://` 연결 거부 | 서버에 `OSORI_TLS_ENABLED=true` 안 켜짐 | `$env:OSORI_TLS_ENABLED = "true"` + 재기동, 로그의 `sslConnector listening` 확인 |

---

## 10. 운영 배포 가이드 (v0.1,

### 10.1 클라이언트 인스톨러 빌드

`cmd
cd installer
build-installer.bat
`

산출물: `installer\Output\OsoriMessenger-Setup-0.1.0.exe` (단일 파일, ~1MB).

사용자 PC 사전 조건:
- Windows 10 1809 (17763) 이상
- **.NET 8 Desktop Runtime** 사전 설치 — https://dotnet.microsoft.com/download/dotnet/8.0
  - 기업 환경은 GPO 배포 권장. 인스톨러는 self-contained 가 아님(용량 작게 유지).

설치 마법사 옵션:
- 시작 메뉴 항목 (필수)
- 바탕화면 아이콘 (선택, 기본 OFF)
- **Windows 시작 시 자동 실행** (선택, 기본 OFF) — `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`

업데이트: `installer\OsoriClient.iss` 의 `MyAppVersion` 만 올려서 재빌드. `AppId` GUID 고정이라 in-place upgrade.

### 10.2 서버 운영 환경변수 (v0.1 추가분)

| 변수 | 기본값 | 비고 |
|---|---|---|
| **`OSORI_LOG_FILE_ENABLED`** | false | true 시 회전 파일 어펜더 활성 |
| **`OSORI_LOG_DIR`** | ./logs | `osori-server.log` + 회전 백업 |

운영 권장:
`bash
OSORI_LOG_FILE_ENABLED=true \
OSORI_LOG_DIR=/var/log/osori \
OSORI_LOG_FORMAT=json \
./gradlew run
`

### 10.3 로그 운영

`OSORI_LOG_FILE_ENABLED=true` 일 때:
- 파일: `${OSORI_LOG_DIR}/osori-server.log`
- 회전: **10MB 도달 시** 또는 **일자 변경 시** `osori-server.YYYY-MM-DD.N.log.gz` 압축 백업
- 보존: 최근 5개 + 총 60MB 상한
- 백업 5개 초과되거나 총량 초과 시 가장 오래된 것부터 자동 삭제

### 10.4 헬스체크 + 메트릭 endpoint

`bash
curl -fsS http://localhost:7070/health
# {"status":"ok","sessions":3,"db":"up","version":"0.1.0","uptime_sec":1234,"mem_used_mb":120,"mem_max_mb":8044}

curl -fsS http://localhost:7070/metrics
# # TYPE osori_messages_sent_total counter
# osori_messages_sent_total 42
# ...
# Prometheus exposition format (v0.0.4)
/health` 필드:
- `sessions` — 현재 활성 WebSocket 세션 수
- `db` — `up` (UserRepository 활성) / `down` (메모리 모드)
- `uptime_sec` — JVM 기동 후 경과 초
- `mem_used_mb` / `mem_max_mb` — JVM 힙

`/metrics` 주요 시리즈:
- 카운터: `osori_messages_{sent,delivered,queued,rate_limited}_total`, `osori_memo_{sent,pushed,read,deleted}_total`, `osori_room_{messages_sent,broadcasts}_total`, `osori_files_{uploaded,downloaded}_total`, `osori_login_{success,failure}_total`
- 게이지: `osori_session_count`, `osori_disk_usable_ratio_per_mille`, `osori_uptime_seconds`, `osori_build_info{version="0.1.0"}`

Grafana 대시보드 템플릿 — `docker/grafana-osori-dashboard.json` (7 패널)

### 10.4a 클라이언트 자동업데이트 (2026-09-11 추가, 체크리스트 D7)

v0.1 출시 이후 추가된 기능. `/client/version` / `/client/download` 두 라우트로 배포판 갱신을 자동화한다.

`bash
curl -fsS http://localhost:7070/client/version
# {"latestVersion":"0.1.1","downloadUrl":"/client/download"}

curl -fsS -o setup.exe http://localhost:7070/client/download
`

서버 환경변수:

| 변수 | 기본값 | 의미 |
|---|---|---|
| `OSORI_CLIENT_LATEST_VERSION` | `0.1.0` | `/client/version` 이 응답하는 최신 버전 문자열 |
| `OSORI_CLIENT_INSTALLER_PATH` | (없음) | 설치 파일 실경로. 미설정 시 `/client/download` 404 |

클라이언트 동작(`MainWindow.CheckForUpdateAsync`):
- 로그인 완료 직후(`MainWindow_OnLoaded`) 백그라운드로 `/client/version` 조회
- 서버 버전이 csproj `<Version>` 보다 높으면 `MessageBox` Yes/No 확인 → 동의 시 `/client/download` 로 설치 파일을 temp 에 받아 `/SILENT /NORESTART /CLOSEAPPLICATIONS` 로 실행 후 앱 종료 (Inno Setup AppId 고정 → in-place 업그레이드)
- 네트워크 오류/구버전 서버 등은 전부 조용히 무시 — 로그인 플로우를 절대 막지 않음

**운영 절차**: 새 버전 배포 시 (1) `installer/build-installer.bat` 로 새 설치본 빌드 (2) 서버 `OSORI_CLIENT_INSTALLER_PATH` 를 새 파일 경로로, `OSORI_CLIENT_LATEST_VERSION` 을 새 버전으로 갱신 후 서버 재시작 (3) 이후 사용자들이 로그인할 때마다 자동으로 업데이트 안내를 받음. 사내 공유폴더 배포는 더 이상 필요 없음.

### 10.4b Schema sanity 자동 체크

서버 기동 직후 `information_schema.tables` 를 SELECT 해서 14개 필수 테이블이 존재하는지 검증.
- OK: `INFO DB schema sanity OK — 모든 필수 테이블 존재`
- 누락 시: `ERROR 🟠 DB schema 누락 테이블: [memos, ...] — schema.sql 적용 또는 마이그레이션 필요`

### 10.5 사용자 진단 로그 수집/252)

사용자가 "이상해요" 라고 했을 때:

1. **오소리 메신저 > 설정 탭 > "진단 로그 내보내기 (zip)"** 안내 → `OsoriMessenger-logs-YYYYMMDD-HHMMSS.zip` 저장됨
2. zip 안 구조:
   - `client-logs/crash.log` — 글로벌 에러 boundary 가 잡은 모든 미처리 예외 (스택 포함)
   - `meta.txt` — 클라 버전 / .NET 런타임 / OS 버전 / 사용자 ID / 생성 시각

직접 경로: `%LOCALAPPDATA%\OsoriMessenger\logs\`

### 10.6 흔한 운영 장애 & 대응

| 증상 | 원인 후보 | 1차 조치 |
|---|---|---|
| 클라가 "연결 끊김" 배너 깜빡임 | 서버 죽음 / 네트워크 흔들림 | 서버 프로세스 확인 → 재기동 → 클라 자동 재연결 |
| "쪽지가 안 와요" | 푸시 토큰 만료 / 비멤버 / 오프라인 누적 미flush | 서버 로그 `memo push 실패` grep + 재로그인 (`flushUndelivered` 가 누적 일괄 푸시) |
| 메모리 사용량 증가 | 세션 핸들러 누수 / DB 풀 leak | `SELECT count(*) FROM pg_stat_activity` |
| TLS 핸드셰이크 실패 | keystore 만료 | `keytool -list -keystore $OSORI_KEYSTORE_PATH` |
| 디스크 가득참 | 업로드 파일 누적 | `$OSORI_FILE_STORAGE` 정리 + 보존 정책 도입 (v0.2) |
| 트레이만 떠있고 창 없음 | Mutex / 자동시작 / 로그인 실패 | 트레이 더블클릭으로 창 복귀 |

### 10.6.1 운영자 비밀번호 재설정

사용자가 STRICT 모드 비밀번호를 잊었을 때:

`bash
cd server
./gradlew passwordReset --args="alice TempPass123!"
`

- `auth_users.password_hash` 가 Argon2id 새 해시로 갱신
- 임시 비밀번호는 별도 채널(전화/사내메일) 로 전달
- 다음 로그인 시 사용자가 직접 변경하는 UI 는 v0.2

### 10.6.1b 표시명 변경 부분)

`bash
./gradlew setDisplayName --args="alice 김알리"
`

운영자 단독 — 사용자 본인이 직접 변경하는 UI 는 v0.2.

### 10.6.2 감사 로그 검색

`bash
./gradlew auditSearch --args="auth.login.failed 100"
./gradlew auditSearch --args="room.created 50"
`

TSV 출력 — grep / awk / Excel 후처리 가능. 주요 `event_type`:

| event_type | 설명 |
|---|---|
| `auth.login.success` / `auth.login.failed` | 로그인 결과 |
| `auth.logout.explicit` / `auth.logout.implicit` | 명시적 / 연결 끊김 로그아웃 |
| `room.created` | 방 생성 |
| `room.member.added` | 멤버 추가 |
| `file.upload.completed` | 파일 업로드 완료 |

### 10.6.3 Rate limit 면제 사용자

운영 계정/봇 등이 rate limit 에 안 걸리도록:

`bash
OSORI_RATE_LIMIT_ENABLED=true \
OSORI_RATE_LIMIT_PER_USER_PER_SEC=5 \
OSORI_RATE_LIMIT_EXEMPT_USERS=admin,system-bot \
./gradlew run
`

쉼표/공백 구분. 기동 시 로그에 면제 목록 출력.

### 10.6.4 첨부 보존 + 디스크 알람/411)

- `OSORI_FILE_RETENTION_DAYS` (기본 90) — 보존 기간 초과 첨부는 6시간마다 자동 cleanup
- `OSORI_DISK_ALARM_RATIO` (기본 0.05 = 5%) — 디스크 잔여 비율이 이 값 미만이면 WARN 로그
- Prometheus `osori_disk_usable_ratio_per_mille` 게이지로 외부 모니터링

### 10.7 백업 / 복원 (수동, 정식 작업 전까지 임시)

`bash
# 백업
pg_dump -U osori -F c osori > osori-$(date +%Y%m%d).dump

# 복원 (테스트 DB 로 먼저 검증)
pg_restore -U osori -d osori_restore osori-YYYYMMDD.dump
`

### 10.8 QA 체크리스트

릴리스 전 `QA_Checklist_v0.1.md` 의 60+ 시나리오 통과 필요. P0/P1 0건 + P2 5건 이하가 출시 기준.

---

## 11. 참고 문서

- **`PLAN.md`** — 전체 Phase 로드맵과 기술 스택 결정 근거
- **`_Progress.md`** — 태스크 단위 진행 로그 (SSOT). 어떤 OC-NNN 이 언제 끝났는지
- **`_Roadmap_v0.1.md`** — v0.1 출시 OC 84개 트래커
- **`QA_Checklist_v0.1.md`** — 1인 QA 60+ 시나리오
- **`PROTOCOL.md`** — 와이어 프로토콜 사양 (Envelope / 9 메시지 / 시퀀스 정책 / 시나리오 다이어그램). 새 클라이언트 만들 때 첫 번째로 읽을 문서.
- **`HOW_TO_RUN.md`** — 이 GUIDE 의 축약본 (빠른 참조용)
- **`installer/README.md`** — Inno Setup 인스톨러 빌드 절차/241/242)
- **`db/README.md`** — DB 스키마 설계 배경 / 분리 정책 / 인덱스 선택 근거

---

**이 문서는 살아있는 문서다.** 명령/경로/클래스가 바뀌면 이 문서도 업데이트할 것. 특히 섹션 5 (클래스 맵) 와 섹션 7 (환경변수) 는 코드 변경과 싱크 유지 중요.

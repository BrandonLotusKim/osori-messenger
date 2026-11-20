# 오소리 메신저 — 빌드 및 실행 (빠른 참조)

> 이 문서는 한 페이지 cheat sheet. 자세한 가이드는 **`GUIDE.md`** 참조.
> 와이어 프로토콜 사양은 **`PROTOCOL.md`** 참조.

---

## 사전 요구사항 (이미 세팅됨)

| 항목 | 경로 |
|---|---|
| JDK 17 | `<repo>\.tools\jdk-17.0.12+7` (JAVA_HOME setx 완료) |
| PostgreSQL 16.6 portable | `<repo>\.tools\pg16\pgsql\` (data: `pg16\data`, log: `pg16\log\server.log`) |
| TLS keystore (옵션) | `<repo>\.tools\osori-tls\osori.jks` (storepass `osori_dev_pass`, alias `osori`) |
| Gradle Wrapper | `server/gradlew.bat` + `test-client/gradlew.bat` (이미 생성됨) |

확인:
`bat
java -version       :: 17 이상
.\gradlew.bat -v    :: 8.5
`

---

## Postgres 기동/정지

`bat
:: 기동
<repo>\.tools\pg16\pgsql\bin\pg_ctl.exe -D <repo>\.tools\pg16\data -l <repo>\.tools\pg16\log\server.log start

:: 정지
<repo>\.tools\pg16\pgsql\bin\pg_ctl.exe -D <repo>\.tools\pg16\data stop

:: 운영 DB 접속 확인
<repo>\.tools\pg16\pgsql\bin\psql.exe -U postgres -h localhost -d osori -c "\dt"
`

DB 가 두 개: `osori` (운영), `osori_test` (gradle test 가 매번 TRUNCATE).

---

## 서버 실행

### 평문 ws 만 (기본)

`bat
cd /d <repo>\OsoriMessenger\server

set OSORI_DB_USER=postgres
set OSORI_DB_PASS=
set OSORI_PORT=7071

.\gradlew.bat run
`

> **포트 7071 권장**: 7070 은 AnyDesk 가 점유.

성공 로그:
`
INFO  ktor.application - Responding at http://127.0.0.1:7071
`

### TLS wss 동시 (옵션,

`bat
cd /d <repo>\OsoriMessenger\server

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

성공 로그 — ws + wss 둘 다 listen:
`
INFO  ktor.application - Responding at http://127.0.0.1:7071
INFO  ktor.application - Responding at https://127.0.0.1:7443
`

### JSON 로그 모드 (옵션,

`bat
set OSORI_LOG_FORMAT=json
@timestamp / level / logger_name / thread_name / message / service=osori-server` 한 줄 한 JSON 출력 (logstash-logback-encoder).

### 핫리로드 (개발 모드,

`bat
set OSORI_DEV_MODE=true
.\gradlew.bat run
`

Ktor `developmentMode=true` + `watchPaths=[classes,resources]` 활성화. `compileKotlin` 결과가 갱신되면 서버가 자동 재로드한다. 운영 빌드에서는 절대 켜지 말 것 (성능/보안 영향). 로그에 `개발 모드 ON — Autoreload 활성화` 가 뜨면 ON.

---

## Protobuf 재생성

`proto/messenger.proto` 를 수정한 뒤에는 Kotlin 서버와 C# 클라 양쪽을 한꺼번에 재생성:

`bash
# bash (Git Bash)
./scripts/regen-proto.sh           # 양쪽 다
./scripts/regen-proto.sh --kotlin  # 서버만
./scripts/regen-proto.sh --csharp  # 클라만
bat
:: Windows 배치
scripts\regen-proto.bat
scripts\regen-proto.bat --kotlin
scripts\regen-proto.bat --csharp
`

Kotlin 측은 `protobuf-gradle-plugin` 의 `generateProto` 태스크가 `server/build/generated/source/proto/main/java/com/osori/proto/v1/*.java` 를 갱신하고, C# 측은 `Grpc.Tools` 가 `OsoriClient.Core` 빌드 시점에 `obj/Debug/net8.0/Messenger.cs` 를 자동으로 갱신한다. 단일 진실 원천: `proto/messenger.proto`.

---

## 테스트 클라이언트 실행

### 기본 (ws + 자동 30s heartbeat)

**터미널 1 — alice 수신 대기**
`bat
cd /d <repo>\OsoriMessenger\test-client
.\gradlew.bat run --args="localhost 7071 alice"
`

**터미널 2 — bob 이 alice 에게 메시지**
`bat
cd /d <repo>\OsoriMessenger\test-client
.\gradlew.bat run --args="localhost 7071 bob alice hello-from-bob"
`

기대 로그 (alice):
`
[alice] 로그인 응답: status=OK token=...
[alice] *** bob ONLINE       (Presence —
[alice] ← bob : hello-from-bob
[alice] heartbeat ack rtt=14ms (30초 후 —
`

### 옵션

`bat
:: 히스토리 50건
.\gradlew.bat run --args="localhost 7071 alice - - --history 50"

:: heartbeat 끄기
.\gradlew.bat run --args="localhost 7071 alice - - --no-heartbeat"

:: wss 접속
.\gradlew.bat run --args="localhost 7443 alice - - --tls"
`

---

## 테스트 실행

`bat
cd /d <repo>\OsoriMessenger\server
.\gradlew.bat test
`

**현재 67 tests 통과** (단위 + 통합 + Postgres + Ktor testApplication 풀스택 + Argon2id 인증 + Rate limit).

### STRICT 인증 모드로 기동
`bat
set OSORI_AUTH_MODE=strict
.\gradlew.bat run
`
- 빈 password 거부 (`--pw` 옵션 필수)
- 첫 로그인 시 password 해시 저장 → 이후 Argon2 검증
- test-client 사용: `.\gradlew.bat run --args="localhost 7071 alice - - --pw mypassword"`

---

## 환경변수 한눈에

| 변수 | 기본 | 비고 |
|---|---|---|
| `OSORI_PORT` | 7070 | ws 포트. 7071 권장 |
| `OSORI_HOST` | 0.0.0.0 | bind |
| `OSORI_DB_ENABLED` | true | false 면 메모리 전용 |
| `OSORI_DB_URL` | jdbc:postgresql://localhost:5432/osori | |
| `OSORI_DB_USER` | osori | 현재 세팅: `postgres` (trust) |
| `OSORI_DB_PASS` | osori_dev_pw | 현재 세팅: 빈 값 |
| `OSORI_DB_POOL_SIZE` | 10 | HikariCP |
| `OSORI_LOG_FORMAT` | text | `json` 으로 JSON 출력 |
| `OSORI_AUTH_MODE` | open | `strict` = Argon2id 실인증 |
| `OSORI_RATE_LIMIT_ENABLED` | false | true = 사용자별 토큰 버킷 |
| `OSORI_RATE_LIMIT_PER_USER_PER_SEC` | 5 | 활성 시 초당 + 버스트 상한 |
| `OSORI_TLS_ENABLED` | false | `true` 면 wss listen |
| `OSORI_TLS_PORT` | 7443 | |
| `OSORI_KEYSTORE_PATH` | (없음) | TLS 활성 시 필수 |
| `OSORI_KEYSTORE_PASSWORD` | 빈 값 | |
| `OSORI_KEY_ALIAS` | osori | |
| `OSORI_KEY_PASSWORD` | KEYSTORE_PASSWORD 와 동일 | |
| `OSORI_DEV_MODE` | false | `true` 면 Ktor developmentMode + autoreload watchPaths. 운영 금지 |

---

## 자주 막히는 지점

| 증상 | 원인 / 해결 |
|---|---|
| `Address already in use 7070` | AnyDesk 점유 → `OSORI_PORT=7071` 사용 |
| `JAVA_HOME ...dk1.8.0_191` | 오타 + Java 17 미설치 → `setx JAVA_HOME "<repo>\.tools\jdk-17.0.12+7"` 후 새 터미널 |
| HeidiSQL 에 `pg_catalog/public/...` 만 보임 | PostgreSQL 의 db→schema→table 3계층 차이. Session Manager → Databases 에 `osori;osori_test` 명시. public 스키마 펼치기 |
| `database "osori" does not exist` | Postgres 가 안 떠있거나 DB 미생성. GUIDE.md §3.1, §3.2 참조 |
| 한글 로그가 콘솔에서 깨짐 | Windows cp949. 파일/ELK 로 redirect 하면 정상. `chcp 65001` 로 콘솔 전환 가능 |

자세한 troubleshooting: **`GUIDE.md` §8** 참조.

---

## WPF 클라이언트 실행 (v0.1)

### 개발 모드 (소스에서 직접)

`bat
cd /d <repo>\OsoriMessenger\client-csharp\src\OsoriClient.Wpf
"C:\Program Files\dotnet\dotnet.exe" run
`

### 빌드된 exe 직접 실행

`bat
"<repo>\OsoriMessenger\client-csharp\src\OsoriClient.Wpf\bin\Debug\net8.0-windows\OsoriClient.Wpf.exe"
`

### 자동 로그인 (테스트용)

`bat
set OSORI_AUTO_LOGIN=alice
"...OsoriClient.Wpf.exe"
`

### Smoke E2E

`bat
cd /d <repo>\OsoriMessenger\client-csharp
"C:\Program Files\dotnet\dotnet.exe" run --project src\OsoriClient.SmokeTest
`

10단 시나리오 (로그인 / 송수신 / 오프라인큐 / 재연결 / 파일 / 조직도 / 방 / 검색 / 쪽지 / 방히스토리) 자동 검증.

---

## 인스톨러 빌드 (배포용 .exe)

`cmd
cd <repo>\OsoriMessenger\installer
build-installer.bat
`

산출물: `installer\Output\OsoriMessenger-Setup-0.1.0.exe`. 사전 조건: Inno Setup 6 설치 (https://jrsoftware.org/isdl.php). 상세는 `installer\README.md`.

---

## Docker compose 운영 프로필 (v0.1)

`bash
cp docker/.env.example docker/.env   # 비번/TLS 채우기
docker compose -f docker/docker-compose.prod.yml --env-file docker/.env up -d
`

구성: Postgres + Server(installDist 기반 멀티스테이지) + pgbackup sidecar (매일 03:15 KST 자동 pg_dump). 상세는 `docker/README.md`, TLS 발급은 `docker/TLS.md`.

---

## 추가 환경변수 (v0.1)

| 변수 | 기본 | 비고 |
|---|---|---|
| `OSORI_LOG_FILE_ENABLED` | false | true 면 logback 회전 파일 어펜더 활성 |
| `OSORI_LOG_DIR` | ./logs | 회전 백업 위치 — `osori-server.log` + `.YYYY-MM-DD.N.log.gz` |

---

## 진단 로그 수집 (사용자에게서 받기)

1. 클라이언트 > 설정 탭 > **"진단 로그 내보내기 (zip)"** 클릭
2. 사용자가 `OsoriMessenger-logs-*.zip` 저장 → 운영자에게 전달
3. zip 안: `client-logs/crash.log` + `meta.txt`

직접 위치: `%LOCALAPPDATA%\OsoriMessenger\logs\`

---

## 참고 문서

- `GUIDE.md` — 전체 가이드 (§10 운영 배포 가이드 포함)
- `PROTOCOL.md` — 와이어 프로토콜 사양 (§13 RoomHistory, §14 Memo 포함)
- `_Progress.md` — 태스크 진행 로그 (SSOT)
- `_Roadmap_v0.1.md` — v0.1 출시 84 OC 트래커
- `QA_Checklist_v0.1.md` — 1인 QA 60+ 시나리오
- `PLAN.md` — Phase 로드맵
- `db/README.md` — DB 스키마 설계 근거
- `installer/README.md` — Inno Setup 인스톨러 빌드
- `docker/README.md` — Docker compose 운영
- `docker/TLS.md` — TLS 인증서 발급 / 적용

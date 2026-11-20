# 오소리 메신저 (Osori Messenger)

사내 메신저를 Kotlin 서버 + C# WPF 클라이언트로 처음부터 설계·구현한 개인 프로젝트.

## 현재 상태

**v0.1 출시 직전** — 기능 풀스택 완성 + 출시 패키지(인스톨러/로그/QA/운영문서) 완비. 사용자 1인 QA 단계 진입.

| Phase / 마일스톤 | 상태 | 주요 결과물 |
|---|---|---|
| Phase 0~6 (서버 풀스택) | ✅ 완료 | Ktor WebSocket + PostgreSQL + 인증/TLS/Rate limit/Audit/Push |
| C# 클라이언트 Phase D | ✅ 완료 | WPF dark+yellow 카톡식 UI, MainWindow 4 탭(조직도/대화/쪽지/설정), ConversationWindow, MemoCompose/Detail/Picker |
| M1-E ** RoomHistory** | ✅ 완료 | 그룹채팅 히스토리 풀스택 — proto/handler/멤버체크/페이지네이션/WPF 무한스크롤 |
| M2-F **쪽지** | ✅ 완료 | 9개 메시지 + 1:N 발송 + 오프라인 누적 flush + 받은/보낸함 + 읽음/삭제 + Smoke 9b |
| M2 폴리시/172/203/211/129b/201) | ✅ 완료 | 다운로드 진행률 / 인앱 토스트 / 탭 슬라이드 / 폰트 fallback / 트레이 빨간점 / 메시지 등장 애니메이션 |
| M3-A 인스톨러/241/242) | ✅ 완료 | Inno Setup `.iss` + `build-installer.bat` + 자동시작/제거 다이얼로그 |
| M3-B 로그/진단/에러/251/252) | ✅ 완료 | logback 회전 10MB×5 / 진단 zip 내보내기 / 글로벌 에러 boundary + crash.log |
| M3-C QA 시나리오 | ✅ 완료 | `QA_Checklist_v0.1.md` 60+ 시나리오 |
| M3-E 운영 인프라 | ✅ 완료 | Docker compose prod + TLS 가이드 + pgbackup sidecar + GUIDE §10 |
| M3-D 잡버그 | ⏳ 사용자 1인 QA 결과 대기 | |

**테스트 현황** (모두 통과):
- Kotlin 통합 13/13 + RoomRepo 8/8 + 기타 단위/통합 합산 200+
- C# Core 62/62
- Smoke E2E **10/10** — 로그인 / 송수신 / 오프라인큐 / 자동재연결 / 파일 / 조직도 / 방생성 / 메시지검색 / **쪽지 9** / **방히스토리 9b**
- WPF 빌드 0 에러

## 빠른 시작

### 개발용 (호스트에서 서버 직접 기동)

`powershell
# 1) DB만 docker 로 띄움
docker compose -f docker/docker-compose.yml up -d

# 2) 서버 기동
$env:JAVA_HOME = "<repo>\.tools\jdk-17.0.12+7"
cd <repo>\OsoriMessenger\server
.\gradlew.bat run

# 3) WPF 클라이언트
cd <repo>\OsoriMessenger\client-csharp\src\OsoriClient.Wpf
& "C:\Program Files\dotnet\dotnet.exe" run
`

### 운영 (전체 스택 컨테이너)

`bash
cp docker/.env.example docker/.env   # 비밀번호/TLS 등 채우기
docker compose -f docker/docker-compose.prod.yml --env-file docker/.env up -d
`

상세는 [`docker/README.md`](./docker/README.md).

### Smoke E2E

`powershell
cd <repo>\OsoriMessenger\client-csharp
& "C:\Program Files\dotnet\dotnet.exe" run --project src\OsoriClient.SmokeTest
`

### 인스톨러 빌드 (Windows 사용자 배포용 .exe)

`cmd
cd installer
build-installer.bat
`

상세는 [`installer/README.md`](./installer/README.md).

## 파일 안내

| 파일 / 디렉토리 | 역할 |
|---|---|
| [`PLAN.md`](./PLAN.md) | 전체 계획, 아키텍처 결정, 기술 스택, 단계별 로드맵 |
| [`PROTOCOL.md`](./PROTOCOL.md) | **와이어 프로토콜 사양서** — 21개 메시지(§13 RoomHistory + §14 Memo 포함) |
| [`GUIDE.md`](./GUIDE.md) | 운영자 가이드 — 환경변수, DB 스키마, JSON 로그, TLS keystore, 진단 수집, 흔한 장애 대응 (§10) |
| [`HOW_TO_RUN.md`](./HOW_TO_RUN.md) | 최소 기동 절차 |
| [`_Progress.md`](./_Progress.md) | **SSOT**. 단계별 체크리스트 + OC-NNN 태스크 로그 |
| [`_Roadmap_v0.1.md`](./_Roadmap_v0.1.md) | v0.1 출시 84 OC 트래커 |
| [`QA_Checklist_v0.1.md`](./QA_Checklist_v0.1.md) | 1인 QA 60+ 시나리오 |
| [`ANNOUNCE_v0.1.md`](./ANNOUNCE_v0.1.md) | 출시 시점 1-pager 안내 (운영자/관리자 배포용) |
| [`RELEASE_NOTES_v0.1.md`](./RELEASE_NOTES_v0.1.md) | v0.1 출시 노트 상세 |
| [`CHANGELOG.md`](./CHANGELOG.md) | 변경 이력 (Keep a Changelog 형식) |
| [`_Roadmap_v0.2.md`](./_Roadmap_v0.2.md) | v0.2 백로그 (A~H 영역) |
| [`server/`](./server/) | Kotlin + Ktor 서버 |
| [`client-csharp/`](./client-csharp/) | C# .NET 8 WPF 클라이언트 + Core 라이브러리 + SmokeTest |
| [`proto/`](./proto/) | Protobuf 스키마 (서버/클라 공유) |
| [`docker/`](./docker/) | dev compose + prod compose + Dockerfile + TLS 가이드 + pgbackup |
| [`installer/`](./installer/) | Inno Setup `.iss` + 빌드 자동화 |
| [`db/`](./db/) | schema.sql 등 DB 관련 |

## 작업 재개 방법


## 기술 스택

| 영역 | 선택 |
|---|---|
| **서버 언어** | Kotlin 1.9 |
| **서버 프레임워크** | Ktor 2.x WebSocket + Coroutines |
| **와이어 프로토콜** | Protobuf 3 over WebSocket (ws / wss) |
| **DB** | PostgreSQL 16 |
| **인증** | Argon2id (OWASP 2024, OPEN/STRICT 모드) |
| **TLS** | TLS 1.3, JKS keystore (개발 자체 서명 / 운영 Let's Encrypt) |
| **로깅** | logback + logstash JSON + 회전 10MB×5 |
| **Rate limit** | per-user 토큰 버킷 |
| **클라이언트 언어** | C# .NET 8 |
| **클라이언트 UI** | WPF (Windows 전용) |
| **배포** | Inno Setup `.exe` 인스톨러 + Docker compose 운영 프로필 |

## v0.1 주요 기능

**메시징**
- 1:1 메시지 송수신 (Heartbeat ACK / 유휴 리퍼 90s / Presence 브로드캐스트)
- 그룹 채팅 (방 생성/참여/메시지 + **방 히스토리 페이지네이션**)
- **쪽지 (Memo)** — 1:N 발송 / 받은·보낸쪽지함 / 읽음·삭제 / 오프라인 누적 자동 flush / 첨부파일 / **답장·전체회신·전달**(클라 전용 프리필, 2026-09 추가)
- 파일 송수신 (chunk 업로드/다운로드, **진행률 실시간 표시**)
- 메시지 본문 검색

**클라이언트 UI (WPF)**
- 카톡식 dark + yellow 테마 + 커스텀 타이틀바 (WindowChrome)
- 4 탭 사이드바 (조직도/대화/쪽지/설정), 탭 전환 액센트 바 슬라이드
- 조직도 우클릭 → "대화 시작" / "쪽지 보내기" 컨텍스트 메뉴
- 인앱 토스트 시스템 (Info/Success/Warn/Error 4 레벨, fade-in/out)
- 트레이 아이콘 + 미열독 시 **빨간 점** 오버레이 + ToolTip 카운트
- 그룹 채팅 무한스크롤 (맨위 도달 시 옛 메시지 추가 로드)
- 새 메시지 등장 fade + slide 애니메이션

**신뢰성 / 운영**
- 오프라인 outbox + 재접속 시 자동 flush (1:1/쪽지 둘 다)
- 자동 재연결 (지수 백오프 1s → 30s, 최대 10회)
- 멱등 `client_msg_id` 캐시
- per-user 토큰 버킷 rate limit — **2026-09-11 부터 표준 기동 스크립트 기본 활성화** (`GUIDE.md` §7)
- **클라이언트 자동업데이트** — `/client/version` + `/client/download`, 로그인 직후 자동 확인 (2026-09-11 추가, `GUIDE.md` §10.4a)
- TLS 1.3 wss
- **글로벌 에러 boundary** — UI/Domain/Task 3종 핸들러 + `crash.log`
- **진단 로그 zip 내보내기** — 설정 탭에서 사용자가 한 번에 압축 → 운영자에게 전달
- **logback 회전** — 10MB × 5개, gz 압축, 총 60MB 상한
- **Docker compose 운영 프로필** — Postgres + server + pgbackup sidecar
- **인스톨러** — Inno Setup, 자동시작/제거 다이얼로그, in-place upgrade


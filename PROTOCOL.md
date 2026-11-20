# 오소리 메신저 와이어 프로토콜 사양 v1

> 이 문서는 `proto/messenger.proto` 를 사람이 읽기 쉽게 풀어 쓴 사양서다.
> 권위 원본은 `.proto` 파일이고, 이 문서는 그것을 보조한다.
> 변경이 일어나면 양쪽 모두 갱신할 것.
>
> **현재 상태 (2026-04-14)**: Phase 2 MVP + 확장 (Heartbeat ACK / Presence / History sync) + TLS 옵션 + 멱등 캐시 + Argon2id 인증 + Rate limit 반영

---

## 1. 설계 원칙

| 원칙 | 의미 |
|---|---|
| **단일 봉투 (Envelope)** | 하나의 WebSocket 바이너리 프레임 = 하나의 `Envelope` 메시지. `oneof payload` 로 종류 구분. 이 구조 하나가 곧 디스패치 테이블이다. |
| **호환성 우선** | 필드 번호는 절대 재사용 금지. 삭제는 `reserved` 로 봉인. 의미 변경은 v2 패키지로 분기. |
| **시퀀스 분리** | 클라→서버 요청·응답은 클라가 발급한 `sequence` 로 매칭. 서버 능동 푸시(`MessageReceived` / `PresenceEvent`)는 별도 시퀀스 영역(아래 §3) 사용. |
| **버전 분리** | `package osori.v1` / Java `com.osori.proto.v1` / C# `Osori.Proto.V1`. 메이저 변경 시 v2 추가. |
| **가벼운 본문** | 스키마는 9개 메시지로 작게. 그룹/파일/조직도/푸시 등은 Phase 6 이후 확장 필드 추가로. |

---

## 2. Envelope — 모든 프레임의 최외곽 봉투

`proto
message Envelope {
  uint64 sequence = 1;
  oneof payload {
    LoginRequest     login_req      = 10;
    LoginResponse    login_res      = 11;
    Logout           logout         = 12;

    SendMessage      send_msg       = 20;
    SendMessageAck   send_msg_ack   = 21;
    MessageReceived  msg_received   = 22;

    PresenceEvent    presence_event = 30;

    HistoryRequest   history_req    = 40;
    HistoryResponse  history_res    = 41;

    HeartbeatAck     heartbeat_ack  = 98;
    Heartbeat        heartbeat      = 99;
  }
}
`

### 필드 번호 영역 약속 (필드 번호는 변경 금지)
| 영역 | 용도 | 사용 중 |
|---|---|---|
| 10–19 | 인증/세션 | login_req(10), login_res(11), logout(12) |
| 20–29 | 메시지 | send_msg(20), send_msg_ack(21), msg_received(22) |
| 30–39 | 프레즌스 | presence_event(30) |
| 40–49 | 동기화 | history_req(40), history_res(41) |
| 98–99 | 세션 유지 | heartbeat_ack(98), heartbeat(99) |
| 50–97, 100+ | (예약 — 그룹/파일/조직도/푸시 확장용) | — |

---

## 3. 시퀀스 정책

| 발신 | 시퀀스 | 의미 |
|---|---|---|
| 클라 → 서버 요청 | 클라 발급 (1, 2, 3, ...) | 서버 응답 시 동일 시퀀스를 돌려주어 **요청/응답 매칭** |
| 서버 → 클라 응답 | 요청과 동일 시퀀스 | LoginResponse / SendMessageAck / HeartbeatAck / HistoryResponse |
| 서버 → 클라 능동 푸시 | **별도 영역** | 요청과 매칭되지 않으므로 클라가 sequence 로 페어링하지 않음 |

### 서버 능동 푸시 시퀀스 영역
| 시작 번호 | 발신원 | 비고 |
|---|---|---|
| `2_000_000` | `OutboxFlusher` (재접속 후 미배달 메시지) | `MessageReceived` 페이로드 |
| `3_000_000` | `PresenceBroadcaster` (Presence 변경) | `PresenceEvent` 페이로드 |
| (향후 확장) `4_000_000`+ | 알림/시스템 메시지 | 새 영역 추가 시 이 표 갱신 |

클라이언트는 서버 능동 푸시의 sequence 를 단순 식별자로 취급할 것 — 비교/매칭 대상이 아님.

---

## 4. 메시지별 사양

### 4.1 LoginRequest (10) / LoginResponse (11) / Logout (12)

`proto
message LoginRequest {
  string user_id        = 1;
  string password       = 2;  // 평문 password. 서버가 Argon2id 로 해시/검증
  string device_id      = 3;
  string client_version = 4;
}

message LoginResponse {
  enum Status {
    UNKNOWN             = 0;
    OK                  = 1;
    INVALID_CREDENTIALS = 2;
    ALREADY_ONLINE      = 3;
    VERSION_MISMATCH    = 4;
    SERVER_ERROR        = 99;
  }
  Status status        = 1;
  string session_token = 2;  // 재접속용 토큰 (UUID, MVP 는 단순 발급)
  int64  server_ts     = 3;  // 서버 현재 시각 (epoch millis)
}

message Logout {
  string session_token = 1;
}
`

**서버 동작 (LoginHandler)**
1. `user_id` blank → `INVALID_CREDENTIALS`
2. `UserRepository.ensureExists(user_id)` — MVP 자동 가입
3. `SessionManager.bind(user_id, session)` — 같은 user_id 로 이미 접속 중이면 **기존 세션 close**(중복 로그인 정책 = "마지막 접속자 우선")
4. `LoginResponse(OK, session_token, server_ts)` 송신
5. `PresenceBroadcaster.sendInitialSnapshot(session, user_id)` — 다른 온라인 사용자 목록을 신규 접속자에게 ONLINE 시리즈로 push
6. `PresenceBroadcaster.notifyOnline(user_id)` — 다른 접속자에게 새 접속자의 ONLINE 알림
7. `OutboxFlusher.flush(session, user_id)` — 미배달 메시지 자동 배달

**Logout 동작**
- `SessionManager.remove(session)` 후 `notifyOffline` → 정상 close. session_token 검증은 MVP 단계에서 미구현.

---

### 4.2 SendMessage (20) / SendMessageAck (21) / MessageReceived (22)

`proto
message SendMessage {
  string client_msg_id = 1;  // 클라 발급 멱등 키 (재전송 중복 방지)
  string to_user_id    = 2;
  string text          = 3;
  int64  client_ts     = 4;
  string reply_to_msg_id = 5;  // — 답글 대상의 server_msg_id (빈 문자열 = 일반 메시지)
}

message SendMessageAck {
  enum Result {
    UNKNOWN        = 0;
    DELIVERED      = 1;  // 수신자 온라인, 즉시 배달 완료
    QUEUED         = 2;  // 수신자 오프라인, 아웃박스 적재
    INVALID_TARGET = 3;
    RATE_LIMITED   = 4;
    SERVER_ERROR   = 99;
  }
  string client_msg_id = 1;
  string server_msg_id = 2;  // 서버 발급 UUID
  int64  server_ts     = 3;
  Result result        = 4;
}

message MessageReceived {
  string server_msg_id = 1;
  string from_user_id  = 2;
  string text          = 3;
  int64  server_ts     = 4;
  string to_user_id    = 5;     // — history self-sent 라우팅
  string reply_to_msg_id = 6;   // — 답글 대상 (빈 문자열 = 일반 메시지)
}
`

**서버 동작 (MessageRouter)**
1. 발신자 `session.userId` 가 null → `SERVER_ERROR` (실제로는 미인증 상태)
2. `to_user_id` blank → `INVALID_TARGET`
3. 수신자 `SessionManager.get(to_user_id)` 조회
   - **온라인**: `MessageRepository.insertMessage` → 수신자에게 `MessageReceived` push → 발신자에게 `SendMessageAck(DELIVERED)` 응답
   - **오프라인**: `MessageRepository.saveOfflineMessage`(트랜잭션 본문+outbox) → `SendMessageAck(QUEUED)` 응답
4. `RATE_LIMITED` 는 향후 토큰 버킷 도입 시 사용

**OutboxFlusher 가 능동 push 시 시퀀스**: `2_000_000`+, payload 는 `MessageReceived`.

---

### 4.3 Heartbeat (99) / HeartbeatAck (98)

`proto
message Heartbeat {
  int64 ts = 1;  // 클라 기준 시각 (epoch millis)
}

message HeartbeatAck {
  int64 client_ts = 1;  // 요청 Heartbeat.ts 그대로 echo (RTT 측정용)
  int64 server_ts = 2;
}
`

**클라 권장 주기**: 30 초.
**서버 동작**:
1. 모든 프레임 수신 시 `ClientSession.lastSeenAtMillis = now` 갱신.
2. heartbeat 케이스 진입 시 즉시 `HeartbeatAck` 응답 (요청과 동일 sequence).

**SessionReaper**:
- 백그라운드 coroutine 이 `intervalMillis = 30s` 마다 sessions 스냅샷 순회.
- `now - lastSeen > idleTimeoutMillis` (기본 90 s) 인 세션을 `CloseReason(NORMAL, "idle timeout")` 으로 close + SessionManager 제거.
- 따라서 클라가 30 초마다 heartbeat 를 보내고 있다면 절대 reap 되지 않는다 (lastSeen 이 최신).
- 30 s heartbeat × 3 회 누락 ≈ 90 s → reap.

---

### 4.4 PresenceEvent (30)

`proto
message PresenceEvent {
  enum State {
    UNKNOWN = 0;
    ONLINE  = 1;
    OFFLINE = 2;
  }
  string user_id   = 1;
  State  state     = 2;
  int64  server_ts = 3;
}
`

**서버 동작 (PresenceBroadcaster)**

| 트리거 | 동작 |
|---|---|
| 로그인 성공 (LoginHandler) | (a) 신규 접속자에게 **다른 모든 온라인 사용자 목록** 을 `ONLINE` 시리즈로 push (`sendInitialSnapshot`) → (b) 다른 모든 접속자에게 **새 접속자의 ONLINE** broadcast (`notifyOnline`, 자기 자신 제외) |
| 로그아웃 (Logout 페이로드) | `SessionManager.remove` 가 실제 제거를 한 경우만 `OFFLINE` broadcast |
| 연결 종료 (WebSocket finally) | 동일. 중복 broadcast 는 `SessionManager.remove` 의 Boolean 반환으로 한 번만 발생 보장 |

**시퀀스**: `3_000_000`+ (능동 푸시 영역).

클라이언트는 PresenceEvent 를 받아 UI 의 사용자 목록/온라인 인디케이터를 갱신하면 된다.

---

### 4.5 HistoryRequest (40) / HistoryResponse (41)

`proto
message HistoryRequest {
  int64  since_ts = 1;  // 이 시각 이후 (exclusive). 0 이면 전체 범위
  int32  limit    = 2;  // 최대 반환 건수
}

message HistoryResponse {
  repeated MessageReceived messages = 1;
  bool   has_more = 2;
}
`

**서버 동작 (HistoryHandler)**

1. 미인증 세션 → 빈 `HistoryResponse` 반환 (로그 경고)
2. `limit` 보정: `<=0 → 50` (DEFAULT_LIMIT), `> 500 → 500` (MAX_LIMIT)
3. `since_ts` 음수면 0 으로 보정
4. `MessageRepository.fetchRecentForUser(userId, sinceMillis, limit + 1)` — 한 건 더 조회해서 `has_more` 판정
5. `messages` 는 `to_user = session.userId` 인 메시지만 반환, **server_ts 오래된 순** 정렬
6. 반환: `take(limit)` + `has_more = rows.size > limit`

**MVP 정책**: 본인이 **수신자였던** 메시지만 반환. 본인이 **발신자였던** 메시지는 미포함 (Phase 6 에서 양쪽 + 대화방 단위 페이징으로 확장 예정).

**시퀀스**: 요청과 동일 sequence (응답 매칭).

#### 클라이언트 호출 예제

**Kotlin test-client**

`bash
./test-client --history 50
`
로그인 직후 `HistoryRequest{since_ts=0, limit=50}` 을 송신하고, 반환되는 `HistoryResponse.messages` 를 콘솔에 찍는다.

**C# (.NET 8) 클라이언트** — `MessengerClient.GetHistoryAsynccsharp
// 로그인 직후 재접속 시 과거 메시지 복구
var lastSyncMillis = LoadLastSyncTimestamp; // 로컬 캐시 (0 이면 전체)
var history = await client.GetHistoryAsync(
    sinceMillis: lastSyncMillis,
    limit: 50,
    CancellationToken.None);

foreach (var msg in history.Messages)
{
    Console.WriteLine($"[HIST] {msg.FromUserId} → {msg.Text}");
}

if (history.HasMore)
{
    // 50 건 초과분 존재 → 다음 윈도우 조회
    var nextFrom = history.Messages.Last.ServerTs;
    // ... 반복 호출
}
`

**재접속 흐름**

1. 클라가 로컬에 마지막 `server_ts` 를 기록해둔다 (`last_sync.dat`)
2. 재접속 성공 후 `GetHistoryAsync(lastSyncMillis, 50)` 호출
3. 서버는 그 시각 이후 수신 메시지를 반환
4. `HasMore == true` 면 마지막 메시지의 `server_ts` 를 새 `since_ts` 로 반복 호출 (페이지네이션)
5. 완료 후 가장 최근 `server_ts` 를 로컬 저장 → 다음 재접속 기준

**주의**
- `GetHistoryAsync` 는 현재 세션의 수신함만 반환. 본인이 **보낸** 메시지는 복구되지 않음 (Phase 6 에서 대화방 모델 도입 시 해결).
- Heartbeat/Login/HistoryRequest 는 rate limit **비적용** — 재접속 직후 여러 페이지 연속 요청 가능.
- `OutboxFlusher` 에 의한 자동 재배달(로그인 직후 outbox push)과는 **독립 경로**다. outbox flush 는 서버가 접속 즉시 push, HistoryRequest 는 클라가 요청해서 당기는 pull.

---

## 5. 시나리오 다이어그램 (텍스트)

### 5.1 정상 로그인 → 메시지 1건 → 종료

`
[클라 alice]                    [서버]                         [클라 bob (이미 접속 중)]
   |                              |                                 |
   |--- WebSocket connect ------->|                                 |
   |                              |                                 |
   |--- Envelope(seq=1, login_req)->|                               |
   |                              |---(LoginHandler.bind)            |
   |<--Envelope(seq=1, login_res OK token=...)----                   |
   |                              |                                 |
   |                              |  -- sendInitialSnapshot ---     |
   |<--Envelope(seq=3000001, presence_event bob ONLINE)---           |
   |                              |                                 |
   |                              |  -- notifyOnline (alice) --    |
   |                              |---Envelope(seq=3000002, presence alice ONLINE)-->|
   |                              |                                 |
   |                              |  -- outboxFlusher.flush(alice)  |
   |                              |  (이번 시나리오는 미배달 없음, 0건)|
   |                              |                                 |
   |--- Envelope(seq=2, send_msg "hi" → bob)->|                    |
   |                              |---(MessageRouter, online)        |
   |                              |---Envelope(seq=N, msg_received "hi")-->|
   |<--Envelope(seq=2, send_msg_ack DELIVERED)--                    |
   |                              |                                 |
   |--- Envelope(seq=3, logout)-->|                                 |
   |                              |---SessionManager.remove(true)    |
   |                              |---notifyOffline(alice)           |
   |                              |---Envelope(seq=3000003, presence alice OFFLINE)->|
   |<--CloseFrame(NORMAL,"logout")|                                 |
`

### 5.2 오프라인 수신자 → 재접속 시 outbox flush

`
[carol]                  [서버]                  [alice (offline)]
   |---send_msg "yo"------>|                                |
   |                       |---to_user offline               |
   |                       |---saveOfflineMessage(트랜잭션)   |
   |<--send_msg_ack QUEUED-|                                |
   |                       |                                |
                                                        (alice 재접속)
                          |<-----------login_req-----------|
                          |---bind                          |
                          |-----login_res OK --------->     |
                          |---OutboxFlusher.flush(alice)    |
                          |---fetchOutbox: 1건              |
                          |---Envelope(seq=2000001, msg_received "yo")-->|
                          |---markDelivered(id)             |
`

### 5.3 재접속 후 히스토리 동기화

`
[alice]                  [서버]
   |---login_req---------->|
   |<--login_res OK--------|
   |<--presence snapshot --|
   |                       |
   |---history_req(since=0,limit=50)--->|
   |                       |---HistoryHandler
   |                       |---fetchRecentForUser("alice", 0, 51)
   |                       |---4건 받음 (has_more = 4 > 50 ? false)
   |<--history_res(messages=[m1,m2,m3,m4], has_more=false)---
`

### 5.4 Heartbeat / 유휴 세션 리프

`
[alice]                  [서버]
   |--- heartbeat ts=t1 -->|
   |                       |---lastSeen=now
   |<--heartbeat_ack(client_ts=t1, server_ts=t2)---
   |                       |
   (30초 후)                |
   |--- heartbeat ts=t3 -->|
   |                       |---lastSeen=now
   |<--heartbeat_ack------|
   |                       |
   (만약 클라가 죽어서 90초 이상 무신호)
                           |
                           |---SessionReaper(30s 주기) 발견
                           |---close(NORMAL,"idle timeout")
                           |---finally → notifyOffline(alice)
                           |---presence broadcast OFFLINE
`

---

## 6. 버전 관리 정책

| 변경 종류 | 정책 |
|---|---|
| 새 메시지 추가 | OK. 새 oneof 필드 번호 부여 (위 §2 영역 약속 따름) |
| 새 enum 값 추가 | OK. 끝에 추가. 클라이언트는 unknown 처리 필요 |
| 새 필드 추가 | OK. 새 필드 번호. 기본값 호환 |
| 필드 번호 재사용 | **금지**. `reserved <num>` 로 봉인 |
| 필드 의미 변경 | **금지**. v2 패키지로 분기 |
| 메시지 삭제 | `reserved` 로 봉인 후 코드에서만 제거 |

---

## 7. 클라이언트 구현 체크리스트

새 클라이언트(C# 등)를 만들 때 필수 처리 항목:

- [ ] WebSocket binary frame ↔ Envelope 양방향 직렬화
- [ ] 클라 발급 sequence 로 요청/응답 매칭 (대기 콜백 맵)
- [ ] 서버 능동 푸시 (2_000_000+ / 3_000_000+) 는 매칭 없이 이벤트로 처리
- [ ] LoginResponse status 분기 (OK / INVALID_CREDENTIALS / ALREADY_ONLINE / VERSION_MISMATCH / SERVER_ERROR)
- [ ] SendMessageAck Result 분기 (DELIVERED / QUEUED / INVALID_TARGET / RATE_LIMITED / SERVER_ERROR)
- [ ] PresenceEvent 수신 시 사용자 목록 UI 업데이트 (ONLINE / OFFLINE)
- [ ] 30 초 주기 Heartbeat 자동 송신 (서버 reaper 90 s)
- [ ] HeartbeatAck 수신 시 RTT 측정 (선택)
- [ ] 재접속 후 HistoryRequest 송신 (마지막 알고있던 server_ts 이후) — outbox flush 와 별도
- [ ] Logout 송신 후 정상 close 처리

---

## 8. 현재 지원 / 제약

### 지원
- **인증** — `OSORI_AUTH_MODE=strict` 시 Argon2id 기반 실인증 (OWASP 2024 파라미터). 기본은 `open` (MVP 무인증). 자세한 정책은 §11 참조
- **TLS** — `OSORI_TLS_ENABLED=true` 시 wss 추가 listen (자체 서명 keystore). 기본은 `ws://` 평문. **strict 인증과 반드시 함께 써야 안전** (평문 ws 는 password 스니핑 가능)
- **멱등** — `client_msg_id` 를 키로 한 in-memory 캐시. 같은 키 재전송 시 기존 ack 재전송, 중복 배달 회피. 서버 재시작 시 리셋 (분산 영속 멱등은 향후 작업)
- **Rate limit** — `OSORI_RATE_LIMIT_ENABLED=true` 시 사용자별 토큰 버킷. 기본 초당 5건 + 버스트 5건. 초과 시 `SendMessageAck.Result.RATE_LIMITED` 반환
- **TLS + Ktor `testApplication` 풀스택 통합 테스트** — CI 레벨 검증 커버리지 확보

### 제약 (Phase 6+ 이후)
- 그룹 대화 / 파일 전송 / 조직도 / 푸시 알림
- 메시지 본문 크기 — Ktor `WebSockets.maxFrameSize = 10MB` (Main.kt)
- 분산 환경 미지원 — `SessionManager` + 멱등 캐시 + rate limit 버킷 전부 메모리 단일 인스턴스 (Redis 이관 예정)
- 재접속 시 히스토리 sync 는 본인 **수신** 메시지만. 발신 메시지 + 대화방 단위 페이징 확장 예정

---

## 9. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-04-12 | 초판 — Envelope + 6 메시지 (Login, Logout, SendMessage, SendMessageAck, MessageReceived, Heartbeat) |
| 2026-04-13 |: HeartbeatAck 추가 (필드 98) |
| 2026-04-13 |: HistoryRequest / HistoryResponse 추가 (필드 40 / 41) |
| 2026-04-13 |: PresenceEvent 추가 (필드 30) |
| 2026-04-13 |: TLS 1.3 wss 옵션 추가 (Main.kt environment form, 자체 서명 keystore). 와이어 스키마는 변경 없음 |
| 2026-04-13 |: 멱등 캐시 추가 (MessageRouter in-memory). 와이어 스키마는 변경 없음 |
| 2026-04-14 |: Argon2id 인증 (OPEN/STRICT 모드). `LoginRequest.password_hash` 필드는 유지하되 의미가 "raw password" 로 변경됨(§11 참조). 필드 번호 재사용 없음 |
| 2026-04-14 |: `LoginRequest.password_hash` → `password` 리네임 (필드 번호 2 유지, 와이어 호환). 이름이 의미와 일치하도록 정정. C# 클라 작업 시작 전 최저 비용 타이밍에 처리 |
| 2026-04-14 |: Rate limit (사용자별 토큰 버킷). `SendMessageAck.Result.RATE_LIMITED` 는 원래 proto 에 있었지만 서버 구현은 이제 동작 |
| 2026-09-10 | 대화(talk)/쪽지(message) 도메인 네이밍 통일. `SendMessage`→`TalkSend`, `MemoSend`→`MessageSend`, `Room*`→`TalkRoom*`, `MessageSearchRequest`→`TalkSearchRequest` 등 다수 message/필드 리네임. **필드 번호는 전부 유지**(이름만 변경 — protobuf 와이어 포맷은 번호로만 결정되므로 호환성 영향 없음). 상세는 커밋 `41e5ea2`(server) / `a0e79cd`(client-csharp) 참고 |

---

## 10. 인증 정책 상세

### 10.1 두 모드

| 모드 | 환경변수 | 동작 |
|---|---|---|
| **OPEN** (기본) | `OSORI_AUTH_MODE=open` 또는 미설정 | 기존 MVP 동작 — password 무시, 사용자 없으면 자동 가입, 비밀번호 설정/검증 없음. 개발/테스트 환경용 |
| **STRICT** | `OSORI_AUTH_MODE=strict` | Argon2id 기반 실인증. 빈 password 즉시 거부, 첫 로그인 시 해시 저장, 이후 로그인은 저장된 해시와 비교 |

### 10.2 STRICT 모드 플로우

`
클라 → LoginRequest(user_id=alice, password="secret123", ...)
   ↓
서버 LoginHandler:
   1. user_id blank → INVALID_CREDENTIALS 종료
   2. password blank → INVALID_CREDENTIALS 종료  ← STRICT 전용
   3. UserRepository.ensureExists(alice)
   4. stored = repo.getPasswordHash(alice)
      - stored == null 또는 "" 이면 → 첫 로그인
        → hasher("secret123") = $argon2id$v=19$m=65536,t=3,p=1$...
        → repo.setPasswordHash(alice, newHash)
        → 다음 단계 진행
      - stored 가 있으면 → 재로그인
        → verifier(stored, "secret123") → true / false
        → false 면 INVALID_CREDENTIALS 종료
   5. SessionManager.bind → LoginResponse(OK, token)
   6. PresenceBroadcaster + OutboxFlusher
`

### 10.3 Argon2id 파라미터 (OWASP 2024 권장)

| 항목 | 값 |
|---|---|
| 알고리즘 | Argon2id |
| iterations (t) | 3 |
| memory (m) | 65,536 KiB = 64 MiB |
| parallelism (p) | 1 |

해시 저장 포맷 예: `$argon2id$v=19$m=65536,t=3,p=1$<salt_b64>$<hash_b64>`

salt + params 가 해시 문자열 자체에 포함되어, `verify` 시 추가 인자 불필요.

### 10.4 필드 네이밍 정정

초판에서 `LoginRequest.password_hash` 로 명명했으나 실제 의미는 "클라가 평문 password 를 전송" 이라 이름이 거짓말을 하고 있었다. 2026-04-14 에 `password` 로 리네임. **필드 번호 2 는 그대로** (와이어 호환 유지). 서버가 받아서 Argon2id 로 해시/검증한다.

### 10.5 보안 경고 ⚠️

- STRICT 모드는 **반드시 TLS (wss) 와 함께** 써야 의미 있음. 평문 ws 에서는 누구나 password 스니핑 가능
- 현재 구현은 가입 플로우와 로그인 플로우가 합쳐짐 (첫 로그인 = 가입). 프로덕션에서는 명시적 RegisterRequest 로 분리 예정
- `LoginResponse.session_token` 은 현재 단순 UUID. 토큰 기반 재접속 검증 (token → 세션 복원) 은 미구현

---

## 11. Rate limit 정책 상세

### 11.1 활성화

| 환경변수 | 기본 | 의미 |
|---|---|---|
| `OSORI_RATE_LIMIT_ENABLED` | `false` | true 면 사용자별 토큰 버킷 활성 |
| `OSORI_RATE_LIMIT_PER_USER_PER_SEC` | `5` | 활성 시 capacity + refill 둘 다 이 값 (즉 버스트도 N, 지속 속도도 N/sec) |

### 11.2 토큰 버킷 동작

- 각 사용자당 독립 버킷 (`ConcurrentHashMap<userId, TokenBucket>`)
- 로그인 순간 처음 진입하는 user 의 버킷이 lazy 생성 (capacity 만큼의 초기 토큰)
- `MessageRouter.handleSend` 진입 시 `bucket.tryConsume`:
  - 성공 → 정상 처리
  - 실패 → `SendMessageAck.Result.RATE_LIMITED` 즉시 반환, 수신자 배달 없음
- refill 은 tryConsume 호출 시점에 lazy 계산: `elapsed_sec * refillPerSecond` 만큼 추가, capacity 상한

### 11.3 한계

- 서버 단일 인스턴스 기준. 분산 환경에선 Redis `INCRBY` + TTL 기반으로 이관 예정
- 서버 재시작 시 버킷 초기화 (모든 사용자가 full capacity 로 시작)
- Heartbeat / Login / HistoryRequest 는 rate limit 대상 **아님** (현재는 SendMessage 만)

---

## 12. 클라이언트 재연결 정책 (C# 전용,

서버는 stateless WebSocket 연결자 입장에서 재연결 의무가 없다. 클라이언트가 자체적으로 감지·복구한다. C# `OsoriClient.Core.MessengerClient` 의 구현은 아래와 같다.

### 12.1 자동 재연결 트리거

`ReceiveLoopAsync` 가 다음 중 하나로 종료되면 auto-reconnect 를 유도:
- `WebSocketException` / `IOException` (네트워크 단절)
- `Close` 프레임 수신 (서버 재시작, idle reaper, 의도적 종료 아닌 모든 경우)
- 기타 예외로 `catch` 에 도달

단, 다음은 재연결 **대상 아님**:
- `_manualDisconnectRequested = true` (호출자가 `LogoutAsync` 또는 `DisposeAsync` 호출)
- `AutoReconnectEnabled = false`

### 12.2 지수 백오프 (`ComputeBackoffDelayMs`)

`
attempt 0 →  1,000 ms  (1s)
attempt 1 →  2,000 ms  (2s)
attempt 2 →  4,000 ms  (4s)
attempt 3 →  8,000 ms  (8s)
attempt 4 → 16,000 ms  (16s)
attempt 5+ → 30,000 ms (cap)
`

공식: `Math.Min(30000, 1000 << attempt)`. 최대 10회 시도 후 `ReconnectFailed` 이벤트.

### 12.3 재연결 시 재인증

`ConnectAsync` / `LoginAsync` 가 연결·인증 파라미터를 인스턴스 필드에 저장해뒀으므로, 재연결 코루틴이 동일 값으로 재호출:
1. 새 `ClientWebSocket` 생성 (TLS 옵션 포함)
2. `_serverUri` 로 재연결
3. `_userId`/`_password`/`_deviceId` 로 재로그인
4. 성공 시 `Reconnected` 이벤트 발생 → 호출자가 히스토리 복구 트리거

### 12.4 SendMessage 재시도 멱등성

메시지 전송은 `ConnectAsync` 레벨의 재연결과 독립적으로, **동일 client_msg_id 로 재시도** 가능:

`csharp
// 2회 재시도 — 같은 client_msg_id 유지
var ack = await client.SendMessageAsync("bob", "hi", maxRetries: 2, ct);
`

재시도 지연 (`ComputeRetryDelayMs`):
`
attempt 0 →   500 ms
attempt 1 → 1,000 ms
attempt 2 → 2,000 ms
attempt 3+→ 3,000 ms (cap)
`

서버의 `MessageRouter` 멱등 캐시, `ConcurrentHashMap<(fromUser, clientMsgId), CachedAck>`) 가 같은 `client_msg_id` 두 번째 요청에 대해 저장된 ack 를 그대로 반환 → DB 중복 저장 방지.

### 12.5 이벤트 시퀀스 (정상 재연결)

`
[클라]                                [서버]
  ├─ WebSocket 수신 루프 예외
  │   (네트워크 drop)
  │
  ├─ Reconnecting 이벤트 발생
  │
  ├─ await Task.Delay(1000ms)
  │
  ├─ new ClientWebSocket
  ├─ ConnectAsync(_serverUri) ─────→ WebSocket accept
  ├─ LoginAsync(_userId, ...) ─────→ LoginHandler
  │                                 ├─ STRICT: Argon2 verify
  │                                 ├─ SessionManager.bind
  │                                 ├─ PresenceBroadcaster (online)
  │                                 └─ OutboxFlusher (과거 offline 메시지 자동 push)
  │   ← LoginResponse(OK) ──────────
  ├─ _reconnectAttempts = 0
  ├─ Reconnected 이벤트 발생
  │
  │   (호출자) ChatWindow 이 구독 중이면:
  ├─ LastSyncStore.Get(userId) → sinceMillis
  ├─ GetHistoryAsync(sinceMillis, 50) ─→ HistoryHandler
  │   ← HistoryResponse(messages, has_more) ─
  ├─ UI 에 [HIST] 접두사로 표시
  └─ LastSyncStore.Set(userId, lastTs)
`

### 12.6 테스트 훅 (`ForceCloseSocketForTestAsync`)

smoke test 가 실제 WebSocket drop 을 시뮬레이션할 수 있도록 `internal async Task ForceCloseSocketForTestAsync` 메서드 제공:
- `_socket.CloseOutputAsync(EndpointUnavailable)` 호출
- `_manualDisconnectRequested` 는 **건드리지 않음** → auto-reconnect 동작 유지
- `InternalsVisibleTo("OsoriClient.SmokeTest")` 로 노출

 smoke test 가 이를 이용해 "강제 close → 15초 내 Reconnected 이벤트 수신 → post-send Delivered" 전체 경로를 실증.

---

이 문서가 권위 있는 사양서로 사용되려면 `proto/messenger.proto` 와 항상 sync 되어야 한다. 필드를 추가하면 §2, §4 양쪽 모두 갱신할 것. §12 는 C# 클라이언트 고유이므로 Kotlin test-client 에도 동일 패턴이 필요하면 에서 이식.

---

## 13. 그룹 채팅 히스토리

### 13.1 Envelope 추가 메시지

| 태그 | 메시지 | 방향 |
|---|---|---|
| 120 | `RoomHistoryRequest` | 클라 → 서버 |
| 121 | `RoomHistoryResponse` | 서버 → 클라 |

### 13.2 메시지 구조

`protobuf
message RoomHistoryRequest {
  string room_id      = 1;
  int64  since_ts     = 2;  // exclusive. 0 = 전체 범위
  int32  limit        = 3;  // 기본 50, 상한 500
  string client_req_id = 4;
}

message RoomHistoryResponse {
  enum Status { UNKNOWN=0; OK=1; NOT_A_MEMBER=2; ROOM_NOT_FOUND=3; SERVER_ERROR=99; }
  string client_req_id = 1;
  Status status        = 2;
  string room_id       = 3;
  repeated RoomMessageReceived messages = 4;
  bool   has_more      = 5;
}
`

### 13.3 정책

- 호출자가 해당 방의 멤버가 아니면 `NOT_A_MEMBER` (보안)
- 메시지는 `server_ts ASC` (오래된 → 최신) 로 반환
- `has_more` = `rows.size > limit` 판정용 (서버가 limit+1 로 조회)
- 그룹 채팅창 첫 열기 시 페이지 1 로드 → 맨위 스크롤 시 페이지 2 누적 로드

---

## 14. 쪽지 (Memo)

### 14.1 Envelope 추가 메시지

| 태그 | 메시지 | 방향 |
|---|---|---|
| 110 | `MemoSend` | 클라 → 서버 |
| 111 | `MemoSendAck` | 서버 → 클라 |
| 112 | `MemoReceived` | 서버 → 수신 클라 (push) |
| 113 | `MemoListRequest` | 클라 → 서버 |
| 114 | `MemoListResponse` | 서버 → 클라 |
| 115 | `MemoMarkRead` | 클라 → 서버 |
| 116 | `MemoMarkReadAck` | 서버 → 클라 |
| 117 | `MemoDelete` | 클라 → 서버 |
| 118 | `MemoDeleteAck` | 서버 → 클라 |

### 14.2 핵심 흐름

`
발신자 alice                서버                  수신자 bob
   │                          │                       │
   │  MemoSend(rid=[bob])     │                       │
   ├─────────────────────────>│                       │
   │                          ├── memos+recipients INS│
   │                          │                       │
   │                          │  MemoReceived (push)  │
   │                          ├──────────────────────>│  (bob 온라인 시)
   │                          │                       │
   │  MemoSendAck(OK)         │                       │
   │<─────────────────────────┤                       │
   │                          │ markDelivered(bob)    │
`

bob 오프라인 시:
- `MemoSend` 시점에 `delivered_at=NULL` 로 누적
- bob 의 다음 로그인 → `LoginHandler.flushUndelivered(bob)` 가 `fetchUndeliveredForUser` → `MemoReceived` 일괄 푸시 → `markDelivered`

### 14.3 받은/보낸쪽지함 페이지네이션

`protobuf
message MemoListRequest {
  enum Box { INBOX=0; SENT=1; }
  string client_req_id = 1;
  Box    box           = 2;
  int64  since_ts      = 3;  // exclusive
  int32  limit         = 4;  // 기본 50, 상한 200
}
`

- INBOX: 본인 = 수신자 + 본인 삭제 X
- SENT: 본인 = 발송자 + 본인 삭제 X. `recipient_ids` 는 `string_agg` 로 다중 집계

### 14.4 읽음 / 삭제

- `MemoMarkRead`: `memo_recipients.read_at = now` RETURNING. 본인 받은쪽지만 가능 — 발송자 시도 시 `NOT_FOUND`
- `MemoDelete`: 발송자/수신자 분기. 발송자 호출 → `memos.deleted_at_by_sender`. 수신자 호출 → `memo_recipients.deleted_at`. 어느쪽도 아니면 `NOT_OWNER`. **소프트 삭제** — 상대 시야에는 보존

### 14.5 첨부

`MemoSend.attachment_upload_id` 가 `file_uploads.upload_id` 외래 참조. 파일은 일반 `FileUpload*` 흐름으로 사전 업로드 후 쪽지 발송 시 참조만 — 다운로드는 일반 `FileDownloadRequest` 와 동일.

---

§13/§14 는~229 동시 구현 시점에 추가됨. 필드 추가 시 `proto/messenger.proto` 와 양쪽 모두 갱신할 것.

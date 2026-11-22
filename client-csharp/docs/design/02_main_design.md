# MainWindow 디자인 스펙 (OC-D02)

> 로그인 후 상시 떠있는 **허브 창**. 대화 목록/조직도/설정을 탭으로 스위칭.
> 개별 대화는 이 창이 아니라 **별도 `ConversationWindow` 팝업** 으로 열린다 (카톡/슬랙 방식).

---

## 1. 목적

- 한눈에 보이는 대화 목록 + 안 읽음 / presence 상태
- 조직도로 새 대화 시작
- 내 presence 토글 (온라인/자리비움/방해금지/오프라인)
- 연결·동기화 상태 가시화
- **세로 공간 효율** — 좁게(360~400px) 고정 가능, 보조 모니터/사이드 패널 사용 상정

---

## 2. 레이아웃 (ASCII 와이어프레임)

```
╔══════════════════════════════════════════════════════════╗
║ 오소리 메신저  🦡                          [─][□][×]    ║  ← 커스텀 타이틀바
╠══════════════════════════════════════════════════════════╣
║ ┌────┐                                                   ║
║ │ 🦡 │  alice (선임연구원)                               ║  ← 내 프로필 헤더
║ └────┘  🟢 온라인 ▼                                      ║    presence 드롭다운
╠═════╦════════════════════════════════════════════════════╣
║  💬 ║  대화                              🔍  [+]        ║  ← 탭 컨텐츠 헤더
║  ●3 ║  ─────────────────────────────────────────────    ║    (● = 안읽음 토탈)
║     ║  ┌──────────────────────────────────────────┐     ║
║  🏢 ║  │ 🟢 bob                           14:25 ③ │     ║
║     ║  │   어제 알려준 그 일정 말인데...          │     ║
║     ║  ├──────────────────────────────────────────┤     ║
║  ⚙  ║  │ 📁 플랫폼개발팀 주간회의        어제 ①  │     ║
║     ║  │   charlie: 회의록 공유드립니다           │     ║
║     ║  ├──────────────────────────────────────────┤     ║
║     ║  │ ⚪ charlie                        수      │     ║
║     ║  │   📎 설계서_v3.pdf 받음                  │     ║
║     ║  ├──────────────────────────────────────────┤     ║
║     ║  │ ⚫ dan                        04-14      │     ║
║     ║  │   (대화 시작 안 함)                      │     ║
║     ║  └──────────────────────────────────────────┘     ║
║     ║                                                    ║
╠═════╩════════════════════════════════════════════════════╣
║ ● 연결됨  ·  145건 동기화  ·  조직도 최신                ║  ← 상태 바
╚══════════════════════════════════════════════════════════╝
          Width: 380    Height: 680   (리사이즈 가능)
```

### 조직도 탭 (🏢)

```
║  💬 ║  조직도                              🔄            ║
║     ║  ─────────────────────────────────────────────    ║
║  🏢 ║  📁 오소리랩 (5)                                   ║
║     ║    ├ 📁 연구개발본부 (3)                           ║
║     ║    │   ├ 👤 charlie     책임연구원      🟢        ║
║     ║    │   └ 📁 플랫폼개발팀                           ║
║  ⚙  ║    │       └ 👤 alice    선임연구원    ━ (나)     ║
║     ║    └ 📁 사업본부 (1)                               ║
║     ║        └ 👤 bob          과장           ⚪        ║
║     ║                                                    ║
║     ║  검색 ┌─────────────────────────────┐              ║
║     ║        │ 이름/부서로 필터              │            ║
║     ║        └─────────────────────────────┘              ║
```

### 설정 탭 (⚙)

```
║  💬 ║  설정                                              ║
║     ║  ─────────────────────────────────────────────    ║
║     ║  ▸ 서버 연결      osori.local:7071         ║
║  🏢 ║  ▸ 알림           메시지 소리, 트레이 알림        ║
║     ║  ▸ 표시           글꼴 14pt, 라이트 테마          ║
║     ║  ▸ 신뢰성         자동 재접속 ON                  ║
║  ⚙  ║  ▸ 푸시 알림      디바이스 등록됨                 ║
║     ║                                                    ║
║     ║  ─────────────────────────────────────────────    ║
║     ║  버전 0.1.0  ·  프로토콜 v1                        ║
║     ║  [로그아웃]  [개발자 도구]                         ║
```

각 섹션 ▸ 를 누르면 서브 패널로 확장 (accordion). SettingsWindow 는 모달 삭제, 인라인으로 통합.

---

## 3. 영역별 스펙

### 3.1 커스텀 타이틀바
- `WindowStyle=None` + `AllowsTransparency=False` + `ResizeMode=CanResize`
- 왼쪽: 앱 로고 + "오소리 메신저"
- 오른쪽: 최소화/최대화/닫기 (네이티브 크롬 포기 대신 일관된 스타일)
- **닫기 동작**: 설정 "트레이로 최소화" 가 켜져 있으면 Hide, 아니면 실제 종료. 기본 켜짐.

### 3.2 내 프로필 헤더 (상단 Fixed)
- 아바타(이니셜 또는 업로드된 이미지 — 후속)
- 이름 + 직함 (조직도 메타에서 조회)
- Presence 드롭다운: 🟢 온라인 / 🟡 자리비움 / 🔴 방해금지 / ⚫ 오프라인
  - 자리비움/방해금지는 **서버 presence 확장** 필요 (현 스키마는 ONLINE/OFFLINE 2단계, state 확장은 OC-D02 에서 스코프 밖)
  - 이번 MVP 는 🟢 / ⚫ 만 지원 + 드롭다운은 나머지 회색 비활성
- 클릭 시 프로필 편집 패널 (후속)

### 3.3 좌측 탭 레일 (56px 고정)
- 아이콘 탭 3개: 💬 대화 / 🏢 조직도 / ⚙ 설정
- 선택된 탭은 좌측 3px 액센트 바 + 배경 틴트
- 💬 탭 아이콘 옆에 전역 안 읽음 수 배지 (●3)
- 키보드: `Ctrl+1/2/3` 탭 전환

### 3.4 대화 리스트 (💬 탭)
- 헤더: "대화" + 검색 버튼 + 새 대화(+)
- 리스트 아이템 **한 행**:
  ```
  [presence dot] [이름/방명] ............... [시각]
                 [미리보기 한 줄]            [안읽음배지]
  ```
- 정렬: 마지막 메시지 시각 DESC
- presence dot 색:
  - 🟢 온라인
  - ⚪ 오프라인
  - ⚫ 대화 시작 안 함(미리보기 없음)
  - 📁 그룹방 (presence 대신 방 아이콘)
- 안 읽음 배지: 원형, 최대 `99+`
- 더블클릭 → `ConversationWindow` 열기 (또는 기존 창 활성화)
- 우클릭 컨텍스트 메뉴:
  - 대화 열기
  - 안 읽음으로 표시 / 읽음으로 표시
  - 상단 고정 (핀)
  - 대화 삭제 (서버 영속 아님, 로컬 리스트에서만)

### 3.5 조직도 (🏢 탭)
- TreeView (기존 구현 재사용) + 검색 필터
- 부서별 멤버 수 표시 `(5)`
- 자기 자신은 `━ (나)` 표식으로 우측 표시
- 멤버 더블클릭 → ConversationWindow (1:1 대화 시작)
- 부서 우클릭 → "이 부서로 그룹방 만들기" (후속)

### 3.6 설정 (⚙ 탭)
- 섹션 5개 (서버 연결 / 알림 / 표시 / 신뢰성 / 푸시) 아코디언
- 현 `SettingsWindow.xaml` 의 12 필드를 섹션별로 이관
- 하단: 버전 정보 + 로그아웃 + 개발자 도구(로그 뷰어, 후속)
- **변경 즉시 반영**: 이벤트 기반 `SettingsStore.SettingsChanged` subscribe → 관련 UI 갱신 (테마/글꼴 실시간)

### 3.7 하단 상태 바
- 좌: 연결 상태 (● 연결됨 / ◯ 재연결 중... / ⚠ 연결 끊김)
- 중: 동기화 정보 (메시지 건수, 조직도 버전)
- 우: 새 알림 요약 (후속)
- 클릭 → 상세 토스트 또는 개발자 도구

---

## 4. 상호작용 상태

| 상태 | 설명 | UI |
|---|---|---|
| **Loaded** | 로그인 직후 진입 | 히스토리 백그라운드 pull, 조직도 pull. 대화 리스트는 서버 데이터 기반 구성 |
| **Disconnected** | WebSocket 끊김 감지 | 상단 노란 배너: "연결이 끊어졌습니다. 재접속 중..." 상태바 ⚠ |
| **Reconnecting** | auto-reconnect 진행 | 배너 유지, 상태바 ◯ 회전 |
| **BackOnline** | Reconnected 이벤트 | 배너 2초간 녹색 "다시 연결됨" 후 자동 숨김 |
| **NewMessage** | 새 메시지 수신 | 해당 대화 아이템 리스트 맨 위로 이동 + 배지 증가 + (설정이면) 소리·트레이 알림 |
| **EmptyList** | 첫 실행 | 대화 리스트 자리에 "아직 대화가 없습니다. 🏢 조직도에서 대화를 시작하세요." 일러스트 |

---

## 5. 대화 목록의 서버 소스

현 클라이언트에는 `ConversationStore` (메모리 기반) 가 있음. 재설계:

- 서버 대화 목록 API 없음 → **클라 메모리 + 영속 캐시** 로 운영
- 영속 캐시 `conversation_cache.json` 을 `%LOCALAPPDATA%\OsoriClient\` 에 저장
  - 각 대화 레코드: counterpart / lastMessageText / lastMessageTs / unreadCount / lastSeenTs
  - 로그인 시 로드 후, 히스토리 pull 결과로 merge + 새 수신 이벤트로 업데이트
- **안 읽음 로직**: 대화 창을 열면 lastSeenTs = 그 시점 cutoff. 그 이후 수신은 unreadCount++
- 서버측 read receipt / 서버 대화 목록 API 는 후속 OC 에서 설계

---

## 6. 스타일 토큰 (`App.xaml` 공유)

LoginWindow 와 동일 팔레트 재사용. 추가 토큰:

```
PresenceOnline      #4CAF50
PresenceOffline     #9E9E9E
PresenceAway        #FBC02D
PresenceDnd         #E53935
UnreadBadgeBg       #D32F2F
UnreadBadgeFg       #FFFFFF
SidebarSelected     #E8F5E9
SidebarAccent       #2E7D32
BannerWarn          #FFF3E0
BannerWarnFg        #E65100
BannerError         #FFEBEE
BannerErrorFg       #C62828
BannerSuccess       #E8F5E9
BannerSuccessFg     #2E7D32

FontTitle             22pt SemiBold
FontSection           16pt SemiBold
FontListTitle         13pt SemiBold
FontListPreview       12pt Regular TextSecondary
FontMeta              11pt Regular TextSecondary

ListItemHeight        64px
ListItemPadding       10 12
RailWidth             56px
AvatarSize            40px
```

---

## 7. 창 관리

- `WindowState.Minimized` → 설정에 따라 트레이로 숨김
- `Closed` → ConversationWindow 전부 함께 닫힘 + 명시적 LOGOUT 송신 + SettingsStore 상태 저장
- **다중 인스턴스 방지**: Mutex 로 같은 사용자 중복 실행 차단 (이미 있으면 기존 창 활성화)
- **화면 복원**: 위치·크기 SettingsStore 저장 (`MainWindowBounds`)

---

## 8. 키보드 단축키

| 단축키 | 동작 |
|---|---|
| `Ctrl+1` | 대화 탭 |
| `Ctrl+2` | 조직도 탭 |
| `Ctrl+3` | 설정 탭 |
| `Ctrl+F` | 대화 검색 창 (기존 SearchWindow) |
| `Ctrl+N` | 새 대화 다이얼로그 (ID 입력) |
| `Ctrl+,` | 설정 탭으로 이동 |
| `F5` | 조직도 새로고침 |
| `Esc` | (대화 리스트에서) 검색 필드 지우기 |

---

## 9. AutomationId 목록

```
MainTitleBar
MainProfileHeader, MainPresenceDropdown
TabRailConversation, TabRailOrg, TabRailSettings
ConversationListBox, ConversationSearchBox, NewConversationButton
OrgTreeView, OrgFilterBox, OrgReloadButton
SettingsPanel, SettingsConnSection, SettingsNotifySection, ...
StatusBarConnection, StatusBarSync, StatusBarNotifications
DisconnectedBanner, ReconnectingBanner
```

---

## 10. 구현 영향 범위

**수정/신규 파일**:
- `MainWindow.xaml` / `.xaml.cs` — `ChatWindow` 를 전면 재구성 (이름도 변경 검토: ChatWindow → MainWindow)
- `TabRail` UserControl (신규, 세로 탭 바)
- `ConversationListItem.xaml` (DataTemplate 는 기존과 유사하나 presence dot + 그룹방 아이콘 추가)
- `PresenceDropdown.xaml` (신규)
- `StatusBar.xaml` (신규 UserControl)
- `DisconnectedBanner.xaml` (신규 UserControl)
- `SettingsStore` 에 필드: `LastSelectedTab`, `MainWindowBounds`, `CloseToTray`, `AutoLoginEnabled`, `EncryptedPassword`, `RecentUserIds`
- `ConversationCache.cs` (신규, `%LOCALAPPDATA%` JSON 영속)
- `App.xaml` 에 스타일/ResourceDictionary 확장

**제거**:
- `SettingsWindow.xaml` / `.xaml.cs` (Main 탭 안으로 흡수)

**새 의존성**: 없음 (WPF 기본 + DPAPI).

---

## 11. Out of scope (이번 재설계에 **안 넣음**)

- 자리비움/방해금지 presence (서버 스키마 확장 필요)
- 서버측 read receipt / 대화 목록 API
- 프로필 이미지 업로드
- 메시지 검색 결과 미리보기(현 SearchWindow 그대로 재사용)
- 대화 고정(pin) 서버 동기화 — 로컬 전용
- 다중 워크스페이스 (다른 회사 테넌트)
- 그룹방 관리 (멤버 추가/삭제/나가기) UI — ConversationWindow 설계(3단계)에서 일부만

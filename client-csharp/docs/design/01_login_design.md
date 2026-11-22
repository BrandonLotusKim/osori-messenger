# LoginWindow 디자인 스펙 (OC-D01)

> 오소리 메신저 WPF 클라이언트의 로그인 창 재설계 스펙.
> 현 MVP(`LoginWindow.xaml`) 는 기능은 있으나 시각 정보 구조/상태 피드백이 부족 → 재설계.

---

## 1. 목적

- 사내 PC 에 설치된 클라이언트가 **빠르게** 대화 상태로 진입하게 한다.
- 고정 사용자가 반복 로그인하므로 **마지막 프로필 자동 복원** 이 기본 동작.
- 서버 호스트는 관리자가 세팅하면 이후 안 건드리는 값 → **접힌 상태** 가 기본.
- STRICT 모드(패스워드 인증) 지원 필요. OPEN 모드에선 password 필드가 사실상 placeholder.

---

## 2. 레이아웃 (ASCII 와이어프레임)

```
╔══════════════════════════════════════════╗
║  오소리 메신저                    [─][□][×]║
╠══════════════════════════════════════════╣
║                                          ║
║           ┌────────┐                     ║
║           │  🦡   │   ← 로고 (56×56)    ║
║           └────────┘                     ║
║                                          ║
║       오소리 메신저                       ║
║       사내 소통의 시작                    ║
║                                          ║
║   ┌─────────────────────────────────┐    ║
║   │ 사용자 ID                       │    ║
║   │ ┌─────────────────────────────┐ │    ║
║   │ │ alice                     ▼ │ │    ║
║   │ └─────────────────────────────┘ │    ║
║   │                                 │    ║
║   │ 비밀번호                        │    ║
║   │ ┌─────────────────────────────┐ │    ║
║   │ │ ••••••••                    │ │    ║
║   │ └─────────────────────────────┘ │    ║
║   │ ☐ 자동 로그인                   │    ║
║   │                                 │    ║
║   │     ┌─────────────────────┐     │    ║
║   │     │       접속          │     │    ║
║   │     └─────────────────────┘     │    ║
║   └─────────────────────────────────┘    ║
║                                          ║
║   ▶ 서버 설정                            ║  ← 접혀있음 (Expander)
║                                          ║
║   [상태 텍스트 영역]                     ║
║                                          ║
╚══════════════════════════════════════════╝
      Width: 360    Height: 520
```

### 펼친 상태 (서버 설정 Expander)
```
   ▼ 서버 설정
     서버 호스트  ┌─────────────────────┐
                  │ osori.local   │
                  └─────────────────────┘
     포트        ┌─────────────────────┐
                  │ 7071                │
                  └─────────────────────┘
     ☑ wss 사용 (자체 서명 인증서 허용)
     [기본값 복원]
```

---

## 3. 상호작용 상태

| 상태 | 트리거 | UI 변화 |
|---|---|---|
| **Default** | 창 열림 | `SettingsStore.LastUserId` 가 있으면 ID 입력칸에 채움. 자동 로그인 체크 시 즉시 Connecting 진입 |
| **Validating** | 접속 클릭 | ID 빈 값, 포트 숫자 아님 → 해당 필드 빨간 테두리 + 인라인 에러 문구 표시 |
| **Connecting** | 접속 시작 | 버튼 문구 "접속 중..." + 비활성, ProgressRing(무한 회전) 좌측 표시, 폼 전체 비활성 |
| **Authenticating** | WS 연결 성공 후 LoginReq 대기 | Connecting 과 동일 UI, 상태 텍스트만 "인증 중..." |
| **Error** | 연결 실패 / 인증 실패 | 상태 텍스트 빨간색, 구체적 메시지. 폼 재활성. 에러 종류에 따라 다음 포커스 결정:<br>- `INVALID_CREDENTIALS` → 비밀번호 필드 클리어 + 포커스<br>- 네트워크 실패 → 서버 설정 Expander 자동 전개 |
| **Success** | LoginRes OK | 상태 텍스트 "로그인 성공. 메인으로 이동..." 녹색, 200ms 후 MainWindow 열고 Close |

---

## 4. 필드 스펙

| 필드 | 컨트롤 | 기본값 | 검증 |
|---|---|---|---|
| 사용자 ID | `ComboBox IsEditable=True` | SettingsStore.LastUserId | 공백 불가, 길이 1~64 |
| 비밀번호 | `PasswordBox` | 비움 | OPEN 모드면 빈 값 허용, STRICT 모드면 필수 (모드는 서버가 결정하므로 클라는 우선 전송, INVALID_CREDENTIALS 응답 시 안내) |
| 자동 로그인 | `CheckBox` | SettingsStore.AutoLoginEnabled | 체크 시 **비밀번호 DPAPI 암호화로 로컬 저장** (새 기능, SettingsStore 확장 필요) |
| 서버 호스트 | `TextBox` | SettingsStore.ServerHost | 공백 불가 |
| 포트 | `TextBox` | SettingsStore.ServerPort | 숫자 1~65535 |
| wss | `CheckBox` | SettingsStore.UseTls | — |

### ComboBox 추천 목록
- 최근 접속한 사용자 ID 상위 5개 (SettingsStore.RecentUserIds: List<string> 새 필드)
- 드롭다운에서 선택 시 자동으로 UserId 채움 + 자동 로그인 저장돼 있으면 비밀번호도 복원

---

## 5. 스타일 토큰 (WPF ResourceDictionary 후보)

```
<!-- 색상 -->
PrimaryBrand        #2E7D32   (오소리 녹갈 계열 — 기존 #1976D2 에서 차별화)
PrimaryBrandHover   #388E3C
Surface             #FFFFFF
SurfaceMuted        #F5F5F5
Border              #E0E0E0
TextPrimary         #212121
TextSecondary       #757575
Error               #D32F2F
Success             #2E7D32
ProgressRingStroke  #2E7D32

<!-- 타이포 -->
FontFamily           맑은 고딕, Segoe UI
Title                22pt SemiBold  (로고 아래 "오소리 메신저")
Subtitle             11pt Regular TextSecondary ("사내 소통의 시작")
Label                11pt SemiBold
Body                 13pt Regular
Button               13pt SemiBold
Error                11pt Regular Error

<!-- 간격 -->
FieldGap             12px  (필드 간 수직)
GroupGap             20px  (섹션 간)
FormPadding          24px  (창 안쪽 여백)
InputHeight          32px
ButtonHeight         36px
CornerRadius         4px
```

---

## 6. 추가 동작 (기존엔 없던 신규)

- **자동 로그인**: 체크 + 성공 시 DPAPI 로 PW 암호화해 SettingsStore 에 저장. 다음 실행 시 자동으로 Connect → Login. 실패 시 폼으로 복귀 + 경고.
- **서버 프로필 복수 보존**: (차후 확장 여지) 호스트별 프로필 전환. 이번 버전은 단일.
- **키보드**: `Enter` = 접속, `Esc` = 창 닫기. TabIndex 순서 ID → PW → 자동로그인 → 접속.
- **연결 중 취소**: Connecting 상태에서 버튼이 "취소" 로 바뀜. 클릭 시 CancellationToken trigger.

---

## 7. 창 크기/반응형

- 고정 크기 `360×520` (`ResizeMode=NoResize`).
- 서버 설정 Expander 펼치면 높이 확장 허용 → `SizeToContent=Height` 고려.
- 화면 중앙(`WindowStartupLocation=CenterScreen`).

---

## 8. 접근성

- 모든 입력 필드에 `AutomationId` 부여 (UIAutomation 테스트 스크립트 호환):
  - `LoginUserIdCombo`, `LoginPasswordBox`, `LoginAutoCheck`, `LoginConnectButton`, `LoginStatusText`, `LoginServerExpander`, `LoginHostBox`, `LoginPortBox`, `LoginTlsCheck`
- 탭 순서 명시.
- 상태 변화 시 `UIA.LiveSetting=Polite` 로 스크린리더 알림.

---

## 9. 구현 시 영향 범위

**수정 파일**:
- `LoginWindow.xaml` / `.xaml.cs` 재작성
- `ClientSettings.cs` 에 필드 추가: `AutoLoginEnabled`, `EncryptedPassword`, `RecentUserIds`
- `App.xaml` 에 스타일 `ResourceDictionary` 추가 (토큰 섹션 참고)
- DPAPI 헬퍼 `CredentialProtector.cs` (신규, `System.Security.Cryptography.ProtectedData`)

**새로운 의존성**: 없음 (DPAPI 는 .NET 8 Windows 빌트인).

---

## 10. Out of scope (이번 재설계에 **안 넣음**)

- 다중 계정 동시 로그인
- SSO / OAuth
- 2FA
- 비밀번호 재설정 UI (관리자가 DB 직접 리셋하는 운영 모델)
- 회원가입(관리자 CSV import 로 사용자 생성 — 기존 모델 유지)

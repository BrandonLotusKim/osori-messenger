# 오소리 메신저 클라이언트 인스톨러/241/242)

Windows 사용자에게 배포 가능한 단일 실행 `.exe` 설치 파일을 만든다.

## 두 가지 배포 방식

| 방식 | 결과물 | 사용자 액션 | 추가 도구 |
|---|---|---|---|
| **A. Inno Setup 인스톨러** | `OsoriMessenger-Setup-0.1.0.exe` | 마법사 따라 설치 | Inno Setup 6 설치 |
| **B. Portable .zip** | `OsoriMessenger-Portable-0.1.0.zip` | 압축 풀고 exe 더블클릭 | 없음 |

빌드 명령:
- A: `build-installer.bat`
- B: `build-portable-zip.bat`

배포 정책 결정:
- 자동시작/시작메뉴/제거 마법사가 필요한 일반 사용자 → **A**
- 임시 검증, 망 분리 환경, 권한 제한 PC → **B**

## 사전 준비 (한 번만)

1. **.NET 8 SDK** — `dotnet --version` 으로 확인. 8.0.x 면 OK.
2. **Inno Setup 6** — https://jrsoftware.org/isdl.php 에서 `innosetup-6.x.x.exe` 다운로드 후 기본 설치 (무료, ~2MB).
   - 한국어 메시지가 필요하면 설치 중 "Korean translation" 옵션 선택 (기본 포함됨).
3. 사용자 PC 에는 **.NET 8 Desktop Runtime** 이 미리 깔려 있어야 함. 인스톨러는 self-contained 모드가 아니므로 런타임을 번들하지 않음.
   - https://dotnet.microsoft.com/download/dotnet/8.0 → "Desktop Runtime 8.0.x"

## 빌드

`cmd
cd installer
build-installer.bat
`

결과: `installer\Output\OsoriMessenger-Setup-0.1.0.exe`

## 인스톨러가 포함하는 것

- `OsoriClient.Wpf.exe` + 의존 DLL (publish 결과 일괄)
- 한국어/영어 설치 UI

## 설치 시 옵션

- 시작 메뉴 항목 자동 생성
- 바탕화면 아이콘 (선택)
- **Windows 시작 시 자동 실행** (선택) — `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` 등록
- 설치 완료 후 즉시 실행 (체크박스)

## 제거 시

- 설치 폴더 자체와 빌드 부산물(`logs/`, `storage/`) 자동 삭제
- 자동시작 레지스트리 키 자동 해제
- **사용자 데이터 보존 선택 다이얼로그**:
  - 예 = `%LOCALAPPDATA%\OsoriMessenger` 까지 삭제 (완전 초기화)
  - 아니오 = 보존 (다음 설치 시 설정/로그 그대로 복원)

## 버전 업데이트 방식

`OsoriClient.iss` 의 `MyAppVersion` 만 올리면 in-place upgrade 됨 (`AppId` GUID 가 고정).

## CI 자동화 (v0.2 이후)

GitHub Actions 에서 빌드하려면 `crazy-max/ghaction-chocolatey` 로 Inno Setup 설치 후 `build-installer.bat` 호출. 현재는 수동 빌드 기준.

# OsoriClient

## 빌드
`powershell
cd <repo>\OsoriMessenger\client-csharp
& "C:\Program Files\dotnet\dotnet.exe" restore
& "C:\Program Files\dotnet\dotnet.exe" build
`

## 실행
`powershell
cd <repo>\OsoriMessenger\client-csharp\src\OsoriClient.Wpf
& "C:\Program Files\dotnet\dotnet.exe" run
`

## 서버 기동 (다른 창)
`powershell
cd <repo>\OsoriMessenger\server
$env:OSORI_PORT = "7071"
.\gradlew.bat run
`

기본 접속 주소는 `ws://localhost:7071/ws` 이며, TLS 서버를 켠 경우 `wss` 체크 후 같은 호스트/포트로 접속하면 된다.

## 엔드투엔드 smoke test 검증용)

WPF UI 없이 `MessengerClient` 가 실제 서버와 통신 가능한지 빠르게 확인하는 콘솔 앱.

`powershell
## 서버 기동 (다른 창)
cd <repo>\OsoriMessenger\server
$env:JAVA_HOME = "<repo>\.tools\jdk-17.0.12+7"
$env:OSORI_PORT = "7071"
.\gradlew.bat run

## smoke test 실행
cd <repo>\OsoriMessenger\client-csharp
& "C:\Program Files\dotnet\dotnet.exe" run --project src\OsoriClient.SmokeTest
`

성공 출력 예:
`
[smoke] alice login status=Ok token=...
[smoke] bob login status=Ok token=...
[smoke] bob presence: user=alice state=Online
[smoke] alice → bob : hello from alice HH:mm:ss
[smoke] bob received: from=alice text=hello from alice HH:mm:ss
[smoke] ack result=Delivered serverMsgId=...
[smoke] OK — 로그인 + 송신 + 수신 전체 검증 통과
`

Exit code 가 0 이면 성공. 2/3 이면 수신 실패 또는 본문 불일치.

## 프로젝트 구조

- `src/OsoriClient.Core` — 와이어 프로토콜 + `MessengerClient` WebSocket 래퍼 (클래스 라이브러리, net8.0)
- `src/OsoriClient.Wpf` — WPF UI (net8.0-windows, WinExe)
- `src/OsoriClient.SmokeTest` — 콘솔 기반 엔드투엔드 검증 (net8.0, 검증용)

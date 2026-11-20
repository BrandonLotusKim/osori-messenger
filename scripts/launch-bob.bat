@echo off
REM 1인 QA 보조 — bob 계정으로 별도 인스턴스 띄우기
REM
REM 사용 시나리오: alice 로 메인 클라 띄워둔 상태에서 이 bat 실행 → 두 번째 창이 bob 로 자동 로그인 →
REM   alice 화면에서 메시지 보내면 bob 창에서 수신 / 그 반대 검증.
REM
REM 환경:
REM   - OSORI_AUTO_LOGIN=bob 만 임시 변경 + Mutex 충돌은 다른 SettingsStore 경로로 우회 불가하므로 동시 두 인스턴스 시도는 첫 번째가 활성화됨.
REM   - 즉 alice 가 떠있으면 이 스크립트는 alice 창을 활성화만 하고 종료 (Mutex 정책)
REM   - **두 사용자 동시 검증은 두 번째 PC 또는 가상머신 또는 다른 윈도우 사용자 세션 필요**.
REM
REM 단일 PC 에서 두 인스턴스 띄우려면 OsoriClient.Wpf 의 SingleInstanceMutexName 가 사용자 SID 단위로 묶인 경우만 가능.
REM 빠른 우회: 다른 윈도우 사용자로 로그인 또는 Sandboxie/UAC trick.

setlocal
set OSORI_AUTO_LOGIN=bob
echo [info] OSORI_AUTO_LOGIN=bob 로 설정. Mutex 가 alice 인스턴스를 차단하면 두 번째 창 안 뜸.
echo [info] 두 사용자 동시 검증은 PC2 또는 별도 윈도우 계정 사용 권장.
start "" "%~dp0..\client-csharp\src\OsoriClient.Wpf\bin\Debug\net8.0-windows\OsoriClient.Wpf.exe"
endlocal

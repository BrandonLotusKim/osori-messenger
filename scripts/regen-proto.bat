@echo off
REM 오소리 메신저 — Protobuf 양쪽 재생성 (Windows 배치 버전)
REM 사용:
REM   scripts\regen-proto.bat              양쪽 다 재생성
REM   scripts\regen-proto.bat --kotlin     서버만
REM   scripts\regen-proto.bat --csharp     클라만

setlocal
set ROOT_DIR=%~dp0..
set PROTO_FILE=%ROOT_DIR%\proto\messenger.proto

if not exist "%PROTO_FILE%" (
  echo [regen-proto] FAIL — proto 파일을 찾을 수 없습니다: %PROTO_FILE%
  exit /b 1
)

set DO_KOTLIN=1
set DO_CSHARP=1
if "%~1"=="--kotlin" set DO_CSHARP=0
if "%~1"=="--csharp" set DO_KOTLIN=0

echo [regen-proto] proto = %PROTO_FILE%

if %DO_KOTLIN%==1 (
  echo [regen-proto] (1/2) Kotlin 서버 generateProto...
  pushd "%ROOT_DIR%\server"
  call gradlew.bat generateProto --quiet
  if errorlevel 1 (
    popd
    echo [regen-proto] FAIL — Kotlin generateProto 실패
    exit /b 1
  )
  popd
  echo [regen-proto] (1/2) Kotlin OK
)

if %DO_CSHARP%==1 (
  echo [regen-proto] (2/2) C# 클라 dotnet build...
  set "DOTNET=C:\Program Files\dotnet\dotnet.exe"
  if not exist "%DOTNET%" set DOTNET=dotnet
  "%DOTNET%" build "%ROOT_DIR%\client-csharp\src\OsoriClient.Core\OsoriClient.Core.csproj" --nologo --verbosity quiet
  if errorlevel 1 (
    echo [regen-proto] FAIL — C# build 실패
    exit /b 1
  )
  echo [regen-proto] (2/2) C# OK
)

echo [regen-proto] DONE — proto 양쪽 재생성 완료.
endlocal

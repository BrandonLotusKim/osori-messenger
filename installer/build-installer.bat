@echo off
REM 오소리 메신저 인스톨러 빌드 자동화 —
REM
REM 단계:
REM   1. dotnet publish (Release, win-x64, self-contained=false → .NET 8 Desktop Runtime 사전 필요)
REM   2. ISCC.exe 로 .iss 컴파일
REM
REM 사전 설치:
REM   - .NET 8 SDK
REM   - Inno Setup 6 (https://jrsoftware.org/isdl.php, 무료 ~2MB)

setlocal enabledelayedexpansion

REM ============== 환경 변수 ==============
set ROOT=%~dp0..
set WPF_PROJ=%ROOT%\client-csharp\src\OsoriClient.Wpf\OsoriClient.Wpf.csproj
set PUBLISH_DIR=%ROOT%\client-csharp\src\OsoriClient.Wpf\bin\Release\net8.0-windows\win-x64\publish
set ISS=%~dp0OsoriClient.iss
set OUTPUT=%~dp0Output

REM ============== Inno Setup 자동 탐색 ==============
set ISCC=
if exist "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" set ISCC=C:\Program Files (x86)\Inno Setup 6\ISCC.exe
if exist "C:\Program Files\Inno Setup 6\ISCC.exe"        set ISCC=C:\Program Files\Inno Setup 6\ISCC.exe
REM 휴대용 설치본 등 다른 위치는 ISCC_PATH 환경변수로 지정
if defined ISCC_PATH if exist "%ISCC_PATH%" set ISCC=%ISCC_PATH%
if "%ISCC%"=="" (
    echo [ERROR] Inno Setup 6 가 설치되어 있지 않습니다.
    echo         https://jrsoftware.org/isdl.php 에서 Inno Setup 6 설치 후 재실행.
    exit /b 1
)
echo [info] ISCC = %ISCC%

REM ============== 1. dotnet publish ==============
echo.
echo [1/2] dotnet publish (Release, win-x64)...
dotnet publish "%WPF_PROJ%" -c Release -r win-x64 --self-contained false -p:PublishSingleFile=false
if errorlevel 1 (
    echo [ERROR] dotnet publish 실패
    exit /b 1
)
if not exist "%PUBLISH_DIR%\OsoriClient.Wpf.exe" (
    echo [ERROR] publish 결과 exe 가 보이지 않음: %PUBLISH_DIR%\OsoriClient.Wpf.exe
    exit /b 1
)

REM ============== 2. ISCC 컴파일 ==============
echo.
echo [2/2] Inno Setup 컴파일...
if not exist "%OUTPUT%" mkdir "%OUTPUT%"
"%ISCC%" /Q "%ISS%"
if errorlevel 1 (
    echo [ERROR] ISCC 컴파일 실패
    exit /b 1
)

echo.
echo ========================================================
echo  성공: %OUTPUT%\OsoriMessenger-Setup-*.exe
echo ========================================================
dir "%OUTPUT%\OsoriMessenger-Setup-*.exe" /B 2>nul
endlocal

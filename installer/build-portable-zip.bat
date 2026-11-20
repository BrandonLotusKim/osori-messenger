@echo off
REM Portable .zip 배포 빌드 — Inno Setup 없이 사용자에게 압축본 전달용
REM
REM 사용: build-portable-zip.bat
REM 결과: installer\Output\OsoriMessenger-Portable-0.1.0.zip
REM
REM 사용자는 .zip 을 풀고 OsoriClient.Wpf.exe 더블클릭만 하면 됨.
REM .NET 8 Desktop Runtime 사전 설치 필요.

setlocal enabledelayedexpansion

set ROOT=%~dp0..
set WPF_PROJ=%ROOT%\client-csharp\src\OsoriClient.Wpf\OsoriClient.Wpf.csproj
set PUBLISH_DIR=%ROOT%\client-csharp\src\OsoriClient.Wpf\bin\Release\net8.0-windows\win-x64\publish
set OUTPUT_DIR=%~dp0Output
set ZIP_NAME=OsoriMessenger-Portable-0.1.0.zip

echo [1/3] dotnet publish (Release, win-x64, self-contained=false)...
dotnet publish "%WPF_PROJ%" -c Release -r win-x64 --self-contained false -p:PublishSingleFile=false
if errorlevel 1 (
    echo [ERROR] dotnet publish 실패
    exit /b 1
)
if not exist "%PUBLISH_DIR%\OsoriClient.Wpf.exe" (
    echo [ERROR] publish 결과 exe 가 보이지 않음: %PUBLISH_DIR%
    exit /b 1
)

echo.
echo [2/3] Output 디렉토리 준비...
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
if exist "%OUTPUT_DIR%\%ZIP_NAME%" del "%OUTPUT_DIR%\%ZIP_NAME%"

echo.
echo [3/3] PowerShell Compress-Archive 로 .zip 생성...
powershell -NoProfile -Command "Compress-Archive -Path '%PUBLISH_DIR%\*' -DestinationPath '%OUTPUT_DIR%\%ZIP_NAME%' -CompressionLevel Optimal"
if errorlevel 1 (
    echo [ERROR] Compress-Archive 실패
    exit /b 1
)

echo.
echo ========================================================
echo  성공: %OUTPUT_DIR%\%ZIP_NAME%
echo  사용자 안내: 압축 풀고 OsoriClient.Wpf.exe 더블클릭
echo               .NET 8 Desktop Runtime 사전 설치 필요
echo ========================================================
dir "%OUTPUT_DIR%\%ZIP_NAME%" /B 2>nul
endlocal

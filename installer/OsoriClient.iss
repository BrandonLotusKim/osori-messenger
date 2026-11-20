; 오소리 메신저 클라이언트 인스톨러 —/241/242
;
; 빌드 사전조건:
;   1. dotnet publish 결과물이 `..\client-csharp\src\OsoriClient.Wpf\bin\Release\net8.0-windows\publish\` 에 있어야 함
;      ( `build-installer.bat` 가 dotnet publish + ISCC 호출까지 한 번에 처리 )
;   2. Inno Setup 6 설치 (https://jrsoftware.org/isdl.php — 무료, ~2MB)
;
; 결과물: `Output\OsoriMessenger-Setup-{version}.exe`

#define MyAppName "오소리 메신저"
#define MyAppNameEn "OsoriMessenger"
#define MyAppVersion "0.1.0"
#define MyAppPublisher "BrandonLotusKim"
#define MyAppURL "https://github.com/BrandonLotusKim"
#define MyAppExeName "OsoriClient.Wpf.exe"

[Setup]
; AppId 는 영구 고정 — 업데이트 시 같은 GUID 유지해야 in-place upgrade 됨
AppId={{2C58E9F5-B3A9-49DC-899A-79268948BE68}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={autopf}\{#MyAppNameEn}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
LicenseFile=
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputDir=Output
OutputBaseFilename=OsoriMessenger-Setup-{#MyAppVersion}
Compression=lzma2/ultra
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
; Win10 1809(17763) 이상 — WindowChrome dark + WinForms NotifyIcon 정상 동작 최소 사양
MinVersion=10.0.17763
UninstallDisplayIcon={app}\{#MyAppExeName}
SetupLogging=yes

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "startupicon"; Description: "Windows 시작 시 자동 실행"; GroupDescription: "추가 옵션:"; Flags: unchecked
Name: "quicklaunchicon"; Description: "{cm:CreateQuickLaunchIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked; OnlyBelowVersion: 6.1

[Files]
; dotnet publish 결과물 일괄 포함. RID(win-x64) 하위 publish/ 가 실제 출력 경로.
Source: "..\client-csharp\src\OsoriClient.Wpf\bin\Release\net8.0-windows\win-x64\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Registry]
; 자동시작 — Tasks 에서 startupicon 체크 시 HKCU\Run 에 등록 (사용자 단위)
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "OsoriMessenger"; ValueData: """{app}\{#MyAppExeName}"""; Flags: uninsdeletevalue; Tasks: startupicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; 빌드 시 함께 떨어지는 부산물만 정리. 사용자 설정/캐시는 [Code] 에서 분기 처리.
Type: filesandordirs; Name: "{app}\logs"
Type: filesandordirs; Name: "{app}\storage"

[Code]
// 제거 시 사용자 데이터(설정/로그/다운로드 캐시) 보존/삭제 선택
var
  CleanupPage: TInputOptionWizardPage;
  KeepUserData: Boolean;

procedure InitializeUninstallProgressForm();
var
  Form: TSetupForm;
  CancelBtn, OKBtn: TNewButton;
  Lbl: TNewStaticText;
  Choice: TNewRadioButton;
  Choice2: TNewRadioButton;
  Selected: Integer;
begin
  KeepUserData := True; // 기본은 보존(안전).
  // 한 번 묻고 끝 — 별도 폼 없이 MessageBox 분기로 단순화
  case MsgBox('사용자 설정과 로그 파일도 함께 삭제하시겠습니까?' + #13#10#13#10 +
              '아니오 = 다음 설치 시 그대로 복원 (권장)' + #13#10 +
              '예    = 모든 데이터 삭제 (초기화)',
              mbConfirmation, MB_YESNO or MB_DEFBUTTON2) of
    IDYES: KeepUserData := False;
    IDNO:  KeepUserData := True;
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  AppData: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    if not KeepUserData then
    begin
      AppData := ExpandConstant('{localappdata}\OsoriMessenger');
      if DirExists(AppData) then
        DelTree(AppData, True, True, True);
    end;
  end;
end;

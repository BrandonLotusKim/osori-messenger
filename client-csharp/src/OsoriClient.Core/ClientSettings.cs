using System.Text.Json;
using System.Text.Json.Serialization;

namespace OsoriClient.Core;

/// <summary>
/// 창 위치/크기 저장용 POCO.
/// WPF Rect/Size 는 STA 의존이라 Core 레이어에선 직접 못 쓰므로 분리.
/// </summary>
public sealed class WindowBounds
{
    public double X { get; set; }
    public double Y { get; set; }
    public double Width { get; set; }
    public double Height { get; set; }
    public bool Maximized { get; set; }
}

/// <summary>
/// 클라이언트 환경설정 POCO.
/// 운영 중 사용자가 변경 가능한 모든 옵션을 한 구조체에 모은다.
/// 최초 설계, OC-D01~D03 재디자인에 맞춰 필드 확장.
/// </summary>
public sealed class ClientSettings
{
    // ── 서버 연결 ──
    public string ServerHost { get; set; } = "127.0.0.1";
    public int ServerPort { get; set; } = 7071;
    public bool UseTls { get; set; } = false;

    // ── 로그인 ──
    // 2026-09-17: LastUserId/AutoLoginEnabled/RememberPasswordEnabled/EncryptedPassword 는
    // LoginCredentialsStore(login_credentials.json) 로 이전했다 — 이 파일(ClientSettings)은
    // OSORI_PROFILE(멀티 인스턴스 격리)에 따라 경로가 갈리는데, multi 모드는 프로필을 프로세스
    // PID 로 만들어서 실행할 때마다 새 프로필이 되다 보니 로그인 자격증명이 절대 재사용될 수
    // 없었다(OsoriClientMulti 로 실행하면 마지막 아이디/자동로그인/비밀번호 저장이 전부 깨지는
    // 버그의 원인). 로그인 자격증명은 프로필과 무관하게 항상 같은 파일에 저장해야 한다.

    /// <summary>OC-D01: 최근 로그인한 사용자 ID 목록 (최신순, 최대 5개). ComboBox 드롭다운 소스.</summary>
    public List<string> RecentUserIds { get; set; } = new();

    // ── 신뢰성 ──
    public bool AutoReconnect { get; set; } = true;
    public bool AutoSyncHistoryOnLogin { get; set; } = true;
    public int HistoryPageSize { get; set; } = 50;
    public bool AutoLoadOrgOnLogin { get; set; } = true;

    // ── 알림 ──
    public bool PlaySoundOnMessage { get; set; } = true;
    public bool ShowTrayNotification { get; set; } = true;
    /// <summary> 채널별 알림 — 1:1 메시지.</summary>
    public bool NotifyDirectMessages { get; set; } = true;
    /// <summary> 채널별 알림 — 그룹 채팅.</summary>
    public bool NotifyRoomMessages { get; set; } = true;
    /// <summary> 채널별 알림 — 쪽지.</summary>
    public bool NotifyMessages { get; set; } = true;
    /// <summary> 채널별 알림 — 파일 도착.</summary>
    public bool NotifyFiles { get; set; } = true;

    // ── 표시 ──
    public int FontSize { get; set; } = 14;
    public string Theme { get; set; } = "light";

    // ── 창 상태 (OC-D02) ──
    /// <summary>MainWindow 의 마지막 위치/크기.</summary>
    public WindowBounds? MainWindowBounds { get; set; }

    /// <summary>TalkWindow 의 마지막 위치/크기 (전역 1개 공유).</summary>
    public WindowBounds? TalkWindowBounds { get; set; }

    /// <summary>창 닫기(X) 버튼을 눌렀을 때 트레이로 최소화할지.</summary>
    public bool CloseToTray { get; set; } = true;

    /// <summary>OC-D02: 마지막으로 선택한 MainWindow 탭. "talk" | "org" | "settings". 디폴트=조직도.</summary>
    public string LastSelectedTab { get; set; } = "org";
}

/// <summary>
/// 클라이언트 환경설정 로컬 영속 저장.
///
/// 기본 경로: %LOCALAPPDATA%\OsoriClient\settings.json
///
/// 정책:
///   - JSON (System.Text.Json) 직렬화
///   - thread-safe (lock)
///   - 로드 실패 시 기본값으로 안전 복구
///   - 저장 실패는 호출자로 전파
///   - OC-D01~D03 재디자인에 맞춰 필드 증분 — 구 JSON 도 기본값으로 역호환
/// </summary>
public sealed class SettingsStore
{
    private readonly string _path;
    private readonly object _lock = new();
    private ClientSettings _settings;

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    /// <summary>설정 변경 시 발화 (UI 가 구독해서 테마/글꼴 실시간 반영에 사용).</summary>
    public event EventHandler? SettingsChanged;

    public SettingsStore(string? customPath = null)
    {
        _path = customPath ?? DefaultPath();
        _settings = Load(_path);
    }

    public ClientSettings Current
    {
        get
        {
            lock (_lock)
            {
                // 2026-09-17 버그 수정: LoginWindow/MainWindow/TalkWindow 가 각자 자기만의
                // SettingsStore 인스턴스를 만들어 쓰는데, 예전엔 생성 시점에 딱 한 번만 파일을
                // 읽어서 그 인스턴스가 살아있는 내내 메모리 캐시를 그대로 썼다. 그래서 한 창이
                // 방금 저장한 값(예: 로그인 성공 시 자동로그인/비밀번호)을 이미 떠 있던 다른 창이
                // 자기 캐시 기준으로 다시 저장하면서 통째로 덮어써 없애버리는 경우가 있었다.
                // 매번 디스크에서 다시 읽어서 항상 최신 상태를 반영하도록 바꿨다.
                _settings = Load(_path);
                return Clone(_settings);
            }
        }
    }

    public void Update(Action<ClientSettings> modify)
    {
        lock (_lock)
        {
            // 위 Current 와 동일한 이유로, 수정 전에 디스크의 최신 상태를 먼저 반영한다 —
            // 안 그러면 이 인스턴스가 생성된 이후 다른 인스턴스가 저장한 값을 이 Update() 가
            // (자신이 건드리지 않은 필드까지 포함해서) 통째로 되돌려버릴 수 있다.
            _settings = Load(_path);
            modify(_settings);
            Save();
        }
        SettingsChanged?.Invoke(this, EventArgs.Empty);
    }

    public void Replace(ClientSettings newSettings)
    {
        lock (_lock)
        {
            _settings = Clone(newSettings);
            Save();
        }
        SettingsChanged?.Invoke(this, EventArgs.Empty);
    }

    public void ResetToDefaults()
    {
        lock (_lock)
        {
            _settings = new ClientSettings();
            Save();
        }
        SettingsChanged?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>
    /// OC-D01: 로그인 성공 시 호출. RecentUserIds 상단으로 이동 + 최대 5개로 트림.
    /// (LastUserId 자체는 2026-09-17 부로 LoginCredentialsStore 로 이전됨.)
    /// </summary>
    public void RecordSuccessfulLogin(string userId)
    {
        if (string.IsNullOrWhiteSpace(userId)) return;
        Update(s =>
        {
            s.RecentUserIds.RemoveAll(u => string.Equals(u, userId, StringComparison.OrdinalIgnoreCase));
            s.RecentUserIds.Insert(0, userId);
            if (s.RecentUserIds.Count > 5)
            {
                s.RecentUserIds.RemoveRange(5, s.RecentUserIds.Count - 5);
            }
        });
    }

    private void Save()
    {
        var dir = Path.GetDirectoryName(_path);
        if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
        {
            Directory.CreateDirectory(dir);
        }
        var json = JsonSerializer.Serialize(_settings, JsonOpts);
        File.WriteAllText(_path, json);
    }

    private static ClientSettings Load(string path)
    {
        try
        {
            if (!File.Exists(path)) return new ClientSettings();
            var json = File.ReadAllText(path);
            if (string.IsNullOrWhiteSpace(json)) return new ClientSettings();
            return JsonSerializer.Deserialize<ClientSettings>(json, JsonOpts) ?? new ClientSettings();
        }
        catch
        {
            return new ClientSettings();
        }
    }

    private static ClientSettings Clone(ClientSettings src) => new()
    {
        ServerHost = src.ServerHost,
        ServerPort = src.ServerPort,
        UseTls = src.UseTls,
        RecentUserIds = new List<string>(src.RecentUserIds),
        AutoReconnect = src.AutoReconnect,
        PlaySoundOnMessage = src.PlaySoundOnMessage,
        ShowTrayNotification = src.ShowTrayNotification,
        NotifyDirectMessages = src.NotifyDirectMessages,
        NotifyRoomMessages = src.NotifyRoomMessages,
        NotifyMessages = src.NotifyMessages,
        NotifyFiles = src.NotifyFiles,
        FontSize = src.FontSize,
        Theme = src.Theme,
        AutoSyncHistoryOnLogin = src.AutoSyncHistoryOnLogin,
        HistoryPageSize = src.HistoryPageSize,
        AutoLoadOrgOnLogin = src.AutoLoadOrgOnLogin,
        MainWindowBounds = src.MainWindowBounds is null ? null : new WindowBounds
        {
            X = src.MainWindowBounds.X,
            Y = src.MainWindowBounds.Y,
            Width = src.MainWindowBounds.Width,
            Height = src.MainWindowBounds.Height,
            Maximized = src.MainWindowBounds.Maximized,
        },
        TalkWindowBounds = src.TalkWindowBounds is null ? null : new WindowBounds
        {
            X = src.TalkWindowBounds.X,
            Y = src.TalkWindowBounds.Y,
            Width = src.TalkWindowBounds.Width,
            Height = src.TalkWindowBounds.Height,
            Maximized = src.TalkWindowBounds.Maximized,
        },
        CloseToTray = src.CloseToTray,
        LastSelectedTab = src.LastSelectedTab,
    };

    private static string DefaultPath()
        => Path.Combine(ClientPaths.BaseDir, "settings.json");
}

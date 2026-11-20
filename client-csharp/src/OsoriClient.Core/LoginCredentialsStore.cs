using System.Text.Json;
using System.Text.Json.Serialization;

namespace OsoriClient.Core;

/// <summary>
/// 2026-09-17 추가 — 로그인 자격증명(마지막 아이디/자동로그인/비밀번호 저장) 전용 저장소.
///
/// ClientSettings(SettingsStore)와 분리한 이유: 그 쪽은 OSORI_PROFILE(멀티 인스턴스 격리)에
/// 따라 경로가 갈리는데, multi 모드는 프로필 키를 프로세스 PID로 만들어서(App.xaml.cs) 실행할
/// 때마다 새 프로필이 된다. 그러면 로그인 성공 직후 저장한 자동로그인/비밀번호가 절대 재사용될
/// 수 없어서, "OsoriClientMulti 로 실행하면 마지막 아이디 자동입력/자동로그인/비밀번호 저장이
/// 전부 안 된다"는 버그가 됐다. 로그인 자격증명은 "이 계정으로 마지막에 로그인했다"는 사실
/// 자체가 중요하지 어느 프로세스/프로필에서 로그인했는지는 무관하므로, OSORI_PROFILE 을 아예
/// 안 타는 [ClientPaths.SharedRootDir] 에 고정 저장해서 single/multi 모드 모두, 그리고 multi
/// 모드의 매 실행(=매번 다른 PID)에도 동일하게 마지막 로그인 정보를 재사용할 수 있게 한다.
/// </summary>
public sealed class LoginCredentials
{
    public string LastUserId { get; set; } = "";
    public bool AutoLoginEnabled { get; set; } = false;
    public bool RememberPasswordEnabled { get; set; } = false;
    public string? EncryptedPassword { get; set; }
}

public sealed class LoginCredentialsStore
{
    private readonly string _path;
    private readonly object _lock = new();

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public LoginCredentialsStore(string? customPath = null)
    {
        _path = customPath ?? Path.Combine(ClientPaths.SharedRootDir, "login_credentials.json");
    }

    /// <summary>매번 디스크에서 다시 읽는다 — ClientSettings 와 같은 이유(여러 인스턴스가
    /// 동시에 이 파일을 쓸 수 있으므로 메모리 캐시를 신뢰하지 않는다).</summary>
    public LoginCredentials Current
    {
        get
        {
            lock (_lock)
            {
                return Load(_path);
            }
        }
    }

    public void Update(Action<LoginCredentials> modify)
    {
        lock (_lock)
        {
            var current = Load(_path);
            modify(current);
            Save(current);
        }
    }

    private void Save(LoginCredentials c)
    {
        var dir = Path.GetDirectoryName(_path);
        if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
        {
            Directory.CreateDirectory(dir);
        }
        var json = JsonSerializer.Serialize(c, JsonOpts);
        File.WriteAllText(_path, json);
    }

    private static LoginCredentials Load(string path)
    {
        try
        {
            if (!File.Exists(path)) return new LoginCredentials();
            var json = File.ReadAllText(path);
            if (string.IsNullOrWhiteSpace(json)) return new LoginCredentials();
            return JsonSerializer.Deserialize<LoginCredentials>(json, JsonOpts) ?? new LoginCredentials();
        }
        catch
        {
            return new LoginCredentials();
        }
    }
}

using System.Text.Json;
using System.Text.Json.Serialization;

namespace OsoriClient.Core;

// 사용자별 마지막 수신 메시지의 server_ts 를 로컬 파일에 영속화.
//: 재접속 시 증분 히스토리 pull 을 위한 sinceMillis 기준.
// 파일 경로 기본값: %LOCALAPPDATA%\OsoriClient\last_sync.json
public sealed class LastSyncStore
{
    private readonly string _path;
    private readonly object _lock = new();
    private readonly Dictionary<string, long> _users;

    public LastSyncStore(string? customPath = null)
    {
        _path = customPath ?? DefaultPath();
        _users = Load(_path);
    }

    public long Get(string userId)
    {
        lock (_lock)
        {
            return _users.TryGetValue(userId, out var ts) ? ts : 0L;
        }
    }

    public void Set(string userId, long serverTs)
    {
        lock (_lock)
        {
            // 단조 증가만 허용 — 과거 값으로 덮어쓰지 않도록 보호
            if (_users.TryGetValue(userId, out var existing) && existing >= serverTs)
            {
                return;
            }

            _users[userId] = serverTs;
            Save();
        }
    }

    public void Clear(string userId)
    {
        lock (_lock)
        {
            if (_users.Remove(userId))
            {
                Save();
            }
        }
    }

    private static string DefaultPath()
        => Path.Combine(ClientPaths.BaseDir, "last_sync.json");

    private static Dictionary<string, long> Load(string path)
    {
        try
        {
            if (!File.Exists(path))
            {
                return new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            }

            var json = File.ReadAllText(path);
            if (string.IsNullOrWhiteSpace(json))
            {
                return new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            }

            var parsed = JsonSerializer.Deserialize<LastSyncFile>(json);
            if (parsed?.Users is null)
            {
                return new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            }

            return new Dictionary<string, long>(parsed.Users, StringComparer.OrdinalIgnoreCase);
        }
        catch (Exception ex)
        {
            // 파싱 실패 시 빈 상태로 복구 — 예외 전파 금지
            Console.Error.WriteLine($"[LastSyncStore] load failed path={path} error={ex.Message}");
            return new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
        }
    }

    private void Save()
    {
        try
        {
            var dir = Path.GetDirectoryName(_path);
            if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
            {
                Directory.CreateDirectory(dir);
            }

            var dto = new LastSyncFile
            {
                Users = new Dictionary<string, long>(_users, StringComparer.OrdinalIgnoreCase)
            };
            var json = JsonSerializer.Serialize(dto, SerializerOptions);
            File.WriteAllText(_path, json);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[LastSyncStore] save failed path={_path} error={ex.Message}");
        }
    }

    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        WriteIndented = true
    };

    private sealed class LastSyncFile
    {
        [JsonPropertyName("users")]
        public Dictionary<string, long>? Users { get; set; }
    }
}

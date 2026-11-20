using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace OsoriClient.Wpf.Helpers;

/// <summary>
///: 사용자별 대화 목록 영속 캐시. `%LOCALAPPDATA%\OsoriClient\conversation_cache_{userId}.json`.
/// 서버에 대화 목록 API 가 없으므로 클라가 메모리 + 파일로 운영. 로그인 시 Load → 갱신 → 종료 시 Save.
/// </summary>
public sealed class TalkCacheRecord
{
    /// <summary>대화 식별 키. 1:1 = userId, 그룹방 = "room:{roomId}".</summary>
    public string TalkKey { get; set; } = "";

    public string DisplayName { get; set; } = "";

    public bool IsRoom { get; set; }

    public string? LastMessageText { get; set; }

    /// <summary>마지막 메시지 server_ts (epoch millis).</summary>
    public long LastMessageTs { get; set; }

    public int UnreadCount { get; set; }

    /// <summary>대화창을 마지막으로 활성화한 시점. 이 이후 수신만 unread 로 카운트.</summary>
    public long LastSeenTs { get; set; }
}

public sealed class TalkCache
{
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly string _filePath;
    private readonly Dictionary<string, TalkCacheRecord> _records = new(StringComparer.Ordinal);
    private readonly object _lock = new();

    public TalkCache(string? userId = null)
    {
        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var dir = Path.Combine(localAppData, "OsoriClient");
        Directory.CreateDirectory(dir);
        var fileName = string.IsNullOrWhiteSpace(userId)
            ? "conversation_cache.json"
            : $"conversation_cache_{Sanitize(userId!)}.json";
        _filePath = Path.Combine(dir, fileName);
    }

    public string FilePath => _filePath;

    public void Load()
    {
        lock (_lock)
        {
            _records.Clear();
            if (!File.Exists(_filePath))
            {
                return;
            }
            try
            {
                var json = File.ReadAllText(_filePath);
                var list = JsonSerializer.Deserialize<List<TalkCacheRecord>>(json, JsonOpts);
                if (list is null)
                {
                    return;
                }
                foreach (var r in list)
                {
                    if (!string.IsNullOrEmpty(r.TalkKey))
                    {
                        _records[r.TalkKey] = r;
                    }
                }
            }
            catch
            {
                // 손상된 파일은 무시하고 빈 캐시로 시작.
            }
        }
    }

    public void Save()
    {
        List<TalkCacheRecord> snapshot;
        lock (_lock)
        {
            snapshot = _records.Values.ToList();
        }
        try
        {
            var json = JsonSerializer.Serialize(snapshot, JsonOpts);
            File.WriteAllText(_filePath, json);
        }
        catch
        {
            // 저장 실패는 조용히 무시 — 종료 시 다시 시도.
        }
    }

    public IReadOnlyList<TalkCacheRecord> GetAll()
    {
        lock (_lock)
        {
            return _records.Values.ToList();
        }
    }

    public TalkCacheRecord? Get(string key)
    {
        lock (_lock)
        {
            return _records.TryGetValue(key, out var r) ? r : null;
        }
    }

    public void Upsert(TalkCacheRecord record)
    {
        if (string.IsNullOrEmpty(record.TalkKey))
        {
            return;
        }
        lock (_lock)
        {
            _records[record.TalkKey] = record;
        }
    }

    public void Remove(string key)
    {
        lock (_lock)
        {
            _records.Remove(key);
        }
    }

    /// <summary>대화창을 활성화했을 때 호출. unread=0 + lastSeenTs 갱신.</summary>
    public void MarkRead(string key, long lastSeenTs)
    {
        lock (_lock)
        {
            if (_records.TryGetValue(key, out var r))
            {
                r.UnreadCount = 0;
                r.LastSeenTs = lastSeenTs;
            }
        }
    }

    /// <summary>새 메시지 수신 시 호출. 활성 창이 아니면 unread 증가.</summary>
    public void NotifyIncoming(string key, string previewText, long messageTs, bool isActiveWindow)
    {
        lock (_lock)
        {
            if (!_records.TryGetValue(key, out var r))
            {
                r = new TalkCacheRecord { TalkKey = key };
                _records[key] = r;
            }
            r.LastMessageText = previewText;
            r.LastMessageTs = messageTs;
            if (!isActiveWindow)
            {
                r.UnreadCount++;
            }
        }
    }

    private static string Sanitize(string s)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var chars = s.Where(c => !invalid.Contains(c)).ToArray();
        return chars.Length == 0 ? "default" : new string(chars);
    }
}

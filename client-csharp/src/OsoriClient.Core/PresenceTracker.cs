using System.Collections.Concurrent;
using Osori.Proto.V1;

namespace OsoriClient.Core;

/// <summary>
/// 서버가 보내는 PresenceEvent(로그인 직후 온라인 목록 스냅샷 + 이후 변경)를 누적해
/// 현재 온라인 사용자 집합을 유지한다. 화면(조직도)이 늦게 열려도 스냅샷을 잃지 않게
/// 이벤트 구독과 별개로 클라이언트가 들고 있는다.
/// </summary>
public sealed class PresenceTracker
{
    private readonly ConcurrentDictionary<string, byte> _online = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>이벤트를 반영한다. 상태가 실제로 바뀌었으면 true.</summary>
    public bool Apply(PresenceEvent e)
    {
        if (string.IsNullOrEmpty(e.UserId)) return false;
        return e.State switch
        {
            PresenceEvent.Types.State.Online => _online.TryAdd(e.UserId, 0),
            PresenceEvent.Types.State.Offline => _online.TryRemove(e.UserId, out _),
            _ => false,
        };
    }

    public bool IsOnline(string userId) => !string.IsNullOrEmpty(userId) && _online.ContainsKey(userId);

    public IReadOnlyCollection<string> Snapshot() => _online.Keys.ToList();

    /// <summary>새 로그인 전에 비운다(서버가 로그인 직후 스냅샷을 다시 보낸다).</summary>
    public void Clear() => _online.Clear();
}

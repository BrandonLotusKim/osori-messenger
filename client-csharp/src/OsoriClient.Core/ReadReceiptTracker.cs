using System;
using System.Collections.Generic;
using System.Linq;

namespace OsoriClient.Core;

/// <summary>
/// 한 방의 멤버별 읽음 위치(마지막으로 읽은 메시지의 서버 시각)를 들고, 내가 보낸 메시지의
/// "안 읽은 인원"을 계산한다. 서버 값은 앞으로만 움직이므로 여기서도 더 큰 값만 반영한다.
/// </summary>
public sealed class ReadReceiptTracker
{
    private readonly Dictionary<string, long> _lastRead = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>읽음 위치를 반영한다. 실제로 앞으로 움직였으면 true.</summary>
    public bool Apply(string userId, long lastReadTs)
    {
        if (string.IsNullOrEmpty(userId) || lastReadTs <= 0) return false;
        if (_lastRead.TryGetValue(userId, out var cur) && cur >= lastReadTs) return false;
        _lastRead[userId] = lastReadTs;
        return true;
    }

    public long LastRead(string userId) => _lastRead.TryGetValue(userId, out var v) ? v : 0;

    /// <summary>
    /// 이 메시지를 아직 안 읽은 멤버 수 — 보낸 사람과 나를 뺀 현재 멤버 중 읽음 위치가 메시지 시각보다 앞인 사람.
    /// 시각을 모르는(아직 전송 중) 메시지는 null.
    /// </summary>
    public int? UnreadCount(long messageTs, string senderId, IEnumerable<string> members)
    {
        if (messageTs <= 0) return null;
        return members
            .Where(m => !string.Equals(m, senderId, StringComparison.OrdinalIgnoreCase))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Count(m => LastRead(m) < messageTs);
    }

    /// <summary>보낸 메시지 옆 표시 문구: 안 읽은 사람이 있으면 그 수, 모두 읽었으면 "읽음".</summary>
    public static string Label(int? unread) => unread switch
    {
        null => "",
        0 => "읽음",
        var n => n.Value.ToString(System.Globalization.CultureInfo.InvariantCulture),
    };

    /// <summary>
    /// 읽음 보고할 시각: 화면에 있는 메시지 중 남이 보낸 가장 최근 메시지의 시각(내 메시지는 이미 읽은 것으로 처리됨).
    /// 이미 보고한 값 이하이면 0(보고 안 함).
    /// </summary>
    public static long NextReportTs(IEnumerable<(long ts, string sender)> messages, string me, long alreadyReported)
    {
        var max = 0L;
        foreach (var (ts, sender) in messages)
        {
            if (string.Equals(sender, me, StringComparison.OrdinalIgnoreCase)) continue;
            if (ts > max) max = ts;
        }
        return max > alreadyReported ? max : 0;
    }
}

using System;
using System.Collections.Generic;
using System.Linq;

namespace OsoriClient.Core;

/// <summary>
/// 방 멤버 목록에서 1:1 상대를 판별하는 순수 함수 모음.
/// 1:1 대화도 방(Room)으로 통합된 뒤, 상대 사용자 ID 가 필요한 기능(파일 첨부 등)이 쓴다.
/// </summary>
public static class RoomPeers
{
    /// <summary>
    /// 멤버가 정확히 2명(본인 + 상대)이면 상대 ID 를 돌려준다. 그 외(그룹, 본인만, 목록 미수신)는 null.
    /// </summary>
    public static string? TryGetDirectCounterpart(IEnumerable<string>? memberIds, string selfUserId)
    {
        if (memberIds is null) return null;
        var distinct = memberIds
            .Where(m => !string.IsNullOrWhiteSpace(m))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        if (distinct.Count != 2) return null;
        if (!distinct.Any(m => string.Equals(m, selfUserId, StringComparison.OrdinalIgnoreCase))) return null;
        return distinct.First(m => !string.Equals(m, selfUserId, StringComparison.OrdinalIgnoreCase));
    }
}

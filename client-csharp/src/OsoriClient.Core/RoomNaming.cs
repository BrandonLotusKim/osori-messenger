namespace OsoriClient.Core;

/// <summary>
/// 대화방 표시 이름 규칙. 대화 목록·대화창·검색 결과가 모두 이 함수를 써서 같은 방은 같은 이름으로 보인다.
/// 자동 명명은 멤버 순서(서버 응답마다 다를 수 있음)에 흔들리지 않도록 아이디 오름차순으로 정렬한다.
/// </summary>
public static class RoomNaming
{
    /// <summary>
    /// 자동 이름 — 혼자면 "나와의 대화", 2~3명이면 아이디 나열, 4명 이상이면 "첫 아이디 외 N명"
    /// (N = 표시된 한 명을 뺀 나머지 전원, 나 포함).
    /// </summary>
    public static string AutoName(IEnumerable<string> otherMemberIds)
    {
        var others = Normalize(otherMemberIds);
        if (others.Count == 0) return "나와의 대화";
        if (others.Count <= 3) return string.Join(", ", others);
        return $"{others[0]} 외 {others.Count}명";
    }

    /// <summary>
    /// 수동으로 바꾼 이름이면 서버 이름 그대로, 자동 이름이면 "나"를 뺀 멤버로 다시 계산한다
    /// (서버는 방장 기준으로 한 번만 계산하므로). 멤버 목록이 비어 있으면 서버 이름 → 대체 키.
    /// </summary>
    public static string Resolve(string? serverName, bool nameIsCustom, IEnumerable<string> memberIds, string currentUserId, string fallbackKey)
    {
        if (nameIsCustom) return string.IsNullOrWhiteSpace(serverName) ? fallbackKey : serverName!;
        var others = memberIds
            .Where(id => !string.Equals(id, currentUserId, StringComparison.OrdinalIgnoreCase))
            .ToList();
        if (others.Count == 0)
        {
            return string.IsNullOrWhiteSpace(serverName) ? fallbackKey : serverName!;
        }
        return AutoName(others);
    }

    private static List<string> Normalize(IEnumerable<string> ids) => ids
        .Where(id => !string.IsNullOrWhiteSpace(id))
        .Distinct(StringComparer.OrdinalIgnoreCase)
        .OrderBy(id => id, StringComparer.OrdinalIgnoreCase)
        .ThenBy(id => id, StringComparer.Ordinal)
        .ToList();
}

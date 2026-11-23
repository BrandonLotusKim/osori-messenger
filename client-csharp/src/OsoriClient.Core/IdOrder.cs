namespace OsoriClient.Core;

/// <summary>
/// 사람이 읽는 순서의 아이디 비교: 숫자 부분은 크기로 비교한다("test7" &lt; "test9" &lt; "test10").
/// 대화방 자동 이름, 사람 선택 목록, 쪽지 받는 사람, 멤버 목록이 모두 이 순서를 쓴다(대소문자 무시).
/// </summary>
public sealed class IdOrder : IComparer<string?>
{
    public static readonly IdOrder Instance = new();

    public int Compare(string? x, string? y)
    {
        if (ReferenceEquals(x, y)) return 0;
        if (x is null) return -1;
        if (y is null) return 1;

        int i = 0, j = 0;
        while (i < x.Length && j < y.Length)
        {
            if (char.IsDigit(x[i]) && char.IsDigit(y[j]))
            {
                var si = i; while (i < x.Length && char.IsDigit(x[i])) i++;
                var sj = j; while (j < y.Length && char.IsDigit(y[j])) j++;
                var a = x.AsSpan(si, i - si).TrimStart('0');
                var b = y.AsSpan(sj, j - sj).TrimStart('0');
                if (a.Length != b.Length) return a.Length.CompareTo(b.Length);
                var c = a.SequenceCompareTo(b);
                if (c != 0) return c;
                continue;
            }
            var cx = char.ToUpperInvariant(x[i]);
            var cy = char.ToUpperInvariant(y[j]);
            if (cx != cy) return cx.CompareTo(cy);
            i++; j++;
        }
        var rest = (x.Length - i).CompareTo(y.Length - j);
        return rest != 0 ? rest : string.CompareOrdinal(x, y);
    }

    /// <summary>중복(대소문자 무시)·빈 값을 빼고 이 순서로 정렬한 목록.</summary>
    public static List<string> Sort(IEnumerable<string> ids) => ids
        .Where(id => !string.IsNullOrWhiteSpace(id))
        .Distinct(StringComparer.OrdinalIgnoreCase)
        .OrderBy(id => id, Instance)
        .ToList();

    /// <summary>받는 사람 요약: 한 명이면 그 아이디, 여럿이면 "첫 아이디 외 N명"(이 순서 기준).</summary>
    public static string Summary(IEnumerable<string> ids)
    {
        var sorted = Sort(ids);
        return sorted.Count switch
        {
            0 => string.Empty,
            1 => sorted[0],
            _ => $"{sorted[0]} 외 {sorted.Count - 1}명",
        };
    }
}

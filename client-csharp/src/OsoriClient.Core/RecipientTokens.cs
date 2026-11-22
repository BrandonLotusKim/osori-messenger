using System;
using System.Collections.Generic;
using System.Linq;

namespace OsoriClient.Core;

/// <summary>
/// 쪽지 받는 사람 칩 입력의 순수 로직: 입력 문자열 → 사용자 ID 토큰, 중복 없는 합치기, 칩 표시 문구.
/// </summary>
public static class RecipientTokens
{
    private static readonly char[] Separators = { ',', ';', ' ', '\n', '\r', '\t' };

    public static List<string> Parse(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return new List<string>();
        return text.Split(Separators, StringSplitOptions.RemoveEmptyEntries)
            .Select(s => s.Trim())
            .Where(s => s.Length > 0)
            .ToList();
    }

    /// <summary>기존 순서를 유지하며 새 ID 를 뒤에 붙인다(대소문자 무시 중복 제거).</summary>
    public static List<string> Merge(IEnumerable<string> existing, IEnumerable<string> add)
    {
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var result = new List<string>();
        foreach (var id in existing.Concat(add))
        {
            var t = id?.Trim() ?? "";
            if (t.Length > 0 && seen.Add(t)) result.Add(t);
        }
        return result;
    }

    /// <summary>칩에 보일 문구: 조직도에 있으면 "이름 (id)", 없으면 id 그대로.</summary>
    public static string Label(string userId, IReadOnlyList<OrgTreeNode>? roots)
    {
        var node = roots is null ? null : FindMember(roots, userId);
        if (node is null) return userId;
        // 조직도 표시 이름은 "이름 · 직급" 형태라 칩에서는 직급을 뗀다(칩은 짧게, 직급은 툴팁 몫).
        var name = node.DisplayName ?? "";
        if (!string.IsNullOrEmpty(node.Title) && name.EndsWith(" · " + node.Title, StringComparison.Ordinal))
            name = name[..^(" · " + node.Title).Length];
        name = name.Trim();
        if (name.Length == 0 || string.Equals(name, userId, StringComparison.OrdinalIgnoreCase))
            return userId;
        return $"{name} ({userId})";
    }

    /// <summary>조직도에 있는 사용자인지(없으면 칩을 경고색으로 보인다). 조직도가 없으면 판단하지 않고 true.</summary>
    public static bool IsKnown(string userId, IReadOnlyList<OrgTreeNode>? roots)
        => roots is null || roots.Count == 0 || FindMember(roots, userId) is not null;

    private static OrgTreeNode? FindMember(IEnumerable<OrgTreeNode> nodes, string userId)
    {
        foreach (var n in nodes)
        {
            if (n.Kind == OrgTreeNodeKind.Member && string.Equals(n.UserId, userId, StringComparison.OrdinalIgnoreCase))
                return n;
            var found = FindMember(n.Children, userId);
            if (found is not null) return found;
        }
        return null;
    }
}

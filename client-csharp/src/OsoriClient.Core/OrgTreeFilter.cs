using System;
using System.Collections.Generic;

namespace OsoriClient.Core;

/// <summary>조직도 트리 필터·접속 상태 반영 (WPF 와 무관한 순수 로직).</summary>
public static class OrgTreeFilter
{
    /// <summary>
    /// 이름(DisplayName)에 query 가 들어간 멤버/부서만 남긴 트리를 만든다.
    /// - 멤버 노드는 원본 인스턴스를 그대로 쓴다(선택 상태가 원본에 반영되어야 대화 생성에 쓰임).
    /// - 부서 노드는 복제하고 펼친 상태(IsExpanded=true)로 만들어 결과가 바로 보이게 한다.
    /// - query 가 비어 있으면 원본 roots 를 그대로 돌려준다.
    /// </summary>
    public static IReadOnlyList<OrgTreeNode> Filter(IReadOnlyList<OrgTreeNode> roots, string? query)
    {
        if (string.IsNullOrWhiteSpace(query)) return roots;
        var q = query.Trim();
        return FilterCore(roots, q);
    }

    private static List<OrgTreeNode> FilterCore(IEnumerable<OrgTreeNode> nodes, string q)
    {
        var result = new List<OrgTreeNode>();
        foreach (var n in nodes)
        {
            var match = (n.DisplayName ?? "").Contains(q, StringComparison.OrdinalIgnoreCase);
            if (n.Kind == OrgTreeNodeKind.Member)
            {
                if (match) result.Add(n);
                continue;
            }

            var childMatches = FilterCore(n.Children, q);
            if (match || childMatches.Count > 0)
            {
                var clone = new OrgTreeNode
                {
                    Kind = n.Kind,
                    DisplayName = n.DisplayName ?? "",
                    Path = n.Path,
                    ParentPath = n.ParentPath,
                    Depth = n.Depth,
                    IsExpanded = true,
                };
                // 부서 이름만 일치하면 그 부서 멤버 전체를 보여 준다.
                var children = childMatches.Count > 0 ? (IEnumerable<OrgTreeNode>)childMatches : n.Children;
                foreach (var c in children) clone.Children.Add(c);
                result.Add(clone);
            }
        }
        return result;
    }

    /// <summary>트리 안 멤버 수(필터 결과 0건 안내용).</summary>
    public static int CountMembers(IEnumerable<OrgTreeNode>? nodes)
    {
        if (nodes is null) return 0;
        var count = 0;
        foreach (var n in nodes)
        {
            if (n.Kind == OrgTreeNodeKind.Member) count++;
            else count += CountMembers(n.Children);
        }
        return count;
    }

    /// <summary>모든 멤버의 IsOnline 을 isOnline(userId) 결과로 맞춘다(조직도 새로 받았을 때).</summary>
    public static void ApplyPresence(IEnumerable<OrgTreeNode>? nodes, Func<string, bool> isOnline)
    {
        if (nodes is null) return;
        foreach (var n in nodes)
        {
            if (n.Kind == OrgTreeNodeKind.Member) n.IsOnline = isOnline(n.UserId);
            else ApplyPresence(n.Children, isOnline);
        }
    }

    /// <summary>한 사용자의 접속 상태를 바꾼다(PresenceEvent 수신 시).</summary>
    public static void SetPresence(IEnumerable<OrgTreeNode>? nodes, string userId, bool online)
    {
        if (nodes is null) return;
        foreach (var n in nodes)
        {
            if (n.Kind == OrgTreeNodeKind.Member)
            {
                if (string.Equals(n.UserId, userId, StringComparison.OrdinalIgnoreCase)) n.IsOnline = online;
            }
            else SetPresence(n.Children, userId, online);
        }
    }
}

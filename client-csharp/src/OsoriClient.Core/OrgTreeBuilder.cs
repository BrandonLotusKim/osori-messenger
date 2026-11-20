using System.Collections.Generic;
using System.Linq;
using Osori.Proto.V1;

namespace OsoriClient.Core;

/// <summary>
/// `OrgListResponse` (flat units + flat members) 를 계층 트리로 변환.
///
/// 규칙:
///   - 각 `OrgUnit.Path` 자체가 자연키. `ParentPath = ""` 은 ROOT.
///   - 멤버는 `OrgPath` 가 가리키는 Unit 노드의 자식으로 추가. `OrgPath = ""` 이면 무소속 → 가상 ROOT "(무소속)" 아래.
///   - 부서 목록은 `Depth` + `SortOrder` + `Name` 순, 멤버 목록은 `SortOrder` + `UserId` 순.
///   - 부모가 존재하지 않는 고아 Unit 은 가상 ROOT "(고아 부서)" 아래에 추가.
/// </summary>
public static class OrgTreeBuilder
{
    public const string OrphanUnitsLabel = "(고아 부서)";
    public const string UnassignedMembersLabel = "(무소속)";

    public static IReadOnlyList<OrgTreeNode> Build(OrgListResponse response)
    {
        if (response == null) return new List<OrgTreeNode>();

        // 1) path → node 맵 + ROOT 수집
        var byPath = new Dictionary<string, OrgTreeNode>();
        var sortedUnits = response.Units
            .OrderBy(u => u.Depth)
            .ThenBy(u => u.SortOrder)
            .ThenBy(u => u.Name)
            .ToList();

        foreach (var u in sortedUnits)
        {
            byPath[u.Path] = new OrgTreeNode
            {
                Kind = OrgTreeNodeKind.Unit,
                DisplayName = u.Name,
                Path = u.Path,
                ParentPath = u.ParentPath,
                Depth = u.Depth,
            };
        }

        var roots = new List<OrgTreeNode>();
        OrgTreeNode? orphanBucket = null;

        foreach (var u in sortedUnits)
        {
            var node = byPath[u.Path];
            if (string.IsNullOrEmpty(u.ParentPath))
            {
                roots.Add(node);
            }
            else if (byPath.TryGetValue(u.ParentPath, out var parent))
            {
                parent.Children.Add(node);
            }
            else
            {
                orphanBucket ??= CreateVirtualBucket(OrphanUnitsLabel);
                orphanBucket.Children.Add(node);
            }
        }
        if (orphanBucket != null) roots.Add(orphanBucket);

        // 2) 멤버 배치
        OrgTreeNode? unassignedBucket = null;
        var sortedMembers = response.Members
            .OrderBy(m => m.SortOrder)
            .ThenBy(m => m.UserId)
            .ToList();

        foreach (var m in sortedMembers)
        {
            var memberNode = new OrgTreeNode
            {
                Kind = OrgTreeNodeKind.Member,
                DisplayName = string.IsNullOrEmpty(m.Title) ? m.UserId : $"{m.UserId} · {m.Title}",
                UserId = m.UserId,
                Title = m.Title,
                Email = m.Email,
                Phone = m.Phone,
                Mobile = m.Mobile,
                OrgPath = m.OrgPath,
            };
            if (string.IsNullOrEmpty(m.OrgPath))
            {
                unassignedBucket ??= CreateVirtualBucket(UnassignedMembersLabel);
                unassignedBucket.Children.Add(memberNode);
            }
            else if (byPath.TryGetValue(m.OrgPath, out var unitNode))
            {
                unitNode.Children.Add(memberNode);
            }
            else
            {
                // 존재하지 않는 부서에 속한 멤버는 무소속으로 취급
                unassignedBucket ??= CreateVirtualBucket(UnassignedMembersLabel);
                unassignedBucket.Children.Add(memberNode);
            }
        }
        if (unassignedBucket != null) roots.Add(unassignedBucket);

        return roots;
    }

    private static OrgTreeNode CreateVirtualBucket(string name) => new OrgTreeNode
    {
        Kind = OrgTreeNodeKind.Unit,
        DisplayName = name,
        Path = name,
        ParentPath = "",
        Depth = 0,
    };
}

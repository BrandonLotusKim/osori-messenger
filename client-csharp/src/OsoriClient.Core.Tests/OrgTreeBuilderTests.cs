using Osori.Proto.V1;
using OsoriClient.Core;

namespace OsoriClient.Core.Tests;

/// <summary>
/// OrgTreeBuilder 단위 테스트.
/// flat OrgListResponse → 계층 트리 변환 로직 검증.
/// </summary>
public sealed class OrgTreeBuilderTests
{
    private static OrgUnit U(string path, string parent, string name, int depth, int sort = 0)
        => new() { Path = path, ParentPath = parent, Name = name, Depth = depth, SortOrder = sort };

    private static OrgMember M(string userId, string orgPath, string title = "", int sort = 0)
        => new() { UserId = userId, OrgPath = orgPath, Title = title, SortOrder = sort };

    [Fact]
    public void Build_EmptyResponse_ReturnsEmpty()
    {
        var roots = OrgTreeBuilder.Build(new OrgListResponse());
        Assert.Empty(roots);
    }

    [Fact]
    public void Build_SingleRoot_WithOneMember()
    {
        var resp = new OrgListResponse();
        resp.Units.Add(U("오소리랩", "", "오소리랩", 0));
        resp.Members.Add(M("alice", "오소리랩", "선임"));

        var roots = OrgTreeBuilder.Build(resp);

        Assert.Single(roots);
        var root = roots[0];
        Assert.Equal(OrgTreeNodeKind.Unit, root.Kind);
        Assert.Equal("오소리랩", root.Path);
        Assert.Single(root.Children);

        var member = root.Children[0];
        Assert.Equal(OrgTreeNodeKind.Member, member.Kind);
        Assert.Equal("alice", member.UserId);
        Assert.Contains("선임", member.DisplayName);
    }

    [Fact]
    public void Build_ThreeLevelTree_WithMixedMembers()
    {
        var resp = new OrgListResponse();
        resp.Units.Add(U("오소리랩", "", "오소리랩", 0));
        resp.Units.Add(U("오소리랩/RND", "오소리랩", "RND", 1, sort: 0));
        resp.Units.Add(U("오소리랩/SALES", "오소리랩", "SALES", 1, sort: 1));
        resp.Units.Add(U("오소리랩/RND/PLAT", "오소리랩/RND", "PLAT", 2));
        resp.Members.Add(M("alice", "오소리랩/RND/PLAT", "선임", sort: 1));
        resp.Members.Add(M("bob", "오소리랩/SALES", "과장", sort: 1));

        var roots = OrgTreeBuilder.Build(resp);

        Assert.Single(roots); // 오소리랩 한 개가 ROOT
        var ez = roots[0];
        Assert.Equal("오소리랩", ez.Path);
        Assert.Equal(2, ez.Children.Count); // RND, SALES

        // depth+sortOrder 정렬 — RND sort=0 먼저, SALES sort=1 다음
        Assert.Equal("오소리랩/RND", ez.Children[0].Path);
        Assert.Equal("오소리랩/SALES", ez.Children[1].Path);

        var rnd = ez.Children[0];
        Assert.Single(rnd.Children); // PLAT
        Assert.Equal("오소리랩/RND/PLAT", rnd.Children[0].Path);

        var plat = rnd.Children[0];
        Assert.Single(plat.Children); // alice
        Assert.Equal("alice", plat.Children[0].UserId);

        var sales = ez.Children[1];
        Assert.Single(sales.Children); // bob
        Assert.Equal("bob", sales.Children[0].UserId);
    }

    [Fact]
    public void Build_OrphanUnit_GoesToVirtualBucket()
    {
        var resp = new OrgListResponse();
        resp.Units.Add(U("오소리랩", "", "오소리랩", 0));
        resp.Units.Add(U("오소리랩/RND", "오소리랩", "RND", 1));
        // 존재하지 않는 부모 참조
        resp.Units.Add(U("홍길동팀", "없는부서", "홍길동팀", 1));

        var roots = OrgTreeBuilder.Build(resp);

        // 오소리랩 + (고아 부서) 가상 ROOT
        Assert.Equal(2, roots.Count);
        var orphan = roots.First(r => r.DisplayName == OrgTreeBuilder.OrphanUnitsLabel);
        Assert.Single(orphan.Children);
        Assert.Equal("홍길동팀", orphan.Children[0].Path);
    }

    [Fact]
    public void Build_UnassignedMember_GoesToVirtualBucket()
    {
        var resp = new OrgListResponse();
        resp.Units.Add(U("팀1", "", "팀1", 0));
        resp.Members.Add(M("a1", "팀1"));
        resp.Members.Add(M("solo", "")); // 무소속
        resp.Members.Add(M("ghost", "없는부서")); // 존재하지 않는 부서 → 무소속 취급

        var roots = OrgTreeBuilder.Build(resp);

        // 팀1 + (무소속)
        Assert.Equal(2, roots.Count);
        var team1 = roots.First(r => r.Path == "팀1");
        Assert.Single(team1.Children);
        Assert.Equal("a1", team1.Children[0].UserId);

        var unassigned = roots.First(r => r.DisplayName == OrgTreeBuilder.UnassignedMembersLabel);
        Assert.Equal(2, unassigned.Children.Count);
        Assert.Contains(unassigned.Children, c => c.UserId == "solo");
        Assert.Contains(unassigned.Children, c => c.UserId == "ghost");
    }

    [Fact]
    public void Build_MembersSorted_BySortOrderThenUserId()
    {
        var resp = new OrgListResponse();
        resp.Units.Add(U("team", "", "team", 0));
        resp.Members.Add(M("z", "team", sort: 1));
        resp.Members.Add(M("a", "team", sort: 2));
        resp.Members.Add(M("b", "team", sort: 1));

        var roots = OrgTreeBuilder.Build(resp);
        var team = roots[0];

        // sort=1 먼저(b, z 알파벳순) → sort=2 (a)
        Assert.Equal(3, team.Children.Count);
        Assert.Equal("b", team.Children[0].UserId);
        Assert.Equal("z", team.Children[1].UserId);
        Assert.Equal("a", team.Children[2].UserId);
    }
}

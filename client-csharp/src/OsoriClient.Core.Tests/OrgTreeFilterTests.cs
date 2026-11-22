using Osori.Proto.V1;
using OsoriClient.Core;

namespace OsoriClient.Core.Tests;

/// <summary>조직도 필터 자동 펼침·결과 수·접속 상태 반영, PresenceTracker 검증.</summary>
public sealed class OrgTreeFilterTests
{
    private static IReadOnlyList<OrgTreeNode> Sample()
    {
        var resp = new OrgListResponse();
        resp.Units.Add(new OrgUnit { Path = "오소리", ParentPath = "", Name = "(주)오소리", Depth = 0 });
        resp.Units.Add(new OrgUnit { Path = "오소리/개발", ParentPath = "오소리", Name = "개발팀", Depth = 1 });
        resp.Units.Add(new OrgUnit { Path = "오소리/영업", ParentPath = "오소리", Name = "영업팀", Depth = 1, SortOrder = 1 });
        resp.Members.Add(new OrgMember { UserId = "test1", OrgPath = "오소리/개발", Title = "팀장" });
        resp.Members.Add(new OrgMember { UserId = "test2", OrgPath = "오소리/개발" });
        resp.Members.Add(new OrgMember { UserId = "test3", OrgPath = "오소리/영업" });
        return OrgTreeBuilder.Build(resp);
    }

    [Fact]
    public void Build_TopLevelExpandedByDefault()
    {
        var roots = Sample();
        Assert.True(roots[0].IsExpanded);
        Assert.False(roots[0].Children[0].IsExpanded);
    }

    [Fact]
    public void Filter_EmptyQuery_ReturnsOriginal()
    {
        var roots = Sample();
        Assert.Same(roots, OrgTreeFilter.Filter(roots, "  "));
    }

    [Fact]
    public void Filter_MemberMatch_UnitsExpanded_MemberIsOriginalInstance()
    {
        var roots = Sample();
        var original = roots[0].Children[0].Children.First(n => n.UserId == "test2");

        var result = OrgTreeFilter.Filter(roots, "test2");

        Assert.Single(result);
        Assert.True(result[0].IsExpanded);
        var dept = Assert.Single(result[0].Children);
        Assert.True(dept.IsExpanded);
        var member = Assert.Single(dept.Children);
        Assert.Same(original, member);
        Assert.Equal(1, OrgTreeFilter.CountMembers(result));
    }

    [Fact]
    public void Filter_UnitNameMatch_ShowsAllMembersOfUnit()
    {
        var roots = Sample();
        var result = OrgTreeFilter.Filter(roots, "영업");
        Assert.Equal(1, OrgTreeFilter.CountMembers(result));
        Assert.Equal("test3", result[0].Children[0].Children[0].UserId);
    }

    [Fact]
    public void Filter_NoMatch_ZeroMembers()
    {
        var result = OrgTreeFilter.Filter(Sample(), "없는사람");
        Assert.Equal(0, OrgTreeFilter.CountMembers(result));
    }

    [Fact]
    public void Filter_SelectionOnFilteredMember_ReflectsInOriginal()
    {
        var roots = Sample();
        var result = OrgTreeFilter.Filter(roots, "test1");
        result[0].Children[0].Children[0].IsSelectedForRoom = true;
        Assert.True(roots[0].Children[0].Children.First(n => n.UserId == "test1").IsSelectedForRoom);
    }

    [Fact]
    public void ApplyPresence_And_SetPresence()
    {
        var roots = Sample();
        OrgTreeFilter.ApplyPresence(roots, id => id == "test1");
        var dev = roots[0].Children[0];
        Assert.True(dev.Children.First(n => n.UserId == "test1").IsOnline);
        Assert.False(dev.Children.First(n => n.UserId == "test2").IsOnline);

        OrgTreeFilter.SetPresence(roots, "TEST2", true);
        Assert.True(dev.Children.First(n => n.UserId == "test2").IsOnline);
        OrgTreeFilter.SetPresence(roots, "test1", false);
        Assert.False(dev.Children.First(n => n.UserId == "test1").IsOnline);
    }

    [Fact]
    public void PresenceTracker_TracksOnlineSet()
    {
        var t = new PresenceTracker();
        Assert.True(t.Apply(new PresenceEvent { UserId = "a", State = PresenceEvent.Types.State.Online }));
        Assert.False(t.Apply(new PresenceEvent { UserId = "A", State = PresenceEvent.Types.State.Online }));
        Assert.True(t.IsOnline("a"));
        Assert.False(t.Apply(new PresenceEvent { UserId = "b", State = PresenceEvent.Types.State.Unknown }));
        Assert.True(t.Apply(new PresenceEvent { UserId = "a", State = PresenceEvent.Types.State.Offline }));
        Assert.False(t.IsOnline("a"));
        t.Apply(new PresenceEvent { UserId = "c", State = PresenceEvent.Types.State.Online });
        t.Clear();
        Assert.Empty(t.Snapshot());
    }
}

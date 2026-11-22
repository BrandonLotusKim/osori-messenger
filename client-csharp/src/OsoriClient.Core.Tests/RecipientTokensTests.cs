using System.Collections.Generic;
using OsoriClient.Core;
using Xunit;

namespace OsoriClient.Core.Tests;

public class RecipientTokensTests
{
    private static IReadOnlyList<OrgTreeNode> Org()
    {
        var root = new OrgTreeNode { Kind = OrgTreeNodeKind.Unit, DisplayName = "본부" };
        var team = new OrgTreeNode { Kind = OrgTreeNodeKind.Unit, DisplayName = "개발" };
        team.Children.Add(new OrgTreeNode { Kind = OrgTreeNodeKind.Member, UserId = "test7", DisplayName = "김칠" });
        team.Children.Add(new OrgTreeNode { Kind = OrgTreeNodeKind.Member, UserId = "test8", DisplayName = "test8 · 선임연구원", Title = "선임연구원" });
        team.Children.Add(new OrgTreeNode { Kind = OrgTreeNodeKind.Member, UserId = "kim", DisplayName = "김정 · 책임", Title = "책임" });
        root.Children.Add(team);
        return new[] { root };
    }

    [Fact]
    public void Parse_SplitsOnCommonSeparators()
    {
        Assert.Equal(new[] { "a", "b", "c", "d" }, RecipientTokens.Parse(" a, b;c\td "));
        Assert.Empty(RecipientTokens.Parse("  "));
        Assert.Empty(RecipientTokens.Parse(null));
    }

    [Fact]
    public void Merge_KeepsOrderAndDropsCaseInsensitiveDuplicates()
    {
        Assert.Equal(new[] { "test7", "test8", "test9" },
            RecipientTokens.Merge(new[] { "test7", "test8" }, new[] { "TEST7", "test9", " ", "test8" }));
    }

    [Fact]
    public void Label_UsesOrgDisplayName()
    {
        var org = Org();
        Assert.Equal("김칠 (test7)", RecipientTokens.Label("test7", org));
        Assert.Equal("김칠 (TEST7)", RecipientTokens.Label("TEST7", org));
        Assert.Equal("test8", RecipientTokens.Label("test8", org));   // 직급을 떼고, 이름이 ID 와 같으면 한 번만
        Assert.Equal("김정 (kim)", RecipientTokens.Label("kim", org));
        Assert.Equal("nobody", RecipientTokens.Label("nobody", org));
        Assert.Equal("test7", RecipientTokens.Label("test7", null));
    }

    [Fact]
    public void IsKnown_ChecksOrgMembership()
    {
        var org = Org();
        Assert.True(RecipientTokens.IsKnown("test7", org));
        Assert.False(RecipientTokens.IsKnown("nobody", org));
        Assert.True(RecipientTokens.IsKnown("nobody", null));
    }
}

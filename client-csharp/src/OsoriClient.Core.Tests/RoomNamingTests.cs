namespace OsoriClient.Core.Tests;

/// <summary>방 이름 규칙(멤버 순서와 무관하게 같은 이름) 검증.</summary>
public sealed class RoomNamingTests
{
    [Fact]
    public void AutoName_OrderIndependent()
    {
        Assert.Equal("test7, test8", RoomNaming.AutoName(new[] { "test8", "test7" }));
        Assert.Equal("test7, test8", RoomNaming.AutoName(new[] { "test7", "test8" }));
    }

    [Fact]
    public void AutoName_AloneAndMany()
    {
        Assert.Equal("나와의 대화", RoomNaming.AutoName(Array.Empty<string>()));
        Assert.Equal("a 외 4명", RoomNaming.AutoName(new[] { "d", "c", "b", "a" }));
    }

    [Fact]
    public void Resolve_SameResultForDifferentMemberOrder()
    {
        var fromList = RoomNaming.Resolve("x", false, new[] { "test9", "test8", "test7" }, "test9", "room");
        var fromSearch = RoomNaming.Resolve("x", false, new[] { "test7", "test9", "test8" }, "test9", "room");
        Assert.Equal("test7, test8", fromList);
        Assert.Equal(fromList, fromSearch);
    }

    [Fact]
    public void Resolve_CustomNameKept_EmptyMembersFallback()
    {
        Assert.Equal("기획팀", RoomNaming.Resolve("기획팀", true, new[] { "a", "b" }, "a", "room"));
        Assert.Equal("서버이름", RoomNaming.Resolve("서버이름", false, Array.Empty<string>(), "a", "room"));
        Assert.Equal("room", RoomNaming.Resolve("", false, Array.Empty<string>(), "a", "room"));
    }
}

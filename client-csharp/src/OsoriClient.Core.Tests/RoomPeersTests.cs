namespace OsoriClient.Core.Tests;

/// <summary>1:1 방 상대 판별(파일 첨부 허용 조건) 검증.</summary>
public sealed class RoomPeersTests
{
    [Fact]
    public void TwoMembers_ReturnsOther()
    {
        Assert.Equal("test2", RoomPeers.TryGetDirectCounterpart(new[] { "test1", "test2" }, "test1"));
    }

    [Fact]
    public void CaseInsensitive_Self()
    {
        Assert.Equal("test2", RoomPeers.TryGetDirectCounterpart(new[] { "TEST1", "test2" }, "test1"));
    }

    [Fact]
    public void GroupRoom_ReturnsNull()
    {
        Assert.Null(RoomPeers.TryGetDirectCounterpart(new[] { "test1", "test2", "test3" }, "test1"));
    }

    [Fact]
    public void SelfNotMember_ReturnsNull()
    {
        Assert.Null(RoomPeers.TryGetDirectCounterpart(new[] { "test2", "test3" }, "test1"));
    }

    [Fact]
    public void EmptyOrNull_ReturnsNull()
    {
        Assert.Null(RoomPeers.TryGetDirectCounterpart(null, "test1"));
        Assert.Null(RoomPeers.TryGetDirectCounterpart(new[] { "test1" }, "test1"));
    }

    [Fact]
    public void Duplicates_AreCollapsed()
    {
        Assert.Equal("test2", RoomPeers.TryGetDirectCounterpart(new[] { "test1", "test2", "test2" }, "test1"));
    }
}

namespace OsoriClient.Core.Tests;

/// <summary>아바타 색·글자 공용 규칙 검증.</summary>
public sealed class AvatarStyleTests
{
    [Fact]
    public void Hex_StableAndFromPalette()
    {
        var a = AvatarStyle.Hex("test7");
        Assert.Equal(a, AvatarStyle.Hex("test7"));
        Assert.Contains(a, AvatarStyle.Palette);
        Assert.Equal(8, AvatarStyle.Palette.Count);
    }

    [Fact]
    public void Hex_EmptyKeyIsGray()
    {
        Assert.Equal(AvatarStyle.EmptyHex, AvatarStyle.Hex(""));
        Assert.Equal(AvatarStyle.EmptyHex, AvatarStyle.Hex(null));
    }

    [Fact]
    public void Index_MatchesSha1FirstByte()
    {
        var hash = System.Security.Cryptography.SHA1.HashData(System.Text.Encoding.UTF8.GetBytes("test9"));
        Assert.Equal(hash[0] % 8, AvatarStyle.Index("test9"));
    }

    [Fact]
    public void TalkKey_DirectUsesPeer_GroupUsesRoom()
    {
        Assert.Equal("test7", AvatarStyle.TalkKey("room-1", "test7"));
        Assert.Equal("room-1", AvatarStyle.TalkKey("room-1", null));
        // 1:1 방의 목록 아바타와 대화창 헤더 아바타가 같은 색이 된다.
        Assert.Equal(AvatarStyle.Hex("test7"), AvatarStyle.Hex(AvatarStyle.TalkKey("room-1", "test7")));
    }

    [Theory]
    [InlineData("test7, test8", "T")]
    [InlineData(" 기획팀", "기")]
    [InlineData("", "?")]
    [InlineData(null, "?")]
    public void Initial_FirstChar(string? name, string expected)
    {
        Assert.Equal(expected, AvatarStyle.Initial(name));
    }
}

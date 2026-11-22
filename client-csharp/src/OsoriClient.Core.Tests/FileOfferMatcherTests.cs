using Google.Protobuf;
using Osori.Proto.V1;
using OsoriClient.Core;
using Xunit;

namespace OsoriClient.Core.Tests;

public class FileOfferMatcherTests
{
    [Fact]
    public void RoomOffer_MatchesOnlySameRoomWindow()
    {
        Assert.True(FileOfferMatcher.Matches("r1", "bob", "r1", null));
        Assert.False(FileOfferMatcher.Matches("r1", "bob", "r2", null));
        Assert.False(FileOfferMatcher.Matches("r1", "bob", null, "bob"));
    }

    [Fact]
    public void DirectOffer_MatchesPeerWindow()
    {
        Assert.True(FileOfferMatcher.Matches("", "bob", "r1", "bob"));
        Assert.True(FileOfferMatcher.Matches(null, "BOB", null, "bob"));
        Assert.False(FileOfferMatcher.Matches("", "carol", "r1", "bob"));
        Assert.False(FileOfferMatcher.Matches("", "bob", "r1", null));
    }

    [Theory]
    [InlineData("📎 report.pdf (1.2 MB)", "report.pdf")]
    [InlineData("📎 a (1).txt (10 B)", "a (1).txt")]
    [InlineData("📎 note.txt (10 B) [다운로드하려면 더블클릭]", "note.txt")]
    [InlineData("📎 x.txt (74 B)\n업로드 중...", "x.txt")]
    [InlineData("📎 x.txt (74 B) 전송 완료", "x.txt")]
    [InlineData("📎 x.txt (1.5 KB) 저장됨: C:\\t\\x.txt", "x.txt")]
    [InlineData("hello", null)]
    [InlineData("", null)]
    public void RoomFileText_ParsesFileName(string text, string? expected)
    {
        Assert.Equal(expected, RoomFileText.ParseFileName(text));
    }

    [Fact]
    public void AttachmentUploadIdSurvivesProtoRoundTrip()
    {
        var msg = TalkRoomReceived.Parser.ParseFrom(new TalkRoomReceived { ServerTalkId = "u1", AttachmentUploadId = "u1" }.ToByteArray());
        Assert.Equal("u1", msg.AttachmentUploadId);
    }

    [Fact]
    public void RoomIdSurvivesProtoRoundTrip()
    {
        var start = FileUploadStart.Parser.ParseFrom(new FileUploadStart { RoomId = "r1", Filename = "a.txt" }.ToByteArray());
        Assert.Equal("r1", start.RoomId);
        var note = FileAvailableNotification.Parser.ParseFrom(new FileAvailableNotification { RoomId = "r1" }.ToByteArray());
        Assert.Equal("r1", note.RoomId);
    }
}

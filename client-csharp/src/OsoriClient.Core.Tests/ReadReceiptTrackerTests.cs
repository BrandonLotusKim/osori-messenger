using Google.Protobuf;
using Osori.Proto.V1;
using OsoriClient.Core;
using Xunit;

namespace OsoriClient.Core.Tests;

public class ReadReceiptTrackerTests
{
    private static readonly string[] Members = { "me", "bob", "carol" };

    [Fact]
    public void Apply_OnlyMovesForward()
    {
        var t = new ReadReceiptTracker();
        Assert.True(t.Apply("bob", 100));
        Assert.False(t.Apply("BOB", 90));
        Assert.False(t.Apply("bob", 100));
        Assert.True(t.Apply("bob", 150));
        Assert.Equal(150, t.LastRead("bob"));
        Assert.False(t.Apply("", 10));
        Assert.False(t.Apply("x", 0));
    }

    [Fact]
    public void UnreadCount_ExcludesSenderAndCountsLaggingMembers()
    {
        var t = new ReadReceiptTracker();
        Assert.Equal(2, t.UnreadCount(100, "me", Members));
        t.Apply("bob", 100);
        Assert.Equal(1, t.UnreadCount(100, "me", Members));
        t.Apply("carol", 120);
        Assert.Equal(0, t.UnreadCount(100, "me", Members));
        Assert.Equal(1, t.UnreadCount(110, "me", Members));      // bob 은 100 까지만
        Assert.Null(t.UnreadCount(0, "me", Members));            // 아직 서버 시각 없음
    }

    [Fact]
    public void Label_ShowsCountOrRead()
    {
        Assert.Equal("", ReadReceiptTracker.Label(null));
        Assert.Equal("읽음", ReadReceiptTracker.Label(0));
        Assert.Equal("2", ReadReceiptTracker.Label(2));
    }

    [Fact]
    public void NextReportTs_UsesLatestOthersMessageAndSkipsAlreadyReported()
    {
        var msgs = new[] { (100L, "bob"), (200L, "me"), (150L, "carol") };
        Assert.Equal(150, ReadReceiptTracker.NextReportTs(msgs, "me", 0));
        Assert.Equal(0, ReadReceiptTracker.NextReportTs(msgs, "me", 150));
        Assert.Equal(0, ReadReceiptTracker.NextReportTs(new[] { (200L, "me") }, "me", 0));
    }

    [Fact]
    public void ReadUpdate_IsDispatchedAsEvent()
    {
        TalkRoomReadUpdate? got = null;
        var env = new Envelope { TalkRoomReadUpdate = new TalkRoomReadUpdate { RoomId = "r", UserId = "bob", LastReadTs = 5 } };
        MessengerClient.DispatchEnvelopeCore(Envelope.Parser.ParseFrom(env.ToByteArray()), null, null, null, null, null,
            roomReadUpdated: (_, e) => got = e);
        Assert.NotNull(got);
        Assert.Equal("bob", got!.UserId);
        Assert.Equal(5, got.LastReadTs);
    }

    [Fact]
    public void HistoryResponse_CarriesReadStates()
    {
        var res = new TalkRoomHistoryResponse();
        res.ReadStates.Add(new TalkRoomReadState { UserId = "bob", LastReadTs = 7 });
        var back = TalkRoomHistoryResponse.Parser.ParseFrom(res.ToByteArray());
        Assert.Equal(7, back.ReadStates[0].LastReadTs);
    }
}

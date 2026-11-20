using Osori.Proto.V1;
using OsoriClient.Core;

namespace OsoriClient.Core.Tests;

public class TalkMessageStatusMapperTests
{
    [Theory]
    [InlineData(TalkSendAck.Types.Result.Delivered, TalkMessageStatus.Delivered)]
    [InlineData(TalkSendAck.Types.Result.Queued, TalkMessageStatus.Queued)]
    [InlineData(TalkSendAck.Types.Result.InvalidTarget, TalkMessageStatus.InvalidTarget)]
    [InlineData(TalkSendAck.Types.Result.RateLimited, TalkMessageStatus.RateLimited)]
    [InlineData(TalkSendAck.Types.Result.ServerError, TalkMessageStatus.ServerError)]
    [InlineData(TalkSendAck.Types.Result.Unknown, TalkMessageStatus.Failed)]
    public void FromAckResult_MapsAllCases(TalkSendAck.Types.Result input, TalkMessageStatus expected)
    {
        Assert.Equal(expected, MessageStatusMapper.FromAckResult(input));
    }
}

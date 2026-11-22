namespace OsoriClient.Core.Tests;

public sealed class MessageGroupingTests
{
    private const long T0 = 1_700_000_000_000L;

    [Fact]
    public void SameSender_WithinThreeMinutes_IsContinuation() =>
        Assert.True(MessageGrouping.IsContinuation("test7", T0, "TEST7", T0 + 60_000, startsNewDay: false));

    [Fact]
    public void DifferentSender_IsNotContinuation() =>
        Assert.False(MessageGrouping.IsContinuation("test7", T0, "test8", T0 + 1_000, startsNewDay: false));

    [Fact]
    public void GapOverThreeMinutes_IsNotContinuation() =>
        Assert.False(MessageGrouping.IsContinuation("test7", T0, "test7", T0 + MessageGrouping.MaxGapMillis + 1, startsNewDay: false));

    [Fact]
    public void NewDay_OrFirstMessage_IsNotContinuation()
    {
        Assert.False(MessageGrouping.IsContinuation("test7", T0, "test7", T0 + 1_000, startsNewDay: true));
        Assert.False(MessageGrouping.IsContinuation(null, 0, "test7", T0, startsNewDay: false));
    }

    [Fact]
    public void UnknownTimestamp_TreatedAsSameGroup() =>
        Assert.True(MessageGrouping.IsContinuation("me", T0, "me", 0, startsNewDay: false));
}

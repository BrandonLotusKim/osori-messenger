namespace OsoriClient.Core.Tests;

public sealed class ReconnectBackoffTests
{
    [Theory]
    [InlineData(0, 1000)]
    [InlineData(1, 2000)]
    [InlineData(2, 4000)]
    [InlineData(3, 8000)]
    [InlineData(4, 16000)]
    public void ComputeBackoffDelayMs_BeforeCap_Doubles(int attempt, int expected)
    {
        var actual = MessengerClient.ComputeBackoffDelayMs(attempt);

        Assert.Equal(expected, actual);
    }

    [Theory]
    [InlineData(5, 30000)]
    [InlineData(6, 30000)]
    [InlineData(9, 30000)]
    public void ComputeBackoffDelayMs_AfterCap_ReturnsMax(int attempt, int expected)
    {
        var actual = MessengerClient.ComputeBackoffDelayMs(attempt);

        Assert.Equal(expected, actual);
    }
}

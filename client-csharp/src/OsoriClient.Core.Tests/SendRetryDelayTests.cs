using Xunit;

namespace OsoriClient.Core.Tests;

public sealed class SendRetryDelayTests
{
    [Theory]
    [InlineData(0, 500)]
    [InlineData(1, 1000)]
    [InlineData(2, 2000)]
    public void ComputeRetryDelayMs_BeforeCap_Doubles(int attempt, int expected)
    {
        Assert.Equal(expected, MessengerClient.ComputeRetryDelayMs(attempt));
    }

    [Theory]
    [InlineData(3, 3000)]
    [InlineData(10, 3000)]
    public void ComputeRetryDelayMs_AfterCap_ReturnsMax(int attempt, int expected)
    {
        Assert.Equal(expected, MessengerClient.ComputeRetryDelayMs(attempt));
    }
}

using Osori.Proto.V1;

namespace OsoriClient.Core.Tests;

/// <summary>중복 로그인(다른 곳 로그인) 종료 사유 처리 — 자동 재접속 중단 + 안내 이벤트 1회.</summary>
public sealed class SessionSupersededTests
{
    [Fact]
    public void IsSupersededSignal_ByNoticeOrCloseDescription()
    {
        Assert.True(MessengerClient.IsSupersededSignal(new SessionClosed { Reason = SessionClosed.Types.Reason.Superseded }, null));
        Assert.True(MessengerClient.IsSupersededSignal(null, "already-online"));
        Assert.False(MessengerClient.IsSupersededSignal(new SessionClosed { Reason = SessionClosed.Types.Reason.Unknown }, null));
        Assert.False(MessengerClient.IsSupersededSignal(null, "reconnect"));
        Assert.False(MessengerClient.IsSupersededSignal(null, null));
    }

    [Fact]
    public async Task SupersededNotice_StopsAutoReconnect_AndRaisesOnce()
    {
        await using var client = new MessengerClient();
        Assert.True(client.ShouldAttemptReconnectForTest());

        var raised = new List<string>();
        client.SessionSuperseded += (_, msg) => raised.Add(msg);

        var env = new Envelope
        {
            SessionClosed = new SessionClosed
            {
                Reason = SessionClosed.Types.Reason.Superseded,
                Message = "다른 곳에서 로그인됨",
            },
        };
        await client.DispatchForTestAsync(env);
        await client.DispatchForTestAsync(env);

        Assert.True(client.IsSessionSuperseded);
        Assert.False(client.ShouldAttemptReconnectForTest());
        Assert.Equal(new[] { "다른 곳에서 로그인됨" }, raised);
    }

    [Fact]
    public async Task UnknownReason_DoesNotStopReconnect()
    {
        await using var client = new MessengerClient();
        await client.DispatchForTestAsync(new Envelope { SessionClosed = new SessionClosed { Reason = SessionClosed.Types.Reason.Unknown } });
        Assert.False(client.IsSessionSuperseded);
        Assert.True(client.ShouldAttemptReconnectForTest());
    }

    [Fact]
    public async Task EmptyMessage_UsesDefaultKoreanNotice()
    {
        await using var client = new MessengerClient();
        string? got = null;
        client.SessionSuperseded += (_, msg) => got = msg;
        await client.DispatchForTestAsync(new Envelope { SessionClosed = new SessionClosed { Reason = SessionClosed.Types.Reason.Superseded } });
        Assert.Equal(MessengerClient.SupersededDefaultMessage, got);
    }
}

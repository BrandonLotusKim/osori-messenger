using Google.Protobuf;
using Osori.Proto.V1;

namespace OsoriClient.Core.Tests;

/// <summary>
/// TalkSearchRequest/TalkSearchResponse proto 왕복 검증.
/// Grpc.Tools 가 proto 정의에서 C# 클래스를 정상 생성했는지 + Envelope.oneof 로
/// tag 90/91 이 올바르게 감싸지는지 확인. 클라-서버 간 와이어 계약 문서화.
/// </summary>
public sealed class TalkSearchProtoTests
{
    [Fact]
    public void MessageSearchRequest_Envelope_OneofRoundTrip()
    {
        var req = new TalkSearchRequest
        {
            ClientReqId = "req-search-1",
            Keyword = "배포 일정",
            SinceTs = 1_700_000_000_000L,
            UntilTs = 1_700_000_900_000L,
            Limit = 25
        };
        var envelope = new Envelope { TalkSearchReq = req, Sequence = 77UL };

        var bytes = envelope.ToByteArray();
        var parsed = Envelope.Parser.ParseFrom(bytes);

        Assert.Equal(Envelope.PayloadOneofCase.TalkSearchReq, parsed.PayloadCase);
        Assert.Equal(77UL, parsed.Sequence);
        Assert.Equal("req-search-1", parsed.TalkSearchReq.ClientReqId);
        Assert.Equal("배포 일정", parsed.TalkSearchReq.Keyword);
        Assert.Equal(1_700_000_000_000L, parsed.TalkSearchReq.SinceTs);
        Assert.Equal(1_700_000_900_000L, parsed.TalkSearchReq.UntilTs);
        Assert.Equal(25, parsed.TalkSearchReq.Limit);
    }

    [Fact]
    public void MessageSearchResponse_WithMessages_RoundTrip()
    {
        var resp = new TalkSearchResponse
        {
            ClientReqId = "req-1",
            MatchedCount = 2,
            HasMore = true,
            ServerTs = 1_700_000_123_456L,
            Result = TalkSearchResponse.Types.Result.Ok
        };
        resp.Talks.Add(new TalkReceived
        {
            ServerTalkId = "msg-1",
            FromUserId = "alice",
            ToUserId = "bob",
            Text = "배포는 내일 오후",
            ServerTs = 1_700_000_100_000L
        });
        resp.Talks.Add(new TalkReceived
        {
            ServerTalkId = "msg-2",
            FromUserId = "bob",
            ToUserId = "alice",
            Text = "배포 일정 확인했어요",
            ServerTs = 1_700_000_120_000L
        });

        var envelope = new Envelope { TalkSearchRes = resp };
        var bytes = envelope.ToByteArray();
        var parsed = Envelope.Parser.ParseFrom(bytes);

        Assert.Equal(Envelope.PayloadOneofCase.TalkSearchRes, parsed.PayloadCase);
        Assert.Equal("req-1", parsed.TalkSearchRes.ClientReqId);
        Assert.Equal(TalkSearchResponse.Types.Result.Ok, parsed.TalkSearchRes.Result);
        Assert.Equal(2, parsed.TalkSearchRes.MatchedCount);
        Assert.True(parsed.TalkSearchRes.HasMore);
        Assert.Equal(1_700_000_123_456L, parsed.TalkSearchRes.ServerTs);
        Assert.Equal(2, parsed.TalkSearchRes.Talks.Count);
        Assert.Equal("msg-1", parsed.TalkSearchRes.Talks[0].ServerTalkId);
        Assert.Equal("배포는 내일 오후", parsed.TalkSearchRes.Talks[0].Text);
        Assert.Equal("bob", parsed.TalkSearchRes.Talks[1].FromUserId);
    }

    [Fact]
    public void MessageSearchResponse_EmptyMessages_OkResult()
    {
        var resp = new TalkSearchResponse
        {
            ClientReqId = "req-empty",
            MatchedCount = 0,
            HasMore = false,
            Result = TalkSearchResponse.Types.Result.Ok
        };

        var parsed = TalkSearchResponse.Parser.ParseFrom(resp.ToByteArray());
        Assert.Empty(parsed.Talks);
        Assert.Equal(0, parsed.MatchedCount);
        Assert.False(parsed.HasMore);
        Assert.Equal(TalkSearchResponse.Types.Result.Ok, parsed.Result);
    }

    [Fact]
    public void MessageSearchResponse_ServerErrorResult_RoundTrip()
    {
        var resp = new TalkSearchResponse
        {
            ClientReqId = "req-err",
            Result = TalkSearchResponse.Types.Result.ServerError
        };

        var parsed = TalkSearchResponse.Parser.ParseFrom(resp.ToByteArray());
        Assert.Equal(TalkSearchResponse.Types.Result.ServerError, parsed.Result);
    }
}

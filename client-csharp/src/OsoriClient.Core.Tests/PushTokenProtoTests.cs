using Google.Protobuf;
using Osori.Proto.V1;

namespace OsoriClient.Core.Tests;

/// <summary>
/// PushTokenRegister/Unregister proto 왕복 검증.
/// Envelope oneof tag 100~103 에서 올바르게 감싸지는지, 한글 기기명/토큰도 보존되는지 확인.
/// </summary>
public sealed class PushTokenProtoTests
{
    [Fact]
    public void PushTokenRegisterRequest_Envelope_OneofRoundTrip()
    {
        var req = new PushTokenRegisterRequest
        {
            ClientReqId = "req-1",
            DeviceId = "홍길동-폰",
            Platform = "fcm",
            Token = "cMv8jfKs...BASE64-ish...=="
        };
        var envelope = new Envelope { PushTokenRegisterReq = req, Sequence = 100UL };

        var parsed = Envelope.Parser.ParseFrom(envelope.ToByteArray());

        Assert.Equal(Envelope.PayloadOneofCase.PushTokenRegisterReq, parsed.PayloadCase);
        Assert.Equal(100UL, parsed.Sequence);
        Assert.Equal("req-1", parsed.PushTokenRegisterReq.ClientReqId);
        Assert.Equal("홍길동-폰", parsed.PushTokenRegisterReq.DeviceId);
        Assert.Equal("fcm", parsed.PushTokenRegisterReq.Platform);
        Assert.Equal("cMv8jfKs...BASE64-ish...==", parsed.PushTokenRegisterReq.Token);
    }

    [Fact]
    public void PushTokenRegisterResponse_AllResults_RoundTrip()
    {
        foreach (var r in new[] {
            PushTokenRegisterResponse.Types.Result.Ok,
            PushTokenRegisterResponse.Types.Result.InvalidRequest,
            PushTokenRegisterResponse.Types.Result.ServerError,
        })
        {
            var resp = new PushTokenRegisterResponse
            {
                ClientReqId = "r",
                ServerTs = 1_700_000_000_000L,
                Result = r,
            };
            var parsed = PushTokenRegisterResponse.Parser.ParseFrom(resp.ToByteArray());
            Assert.Equal(r, parsed.Result);
            Assert.Equal(1_700_000_000_000L, parsed.ServerTs);
        }
    }

    [Fact]
    public void PushTokenUnregisterRequest_Envelope_OneofRoundTrip()
    {
        var req = new PushTokenUnregisterRequest { ClientReqId = "u", DeviceId = "phone" };
        var envelope = new Envelope { PushTokenUnregisterReq = req, Sequence = 103UL };

        var parsed = Envelope.Parser.ParseFrom(envelope.ToByteArray());
        Assert.Equal(Envelope.PayloadOneofCase.PushTokenUnregisterReq, parsed.PayloadCase);
        Assert.Equal(103UL, parsed.Sequence);
        Assert.Equal("phone", parsed.PushTokenUnregisterReq.DeviceId);
    }

    [Fact]
    public void PushTokenUnregisterResponse_DeletedCount_RoundTrip()
    {
        var resp = new PushTokenUnregisterResponse
        {
            ClientReqId = "u",
            DeletedCount = 3,
            ServerTs = 1L,
            Result = PushTokenUnregisterResponse.Types.Result.Ok,
        };
        var parsed = PushTokenUnregisterResponse.Parser.ParseFrom(resp.ToByteArray());
        Assert.Equal(3, parsed.DeletedCount);
        Assert.Equal(PushTokenUnregisterResponse.Types.Result.Ok, parsed.Result);
    }
}

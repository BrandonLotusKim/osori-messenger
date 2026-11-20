using Google.Protobuf;
using Osori.Proto.V1;

namespace OsoriClient.Core.Tests;

/// <summary>
/// OrgListRequest/OrgListResponse proto 클래스 생성 + 직렬화 왕복 검증.
/// Grpc.Tools 가 proto 파일에서 정상적으로 C# 클래스를 생성했는지 + Envelope 안에서
/// oneof payload 로 올바르게 감싸지는지 확인한다.
/// </summary>
public sealed class OrgListProtoTests
{
    [Fact]
    public void OrgListRequest_Envelope_OneofRoundTrip()
    {
        var req = new OrgListRequest { ClientReqId = "req-abc" };
        var envelope = new Envelope { OrgListReq = req, Sequence = 42UL };

        var bytes = envelope.ToByteArray();
        var parsed = Envelope.Parser.ParseFrom(bytes);

        Assert.Equal(Envelope.PayloadOneofCase.OrgListReq, parsed.PayloadCase);
        Assert.Equal(42UL, parsed.Sequence);
        Assert.Equal("req-abc", parsed.OrgListReq.ClientReqId);
    }

    [Fact]
    public void OrgListResponse_WithUnitsAndMembers_RoundTrip()
    {
        var resp = new OrgListResponse
        {
            ClientReqId = "req-1",
            UnitCount = 2,
            MemberCount = 1,
            ServerTs = 1_700_000_000_000L,
            Result = OrgListResponse.Types.Result.Ok
        };
        resp.Units.Add(new OrgUnit { Path = "오소리랩", ParentPath = "", Name = "오소리랩", Depth = 0, SortOrder = 0 });
        resp.Units.Add(new OrgUnit { Path = "오소리랩/RND", ParentPath = "오소리랩", Name = "RND", Depth = 1, SortOrder = 0 });
        resp.Members.Add(new OrgMember
        {
            UserId = "kjm",
            OrgPath = "오소리랩/RND",
            Email = "kjm@example.com",
            Phone = "",
            Mobile = "010-1",
            Title = "선임",
            SortOrder = 1
        });

        var envelope = new Envelope { OrgListRes = resp };
        var bytes = envelope.ToByteArray();
        var parsed = Envelope.Parser.ParseFrom(bytes);

        Assert.Equal(Envelope.PayloadOneofCase.OrgListRes, parsed.PayloadCase);
        Assert.Equal("req-1", parsed.OrgListRes.ClientReqId);
        Assert.Equal(OrgListResponse.Types.Result.Ok, parsed.OrgListRes.Result);
        Assert.Equal(2, parsed.OrgListRes.Units.Count);
        Assert.Equal("오소리랩/RND", parsed.OrgListRes.Units[1].Path);
        Assert.Equal(1, parsed.OrgListRes.Units[1].Depth);
        Assert.Single(parsed.OrgListRes.Members);
        Assert.Equal("kjm", parsed.OrgListRes.Members[0].UserId);
        Assert.Equal("오소리랩/RND", parsed.OrgListRes.Members[0].OrgPath);
        Assert.Equal("선임", parsed.OrgListRes.Members[0].Title);
    }

    [Fact]
    public void OrgListResponse_EmptyParentPath_RemainsEmpty()
    {
        var resp = new OrgListResponse
        {
            ClientReqId = "req",
            Result = OrgListResponse.Types.Result.Ok
        };
        resp.Units.Add(new OrgUnit { Path = "ROOT", ParentPath = "", Name = "ROOT", Depth = 0, SortOrder = 0 });

        var parsed = OrgListResponse.Parser.ParseFrom(resp.ToByteArray());
        Assert.Single(parsed.Units);
        Assert.Equal("", parsed.Units[0].ParentPath);
    }
}

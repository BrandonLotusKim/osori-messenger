using Osori.Proto.V1;

namespace OsoriClient.Core;

/// <summary>조직도 조회, 메시지 키워드 검색, 푸시 토큰 등록/해제.</summary>
public sealed partial class MessengerClient
{
    /// <summary>
    /// 조직도 전체 스냅샷(부서 트리 + 멤버) 을 서버에서 받아온다.
    /// MVP 는 페이징 없이 한 번에 전체 반환. 사이드바/트리뷰 그릴 때 호출.
    /// </summary>
    public async Task<OrgListResponse> GetOrgListAsync(CancellationToken ct = default)
    {
        var clientReqId = Guid.NewGuid().ToString("N");
        var envelope = new Envelope
        {
            OrgListReq = new OrgListRequest
            {
                ClientReqId = clientReqId
            }
        };
        var response = await SendRequestAsync(envelope, Envelope.PayloadOneofCase.OrgListRes, ct);
        return response.OrgListRes;
    }

    /// <summary>
    /// 1:1 메시지 본문 키워드 검색.
    /// keyword 가 빈 문자열이면 서버가 OK + 빈 결과를 돌려준다.
    /// sinceMillis/untilMillis = 0 이면 무한대(전체 기간).
    /// limit 0 → 서버 기본값 50, 최대 500.
    /// </summary>
    public async Task<TalkSearchResponse> SearchMessagesAsync(
        string keyword,
        long sinceMillis = 0,
        long untilMillis = 0,
        int limit = 0,
        CancellationToken ct = default)
    {
        var clientReqId = Guid.NewGuid().ToString("N");
        var envelope = new Envelope
        {
            TalkSearchReq = new TalkSearchRequest
            {
                ClientReqId = clientReqId,
                Keyword = keyword ?? "",
                SinceTs = sinceMillis,
                UntilTs = untilMillis,
                Limit = limit,
            }
        };
        var response = await SendRequestAsync(envelope, Envelope.PayloadOneofCase.TalkSearchRes, ct);
        return response.TalkSearchRes;
    }

    /// <summary>푸시 토큰 등록. 같은 device 로 재호출 시 upsert 로 토큰 회전.</summary>
    public async Task<PushTokenRegisterResponse> RegisterPushTokenAsync(
        string deviceId,
        string platform,
        string token,
        CancellationToken ct = default)
    {
        var envelope = new Envelope
        {
            PushTokenRegisterReq = new PushTokenRegisterRequest
            {
                ClientReqId = Guid.NewGuid().ToString("N"),
                DeviceId = deviceId ?? "",
                Platform = platform ?? "",
                Token = token ?? "",
            }
        };
        var response = await SendRequestAsync(envelope, Envelope.PayloadOneofCase.PushTokenRegisterRes, ct);
        return response.PushTokenRegisterRes;
    }

    /// <summary>푸시 토큰 해제. 로그아웃/앱삭제 시.</summary>
    public async Task<PushTokenUnregisterResponse> UnregisterPushTokenAsync(
        string deviceId,
        CancellationToken ct = default)
    {
        var envelope = new Envelope
        {
            PushTokenUnregisterReq = new PushTokenUnregisterRequest
            {
                ClientReqId = Guid.NewGuid().ToString("N"),
                DeviceId = deviceId ?? "",
            }
        };
        var response = await SendRequestAsync(envelope, Envelope.PayloadOneofCase.PushTokenUnregisterRes, ct);
        return response.PushTokenUnregisterRes;
    }
}

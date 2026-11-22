using Osori.Proto.V1;

namespace OsoriClient.Core;

/// <summary>대화방(room) 생성/입장/조회 및 방 메시지 송신/이력.</summary>
public sealed partial class MessengerClient
{
    public async Task<TalkRoomCreateAck> CreateRoomAsync(string name, CancellationToken ct = default)
    {
        var clientReqId = Guid.NewGuid().ToString("N");
        return await CreateRoomAsync(name, clientReqId, ct);
    }

    public async Task<TalkRoomCreateAck> CreateRoomAsync(string name, string clientReqId, CancellationToken ct = default) =>
        await CreateRoomAsync(name, memberIds: Array.Empty<string>(), force: false, clientReqId: clientReqId, ct: ct);

    /// <summary>
    /// 본인 외 초기 멤버(memberIds)를 함께 넣어 방을 만든다. force=false 인데 정확히 같은 구성원
    /// (본인 포함)의 방이 이미 있으면 서버가 DUPLICATE_MEMBERS + ExistingRoomId 를 돌려준다 —
    /// 호출자는 그 경우 사용자에게 "이동/새로 만들기"를 물어보고, "새로 만들기"면 force=true 로 재호출.
    /// </summary>
    public async Task<TalkRoomCreateAck> CreateRoomAsync(
        string name,
        IEnumerable<string> memberIds,
        bool force = false,
        CancellationToken ct = default) =>
        await CreateRoomAsync(name, memberIds, force, Guid.NewGuid().ToString("N"), ct);

    public async Task<TalkRoomCreateAck> CreateRoomAsync(
        string name,
        IEnumerable<string> memberIds,
        bool force,
        string clientReqId,
        CancellationToken ct = default)
    {
        var create = new TalkRoomCreate
        {
            Name = name,
            ClientReqId = clientReqId,
            Force = force,
        };
        create.MemberIds.AddRange(memberIds);
        var envelope = new Envelope { TalkRoomCreate = create };
        var response = await SendRequestAsync(envelope, Envelope.PayloadOneofCase.TalkRoomCreateAck, ct);
        return response.TalkRoomCreateAck;
    }

    /// <summary>방 안 누구나 다른 사람을 초대할 수 있다(방장 개념 없음). 이미 멤버면 서버가 조용히 건너뛰고 알려준다.</summary>
    public async Task<TalkRoomInviteAck> InviteToRoomAsync(string roomId, IEnumerable<string> targetUserIds, CancellationToken ct = default)
    {
        var invite = new TalkRoomInvite
        {
            RoomId = roomId,
            ClientReqId = Guid.NewGuid().ToString("N"),
        };
        invite.TargetUserIds.AddRange(targetUserIds);
        var envelope = new Envelope { TalkRoomInvite = invite };
        var response = await SendRequestAsync(envelope, Envelope.PayloadOneofCase.TalkRoomInviteAck, ct);
        return response.TalkRoomInviteAck;
    }

    /// <summary>나가기 — 본인만 방에서 제외된다. 0명이 되면 방/멤버십은 삭제되지만 대화 기록은 서버에 보존된다.</summary>
    public async Task<TalkRoomLeaveAck> LeaveRoomAsync(string roomId, CancellationToken ct = default)
    {
        var envelope = new Envelope
        {
            TalkRoomLeave = new TalkRoomLeave
            {
                RoomId = roomId,
                ClientReqId = Guid.NewGuid().ToString("N"),
            }
        };
        var response = await SendRequestAsync(envelope, Envelope.PayloadOneofCase.TalkRoomLeaveAck, ct);
        return response.TalkRoomLeaveAck;
    }

    /// <summary>방 이름 변경 — 방 안 누구나 가능.</summary>
    public async Task<TalkRoomRenameAck> RenameRoomAsync(string roomId, string newName, CancellationToken ct = default)
    {
        var envelope = new Envelope
        {
            TalkRoomRename = new TalkRoomRename
            {
                RoomId = roomId,
                NewName = newName,
                ClientReqId = Guid.NewGuid().ToString("N"),
            }
        };
        var response = await SendRequestAsync(envelope, Envelope.PayloadOneofCase.TalkRoomRenameAck, ct);
        return response.TalkRoomRenameAck;
    }

    public async Task<TalkRoomJoinAck> JoinRoomAsync(string roomId, CancellationToken ct = default)
    {
        var clientReqId = Guid.NewGuid().ToString("N");
        var envelope = new Envelope
        {
            TalkRoomJoin = new TalkRoomJoin
            {
                RoomId = roomId,
                ClientReqId = clientReqId
            }
        };
        var response = await SendRequestAsync(envelope, Envelope.PayloadOneofCase.TalkRoomJoinAck, ct);
        return response.TalkRoomJoinAck;
    }

    public async Task<TalkRoomInfoResponse> GetRoomInfoAsync(string roomId, CancellationToken ct = default)
    {
        var clientReqId = Guid.NewGuid().ToString("N");
        var envelope = new Envelope
        {
            TalkRoomInfoReq = new TalkRoomInfoRequest
            {
                RoomId = roomId,
                ClientReqId = clientReqId
            }
        };
        var response = await SendRequestAsync(envelope, Envelope.PayloadOneofCase.TalkRoomInfoRes, ct);
        return response.TalkRoomInfoRes;
    }

    /// <summary>로그인 직후 사이드바 복원용 — 내가 멤버인 대화방 전체 목록.</summary>
    public async Task<TalkRoomListResponse> ListRoomsAsync(CancellationToken ct = default)
    {
        var clientReqId = Guid.NewGuid().ToString("N");
        var envelope = new Envelope
        {
            TalkRoomListReq = new TalkRoomListRequest
            {
                ClientReqId = clientReqId
            }
        };
        var response = await SendRequestAsync(envelope, Envelope.PayloadOneofCase.TalkRoomListRes, ct);
        return response.TalkRoomListRes;
    }

    public async Task<TalkRoomSendAck> SendRoomMessageAsync(string roomId, string text, CancellationToken ct = default)
    {
        var clientMsgId = Guid.NewGuid().ToString("N");
        var envelope = new Envelope
        {
            TalkRoomSend = new TalkRoomSend
            {
                ClientTalkId = clientMsgId,
                RoomId = roomId,
                Text = text,
                ClientTs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
            }
        };
        var response = await SendRequestAsync(envelope, Envelope.PayloadOneofCase.TalkRoomSendAck, ct);
        return response.TalkRoomSendAck;
    }

    /// <summary>방 히스토리 조회. roomId 의 멤버만 호출 가능 (서버가 NOT_A_MEMBER 로 거부).</summary>
    public async Task<TalkRoomHistoryResponse> GetRoomHistoryAsync(
        string roomId,
        long sinceMillis = 0,
        int limit = 0,
        CancellationToken ct = default)
    {
        var envelope = new Envelope
        {
            TalkRoomHistoryReq = new TalkRoomHistoryRequest
            {
                ClientReqId = Guid.NewGuid().ToString("N"),
                RoomId = roomId ?? "",
                SinceTs = sinceMillis,
                Limit = limit,
            }
        };
        var response = await SendRequestAsync(envelope, Envelope.PayloadOneofCase.TalkRoomHistoryRes, ct);
        return response.TalkRoomHistoryRes;
    }

    /// <summary>
    /// 읽음 보고 — 이 방을 lastReadTs(서버 시각, ms) 까지 읽었다. 응답 없는 단방향 전송이며 실패해도 무시한다
    /// (다음 보고 때 더 큰 값으로 다시 맞춰진다).
    /// </summary>
    public async Task ReportRoomReadAsync(string roomId, long lastReadTs, CancellationToken ct = default)
    {
        if (string.IsNullOrEmpty(roomId) || lastReadTs <= 0) return;
        var envelope = new Envelope
        {
            Sequence = (ulong)Interlocked.Increment(ref _sequence),
            TalkRoomReadReport = new TalkRoomReadReport { RoomId = roomId, LastReadTs = lastReadTs },
        };
        try { await SendAsync(envelope, ct); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"[read] report failed: {ex.Message}"); }
    }
}

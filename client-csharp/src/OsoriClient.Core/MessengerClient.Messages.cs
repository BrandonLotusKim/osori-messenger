using Osori.Proto.V1;

namespace OsoriClient.Core;

/// <summary>쪽지(message) 발송/받은쪽지함/보낸쪽지함/읽음 처리/삭제.</summary>
public sealed partial class MessengerClient
{
    /// <summary> 쪽지 발송. 다중 수신자 지원. 첨부파일은 사전 업로드된 upload_id 로 참조.</summary>
    public async Task<MessageSendAck> SendMessageAsync(
        IEnumerable<string> recipientIds,
        string title,
        string body,
        string? attachmentUploadId = null,
        CancellationToken ct = default)
    {
        var req = new MessageSend
        {
            ClientMessageId = Guid.NewGuid().ToString("N"),
            Title = title ?? "",
            Body = body ?? "",
            AttachmentUploadId = attachmentUploadId ?? "",
        };
        foreach (var rid in recipientIds ?? Array.Empty<string>())
        {
            if (!string.IsNullOrWhiteSpace(rid)) req.RecipientIds.Add(rid);
        }
        var response = await SendRequestAsync(new Envelope { MessageSend = req }, Envelope.PayloadOneofCase.MessageSendAck, ct);
        return response.MessageSendAck;
    }

    /// <summary> 받은쪽지함 조회. sinceTs > 0 이면 그 이후만, limit 0 이면 서버 기본값(50).</summary>
    public async Task<MessageListResponse> GetMessageInboxAsync(
        long sinceTs = 0,
        int limit = 0,
        CancellationToken ct = default)
    {
        var req = new MessageListRequest
        {
            ClientReqId = Guid.NewGuid().ToString("N"),
            Box = MessageListRequest.Types.Box.Inbox,
            SinceTs = sinceTs,
            Limit = limit,
        };
        var response = await SendRequestAsync(new Envelope { MessageListReq = req }, Envelope.PayloadOneofCase.MessageListRes, ct);
        return response.MessageListRes;
    }

    /// <summary> 보낸쪽지함 조회.</summary>
    public async Task<MessageListResponse> GetMessageSentAsync(
        long sinceTs = 0,
        int limit = 0,
        CancellationToken ct = default)
    {
        var req = new MessageListRequest
        {
            ClientReqId = Guid.NewGuid().ToString("N"),
            Box = MessageListRequest.Types.Box.Sent,
            SinceTs = sinceTs,
            Limit = limit,
        };
        var response = await SendRequestAsync(new Envelope { MessageListReq = req }, Envelope.PayloadOneofCase.MessageListRes, ct);
        return response.MessageListRes;
    }

    /// <summary> 쪽지 읽음 처리. 본인 수신쪽지만 가능.</summary>
    public async Task<MessageMarkReadAck> MarkMessageReadAsync(
        string serverMessageId,
        CancellationToken ct = default)
    {
        var req = new MessageMarkRead
        {
            ClientReqId = Guid.NewGuid().ToString("N"),
            ServerMessageId = serverMessageId ?? "",
        };
        var response = await SendRequestAsync(new Envelope { MessageMarkRead = req }, Envelope.PayloadOneofCase.MessageMarkReadAck, ct);
        return response.MessageMarkReadAck;
    }

    /// <summary> 쪽지 소프트 삭제. 발신자/수신자 어느 쪽이든 본인 시야에서만 사라짐.</summary>
    public async Task<MessageDeleteAck> DeleteMessageAsync(
        string serverMessageId,
        CancellationToken ct = default)
    {
        var req = new MessageDelete
        {
            ClientReqId = Guid.NewGuid().ToString("N"),
            ServerMessageId = serverMessageId ?? "",
        };
        var response = await SendRequestAsync(new Envelope { MessageDelete = req }, Envelope.PayloadOneofCase.MessageDeleteAck, ct);
        return response.MessageDeleteAck;
    }
}

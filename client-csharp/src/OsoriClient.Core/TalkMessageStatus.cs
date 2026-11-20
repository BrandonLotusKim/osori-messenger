using Osori.Proto.V1;

namespace OsoriClient.Core;

public enum MessageDirection
{
    Sent,
    Received,
    History
}

public enum TalkMessageStatus
{
    Sending,
    Delivered,
    Queued,
    RateLimited,
    InvalidTarget,
    Failed,
    ServerError,
    Received
}

public static class MessageStatusMapper
{
    public static TalkMessageStatus FromAckResult(TalkSendAck.Types.Result result) => result switch
    {
        TalkSendAck.Types.Result.Delivered => TalkMessageStatus.Delivered,
        TalkSendAck.Types.Result.Queued => TalkMessageStatus.Queued,
        TalkSendAck.Types.Result.InvalidTarget => TalkMessageStatus.InvalidTarget,
        TalkSendAck.Types.Result.RateLimited => TalkMessageStatus.RateLimited,
        TalkSendAck.Types.Result.ServerError => TalkMessageStatus.ServerError,
        _ => TalkMessageStatus.Failed
    };
}

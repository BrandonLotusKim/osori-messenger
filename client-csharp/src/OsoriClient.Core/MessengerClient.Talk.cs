using System.IO;
using System.Net.WebSockets;
using Osori.Proto.V1;

namespace OsoriClient.Core;

/// <summary>1:1 대화(talk) 송신 및 이력 조회.</summary>
public sealed partial class MessengerClient
{
    public async Task<TalkSendAck> SendTalkAsync(string toUserId, string text, CancellationToken ct)
    {
        var clientMsgId = Guid.NewGuid().ToString("N");
        return await SendTalkInternalAsync(toUserId, text, clientMsgId, ct);
    }

    public async Task<TalkSendAck> SendTalkAsync(string toUserId, string text, string clientMsgId, int maxRetries = 0, CancellationToken ct = default)
    {
        if (maxRetries <= 0)
        {
            return await SendTalkInternalAsync(toUserId, text, clientMsgId, ct);
        }

        for (var attempt = 0; ; attempt++)
        {
            try
            {
                return await SendTalkInternalAsync(toUserId, text, clientMsgId, ct);
            }
            catch (WebSocketException) when (attempt < maxRetries)
            {
            }
            catch (IOException) when (attempt < maxRetries)
            {
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested && attempt < maxRetries)
            {
            }

            await Task.Delay(ComputeRetryDelayMs(attempt), ct);
        }
    }

    public async Task<TalkSendAck> SendTalkAsync(string toUserId, string text, int maxRetries = 0, CancellationToken ct = default)
    {
        var clientMsgId = Guid.NewGuid().ToString("N");
        return await SendTalkAsync(toUserId, text, clientMsgId, maxRetries, ct);
    }

    private async Task<TalkSendAck> SendTalkInternalAsync(string toUserId, string text, string clientMsgId, CancellationToken ct)
    {
        var response = await SendRequestAsync(
            new Envelope
            {
                TalkSend = new TalkSend
                {
                    ClientTalkId = clientMsgId,
                    ToUserId = toUserId,
                    Text = text,
                    ClientTs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
                }
            },
            Envelope.PayloadOneofCase.TalkSendAck,
            ct);

        return response.TalkSendAck;
    }

    public async Task<TalkHistoryResponse> GetHistoryAsync(long sinceMillis, int limit, CancellationToken ct)
    {
        var response = await SendRequestAsync(
            new Envelope
            {
                TalkHistoryReq = new TalkHistoryRequest
                {
                    SinceTs = sinceMillis,
                    Limit = limit
                }
            },
            Envelope.PayloadOneofCase.TalkHistoryRes,
            ct);

        TalkHistoryReceived?.Invoke(this, response.TalkHistoryRes);
        return response.TalkHistoryRes;
    }

    internal static int ComputeRetryDelayMs(int attempt)
    {
        return Math.Min(3000, 500 * (1 << attempt));
    }
}

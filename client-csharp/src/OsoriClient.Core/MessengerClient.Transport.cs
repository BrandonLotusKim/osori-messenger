using System.IO;
using System.Net.WebSockets;
using Google.Protobuf;
using Osori.Proto.V1;

namespace OsoriClient.Core;

/// <summary>요청-응답 프레이밍(시퀀스/타임아웃), 소켓 송수신 루프, 수신 이벤트 디스패치.</summary>
public sealed partial class MessengerClient
{
    /// <summary>
    /// ix — 요청-응답(SendRequestAsync) 최대 대기 시간.
    /// 이전에는 타임아웃이 전혀 없어서, 서버가 응답을 못 보내는 어떤 이유(디스패치 누락,
    /// 네트워크 단절, 서버측 예외로 ack 미전송 등)로든 호출자가 CancellationToken.None을
    /// 넘기면 영원히 대기했다 — 파일첨부가 "업로드 중"에서 안 끝나는 버그(B1)의 근본 원인.
    /// 이제는 응답이 안 오면 이 시간 후 TimeoutException 으로 실패해서, 호출자가
    /// catch 해서 "전송 실패"로 화면에 표시할 수 있다(조용히 멈추지 않음).
    /// </summary>
    public TimeSpan RequestTimeout { get; set; } = TimeSpan.FromSeconds(20);

    private async Task<Envelope> SendRequestAsync(Envelope envelope, Envelope.PayloadOneofCase expectedPayload, CancellationToken ct)
    {
        var sequence = Interlocked.Increment(ref _sequence);
        envelope.Sequence = (ulong)sequence;
        var tcs = new TaskCompletionSource<Envelope>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!_pending.TryAdd(sequence, tcs))
        {
            throw new InvalidOperationException("시퀀스 등록에 실패했습니다.");
        }

        using var timeoutCts = new CancellationTokenSource(RequestTimeout);
        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(ct, timeoutCts.Token);
        using var registration = linkedCts.Token.Register(() =>
        {
            if (_pending.TryRemove(sequence, out var pending))
            {
                if (timeoutCts.IsCancellationRequested && !ct.IsCancellationRequested)
                {
                    pending.TrySetException(new TimeoutException(
                        $"서버 응답 대기 시간 초과 ({RequestTimeout.TotalSeconds:0}s, seq={sequence}, expected={expectedPayload})"));
                }
                else
                {
                    pending.TrySetCanceled(ct);
                }
            }
        });

        await SendAsync(envelope, ct);
        var response = await tcs.Task;
        if (response.PayloadCase != expectedPayload)
        {
            throw new InvalidOperationException($"예상한 응답이 아닙니다: {response.PayloadCase}");
        }

        return response;
    }

    private async Task SendAsync(Envelope envelope, CancellationToken ct)
    {
        var bytes = envelope.ToByteArray();
        await _sendLock.WaitAsync(ct);
        try
        {
            await _socket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Binary, true, ct);
        }
        finally
        {
            _sendLock.Release();
        }
    }

    private async Task ReceiveLoopAsync(ClientWebSocket socket, CancellationToken ct)
    {
        var buffer = new byte[4096];
        Exception? failure = null;
        var closedByPeer = false;

        try
        {
            while (!ct.IsCancellationRequested)
            {
                using var stream = new MemoryStream();
                WebSocketReceiveResult result;
                do
                {
                    result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        closedByPeer = true;
                        // 구버전 서버(종료 통지 메시지 없음)도 종료 설명으로 중복 로그인을 알 수 있다.
                        if (ReferenceEquals(socket, _socket)
                            && IsSupersededSignal(null, result.CloseStatusDescription ?? socket.CloseStatusDescription))
                        {
                            MarkSuperseded(null);
                        }
                        break;
                    }

                    stream.Write(buffer, 0, result.Count);
                }
                while (!result.EndOfMessage);

                if (closedByPeer)
                {
                    break;
                }

                var envelope = Envelope.Parser.ParseFrom(stream.ToArray());
                await DispatchEnvelopeAsync(envelope);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            failure = ex;
        }

        if (failure is not null)
        {
            FailPendingRequests(failure);
        }
        else if (closedByPeer)
        {
            FailPendingRequests(new WebSocketException("WebSocket 연결이 종료되었습니다."));
        }

        if (ShouldAttemptReconnect(socket))
        {
            try
            {
                await TryReconnectAsync();
            }
            catch
            {
            }
        }
    }

    /// <summary>테스트용 — 서버 프레임 수신을 흉내 낸다.</summary>
    internal Task DispatchForTestAsync(Envelope envelope) => DispatchEnvelopeAsync(envelope);

    /// <summary>테스트용 — 현재 상태에서 자동 재접속을 시도할지.</summary>
    internal bool ShouldAttemptReconnectForTest() => ShouldAttemptReconnect(_socket);

    private Task DispatchEnvelopeAsync(Envelope envelope)
    {
        var sequence = unchecked((long)envelope.Sequence);
        if (_pending.TryRemove(sequence, out var pending))
        {
            pending.TrySetResult(envelope);
            return Task.CompletedTask;
        }

        if (envelope.PayloadCase == Envelope.PayloadOneofCase.FileDownloadChunk)
        {
            HandleDownloadChunk(envelope.FileDownloadChunk);
            return Task.CompletedTask;
        }

        if (envelope.PayloadCase == Envelope.PayloadOneofCase.PresenceEvent)
        {
            Presence.Apply(envelope.PresenceEvent);
        }

        if (envelope.PayloadCase == Envelope.PayloadOneofCase.SessionClosed)
        {
            if (IsSupersededSignal(envelope.SessionClosed, null))
            {
                MarkSuperseded(envelope.SessionClosed.Message);
            }
            return Task.CompletedTask;
        }

        DispatchEnvelopeCore(
            envelope,
            this,
            TalkReceived,
            TalkHistoryReceived,
            PresenceChanged,
            HeartbeatAckReceived,
            TalkRoomPushed,
            FileAvailablePushed,
            MessagePushed,
            RoomMemberChanged,
            RoomRenamed,
            RoomReadUpdated);

        return Task.CompletedTask;
    }

    internal static void DispatchEnvelopeCore(
        Envelope envelope,
        object? sender,
        EventHandler<TalkReceivedEventArgs>? messageReceived,
        TalkHistoryReceivedEventHandler? historyReceived,
        EventHandler<PresenceEvent>? presenceChanged,
        EventHandler<HeartbeatAck>? heartbeatAckReceived,
        EventHandler<TalkRoomReceived>? roomMessagePushed = null,
        EventHandler<FileAvailableNotification>? fileAvailablePushed = null,
        EventHandler<MessageReceived>? memoPushed = null,
        EventHandler<TalkRoomMemberEvent>? roomMemberChanged = null,
        EventHandler<TalkRoomRenamed>? roomRenamed = null,
        EventHandler<TalkRoomReadUpdate>? roomReadUpdated = null)
    {
        switch (envelope.PayloadCase)
        {
            case Envelope.PayloadOneofCase.TalkReceived:
                messageReceived?.Invoke(sender, new TalkReceivedEventArgs(envelope.TalkReceived));
                break;
            case Envelope.PayloadOneofCase.TalkHistoryRes:
                historyReceived?.Invoke(sender, envelope.TalkHistoryRes);
                break;
            case Envelope.PayloadOneofCase.PresenceEvent:
                presenceChanged?.Invoke(sender, envelope.PresenceEvent);
                break;
            case Envelope.PayloadOneofCase.HeartbeatAck:
                heartbeatAckReceived?.Invoke(sender, envelope.HeartbeatAck);
                break;
            case Envelope.PayloadOneofCase.TalkRoomReceived:
                roomMessagePushed?.Invoke(sender, envelope.TalkRoomReceived);
                break;
            case Envelope.PayloadOneofCase.FileAvailable:
                fileAvailablePushed?.Invoke(sender, envelope.FileAvailable);
                break;
            case Envelope.PayloadOneofCase.MessageReceived:
                memoPushed?.Invoke(sender, envelope.MessageReceived);
                break;
            case Envelope.PayloadOneofCase.TalkRoomMemberEvent:
                roomMemberChanged?.Invoke(sender, envelope.TalkRoomMemberEvent);
                break;
            case Envelope.PayloadOneofCase.TalkRoomRenamed:
                roomRenamed?.Invoke(sender, envelope.TalkRoomRenamed);
                break;
            case Envelope.PayloadOneofCase.TalkRoomReadUpdate:
                roomReadUpdated?.Invoke(sender, envelope.TalkRoomReadUpdate);
                break;
        }
    }

    private void FailPendingRequests(Exception exception)
    {
        foreach (var pair in _pending.ToArray())
        {
            if (_pending.TryRemove(pair.Key, out var pending))
            {
                pending.TrySetException(exception);
            }
        }
    }
}

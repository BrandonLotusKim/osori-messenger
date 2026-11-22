using System.Net.WebSockets;
using Osori.Proto.V1;

namespace OsoriClient.Core;

/// <summary>하트비트 루프 + 끊김 감지 시 지수 백오프 자동 재접속/재로그인.</summary>
public sealed partial class MessengerClient
{
    public void StartHeartbeat()
    {
        if (_heartbeatCts is not null)
        {
            return;
        }

        _heartbeatCts = CancellationTokenSource.CreateLinkedTokenSource(_lifetimeCts.Token);
        _ = Task.Run(() => HeartbeatLoopAsync(_heartbeatCts.Token), _heartbeatCts.Token);
    }

    public void StopHeartbeat()
    {
        _heartbeatCts?.Cancel();
        _heartbeatCts?.Dispose();
        _heartbeatCts = null;
    }

    private async Task HeartbeatLoopAsync(CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromSeconds(30), ct);
                if (_socket.State != WebSocketState.Open)
                {
                    continue;
                }

                _ = SendRequestAsync(
                    new Envelope
                    {
                        Heartbeat = new Heartbeat { Ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() }
                    },
                    Envelope.PayloadOneofCase.HeartbeatAck,
                    ct);
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private bool ShouldAttemptReconnect(ClientWebSocket socket)
    {
        if (!AutoReconnectEnabled || _manualDisconnectRequested || _sessionSuperseded || _lifetimeCts.IsCancellationRequested)
        {
            return false;
        }

        return ReferenceEquals(socket, _socket);
    }

    private async Task TryReconnectAsync()
    {
        if (Interlocked.CompareExchange(ref _reconnectInProgress, 1, 0) != 0)
        {
            return;
        }

        try
        {
            if (_serverUri is null || _userId is null || _password is null || _deviceId is null)
            {
                ReconnectFailed?.Invoke(this, "재연결 정보가 부족합니다.");
                return;
            }

            Exception? lastError = null;

            while (!_lifetimeCts.IsCancellationRequested && !_manualDisconnectRequested && !_sessionSuperseded && _reconnectAttempts < _maxReconnectAttempts)
            {
                var attemptIndex = _reconnectAttempts;
                var delayMs = ComputeBackoffDelayMs(attemptIndex);
                _reconnectAttempts++;

                Reconnecting?.Invoke(this, EventArgs.Empty);
                await Task.Delay(delayMs, _lifetimeCts.Token);

                var newSocket = CreateSocket();
                try
                {
                    await newSocket.ConnectAsync(_serverUri, _lifetimeCts.Token);

                    var oldSocket = _socket;
                    _socket = newSocket;
                    _receiveTask = Task.Run(() => ReceiveLoopAsync(newSocket, _lifetimeCts.Token), _lifetimeCts.Token);

                    try
                    {
                        if (oldSocket.State == WebSocketState.Open || oldSocket.State == WebSocketState.CloseReceived)
                        {
                            await oldSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "reconnect", CancellationToken.None);
                        }
                    }
                    catch
                    {
                    }
                    finally
                    {
                        oldSocket.Dispose();
                    }

                    if (_sessionSuperseded)
                    {
                        // 재접속 대기 중에 다른 곳 로그인 통지를 받은 경우 — 재로그인하지 않는다.
                        return;
                    }
                    var response = await LoginAsync(_userId, _password, _deviceId, _lifetimeCts.Token);
                    if (response.Status != LoginResponse.Types.Status.Ok)
                    {
                        throw new InvalidOperationException($"재로그인 실패: {response.Status}");
                    }

                    _reconnectAttempts = 0;
                    Reconnected?.Invoke(this, EventArgs.Empty);
                    return;
                }
                catch (OperationCanceledException) when (_lifetimeCts.IsCancellationRequested || _manualDisconnectRequested)
                {
                    return;
                }
                catch (Exception ex)
                {
                    lastError = ex;
                    newSocket.Dispose();
                }
            }

            ReconnectFailed?.Invoke(this, lastError?.Message ?? "최대 재연결 시도를 초과했습니다.");
        }
        finally
        {
            Interlocked.Exchange(ref _reconnectInProgress, 0);
        }
    }

    internal static int ComputeBackoffDelayMs(int attempt)
    {
        const int baseMs = 1000;
        const int maxMs = 30000;
        return Math.Min(maxMs, baseMs * (1 << attempt));
    }
}

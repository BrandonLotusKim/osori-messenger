using System.Collections.Concurrent;
using System.Net.Security;
using System.Net.WebSockets;
using Osori.Proto.V1;

namespace OsoriClient.Core;

public delegate void TalkHistoryReceivedEventHandler(object? sender, TalkHistoryResponse response);

/// <summary>
/// 오소리 메신저 클라이언트 소켓/세션 진입점.
/// 기능 영역별로 partial class 로 분리되어 있다 (동작은 기존과 동일, 파일 구조만 정리):
///   - MessengerClient.cs            : 필드/이벤트/연결·로그인·로그아웃·Dispose (이 파일)
///   - MessengerClient.Transport.cs  : 요청-응답 프레이밍, 송수신 루프, 이벤트 디스패치
///   - MessengerClient.Reconnect.cs  : 하트비트 + 자동 재접속
///   - MessengerClient.Talk.cs       : 1:1 대화(talk) 송신/이력
///   - MessengerClient.Rooms.cs      : 대화방(room) 생성/입장/조회/이력
///   - MessengerClient.Files.cs      : 파일 업로드/다운로드
///   - MessengerClient.Messages.cs   : 쪽지(message) 발송/수신함/읽음/삭제
///   - MessengerClient.Directory.cs  : 조직도 조회, 검색, 푸시 토큰 등록/해제
/// 공개 API 시그니처는 모두 그대로이므로 호출부(WPF 등)는 영향받지 않는다.
/// </summary>
public sealed partial class MessengerClient : IAsyncDisposable
{
    private ClientWebSocket _socket = new();
    private readonly ConcurrentDictionary<long, TaskCompletionSource<Envelope>> _pending = new();
    private readonly ConcurrentDictionary<string, DownloadCollector> _activeDownloads = new(StringComparer.Ordinal);
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private readonly CancellationTokenSource _lifetimeCts = new();
    private readonly string _clientVersion = "OsoriClient.Wpf/0.1.0";
    private Task? _receiveTask;
    private CancellationTokenSource? _heartbeatCts;
    private long _sequence;
    private string? _sessionToken;
    private string? _userId;
    /// <summary>마지막으로 로그인한 사용자 ID(로그인 전이면 null).</summary>
    public string? UserId => _userId;
    private string? _password;
    private string? _deviceId;
    private Uri? _serverUri;
    private bool _acceptSelfSignedTls;
    private int _reconnectAttempts;
    private readonly int _maxReconnectAttempts = 10;
    private int _reconnectInProgress;
    private bool _manualDisconnectRequested;

    public bool AutoReconnectEnabled { get; set; } = true;

    /// <summary>현재 온라인 사용자 집합(서버 PresenceEvent 누적).</summary>
    public PresenceTracker Presence { get; } = new();

    public event EventHandler<TalkReceivedEventArgs>? TalkReceived;
    public event TalkHistoryReceivedEventHandler? TalkHistoryReceived;
    public event EventHandler<PresenceEvent>? PresenceChanged;
    public event EventHandler<HeartbeatAck>? HeartbeatAckReceived;
    public event EventHandler<TalkRoomReceived>? TalkRoomPushed;
    public event EventHandler<FileAvailableNotification>? FileAvailablePushed;
    public event EventHandler<MessageReceived>? MessagePushed;
    /// <summary>방 멤버 구성이 바뀔 때(초대/나가기) 서버가 실시간으로 보내는 이벤트.</summary>
    public event EventHandler<TalkRoomMemberEvent>? RoomMemberChanged;
    /// <summary>방 이름이 바뀔 때 서버가 실시간으로 보내는 이벤트.</summary>
    public event EventHandler<TalkRoomRenamed>? RoomRenamed;
    /// <summary>방 멤버의 읽음 위치가 앞으로 이동함(서버 push).</summary>
    public event EventHandler<TalkRoomReadUpdate>? RoomReadUpdated;
    public event EventHandler? Reconnecting;
    public event EventHandler? Reconnected;
    public event EventHandler<string>? ReconnectFailed;

    /// <summary>
    /// 같은 계정이 다른 곳에서 로그인해 서버가 이 세션을 끊었을 때(인자 = 안내 문구).
    /// 이때는 자동 재접속을 하지 않는다 — 재접속하면 상대 세션을 끊고, 상대도 재접속해 무한 교대가 된다.
    /// </summary>
    public event EventHandler<string>? SessionSuperseded;

    private volatile bool _sessionSuperseded;
    private int _supersededRaised;

    /// <summary>다른 곳 로그인으로 끊긴 상태인지(다시 로그인하기 전까지 true).</summary>
    public bool IsSessionSuperseded => _sessionSuperseded;

    internal const string SupersededDefaultMessage = "다른 곳에서 같은 계정으로 로그인되어 연결이 끊겼습니다.";

    /// <summary>서버 웹소켓 종료 설명(close reason) 중 중복 로그인 표식.</summary>
    internal const string SupersededCloseDescription = "already-online";

    /// <summary>서버 종료 통지/종료 설명이 "다른 곳 로그인" 인지 판정(순수 함수, 테스트용).</summary>
    internal static bool IsSupersededSignal(SessionClosed? notice, string? closeDescription) =>
        notice?.Reason == SessionClosed.Types.Reason.Superseded
        || string.Equals(closeDescription, SupersededCloseDescription, StringComparison.Ordinal);

    private void MarkSuperseded(string? message)
    {
        _sessionSuperseded = true;
        StopHeartbeat();
        if (Interlocked.Exchange(ref _supersededRaised, 1) == 0)
        {
            SessionSuperseded?.Invoke(this, string.IsNullOrWhiteSpace(message) ? SupersededDefaultMessage : message!);
        }
    }

    public async Task ConnectAsync(Uri serverUri, bool acceptSelfSignedTls, CancellationToken ct)
    {
        if (_socket.State is not WebSocketState.None and not WebSocketState.Closed)
        {
            throw new InvalidOperationException("이미 연결이 초기화되었습니다.");
        }

        _serverUri = serverUri;
        _acceptSelfSignedTls = acceptSelfSignedTls;
        _manualDisconnectRequested = false;

        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(ct, _lifetimeCts.Token);
        _socket = CreateSocket();
        await _socket.ConnectAsync(serverUri, linkedCts.Token);
        _receiveTask = Task.Run(() => ReceiveLoopAsync(_socket, _lifetimeCts.Token), _lifetimeCts.Token);
    }

    public async Task<LoginResponse> LoginAsync(string userId, string password, string deviceId, CancellationToken ct)
    {
        _userId = userId;
        _password = password;
        _deviceId = deviceId;
        Presence.Clear();

        var response = await SendRequestAsync(
            new Envelope
            {
                LoginReq = new LoginRequest
                {
                    UserId = userId,
                    Password = password,
                    DeviceId = deviceId,
                    ClientVersion = _clientVersion
                }
            },
            Envelope.PayloadOneofCase.LoginRes,
            ct);

        _sessionToken = response.LoginRes.SessionToken;
        _reconnectAttempts = 0;
        if (response.LoginRes.Status == LoginResponse.Types.Status.Ok)
        {
            _sessionSuperseded = false;
            Interlocked.Exchange(ref _supersededRaised, 0);
        }
        return response.LoginRes;
    }

    public async Task LogoutAsync(CancellationToken ct)
    {
        StopHeartbeat();
        _manualDisconnectRequested = true;
        if (string.IsNullOrWhiteSpace(_sessionToken) || _socket.State != WebSocketState.Open)
        {
            return;
        }

        try
        {
            await SendAsync(
                new Envelope
                {
                    Sequence = (ulong)Interlocked.Increment(ref _sequence),
                    Logout = new Logout { SessionToken = _sessionToken }
                },
                ct);
        }
        finally
        {
            _sessionToken = null;
            if (_socket.State == WebSocketState.Open || _socket.State == WebSocketState.CloseReceived)
            {
                await _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "logout", ct);
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        StopHeartbeat();
        _manualDisconnectRequested = true;
        _lifetimeCts.Cancel();
        FailPendingRequests(new OperationCanceledException(_lifetimeCts.Token));

        if (_socket.State == WebSocketState.Open || _socket.State == WebSocketState.CloseReceived)
        {
            try
            {
                await _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "dispose", CancellationToken.None);
            }
            catch
            {
            }
        }

        if (_receiveTask is not null)
        {
            try
            {
                await _receiveTask;
            }
            catch
            {
            }
        }

        _sendLock.Dispose();
        _socket.Dispose();
        _lifetimeCts.Dispose();
    }

    internal async Task ForceCloseSocketForTestAsync()
    {
        if (_socket.State == WebSocketState.Open)
        {
            try
            {
                await _socket.CloseOutputAsync(WebSocketCloseStatus.EndpointUnavailable, "test force close", CancellationToken.None);
            }
            catch
            {
            }
        }
    }

    private ClientWebSocket CreateSocket()
    {
        var socket = new ClientWebSocket();
        if (_serverUri?.Scheme.Equals("wss", StringComparison.OrdinalIgnoreCase) == true && _acceptSelfSignedTls)
        {
            socket.Options.RemoteCertificateValidationCallback = static (_, _, _, _) => true;
        }

        return socket;
    }
}

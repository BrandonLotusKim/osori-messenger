using System.ComponentModel;
using System.Runtime.CompilerServices;
using Osori.Proto.V1;
using OsoriClient.Core;

namespace OsoriClient.Wpf;

public sealed class TalkMessageRow : INotifyPropertyChanged
{
    private string? _serverMsgId;
    private long _serverTs;
    private TalkMessageStatus _status;

    public TalkMessageRow(string clientMsgId, string sender, string text)
    {
        ClientTalkId = clientMsgId;
        Sender = sender;
        Text = text;
        Direction = MessageDirection.Sent;
        Status = TalkMessageStatus.Sending;
    }

    public TalkMessageRow(TalkReceived msg, bool isHistory)
    {
        ClientTalkId = null;
        ServerTalkId = msg.ServerTalkId;
        _serverTs = msg.ServerTs;
        Sender = msg.FromUserId;
        Text = msg.Text;
        Direction = isHistory ? MessageDirection.History : MessageDirection.Received;
        _status = TalkMessageStatus.Received;
    }

    public TalkMessageRow(TalkRoomReceived msg, bool isHistory)
    {
        ClientTalkId = null;
        ServerTalkId = msg.ServerTalkId;
        _serverTs = msg.ServerTs;
        Sender = msg.FromUserId;
        Text = msg.Text;
        Direction = isHistory ? MessageDirection.History : MessageDirection.Received;
        _status = TalkMessageStatus.Received;
        AttachmentUploadId = string.IsNullOrEmpty(msg.AttachmentUploadId) ? null : msg.AttachmentUploadId;
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    /// <summary>방에 올린 파일 메시지면 업로드 ID — 더블클릭/Enter 로 내려받는다.</summary>
    public string? AttachmentUploadId { get; set; }

    private int? _unreadCount;

    /// <summary>내가 보낸 방 메시지를 아직 안 읽은 인원(null = 표시 안 함).</summary>
    public int? UnreadCount
    {
        get => _unreadCount;
        set
        {
            if (_unreadCount == value) return;
            _unreadCount = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(ReadLabel));
            OnPropertyChanged(nameof(HasUnread));
        }
    }

    /// <summary>말풍선 옆 표시: 안 읽은 인원 수 또는 "읽음".</summary>
    public string ReadLabel => ReadReceiptTracker.Label(_unreadCount);

    public bool HasUnread => _unreadCount is > 0;

    public string? ClientTalkId { get; init; }

    public string? ServerTalkId
    {
        get => _serverMsgId;
        set
        {
            if (_serverMsgId == value)
            {
                return;
            }

            _serverMsgId = value;
            OnPropertyChanged();
        }
    }

    public long ServerTs
    {
        get => _serverTs;
        set
        {
            if (_serverTs == value)
            {
                return;
            }

            _serverTs = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(TimestampText));
            OnPropertyChanged(nameof(AbsoluteTimestampText));
        }
    }

    public string Sender { get; set; }

    // 파일 전송 진행("업로드 중..." → "전송 완료", 다운로드 % 등)처럼 생성 후 문구가 바뀌므로 변경 알림이 필요하다.
    private string _text = string.Empty;
    public string Text
    {
        get => _text;
        set
        {
            if (_text == value) return;
            _text = value;
            OnPropertyChanged();
        }
    }

    public MessageDirection Direction { get; set; }

    public TalkMessageStatus Status
    {
        get => _status;
        set
        {
            if (_status == value)
            {
                return;
            }

            _status = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(StatusIcon));
        }
    }

    public string TimestampText => _serverTs > 0
        ? DateTimeOffset.FromUnixTimeMilliseconds(_serverTs).ToLocalTime().ToString("HH:mm")
        : string.Empty;

    /// <summary>Hover ToolTip 용 절대 시각 — yyyy-MM-dd HH:mm:ss</summary>
    public string AbsoluteTimestampText => _serverTs > 0
        ? DateTimeOffset.FromUnixTimeMilliseconds(_serverTs).ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss")
        : string.Empty;

    // ───── 말풍선 바인딩용 computed 속성 ─────

    /// <summary>내가 보낸 메시지인지 (Sent 방향) — 말풍선 위치 결정.</summary>
    public bool IsMine => Direction == MessageDirection.Sent;

    /// <summary>그룹방 표시용 발신자 이름(내 메시지엔 표시 안 함).</summary>
    public string SenderLabel => IsMine ? string.Empty : Sender;

    private static readonly string[] AvatarPalette =
    {
        "#E57373", "#BA68C8", "#7986CB", "#4FC3F7",
        "#4DB6AC", "#81C784", "#FFB74D", "#A1887F",
    };

    /// <summary>발신자 아바타 색 (userId SHA1 % 8).</summary>
    public string SenderAvatarHex
    {
        get
        {
            if (string.IsNullOrEmpty(Sender)) return "#BDBDBD";
            using var sha = System.Security.Cryptography.SHA1.Create();
            var bytes = sha.ComputeHash(System.Text.Encoding.UTF8.GetBytes(Sender));
            return AvatarPalette[bytes[0] % AvatarPalette.Length];
        }
    }

    public string SenderInitial => string.IsNullOrEmpty(Sender) ? "?" : Sender[0].ToString().ToUpperInvariant();

    /// <summary>내 메시지 상태 배지 이모지 (받은 메시지엔 빈 문자열).</summary>
    public string StatusIcon => Direction == MessageDirection.Sent
        ? _status switch
        {
            TalkMessageStatus.Sending       => "⏳",
            TalkMessageStatus.Delivered     => "✓",
            TalkMessageStatus.Queued        => "📮",
            TalkMessageStatus.Failed        => "⚠",
            TalkMessageStatus.InvalidTarget => "🚫",
            TalkMessageStatus.RateLimited   => "🚫",
            TalkMessageStatus.ServerError   => "⚠",
            _ => string.Empty,
        }
        : string.Empty;

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }
}

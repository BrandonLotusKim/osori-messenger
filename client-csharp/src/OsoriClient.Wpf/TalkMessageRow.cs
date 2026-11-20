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
    }

    public event PropertyChangedEventHandler? PropertyChanged;

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

    public string Text { get; set; }

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

    // ───── OC-D03 말풍선 바인딩용 computed 속성 ─────

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

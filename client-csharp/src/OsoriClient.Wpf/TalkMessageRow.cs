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

    private string? _savedPath;

    /// <summary>내려받아 저장한 위치(말풍선에는 쓰지 않고 툴팁·폴더 열기에만 쓴다).</summary>
    public string? SavedPath
    {
        get => _savedPath;
        set
        {
            if (_savedPath == value) return;
            _savedPath = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(BubbleToolTip));
        }
    }

    /// <summary>말풍선 툴팁: 보낸 시각, 저장한 파일이면 저장 위치도.</summary>
    public string BubbleToolTip => string.IsNullOrEmpty(_savedPath)
        ? AbsoluteTimestampText
        : $"{AbsoluteTimestampText}\n저장 위치: {_savedPath}";

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
            OnPropertyChanged(nameof(BubbleToolTip));
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
            // 파일 메시지(기록 포함)는 크기 "(41 B)"가 줄바꿈으로 갈라지지 않게 묶는다.
            var text = RoomFileText.KeepSizeTogether(value);
            if (_text == text) return;
            _text = text;
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
            OnPropertyChanged(nameof(StatusToolTip));
        }
    }

    /// <summary>상태 아이콘 툴팁·낭독 문구(내 메시지만). 실패면 재전송 방법도 알려 준다.</summary>
    public string StatusToolTip => IsMine ? TalkMessageText.StatusToolTip(_status) : string.Empty;

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

    /// <summary>발신자 아바타 색 — 대화 목록·헤더와 같은 공용 함수.</summary>
    public string SenderAvatarHex => AvatarStyle.Hex(Sender);

    public string SenderInitial => AvatarStyle.Initial(Sender);

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

using System.ComponentModel;
using System.Runtime.CompilerServices;
using OsoriClient.Core;

namespace OsoriClient.Wpf;

public sealed class TalkRow : INotifyPropertyChanged
{
    private int _unreadCount;
    private string? _directPeerId;
    private string _lastMessagePreview = string.Empty;
    private long _lastMessageTs;
    private string _presence = "offline"; // "online" | "offline" | "room"
    private string _displayName;

    public TalkRow(string counterpartId)
        : this(counterpartId, displayName: counterpartId, isRoom: false, roomId: null)
    {
    }

    public TalkRow(string counterpartId, string displayName, bool isRoom, string? roomId)
    {
        CounterpartId = counterpartId;
        _displayName = displayName;
        IsRoom = isRoom;
        RoomId = roomId;
    }

    public string CounterpartId { get; }

    /// <summary>대화방 이름 변경(TalkRoomRenamed 이벤트) 시 갱신할 수 있도록 setter 있음.</summary>
    public string DisplayName
    {
        get => _displayName;
        set
        {
            if (_displayName == value) return;
            _displayName = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(SidebarTitle));
            OnPropertyChanged(nameof(AvatarInitial));
            OnPropertyChanged(nameof(AccessibleName));
        }
    }

    /// <summary>
    /// 1:1 방이면 상대 사용자 ID(멤버 목록을 받았을 때 채움). 아바타 색을 대화창 헤더와 같은
    /// 기준(상대 ID)으로 계산하는 데 쓴다. 그룹 방·미확인이면 null → 방 ID 기준.
    /// </summary>
    public string? DirectPeerId
    {
        get => _directPeerId;
        set
        {
            if (_directPeerId == value) return;
            _directPeerId = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(AvatarColorHex));
            OnPropertyChanged(nameof(AvatarInitial));
        }
    }

    public bool IsRoom { get; }
    public string? RoomId { get; }
    // 2026-09-16: 카카오톡 PC 참고해서 1:1/그룹방을 목록에서 구분 없이 동일하게 표시하기로 결정
    // (방 이름 앞에 "#" 붙이던 것, 방 전용 폴더 아바타/보라색 전부 제거).
    public string SidebarTitle => DisplayName;

    public int UnreadCount
    {
        get => _unreadCount;
        set
        {
            if (_unreadCount == value)
            {
                return;
            }

            _unreadCount = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(HasUnread));
            OnPropertyChanged(nameof(AccessibleName));
        }
    }

    public bool HasUnread => _unreadCount > 0;

    /// <summary>목록 두 번째 줄 표시용 — 메시지가 없으면 기본 문구로 두 줄 구조를 유지한다.</summary>
    public string PreviewText => string.IsNullOrEmpty(_lastMessagePreview) ? "아직 메시지가 없습니다" : _lastMessagePreview;

    /// <summary>마지막 메시지 원문을 넣으면 목록용 문구(파일이면 "[파일] 이름", 그 외 한 줄)로 바꿔 저장한다.</summary>
    public string LastMessagePreview
    {
        get => _lastMessagePreview;
        set
        {
            var preview = TalkListFormat.Preview(value);
            if (_lastMessagePreview == preview)
            {
                return;
            }

            _lastMessagePreview = preview;
            OnPropertyChanged();
            OnPropertyChanged(nameof(PreviewText));
            OnPropertyChanged(nameof(AccessibleName));
        }
    }

    public long LastMessageTs
    {
        get => _lastMessageTs;
        set
        {
            if (_lastMessageTs == value)
            {
                return;
            }

            _lastMessageTs = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(LastMessageTsText));
        }
    }

    /// <summary>오늘이면 HH:mm, 올해면 MM-dd, 그 전이면 yyyy-MM-dd (쪽지 목록과 같은 규칙).</summary>
    public string LastMessageTsText => TalkListFormat.ListTime(_lastMessageTs);

    /// <summary>Presence 상태 — "online"/"offline"/"room". MainWindow 가 PresenceChanged 이벤트로 갱신.</summary>
    public string Presence
    {
        get => _presence;
        set
        {
            if (_presence == value) return;
            _presence = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(PresenceColorHex));
        }
    }

    /// <summary>DataTemplate 바인딩용 presence dot 색상 (hex).</summary>
    public string PresenceColorHex => _presence switch
    {
        "online" => "#4CAF50",
        "room"   => "#9E9E9E", // 방은 dot 숨기거나 grey
        _        => "#9E9E9E",
    };

    /// <summary>아바타 첫 글자 — 1:1/그룹방 구분 없이 표시 이름 첫 글자.</summary>
    // 1:1 은 대화창 헤더처럼 상대 ID 첫 글자(이름을 바꾼 1:1 방도 같은 아바타), 그룹 방은 방 이름 첫 글자.
    public string AvatarInitial => AvatarStyle.Initial(string.IsNullOrEmpty(DirectPeerId) ? DisplayName : DirectPeerId);

    /// <summary>해시 기반 아바타 배경색 — 1:1 은 상대 ID, 그룹 방은 방 ID 기준(대화창 헤더와 같은 함수).</summary>
    public string AvatarColorHex => AvatarStyle.Hex(AvatarStyle.TalkKey(CounterpartId, DirectPeerId));

    /// <summary>화면 읽기·UI 자동화용 이름: "방 이름, 미리보기[, 안 읽음 N개]".</summary>
    public string AccessibleName => _unreadCount > 0
        ? $"{DisplayName}, {PreviewText}, 안 읽음 {_unreadCount}개"
        : $"{DisplayName}, {PreviewText}";

    public override string ToString() => AccessibleName;

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }
}

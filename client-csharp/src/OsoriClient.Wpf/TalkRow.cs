using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace OsoriClient.Wpf;

public sealed class TalkRow : INotifyPropertyChanged
{
    private static readonly string[] AvatarPalette =
    {
        "#E57373", "#BA68C8", "#7986CB", "#4FC3F7",
        "#4DB6AC", "#81C784", "#FFB74D", "#A1887F",
    };

    private int _unreadCount;
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
        }
    }

    public bool HasUnread => _unreadCount > 0;

    public string LastMessagePreview
    {
        get => _lastMessagePreview;
        set
        {
            if (_lastMessagePreview == value)
            {
                return;
            }

            _lastMessagePreview = value;
            OnPropertyChanged();
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

    public string LastMessageTsText => _lastMessageTs > 0
        ? DateTimeOffset.FromUnixTimeMilliseconds(_lastMessageTs).ToLocalTime().ToString("HH:mm")
        : string.Empty;

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
    public string AvatarInitial => string.IsNullOrEmpty(DisplayName) ? "?" : DisplayName[0].ToString().ToUpperInvariant();

    /// <summary>해시 기반 아바타 배경색 — 1:1/그룹방 구분 없이 동일한 규칙.</summary>
    public string AvatarColorHex => AvatarPalette[GetAvatarIndex(CounterpartId)];

    private static int GetAvatarIndex(string id)
    {
        if (string.IsNullOrEmpty(id)) return 0;
        using var sha = System.Security.Cryptography.SHA1.Create();
        var bytes = sha.ComputeHash(System.Text.Encoding.UTF8.GetBytes(id));
        return bytes[0] % AvatarPalette.Length;
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }
}

using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Globalization;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Win32;
using Osori.Proto.V1;
using OsoriClient.Core;
using OsoriClient.Wpf.Helpers;

namespace OsoriClient.Wpf;

public partial class TalkWindow : Window
{
    public enum Mode
    {
        DirectMessage,
        Room,
    }

    private const double BottomThreshold = 100;
    private const double TopThreshold = 50;
    private int _currentHistoryLimit = 50;
    private int _currentRoomHistoryLimit = 50;
    private bool _isLoadingOlder = false;

    private readonly MessengerClient _client;
    private readonly string _currentUserId;
    private readonly string _counterpartKey;
    private readonly string _displayName;
    private readonly Mode _mode;
    private readonly bool _pendingInvite;
    // 초대하기(대화방 만들기 대기 창의 최초 초대 + 기존 방에 더 초대하기)에 조직도 선택 UI 를
    // 띄우려면 필요 — Room 모드가 아닌 창(DM)에서는 초대 메뉴 자체를 안 보여주므로 null 이어도 무방.
    private readonly IReadOnlyList<OrgTreeNode>? _orgRoots;
    private readonly SettingsStore _settings = new();

    private readonly ObservableCollection<TalkMessageItem> _messages = new();
    private readonly Dictionary<string, TalkMessageRow> _pendingByClientMsgId = new(StringComparer.Ordinal);
    private readonly HashSet<string> _seenServerMsgIds = new(StringComparer.Ordinal);
    private readonly HashSet<string> _seenRoomMsgIds = new(StringComparer.Ordinal);
    private readonly ObservableCollection<string> _roomMembers = new();
    private readonly ObservableCollection<MemberDisplayItem> _memberDisplayItems = new();

    private ScrollViewer? _messagesScrollViewer;
    private int _newMessageBadgeCount;
    private int _unread;

    public string CounterpartKey => _counterpartKey;

    /// <summary>
    /// 이 창에서 메시지를 로컬로 전송했을 때 발생 (미리보기 텍스트, server_ts).
    /// MainWindow 가 구독해서 대화 목록의 마지막 대화 미리보기를 갱신한다.
    /// 서버는 발신자 본인에게는 TalkReceived 를 다시 push 하지 않으므로,
    /// MainWindow 는 이 이벤트 없이는 자신이 보낸 메시지를 알 방법이 없었다.
    /// </summary>
    public event Action<string, long>? MessageSentLocally;

    /// <summary>
    /// 대화 상대 초대 대기 창에서 "대화 상대 초대하기"로 상대(들)를 확정지었을 때 발생.
    /// MainWindow 가 구독해서 실제 대화방 생성(중복 방 감지 포함)을 처리하고, 이 창은 곧바로
    /// Close() 된다 — "빈 창을 실제 대화방으로 전환"하지 않고 Room 모드로 새로 여는 편이
    /// (MainWindow.OpenTalk 을 그대로 재사용할 수 있어) 훨씬 단순하고 중복 코드가 없다.
    /// </summary>
    public event Action<IReadOnlyList<string>>? CounterpartInvited;

    /// <summary>방 나가기에 성공했을 때 발생(RoomId) — MainWindow 가 사이드바에서 이 방을 제거한다.</summary>
    public event Action<string>? RoomLeft;

    public TalkWindow(
        MessengerClient client,
        string currentUserId,
        string counterpartKey,
        Mode mode,
        string displayName,
        IReadOnlyList<OrgTreeNode>? orgRoots = null)
    {
        InitializeComponent();
        WindowCornerHelper.ApplyRoundCorners(this);
        _client = client ?? throw new ArgumentNullException(nameof(client));
        _currentUserId = currentUserId ?? throw new ArgumentNullException(nameof(currentUserId));
        _counterpartKey = counterpartKey ?? throw new ArgumentNullException(nameof(counterpartKey));
        _displayName = string.IsNullOrWhiteSpace(displayName) ? counterpartKey : displayName;
        _mode = mode;
        _orgRoots = orgRoots;

        Title = $"{_displayName} - 오소리 메신저";
        TalkMessagesListBox.ItemsSource = _messages;
        MemberListBox.ItemsSource = _memberDisplayItems;

        ApplyHeader();
        RestoreTalkBounds();

        _client.TalkReceived += OnClientMessageReceived;
        _client.TalkRoomPushed += OnClientRoomMessagePushed;
        _client.RoomReadUpdated += OnClientRoomReadUpdated;
        _roomMembers.CollectionChanged += (_, _) => RefreshReadLabels();
        _client.FileAvailablePushed += OnClientFileAvailable;
        _client.PresenceChanged += OnClientPresenceChangedForHeader;
        _client.RoomMemberChanged += OnClientRoomMemberChanged;
        _client.RoomRenamed += OnClientRoomRenamed;

        Loaded += TalkWindow_OnLoaded;
        Activated += TalkWindow_OnActivated;
        Closed += TalkWindow_OnClosed;
        StateChanged += (_, _) => UpdateMaximizeButtonState();

        // TalkWindow 단축키: Esc=닫기, Ctrl+M=멤버 드로어(Room), Ctrl+Shift+F=현재 대화 본문 검색(향후)
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape)
            {
                Close(); e.Handled = true;
            }
            else if (e.Key == Key.M && Keyboard.Modifiers.HasFlag(ModifierKeys.Control) && _mode == Mode.Room)
            {
                HeaderMemberButton_OnClick(this, new RoutedEventArgs());
                e.Handled = true;
            }
        };
    }

    /// <summary>
    /// 대화 상대가 아직 정해지지 않은 "초대 대기" 창. 조직도에서 아무도(또는 조직만) 포커싱된
    /// 상태에서 플러그인 "대화 보내기" 버튼을 눌렀을 때 사용 — 창 가운데 "대화 상대 초대하기"
    /// 버튼 외에는 아무 조작도 안 되다가(PendingInviteOverlay), 상대(들)를 고르면 이 창은 닫히고
    /// MainWindow 가 진짜 대화방을 만들어(또는 이미 있으면 열어) 새 창을 띄운다.
    /// </summary>
    public TalkWindow(MessengerClient client, string currentUserId, IReadOnlyList<OrgTreeNode>? orgRoots)
        : this(client, currentUserId, string.Empty, Mode.DirectMessage, "대화 상대 초대", orgRoots)
    {
        _pendingInvite = true;
        PendingInviteOverlay.Visibility = Visibility.Visible;

        // PendingInviteOverlay 는 마우스 클릭만 막을 뿐이라(위에 그려진 Border 하나일 뿐),
        // Tab 키로 뒤에 깔린 컨트롤에 키보드 포커스가 넘어가면 오버레이를 우회해 타이핑/전송이
        // 가능해지는 구멍이 있었다. "초대 버튼 말고는 아무것도 동작하면 안 된다"는 요구사항을
        // 확실히 지키기 위해 실제로 IsEnabled 도 꺼둔다. 이 창은 초대 확정 시 바로 Close() 되므로
        // 다시 켜줄 필요가 없다.
        TalkInputBox.IsEnabled = false;
        AttachButton.IsEnabled = false;
        SendButton.IsEnabled = false;
        HeaderSearchButton.IsEnabled = false;
        HeaderMenuButton.IsEnabled = false;
    }

    private void ApplyHeader()
    {
        HeaderTitleText.Text = _displayName;

        if (_mode == Mode.Room)
        {
            HeaderMemberButton.Visibility = Visibility.Visible;
            ApplyGroupHeaderAvatar();
            HeaderMetaText.Text = "그룹 대화";
            return;
        }

        HeaderAvatarInitial.Text = AvatarStyle.Initial(_displayName);
        HeaderAvatarBorder.Background = new SolidColorBrush(HashColor(_counterpartKey));
        HeaderMetaText.Text = string.Empty;
    }

    /// <summary>
    /// 1:1 방(멤버 2명)이면 헤더를 상대 기준으로: 상대 이니셜·색 아바타, 접속 점, "온라인/오프라인".
    /// 그룹 방은 기존처럼 방 아이콘 + 멤버 수. (예전엔 1:1 도 아바타 "방", 창 제목 "대화" 였다.)
    /// </summary>
    private void UpdateRoomHeaderAppearance()
    {
        if (_mode != Mode.Room) return;
        var peer = RoomPeers.TryGetDirectCounterpart(_roomMembers, _currentUserId);
        if (peer is null)
        {
            ApplyGroupHeaderAvatar();
            return;
        }
        HeaderAvatarInitial.Text = AvatarStyle.Initial(peer);
        HeaderAvatarBorder.Background = new SolidColorBrush(HashColor(peer));
        var online = _client.Presence.IsOnline(peer);
        HeaderPresenceDot.Visibility = Visibility.Visible;
        HeaderPresenceDot.Fill = (Brush)FindResource(online ? "PresenceOnlineBrush" : "PresenceOfflineBrush");
        HeaderMetaText.Text = online ? "온라인" : "오프라인";
    }

    private void OnClientPresenceChangedForHeader(object? sender, PresenceEvent e)
    {
        Dispatcher.BeginInvoke(() =>
        {
            var peer = RoomPeers.TryGetDirectCounterpart(_roomMembers, _currentUserId);
            if (peer is not null && string.Equals(peer, e.UserId, StringComparison.OrdinalIgnoreCase))
            {
                UpdateRoomHeaderAppearance();
            }
        });
    }

    /// <summary>대화 목록(TalkRow)과 같은 공용 함수(AvatarStyle)로 색을 계산한다.</summary>
    private static Color HashColor(string id)
        => (Color)ColorConverter.ConvertFromString(AvatarStyle.Hex(id));

    /// <summary>
    /// 그룹 방 헤더 아바타 — 대화 목록과 같은 규칙(방 이름 첫 글자, 방 ID 기준 색).
    /// 예전엔 목록은 이름 첫 글자, 헤더는 "방"+고정 보라색이라 같은 방이 다르게 보였다.
    /// </summary>
    private void ApplyGroupHeaderAvatar()
    {
        HeaderAvatarInitial.Text = AvatarStyle.Initial(HeaderTitleText.Text);
        HeaderAvatarBorder.Background = new SolidColorBrush(HashColor(_counterpartKey));
        HeaderPresenceDot.Visibility = Visibility.Collapsed;
    }

    private async void TalkWindow_OnLoaded(object sender, RoutedEventArgs e)
    {
        if (_pendingInvite)
        {
            // TalkInputBox 는 PendingInviteOverlay 에 시각적으로/마우스클릭만 가려져 있을 뿐
            // 실제로는 Enabled 상태라서, 여기서 TalkInputBox.Focus() 를 그대로 두면 오버레이가
            // 마우스는 막아도 키보드 포커스는 그대로 입력란에 가 있어 사용자가 클릭 없이
            // 타이핑만으로 글을 쓰고 Enter 로 전송을 시도할 수 있었다(_counterpartKey="" 로
            // 서버에 빈 수신자 전송 시도하는 버그). 대신 초대 버튼으로 포커스를 둔다.
            InviteCounterpartButton.Focus();
            InitialLoadingOverlay.Visibility = Visibility.Collapsed;
            return;
        }

        TalkInputBox.Focus();

        try
        {
            if (_mode == Mode.DirectMessage)
            {
                await LoadInitialHistoryAsync();
            }
            else
            {
                await LoadRoomInfoAsync();
                await LoadInitialRoomHistoryAsync();
            }
        }
        finally
        {
            //: 히스토리/룸정보 로드 완료(또는 실패) 시 오버레이 숨김
            InitialLoadingOverlay.Visibility = Visibility.Collapsed;
            _messages.CollectionChanged += (_, _) => UpdateEmptyRoomHint();
            UpdateEmptyRoomHint();
        }
    }

    private void InviteCounterpartButton_OnClick(object sender, RoutedEventArgs e)
    {
        if (_orgRoots is null) return;
        var picker = new MessageRecipientPicker(_orgRoots, Array.Empty<string>(), excludeUserIds: new[] { _currentUserId }) { Owner = this };
        picker.SetPurpose("대화 상대 선택", "대화 시작", "대화할 사람을 골라 주세요");
        if (picker.ShowDialog() != true) return;

        var targets = picker.SelectedUserIds
            .Where(id => !string.Equals(id, _currentUserId, StringComparison.OrdinalIgnoreCase))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        if (targets.Count == 0)
        {
            if (picker.SelectedUserIds.Any(id => string.Equals(id, _currentUserId, StringComparison.OrdinalIgnoreCase)))
            {
                AppDialog.Info(this, "자기 자신과는 대화를 시작할 수 없습니다.", "오소리 메신저");
            }
            return;
        }

        // 실제 대화방 생성(동일 구성원 방 중복 감지 포함)은 MainWindow 가 처리한다 — 이 창은
        // 상대를 고르는 역할만 하고 바로 닫힌 뒤, MainWindow.OpenTalk 로 Room 모드 창이 새로 뜬다.
        CounterpartInvited?.Invoke(targets);
        Close();
    }

    private void TalkWindow_OnActivated(object? sender, EventArgs e)
    {
        ResetUnreadState();
        TryReportRead();
        // 비활성일 때 온 파일은 묻지 않고 쌓아 두었다가, 사용자가 이 창으로 돌아오면 그때 묻는다.
        if (_pendingFileOffers.Count > 0)
        {
            var (offer, row) = _pendingFileOffers.Dequeue();
            _ = Dispatcher.BeginInvoke(() => _ = HandleFileOfferAsync(offer, row));
        }
    }

    /// <summary>창이 비활성일 때 받은 파일 알림 — 모달 질문 창이 다른 앱의 포커스를 가져가지 않게 미뤄 둔다.</summary>
    private readonly Queue<(FileAvailableNotification Offer, TalkMessageRow Row)> _pendingFileOffers = new();

    private async Task LoadRoomInfoAsync()
    {
        try
        {
            var info = await _client.GetRoomInfoAsync(_counterpartKey, CancellationToken.None);
            _ = Dispatcher.BeginInvoke(() =>
            {
                _roomMembers.Clear();
                foreach (var memberId in info.MemberIds)
                {
                    _roomMembers.Add(memberId);
                }
                RebuildMemberDisplayItems();
                DrawerHeaderText.Text = $"멤버 ({info.MemberCount})";
                HeaderMetaText.Text = $"멤버 {info.MemberCount}명";
                var resolvedName = MainWindow.ResolveRoomDisplayName(info.Name, info.NameIsCustom, info.MemberIds, _currentUserId, _counterpartKey);
                if (!string.IsNullOrWhiteSpace(resolvedName))
                {
                    Title = $"{resolvedName} - 오소리 메신저";
                    HeaderTitleText.Text = resolvedName;
                }
                UpdateRoomHeaderAppearance();
            });
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[Conv] room info failed: {ex.Message}");
        }
    }

    private void TalkWindow_OnClosed(object? sender, EventArgs e)
    {
        _client.TalkReceived -= OnClientMessageReceived;
        _client.TalkRoomPushed -= OnClientRoomMessagePushed;
        _client.RoomReadUpdated -= OnClientRoomReadUpdated;
        _client.FileAvailablePushed -= OnClientFileAvailable;
        _client.PresenceChanged -= OnClientPresenceChangedForHeader;
        _client.RoomMemberChanged -= OnClientRoomMemberChanged;
        _client.RoomRenamed -= OnClientRoomRenamed;

        if (_messagesScrollViewer is not null)
        {
            _messagesScrollViewer.ScrollChanged -= MessagesScrollViewer_OnScrollChanged;
        }

        _settings.Update(s =>
        {
            s.TalkWindowBounds = new WindowBounds
            {
                X = Left,
                Y = Top,
                Width = Width,
                Height = Height,
                Maximized = WindowState == WindowState.Maximized,
            };
        });
    }

    /// <summary>
    /// 이전에 저장된 위치를 복원했는지 — false 면 아직 한 번도 안 열어본 첫 오픈이라는 뜻이라,
    /// 호출자(MainWindow.OpenTalk)가 대화창을 자기 옆으로 배치해야 한다. 안 그러면 둘 다
    /// WindowStartupLocation=CenterScreen 이라 화면 중앙에서 완전히 겹쳐 MainWindow가
    /// 가려지고, 사용자는 "조직도 우클릭이 안 된다"고 오해하게 된다(실제론 클릭이 대화창에 먹힘).
    /// </summary>
    public bool RestoredSavedPosition { get; private set; }

    private void RestoreTalkBounds()
    {
        var bounds = _settings.Current.TalkWindowBounds;
        if (bounds is null || bounds.Width <= 0 || bounds.Height <= 0)
        {
            return;
        }

        Left = bounds.X + 40;
        Top = bounds.Y + 40;
        Width = bounds.Width;
        Height = bounds.Height;
        RestoredSavedPosition = true;
    }

    private async Task LoadInitialHistoryAsync()
    {
        try
        {
            var history = await _client.GetHistoryAsync(
                0,
                _settings.Current.HistoryPageSize,
                CancellationToken.None);

            foreach (var msg in history.Talks)
            {
                if (!IsMessageForThisTalk(msg))
                {
                    continue;
                }

                if (!_seenServerMsgIds.Add(msg.ServerTalkId))
                {
                    continue;
                }

                AddMessageRow(BuildHistoryRow(msg));
            }

            ScrollToBottom();
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[Conv] history load failed: {ex.Message}");
        }
    }

    private bool IsMessageForThisTalk(TalkReceived msg)
    {
        if (_mode != Mode.DirectMessage)
        {
            return false;
        }

        return msg.FromUserId == _counterpartKey || msg.ToUserId == _counterpartKey;
    }

    private TalkMessageRow BuildHistoryRow(TalkReceived msg)
    {
        // 버그 수정: 예전엔 본인이 보낸 메시지는 실제 배달 여부와 무관하게
        // 무조건 Delivered(✓)로 하드코딩했음 — 상대가 계속 오프라인이라 아직도
        // 아웃박스에 대기 중이어도 창을 닫았다 열면 체크로 잘못 표시됐다.
        // 서버가 history 응답에 실어주는 msg.Delivered(outbox 미배달 여부 기반)를 반영.
        return new TalkMessageRow(msg, isHistory: true)
        {
            Direction = msg.FromUserId == _currentUserId
                ? MessageDirection.Sent
                : MessageDirection.Received,
            Status = msg.FromUserId == _currentUserId
                ? (msg.Delivered ? TalkMessageStatus.Delivered : TalkMessageStatus.Queued)
                : TalkMessageStatus.Received,
        };
    }

    private void OnClientMessageReceived(object? sender, TalkReceivedEventArgs e)
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (!IsMessageForThisTalk(e.Message))
            {
                return;
            }

            if (!_seenServerMsgIds.Add(e.Message.ServerTalkId))
            {
                return;
            }

            if (e.Message.FromUserId == _currentUserId)
            {
                return;
            }

            var row = new TalkMessageRow(e.Message, isHistory: false)
            {
                Direction = MessageDirection.Received,
                Status = TalkMessageStatus.Received,
            };

            HandleIncomingRow(row);
        });
    }

    private void OnClientRoomMessagePushed(object? sender, TalkRoomReceived e)
    {
        if (_mode != Mode.Room)
        {
            return;
        }

        if (!string.Equals(e.RoomId, _counterpartKey, StringComparison.OrdinalIgnoreCase))
        {
            return;
        }

        Dispatcher.BeginInvoke(() =>
        {
            if (!_seenRoomMsgIds.Add(e.ServerTalkId))
            {
                return;
            }

            if (string.Equals(e.FromUserId, _currentUserId, StringComparison.OrdinalIgnoreCase))
            {
                return;
            }

            var row = new TalkMessageRow(e, isHistory: false)
            {
                Direction = MessageDirection.Received,
                Status = TalkMessageStatus.Received,
            };
            if (row.AttachmentUploadId is not null) row.Text = RoomFileText.WithDownloadHint(row.Text);

            HandleIncomingRow(row);
            // 보낸 사람은 자기 메시지까지 읽은 것 → 내 이전 메시지의 안 읽은 인원이 줄 수 있다.
            if (_reads.Apply(e.FromUserId, e.ServerTs)) RefreshReadLabels();
            TryReportRead();
        });
    }

    private void HandleIncomingRow(TalkMessageRow row)
    {
        var shouldAutoScroll = IsNearBottom();
        AddMessageRow(row);

        if (shouldAutoScroll)
        {
            ScrollToBottom();
            return;
        }

        _newMessageBadgeCount++;
        UpdateNewMessageBadge();

        if (!IsActive)
        {
            _unread++;
        }
    }

    private void TalkInputBox_OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && !Keyboard.Modifiers.HasFlag(ModifierKeys.Shift))
        {
            e.Handled = true;
            _ = TrySendMessageAsync();
        }
        else if (e.Key == Key.Escape && AttachmentPreview.Visibility == Visibility.Visible)
        {
            AttachmentCancelButton_OnClick(sender, new RoutedEventArgs());
        }
    }

    private async void SendButton_OnClick(object sender, RoutedEventArgs e)
    {
        await TrySendMessageAsync();
    }

    private async Task TrySendMessageAsync()
    {
        var text = TalkInputBox.Text.Trim();
        if (string.IsNullOrEmpty(text))
        {
            return;
        }

        if (_mode == Mode.DirectMessage)
        {
            await SendDirectMessageAsync(text);
        }
        else
        {
            await SendRoomMessageAsync(text);
        }
    }

    private async Task SendDirectMessageAsync(string text)
    {
        var clientMsgId = Guid.NewGuid().ToString("N");
        var nowTs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var row = new TalkMessageRow(clientMsgId, _currentUserId, text)
        {
            ServerTs = nowTs,
        };

        AddMessageRow(row);
        _pendingByClientMsgId[clientMsgId] = row;
        MessageSentLocally?.Invoke(text, nowTs);
        TalkInputBox.Clear();
        ScrollToBottom();

        try
        {
            var ack = await _client.SendTalkAsync(
                _counterpartKey,
                text,
                clientMsgId,
                maxRetries: 2,
                CancellationToken.None);

            row.ServerTalkId = ack.ServerTalkId;
            if (ack.ServerTs > 0)
            {
                row.ServerTs = ack.ServerTs;
            }

            row.Status = MessageStatusMapper.FromAckResult(ack.Result);
            if (!string.IsNullOrEmpty(ack.ServerTalkId))
            {
                _seenServerMsgIds.Add(ack.ServerTalkId);
            }
        }
        catch (Exception ex)
        {
            row.Status = TalkMessageStatus.Failed;
            System.Diagnostics.Debug.WriteLine($"[Conv] DM send failed: {ex.Message}");
        }
        finally
        {
            _pendingByClientMsgId.Remove(clientMsgId);
        }
    }

    private async Task SendRoomMessageAsync(string text)
    {
        var nowTs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var row = new TalkMessageRow(Guid.NewGuid().ToString("N"), _currentUserId, text)
        {
            ServerTs = nowTs,
        };

        AddMessageRow(row);
        MessageSentLocally?.Invoke(text, nowTs);
        TalkInputBox.Clear();
        ScrollToBottom();

        try
        {
            var ack = await _client.SendRoomMessageAsync(_counterpartKey, text, CancellationToken.None);
            if (ack.ServerTs > 0)
            {
                row.ServerTs = ack.ServerTs;
            }

            row.Status = ack.Result switch
            {
                TalkRoomSendAck.Types.Result.Ok => TalkMessageStatus.Delivered,
                TalkRoomSendAck.Types.Result.RateLimited => TalkMessageStatus.RateLimited,
                _ => TalkMessageStatus.Failed,
            };
            if (ack.Result != TalkRoomSendAck.Types.Result.Ok)
            {
                // 실패 이유를 말풍선 아래 표시 대신 짧은 알림으로(요청 제한이면 잠시 후 다시 보내면 됨).
                App.ShowInAppToast("보내지 못함", ResultMessages.RoomSend(ack.Result),
                    ack.Result == TalkRoomSendAck.Types.Result.RateLimited ? App.ToastLevel.Warn : App.ToastLevel.Error);
            }

            if (!string.IsNullOrEmpty(ack.ServerTalkId))
            {
                row.ServerTalkId = ack.ServerTalkId;
                _seenRoomMsgIds.Add(ack.ServerTalkId);
            }
            if (ack.ServerTs > 0) _reads.Apply(_currentUserId, ack.ServerTs);
            RefreshReadLabels();
        }
        catch (Exception ex)
        {
            row.Status = TalkMessageStatus.Failed;
            System.Diagnostics.Debug.WriteLine($"[Conv] Room send failed: {ex.Message}");
        }
    }

    private void AddMessageRow(TalkMessageRow row)
    {
        var item = new TalkMessageItem(row);
        _messages.Add(item);
        RefreshDateSeparatorAt(_messages.Count - 1);
        if (row.ServerTs <= 0)
        {
            // 보내는 중인 내 메시지는 서버 시각이 나중에 정해진다 — 그때 묶음·시각 표시를 다시 계산.
            void OnTs(object? s, PropertyChangedEventArgs a)
            {
                if (a.PropertyName != nameof(TalkMessageRow.ServerTs) || row.ServerTs <= 0) return;
                row.PropertyChanged -= OnTs;
                var idx = _messages.IndexOf(item);
                if (idx >= 0) RefreshDateSeparatorRange(idx, idx + 1);
            }
            row.PropertyChanged += OnTs;
        }
    }

    /// <summary>
    ///: 송신 실패 메시지를 우클릭 → "재전송" 메뉴 클릭 시 호출.
    /// 새 client_msg_id 로 새 row 추가하여 재전송. 기존 실패 row 는 시각적 증거로 보존.
    /// </summary>
    // 메시지 우클릭 → "텍스트 복사" — 모든 메시지에 항상 활성.
    private void CopyMessageMenuItem_OnClick(object sender, RoutedEventArgs e)
    {
        if (sender is not MenuItem mi) return;
        TalkMessageItem? item = mi.DataContext as TalkMessageItem;
        if (item is null && mi.Parent is ContextMenu cm && cm.PlacementTarget is FrameworkElement fe)
        {
            item = fe.DataContext as TalkMessageItem;
        }
        var text = item?.Row.Text;
        if (string.IsNullOrEmpty(text)) return;
        try { Clipboard.SetText(text); App.ShowInAppToast("복사됨", "메시지가 클립보드에 복사되었습니다", App.ToastLevel.Success, 1500); }
        catch { /* 클립보드 잠금 등 무시 */ }
    }

    private async void ResendMessageMenuItem_OnClick(object sender, RoutedEventArgs e)
    {
        if (sender is not MenuItem mi)
        {
            return;
        }

        // ContextMenu 의 DataContext 는 inheritance 안 되므로 PlacementTarget 우선
        TalkMessageItem? item = mi.DataContext as TalkMessageItem;
        if (item is null && mi.Parent is ContextMenu cm && cm.PlacementTarget is FrameworkElement fe)
        {
            item = fe.DataContext as TalkMessageItem;
        }

        var text = item?.Row.Text;
        if (string.IsNullOrEmpty(text))
        {
            return;
        }

        try
        {
            if (_mode == Mode.DirectMessage)
            {
                await SendDirectMessageAsync(text);
            }
            else
            {
                await SendRoomMessageAsync(text);
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[Conv] Resend failed: {ex.Message}");
        }
    }

    private void RefreshDateSeparatorAt(int index)
    {
        if (index < 0 || index >= _messages.Count)
        {
            return;
        }

        var item = _messages[index];
        var showDateSeparator = index == 0 || !IsSameLocalDate(_messages[index - 1].Row, item.Row);
        item.ShowDateSeparator = showDateSeparator;
        item.DateSeparatorLabel = showDateSeparator
            ? BuildDateSeparatorLabel(item.Row.ServerTs)
            : string.Empty;
        var prev = index > 0 ? _messages[index - 1].Row : null;
        item.IsContinuation = MessageGrouping.IsContinuation(
            prev?.Sender, prev?.ServerTs ?? 0, item.Row.Sender, item.Row.ServerTs, showDateSeparator);

        // 시각은 같은 사람·같은 분 묶음의 마지막에만 — 이 항목과 바로 앞 항목을 다시 계산한다.
        var next = index + 1 < _messages.Count ? _messages[index + 1].Row : null;
        item.ShowTime = MessageGrouping.ShowsTime(item.Row.Sender, item.Row.ServerTs, next?.Sender, next?.ServerTs ?? 0);
        if (prev is not null)
        {
            _messages[index - 1].ShowTime = MessageGrouping.ShowsTime(prev.Sender, prev.ServerTs, item.Row.Sender, item.Row.ServerTs);
        }
    }

    /// <summary>위쪽에 이전 메시지를 끼워 넣은 뒤: 끼워 넣은 항목들과 원래 첫 항목의 구분선·묶음을 다시 계산.</summary>
    private void RefreshDateSeparatorRange(int fromInclusive, int toInclusive)
    {
        for (var i = Math.Max(0, fromInclusive); i <= Math.Min(toInclusive, _messages.Count - 1); i++)
        {
            RefreshDateSeparatorAt(i);
        }
    }

    private void UpdateEmptyRoomHint()
    {
        EmptyRoomHint.Visibility = _messages.Count == 0 && !_pendingInvite ? Visibility.Visible : Visibility.Collapsed;
    }

    private static bool IsSameLocalDate(TalkMessageRow left, TalkMessageRow right)
    {
        return ToLocalDate(left.ServerTs) == ToLocalDate(right.ServerTs);
    }

    private static DateTime ToLocalDate(long serverTs)
    {
        if (serverTs <= 0)
        {
            return DateTime.Now.Date;
        }

        return DateTimeOffset.FromUnixTimeMilliseconds(serverTs).ToLocalTime().Date;
    }

    private static string BuildDateSeparatorLabel(long serverTs)
    {
        var culture = new CultureInfo("ko-KR");
        var date = ToLocalDate(serverTs);
        var today = DateTime.Now.Date;

        if (date == today)
        {
            return "오늘";
        }

        if (date == today.AddDays(-1))
        {
            return "어제";
        }

        return date.ToString("yyyy-MM-dd dddd", culture);
    }

    private void ScrollToBottom()
    {
        if (_messages.Count == 0)
        {
            return;
        }

        TalkMessagesListBox.ScrollIntoView(_messages[^1]);
    }

    /// <summary>
    /// 말풍선 항목은 Focusable=False 라 WPF 기본 클릭 선택(항목에 포커스를 줘야 선택됨)이 일어나지 않는다.
    /// 클릭한 항목을 직접 선택하고 목록 자체에 포커스를 둬서, 이어지는 위/아래·Enter 키와 더블클릭이 그 항목을 대상으로 하게 한다.
    /// </summary>
    private void TalkMessagesListBox_OnPreviewMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is not DependencyObject source) return;
        if (ItemsControl.ContainerFromElement(TalkMessagesListBox, source) is not ListBoxItem container) return;
        container.IsSelected = true;
        TalkMessagesListBox.Focus();
    }

    private void TalkMessagesListBox_OnMouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        // 목록 항목은 TalkMessageItem(날짜 구분선 등 포함 래퍼) — 실제 메시지는 Row.
        if (TalkMessagesListBox.SelectedItem is TalkMessageItem { Row: var row } && row.AttachmentUploadId is not null)
            _ = DownloadAttachmentRowAsync(row);
    }

    private void TalkMessagesListBox_OnKeyDown(object sender, KeyEventArgs e)
    {
        // 말풍선 항목은 포커스를 받지 않으므로(입력칸 포커스 유지) 위/아래 키로 목록에서 직접 선택을 옮긴다.
        if (e.Key is Key.Up or Key.Down && _messages.Count > 0)
        {
            var i = TalkMessagesListBox.SelectedIndex;
            i = i < 0 ? _messages.Count - 1 : Math.Clamp(i + (e.Key == Key.Up ? -1 : 1), 0, _messages.Count - 1);
            TalkMessagesListBox.SelectedIndex = i;
            TalkMessagesListBox.ScrollIntoView(_messages[i]);
            e.Handled = true;
            return;
        }
        if (e.Key == Key.Enter && TalkMessagesListBox.SelectedItem is TalkMessageItem { Row: var row } && row.AttachmentUploadId is not null)
        {
            e.Handled = true;
            _ = DownloadAttachmentRowAsync(row);
        }
    }

    /// <summary>기록에 남은 방 파일 메시지(또는 나중에 받기를 고른 알림)를 사용자가 직접 내려받는다.</summary>
    private async Task DownloadAttachmentRowAsync(TalkMessageRow row)
    {
        var uploadId = row.AttachmentUploadId;
        if (string.IsNullOrEmpty(uploadId)) return;
        // 이미 저장한 파일이면 다시 받지 않고 저장 폴더를 연다(사용자가 직접 고른 동작).
        if (!string.IsNullOrEmpty(row.SavedPath) && System.IO.File.Exists(row.SavedPath))
        {
            System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{row.SavedPath}\"");
            return;
        }
        var notice = new FileAvailableNotification
        {
            UploadId = uploadId,
            FromUserId = row.Sender,
            Filename = RoomFileText.ParseFileName(row.Text) ?? "file",
            RoomId = _mode == Mode.Room ? _counterpartKey : "",
        };
        await DownloadFileToAsync(notice, row, originalText: row.Text);
    }

    private void TalkMessagesListBox_OnLoaded(object sender, RoutedEventArgs e)
    {
        _messagesScrollViewer ??= FindDescendant<ScrollViewer>(TalkMessagesListBox);
        if (_messagesScrollViewer is null)
        {
            return;
        }

        _messagesScrollViewer.ScrollChanged -= MessagesScrollViewer_OnScrollChanged;
        _messagesScrollViewer.ScrollChanged += MessagesScrollViewer_OnScrollChanged;
    }

    private void MessagesScrollViewer_OnScrollChanged(object sender, ScrollChangedEventArgs e)
    {
        if (IsNearBottom())
        {
            ResetUnreadState();
        }

        //: 맨위 50px 이내면 이전 메시지 더 가져오기 (limit 누적)
        if (!_isLoadingOlder && IsNearTop() && e.VerticalChange < 0)
        {
            if (_mode == Mode.DirectMessage) _ = LoadOlderMessagesAsync();
            else if (_mode == Mode.Room) _ = LoadOlderRoomMessagesAsync();
        }
    }

    private bool IsNearTop()
    {
        return _messagesScrollViewer is not null && _messagesScrollViewer.VerticalOffset <= TopThreshold;
    }

    private async Task LoadInitialRoomHistoryAsync()
    {
        try
        {
            var resp = await _client.GetRoomHistoryAsync(
                _counterpartKey,
                sinceMillis: 0,
                limit: _settings.Current.HistoryPageSize,
                ct: CancellationToken.None);

            if (resp.Status != TalkRoomHistoryResponse.Types.Status.Ok)
            {
                System.Diagnostics.Debug.WriteLine($"[Conv] room history load status={resp.Status}");
                return;
            }

            foreach (var msg in resp.Talks)
            {
                if (!_seenRoomMsgIds.Add(msg.ServerTalkId)) continue;
                AddMessageRow(BuildRoomHistoryRow(msg));
            }
            ApplyReadStates(resp);
            ScrollToBottom();
            TryReportRead();
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[Conv] room history initial load failed: {ex.Message}");
        }
    }

    // ---------------- 읽음 처리 ----------------
    private readonly ReadReceiptTracker _reads = new();
    private long _lastReportedReadTs;

    /// <summary>히스토리 응답의 멤버별 읽음 위치 + "메시지를 보낸 사람은 그 메시지까지 읽음"을 반영.</summary>
    private void ApplyReadStates(TalkRoomHistoryResponse resp)
    {
        foreach (var s in resp.ReadStates) _reads.Apply(s.UserId, s.LastReadTs);
        foreach (var m in resp.Talks) _reads.Apply(m.FromUserId, m.ServerTs);
        RefreshReadLabels();
    }

    /// <summary>내가 보낸 메시지마다 안 읽은 인원/읽음 표시를 다시 계산(방 모드만).</summary>
    private void RefreshReadLabels()
    {
        if (_mode != Mode.Room || _roomMembers.Count == 0) return;
        foreach (var item in _messages)
        {
            var row = item.Row;
            if (!row.IsMine) continue;
            if (row.Status is not (TalkMessageStatus.Delivered or TalkMessageStatus.Sending))
            {
                row.UnreadCount = null;
                continue;
            }
            row.UnreadCount = _reads.UnreadCount(row.Status == TalkMessageStatus.Delivered ? row.ServerTs : 0, _currentUserId, _roomMembers);
        }
    }

    /// <summary>창이 활성이고 화면에 남이 보낸 새 메시지가 있으면 서버에 읽음 보고.</summary>
    private void TryReportRead()
    {
        if (_mode != Mode.Room || !IsActive || WindowState == WindowState.Minimized) return;
        var ts = ReadReceiptTracker.NextReportTs(_messages.Select(i => (i.Row.ServerTs, i.Row.Sender)), _currentUserId, _lastReportedReadTs);
        if (ts == 0) return;
        _lastReportedReadTs = ts;
        _reads.Apply(_currentUserId, ts);
        _ = _client.ReportRoomReadAsync(_counterpartKey, ts);
    }

    private void OnClientRoomReadUpdated(object? sender, TalkRoomReadUpdate e)
    {
        if (_mode != Mode.Room || !string.Equals(e.RoomId, _counterpartKey, StringComparison.OrdinalIgnoreCase)) return;
        Dispatcher.BeginInvoke(() =>
        {
            if (_reads.Apply(e.UserId, e.LastReadTs)) RefreshReadLabels();
        });
    }

    private TalkMessageRow BuildRoomHistoryRow(TalkRoomReceived msg)
    {
        var row = new TalkMessageRow(msg, isHistory: true)
        {
            Direction = msg.FromUserId == _currentUserId ? MessageDirection.Sent : MessageDirection.Received,
            Status = msg.FromUserId == _currentUserId ? TalkMessageStatus.Delivered : TalkMessageStatus.Received,
        };
        // 받은 파일 기록에도 실시간 알림 행과 같은 "더블클릭하여 다운로드" 안내를 붙인다.
        if (!row.IsMine && row.AttachmentUploadId is not null) row.Text = RoomFileText.WithDownloadHint(row.Text);
        return row;
    }

    private async Task LoadOlderRoomMessagesAsync()
    {
        if (_isLoadingOlder) return;
        _isLoadingOlder = true;
        try
        {
            var prevOffset = _messagesScrollViewer?.ExtentHeight ?? 0;
            _currentRoomHistoryLimit += _settings.Current.HistoryPageSize;
            var resp = await _client.GetRoomHistoryAsync(
                _counterpartKey,
                sinceMillis: 0,
                limit: _currentRoomHistoryLimit,
                ct: CancellationToken.None);
            if (resp.Status != TalkRoomHistoryResponse.Types.Status.Ok) return;

            var newRows = new List<TalkMessageRow>();
            foreach (var msg in resp.Talks)
            {
                if (!_seenRoomMsgIds.Add(msg.ServerTalkId)) continue;
                newRows.Add(BuildRoomHistoryRow(msg));
            }
            if (newRows.Count == 0) return;

            newRows.Sort((a, b) => a.ServerTs.CompareTo(b.ServerTs));
            for (int i = newRows.Count - 1; i >= 0; i--)
            {
                _messages.Insert(0, new TalkMessageItem(newRows[i]));
            }
            // 예전엔 0번만 다시 계산해 끼워 넣은 나머지 항목에 날짜 구분선이 빠졌다.
            RefreshDateSeparatorRange(0, newRows.Count);
            ApplyReadStates(resp);

            _ = Dispatcher.BeginInvoke(new Action(() =>
            {
                if (_messagesScrollViewer is not null)
                {
                    var delta = _messagesScrollViewer.ExtentHeight - prevOffset;
                    _messagesScrollViewer.ScrollToVerticalOffset(delta);
                }
            }), System.Windows.Threading.DispatcherPriority.Loaded);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[Conv] LoadOlderRoom fail: {ex.Message}");
        }
        finally
        {
            _isLoadingOlder = false;
        }
    }

    private async Task LoadOlderMessagesAsync()
    {
        if (_isLoadingOlder) return;
        _isLoadingOlder = true;
        try
        {
            var prevOffset = _messagesScrollViewer?.ExtentHeight ?? 0;
            _currentHistoryLimit += _settings.Current.HistoryPageSize;
            var history = await _client.GetHistoryAsync(0, _currentHistoryLimit, CancellationToken.None);

            // 기존에 본 ServerTalkId 제외하고 추가. 새로 가져온 것 중 오래된 순으로 맨위에 삽입.
            var newRows = new List<TalkMessageRow>();
            foreach (var msg in history.Talks)
            {
                if (!IsMessageForThisTalk(msg)) continue;
                if (!_seenServerMsgIds.Add(msg.ServerTalkId)) continue;
                newRows.Add(BuildHistoryRow(msg));
            }

            if (newRows.Count == 0) return;

            // 오래된 메시지부터 위로. ServerTs 오름차순.
            newRows.Sort((a, b) => a.ServerTs.CompareTo(b.ServerTs));

            for (int i = newRows.Count - 1; i >= 0; i--)
            {
                var row = newRows[i];
                var item = new TalkMessageItem(row);
                _messages.Insert(0, item);
            }
            RefreshDateSeparatorRange(0, newRows.Count);

            // 스크롤 위치 보존 — 새 메시지 추가된 만큼 아래로
            _ = Dispatcher.BeginInvoke(new Action(() =>
            {
                if (_messagesScrollViewer is not null)
                {
                    var delta = _messagesScrollViewer.ExtentHeight - prevOffset;
                    _messagesScrollViewer.ScrollToVerticalOffset(delta);
                }
            }), System.Windows.Threading.DispatcherPriority.Loaded);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[Conv] LoadOlder fail: {ex.Message}");
        }
        finally
        {
            _isLoadingOlder = false;
        }
    }

    private bool IsNearBottom()
    {
        if (_messagesScrollViewer is null)
        {
            return true;
        }

        return _messagesScrollViewer.ScrollableHeight - _messagesScrollViewer.VerticalOffset <= BottomThreshold;
    }

    private void UpdateNewMessageBadge()
    {
        if (_newMessageBadgeCount <= 0)
        {
            NewMessageBadgeButton.Visibility = Visibility.Collapsed;
            NewMessageBadgeText.Text = "↓ 새 메시지 0건";
            return;
        }

        NewMessageBadgeText.Text = $"↓ 새 메시지 {_newMessageBadgeCount}건";
        NewMessageBadgeButton.Visibility = Visibility.Visible;
    }

    private void ResetUnreadState()
    {
        _newMessageBadgeCount = 0;
        _unread = 0;
        UpdateNewMessageBadge();
    }

    private void NewMessageBadgeButton_OnClick(object sender, RoutedEventArgs e)
    {
        ScrollToBottom();
        ResetUnreadState();
    }

    // 그룹방 멤버 팝업에서 멤버 더블클릭 → 그 사용자와의 대화방을 열거나 만든다(2026-09-17,
    // 다른 진입점들과 동일하게 Room 경로로 통일 — 구식 Mode.DirectMessage 는 더 이상 안 씀).
    private void MemberListBox_OnMouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (MemberListBox.SelectedItem is not MemberDisplayItem item) return;
        var userId = item.UserId;
        if (string.IsNullOrEmpty(userId)) return;
        if (string.Equals(userId, _currentUserId, StringComparison.OrdinalIgnoreCase)) return;
        MemberPopup.IsOpen = false;
        if (Application.Current?.MainWindow is MainWindow mw)
        {
            _ = mw.OpenOrCreateRoomAsync(new List<string> { userId });
        }
    }

    /// <summary>멤버 목록(_roomMembers, 순수 UserId 문자열)으로부터 화면 표시용 아바타/이니셜을 계산한다.</summary>
    private void RebuildMemberDisplayItems()
    {
        _memberDisplayItems.Clear();
        // 나를 맨 위에 "(나)"로, 나머지는 아이디 순서(test7, test9, test10)로.
        var ordered = IdOrder.Sort(_roomMembers)
            .OrderBy(id => string.Equals(id, _currentUserId, StringComparison.OrdinalIgnoreCase) ? 0 : 1);
        foreach (var userId in ordered)
        {
            var isMe = string.Equals(userId, _currentUserId, StringComparison.OrdinalIgnoreCase);
            _memberDisplayItems.Add(new MemberDisplayItem
            {
                UserId = userId,
                DisplayName = isMe ? $"{userId} (나)" : userId,
                AvatarInitial = AvatarStyle.Initial(userId),
                AvatarColorHex = HashColor(userId),
            });
        }
    }

    private void HeaderMemberButton_OnClick(object sender, RoutedEventArgs e)
    {
        MemberPopup.IsOpen = !MemberPopup.IsOpen;
    }

    private void PopupInviteButton_OnClick(object sender, RoutedEventArgs e)
    {
        MemberPopup.IsOpen = false;
        _ = InviteMoreToRoomAsync();
    }


    private async void DrawerLeaveButton_OnClick(object sender, RoutedEventArgs e)
    {
        await LeaveRoomFlowAsync();
    }

    private void TitleCloseButton_OnClick(object sender, RoutedEventArgs e)
    {
        Close();
    }

    private void TitleMinimizeButton_OnClick(object sender, RoutedEventArgs e)
    {
        WindowState = WindowState.Minimized;
    }

    private void TitleMaximizeButton_OnClick(object sender, RoutedEventArgs e)
    {
        WindowState = WindowState == WindowState.Maximized
            ? WindowState.Normal
            : WindowState.Maximized;
    }

    private void UpdateMaximizeButtonState()
    {
        var isMaximized = WindowState == WindowState.Maximized;
        TitleMaximizeButton.ToolTip = isMaximized ? "복원" : "최대화";
        TitleMaximizeIconPath.Data = (Geometry)FindResource(isMaximized ? "IconRestore" : "IconMaximize");
    }

    /// <summary>
    /// 입력란에 포커스가 있는 상태에서 입력란 밖(대화창 아무 곳)을 클릭하면 포커스 해제.
    /// Keyboard.ClearFocus() 는 스레드 전체에서 포커스를 완전히 없애버려서, 그 뒤로는
    /// 이 창의 PreviewKeyDown(Esc 닫기 등)이 라우팅될 대상이 없어져 키 입력이 전혀 안 먹는
    /// 버그가 있었다. 대신 RootGrid 로 포커스를 옮겨서 라우팅 체인을 살려둔다.
    /// </summary>
    private void RootGrid_OnPreviewMouseDown(object sender, MouseButtonEventArgs e)
    {
        if (TalkInputBox.IsKeyboardFocusWithin && !TalkInputBox.IsMouseOver)
        {
            Keyboard.Focus(RootGrid);
        }
    }

    private void HeaderSearchButton_OnClick(object sender, RoutedEventArgs e)
    {
        var win = new TalkSearchWindow(_client, _currentUserId) { Owner = this };
        win.ShowDialog();
    }

    private void HeaderMenuButton_OnClick(object sender, RoutedEventArgs e)
    {
        if (_mode != Mode.Room)
        {
            AppDialog.Info(this, "이 메뉴는 대화방에서만 사용할 수 있습니다.", "알림");
            return;
        }

        // ContextMenu(Popup) 는 Window 의 흰색 Foreground 를 상속하지 않고 밝은 기본 배경을
        // 쓰기 때문에 명시적으로 검은 글자로 고정해야 한다(안 그러면 안 보이는 버그).
        var menu = new ContextMenu { Foreground = Brushes.Black };
        var invite = new MenuItem { Header = "초대하기" };
        invite.Click += async (_, _) => await InviteMoreToRoomAsync();
        var rename = new MenuItem { Header = "이름 설정" };
        rename.Click += async (_, _) => await RenameRoomFlowAsync();
        var leave = new MenuItem { Header = "대화방 나가기" };
        leave.Click += async (_, _) => await LeaveRoomFlowAsync();
        menu.Items.Add(invite);
        menu.Items.Add(rename);
        menu.Items.Add(leave);
        menu.PlacementTarget = HeaderMenuButton;
        menu.IsOpen = true;
    }

    /// <summary>
    /// 방 안 누구나 가능(방장 없음). 이미 있는 사람은 목록에서 아예 빼서 보여주므로(2026-09-17),
    /// 여기서 넘어오는 targets 는 전부 신규 대상이다 — 서버의 already-member 체크는 그래도
    /// 남겨둔다(동시에 두 명이 같은 사람을 초대하는 등의 경합 상황 대비 안전장치).
    ///
    /// 2026-09-17 사용자 요구사항: 지금 방이 1:1(2인)이면 기존 방은 그대로 둔 채 새 그룹방을
    /// 만들어 그 방을 띄운다 — 이미 1:n(3인 이상) 방이면 그냥 이 방에 인원만 추가한다.
    /// </summary>
    private async Task InviteMoreToRoomAsync()
    {
        if (_orgRoots is null)
        {
            AppDialog.Info(this, "조직도를 아직 불러오지 못했습니다.", "오소리 메신저");
            return;
        }
        var picker = new MessageRecipientPicker(_orgRoots, Array.Empty<string>(), excludeUserIds: _roomMembers) { Owner = this };
        // 쪽지용 기본 문구("수신자 선택") 대신 초대 용도에 맞는 문구.
        picker.SetPurpose("대화 상대 초대", "초대", "초대할 사람을 골라 주세요");
        if (picker.ShowDialog() != true) return;
        var targets = picker.SelectedUserIds
            .Where(id => !string.Equals(id, _currentUserId, StringComparison.OrdinalIgnoreCase))
            .ToList();
        if (targets.Count == 0) return;

        if (_roomMembers.Count == 2 && Application.Current?.MainWindow is MainWindow mw)
        {
            var newRoomMembers = _roomMembers.Concat(targets).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
            await mw.OpenOrCreateRoomAsync(newRoomMembers);
            return;
        }

        TalkRoomInviteAck ack;
        try
        {
            ack = await _client.InviteToRoomAsync(_counterpartKey, targets);
        }
        catch (Exception ex)
        {
            AppDialog.Info(this, $"초대에 실패했습니다: {ex.Message}", "오소리 메신저");
            return;
        }

        if (ack.Result != TalkRoomInviteAck.Types.Result.Ok && ack.Result != TalkRoomInviteAck.Types.Result.NoValidTargets)
        {
            AppDialog.Info(this, $"초대하지 못했습니다.\n{ResultMessages.RoomInvite(ack.Result)}", "오소리 메신저");
            return;
        }
        if (ack.AlreadyMemberUserIds.Count > 0)
        {
            AppDialog.Info(
                this,
                $"이미 이 대화방에 있어서 다시 초대하지 않았습니다: {string.Join(", ", ack.AlreadyMemberUserIds)}",
                "오소리 메신저");
        }
        // 멤버 목록/시스템 알림은 서버가 쏴주는 TalkRoomMemberEvent(OnClientRoomMemberChanged)로 반영된다.
    }

    private async Task RenameRoomFlowAsync()
    {
        var newName = PromptForText(this, "대화방 이름 설정", "새 이름을 입력하세요.", HeaderTitleText.Text);
        if (string.IsNullOrWhiteSpace(newName)) return;
        newName = newName.Trim();

        TalkRoomRenameAck ack;
        try
        {
            ack = await _client.RenameRoomAsync(_counterpartKey, newName);
        }
        catch (Exception ex)
        {
            AppDialog.Info(this, $"이름 변경에 실패했습니다: {ex.Message}", "오소리 메신저");
            return;
        }
        if (ack.Result != TalkRoomRenameAck.Types.Result.Ok)
        {
            AppDialog.Info(this, $"이름을 바꾸지 못했습니다.\n{ResultMessages.RoomRename(ack.Result)}", "오소리 메신저");
            return;
        }
        // 화면 반영은 서버가 쏴주는 TalkRoomRenamed(OnClientRoomRenamed)로 본인 창도 동일하게 처리된다.
    }

    /// <summary>나가기 — 본인만 방에서 제외된다. 헤더 메뉴 "대화방 나가기"와 멤버 서랍 나가기 버튼이 공유.</summary>
    private async Task LeaveRoomFlowAsync()
    {
        if (_mode != Mode.Room) return;
        if (!AppDialog.Confirm(
                this,
                "이 대화방에서 나가시겠습니까?",
                "대화방 나가기",
                yes: "나가기",
                no: "취소",
                detail: "나가면 이 방의 새 메시지를 받지 않습니다. 대화 기록은 서버에 보존됩니다."))
        {
            return;
        }

        try
        {
            var ack = await _client.LeaveRoomAsync(_counterpartKey);
            if (ack.Result != TalkRoomLeaveAck.Types.Result.Ok)
            {
                AppDialog.Info(this, $"나가지 못했습니다.\n{ResultMessages.RoomLeave(ack.Result)}", "오소리 메신저");
                return;
            }
        }
        catch (Exception ex)
        {
            AppDialog.Info(this, $"나가기에 실패했습니다: {ex.Message}", "오소리 메신저");
            return;
        }

        RoomLeft?.Invoke(_counterpartKey);
        Close();
    }

    private void OnClientRoomMemberChanged(object? sender, TalkRoomMemberEvent e)
    {
        if (!string.Equals(e.RoomId, _counterpartKey, StringComparison.OrdinalIgnoreCase)) return;
        Dispatcher.BeginInvoke(() =>
        {
            _roomMembers.Clear();
            foreach (var memberId in e.MemberIds)
            {
                _roomMembers.Add(memberId);
            }
            RebuildMemberDisplayItems();
            DrawerHeaderText.Text = $"멤버 ({e.MemberIds.Count})";
            HeaderMetaText.Text = $"멤버 {e.MemberIds.Count}명";
            UpdateRoomHeaderAppearance();

            // 2026-09-17 — 방 이름이 수동으로 바뀐 적 없으면 서버가 인원 변동 때마다 자동으로
            // 재명명한다. 다만 서버는 "방장 기준"으로 딱 한 번만 계산해서 모든 멤버에게 똑같은
            // 문자열을 내려주므로, 방장이 아닌 멤버는 상대방 대신 자기 이름을 보게 되는 버그가
            // 있었다 — 자동 명명 방이면 멤버 목록에서 나를 뺀 나머지로 직접 재계산한다.
            var resolvedName = MainWindow.ResolveRoomDisplayName(e.RoomName, e.NameIsCustom, e.MemberIds, _currentUserId, e.RoomId);
            if (!string.IsNullOrWhiteSpace(resolvedName))
            {
                Title = $"{resolvedName} - 오소리 메신저";
                HeaderTitleText.Text = resolvedName;
                UpdateRoomHeaderAppearance();
            }

            if (e.EventType == TalkRoomMemberEvent.Types.EventType.MembersAdded)
            {
                App.ShowInAppToast("대화방 초대", $"{string.Join(", ", e.AffectedUserIds)} 님이 초대되었습니다", App.ToastLevel.Info);
            }
            else if (e.EventType == TalkRoomMemberEvent.Types.EventType.MemberLeft)
            {
                // 1:1(2인) 방에서 상대가 나간 경우엔 "나갔습니다" 알림을 띄우지 않는다(요구사항).
                var wasTwoPersonRoom = e.MemberIds.Count + e.AffectedUserIds.Count == 2;
                if (!wasTwoPersonRoom)
                {
                    App.ShowInAppToast("대화방 퇴장", $"{e.ActorUserId} 님이 나갔습니다", App.ToastLevel.Info);
                }
            }
        });
    }

    private void OnClientRoomRenamed(object? sender, TalkRoomRenamed e)
    {
        if (!string.Equals(e.RoomId, _counterpartKey, StringComparison.OrdinalIgnoreCase)) return;
        Dispatcher.BeginInvoke(() =>
        {
            Title = $"{e.NewName} - 오소리 메신저";
            HeaderTitleText.Text = e.NewName;
            UpdateRoomHeaderAppearance();
        });
    }

    /// <summary>간단한 텍스트 입력 다이얼로그 (MainWindow.PromptForText 와 동일한 패턴).</summary>
    private static string? PromptForText(Window owner, string title, string prompt, string initialValue = "")
    {
        // 코드로 만든 흰 시스템 타이틀바 창 대신 다른 창과 같은 커스텀 타이틀바의 공통 입력 창을 쓴다.
        return TextInputDialog.Ask(owner, title, prompt, initialValue);
    }

    private async void AttachButton_OnClick(object sender, RoutedEventArgs e)
    {
        // 방 모드는 방 ID 로 올린다(그룹·1:1 모두 방 멤버가 받는다). 구식 1:1 모드만 상대 ID.
        var roomId = _mode == Mode.Room ? _counterpartKey : "";
        var target = roomId.Length > 0 ? "" : ResolveFileTargetUserId();
        if (roomId.Length == 0 && target is null)
        {
            AppDialog.Info(this, "이 대화에서는 파일을 첨부할 수 없습니다.", "알림");
            return;
        }

        var dialog = new OpenFileDialog
        {
            Title = "파일 첨부",
            Filter = "모든 파일|*.*",
            Multiselect = false,
        };

        if (dialog.ShowDialog(this) != true)
        {
            return;
        }

        await UploadAndSendAsync(dialog.FileName, target ?? "", roomId);
    }

    /// <summary>
    /// 파일을 받을 상대 사용자 ID. 구식 1:1 모드는 _counterpartKey 가 곧 상대 ID,
    /// 방 모드는 멤버가 2명일 때만 나머지 한 명(그룹 방은 아직 미지원 → null).
    /// </summary>
    private string? ResolveFileTargetUserId()
    {
        if (_mode == Mode.DirectMessage)
            return string.IsNullOrEmpty(_counterpartKey) ? null : _counterpartKey;
        return RoomPeers.TryGetDirectCounterpart(_roomMembers, _currentUserId);
    }

    private async Task UploadAndSendAsync(string filePath, string targetUserId, string roomId = "")
    {
        var fileName = System.IO.Path.GetFileName(filePath);
        var fileInfo = new System.IO.FileInfo(filePath);
        var head = RoomFileText.Head(fileName, fileInfo.Length);
        var row = new TalkMessageRow(Guid.NewGuid().ToString("N"), _currentUserId, $"{head}\n업로드 중...")
        {
            ServerTs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        };

        AddMessageRow(row);
        ScrollToBottom();
        // 파일을 읽고 보내기 전에 화면을 한 번 그려서, 고르자마자 "업로드 중..." 말풍선이 보이게 한다.
        await Dispatcher.InvokeAsync(() => { }, System.Windows.Threading.DispatcherPriority.Background);

        var total = Math.Max(1, fileInfo.Length);
        var progress = new Progress<long>(sent =>
        {
            if (row.Status != TalkMessageStatus.Sending) return;
            var pct = (int)Math.Min(99, sent * 100 / total);
            row.Text = $"{head}\n업로드 중... {pct}%";
        });

        try
        {
            var ack = await _client.UploadFileAsync(filePath, targetUserId, ct: CancellationToken.None, roomId: roomId, progress: progress);
            if (ack.Result == FileUploadCompleteAck.Types.Result.Ok)
            {
                row.Status = TalkMessageStatus.Delivered;
                row.Text = $"{head}\n전송 완료";
                row.ServerTalkId = ack.UploadId;
                row.AttachmentUploadId = ack.UploadId;
                if (roomId.Length > 0) _seenRoomMsgIds.Add(ack.UploadId);
                RefreshReadLabels();
                // 대화 목록 미리보기도 마지막 파일 메시지로 갱신("[파일] 이름").
                MessageSentLocally?.Invoke(row.Text, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            }
            else
            {
                row.Text = $"{head}\n전송 실패(서버에서 완료 처리 못함)";
                row.Status = TalkMessageStatus.Failed;
            }
        }
        catch (Exception ex)
        {
            row.Text = $"{head}\n전송 실패: {ex.Message}";
            row.Status = TalkMessageStatus.Failed;
        }
    }

    private void OnClientFileAvailable(object? sender, FileAvailableNotification e)
    {
        Dispatcher.BeginInvoke(() =>
        {
            // 멤버 목록(_roomMembers)은 UI 스레드에서만 바뀌므로 판별도 UI 스레드에서.
            if (!FileOfferMatcher.Matches(e.RoomId, e.FromUserId, _mode == Mode.Room ? _counterpartKey : null, ResolveFileTargetUserId()))
            {
                return;
            }

            // 방 파일은 서버가 같은 ID 로 대화 기록 메시지도 보내므로 방 메시지 ID 집합으로 중복을 거른다.
            var seen = string.IsNullOrEmpty(e.RoomId) ? _seenServerMsgIds : _seenRoomMsgIds;
            if (!seen.Add(e.UploadId))
            {
                return;
            }

            var msg = new TalkReceived
            {
                ServerTalkId = e.UploadId,
                FromUserId = e.FromUserId,
                ToUserId = _currentUserId,
                // 안내 문구는 둘째 줄에 따로 — 한 줄에 붙이면 괄호 중간에서 줄이 바뀌었다.
                Text = RoomFileText.WithDownloadHint(RoomFileText.Head(e.Filename, e.TotalSize)),
                ServerTs = e.ServerTs,
            };

            var row = new TalkMessageRow(msg, isHistory: false)
            {
                Direction = MessageDirection.Received,
                Status = TalkMessageStatus.Received,
                AttachmentUploadId = e.UploadId,
            };

            HandleIncomingRow(row);
            // 다른 앱을 쓰는 중(이 창 비활성)에 모달 질문을 띄우면 포커스를 빼앗는다 → 돌아올 때 묻는다.
            // 그 사이에는 말풍선의 "더블클릭하여 다운로드"로도 받을 수 있다.
            if (IsActive) _ = HandleFileOfferAsync(e, row);
            else _pendingFileOffers.Enqueue((e, row));
        });
    }

    private async Task HandleFileOfferAsync(FileAvailableNotification e, TalkMessageRow row)
    {
        // 미뤄 둔 사이 사용자가 말풍선을 더블클릭해 이미 받았으면 다시 묻지 않는다.
        if (!string.IsNullOrEmpty(row.SavedPath)) return;
        var choice = AppDialog.Confirm(
            this,
            $"{e.FromUserId} 님이 파일을 보냈습니다. 지금 받을까요?",
            "파일 수신",
            yes: "받기",
            no: "나중에",
            detail: $"파일명: {e.Filename}\n크기: {TalkListFormat.FileSize(e.TotalSize)}");

        if (!choice)
        {
            return;
        }

        await DownloadFileToAsync(e, row, originalText: null);
    }

    /// <summary>
    /// 저장 위치를 묻고 내려받는다. 진행률·완료·실패는 말풍선 둘째 줄에 보이고, 로컬 경로는 말풍선에 쓰지 않는다
    /// (툴팁과 "더블클릭하면 폴더 열기"로만). 탐색기는 사용자가 저장된 행을 더블클릭할 때만 연다.
    /// </summary>
    private async Task DownloadFileToAsync(FileAvailableNotification e, TalkMessageRow row, string? originalText)
    {
        var save = new SaveFileDialog
        {
            Title = "파일 저장",
            FileName = e.Filename,
            Filter = "모든 파일|*.*",
        };

        if (save.ShowDialog(this) != true)
        {
            return;
        }

        var knownSize = e.TotalSize;
        var progress = new Progress<MessengerClient.DownloadProgress>(p =>
        {
            if (p.TotalBytes > 0) knownSize = p.TotalBytes;
            var pct = p.TotalBytes > 0 ? (int)Math.Round(p.Fraction * 100) : 0;
            row.Text = RoomFileText.Downloading(e.Filename, knownSize, pct);
        });

        try
        {
            row.Text = RoomFileText.Downloading(e.Filename, knownSize, 0);
            var file = await _client.DownloadFileAsync(e.UploadId, progress, CancellationToken.None);
            await System.IO.File.WriteAllBytesAsync(save.FileName, file.Bytes);
            row.SavedPath = save.FileName;
            row.Text = RoomFileText.Saved(e.Filename, file.Bytes.Length);
            if (IsImageFilename(e.Filename))
            {
                try
                {
                    var preview = new ImagePreviewWindow(save.FileName, e.Filename, file.Bytes.Length) { Owner = this };
                    preview.Show();
                }
                catch { /* 미리보기 실패해도 저장은 성공 */ }
            }
        }
        catch (Exception ex)
        {
            row.Text = RoomFileText.DownloadFailed(e.Filename, knownSize, ex.Message);
        }
    }
    private static bool IsImageFilename(string filename)
    {
        if (string.IsNullOrEmpty(filename)) return false;
        var ext = System.IO.Path.GetExtension(filename).ToLowerInvariant();
        return ext is ".jpg" or ".jpeg" or ".png" or ".gif" or ".webp" or ".bmp";
    }

    private void AttachmentCancelButton_OnClick(object sender, RoutedEventArgs e)
    {
        AttachmentPreview.Visibility = Visibility.Collapsed;
    }

    private static T? FindDescendant<T>(DependencyObject root) where T : DependencyObject
    {
        var childCount = VisualTreeHelper.GetChildrenCount(root);
        for (var i = 0; i < childCount; i++)
        {
            var child = VisualTreeHelper.GetChild(root, i);
            if (child is T typedChild)
            {
                return typedChild;
            }

            var descendant = FindDescendant<T>(child);
            if (descendant is not null)
            {
                return descendant;
            }
        }

        return null;
    }
}

internal sealed class TalkMessageItem : INotifyPropertyChanged
{
    private string _dateSeparatorLabel = string.Empty;
    private bool _showDateSeparator;

    public TalkMessageItem(TalkMessageRow row)
    {
        Row = row ?? throw new ArgumentNullException(nameof(row));
        // 문구·읽음·상태가 바뀌면 낭독 이름도 바뀐다.
        Row.PropertyChanged += (_, _) => OnPropertyChanged(nameof(AccessibleName));
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public TalkMessageRow Row { get; }

    /// <summary>화면 낭독기용 이름(클래스 이름 대신 보낸 사람·내용·시각·읽음).</summary>
    public string AccessibleName => TalkMessageText.AccessibleName(
        Row.Sender, Row.IsMine, Row.Text, Row.TimestampText, Row.ReadLabel, Row.Status);

    public override string ToString() => AccessibleName;

    private bool _showTime = true;

    /// <summary>같은 사람이 같은 분에 이어 보낸 메시지면 마지막 것에만 시각을 보인다.</summary>
    public bool ShowTime
    {
        get => _showTime;
        set
        {
            if (_showTime == value) return;
            _showTime = value;
            OnPropertyChanged();
        }
    }

    private bool _isContinuation;

    /// <summary>바로 앞 메시지와 같은 사람이 3분 안에 이어 보낸 메시지(이름·아바타 생략).</summary>
    public bool IsContinuation
    {
        get => _isContinuation;
        set
        {
            if (_isContinuation == value) return;
            _isContinuation = value;
            OnPropertyChanged();
        }
    }

    public bool ShowDateSeparator
    {
        get => _showDateSeparator;
        set
        {
            if (_showDateSeparator == value)
            {
                return;
            }

            _showDateSeparator = value;
            OnPropertyChanged();
        }
    }

    public string DateSeparatorLabel
    {
        get => _dateSeparatorLabel;
        set
        {
            if (_dateSeparatorLabel == value)
            {
                return;
            }

            _dateSeparatorLabel = value;
            OnPropertyChanged();
        }
    }

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }
}

/// <summary>멤버 팝업(카카오톡 스타일) 한 행의 표시용 모델 — 순수 UserId 목록(_roomMembers)에서 파생.</summary>
internal sealed class MemberDisplayItem
{
    public string UserId { get; init; } = "";
    /// <summary>목록 표시·낭독 이름(나는 "아이디 (나)").</summary>
    public string DisplayName { get; init; } = "";
    public string AvatarInitial { get; init; } = "";
    public Color AvatarColorHex { get; init; }
    public override string ToString() => DisplayName;
}

using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Osori.Proto.V1;
using OsoriClient.Core;
using OsoriClient.Wpf.Helpers;

namespace OsoriClient.Wpf;

/// <summary>
/// MainWindow — 로그인 후 상시 떠있는 허브.
/// 대화/조직도/설정 탭으로 스위칭. 개별 대화는 TalkWindow 팝업(Phase D).
///
/// 이번 단계(C-6): 프레임 + 프로필 헤더 + 탭 레일 + 빈 탭 컨텐츠 + 상태 바 뼈대만.
/// 탭 콘텐츠는 C-7/C-8/C-9 에서 채움. Banner/Reconnect 연결은 C-10.
/// </summary>
public partial class MainWindow : Window
{
    private readonly MessengerClient _client;
    private readonly string _currentUserId;
    private readonly SettingsStore _settings = new();

    /// <summary>
    /// 대화 탭 리스트 소스. CounterpartId → Row 매핑.
    /// </summary>
    private readonly ObservableCollection<TalkRow> _conversations = new();
    private readonly Dictionary<string, TalkRow> _conversationByKey = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>열려있는 대화 창 (중복 오픈 방지).</summary>
    private readonly Dictionary<string, TalkWindow> _openTalks = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>
    /// Avatar 색상 팔레트 — userId SHA1 mod 8.
    /// (디자인 스펙 03_conversation_design.md 와 동일)
    /// </summary>
    private static readonly string[] AvatarPalette =
    {
        "#E57373", "#BA68C8", "#7986CB", "#4FC3F7",
        "#4DB6AC", "#81C784", "#FFB74D", "#A1887F",
    };

    public MainWindow(MessengerClient client, string currentUserId)
    {
        InitializeComponent();
        WindowCornerHelper.ApplyRoundCorners(this);
        _client = client ?? throw new ArgumentNullException(nameof(client));
        _currentUserId = currentUserId ?? throw new ArgumentNullException(nameof(currentUserId));

        Title = $"오소리 메신저 — {_currentUserId}";
        TalkListBox.ItemsSource = _conversations;
        ApplyProfileHeader();
        LoadSettingsUi();
        // 초기 글꼴 크기 적용 (저장된 값, 범위 가드)
        var initialFs = _settings.Current.FontSize;
        if (initialFs >= 8 && initialFs <= 48) FontSize = initialFs;
        //: 로그인 시 디폴트 탭은 항상 조직도 (XAML 의 TabOrg IsChecked=True).
        // LastSelectedTab 복원은 의도적으로 호출하지 않음 — 사용자 명시 요구사항.
        TabOrg.IsChecked = true;
        RestoreWindowBounds();
        UpdateMaximizeButtonState();
        WireClientEvents();
        KeyDown += MainWindow_OnKeyDown;
        Loaded += MainWindow_OnLoaded;
        Closed += MainWindow_OnClosed;
        StateChanged += MainWindow_OnStateChanged;
    }

    private async void MainWindow_OnLoaded(object sender, RoutedEventArgs e)
    {
        var s = _settings.Current;
        if (s.AutoLoadOrgOnLogin)
        {
            await ReloadOrgTreeAsync();
            // 조직도에서 내 직함 찾아 프로필 헤더 갱신
            UpdateProfileTitleFromOrg();
        }
        // 2026-09-17 사용자 요구사항 — 옛날 1:1 대화를 전부 대화방(Room)으로 이전한 뒤부터는
        // 이전 세션에서 이미 멤버였던 방도 재로그인 시 사이드바에 보여야 한다. 새로 초대된 방은
        // 실시간 이벤트(OnClientRoomMemberChangedGlobal)로 반영되지만, 그건 "이 세션 동안"만
        // 커버하므로 로그인 직후 한 번은 서버에 내 방 목록 전체를 물어봐야 한다.
        await PreloadRoomListAsync();

        // 로그인 직후 받은쪽지함을 미리 당겨와 미열독 배지를 채운다. 이게 없으면
        // _messageUnread 가 실시간 푸시(OnClientMessagePushed)로만 증가하는 순수
        // 인메모리 카운터라서, 로그인 전에 이미 와있던 미열독 쪽지는 배지에 전혀
        // 반영되지 않고 새 쪽지가 하나 도착해야만 그제서야 배지가 나타나는 버그가 있었다.
        await ReloadMessageBoxAsync();

        // D7 자동 업데이트 — 실패해도 로그인 플로우를 막으면 안 되므로 완전히 조용히 무시.
        _ = CheckForUpdateAsync();
    }

    /// <summary>
    /// D7 — 서버 /client/version 을 조회해 새 버전이 있으면 사용자에게 물어보고,
    /// 동의 시 설치 파일을 받아 자동 실행(/SILENT)한 뒤 앱을 종료한다.
    /// 네트워크 오류/구버전 서버(엔드포인트 없음) 등은 전부 조용히 무시 — 필수 기능이 아님.
    /// </summary>
    private async System.Threading.Tasks.Task CheckForUpdateAsync()
    {
        try
        {
            var s = _settings.Current;
            var scheme = s.UseTls ? "https" : "http";
            var baseUrl = $"{scheme}://{s.ServerHost}:{s.ServerPort}";

            using var http = new System.Net.Http.HttpClient { Timeout = TimeSpan.FromSeconds(5) };
            var json = await http.GetStringAsync($"{baseUrl}/client/version");
            using var doc = System.Text.Json.JsonDocument.Parse(json);
            var latestStr = doc.RootElement.GetProperty("latestVersion").GetString();
            var downloadUrl = doc.RootElement.GetProperty("downloadUrl").GetString();
            if (string.IsNullOrEmpty(latestStr) || string.IsNullOrEmpty(downloadUrl)) return;

            var current = System.Reflection.Assembly.GetExecutingAssembly().GetName().Version;
            if (current is null || !Version.TryParse(latestStr, out var latest)) return;
            // AssemblyVersion 은 4자리(0.1.0.0), csproj Version 은 3자리 — 앞 3자리만 비교.
            var currentTrimmed = new Version(current.Major, current.Minor, current.Build < 0 ? 0 : current.Build);
            if (latest <= currentTrimmed) return;

            var answer = AppDialog.Confirm(
                this,
                $"새 버전 {latest}이 있습니다 (현재 {currentTrimmed}).\n지금 업데이트할까요?",
                "업데이트 확인",
                yes: "지금 업데이트",
                no: "나중에",
                detail: "업데이트하면 설치 파일을 받아 자동 설치한 뒤 앱을 다시 시작합니다.");
            if (!answer) return;

            var tempPath = System.IO.Path.Combine(System.IO.Path.GetTempPath(), $"OsoriMessenger-Update-{latest}.exe");
            var bytes = await http.GetByteArrayAsync($"{baseUrl}{downloadUrl}");
            await System.IO.File.WriteAllBytesAsync(tempPath, bytes);

            // Inno Setup 설치본은 같은 AppId 를 유지하므로 /SILENT 로 in-place 업그레이드된다.
            // /CLOSEAPPLICATIONS 로 실행 중인 OsoriClient.Wpf.exe 를 설치 전 자동 종료.
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = tempPath,
                Arguments = "/SILENT /NORESTART /CLOSEAPPLICATIONS",
                UseShellExecute = true,
            });
            Application.Current.Shutdown();
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine("[CheckForUpdate] " + ex.Message);
        }
    }

    /// <summary>
    /// 로그인 직후 내가 멤버인 대화방 전체를 사이드바에 미리 채운다(2026-09-17).
    /// 2026-09-17 버그 수정: 마지막 대화 미리보기가 재접속 시 안 보이던 문제 — 서버가 이제
    /// 방마다 마지막 메시지도 같이 내려주므로 그걸 채워넣고, 오래된 순으로 GetOrCreateTalkRow
    /// (내부에서 Insert(0, ...) 로 맨 위에 꽂음)를 호출해 최근 대화가 자연스럽게 맨 위로 오게 한다.
    /// </summary>
    private async System.Threading.Tasks.Task PreloadRoomListAsync()
    {
        try
        {
            var resp = await _client.ListRoomsAsync(System.Threading.CancellationToken.None);
            if (resp.Result != TalkRoomListResponse.Types.Result.Ok) return;
            foreach (var item in resp.Rooms.OrderBy(r => r.LastMessageTs))
            {
                var name = ResolveRoomDisplayName(item.Name, item.NameIsCustom, item.MemberIds, _currentUserId, item.RoomId);
                var row = GetOrCreateTalkRow(item.RoomId, name, isRoom: true);
                row.DirectPeerId = RoomPeers.TryGetDirectCounterpart(item.MemberIds, _currentUserId);
                if (item.LastMessageTs > 0)
                {
                    row.LastMessagePreview = item.LastMessageText ?? string.Empty;
                    row.LastMessageTs = item.LastMessageTs;
                }
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine("[PreloadRoomList] " + ex.Message);
        }
    }

    private void UpdateProfileTitleFromOrg()
    {
        if (_orgRoots is null) return;
        // 재귀 탐색
        OrgTreeNode? Find(System.Collections.Generic.IEnumerable<OrgTreeNode> nodes)
        {
            foreach (var n in nodes)
            {
                if (n.Kind == OrgTreeNodeKind.Member
                    && string.Equals(n.UserId, _currentUserId, StringComparison.OrdinalIgnoreCase))
                {
                    return n;
                }
                var inChild = Find(n.Children);
                if (inChild != null) return inChild;
            }
            return null;
        }
        var me = Find(_orgRoots);
        if (me != null && !string.IsNullOrEmpty(me.DisplayName))
        {
            ProfileNameText.Text = me.DisplayName;
        }
    }

    private void WireClientEvents()
    {
        _client.Reconnected += OnClientReconnected;
        _client.ReconnectFailed += OnClientReconnectFailed;
        _client.Reconnecting += OnClientReconnecting;
        _client.SessionSuperseded += OnClientSessionSuperseded;
        _client.TalkReceived += OnClientMessageReceived;
        _client.PresenceChanged += OnClientPresenceChanged;
        _client.MessagePushed += OnClientMessagePushed;
        _client.RoomMemberChanged += OnClientRoomMemberChangedGlobal;
        _client.RoomRenamed += OnClientRoomRenamedGlobal;
        _client.TalkRoomPushed += OnClientRoomMessagePushedGlobal;
        MessageListBox.ItemsSource = _messageRows;
    }

    /// <summary>
    /// 대화창을 열어둔 게 없어도(다른 사람이 나를 새로 초대해서 처음 알게 된 방 포함) 사이드바
    /// 목록에 반영되도록 전역으로 구독한다. 이미 열려있는 TalkWindow 는 자기 창 안에서 별도로
    /// (OnClientRoomMemberChanged) 멤버 목록/토스트를 처리하므로 여기서는 목록 행만 관리한다.
    /// </summary>
    private void OnClientRoomMemberChangedGlobal(object? sender, TalkRoomMemberEvent e)
    {
        Dispatcher.BeginInvoke(() =>
        {
            var name = ResolveRoomDisplayName(e.RoomName, e.NameIsCustom, e.MemberIds, _currentUserId, e.RoomId);

            if (e.EventType == TalkRoomMemberEvent.Types.EventType.MembersAdded
                && e.AffectedUserIds.Contains(_currentUserId, StringComparer.OrdinalIgnoreCase)
                && !_conversationByKey.ContainsKey(e.RoomId))
            {
                var newRow = GetOrCreateTalkRow(e.RoomId, name, isRoom: true);
                newRow.DirectPeerId = RoomPeers.TryGetDirectCounterpart(e.MemberIds, _currentUserId);
                App.ShowInAppToast("새 대화방", $"'{name}' 대화방에 초대되었습니다", App.ToastLevel.Info);
                return;
            }

            // 2026-09-17 — 이미 사이드바에 있는 방인데 인원이 바뀌어서 자동 명명 이름이
            // 갱신됐을 수 있다(수동 개명한 방은 서버가 이름을 그대로 유지하므로 무해하게 같은
            // 값을 다시 쓸 뿐). 해당 방 창을 안 열어놓고 있어도 사이드바 표시가 갱신되어야 한다.
            if (_conversationByKey.TryGetValue(e.RoomId, out var row))
            {
                row.DisplayName = name;
                row.DirectPeerId = RoomPeers.TryGetDirectCounterpart(e.MemberIds, _currentUserId);
            }

            // 2026-09-17 — 3명 이상 방에서 누가 나갔는데 그 방 창을 안 열어놓고 있으면 지금까지
            // 아무 알림도 못 받았다(TalkWindow 가 열려있을 때만 토스트를 띄우는 로컬 핸들러만
            // 있었음). 창이 열려있으면 그쪽에서 이미 토스트를 띄우므로 여기서는 중복 방지.
            if (e.EventType == TalkRoomMemberEvent.Types.EventType.MemberLeft
                && !_openTalks.ContainsKey(e.RoomId))
            {
                var wasTwoPersonRoom = e.MemberIds.Count + e.AffectedUserIds.Count == 2;
                if (!wasTwoPersonRoom)
                {
                    App.ShowInAppToast("대화방 퇴장", $"{e.ActorUserId} 님이 나갔습니다", App.ToastLevel.Info);
                }
            }
        });
    }

    private void OnClientRoomRenamedGlobal(object? sender, TalkRoomRenamed e)
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (_conversationByKey.TryGetValue(e.RoomId, out var row))
            {
                row.DisplayName = e.NewName;
            }
        });
    }

    private void OnClientMessageReceived(object? sender, TalkReceivedEventArgs e)
    {
        Dispatcher.BeginInvoke(() =>
        {
            // 상대 결정: 내가 보낸 거면 ToUserId, 받은 거면 FromUserId
            var counterpart = string.Equals(e.Message.FromUserId, _currentUserId, StringComparison.OrdinalIgnoreCase)
                ? e.Message.ToUserId
                : e.Message.FromUserId;
            if (string.IsNullOrEmpty(counterpart)) return;

            var row = GetOrCreateTalkRow(counterpart, counterpart, isRoom: false);
            row.LastMessagePreview = e.Message.Text ?? string.Empty;
            row.LastMessageTs = e.Message.ServerTs;

            // 해당 대화창이 열려있고 Active 면 unread 증가 안 함
            var isOpenAndActive = _openTalks.TryGetValue(counterpart, out var win)
                                   && win.IsActive;
            var isFromOther = !string.Equals(e.Message.FromUserId, _currentUserId, StringComparison.OrdinalIgnoreCase);
            if (!isOpenAndActive && isFromOther)
            {
                row.UnreadCount++; // 탭 배지·트레이 합계는 RefreshTalkUnreadTotal 이 갱신

                // NotifyDirectMessages 토글로 게이팅
                if (_settings.Current.NotifyDirectMessages)
                {
                    if (!IsActive)
                    {
                        var preview = e.Message.Text ?? string.Empty;
                        if (preview.Length > 80) preview = preview.Substring(0, 80) + "…";
                        App.ShowToast(counterpart, preview);
                    }
                    if (_settings.Current.PlaySoundOnMessage)
                    {
                        try { System.Media.SystemSounds.Asterisk.Play(); } catch { }
                    }
                }
            }
            BringToTop(row);
        });
    }

    /// <summary>
    /// 2026-09-17 버그 수정 — 지금까지 방 메시지 수신(TalkRoomPushed)은 그 방의 TalkWindow 가
    /// 열려있을 때만(TalkWindow 자체 구독) 처리됐다. 창을 안 열어놓고 있으면 사이드바 미리보기/
    /// 순서/미읽음 배지가 전혀 갱신되지 않아서, 초대만 받고 창을 한 번도 안 연 사용자(예:
    /// test10)에게는 "대화가 아예 안 오는 것처럼" 보였다. 로그인 직후 PreloadRoomListAsync 로
    /// 이미 사이드바에 방이 채워져 있으므로 여기서는 그 행을 찾아 갱신만 하면 된다.
    /// </summary>
    private void OnClientRoomMessagePushedGlobal(object? sender, TalkRoomReceived e)
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (string.Equals(e.FromUserId, _currentUserId, StringComparison.OrdinalIgnoreCase)) return;

            var row = GetOrCreateTalkRow(e.RoomId, e.RoomId, isRoom: true);
            row.LastMessagePreview = e.Text ?? string.Empty;
            row.LastMessageTs = e.ServerTs;

            var isOpenAndActive = _openTalks.TryGetValue(e.RoomId, out var win) && win.IsActive;
            if (!isOpenAndActive)
            {
                row.UnreadCount++; // 탭 배지·트레이 합계는 RefreshTalkUnreadTotal 이 갱신

                if (_settings.Current.NotifyDirectMessages)
                {
                    if (!IsActive)
                    {
                        var preview = e.Text ?? string.Empty;
                        if (preview.Length > 80) preview = preview.Substring(0, 80) + "…";
                        App.ShowToast(row.DisplayName, preview);
                    }
                    if (_settings.Current.PlaySoundOnMessage)
                    {
                        try { System.Media.SystemSounds.Asterisk.Play(); } catch { }
                    }
                }
            }
            BringToTop(row);
        });
    }

    private void OnClientPresenceChanged(object? sender, PresenceEvent e)
    {
        Dispatcher.BeginInvoke(() =>
        {
            var online = e.State == PresenceEvent.Types.State.Online;
            if (_conversationByKey.TryGetValue(e.UserId, out var row))
            {
                row.Presence = online ? "online" : "offline";
            }
            if (e.State != PresenceEvent.Types.State.Unknown)
            {
                OrgTreeFilter.SetPresence(_orgRoots, e.UserId, online);
            }
        });
    }

    private TalkRow GetOrCreateTalkRow(string key, string displayName, bool isRoom)
    {
        if (_conversationByKey.TryGetValue(key, out var existing))
        {
            return existing;
        }
        var row = new TalkRow(key, displayName, isRoom, isRoom ? key : null);
        row.PropertyChanged += TalkRow_OnPropertyChanged;
        _conversationByKey[key] = row;
        _conversations.Insert(0, row);
        return row;
    }

    private void BringToTop(TalkRow row)
    {
        var idx = _conversations.IndexOf(row);
        if (idx > 0) _conversations.Move(idx, 0);
    }

    private void MinimizeButton_Click(object sender, RoutedEventArgs e)
    {
        WindowState = WindowState.Minimized;
    }

    private void MaximizeButton_Click(object sender, RoutedEventArgs e)
    {
        WindowState = WindowState == WindowState.Maximized
            ? WindowState.Normal
            : WindowState.Maximized;
    }

    private void CloseButton_Click(object sender, RoutedEventArgs e)
    {
        Close();
    }

    private void MainWindow_OnStateChanged(object? sender, EventArgs e)
    {
        UpdateMaximizeButtonState();
    }

    private void UpdateMaximizeButtonState()
    {
        if (MaximizeButton is null)
        {
            return;
        }

        var isMaximized = WindowState == WindowState.Maximized;
        MaximizeButton.ToolTip = isMaximized ? "복원" : "최대화";
        MaximizeIconPath.Data = (Geometry)FindResource(isMaximized ? "IconRestore" : "IconMaximize");
    }

    private void OnClientReconnected(object? sender, EventArgs e)
    {
        Dispatcher.BeginInvoke(() =>
        {
            ReloginButton.Visibility = Visibility.Collapsed;
            ShowBanner("다시 연결됨", isSuccess: true);
            SetStatusBar("연결됨", isConnected: true);
            SetProfilePresence(online: true);
            // 2초 후 배너 자동 숨김
            var timer = new System.Windows.Threading.DispatcherTimer
            {
                Interval = TimeSpan.FromSeconds(2)
            };
            timer.Tick += (_, _) =>
            {
                ConnectionBanner.Visibility = Visibility.Collapsed;
                timer.Stop();
                // 재로그인 직후 서버가 다시 보낸 온라인 스냅샷으로 조직도 접속 점을 맞춘다
                // (끊긴 동안 나간 사람의 OFFLINE 이벤트는 못 받았을 수 있음).
                OrgTreeFilter.ApplyPresence(_orgRoots, id => _client.Presence.IsOnline(id)
                    || string.Equals(id, _currentUserId, StringComparison.OrdinalIgnoreCase));
            };
            timer.Start();
        });
    }

    private void OnClientReconnectFailed(object? sender, string reason)
    {
        Dispatcher.BeginInvoke(() =>
        {
            ShowBanner($"연결 실패: {reason}", isSuccess: false);
            SetStatusBar("연결 끊김", isConnected: false);
            SetProfilePresence(online: false);
        });
    }

    /// <summary>WebSocket 끊김 → 재접속 진행 중 노란 배너 + 상태바 회전 표시.</summary>
    private void OnClientReconnecting(object? sender, EventArgs e)
    {
        Dispatcher.BeginInvoke(() =>
        {
            ShowBanner("연결이 끊어졌습니다. 재접속 중...", isSuccess: false);
            SetStatusBar("재연결 중...", isConnected: false);
            SetProfilePresence(online: false);
        });
    }

    /// <summary>
    /// 같은 계정이 다른 곳에서 로그인해 서버가 이 세션을 끊음. 자동 재접속은 클라이언트가 이미 멈췄으므로
    /// 안내 배너와 "다시 로그인" 버튼만 보여 준다(누르면 로그인 화면으로).
    /// </summary>
    private void OnClientSessionSuperseded(object? sender, string message)
    {
        Dispatcher.BeginInvoke(() =>
        {
            ShowBanner(message, isSuccess: false);
            ReloginButton.Visibility = Visibility.Visible;
            SetStatusBar("다른 곳에서 로그인됨", isConnected: false);
            SetProfilePresence(online: false);
            ProfilePresenceText.Text = "다른 곳에서 로그인됨";
            App.ShowInAppToast("연결 끊김", message, App.ToastLevel.Warn);
        });
    }

    private async void ReloginButton_OnClick(object sender, RoutedEventArgs e)
    {
        ReloginButton.IsEnabled = false;
        try { await _client.DisposeAsync(); } catch { }
        foreach (var w in _openTalks.Values.ToList())
        {
            try { w.Close(); } catch { }
        }
        // 자동 로그인을 이번 한 번 건너뛰어, 사용자가 다른 곳 연결을 끊을지 직접 정하게 한다.
        new LoginWindow(suppressAutoLogin: true, notice: "다른 곳에서 같은 계정으로 로그인되어 연결이 끊겼습니다. 다시 로그인하면 다른 곳의 연결이 끊깁니다.").Show();
        _forceClose = true;
        Close();
    }

    /// <summary>로그아웃/재로그인으로 창을 닫을 때는 트레이 숨김 대신 실제로 닫는다.</summary>
    private bool _forceClose;

    /// <summary>프로필 헤더의 본인 presence dot/text 갱신 — 재연결 상태에 연동.</summary>
    private void SetProfilePresence(bool online)
    {
        ProfilePresenceDot.Fill = online
            ? (Brush)FindResource("PresenceOnlineBrush")
            : (Brush)FindResource("TextMutedBrush");
        ProfilePresenceText.Text = online ? "온라인" : "연결 끊김";
    }

    private void ShowBanner(string text, bool isSuccess)
    {
        ConnectionBannerText.Text = text;
        ConnectionBanner.Visibility = Visibility.Visible;
        if (isSuccess)
        {
            ConnectionBanner.Background = (Brush)FindResource("BannerSuccessBgBrush");
            ConnectionBanner.BorderBrush = (Brush)FindResource("BannerSuccessFgBrush");
            ConnectionBannerText.Foreground = (Brush)FindResource("BannerSuccessFgBrush");
        }
        else
        {
            ConnectionBanner.Background = (Brush)FindResource("BannerWarnBgBrush");
            ConnectionBanner.BorderBrush = (Brush)FindResource("BannerWarnBorderBrush");
            ConnectionBannerText.Foreground = (Brush)FindResource("BannerWarnFgBrush");
        }
    }

    private void SetStatusBar(string text, bool isConnected)
    {
        // 색 점 하나 + 문구. 예전엔 문구 앞에도 ●/◯ 기호가 붙어 점이 두 개처럼 보였다.
        StatusBarText.Text = text;
        StatusBarDot.Fill = isConnected
            ? (Brush)FindResource("PresenceOnlineBrush")
            : (Brush)FindResource("BannerWarnFgBrush");
        StatusBarBorder.ToolTip = $"서버 연결: {text} (초록 = 연결됨, 노랑 = 끊김 또는 재연결 중)";
    }

    /// <summary>외부에서 동기화 건수를 갱신할 때 호출 (Phase D-13).</summary>
    public void UpdateSyncStatus(string text)
    {
        StatusBarSyncText.Text = text;
    }

    // 2026-09-17 사용자 요구사항 — 옛날 1:1 대화를 전부 대화방(Room) 시스템으로 이전하면서,
    // 이 두 진입점(검색해서 열기 / ID 직접 입력해서 새 대화)도 더 이상 구식 Mode.DirectMessage 로
    // 열지 않고 다른 진입점(조직도 더블클릭/우클릭/플러그인 버튼)과 동일하게 방 생성-또는-열기로
    // 통일한다. 이걸 안 하면 이 두 경로로 연 창만 메뉴(초대/이름변경/나가기)가 안 먹혀서 사용자가
    // "메뉴 버튼이 안 눌린다"고 느끼는 혼란이 생긴다. 검색은 서버가 내가 멤버인 방의 메시지를 찾아
    // 방 ID 를 주므로 그 방을 바로 연다(옛 서버 응답이면 상대 ID 로 방 생성-또는-열기).
    private void TalkSearchButton_OnClick(object sender, RoutedEventArgs e)
    {
        var win = new TalkSearchWindow(_client, _currentUserId) { Owner = this };
        if (win.ShowDialog() != true) return;
        if (!string.IsNullOrEmpty(win.SelectedRoomId))
        {
            var title = win.SelectedRoomTitle ?? win.SelectedRoomId;
            GetOrCreateTalkRow(win.SelectedRoomId, title, isRoom: true);
            OpenTalk(win.SelectedRoomId, title, TalkWindow.Mode.Room);
        }
        else if (!string.IsNullOrEmpty(win.SelectedCounterpart))
        {
            _ = OpenOrCreateRoomAsync(new List<string> { win.SelectedCounterpart });
        }
    }

    // 새 대화: 사용자 ID 를 손으로 치는 대신 조직도 기반 선택기(쪽지 수신자 선택기 재사용)로 상대를 고른다.
    // 1명이면 1:1(이미 있으면 그 방), 여러 명이면 그룹 방.
    private void TalkNewButton_OnClick(object sender, RoutedEventArgs e)
    {
        if (_orgRoots is null || _orgRoots.Count == 0)
        {
            // 조직도를 아직 못 받았을 때만 예전 방식(ID 입력)으로 대체
            var input = PromptForText(this, "새 대화", "상대방 사용자 ID를 입력하세요.");
            if (string.IsNullOrWhiteSpace(input)) return;
            _ = OpenOrCreateRoomAsync(new List<string> { input.Trim() });
            return;
        }
        var picker = new MessageRecipientPicker(_orgRoots, Array.Empty<string>(), excludeUserIds: new[] { _currentUserId }) { Owner = this };
        picker.SetPurpose("대화 상대 선택", "대화 시작", "대화할 사람을 골라 주세요");
        if (picker.ShowDialog() != true) return;
        var chosen = picker.SelectedUserIds;
        if (chosen.Count == 0) return;
        _ = OpenOrCreateRoomAsync(chosen.ToList());
    }

    private void TalkListBox_OnMouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        OpenSelectedTalkRow();
    }

    /// <summary>대화 목록에서 ↑/↓로 포커스 이동 후 Enter 로 선택된 대화 열기.</summary>
    private void TalkListBox_OnKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        OpenSelectedTalkRow();
        e.Handled = true;
    }

    private void OpenSelectedTalkRow()
    {
        if (TalkListBox.SelectedItem is not TalkRow row) return;
        OpenTalk(
            row.CounterpartId,
            row.DisplayName,
            row.IsRoom ? TalkWindow.Mode.Room : TalkWindow.Mode.DirectMessage);
        // 선택 시점에 읽음 처리
        row.UnreadCount = 0;
    }

    /// <summary>
    /// Dictionary 로 중복 오픈 방지. 이미 열려있으면 Activate, 없으면 생성.
    /// </summary>
    public void OpenTalk(string key, string displayName, TalkWindow.Mode mode)
    {
        if (string.IsNullOrWhiteSpace(key)) return;
        if (_openTalks.TryGetValue(key, out var existing))
        {
            if (existing.WindowState == WindowState.Minimized) existing.WindowState = WindowState.Normal;
            existing.Activate();
            ClearTalkUnread(key);
            return;
        }
        var win = new TalkWindow(_client, _currentUserId, key, mode, displayName, _orgRoots)
        {
            Owner = null,  // 독립 창
        };
        win.Closed += (_, _) => _openTalks.Remove(key);
        // 창이 열린 채 비활성일 때 온 메시지는 목록에 안 읽음으로 쌓인다. 그 창을 다시 활성화하면
        // 대화창은 서버에 읽음을 보고하므로 목록 숫자도 함께 지운다(예전엔 남아 있었다).
        win.Activated += (_, _) => ClearTalkUnread(key);
        // 서버는 발신자 본인에게 TalkReceived 를 다시 push 하지 않아서, 내가 보낸 메시지는
        // 이 이벤트 없이는 대화 목록 미리보기가 갱신될 방법이 없었다.
        win.MessageSentLocally += (text, ts) =>
        {
            var row = GetOrCreateTalkRow(key, displayName, isRoom: mode == TalkWindow.Mode.Room);
            row.LastMessagePreview = text;
            row.LastMessageTs = ts;
            BringToTop(row);
        };
        // 대화방 나가기 성공 시 내 목록에서만 이 방이 사라져야 한다(다른 멤버에게는 유지).
        win.RoomLeft += roomId => RemoveTalkRow(roomId);
        _openTalks[key] = win;

        // 대화 목록에 없으면 추가. 어느 경로(목록·조직도·검색)로 열든 연 순간 읽은 것으로 본다.
        GetOrCreateTalkRow(key, displayName, isRoom: mode == TalkWindow.Mode.Room);
        ClearTalkUnread(key);

        // 저장된 위치가 없는 첫 오픈이면 MainWindow 옆으로 배치한다. 안 그러면 대화창도
        // MainWindow 도 화면 중앙에 뜨려고 해서 완전히 겹쳐버려, 조직도가 대화창에 가려져
        // "우클릭이 안 된다"는 것처럼 보이는 문제가 있었다(실제론 클릭이 대화창 표면에 먹힘).
        if (!win.RestoredSavedPosition)
        {
            PositionTalkWindowBeside(win);
        }

        win.Show();
    }

    /// <summary>새 TalkWindow 를 MainWindow 겹치지 않게 옆에(오른쪽 우선, 공간 없으면 왼쪽) 배치.</summary>
    private void PositionTalkWindowBeside(TalkWindow win)
    {
        var screenWidth = SystemParameters.WorkArea.Width;
        var screenHeight = SystemParameters.WorkArea.Height;
        var gap = 12.0;

        var spaceRight = screenWidth - (Left + ActualWidth + gap);
        var spaceLeft = Left - gap;

        if (spaceRight >= win.Width)
        {
            win.Left = Left + ActualWidth + gap;
        }
        else if (spaceLeft >= win.Width)
        {
            win.Left = Left - gap - win.Width;
        }
        else
        {
            // 양쪽 다 공간 부족 — 화면 안에 최대한 맞춰 배치(그래도 MainWindow 와 겹치지 않게 오른쪽 우선)
            win.Left = Math.Max(0, Math.Min(Left + ActualWidth + gap, screenWidth - win.Width));
        }

        win.Top = Math.Max(0, Math.Min(Top, screenHeight - win.Height));
    }

    /// <summary>간단한 텍스트 입력 다이얼로그 (별도 Window 없이 모달).</summary>
    private static string? PromptForText(Window owner, string title, string prompt, string initialValue = "")
    {
        // 코드로 만든 흰 시스템 타이틀바 창 대신 다른 창과 같은 커스텀 타이틀바의 공통 입력 창을 쓴다.
        return TextInputDialog.Ask(owner, title, prompt, initialValue);
    }

    // ───── 조직도 탭 (Phase C-8) ─────

    /// <summary>조직도 원본 스냅샷 (필터 적용 전). </summary>
    private System.Collections.Generic.IReadOnlyList<OrgTreeNode>? _orgRoots;

    private async void OrgReloadButton_OnClick(object sender, RoutedEventArgs e)
    {
        await ReloadOrgTreeAsync();
    }

    private async System.Threading.Tasks.Task ReloadOrgTreeAsync()
    {
        try
        {
            var resp = await _client.GetOrgListAsync(System.Threading.CancellationToken.None);
            _orgRoots = OrgTreeBuilder.Build(resp);
            OrgTreeFilter.ApplyPresence(_orgRoots, id => _client.Presence.IsOnline(id)
                || string.Equals(id, _currentUserId, StringComparison.OrdinalIgnoreCase));
            ApplyOrgFilter(OrgFilterBox?.Text ?? string.Empty);
        }
        catch (Exception ex)
        {
            AppDialog.Info(this, $"조직도 로드 실패: {ex.Message}", "오류");
        }
    }

    private void OrgFilterBox_OnTextChanged(object sender, TextChangedEventArgs e)
    {
        ApplyOrgFilter(OrgFilterBox.Text);
    }

    // 필터 박스에서 ↓ 화살표 누르면 트리로 포커스 이동 (검색 → 결과 선택 UX).
    private void OrgFilterBox_OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Down)
        {
            OrgTreeView.Focus();
            // 첫 항목 자동 선택
            if (OrgTreeView.Items.Count > 0 &&
                OrgTreeView.ItemContainerGenerator.ContainerFromIndex(0) is TreeViewItem first)
            {
                first.IsSelected = true;
                first.Focus();
            }
            e.Handled = true;
        }
        else if (e.Key == Key.Enter)
        {
            // 필터 후 첫 매칭 멤버 자동 대화 시작 — 조직도의 다른 진입점(더블클릭/트리 Enter/
            // 우클릭/플러그인 버튼)과 동일하게 방 생성-또는-열기 경로로 통일한다. 예전엔 여기만
            // 구식 OpenTalk(..., Mode.DirectMessage) 로 열어서 그 창은 방이 아니므로 헤더의
            // 초대하기/이름설정/나가기 메뉴가 전부 막혀 있었다("이 메뉴는 대화방에서만 사용할 수
            // 있습니다" 안내만 뜸) — 검색창에서 Enter 로 대화를 시작하는 사용자에게는 메뉴 버튼이
            // 안 눌리는 것처럼 보이는 버그였다.
            var firstMember = FindFirstMember(OrgTreeView.ItemsSource as IEnumerable<OrgTreeNode>);
            if (firstMember != null
                && !string.IsNullOrEmpty(firstMember.UserId)
                && !string.Equals(firstMember.UserId, _currentUserId, StringComparison.OrdinalIgnoreCase))
            {
                _ = OpenOrCreateRoomAsync(new List<string> { firstMember.UserId });
                e.Handled = true;
            }
        }
    }

    private static OrgTreeNode? FindFirstMember(IEnumerable<OrgTreeNode>? nodes)
    {
        if (nodes is null) return null;
        foreach (var n in nodes)
        {
            if (n.Kind == OrgTreeNodeKind.Member) return n;
            var inChild = FindFirstMember(n.Children);
            if (inChild != null) return inChild;
        }
        return null;
    }

    private void ApplyOrgFilter(string? query)
    {
        if (_orgRoots is null)
        {
            OrgTreeView.ItemsSource = null;
            OrgFilterEmptyText.Visibility = Visibility.Collapsed;
            return;
        }
        // 멤버는 원본 인스턴스를 그대로 노출(선택 상태 유지), 부서는 펼친 복제본 — OrgTreeFilter 참고.
        var filtered = OrgTreeFilter.Filter(_orgRoots, query);
        OrgTreeView.ItemsSource = filtered;

        var isFiltering = !string.IsNullOrWhiteSpace(query);
        if (isFiltering && OrgTreeFilter.CountMembers(filtered) == 0)
        {
            OrgFilterEmptyText.Text = $"'{query!.Trim()}' 에 해당하는 사람이 없습니다.\n이름 일부나 부서 이름으로 다시 찾아 보세요.";
            OrgFilterEmptyText.Visibility = Visibility.Visible;
        }
        else
        {
            OrgFilterEmptyText.Visibility = Visibility.Collapsed;
        }
    }

    /// <summary>
    /// 2026-09-17 사용자 요구사항 — 메인 조직도 탭에서는 체크박스 대신 클릭으로 선택한다.
    /// 그냥 클릭하면 이 사람만 단일 선택(기존 선택 전부 해제), Ctrl+클릭이면 이 사람만
    /// 토글하면서 기존 선택은 그대로 유지(다중 선택). 수신자 선택 창(MessageRecipientPicker)의
    /// 체크박스는 이 핸들러와 무관하게 그대로 유지된다.
    /// </summary>
    private void OrgMemberRow_OnMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is not OrgTreeNode node) return;
        if (node.Kind != OrgTreeNodeKind.Member) return;

        // 2026-09-17 발견 — TreeViewItem 안의 Border 에서 일반 MouseLeftButtonDown(버블링)으로
        // Keyboard.Modifiers 를 읽으면, TreeView 자체의 네이티브 선택 처리가 먼저/동시에 끼어들어
        // Ctrl 이 물리적으로 눌려있어도(GetAsyncKeyState 로 확인됨) 감지가 안 되는 경우가 있었다
        // (Ctrl+클릭이 다중선택이 아니라 매번 새로 단일선택 하는 것처럼 동작). Keyboard.IsKeyDown
        // 을 좌/우 Ctrl 각각에 대해 직접 확인하는 방식으로 바꿔 더 안정적으로 감지한다.
        var ctrlHeld = Keyboard.IsKeyDown(Key.LeftCtrl) || Keyboard.IsKeyDown(Key.RightCtrl);
        if (ctrlHeld)
        {
            node.IsSelectedForRoom = !node.IsSelectedForRoom;
        }
        else
        {
            ClearOrgCheckboxes();
            node.IsSelectedForRoom = true;
        }
        UpdateOrgSelectionHint();
    }

    /// <summary>
    /// 조직도 선택 인원을 레일 버튼에 보여 준다(예: "대화 3명"). 선택이 몇 명인지 화면에서 바로 알 수 있게.
    /// 본인은 대화 상대가 아니므로 세지 않는다.
    /// </summary>
    private void UpdateOrgSelectionHint()
    {
        var count = GetCheckedOrgMemberIds()
            .Count(id => !string.Equals(id, _currentUserId, StringComparison.OrdinalIgnoreCase));
        PluginSendTalkLabel.Text = count > 0 ? $"대화 {count}명" : "대화하기";
        PluginSendTalkButton.ToolTip = count > 0
            ? $"선택한 {count}명과 대화하기 (Ctrl+클릭으로 더 고르기)"
            : "대화하기 — 조직도에서 고른 사람과 대화방을 엽니다";
        System.Windows.Automation.AutomationProperties.SetName(PluginSendTalkButton, count > 0 ? $"선택한 {count}명과 대화하기" : "대화하기");
        PluginSendMessageButton.ToolTip = count > 0
            ? $"선택한 {count}명에게 쪽지 쓰기"
            : "쪽지 쓰기 — 조직도에서 고른 사람이 받는 사람으로 들어갑니다";
    }

    private void OrgTreeView_OnMouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (OrgTreeView.SelectedItem is not OrgTreeNode node) return;
        if (node.Kind != OrgTreeNodeKind.Member) return;
        if (string.IsNullOrEmpty(node.UserId)) return;
        if (string.Equals(node.UserId, _currentUserId, StringComparison.OrdinalIgnoreCase)) return;
        // 우클릭 "대화하기"/플러그인 버튼과 동일한 대화방 생성-또는-열기 경로로 통일.
        _ = OpenOrCreateRoomAsync(new List<string> { node.UserId });
    }

    // Enter 키: 선택된 멤버와 1:1 대화 시작 (TreeView 의 위/아래 화살표는 WPF default).
    private void OrgTreeView_OnKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        if (OrgTreeView.SelectedItem is not OrgTreeNode node) return;
        if (node.Kind != OrgTreeNodeKind.Member) return;
        if (string.IsNullOrEmpty(node.UserId)) return;
        if (string.Equals(node.UserId, _currentUserId, StringComparison.OrdinalIgnoreCase)) return;
        _ = OpenOrCreateRoomAsync(new List<string> { node.UserId });
        e.Handled = true;
    }

    // 우클릭 컨텍스트 메뉴: ContextMenu 의 DataContext 는 자동으로 PlacementTarget 의 DataContext 와 같음(트리 노드).
    // 체크된 사람이 있으면(다중 선택) 그 사람들 전부로, 없으면 우클릭한 이 사람 한 명으로 대화방을 만들거나 연다.
    private void OrgMember_StartTalk_OnClick(object sender, RoutedEventArgs e)
    {
        var targets = GetContextMenuOrgTargets(sender);
        if (targets.Count == 0) return;
        _ = OpenOrCreateRoomAsync(targets);
    }

    private void OrgMember_SendMessage_OnClick(object sender, RoutedEventArgs e)
    {
        var targets = GetContextMenuOrgTargets(sender);
        if (targets.Count == 0) return;

        var dlg = new MessageComposeWindow(_client, _orgRoots) { Owner = this };
        dlg.SetInitialRecipients(targets);
        if (dlg.ShowDialog() == true)
        {
            if (_messageBox == "sent") _ = ReloadMessageBoxAsync();
            App.ShowInAppToast("쪽지 보냄", $"{string.Join(", ", targets)} 에게 쪽지를 보냈습니다", App.ToastLevel.Success);
        }
    }

    /// <summary>체크된 조직도 멤버(다중 선택)가 있으면 그 UserId 들을, 없으면 우클릭한 사람 1명을 반환.</summary>
    private List<string> GetContextMenuOrgTargets(object sender)
    {
        var checkedIds = GetCheckedOrgMemberIds();
        if (checkedIds.Count > 0) return checkedIds;
        if ((sender as FrameworkElement)?.DataContext is OrgTreeNode node
            && node.Kind == OrgTreeNodeKind.Member
            && !string.IsNullOrEmpty(node.UserId)
            && !string.Equals(node.UserId, _currentUserId, StringComparison.OrdinalIgnoreCase))
        {
            return new List<string> { node.UserId };
        }
        return new List<string>();
    }

    // ───── 좌측 레일 플러그인 버튼 (쪽지/대화 바로 보내기) ─────

    /// <summary>
    /// 조직도 탭이 현재 활성 탭이고, 트리에서 "사람" 노드(부서 아님)가 선택되어 있을 때만
    /// 그 사람을 반환한다. 그 외(다른 탭/미선택/부서 선택/본인 선택)는 null.
    /// </summary>
    private OrgTreeNode? GetFocusedOrgMember()
    {
        if (TabOrg.IsChecked != true) return null;
        if (OrgTreeView.SelectedItem is not OrgTreeNode node) return null;
        if (node.Kind != OrgTreeNodeKind.Member) return null;
        if (string.IsNullOrEmpty(node.UserId)) return null;
        if (string.Equals(node.UserId, _currentUserId, StringComparison.OrdinalIgnoreCase)) return null;
        return node;
    }

    /// <summary>
    /// 조직도에서 체크된(다중 선택) 멤버들의 UserId 목록 — 본인도 체크했다면 그대로 포함해서 반환한다.
    /// 본인만 체크하고 "대화하기"를 누르면 "나 혼자 선택 = 나와의 대화" 특이 케이스가 되어야 하는데,
    /// 여기서 본인을 미리 걸러버리면 호출자가 "아무도 선택 안 함"과 구분을 못 해 빈 초대-대기 창이
    /// 뜨는 잘못된 동작이 됐다. 본인 제외는 OpenOrCreateRoomAsync 에서 한 번만 한다.
    /// </summary>
    private List<string> GetCheckedOrgMemberIds()
    {
        var result = new List<string>();
        void Walk(IEnumerable<OrgTreeNode>? nodes)
        {
            if (nodes is null) return;
            foreach (var n in nodes)
            {
                if (n.Kind == OrgTreeNodeKind.Member && n.IsSelectedForRoom && !string.IsNullOrEmpty(n.UserId))
                {
                    result.Add(n.UserId);
                }
                Walk(n.Children);
            }
        }
        Walk(_orgRoots);
        return result.Distinct(StringComparer.OrdinalIgnoreCase).ToList();
    }

    /// <summary>체크박스로 선택된 조직도 항목을 전부 해제한다(대화방 생성/초대 실행 후 호출).</summary>
    private void ClearOrgCheckboxes()
    {
        void Walk(IEnumerable<OrgTreeNode>? nodes)
        {
            if (nodes is null) return;
            foreach (var n in nodes)
            {
                if (n.IsSelectedForRoom) n.IsSelectedForRoom = false;
                Walk(n.Children);
            }
        }
        Walk(_orgRoots);
        if (PluginSendTalkLabel is not null) UpdateOrgSelectionHint();
    }

    /// <summary>체크된 멤버(다중 선택)가 있으면 그들 전부, 없으면 포커싱된 단일 멤버(있다면) 하나.</summary>
    private List<string> GetSelectedOrgMemberIds()
    {
        var checkedIds = GetCheckedOrgMemberIds();
        if (checkedIds.Count > 0) return checkedIds;
        var single = GetFocusedOrgMember();
        return single is not null ? new List<string> { single.UserId } : new List<string>();
    }

    private void PluginSendMessage_OnClick(object sender, RoutedEventArgs e)
    {
        var targets = GetSelectedOrgMemberIds();
        var dlg = new MessageComposeWindow(_client, _orgRoots) { Owner = this };
        if (targets.Count > 0)
        {
            dlg.SetInitialRecipients(targets);
        }
        if (dlg.ShowDialog() == true)
        {
            if (_messageBox == "sent") _ = ReloadMessageBoxAsync();
            StatusBarSyncText.Text = "쪽지를 보냈습니다";
        }
    }

    private void PluginSendTalk_OnClick(object sender, RoutedEventArgs e)
    {
        var targets = GetSelectedOrgMemberIds();
        if (targets.Count > 0)
        {
            _ = OpenOrCreateRoomAsync(targets);
            return;
        }

        // 조직도에서 아무도 선택/포커싱되어 있지 않은 경우(무선택/부서 선택/다른 탭) —
        // 대화 상대가 아직 없는 빈 대화창을 띄우고, 창 안의 "대화 상대 초대하기" 버튼으로
        // 나중에 상대(들)를 고르게 한다(TalkWindow 의 pendingInvite 생성자, PendingInviteOverlay).
        var win = new TalkWindow(_client, _currentUserId, _orgRoots) { Owner = null };
        win.CounterpartInvited += chosen => _ = OpenOrCreateRoomAsync(chosen.ToList());

        if (!win.RestoredSavedPosition)
        {
            PositionTalkWindowBeside(win);
        }
        win.Show();
    }

    /// <summary>
    /// 조직도에서 고른 사람들(targetUserIds, 본인 제외)과 대화방을 열거나 새로 만든다.
    /// 정확히 같은 구성원의 방이 이미 있으면 "대화방으로 이동 / 대화방 만들기"를 물어본다.
    /// 대상이 0명이면(나 혼자 선택) "나와의 대화" 혼잣말 방이 된다.
    /// </summary>
    // internal — TalkWindow 가 1:1 방에서 초대하기를 눌렀을 때 새 그룹방을 만들기 위해 호출한다
    // (2026-09-17 요구사항: 1:1 방에서 초대하면 기존 1:1 방은 그대로 두고 새 그룹방을 만듦).
    internal async Task OpenOrCreateRoomAsync(IReadOnlyList<string> targetUserIds)
    {
        var targets = targetUserIds
            .Where(id => !string.Equals(id, _currentUserId, StringComparison.OrdinalIgnoreCase))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        ClearOrgCheckboxes();

        var roomName = ComputeAutoRoomName(targets);
        TalkRoomCreateAck ack;
        try
        {
            ack = await _client.CreateRoomAsync(roomName, targets, force: false);
        }
        catch (Exception ex)
        {
            AppDialog.Info(this, $"대화방을 만들지 못했습니다: {ex.Message}", "오소리 메신저");
            return;
        }

        if (ack.Result == TalkRoomCreateAck.Types.Result.DuplicateMembers)
        {
            // 1:1(상대 1명)·나와의 대화는 같은 구성원 방을 새로 만들 이유가 거의 없으므로 묻지 않고 기존 방을 연다.
            // 그룹(상대 2명 이상)만 "이동 / 새로 만들기" 를 묻는다.
            var choice = targets.Count <= 1 ? DuplicateRoomChoice.Move : ShowDuplicateRoomDialog();
            if (choice == DuplicateRoomChoice.Cancel) return;
            if (choice == DuplicateRoomChoice.Move)
            {
                OpenTalk(ack.ExistingRoomId, roomName, TalkWindow.Mode.Room);
                return;
            }
            try
            {
                ack = await _client.CreateRoomAsync(roomName, targets, force: true);
            }
            catch (Exception ex)
            {
                AppDialog.Info(this, $"대화방을 만들지 못했습니다: {ex.Message}", "오소리 메신저");
                return;
            }
        }

        if (ack.Result != TalkRoomCreateAck.Types.Result.Ok)
        {
            AppDialog.Info(this, $"대화방을 만들지 못했습니다.\n{ResultMessages.RoomCreate(ack.Result)}", "오소리 메신저");
            return;
        }

        OpenTalk(ack.RoomId, roomName, TalkWindow.Mode.Room);
        if (targets.Count == 1 && _conversationByKey.TryGetValue(ack.RoomId, out var created))
        {
            created.DirectPeerId = targets[0];
        }
    }

    /// <summary>
    /// 방 이름 자동 명명 — 혼자면 "나와의 대화", 2~3명이면 아이디 전체 나열, 4명 이상이면
    /// "첫 번째 아이디 외 N명" (쪽지 보낸함에서 이미 쓰는 표기법과 동일). 나중에 메뉴에서 수동 변경 가능.
    /// </summary>
    internal static string ComputeAutoRoomName(IReadOnlyList<string> otherMemberIds)
        // 규칙은 Core 로 옮김: 아이디 오름차순 정렬로 목록·대화창·검색 결과가 같은 이름을 보인다.
        => RoomNaming.AutoName(otherMemberIds);

    /// <summary>
    /// 2026-09-17 버그 수정 — 서버는 자동 명명된 방 이름을 "방장 기준"으로 딱 한 번 계산해서
    /// 모든 멤버에게 똑같이 내려준다. 그래서 방장이 아닌 멤버는 상대방 대신 자기 자신의 이름을
    /// 방 제목으로 보게 됐다. 자동 명명 방(nameIsCustom=false)이면 멤버 목록에서 "나"를 뺀
    /// 나머지로 이름을 직접 재계산하고, 수동 개명 방이면 서버가 준 이름을 그대로 쓴다.
    /// </summary>
    internal static string ResolveRoomDisplayName(
        string serverName,
        bool nameIsCustom,
        IEnumerable<string> memberIds,
        string currentUserId,
        string fallbackKey)
        => RoomNaming.Resolve(serverName, nameIsCustom, memberIds, currentUserId, fallbackKey);

    private enum DuplicateRoomChoice { Move, CreateNew, Cancel }

    /// <summary>"이미 같은 구성원의 방이 있습니다 — 이동할까요, 새로 만들까요?" 확인창.</summary>
    private DuplicateRoomChoice ShowDuplicateRoomDialog()
    {
        // 공통 보조 창(AppDialog): 다크 타이틀바, 내용 높이에 맞춰 늘어나 버튼이 잘리지 않는다.
        var idx = AppDialog.Choose(
            this,
            "이미 같은 구성원으로 만들어진 대화방이 있습니다.\n어떻게 할까요?",
            "대화방 만들기",
            new[] { "대화방으로 이동", "새 대화방 만들기", "취소" },
            defaultIndex: 0,
            cancelIndex: 2);
        return idx switch
        {
            0 => DuplicateRoomChoice.Move,
            1 => DuplicateRoomChoice.CreateNew,
            _ => DuplicateRoomChoice.Cancel,
        };
    }

    private void RemoveTalkRow(string key)
    {
        if (_conversationByKey.TryGetValue(key, out var row))
        {
            _conversationByKey.Remove(key);
            _conversations.Remove(row);
            row.PropertyChanged -= TalkRow_OnPropertyChanged;
            RefreshTalkUnreadTotal();
        }
    }

    private void TalkRow_OnPropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(TalkRow.UnreadCount)) RefreshTalkUnreadTotal();
    }

    /// <summary>안 읽은 대화 합계 → 왼쪽 대화 탭 아이콘 배지(쪽지 배지와 같은 모양)와 트레이 표시.</summary>
    private void RefreshTalkUnreadTotal()
    {
        var total = _conversations.Sum(c => c.UnreadCount);
        TalkTabBadge.Visibility = total > 0 ? Visibility.Visible : Visibility.Collapsed;
        TalkTabBadgeText.Text = total > 99 ? "99+" : total.ToString();
        System.Windows.Automation.AutomationProperties.SetName(TabTalk, total > 0 ? $"대화, 안 읽음 {total}개" : "대화");
        App.UpdateTrayUnreadCount(total);
    }

    /// <summary>대화창을 열거나 그 창이 활성화되면(= 읽음 보고 시점) 목록의 안 읽음 숫자를 지운다.</summary>
    private void ClearTalkUnread(string key)
    {
        if (_conversationByKey.TryGetValue(key, out var row)) row.UnreadCount = 0;
    }

    // ───── 설정 탭 (Phase C-9) ─────

    private void LoadSettingsUi()
    {
        var s = _settings.Current;
        SettingsHostBox.Text = s.ServerHost;
        SettingsPortBox.Text = s.ServerPort.ToString();
        SettingsUseTlsCheck.IsChecked = s.UseTls;
        SettingsServerSummary.Text = $"{s.ServerHost}:{s.ServerPort}" + (s.UseTls ? " · wss" : "");

        SettingsPlaySoundCheck.IsChecked = s.PlaySoundOnMessage;
        SettingsTrayCheck.IsChecked = s.ShowTrayNotification;
        SettingsCloseToTrayCheck.IsChecked = s.CloseToTray;
        SettingsNotifyDmCheck.IsChecked = s.NotifyDirectMessages;
        SettingsNotifyRoomCheck.IsChecked = s.NotifyRoomMessages;
        SettingsNotifyMessageCheck.IsChecked = s.NotifyMessages;
        SettingsNotifyFileCheck.IsChecked = s.NotifyFiles;
        SettingsNotifySummary.Text = string.Join(" · ",
            new[] {
                s.PlaySoundOnMessage ? "소리" : null,
                s.ShowTrayNotification ? "트레이" : null,
                s.CloseToTray ? "X→트레이" : null,
            }.Where(x => x != null)!);

        SettingsFontSizeBox.Text = s.FontSize.ToString();
        foreach (ComboBoxItem item in SettingsThemeCombo.Items)
        {
            if ((item.Content?.ToString() ?? "") == s.Theme)
            {
                SettingsThemeCombo.SelectedItem = item;
                break;
            }
        }
        // 테마 전환은 아직 없으므로 저장값(옛 기본 "light")과 무관하게 실제 화면인 다크로 표시한다.
        SettingsDisplaySummary.Text = $"글꼴 {s.FontSize}pt · 다크";

        SettingsAutoReconnectCheck.IsChecked = s.AutoReconnect;
        SettingsAutoHistoryCheck.IsChecked = s.AutoSyncHistoryOnLogin;
        SettingsAutoOrgCheck.IsChecked = s.AutoLoadOrgOnLogin;
        SettingsHistorySizeBox.Text = s.HistoryPageSize.ToString();
        SettingsReliabilitySummary.Text = string.Join(" · ",
            new[] {
                s.AutoReconnect ? "재접속" : null,
                s.AutoSyncHistoryOnLogin ? "히스토리 동기화" : null,
                s.AutoLoadOrgOnLogin ? "조직도 자동" : null,
            }.Where(x => x != null)!);

        // 버전 정보 영역 — 빌드 시점은 assembly file last-write 로 추정 (.NET 8 는 어셈블리 BuildTime attribute 가 기본 없음)
        try
        {
            var asmPath = System.Reflection.Assembly.GetExecutingAssembly().Location;
            var build = !string.IsNullOrEmpty(asmPath) && System.IO.File.Exists(asmPath)
                ? System.IO.File.GetLastWriteTime(asmPath).ToString("yyyy-MM-dd HH:mm")
                : "(알수없음)";
            VersionInfoText.Text =
                $"빌드 {build}\n.NET {Environment.Version}\n{Environment.OSVersion}\n사용자 {_currentUserId}";
        }
        catch
        {
            VersionInfoText.Text = $"사용자 {_currentUserId}";
        }
    }

    private void SettingsSaveButton_OnClick(object sender, RoutedEventArgs e)
    {
        // 유효성 검증
        if (!int.TryParse(SettingsPortBox.Text.Trim(), out var port) || port < 1 || port > 65535)
        {
            AppDialog.Info(this, "포트는 1~65535 범위여야 합니다.", "저장 실패");
            return;
        }
        if (!int.TryParse(SettingsFontSizeBox.Text.Trim(), out var fontSize) || fontSize < 8 || fontSize > 48)
        {
            AppDialog.Info(this, "글꼴 크기는 8~48 범위여야 합니다.", "저장 실패");
            return;
        }
        if (!int.TryParse(SettingsHistorySizeBox.Text.Trim(), out var pageSize) || pageSize < 1 || pageSize > 500)
        {
            AppDialog.Info(this, "히스토리 페이지 크기는 1~500 이어야 합니다.", "저장 실패");
            return;
        }

        var prevHost = _settings.Current.ServerHost;
        var prevPort = _settings.Current.ServerPort;
        var prevTls = _settings.Current.UseTls;
        var newHost = SettingsHostBox.Text.Trim();
        var newTls = SettingsUseTlsCheck.IsChecked == true;
        _settings.Update(s =>
        {
            s.ServerHost = newHost;
            s.ServerPort = port;
            s.UseTls = newTls;
            s.PlaySoundOnMessage = SettingsPlaySoundCheck.IsChecked == true;
            s.ShowTrayNotification = SettingsTrayCheck.IsChecked == true;
            s.CloseToTray = SettingsCloseToTrayCheck.IsChecked == true;
            s.NotifyDirectMessages = SettingsNotifyDmCheck.IsChecked == true;
            s.NotifyRoomMessages = SettingsNotifyRoomCheck.IsChecked == true;
            s.NotifyMessages = SettingsNotifyMessageCheck.IsChecked == true;
            s.NotifyFiles = SettingsNotifyFileCheck.IsChecked == true;
            s.FontSize = fontSize;
            s.Theme = "dark"; // 테마 전환 구현 전 — 실제 화면과 같은 값으로 저장
            s.AutoReconnect = SettingsAutoReconnectCheck.IsChecked == true;
            s.AutoSyncHistoryOnLogin = SettingsAutoHistoryCheck.IsChecked == true;
            s.AutoLoadOrgOnLogin = SettingsAutoOrgCheck.IsChecked == true;
            s.HistoryPageSize = pageSize;
        });
        LoadSettingsUi();

        // 서버 URL 변경 시 — 현재 세션은 영향 없음, 다음 로그인부터 적용
        if (prevHost != newHost || prevPort != port || prevTls != newTls)
        {
            App.ShowInAppToast(
                "서버 주소 변경됨",
                "다음 로그인부터 새 주소로 접속됩니다. 즉시 적용하려면 로그아웃 후 재로그인.",
                App.ToastLevel.Warn,
                durationMs: 5000);
        }

        // 글꼴 크기 — 즉시 반영 (Window.FontSize inherit). 명시 FontSize 가진 토큰들은 영향 안 받음.
        FontSize = fontSize;
    }

    private void SettingsResetButton_OnClick(object sender, RoutedEventArgs e)
    {
        if (AppDialog.Confirm(this, "모든 설정을 기본값으로 되돌릴까요?", "초기화", yes: "초기화", no: "취소"))
        {
            _settings.ResetToDefaults();
            LoadSettingsUi();
        }
    }

    // 진단 로그 zip 내보내기 — crash.log + 서버 로그(있다면) + 클라 설정 메타 한 묶음
    private void SettingsExportLogsButton_OnClick(object sender, RoutedEventArgs e)
    {
        var defaultName = $"OsoriMessenger-logs-{DateTime.Now:yyyyMMdd-HHmmss}.zip";
        var save = new Microsoft.Win32.SaveFileDialog
        {
            Title = "진단 로그 저장",
            FileName = defaultName,
            Filter = "ZIP 압축|*.zip",
        };
        if (save.ShowDialog(this) != true) return;

        try
        {
            var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var logsDir = System.IO.Path.Combine(localAppData, "OsoriMessenger", "logs");
            using var fs = System.IO.File.Create(save.FileName);
            using var archive = new System.IO.Compression.ZipArchive(fs, System.IO.Compression.ZipArchiveMode.Create);

            // 1) crash.log + 다른 .log 파일 일괄 포함
            if (System.IO.Directory.Exists(logsDir))
            {
                foreach (var f in System.IO.Directory.EnumerateFiles(logsDir, "*", System.IO.SearchOption.AllDirectories))
                {
                    var rel = System.IO.Path.GetRelativePath(logsDir, f);
                    try
                    {
                        var entry = archive.CreateEntry("client-logs/" + rel.Replace('\\', '/'), System.IO.Compression.CompressionLevel.Optimal);
                        using var src = System.IO.File.OpenRead(f);
                        using var dst = entry.Open();
                        src.CopyTo(dst);
                    }
                    catch { /* 잠긴 파일은 스킵 */ }
                }
            }

            // 2) 환경 메타정보 (사용자ID, 버전, .NET 런타임)
            var meta = $"client_version: 0.1.0\nuser_id: {_currentUserId}\ndotnet: {Environment.Version}\nos: {Environment.OSVersion}\ngenerated: {DateTime.Now:O}\n";
            var metaEntry = archive.CreateEntry("meta.txt");
            using (var w = new System.IO.StreamWriter(metaEntry.Open())) w.Write(meta);

            App.ShowInAppToast("진단 로그 저장됨", System.IO.Path.GetFileName(save.FileName), App.ToastLevel.Success);
            System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{save.FileName}\"");
        }
        catch (Exception ex)
        {
            AppDialog.Info(this, "로그를 내보내지 못했습니다.", "오류", detail: ex.Message);
        }
    }

    private async void SettingsLogoutButton_OnClick(object sender, RoutedEventArgs e)
    {
        await _client.LogoutAsync(System.Threading.CancellationToken.None);
        await _client.DisposeAsync();
        // 2026-09-17 버그 수정: 자동 로그인이 켜져 있으면 그냥 new LoginWindow() 는 뜨자마자
        // 저장된 비밀번호로 즉시 재접속해버려서 "로그아웃"이 사실상 무의미했다(같은 계정으로
        // 바로 다시 로그인됨). 명시적으로 로그아웃한 이번 한 번만 자동 로그인을 건너뛴다 —
        // 저장된 설정 자체는 안 건드리므로, 앱을 완전히 재시작하면 자동 로그인은 다시 정상 동작한다.
        new LoginWindow(suppressAutoLogin: true).Show();
        // 트레이 숨김 설정이어도 로그아웃이면 이 창은 실제로 닫는다(숨긴 채 남으면 옛 세션 창이 살아 있음).
        _forceClose = true;
        Close();
    }

    /// <summary>설정 탭 "종료" 버튼 — 로그아웃 후 메신저 프로세스 자체를 종료한다(트레이로 숨기지 않음).</summary>
    private async void SettingsExitButton_OnClick(object sender, RoutedEventArgs e)
    {
        try
        {
            await _client.LogoutAsync(System.Threading.CancellationToken.None);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[Exit] logout failed: {ex.Message}");
        }
        finally
        {
            await _client.DisposeAsync();
        }

        App.RequestShutdown();
    }

    private void ApplyProfileHeader()
    {
        ProfileNameText.Text = _currentUserId;
        ProfileTitleText.Text = ""; // 조직도에서 찾아 채우는 건 C-7 이후
        AvatarInitial.Text = string.IsNullOrEmpty(_currentUserId)
            ? "?"
            : _currentUserId[0].ToString().ToUpperInvariant();

        // userId 해시 기반 아바타 색
        var colorHex = AvatarPalette[GetColorIndex(_currentUserId)];
        var color = (Color)ColorConverter.ConvertFromString(colorHex);
        if (((Border)AvatarInitial.Parent) is Border avatarBorder)
        {
            avatarBorder.Background = new SolidColorBrush(color);
        }
    }

    private static int GetColorIndex(string userId)
    {
        if (string.IsNullOrEmpty(userId)) return 0;
        using var sha = System.Security.Cryptography.SHA1.Create();
        var bytes = sha.ComputeHash(System.Text.Encoding.UTF8.GetBytes(userId));
        return bytes[0] % AvatarPalette.Length;
    }

    private void RestoreLastTab()
    {
        switch (_settings.Current.LastSelectedTab)
        {
            case "org": TabOrg.IsChecked = true; break;
            case "talk": TabTalk.IsChecked = true; break;
            case "message": TabMessage.IsChecked = true; break;
            case "settings": TabSettings.IsChecked = true; break;
            default: TabOrg.IsChecked = true; break;
        }
    }

    private void RestoreWindowBounds()
    {
        var bounds = _settings.Current.MainWindowBounds;
        if (bounds is null) return;
        if (bounds.Width > 0 && bounds.Height > 0)
        {
            Left = bounds.X;
            Top = bounds.Y;
            Width = bounds.Width;
            Height = bounds.Height;
        }
        if (bounds.Maximized)
        {
            WindowState = WindowState.Maximized;
        }
    }

    private void Tab_OnChecked(object sender, RoutedEventArgs e)
    {
        if (sender is not RadioButton rb || rb.Tag is not string tag) return;
        ShowTab(tag);
        _settings.Update(s => s.LastSelectedTab = tag);
    }

    private void ShowTab(string tag)
    {
        TalkTabContent.Visibility = tag == "talk" ? Visibility.Visible : Visibility.Collapsed;
        OrgTabContent.Visibility = tag == "org" ? Visibility.Visible : Visibility.Collapsed;
        MessageTabContent.Visibility = tag == "message" ? Visibility.Visible : Visibility.Collapsed;
        SettingsTabContent.Visibility = tag == "settings" ? Visibility.Visible : Visibility.Collapsed;
        if (tag == "message") _ = ReloadMessageBoxAsync();
    }

    private void MainWindow_OnKeyDown(object sender, KeyEventArgs e)
    {
        // F5: 활성 탭 새로고침 (조직도 / 쪽지함). 다른 단축키는 Ctrl 동반.
        if (e.Key == Key.F5)
        {
            if (OrgTabContent.Visibility == Visibility.Visible) _ = ReloadOrgTreeAsync();
            else if (MessageTabContent.Visibility == Visibility.Visible) _ = ReloadMessageBoxAsync();
            e.Handled = true;
            return;
        }
        if (!Keyboard.Modifiers.HasFlag(ModifierKeys.Control)) return;
        switch (e.Key)
        {
            case Key.D1: TabOrg.IsChecked = true; e.Handled = true; break;
            case Key.D2: TabTalk.IsChecked = true; e.Handled = true; break;
            case Key.D3: TabMessage.IsChecked = true; e.Handled = true; break;
            case Key.OemComma: TabSettings.IsChecked = true; e.Handled = true; break;
            // Ctrl+N: 새 쪽지 작성 (쪽지 탭으로 전환 + Compose 열기)
            case Key.N:
                TabMessage.IsChecked = true;
                MessageComposeButton_OnClick(this, new RoutedEventArgs());
                e.Handled = true;
                break;
            // Ctrl+F: 대화 검색 (대화 탭 검색 박스 활성)
            case Key.F:
                TabTalk.IsChecked = true;
                TalkSearchButton_OnClick(this, new RoutedEventArgs());
                e.Handled = true;
                break;
        }
    }

    private void MainWindow_OnClosed(object? sender, EventArgs e)
    {
        // 창 위치/크기 저장
        _settings.Update(s =>
        {
            s.MainWindowBounds = new WindowBounds
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
    ///: 닫기 버튼(X)을 눌렀을 때 CloseToTray 옵션이 켜져있고 트레이 종료 의사가 아니면
    /// 종료 대신 Hide. 트레이 메뉴의 "종료" 또는 명시적 RequestShutdown 시에만 실제 종료.
    /// </summary>
    protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
    {
        // multi 모드에선 트레이 아이콘이 없으므로 X = 실제 종료 (Hide 하면 되살릴 방법이 없음)
        var closeToTray = _settings.Current.CloseToTray && !App.MultiInstanceMode;
        if (closeToTray && !App.ForceShutdownRequested && !_forceClose)
        {
            e.Cancel = true;
            Hide();
            return;
        }
        base.OnClosing(e);
    }

    // ════════ 쪽지 ════════
    private readonly ObservableCollection<MessageRowVm> _messageRows = new();
    private string _messageBox = "inbox";
    private int _messageUnread;

    private async System.Threading.Tasks.Task ReloadMessageBoxAsync()
    {
        try
        {
            var res = _messageBox == "sent"
                ? await _client.GetMessageSentAsync()
                : await _client.GetMessageInboxAsync();
            _messageRows.Clear();
            if (res?.Status != MessageListResponse.Types.Status.Ok) return;
            foreach (var e in res.Entries)
            {
                _messageRows.Add(MessageRowVm.FromEntry(e, _messageBox == "sent", _currentUserId));
            }
            MessageEmptyText.Text = _messageBox == "sent" ? "보낸 쪽지가 없습니다" : "받은 쪽지가 없습니다";
            // 보낸쪽지함 조회 중엔 _messageRows 가 전부 IsSentBox=true 라 여기서 재계산하면
            // 실제 받은쪽지함 미열독 수를 0으로 덮어써버리므로, 받은쪽지함일 때만 반영한다.
            if (_messageBox == "inbox")
            {
                RecomputeMessageUnread();
            }
        }
        catch (Exception ex)
        {
            StatusBarSyncText.Text = $"쪽지 동기화 실패: {ex.Message}";
        }
    }

    private void MessageBox_OnChecked(object sender, RoutedEventArgs e)
    {
        if (sender is not RadioButton rb || rb.Tag is not string tag) return;
        if (_messageBox == tag) return;
        _messageBox = tag;
        _ = ReloadMessageBoxAsync();
    }

    private void MessageReloadButton_OnClick(object sender, RoutedEventArgs e) => _ = ReloadMessageBoxAsync();

    /// <summary>외부(트레이 메뉴/단축키) 진입점 — 쪽지 탭으로 전환 후 Compose 다이얼로그 오픈.</summary>
    public void OpenMessageComposeFromExternal()
    {
        Dispatcher.BeginInvoke(() =>
        {
            TabMessage.IsChecked = true;
            MessageComposeButton_OnClick(this, new RoutedEventArgs());
        });
    }

    private void MessageComposeButton_OnClick(object sender, RoutedEventArgs e)
    {
        var dlg = new MessageComposeWindow(_client, _orgRoots) { Owner = this };
        if (dlg.ShowDialog() == true)
        {
            // 보낸쪽지함을 보고 있었다면 즉시 반영
            if (_messageBox == "sent") _ = ReloadMessageBoxAsync();
            StatusBarSyncText.Text = "쪽지를 보냈습니다";
        }
    }

    private void MessageListBox_OnMouseDoubleClick(object sender, MouseButtonEventArgs e) => OpenSelectedMessage();

    /// <summary>쪽지 목록에서 Enter 로도 상세 창을 연다(키보드 사용자).</summary>
    private void MessageListBox_OnKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter || MessageListBox.SelectedItem is null) return;
        e.Handled = true;
        OpenSelectedMessage();
    }

    private void OpenSelectedMessage()
    {
        if (MessageListBox.SelectedItem is not MessageRowVm row) return;
        var dlg = new MessageDetailWindow(_client, row, _messageBox == "sent", _currentUserId) { Owner = this };
        var result = dlg.ShowDialog();
        if (result == true && dlg.WasDeleted)
        {
            _messageRows.Remove(row);
            return;
        }
        // MessageDetailWindow 가 Loaded 시점에 이미 서버에 읽음 처리를 요청하고
        // row.IsRead 를 true 로 바꿔놓기 때문에, 여기서 "!row.IsRead" 로 감싸면
        // 다이얼로그가 닫힌 시점엔 항상 false 라 배지가 절대 안 줄어드는 버그가 있었다.
        // RecomputeMessageUnread 는 매번 호출해도 안전(멱등)하므로 무조건 재계산한다.
        if (_messageBox != "sent")
        {
            row.MarkReadLocally();
            RecomputeMessageUnread();
        }

        // 답장/전체회신/전달 — 상세창이 닫히며 요청한 액션을 이어서 Compose 로 연다
        if (dlg.RequestedAction != MessageDetailAction.None)
        {
            OpenMessageComposeForAction(dlg);
        }
    }

    private void OpenMessageComposeForAction(MessageDetailWindow dlg)
    {
        var composeDlg = new MessageComposeWindow(_client, _orgRoots) { Owner = this };
        switch (dlg.RequestedAction)
        {
            case MessageDetailAction.Reply:
                composeDlg.SetPrefill(dlg.ReplyRecipients(), dlg.ReplyTitle(), dlg.QuotedBody());
                break;
            case MessageDetailAction.ReplyAll:
                composeDlg.SetPrefill(dlg.ReplyAllRecipients(), dlg.ReplyTitle(), dlg.QuotedBody());
                break;
            case MessageDetailAction.Forward:
                composeDlg.SetPrefill(Array.Empty<string>(), dlg.ForwardTitle(), dlg.QuotedBody());
                break;
            default:
                return;
        }
        if (composeDlg.ShowDialog() == true)
        {
            if (_messageBox == "sent") _ = ReloadMessageBoxAsync();
            StatusBarSyncText.Text = "쪽지를 보냈습니다";
        }
    }

    private void OnClientMessagePushed(object? sender, MessageReceived e)
    {
        Dispatcher.BeginInvoke(() =>
        {
            // 받은쪽지함 카운트
            _messageUnread++;
            UpdateMessageBadge();
            // 받은쪽지함 화면에 있으면 즉시 prepend
            if (_messageBox == "inbox" && MessageTabContent.Visibility == Visibility.Visible)
            {
                _messageRows.Insert(0, MessageRowVm.FromReceived(e, _currentUserId));
            }
            // 토스트 + 사운드 — NotifyMessages 토글로 게이팅
            if (_settings.Current.NotifyMessages)
            {
                var preview = string.IsNullOrEmpty(e.Body) ? e.Title : $"{e.Title} — {e.Body}";
                if (preview.Length > 80) preview = preview.Substring(0, 80) + "…";
                if (!IsActive)
                {
                    App.ShowToast($"📨 {e.FromUserId} 님의 쪽지", preview);
                }
                else
                {
                    App.ShowInAppToast($"📨 {e.FromUserId} 님의 쪽지", preview, App.ToastLevel.Info);
                }
                if (_settings.Current.PlaySoundOnMessage)
                {
                    try { System.Media.SystemSounds.Asterisk.Play(); } catch { }
                }
            }
        });
    }

    private void UpdateMessageBadge()
    {
        if (_messageUnread <= 0)
        {
            MessageTabBadge.Visibility = Visibility.Collapsed;
        }
        else
        {
            MessageTabBadge.Visibility = Visibility.Visible;
            MessageTabBadgeText.Text = _messageUnread > 99 ? "99+" : _messageUnread.ToString();
        }
    }

    private void RecomputeMessageUnread()
    {
        _messageUnread = _messageRows.Count(r => !r.IsRead && !r.IsSentBox);
        UpdateMessageBadge();
    }

    // ════════ 인앱 토스트 ════════
    internal void ShowInAppToast(string title, string body, App.ToastLevel level, int durationMs)
    {
        Dispatcher.BeginInvoke(() =>
        {
            var (bg, fg, accent) = level switch
            {
                App.ToastLevel.Success => ("#1B5E20", "#FFFFFF", "#43A047"),
                App.ToastLevel.Warn    => ("#5D4037", "#FFFFFF", "#FFB300"),
                App.ToastLevel.Error   => ("#5D1A1A", "#FFFFFF", "#E53935"),
                _                       => ("#263238", "#FFFFFF", "#42A5F5"),
            };
            var card = new Border
            {
                Background = (System.Windows.Media.Brush)new System.Windows.Media.BrushConverter().ConvertFromString(bg)!,
                BorderBrush = (System.Windows.Media.Brush)new System.Windows.Media.BrushConverter().ConvertFromString(accent)!,
                BorderThickness = new Thickness(4, 0, 0, 0),
                CornerRadius = new System.Windows.CornerRadius(6),
                Padding = new Thickness(12, 8, 14, 10),
                Margin = new Thickness(0, 0, 0, 8),
                MinWidth = 240,
                MaxWidth = 320,
                Opacity = 0,
            };
            var panel = new StackPanel();
            panel.Children.Add(new TextBlock
            {
                Text = title,
                FontWeight = FontWeights.SemiBold,
                FontSize = 13,
                Foreground = (System.Windows.Media.Brush)new System.Windows.Media.BrushConverter().ConvertFromString(fg)!,
                TextTrimming = TextTrimming.CharacterEllipsis,
            });
            if (!string.IsNullOrWhiteSpace(body))
            {
                panel.Children.Add(new TextBlock
                {
                    Text = body,
                    FontSize = 12,
                    Foreground = (System.Windows.Media.Brush)new System.Windows.Media.BrushConverter().ConvertFromString(fg)!,
                    TextWrapping = TextWrapping.Wrap,
                    Margin = new Thickness(0, 2, 0, 0),
                    Opacity = 0.92,
                });
            }
            card.Child = panel;
            ToastContainer.Children.Add(card);

            // fade-in + fade-out 애니메이션 + 자동 제거
            var fadeIn = new System.Windows.Media.Animation.DoubleAnimation
            {
                From = 0, To = 1, Duration = TimeSpan.FromMilliseconds(180),
                EasingFunction = new System.Windows.Media.Animation.QuadraticEase { EasingMode = System.Windows.Media.Animation.EasingMode.EaseOut }
            };
            card.BeginAnimation(UIElement.OpacityProperty, fadeIn);

            var timer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(durationMs) };
            timer.Tick += (_, __) =>
            {
                timer.Stop();
                var fadeOut = new System.Windows.Media.Animation.DoubleAnimation
                {
                    From = 1, To = 0, Duration = TimeSpan.FromMilliseconds(220),
                };
                fadeOut.Completed += (_, _) => ToastContainer.Children.Remove(card);
                card.BeginAnimation(UIElement.OpacityProperty, fadeOut);
            };
            timer.Start();
        });
    }
}

public sealed class MessageRowVm : System.ComponentModel.INotifyPropertyChanged
{
    public string ServerMessageId { get; init; } = "";
    public string CounterpartLabel { get; init; } = "";
    public string Title { get; init; } = "";
    public string Body { get; init; } = "";
    public long SentAtMs { get; init; }
    public string SentAtText { get; init; } = "";
    public bool IsSentBox { get; init; }
    public bool HasAttachment { get; init; }
    public string AttachmentUploadId { get; init; } = "";
    /// <summary>첨부 파일 이름·크기(서버가 알려 준 경우).</summary>
    public string AttachmentFilename { get; init; } = "";
    public long AttachmentSize { get; init; }
    public Visibility AttachmentVisibility => HasAttachment ? Visibility.Visible : Visibility.Collapsed;

    /// <summary>답장/전체회신/전달용 원본 ID. 발신자(보낸사람).</summary>
    public string FromUserId { get; init; } = "";
    /// <summary>이 쪽지의 전체 수신자 목록 (INBOX/SENT 공통, 서버가 string_agg 로 채움).</summary>
    public IReadOnlyList<string> RecipientIds { get; init; } = Array.Empty<string>();

    private bool _isRead;
    public bool IsRead
    {
        get => _isRead;
        set
        {
            if (_isRead == value) return;
            _isRead = value;
            PropertyChanged?.Invoke(this, new System.ComponentModel.PropertyChangedEventArgs(nameof(IsRead)));
            PropertyChanged?.Invoke(this, new System.ComponentModel.PropertyChangedEventArgs(nameof(UnreadVisibility)));
            PropertyChanged?.Invoke(this, new System.ComponentModel.PropertyChangedEventArgs(nameof(IsUnreadInbox)));
            PropertyChanged?.Invoke(this, new System.ComponentModel.PropertyChangedEventArgs(nameof(AccessibleName)));
        }
    }

    /// <summary>화면 읽기·UI 자동화용 이름: "읽지 않음, 보낸 사람, 제목, 첨부 있음, 날짜".</summary>
    public string AccessibleName =>
        string.Join(", ", new[]
        {
            IsUnreadInbox ? "읽지 않음" : "",
            CounterpartLabel,
            Title,
            HasAttachment ? "첨부 있음" : "",
            SentAtText,
        }.Where(s => !string.IsNullOrEmpty(s)));

    public override string ToString() => AccessibleName;
    public Visibility UnreadVisibility => (!IsSentBox && !IsRead) ? Visibility.Visible : Visibility.Collapsed;

    /// <summary>받은 쪽지함의 안 읽은 쪽지(제목 강조용).</summary>
    public bool IsUnreadInbox => !IsSentBox && !IsRead;

    /// <summary>목록용 본문 미리보기 — 줄바꿈을 공백으로 바꾼 앞부분.</summary>
    public string BodyPreview => OsoriClient.Core.TextPreview.OneLine(Body, 60);

    public event System.ComponentModel.PropertyChangedEventHandler? PropertyChanged;

    public void MarkReadLocally() => IsRead = true;

    public static MessageRowVm FromEntry(MessageEntry e, bool sentBox, string myUserId)
    {
        var counterpart = sentBox
            ? (e.RecipientIds.Count == 1 ? e.RecipientIds[0] : $"{e.RecipientIds.FirstOrDefault() ?? ""} 외 {Math.Max(0, e.RecipientIds.Count - 1)}명")
            : e.FromUserId;
        return new MessageRowVm
        {
            ServerMessageId = e.ServerMessageId,
            CounterpartLabel = counterpart,
            Title = e.Title,
            Body = e.Body,
            SentAtMs = e.SentAt,
            SentAtText = FormatSentAt(e.SentAt),
            IsSentBox = sentBox,
            HasAttachment = e.HasAttachment,
            AttachmentUploadId = e.AttachmentUploadId,
            AttachmentFilename = e.AttachmentFilename,
            AttachmentSize = e.AttachmentSize,
            FromUserId = e.FromUserId,
            RecipientIds = e.RecipientIds.ToList(),
            _isRead = sentBox || e.ReadAt > 0,
        };
    }

    public static MessageRowVm FromReceived(MessageReceived e, string myUserId) => new()
    {
        ServerMessageId = e.ServerMessageId,
        CounterpartLabel = e.FromUserId,
        Title = e.Title,
        Body = e.Body,
        SentAtMs = e.SentAt,
        SentAtText = FormatSentAt(e.SentAt),
        IsSentBox = false,
        FromUserId = e.FromUserId,
        RecipientIds = new List<string> { myUserId },
        HasAttachment = !string.IsNullOrEmpty(e.AttachmentUploadId),
        AttachmentUploadId = e.AttachmentUploadId,
        AttachmentFilename = e.AttachmentFilename,
        AttachmentSize = e.AttachmentSize,
        _isRead = false,
    };

    private static string FormatSentAt(long ms)
    {
        // 대화 목록과 같은 규칙: 오늘 HH:mm / 올해 MM-dd / 그 전 yyyy-MM-dd
        return OsoriClient.Core.TalkListFormat.ListTime(ms);
    }
}

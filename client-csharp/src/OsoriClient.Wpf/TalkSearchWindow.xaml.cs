using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Input;
using Osori.Proto.V1;
using OsoriClient.Core;
using OsoriClient.Wpf.Helpers;

namespace OsoriClient.Wpf;

/// <summary>
/// 대화 검색 창. 키워드 + Enter 또는 [검색] 버튼.
/// 서버가 "내가 멤버인 대화방"의 메시지를 찾아 방 ID 와 함께 돌려준다(hits).
/// 결과 더블클릭/Enter 시 그 방을 소유자(MainWindow)가 연다.
/// 옛 서버(hits 없이 talks 만 주는 경우)면 상대 ID 기준으로 연다.
/// </summary>
public partial class TalkSearchWindow : Window
{
    private readonly MessengerClient _client;
    private readonly string _myUserId;
    private readonly ObservableCollection<ResultRow> _results = new();

    /// <summary>선택된 결과의 방 ID(방 검색 결과일 때).</summary>
    public string? SelectedRoomId { get; private set; }

    /// <summary>선택된 결과의 방 표시 이름.</summary>
    public string? SelectedRoomTitle { get; private set; }

    /// <summary>옛 1:1 결과에서 선택된 상대방 ID(방 ID 가 없을 때만).</summary>
    public string? SelectedCounterpart { get; private set; }

    public TalkSearchWindow(MessengerClient client, string myUserId)
    {
        InitializeComponent();
        WindowCornerHelper.ApplyRoundCorners(this);
        _client = client;
        _myUserId = myUserId;
        ResultsListBox.ItemsSource = _results;
        Loaded += (_, _) => KeywordTextBox.Focus();
    }

    public sealed class ResultRow
    {
        public string ServerTalkId { get; init; } = "";
        public string RoomId { get; init; } = "";
        public string Title { get; init; } = "";
        public string FromUserId { get; init; } = "";
        public string Text { get; init; } = "";
        public long ServerTs { get; init; }
        public string TimestampText => DateTimeOffset.FromUnixTimeMilliseconds(ServerTs).LocalDateTime.ToString("yyyy-MM-dd HH:mm");
        public string Counterpart { get; init; } = "";
        /// <summary>본문에서 강조할 검색어.</summary>
        public string Keyword { get; init; } = "";
        public string SenderText => string.IsNullOrEmpty(FromUserId) ? "" : $" · {FromUserId}";
        /// <summary>화면 읽기·UI 자동화용 이름: "방 이름, 보낸 사람, 본문, 시각".</summary>
        public string AccessibleName =>
            string.Join(", ", new[] { Title, FromUserId, Text, TimestampText }.Where(s => !string.IsNullOrEmpty(s)));
        public override string ToString() => AccessibleName;
    }

    private async Task RunSearchAsync()
    {
        var keyword = KeywordTextBox.Text.Trim();
        EmptyPanel.Visibility = Visibility.Collapsed;
        if (keyword.Length == 0)
        {
            _results.Clear();
            StatusTextBlock.Text = "검색어를 입력하세요.";
            return;
        }

        SearchButton.IsEnabled = false;
        StatusTextBlock.Text = "검색 중...";
        _results.Clear();

        try
        {
            var resp = await _client.SearchMessagesAsync(keyword, limit: 100, ct: CancellationToken.None);
            if (resp.Result != TalkSearchResponse.Types.Result.Ok)
            {
                StatusTextBlock.Text = ResultMessages.Search(resp.Result);
                return;
            }

            foreach (var h in resp.Hits)
            {
                var title = MainWindow.ResolveRoomDisplayName(h.RoomName, h.RoomNameIsCustom, h.MemberIds, _myUserId, h.RoomId);
                _results.Add(new ResultRow
                {
                    ServerTalkId = h.ServerTalkId,
                    RoomId = h.RoomId,
                    Title = title,
                    FromUserId = h.FromUserId,
                    Text = h.Text,
                    ServerTs = h.ServerTs,
                    Keyword = keyword,
                });
            }

            foreach (var m in resp.Talks)
            {
                var counterpart = m.FromUserId == _myUserId ? m.ToUserId : m.FromUserId;
                _results.Add(new ResultRow
                {
                    ServerTalkId = m.ServerTalkId,
                    Title = counterpart,
                    FromUserId = m.FromUserId,
                    Text = m.Text,
                    ServerTs = m.ServerTs,
                    Keyword = keyword,
                    Counterpart = counterpart,
                });
            }

            if (_results.Count == 0)
            {
                EmptyTitleText.Text = $"'{keyword}' 검색 결과가 없습니다";
                EmptyPanel.Visibility = Visibility.Visible;
                StatusTextBlock.Text = "결과 0건";
                return;
            }

            var moreTail = resp.HasMore ? " (최근 100건까지 표시)" : "";
            StatusTextBlock.Text = $"'{keyword}' 결과 {_results.Count}건{moreTail} — 더블클릭 또는 Enter 로 대화방 열기";
        }
        catch (Exception ex)
        {
            StatusTextBlock.Text = $"검색 중 오류가 났습니다: {ex.Message}";
        }
        finally
        {
            SearchButton.IsEnabled = true;
        }
    }

    private void TitleCloseButton_OnClick(object sender, RoutedEventArgs e)
    {
        Close();
    }

    private async void SearchButton_OnClick(object sender, RoutedEventArgs e)
    {
        await RunSearchAsync();
    }

    private async void KeywordTextBox_OnKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            await RunSearchAsync();
        }
        else if (e.Key == Key.Down && _results.Count > 0)
        {
            e.Handled = true;
            ResultsListBox.SelectedIndex = 0;
            (ResultsListBox.ItemContainerGenerator.ContainerFromIndex(0) as UIElement)?.Focus();
        }
    }

    private void ResultsListBox_OnMouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        AcceptSelection();
    }

    private void ResultsListBox_OnKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            AcceptSelection();
        }
    }

    private void Window_OnKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            e.Handled = true;
            Close();
        }
    }

    private void AcceptSelection()
    {
        if (ResultsListBox.SelectedItem is not ResultRow row) return;
        if (!string.IsNullOrEmpty(row.RoomId))
        {
            SelectedRoomId = row.RoomId;
            SelectedRoomTitle = row.Title;
        }
        else if (!string.IsNullOrEmpty(row.Counterpart))
        {
            SelectedCounterpart = row.Counterpart;
        }
        else
        {
            return;
        }
        DialogResult = true;
        Close();
    }
}

using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Input;
using Osori.Proto.V1;
using OsoriClient.Core;
using OsoriClient.Wpf.Helpers;

namespace OsoriClient.Wpf;

/// <summary>
/// 메시지 검색 창. 키워드 + Enter 또는 [검색] 버튼.
/// 결과 더블클릭 시 해당 상대 ID 를 소유자 MainWindow 로 전달해 대화창을 연다.
/// </summary>
public partial class TalkSearchWindow : Window
{
    private readonly MessengerClient _client;
    private readonly string _myUserId;
    private readonly ObservableCollection<ResultRow> _results = new();

    /// <summary>더블클릭으로 선택된 상대방 ID. 소유자(MainWindow)가 close 후 사용.</summary>
    public string? SelectedCounterpart { get; private set; }

    public TalkSearchWindow(MessengerClient client, string myUserId)
    {
        InitializeComponent();
        WindowCornerHelper.ApplyRoundCorners(this);
        _client = client;
        _myUserId = myUserId;
        ResultsListBox.ItemsSource = _results;
    }

    public sealed class ResultRow
    {
        public string ServerTalkId { get; init; } = "";
        public string FromUserId { get; init; } = "";
        public string ToUserId { get; init; } = "";
        public string Text { get; init; } = "";
        public long ServerTs { get; init; }
        public string TimestampText => DateTimeOffset.FromUnixTimeMilliseconds(ServerTs).LocalDateTime.ToString("yyyy-MM-dd HH:mm");
        public string Counterpart { get; init; } = "";
    }

    private async Task RunSearchAsync()
    {
        var keyword = KeywordTextBox.Text.Trim();
        if (keyword.Length == 0)
        {
            _results.Clear();
            StatusTextBlock.Text = "키워드를 입력하세요.";
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
                StatusTextBlock.Text = $"검색 실패: {resp.Result}";
                return;
            }

            foreach (var m in resp.Talks)
            {
                var counterpart = m.FromUserId == _myUserId ? m.ToUserId : m.FromUserId;
                _results.Add(new ResultRow
                {
                    ServerTalkId = m.ServerTalkId,
                    FromUserId = m.FromUserId,
                    ToUserId = m.ToUserId,
                    Text = m.Text,
                    ServerTs = m.ServerTs,
                    Counterpart = counterpart,
                });
            }
            var moreTail = resp.HasMore ? " (더 많음)" : "";
            StatusTextBlock.Text = $"'{keyword}' 결과 {resp.MatchedCount}건{moreTail}";
        }
        catch (Exception ex)
        {
            StatusTextBlock.Text = $"오류: {ex.Message}";
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
    }

    private void ResultsListBox_OnMouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (ResultsListBox.SelectedItem is ResultRow row && !string.IsNullOrEmpty(row.Counterpart))
        {
            SelectedCounterpart = row.Counterpart;
            DialogResult = true;
            Close();
        }
    }
}

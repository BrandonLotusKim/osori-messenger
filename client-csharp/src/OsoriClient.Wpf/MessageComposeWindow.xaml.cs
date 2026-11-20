using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Input;
using Osori.Proto.V1;
using OsoriClient.Core;
using OsoriClient.Wpf.Helpers;

namespace OsoriClient.Wpf;

/// <summary>
/// 쪽지 작성 다이얼로그. 수신자(콤마 구분) + 제목 + 본문.
/// 조직도 버튼으로 멤버 선택 가능. ShowDialog true = 전송 성공.
/// </summary>
public partial class MessageComposeWindow : Window
{
    private readonly MessengerClient _client;
    private readonly IReadOnlyList<OrgTreeNode>? _orgRoots;

    public MessageComposeWindow(MessengerClient client, IReadOnlyList<OrgTreeNode>? orgRoots)
    {
        InitializeComponent();
        WindowCornerHelper.ApplyRoundCorners(this);
        _client = client ?? throw new ArgumentNullException(nameof(client));
        _orgRoots = orgRoots;
        Loaded += (_, _) => TitleBox.Focus();
        // 키보드 단축키: Esc=취소, Ctrl+Enter=보내기
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape)
            {
                DialogResult = false; Close(); e.Handled = true;
            }
            else if (e.Key == Key.Enter && Keyboard.Modifiers.HasFlag(ModifierKeys.Control))
            {
                SendButton_OnClick(this, new RoutedEventArgs()); e.Handled = true;
            }
        };
    }

    /// <summary>외부에서 호출 — 수신자 텍스트박스를 미리 채움 (조직도 우클릭 → 쪽지 보내기 등).</summary>
    public void SetInitialRecipients(System.Collections.Generic.IEnumerable<string> userIds)
    {
        RecipientsBox.Text = string.Join(", ", userIds);
    }

    /// <summary>OC-D04 — 답장/전체회신/전달 시 MainWindow 가 호출. 수신자/제목/인용본문을 미리 채운다.</summary>
    public void SetPrefill(System.Collections.Generic.IEnumerable<string> recipients, string title, string? quotedBody)
    {
        RecipientsBox.Text = string.Join(", ", recipients);
        TitleBox.Text = title;
        if (!string.IsNullOrEmpty(quotedBody))
        {
            BodyBox.Text = quotedBody;
        }
        Loaded += (_, _) => { BodyBox.Focus(); BodyBox.CaretIndex = 0; };
    }

    private void CloseButton_OnClick(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }

    private void CancelButton_OnClick(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }

    private void PickRecipients_OnClick(object sender, RoutedEventArgs e)
    {
        if (_orgRoots is null)
        {
            StatusText.Text = "조직도가 아직 로드되지 않았습니다";
            return;
        }
        var picker = new MessageRecipientPicker(_orgRoots, ParseRecipients(RecipientsBox.Text)) { Owner = this };
        if (picker.ShowDialog() == true)
        {
            RecipientsBox.Text = string.Join(", ", picker.SelectedUserIds);
        }
    }

    private async void SendButton_OnClick(object sender, RoutedEventArgs e)
    {
        var recipients = ParseRecipients(RecipientsBox.Text).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        var title = TitleBox.Text?.Trim() ?? "";
        var body = BodyBox.Text ?? "";

        if (recipients.Count == 0)
        {
            StatusText.Text = "수신자를 1명 이상 입력하세요";
            RecipientsBox.Focus();
            return;
        }
        if (string.IsNullOrWhiteSpace(title))
        {
            StatusText.Text = "제목을 입력하세요";
            TitleBox.Focus();
            return;
        }

        SendButton.IsEnabled = false;
        StatusText.Text = "보내는 중...";
        try
        {
            var ack = await _client.SendMessageAsync(recipients, title, body, null);
            if (ack.Result == MessageSendAck.Types.Result.Ok)
            {
                DialogResult = true;
                Close();
                return;
            }
            StatusText.Text = $"보내기 실패: {ack.Result}";
        }
        catch (Exception ex)
        {
            StatusText.Text = $"보내기 오류: {ex.Message}";
        }
        finally
        {
            SendButton.IsEnabled = true;
        }
    }

    private static List<string> ParseRecipients(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return new List<string>();
        return text
            .Split(new[] { ',', ';', ' ', '\n', '\r', '\t' }, StringSplitOptions.RemoveEmptyEntries)
            .Select(s => s.Trim())
            .Where(s => s.Length > 0)
            .ToList();
    }
}

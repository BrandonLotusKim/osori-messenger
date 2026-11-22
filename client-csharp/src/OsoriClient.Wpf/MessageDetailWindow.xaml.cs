using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Windows;
using Microsoft.Win32;
using Osori.Proto.V1;
using OsoriClient.Core;
using OsoriClient.Wpf.Helpers;

namespace OsoriClient.Wpf;

/// <summary>답장/전체회신/전달 요청 종류. MessageDetailWindow 가 닫힐 때 호출자(MainWindow)에게 전달.</summary>
public enum MessageDetailAction
{
    None,
    Reply,
    ReplyAll,
    Forward,
}

/// <summary>
/// 쪽지 상세창. 열자마자 자동 읽음 처리(수신함일 때). 삭제 시 WasDeleted=true 로 닫힘.
///: 답장/전체회신/전달 버튼 + 첨부파일 다운로드 추가.
/// </summary>
public partial class MessageDetailWindow : Window
{
    private readonly MessengerClient _client;
    private readonly MessageRowVm _row;
    private readonly bool _isSentBox;
    private readonly string _myUserId;
    public bool WasDeleted { get; private set; }
    public MessageDetailAction RequestedAction { get; private set; } = MessageDetailAction.None;

    public MessageDetailWindow(MessengerClient client, MessageRowVm row, bool isSentBox, string myUserId)
    {
        InitializeComponent();
        WindowCornerHelper.ApplyRoundCorners(this);
        _client = client ?? throw new ArgumentNullException(nameof(client));
        _row = row ?? throw new ArgumentNullException(nameof(row));
        _isSentBox = isSentBox;
        _myUserId = myUserId ?? "";

        HeaderLine.Text = isSentBox ? $"받는 사람 · {row.CounterpartLabel}" : $"보낸 사람 · {row.CounterpartLabel}";
        SentAtText.Text = row.SentAtText;
        TitleText.Text = row.Title;
        BodyText.Text = row.Body;

        AttachmentChip.Visibility = row.AttachmentVisibility;
        AttachmentStatusText.Text = AttachmentLabel();
        AttachmentStatusText.ToolTip = AttachmentStatusText.Text;

        // 받는 사람 전체 목록 — 목록의 "a 외 N명" 대신 모두 보여 준다.
        // 보낸쪽지함은 첫 줄(받는 사람)에, 받은쪽지함은 보낸 사람 아래 둘째 줄에.
        var recipients = row.RecipientIds.Where(r => !string.IsNullOrWhiteSpace(r)).ToList();
        var recipientsText = recipients.Count == 0
            ? string.Empty
            : $"받는 사람 ({recipients.Count}명) · {string.Join(", ", recipients)}";
        if (isSentBox && recipients.Count > 0)
        {
            HeaderLine.Text = $"받는 사람 ({recipients.Count}명)";
            RecipientsLine.Text = string.Join(", ", recipients);
            RecipientsLine.Visibility = Visibility.Visible;
        }
        else
        {
            RecipientsLine.Text = recipientsText;
            RecipientsLine.Visibility = recipients.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        }

        // 보낸쪽지함(내가 쓴 쪽지)엔 답장/전체회신이 성립하지 않음 — 전달만 허용
        if (isSentBox)
        {
            ReplyButton.IsEnabled = false;
            ReplyAllButton.IsEnabled = false;
        }
        else
        {
            ReplyButton.IsEnabled = true;
            // 나 말고 다른 수신자가 더 있을 때만(그룹 쪽지) 전체회신 활성화 — 1:1 쪽지는 답장과 동일해지므로 비활성
            var othersBesidesMe = row.RecipientIds.Any(r => !string.Equals(r, _myUserId, StringComparison.OrdinalIgnoreCase));
            ReplyAllButton.IsEnabled = othersBesidesMe;
        }

        Loaded += MessageDetailWindow_OnLoaded;
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == System.Windows.Input.Key.Escape)
            {
                DialogResult = DialogResult ?? false; Close(); e.Handled = true;
            }
        };
    }

    private async void MessageDetailWindow_OnLoaded(object sender, RoutedEventArgs e)
    {
        if (_isSentBox || _row.IsRead || string.IsNullOrEmpty(_row.ServerMessageId)) return;
        try
        {
            var ack = await _client.MarkMessageReadAsync(_row.ServerMessageId);
            if (ack.Result == MessageMarkReadAck.Types.Result.Ok)
            {
                _row.MarkReadLocally();
            }
        }
        catch (Exception ex)
        {
            StatusText.Text = $"읽음 처리 실패: {ex.Message}";
        }
    }

    private void ReplyButton_OnClick(object sender, RoutedEventArgs e) => CloseWithAction(MessageDetailAction.Reply);
    private void ReplyAllButton_OnClick(object sender, RoutedEventArgs e) => CloseWithAction(MessageDetailAction.ReplyAll);
    private void ForwardButton_OnClick(object sender, RoutedEventArgs e) => CloseWithAction(MessageDetailAction.Forward);

    private void CloseWithAction(MessageDetailAction action)
    {
        RequestedAction = action;
        DialogResult = false;
        Close();
    }

    /// <summary>답장 대상 = 원 발신자 1명.</summary>
    public IReadOnlyList<string> ReplyRecipients() => new List<string> { _row.FromUserId };

    /// <summary>전체회신 대상 = 원 발신자 + 나를 제외한 원 수신자 전원.</summary>
    public IReadOnlyList<string> ReplyAllRecipients()
    {
        var set = new List<string> { _row.FromUserId };
        foreach (var r in _row.RecipientIds)
        {
            if (!string.Equals(r, _myUserId, StringComparison.OrdinalIgnoreCase) && !set.Contains(r, StringComparer.OrdinalIgnoreCase))
            {
                set.Add(r);
            }
        }
        return set;
    }

    public string ReplyTitle() => _row.Title.StartsWith("답장: ", StringComparison.Ordinal) ? _row.Title : $"답장: {_row.Title}";
    public string ForwardTitle() => _row.Title.StartsWith("전달: ", StringComparison.Ordinal) ? _row.Title : $"전달: {_row.Title}";

    public string QuotedBody()
    {
        var quotedLines = (_row.Body ?? "").Split('\n').Select(l => "> " + l);
        return $"\n\n---- 원본 쪽지 ({_row.CounterpartLabel}, {_row.SentAtText}) ----\n" + string.Join("\n", quotedLines);
    }

    /// <summary>"파일명 (크기)" — 서버가 이름을 못 준 옛 쪽지는 "첨부파일 1개".</summary>
    private string AttachmentLabel() => string.IsNullOrEmpty(_row.AttachmentFilename)
        ? "첨부파일 1개"
        : $"{_row.AttachmentFilename} ({TalkListFormat.FileSize(_row.AttachmentSize)})";

    private async void AttachmentDownloadButton_OnClick(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrEmpty(_row.AttachmentUploadId)) return;
        AttachmentDownloadButton.IsEnabled = false;
        var progress = new Progress<MessengerClient.DownloadProgress>(p =>
        {
            var pct = p.TotalBytes > 0 ? (int)Math.Round(p.Fraction * 100) : 0;
            AttachmentStatusText.Text = $"다운로드 중... {pct}%";
        });
        try
        {
            AttachmentStatusText.Text = "다운로드 시작...";
            var file = await _client.DownloadFileAsync(_row.AttachmentUploadId, progress, CancellationToken.None);
            var save = new SaveFileDialog { Title = "첨부파일 저장", FileName = file.Filename, Filter = "모든 파일|*.*" };
            if (save.ShowDialog(this) != true)
            {
                AttachmentStatusText.Text = AttachmentLabel();
                return;
            }
            await System.IO.File.WriteAllBytesAsync(save.FileName, file.Bytes);
            AttachmentStatusText.Text = $"저장됨: {save.FileName}";
            System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{save.FileName}\"");
        }
        catch (Exception ex)
        {
            AttachmentStatusText.Text = $"다운로드 실패: {ex.Message}";
        }
        finally
        {
            AttachmentDownloadButton.IsEnabled = true;
        }
    }

    private async void DeleteButton_OnClick(object sender, RoutedEventArgs e)
    {
        if (!AppDialog.Confirm(this, "이 쪽지를 삭제하시겠습니까?", "쪽지 삭제", yes: "삭제", no: "취소")) return;

        DeleteButton.IsEnabled = false;
        try
        {
            var ack = await _client.DeleteMessageAsync(_row.ServerMessageId);
            if (ack.Result == MessageDeleteAck.Types.Result.Ok)
            {
                WasDeleted = true;
                DialogResult = true;
                Close();
                return;
            }
            StatusText.Text = $"삭제 실패: {ack.Result}";
        }
        catch (Exception ex)
        {
            StatusText.Text = $"삭제 오류: {ex.Message}";
        }
        finally
        {
            DeleteButton.IsEnabled = true;
        }
    }

    private void CloseButton_OnClick(object sender, RoutedEventArgs e)
    {
        DialogResult = DialogResult ?? false;
        Close();
    }
}

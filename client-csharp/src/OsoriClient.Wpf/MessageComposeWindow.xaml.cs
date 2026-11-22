using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
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
        RecipientChips.ItemsSource = _chips;
        UpdateRecipientHint();
        // 받는 사람이 비어 있으면 받는 사람 칸부터, 있으면 제목부터.
        Loaded += (_, _) => { if (_chips.Count == 0) RecipientsBox.Focus(); else TitleBox.Focus(); };
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

    /// <summary>받는 사람 칩 1개.</summary>
    public sealed record RecipientChip(string UserId, string Label, bool IsKnown)
    {
        public string ToolTip => IsKnown ? UserId : $"{UserId} — 조직도에 없는 아이디입니다";
        public string RemoveName => $"{Label} 빼기";
    }

    private readonly ObservableCollection<RecipientChip> _chips = new();
    private string? _attachmentPath;

    private const long MaxAttachmentBytes = 32L * 1024 * 1024;

    /// <summary>현재 받는 사람(칩 + 아직 칩으로 안 바뀐 입력칸 내용).</summary>
    public IReadOnlyList<string> CurrentRecipients =>
        RecipientTokens.Merge(_chips.Select(c => c.UserId), RecipientTokens.Parse(RecipientsBox.Text));

    private void SetChips(IEnumerable<string> userIds)
    {
        _chips.Clear();
        AddChips(userIds);
    }

    private void AddChips(IEnumerable<string> userIds)
    {
        var merged = RecipientTokens.Merge(_chips.Select(c => c.UserId), userIds);
        foreach (var id in merged.Skip(_chips.Count))
        {
            _chips.Add(new RecipientChip(id, RecipientTokens.Label(id, _orgRoots), RecipientTokens.IsKnown(id, _orgRoots)));
        }
        UpdateRecipientHint();
    }

    private void CommitTypedRecipients()
    {
        var typed = RecipientTokens.Parse(RecipientsBox.Text);
        if (typed.Count == 0) return;
        RecipientsBox.Text = "";
        AddChips(typed);
    }

    private void UpdateRecipientHint()
    {
        RecipientsBox.MinWidth = _chips.Count == 0 ? 200 : 80;
    }

    private void RecipientsBox_OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        var hasText = !string.IsNullOrWhiteSpace(RecipientsBox.Text);
        if (e.Key == Key.Enter && !Keyboard.Modifiers.HasFlag(ModifierKeys.Control))
        {
            CommitTypedRecipients();
            e.Handled = true;
        }
        else if ((e.Key is Key.Space or Key.OemComma or Key.OemSemicolon) && hasText)
        {
            CommitTypedRecipients();
            e.Handled = true;
        }
        else if (e.Key == Key.Tab && hasText)
        {
            CommitTypedRecipients();
        }
        else if (e.Key == Key.Back && RecipientsBox.Text.Length == 0 && _chips.Count > 0)
        {
            _chips.RemoveAt(_chips.Count - 1);
            UpdateRecipientHint();
            e.Handled = true;
        }
    }

    private void RecipientsBox_OnTextChanged(object sender, System.Windows.Controls.TextChangedEventArgs e)
    {
        // 붙여넣기 등으로 구분자가 들어오면 바로 칩으로 바꾼다(마지막 토큰은 입력 중일 수 있어 남김).
        var text = RecipientsBox.Text;
        var cut = text.LastIndexOfAny(new[] { ',', ';', '\n', '\r', '\t' });
        if (cut < 0) return;
        var done = RecipientTokens.Parse(text[..cut]);
        RecipientsBox.Text = text[(cut + 1)..].TrimStart();
        RecipientsBox.CaretIndex = RecipientsBox.Text.Length;
        AddChips(done);
    }

    private void RecipientsBox_OnLostKeyboardFocus(object sender, KeyboardFocusChangedEventArgs e) => CommitTypedRecipients();

    private void RecipientsFrame_OnMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        RecipientsBox.Focus();
        RecipientsBox.CaretIndex = RecipientsBox.Text.Length;
    }

    private void RemoveChip_OnClick(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: string id })
        {
            var chip = _chips.FirstOrDefault(c => string.Equals(c.UserId, id, StringComparison.OrdinalIgnoreCase));
            if (chip is not null) _chips.Remove(chip);
            UpdateRecipientHint();
            RecipientsBox.Focus();
        }
    }

    private void AttachButton_OnClick(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFileDialog { Title = "파일 첨부", Filter = "모든 파일|*.*", Multiselect = false };
        if (dialog.ShowDialog(this) != true) return;
        SetAttachment(dialog.FileName);
    }

    /// <summary>첨부 파일 지정(자동화·테스트에서도 사용). 크기 제한을 넘으면 거절하고 false.</summary>
    public bool SetAttachment(string? path)
    {
        if (string.IsNullOrEmpty(path))
        {
            _attachmentPath = null;
            AttachmentChip.Visibility = Visibility.Collapsed;
            return true;
        }
        var info = new System.IO.FileInfo(path);
        if (!info.Exists || info.Length == 0 || info.Length > MaxAttachmentBytes)
        {
            StatusText.Text = info.Exists && info.Length > MaxAttachmentBytes
                ? "첨부는 32MB 이하만 가능합니다"
                : "첨부할 수 없는 파일입니다";
            return false;
        }
        _attachmentPath = path;
        AttachmentNameText.Text = $"{info.Name} ({FormatSize(info.Length)})";
        AttachmentChip.ToolTip = path;
        AttachmentChip.Visibility = Visibility.Visible;
        StatusText.Text = "";
        return true;
    }

    private void RemoveAttachment_OnClick(object sender, RoutedEventArgs e) => SetAttachment(null);

    private static string FormatSize(long bytes) => bytes switch
    {
        < 1024 => $"{bytes} B",
        < 1024 * 1024 => $"{bytes / 1024.0:0.0} KB",
        _ => $"{bytes / (1024.0 * 1024.0):0.0} MB",
    };

    /// <summary>외부에서 호출 — 받는 사람 칩을 미리 채움 (조직도 우클릭 → 쪽지 보내기 등).</summary>
    public void SetInitialRecipients(System.Collections.Generic.IEnumerable<string> userIds)
    {
        SetChips(userIds);
    }

    /// <summary>OC-D04 — 답장/전체회신/전달 시 MainWindow 가 호출. 수신자/제목/인용본문을 미리 채운다.</summary>
    public void SetPrefill(System.Collections.Generic.IEnumerable<string> recipients, string title, string? quotedBody)
    {
        SetChips(recipients);
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
        var picker = new MessageRecipientPicker(_orgRoots, CurrentRecipients) { Owner = this };
        if (picker.ShowDialog() == true)
        {
            RecipientsBox.Text = "";
            SetChips(picker.SelectedUserIds);
        }
    }

    private async void SendButton_OnClick(object sender, RoutedEventArgs e)
    {
        CommitTypedRecipients();
        var recipients = CurrentRecipients.ToList();
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
        AttachButton.IsEnabled = false;
        StatusText.Text = "보내는 중...";
        try
        {
            string? attachmentId = null;
            if (_attachmentPath is not null)
            {
                // 쪽지 첨부는 "나 자신"을 받는 사람으로 올려 둔다(서버는 이때 파일 알림을 보내지 않는다).
                // 받는 사람들의 다운로드 권한은 서버가 쪽지 수신자 목록으로 판단한다.
                StatusText.Text = "첨부 올리는 중...";
                var up = await _client.UploadFileAsync(_attachmentPath, _client.UserId ?? "");
                if (up.Result != FileUploadCompleteAck.Types.Result.Ok)
                {
                    StatusText.Text = "첨부를 올리지 못했습니다";
                    return;
                }
                attachmentId = up.UploadId;
                StatusText.Text = "보내는 중...";
            }
            var ack = await _client.SendMessageAsync(recipients, title, body, attachmentId);
            if (ack.Result == MessageSendAck.Types.Result.Ok)
            {
                DialogResult = true;
                Close();
                return;
            }
            StatusText.Text = $"보내지 못했습니다: {ResultMessages.MessageSend(ack.Result)}";
        }
        catch (Exception ex)
        {
            StatusText.Text = $"보내기 오류: {ex.Message}";
        }
        finally
        {
            SendButton.IsEnabled = true;
            AttachButton.IsEnabled = true;
        }
    }
}

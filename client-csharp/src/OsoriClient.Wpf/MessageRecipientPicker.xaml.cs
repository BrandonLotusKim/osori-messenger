using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Data;
using OsoriClient.Core;
using OsoriClient.Wpf.Helpers;

namespace OsoriClient.Wpf;

/// <summary>
/// 멤버 선택 다이얼로그. 조직도 트리를 flatten 해서 체크박스 리스트 + 필터.
/// </summary>
public partial class MessageRecipientPicker : Window
{
    private readonly ObservableCollection<MemberItem> _all = new();
    private readonly ICollectionView _view;

    public IReadOnlyList<string> SelectedUserIds =>
        _all.Where(m => m.IsChecked).Select(m => m.UserId).ToList();

    public MessageRecipientPicker(
        IReadOnlyList<OrgTreeNode> roots,
        IEnumerable<string> preChecked,
        IEnumerable<string>? excludeUserIds = null)
    {
        InitializeComponent();
        WindowCornerHelper.ApplyRoundCorners(this);
        var pre = new HashSet<string>(preChecked, StringComparer.OrdinalIgnoreCase);
        // 2026-09-17 — "초대하기" 에서 이미 이 방에 있는 사람을 목록에서 아예 빼기 위해 추가.
        // 예전엔 이미 있는 사람이 미리 체크된 채로 보여서, 사용자가 새로 한 명만 고르고 눌러도
        // 이미 체크돼 있던 기존 멤버들까지 같이 제출돼 "이미 있어서 초대 안 됨" 안내가 뜨고,
        // 정작 새로 고른 사람은 조용히(토스트만) 초대돼서 "초대가 안 된 것처럼 보이는데 실제로는
        // 된 것 같다"는 혼란을 일으켰다.
        var exclude = new HashSet<string>(excludeUserIds ?? Enumerable.Empty<string>(), StringComparer.OrdinalIgnoreCase);
        // 같은 부서 멤버끼리 묶이도록 OrgPath 우선 정렬 + 같은 부서 안에선 이름 순
        var ordered = Flatten(roots)
            .Where(m => !exclude.Contains(m.UserId))
            .OrderBy(m => m.OrgPath, StringComparer.OrdinalIgnoreCase)
            .ThenBy(m => m.DisplayName, StringComparer.OrdinalIgnoreCase);
        foreach (var member in ordered)
        {
            member.IsChecked = pre.Contains(member.UserId);
            member.PropertyChanged += (_, __) => UpdateSelectedCount();
            _all.Add(member);
        }
        _view = CollectionViewSource.GetDefaultView(_all);
        // OrgPath 별 그룹핑 — UI 에 부서 헤더 자동 생성
        _view.GroupDescriptions.Clear();
        _view.GroupDescriptions.Add(new PropertyGroupDescription(nameof(MemberItem.OrgPath)));
        MemberList.ItemsSource = _view;
        UpdateSelectedCount();
        Loaded += (_, _) => FilterBox.Focus();
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == System.Windows.Input.Key.Escape)
            {
                DialogResult = false; Close(); e.Handled = true;
            }
            else if (e.Key == System.Windows.Input.Key.Enter &&
                     System.Windows.Input.Keyboard.Modifiers.HasFlag(System.Windows.Input.ModifierKeys.Control))
            {
                OkButton_OnClick(this, new RoutedEventArgs()); e.Handled = true;
            }
        };
    }

    private string _noneText = "선택된 수신자 없음";
    private bool _requireSelection;

    /// <summary>
    /// 같은 선택기를 다른 용도(새 대화 상대 고르기 등)로 쓸 때 제목·확인 버튼·안내 문구를 바꾼다.
    /// </summary>
    public void SetPurpose(string title, string okText, string noneText)
    {
        Title = title;
        HeaderTitleText.Text = title;
        OkButton.Content = okText;
        _noneText = noneText;
        _requireSelection = true; // 이 용도에선 한 명 이상 골라야 확인 가능
        UpdateSelectedCount();
    }

    private void UpdateSelectedCount()
    {
        var n = _all.Count(m => m.IsChecked);
        SelectedCountText.Text = n == 0 ? _noneText : $"선택 {n}명";
        if (OkButton is not null) OkButton.IsEnabled = n > 0 || !_requireSelection;
    }

    private void FilterBox_OnTextChanged(object sender, System.Windows.Controls.TextChangedEventArgs e)
    {
        var q = FilterBox.Text?.Trim() ?? "";
        if (q.Length == 0)
        {
            _view.Filter = null;
        }
        else
        {
            _view.Filter = obj =>
            {
                if (obj is not MemberItem m) return false;
                return m.DisplayName.IndexOf(q, StringComparison.OrdinalIgnoreCase) >= 0
                    || m.UserId.IndexOf(q, StringComparison.OrdinalIgnoreCase) >= 0
                    || m.OrgPath.IndexOf(q, StringComparison.OrdinalIgnoreCase) >= 0;
            };
        }
    }

    private void OkButton_OnClick(object sender, RoutedEventArgs e)
    {
        if (_requireSelection && !_all.Any(m => m.IsChecked)) return; // Ctrl+Enter 경로도 같은 조건
        DialogResult = true;
        Close();
    }

    private void CancelButton_OnClick(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }

    private static IEnumerable<MemberItem> Flatten(IEnumerable<OrgTreeNode> nodes)
    {
        foreach (var n in nodes)
        {
            if (n.Kind == OrgTreeNodeKind.Member && !string.IsNullOrEmpty(n.UserId))
            {
                yield return new MemberItem
                {
                    UserId = n.UserId,
                    DisplayName = n.DisplayName,
                    OrgPath = n.OrgPath,
                };
            }
            foreach (var c in Flatten(n.Children)) yield return c;
        }
    }

    public sealed class MemberItem : INotifyPropertyChanged
    {
        public string UserId { get; init; } = "";
        public string DisplayName { get; init; } = "";
        public string OrgPath { get; init; } = "";

        private bool _isChecked;
        public bool IsChecked
        {
            get => _isChecked;
            set
            {
                if (_isChecked == value) return;
                _isChecked = value;
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(IsChecked)));
            }
        }
        public event PropertyChangedEventHandler? PropertyChanged;
    }
}

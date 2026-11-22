using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using OsoriClient.Wpf.Helpers;

namespace OsoriClient.Wpf;

/// <summary>
/// 공통 보조 창(알림/선택). 시스템 MessageBox 와 코드로 만든 흰 타이틀바 창을 대신해
/// 모든 보조 창이 같은 다크 타이틀바·버튼 배치를 쓰게 한다.
/// </summary>
public partial class AppDialog : Window
{
    /// <summary>누른 버튼 번호(0부터). 닫기/Esc 는 -1.</summary>
    public int ChosenIndex { get; private set; } = -1;

    private AppDialog(string title, string message, string? detail, IReadOnlyList<string> buttons, int defaultIndex, int cancelIndex)
    {
        InitializeComponent();
        WindowCornerHelper.ApplyRoundCorners(this);
        Title = title;
        TitleBarText.Text = title;
        MessageText.Text = message;
        if (!string.IsNullOrWhiteSpace(detail))
        {
            DetailText.Text = detail;
            DetailText.Visibility = Visibility.Visible;
        }

        for (var i = 0; i < buttons.Count; i++)
        {
            var index = i;
            var btn = new Button
            {
                Content = buttons[i],
                MinWidth = 84,
                Height = 32,
                Padding = new Thickness(12, 0, 12, 0),
                Margin = new Thickness(i == 0 ? 0 : 8, 0, 0, 0),
                Style = (Style)Application.Current.FindResource(i == defaultIndex ? "PrimaryButton" : "SecondaryButton"),
                IsDefault = i == defaultIndex,
                IsCancel = i == cancelIndex,
            };
            System.Windows.Automation.AutomationProperties.SetAutomationId(btn, $"AppDialogButton{i}");
            btn.Click += (_, _) => { ChosenIndex = index; DialogResult = true; };
            ButtonsPanel.Children.Add(btn);
        }

        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape)
            {
                ChosenIndex = cancelIndex;
                DialogResult = false;
                e.Handled = true;
            }
        };
        Loaded += (_, _) =>
        {
            if (defaultIndex >= 0 && defaultIndex < ButtonsPanel.Children.Count)
                (ButtonsPanel.Children[defaultIndex] as Button)?.Focus();
        };
    }

    private void TitleCloseButton_OnClick(object sender, RoutedEventArgs e)
    {
        ChosenIndex = -1;
        DialogResult = false;
    }

    private static int Show(Window? owner, string title, string message, string? detail, IReadOnlyList<string> buttons, int defaultIndex, int cancelIndex)
    {
        var dlg = new AppDialog(title, message, detail, buttons, defaultIndex, cancelIndex);
        var realOwner = owner ?? Application.Current?.MainWindow;
        if (realOwner is not null && !ReferenceEquals(realOwner, dlg) && realOwner.IsLoaded)
        {
            dlg.Owner = realOwner;
        }
        else
        {
            dlg.WindowStartupLocation = WindowStartupLocation.CenterScreen;
        }
        dlg.ShowDialog();
        return dlg.ChosenIndex;
    }

    /// <summary>확인 버튼 하나짜리 알림.</summary>
    public static void Info(Window? owner, string message, string title = "오소리 메신저", string? detail = null) =>
        Show(owner, title, message, detail, new[] { "확인" }, defaultIndex: 0, cancelIndex: 0);

    /// <summary>예/아니요. 예면 true.</summary>
    public static bool Confirm(Window? owner, string message, string title = "오소리 메신저", string yes = "예", string no = "아니요", string? detail = null) =>
        Show(owner, title, message, detail, new[] { yes, no }, defaultIndex: 0, cancelIndex: 1) == 0;

    /// <summary>여러 선택지. 누른 버튼 번호(닫기/Esc 는 -1).</summary>
    public static int Choose(Window? owner, string message, string title, IReadOnlyList<string> buttons, int defaultIndex = 0, int cancelIndex = -1, string? detail = null) =>
        Show(owner, title, message, detail, buttons, defaultIndex, cancelIndex);
}

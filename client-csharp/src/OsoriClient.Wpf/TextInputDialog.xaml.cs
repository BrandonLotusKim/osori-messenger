using System.Windows;
using System.Windows.Input;
using OsoriClient.Wpf.Helpers;

namespace OsoriClient.Wpf;

public partial class TextInputDialog : Window
{
    public string InputValue { get; private set; } = string.Empty;

    public TextInputDialog(string title, string prompt, string initialValue = "")
    {
        InitializeComponent();
        WindowCornerHelper.ApplyRoundCorners(this);
        Title = title;
        TitleBarText.Text = title;
        PromptText.Text = prompt;
        // 낭독 이름은 창 용도(예: "대화방 이름 설정")로 — 일반적인 "입력" 대신.
        System.Windows.Automation.AutomationProperties.SetName(InputTextBox, title);
        System.Windows.Automation.AutomationProperties.SetHelpText(InputTextBox, prompt);
        InputTextBox.Text = initialValue;
        UpdateOkEnabled();
        Loaded += (_, _) =>
        {
            InputTextBox.Focus();
            InputTextBox.SelectAll();
        };
    }

    public static string? Ask(Window? owner, string title, string prompt, string initialValue = "")
    {
        var dialog = new TextInputDialog(title, prompt, initialValue);
        if (owner != null)
        {
            dialog.Owner = owner;
        }
        return dialog.ShowDialog() == true && !string.IsNullOrWhiteSpace(dialog.InputValue)
            ? dialog.InputValue.Trim()
            : null;
    }

    // 공백만 있으면 확인을 막는다 — 예전엔 눌러도 아무 일 없이 창만 닫혀 바뀐 줄 알았다.
    private void UpdateOkEnabled() => OkButton.IsEnabled = !string.IsNullOrWhiteSpace(InputTextBox.Text);

    private void InputTextBox_OnTextChanged(object sender, System.Windows.Controls.TextChangedEventArgs e) => UpdateOkEnabled();

    private void OkButton_OnClick(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(InputTextBox.Text)) return;
        InputValue = InputTextBox.Text;
        DialogResult = true;
    }

    private void CancelButton_OnClick(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
    }

    private void InputTextBox_OnKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            OkButton_OnClick(sender, e);
            e.Handled = true;
        }
    }
}

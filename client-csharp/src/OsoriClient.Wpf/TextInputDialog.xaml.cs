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
        InputTextBox.Text = initialValue;
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

    private void OkButton_OnClick(object sender, RoutedEventArgs e)
    {
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

using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Navigation;

namespace OsoriClient.Wpf.Behaviors;

/// <summary>
///: TextBlock 에 첨부 속성으로 들어가서 URL(http/https/ftp) 을 자동 Hyperlink 로 변환.
/// 사용:
///   &lt;TextBlock behaviors:LinkifiedText.Text="{Binding Row.Text}" .../&gt;
/// 클릭 시 OS 기본 브라우저 (또는 등록된 핸들러) 로 오픈. Process.Start UseShellExecute=true.
/// </summary>
public static class LinkifiedText
{
    public static readonly DependencyProperty TextProperty =
        DependencyProperty.RegisterAttached(
            "Text",
            typeof(string),
            typeof(LinkifiedText),
            new PropertyMetadata(string.Empty, OnTextChanged));

    public static void SetText(DependencyObject obj, string value) => obj.SetValue(TextProperty, value);
    public static string GetText(DependencyObject obj) => (string)obj.GetValue(TextProperty);

    // URL + @mention 동시 매칭 — alternation. 매칭 그룹으로 어느 쪽인지 판별.
    // URL: http/https/ftp/www. 시작 / Mention: @ + 한글·영문·숫자·언더스코어
    private static readonly Regex TokenRegex = new(
        @"(?<url>\b(?:https?://|ftp://|www\.)[^\s<>""'`]+[^\s<>""'`.,;:!?\)\]\}])" +
        @"|(?<mention>@[\w가-힣]+)",
        RegexOptions.Compiled | RegexOptions.IgnoreCase);

    private static void OnTextChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not TextBlock tb) return;
        tb.Inlines.Clear();

        var text = (e.NewValue as string) ?? string.Empty;
        if (text.Length == 0) return;

        int last = 0;
        foreach (Match m in TokenRegex.Matches(text))
        {
            if (m.Index > last)
            {
                tb.Inlines.Add(new Run(text.Substring(last, m.Index - last)));
            }

            if (m.Groups["url"].Success)
            {
                var url = m.Value;
                var href = url.StartsWith("www.", System.StringComparison.OrdinalIgnoreCase)
                    ? "http://" + url
                    : url;
                var link = new Hyperlink(new Run(url))
                {
                    NavigateUri = new System.Uri(href, System.UriKind.Absolute),
                };
                link.RequestNavigate += OnRequestNavigate;
                tb.Inlines.Add(link);
            }
            else if (m.Groups["mention"].Success)
            {
                //: @mention — 노랑 색 + SemiBold 강조 (자동완성/알림 분기는 v0.2)
                var mention = new Run(m.Value)
                {
                    Foreground = (System.Windows.Media.Brush)Application.Current.FindResource("BrandBrush"),
                    FontWeight = System.Windows.FontWeights.SemiBold,
                };
                tb.Inlines.Add(mention);
            }

            last = m.Index + m.Length;
        }
        if (last < text.Length)
        {
            tb.Inlines.Add(new Run(text.Substring(last)));
        }
    }

    private static void OnRequestNavigate(object sender, RequestNavigateEventArgs e)
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = e.Uri.AbsoluteUri,
                UseShellExecute = true,
            });
        }
        catch
        {
            // 핸들러 없거나 차단되면 무시
        }
        e.Handled = true;
    }
}

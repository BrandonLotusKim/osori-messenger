using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using OsoriClient.Core;

namespace OsoriClient.Wpf.Behaviors;

/// <summary>
/// TextBlock 에 원문(Source)과 검색어(Keyword)를 주면 검색어 부분만 굵게 + 강조 배경으로 그린다.
/// 분할 규칙은 Core 의 <see cref="TalkListFormat.SplitHighlight"/>(대소문자 무시).
/// </summary>
public static class HighlightedText
{
    public static readonly DependencyProperty SourceProperty = DependencyProperty.RegisterAttached(
        "Source", typeof(string), typeof(HighlightedText), new PropertyMetadata(null, OnChanged));

    public static readonly DependencyProperty KeywordProperty = DependencyProperty.RegisterAttached(
        "Keyword", typeof(string), typeof(HighlightedText), new PropertyMetadata(null, OnChanged));

    public static string? GetSource(DependencyObject d) => (string?)d.GetValue(SourceProperty);
    public static void SetSource(DependencyObject d, string? value) => d.SetValue(SourceProperty, value);
    public static string? GetKeyword(DependencyObject d) => (string?)d.GetValue(KeywordProperty);
    public static void SetKeyword(DependencyObject d, string? value) => d.SetValue(KeywordProperty, value);

    private static void OnChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not TextBlock tb) return;
        tb.Inlines.Clear();
        var highlightBg = tb.TryFindResource("SearchHighlightBrush") as Brush
                          ?? new SolidColorBrush(Color.FromArgb(0x66, 0xFF, 0xC1, 0x07));
        foreach (var (text, isMatch) in TalkListFormat.SplitHighlight(GetSource(tb), GetKeyword(tb)))
        {
            var run = new Run(text);
            if (isMatch)
            {
                run.FontWeight = FontWeights.Bold;
                run.Background = highlightBg;
            }
            tb.Inlines.Add(run);
        }
    }
}

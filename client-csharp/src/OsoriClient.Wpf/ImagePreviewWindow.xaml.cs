using System;
using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using OsoriClient.Wpf.Helpers;

namespace OsoriClient.Wpf;

/// <summary>
/// 부분 — 다운로드된 이미지 파일 인앱 미리보기.
/// 호출자는 파일 디스크 경로 + 표시 이름을 전달. 큰 이미지는 BitmapCacheOption.OnLoad 로 즉시 메모리 로드 후 파일 락 해제.
/// </summary>
public partial class ImagePreviewWindow : Window
{
    private readonly string _filePath;

    public ImagePreviewWindow(string filePath, string displayName, long sizeBytes)
    {
        InitializeComponent();
        WindowCornerHelper.ApplyRoundCorners(this);
        _filePath = filePath;
        TitleText.Text = displayName;
        MetaText.Text = $"{displayName} · {FormatSize(sizeBytes)} · {filePath}";

        try
        {
            var bi = new BitmapImage();
            bi.BeginInit();
            bi.CacheOption = BitmapCacheOption.OnLoad;
            bi.UriSource = new Uri(filePath, UriKind.Absolute);
            bi.EndInit();
            bi.Freeze();
            PreviewImage.Source = bi;
        }
        catch (Exception ex)
        {
            MetaText.Text = $"이미지 로드 실패: {ex.Message}";
        }

        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape) { Close(); e.Handled = true; }
        };
    }

    private void CloseButton_OnClick(object sender, RoutedEventArgs e) => Close();

    private void OpenInExplorerButton_OnClick(object sender, RoutedEventArgs e)
    {
        try
        {
            System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{_filePath}\"");
        }
        catch { /* 무시 */ }
    }

    private static string FormatSize(long b)
    {
        if (b < 1024) return $"{b} B";
        if (b < 1024 * 1024) return $"{b / 1024.0:F1} KB";
        return $"{b / 1024.0 / 1024.0:F1} MB";
    }
}

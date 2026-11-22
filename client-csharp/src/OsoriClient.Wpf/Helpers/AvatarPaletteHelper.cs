using System;
using System.Security.Cryptography;
using System.Text;
using System.Windows;
using System.Windows.Media;

namespace OsoriClient.Wpf.Helpers;

/// <summary>
/// 아바타 색 — 테마 리소스(AvatarPalette0~7) 조회용. 인덱스 계산은 Core 의 AvatarStyle 과 공유해
/// 같은 사용자는 모든 화면에서 같은 색을 가진다.
/// </summary>
public static class AvatarPaletteHelper
{
    public const int PaletteSize = 8;

    public static int GetIndex(string? userId) => OsoriClient.Core.AvatarStyle.Index(userId);

    public static Brush GetBrush(string? userId)
    {
        var key = $"AvatarPalette{GetIndex(userId)}Brush";
        if (Application.Current?.Resources[key] is Brush brush)
        {
            return brush;
        }
        return Brushes.Gray;
    }

    public static Color GetColor(string? userId)
    {
        var key = $"AvatarPalette{GetIndex(userId)}";
        if (Application.Current?.Resources[key] is Color color)
        {
            return color;
        }
        return Colors.Gray;
    }

    public static string GetInitial(string? displayName)
    {
        if (string.IsNullOrWhiteSpace(displayName))
        {
            return "?";
        }
        var trimmed = displayName.Trim();
        return trimmed.Substring(0, 1).ToUpperInvariant();
    }
}

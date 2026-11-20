using System;
using System.Security.Cryptography;
using System.Text;
using System.Windows;
using System.Windows.Media;

namespace OsoriClient.Wpf.Helpers;

/// <summary>
/// OC-D03 §10 Avatar 색 해시 — 사용자 ID 를 SHA1 → 첫 바이트 mod 8 → 팔레트 인덱스.
/// 같은 사용자는 모든 화면에서 같은 색을 가진다.
/// </summary>
public static class AvatarPaletteHelper
{
    public const int PaletteSize = 8;

    public static int GetIndex(string? userId)
    {
        if (string.IsNullOrEmpty(userId))
        {
            return 0;
        }

        var bytes = Encoding.UTF8.GetBytes(userId);
        var hash = SHA1.HashData(bytes);
        return hash[0] % PaletteSize;
    }

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

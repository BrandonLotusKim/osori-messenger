using System.Security.Cryptography;
using System.Text;

namespace OsoriClient.Core;

/// <summary>
/// 아바타 색·글자 규칙. 대화 목록, 대화창 헤더, 말풍선, 멤버 목록이 모두 이 함수를 써서
/// 같은 대상(사용자 ID 또는 그룹 방 ID)은 어느 화면에서나 같은 색·글자로 보인다.
/// </summary>
public static class AvatarStyle
{
    /// <summary>테마 팔레트(Tokens.xaml 의 AvatarPalette0~7)와 같은 값.</summary>
    public static readonly IReadOnlyList<string> Palette = new[]
    {
        "#E57373", "#BA68C8", "#7986CB", "#4FC3F7",
        "#4DB6AC", "#81C784", "#FFB74D", "#A1887F",
    };

    /// <summary>키가 비었을 때 쓰는 회색.</summary>
    public const string EmptyHex = "#BDBDBD";

    /// <summary>SHA1 첫 바이트 mod 팔레트 크기.</summary>
    public static int Index(string? key)
    {
        if (string.IsNullOrEmpty(key)) return 0;
        var hash = SHA1.HashData(Encoding.UTF8.GetBytes(key));
        return hash[0] % Palette.Count;
    }

    public static string Hex(string? key) => string.IsNullOrEmpty(key) ? EmptyHex : Palette[Index(key)];

    /// <summary>표시 이름 첫 글자(대문자). 비었으면 "?".</summary>
    public static string Initial(string? displayName)
    {
        if (string.IsNullOrWhiteSpace(displayName)) return "?";
        return displayName.Trim().Substring(0, 1).ToUpperInvariant();
    }

    /// <summary>
    /// 대화(방) 아바타의 색 기준 키: 1:1(상대가 한 명으로 정해짐)이면 상대 ID, 그룹 방이면 방 ID.
    /// </summary>
    public static string TalkKey(string roomOrCounterpartId, string? directPeerId)
        => string.IsNullOrEmpty(directPeerId) ? roomOrCounterpartId : directPeerId;
}

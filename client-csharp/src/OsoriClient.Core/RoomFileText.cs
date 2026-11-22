using System.Text.RegularExpressions;

namespace OsoriClient.Core;

/// <summary>
/// 방 대화 기록에 남는 파일 메시지 문구(서버 형식: <c>📎 이름 (크기)</c>)에서 파일 이름을 꺼낸다.
/// 화면에서 덧붙이는 꼬리(<c>전송 완료</c>, <c>[다운로드하려면 더블클릭]</c>, <c>저장됨: ...</c>)가 있어도 된다.
/// </summary>
public static class RoomFileText
{
    // 이름 뒤 마지막 "(숫자 단위)" 묶음을 크기로 본다. 이름 안의 "(1)" 같은 괄호는 단위가 없어 건너뛴다.
    private static readonly Regex Pattern = new(
        "^" + Regex.Escape("\U0001F4CE") + @"\s*(?<name>.+)\s\(\d+(?:[.,]\d+)?\s?(?:B|KB|MB|GB)\)",
        RegexOptions.CultureInvariant);

    public static string? ParseFileName(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        var firstLine = text.Trim().Split('\n')[0].TrimEnd();
        var m = Pattern.Match(firstLine);
        if (!m.Success) return null;
        var name = m.Groups["name"].Value.Trim();
        return name.Length == 0 ? null : name;
    }
}

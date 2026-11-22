using System.Text.RegularExpressions;

namespace OsoriClient.Core;

/// <summary>
/// 방 대화 기록에 남는 파일 메시지 문구(서버 형식: <c>📎 이름 (크기)</c>)에서 파일 이름을 꺼낸다.
/// 화면에서 덧붙이는 꼬리(<c>전송 완료</c>, <c>더블클릭하여 다운로드</c>, <c>저장됨: ...</c>)가 있어도 된다.
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

    private static readonly Regex SizeSpace = new(@"\((\d+(?:[.,]\d+)?) (B|KB|MB|GB)\)", RegexOptions.CultureInvariant);

    /// <summary>
    /// 파일 메시지면 크기 "(41 B)"의 숫자·단위 사이 공백을 줄바꿈 없는 공백(U+00A0)으로 바꾼다.
    /// 긴 파일명 뒤에서 말풍선이 괄호 안을 끊어 "(41 / B)"로 보이는 것을 막는다. 파일 메시지가 아니면 그대로.
    /// </summary>
    public static string KeepSizeTogether(string? text)
    {
        if (string.IsNullOrEmpty(text)) return text ?? string.Empty;
        if (ParseFileName(text) is null) return text;
        return SizeSpace.Replace(text, "($1 $2)", 1);
    }
}

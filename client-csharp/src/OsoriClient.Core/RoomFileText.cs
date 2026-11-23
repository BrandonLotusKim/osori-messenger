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

    public const string DownloadHint = "더블클릭하여 다운로드";
    public const string SavedHint = "저장 완료 · 더블클릭하면 폴더 열기";

    /// <summary>파일 메시지 첫 줄 <c>📎 이름 (크기)</c> — 서버 기록 문구와 같은 크기 표기.</summary>
    public static string Head(string fileName, long size) => $"\U0001F4CE {fileName} ({TalkListFormat.FileSize(size)})";

    /// <summary>
    /// 받은 파일 메시지(기록 포함)에 받는 방법 안내 줄을 붙인다. 이미 둘째 줄이 있거나 파일 메시지가 아니면 그대로.
    /// </summary>
    public static string WithDownloadHint(string? text)
    {
        if (string.IsNullOrEmpty(text)) return text ?? string.Empty;
        if (ParseFileName(text) is null || text.TrimEnd().Contains('\n')) return text;
        return $"{text.TrimEnd()}\n{DownloadHint}";
    }

    public static string Downloading(string fileName, long size, int percent) =>
        $"{Head(fileName, size)}\n다운로드 중... {Math.Clamp(percent, 0, 100)}%";

    /// <summary>저장 완료 — 로컬 경로는 말풍선에 쓰지 않는다(툴팁·폴더 열기로만).</summary>
    public static string Saved(string fileName, long size) => $"{Head(fileName, size)}\n{SavedHint}";

    public static string DownloadFailed(string fileName, long size, string reason) =>
        $"{Head(fileName, size)}\n다운로드 실패: {reason}";

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

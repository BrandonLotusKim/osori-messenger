using System.Globalization;

namespace OsoriClient.Core;

/// <summary>대화·쪽지 목록에 쓰는 표시 문구(시각, 미리보기, 파일 크기).</summary>
public static class TalkListFormat
{
    /// <summary>
    /// 목록 시각 표시: 오늘이면 <c>HH:mm</c>, 올해면 <c>MM-dd</c>, 그 전이면 <c>yyyy-MM-dd</c>.
    /// <paramref name="now"/> 와 같은 시간대(현지 시각)로 비교한다.
    /// </summary>
    public static string ListTime(long unixMs, DateTimeOffset now)
    {
        if (unixMs <= 0) return string.Empty;
        var t = DateTimeOffset.FromUnixTimeMilliseconds(unixMs).ToOffset(now.Offset);
        if (t.Date == now.Date) return t.ToString("HH:mm", CultureInfo.InvariantCulture);
        if (t.Year == now.Year) return t.ToString("MM'-'dd", CultureInfo.InvariantCulture);
        return t.ToString("yyyy'-'MM'-'dd", CultureInfo.InvariantCulture);
    }

    /// <summary>현지 시각 기준 <see cref="ListTime(long, DateTimeOffset)"/>.</summary>
    public static string ListTime(long unixMs) => ListTime(unixMs, DateTimeOffset.Now);

    /// <summary>
    /// 대화 목록 미리보기: 파일 메시지(<c>📎 이름 (크기)</c> 형식)면 <c>[파일] 이름</c>,
    /// 그 외에는 줄바꿈을 공백으로 합친 한 줄.
    /// </summary>
    public static string Preview(string? text)
    {
        var fileName = RoomFileText.ParseFileName(text);
        if (fileName is not null) return $"[파일] {fileName}";
        return TextPreview.OneLine(text, 100);
    }

    /// <summary>
    /// 검색어 강조용 분할: 대소문자 무시로 keyword 가 나오는 부분을 IsMatch=true 조각으로 나눈다.
    /// 조각을 순서대로 이으면 원문과 같다. keyword 가 비면 원문 한 조각.
    /// </summary>
    public static IReadOnlyList<(string Text, bool IsMatch)> SplitHighlight(string? text, string? keyword)
    {
        var result = new List<(string, bool)>();
        if (string.IsNullOrEmpty(text)) return result;
        if (string.IsNullOrWhiteSpace(keyword))
        {
            result.Add((text, false));
            return result;
        }
        var k = keyword.Trim();
        var pos = 0;
        while (pos < text.Length)
        {
            var idx = text.IndexOf(k, pos, StringComparison.OrdinalIgnoreCase);
            if (idx < 0) break;
            if (idx > pos) result.Add((text.Substring(pos, idx - pos), false));
            result.Add((text.Substring(idx, k.Length), true));
            pos = idx + k.Length;
        }
        if (pos < text.Length) result.Add((text.Substring(pos), false));
        return result;
    }

    /// <summary>파일 크기 문구: B / KB / MB (소수 한 자리).</summary>
    public static string FileSize(long bytes)
    {
        if (bytes < 0) bytes = 0;
        if (bytes < 1024) return $"{bytes} B";
        if (bytes < 1024 * 1024) return (bytes / 1024.0).ToString("0.0", CultureInfo.InvariantCulture) + " KB";
        return (bytes / (1024.0 * 1024.0)).ToString("0.0", CultureInfo.InvariantCulture) + " MB";
    }
}

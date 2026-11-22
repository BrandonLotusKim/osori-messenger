namespace OsoriClient.Core;

/// <summary>목록 미리보기 문구 만들기(순수 함수).</summary>
public static class TextPreview
{
    /// <summary>줄바꿈·연속 공백을 공백 하나로 합치고 maxChars 를 넘으면 … 로 줄인다.</summary>
    public static string OneLine(string? text, int maxChars)
    {
        if (string.IsNullOrWhiteSpace(text)) return string.Empty;
        var parts = text.Split(new[] { '\r', '\n', '\t', ' ' }, StringSplitOptions.RemoveEmptyEntries);
        var joined = string.Join(' ', parts);
        if (maxChars <= 0 || joined.Length <= maxChars) return joined;
        return joined.Substring(0, maxChars).TrimEnd() + "…";
    }
}

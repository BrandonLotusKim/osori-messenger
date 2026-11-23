namespace OsoriClient.Core;

/// <summary>대화 말풍선 한 줄의 낭독 문구·상태 설명(화면 낭독기와 상태 아이콘 툴팁이 같이 쓴다).</summary>
public static class TalkMessageText
{
    /// <summary>내 메시지 상태를 말로: 아이콘(⏳ ✓ 📮 ⚠ 🚫)만으로는 뜻이 전해지지 않아서.</summary>
    public static string StatusLabel(TalkMessageStatus status) => status switch
    {
        TalkMessageStatus.Sending => "보내는 중",
        TalkMessageStatus.Delivered => "전달됨",
        TalkMessageStatus.Queued => "상대가 접속하면 전달됩니다",
        TalkMessageStatus.RateLimited => "너무 빨리 보내서 전송이 제한됐습니다",
        TalkMessageStatus.InvalidTarget => "받을 수 없는 상대입니다",
        TalkMessageStatus.Failed => "전송 실패",
        TalkMessageStatus.ServerError => "서버 오류로 전송 실패",
        _ => string.Empty,
    };

    /// <summary>오른쪽 클릭 "재전송"을 쓸 수 있는 상태인지.</summary>
    public static bool CanResend(TalkMessageStatus status) =>
        status is TalkMessageStatus.Failed or TalkMessageStatus.ServerError
            or TalkMessageStatus.InvalidTarget or TalkMessageStatus.RateLimited;

    /// <summary>상태 아이콘 툴팁: 상태 설명 + 재전송 방법(재전송 가능한 경우).</summary>
    public static string StatusToolTip(TalkMessageStatus status)
    {
        var label = StatusLabel(status);
        return CanResend(status) ? $"{label} — 말풍선을 오른쪽 클릭해 재전송할 수 있습니다" : label;
    }

    /// <summary>
    /// 말풍선 낭독 이름. 예: "test8, 네, 확인했습니다., 10:21" / "나, 금요일 배포..., 10:20, 읽음".
    /// 줄바꿈은 공백으로 합친다. 비어 있는 부분은 건너뛴다.
    /// </summary>
    public static string AccessibleName(string sender, bool isMine, string? text, string? time, string? readLabel, TalkMessageStatus status)
    {
        var parts = new List<string> { isMine ? "나" : sender, TextPreview.OneLine(text, 300) };
        if (!string.IsNullOrEmpty(time)) parts.Add(time!);
        if (isMine)
        {
            if (!string.IsNullOrEmpty(readLabel))
                parts.Add(readLabel == "읽음" ? "읽음" : $"안 읽은 사람 {readLabel}명");
            if (status is not (TalkMessageStatus.Delivered or TalkMessageStatus.Received))
                parts.Add(StatusLabel(status));
        }
        return string.Join(", ", parts.Where(p => !string.IsNullOrWhiteSpace(p)));
    }
}

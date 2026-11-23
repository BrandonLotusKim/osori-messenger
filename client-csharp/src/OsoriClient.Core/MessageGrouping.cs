namespace OsoriClient.Core;

/// <summary>
/// 대화창 연속 메시지 묶기 규칙(순수 함수). 같은 사람이 짧은 간격으로 이어 보낸 메시지는
/// 보낸 사람 이름·아바타를 첫 메시지에만 보여 준다.
/// </summary>
public static class MessageGrouping
{
    /// <summary>묶음으로 보는 최대 간격(3분).</summary>
    public const long MaxGapMillis = 3 * 60 * 1000;

    /// <param name="prevSender">바로 앞 메시지 보낸 사람(없으면 null)</param>
    /// <param name="prevTs">바로 앞 메시지 서버 시각(ms)</param>
    /// <param name="curSender">이번 메시지 보낸 사람</param>
    /// <param name="curTs">이번 메시지 서버 시각(ms)</param>
    /// <param name="startsNewDay">이번 메시지 위에 날짜 구분선이 있는지</param>
    public static bool IsContinuation(string? prevSender, long prevTs, string curSender, long curTs, bool startsNewDay)
    {
        if (startsNewDay || prevSender is null) return false;
        if (!string.Equals(prevSender, curSender, StringComparison.OrdinalIgnoreCase)) return false;
        if (prevTs <= 0 || curTs <= 0) return true; // 시각 미정(전송 중)이면 같은 묶음으로 본다
        return Math.Abs(curTs - prevTs) <= MaxGapMillis;
    }

    /// <summary>
    /// 말풍선 옆 시각을 보일지: 바로 다음 메시지가 같은 사람·같은 분(현지 시각)이면 이번 시각은 숨기고
    /// 묶음의 마지막 메시지에만 한 번 보인다. 다음 메시지가 없거나 시각을 모르면(전송 중) 보인다.
    /// </summary>
    /// <param name="utcOffset">현지 시간대 오프셋(테스트용, 기본은 현재 PC 시간대)</param>
    public static bool ShowsTime(string curSender, long curTs, string? nextSender, long nextTs, TimeSpan? utcOffset = null)
    {
        if (nextSender is null || curTs <= 0 || nextTs <= 0) return true;
        if (!string.Equals(curSender, nextSender, StringComparison.OrdinalIgnoreCase)) return true;
        var offset = utcOffset ?? TimeZoneInfo.Local.GetUtcOffset(DateTimeOffset.FromUnixTimeMilliseconds(curTs));
        static long Minute(long ts, TimeSpan off) => (ts + (long)off.TotalMilliseconds) / 60_000;
        return Minute(curTs, offset) != Minute(nextTs, offset);
    }
}

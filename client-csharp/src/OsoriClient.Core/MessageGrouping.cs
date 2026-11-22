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
}

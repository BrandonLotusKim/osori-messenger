namespace OsoriClient.Core.Tests;

/// <summary>목록 시각·미리보기·파일 크기 문구 검증.</summary>
public sealed class TalkListFormatTests
{
    private static readonly TimeSpan Kst = TimeSpan.FromHours(9);
    private static readonly DateTimeOffset Now = new(2026, 11, 23, 15, 0, 0, Kst);

    private static long Ms(int y, int mo, int d, int h, int mi) =>
        new DateTimeOffset(y, mo, d, h, mi, 0, Kst).ToUnixTimeMilliseconds();

    [Fact]
    public void Today_ShowsTimeOnly()
    {
        Assert.Equal("14:07", TalkListFormat.ListTime(Ms(2026, 11, 23, 14, 7), Now));
        Assert.Equal("00:00", TalkListFormat.ListTime(Ms(2026, 11, 23, 0, 0), Now));
    }

    [Fact]
    public void EarlierThisYear_ShowsMonthDay()
    {
        Assert.Equal("11-22", TalkListFormat.ListTime(Ms(2026, 11, 22, 23, 59), Now));
        Assert.Equal("01-02", TalkListFormat.ListTime(Ms(2026, 1, 2, 9, 0), Now));
    }

    [Fact]
    public void PreviousYear_ShowsFullDate()
    {
        Assert.Equal("2025-12-31", TalkListFormat.ListTime(Ms(2025, 12, 31, 23, 0), Now));
    }

    [Fact]
    public void ZeroOrNegative_Empty()
    {
        Assert.Equal("", TalkListFormat.ListTime(0, Now));
        Assert.Equal("", TalkListFormat.ListTime(-5, Now));
    }

    [Theory]
    [InlineData("\U0001F4CE report.pdf (12.0 KB)", "[파일] report.pdf")]
    [InlineData("\U0001F4CE group_file.txt (40 B) 전송 완료", "[파일] group_file.txt")]
    [InlineData("\U0001F4CE a b.txt (74 B)\n업로드 중...", "[파일] a b.txt")]
    [InlineData("\U0001F4CE photo.png (1.5 KB)\n더블클릭하여 다운로드", "[파일] photo.png")]
    [InlineData("\U0001F4CE 긴 이름.txt (41 B)\n전송 완료", "[파일] 긴 이름.txt")]
    [InlineData("안녕하세요\n반갑습니다", "안녕하세요 반갑습니다")]
    [InlineData("", "")]
    [InlineData(null, "")]
    public void Preview_FileOrText(string? text, string expected)
    {
        Assert.Equal(expected, TalkListFormat.Preview(text));
    }

    [Fact]
    public void SplitHighlight_CaseInsensitiveMultipleMatches()
    {
        var parts = TalkListFormat.SplitHighlight("Hello 회의 hello", "HELLO");
        Assert.Equal(new[] { ("Hello", true), (" 회의 ", false), ("hello", true) }, parts);
    }

    [Fact]
    public void SplitHighlight_NoKeywordOrNoMatch()
    {
        Assert.Equal(new[] { ("abc", false) }, TalkListFormat.SplitHighlight("abc", ""));
        Assert.Equal(new[] { ("abc", false) }, TalkListFormat.SplitHighlight("abc", "z"));
        Assert.Empty(TalkListFormat.SplitHighlight("", "a"));
    }

    [Fact]
    public void KeepSizeTogether_OnlyFileMessages()
    {
        Assert.Equal("\U0001F4CE 보고서 (1).txt (41 B)", RoomFileText.KeepSizeTogether("\U0001F4CE 보고서 (1).txt (41 B)"));
        Assert.Equal("\U0001F4CE a.bin (30.0 MB)\n전송 완료", RoomFileText.KeepSizeTogether("\U0001F4CE a.bin (30.0 MB)\n전송 완료"));
        Assert.Equal("가격은 (41 B) 입니다", RoomFileText.KeepSizeTogether("가격은 (41 B) 입니다"));
        Assert.Equal("", RoomFileText.KeepSizeTogether(null));
    }

    [Theory]
    [InlineData(0, "0 B")]
    [InlineData(1023, "1023 B")]
    [InlineData(2048, "2.0 KB")]
    [InlineData(5 * 1024 * 1024, "5.0 MB")]
    public void FileSize_Units(long bytes, string expected)
    {
        Assert.Equal(expected, TalkListFormat.FileSize(bytes));
    }
}

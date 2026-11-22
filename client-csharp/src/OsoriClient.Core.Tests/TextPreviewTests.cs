namespace OsoriClient.Core.Tests;

public sealed class TextPreviewTests
{
    [Fact]
    public void CollapsesNewlinesAndSpaces() =>
        Assert.Equal("배포 일정 안내 금요일 오전", TextPreview.OneLine("배포 일정 안내\r\n\r\n  금요일   오전", 60));

    [Fact]
    public void TruncatesWithEllipsis() =>
        Assert.Equal("12345…", TextPreview.OneLine("1234567890", 5));

    [Fact]
    public void EmptyOrNull_ReturnsEmpty()
    {
        Assert.Equal("", TextPreview.OneLine(null, 10));
        Assert.Equal("", TextPreview.OneLine("  \n ", 10));
    }
}

using OsoriClient.Core;

namespace OsoriClient.Core.Tests;

/// <summary>3차 점검 수정분: 아이디 순서, 낭독 이름, 시각 묶기, 파일 문구, 글꼴 크기.</summary>
public sealed class IdOrderTests
{
    [Fact]
    public void Numbers_compare_by_value()
    {
        Assert.Equal(new[] { "test7", "test9", "test10" }, IdOrder.Sort(new[] { "test10", "test9", "test7" }));
        Assert.True(IdOrder.Instance.Compare("test2", "test11") < 0);
        Assert.True(IdOrder.Instance.Compare("a", "b") < 0);
        Assert.Equal(0, IdOrder.Instance.Compare("test7", "test7"));
    }

    [Fact]
    public void Sort_ignores_case_duplicates_and_blanks()
    {
        Assert.Equal(new[] { "Test1", "test3" }, IdOrder.Sort(new[] { "test3", "Test1", "test1", " ", "" }));
    }

    [Fact]
    public void Summary_uses_first_in_order()
    {
        Assert.Equal("", IdOrder.Summary(Array.Empty<string>()));
        Assert.Equal("test8", IdOrder.Summary(new[] { "test8" }));
        Assert.Equal("test7 외 1명", IdOrder.Summary(new[] { "test10", "test7" }));
    }

    [Fact]
    public void Room_auto_name_uses_natural_order()
    {
        Assert.Equal("test7, test9, test10", RoomNaming.AutoName(new[] { "test10", "test7", "test9" }));
        Assert.Equal("test7, test10", RoomNaming.Resolve("x", false, new[] { "test10", "test8", "test7" }, "test8", "room"));
    }
}

public sealed class OrgAccessibleNameTests
{
    [Fact]
    public void Member_name_reads_presence_and_selection()
    {
        var n = new OrgTreeNode { Kind = OrgTreeNodeKind.Member, DisplayName = "test8 · 선임연구원", UserId = "test8" };
        Assert.Equal("test8 · 선임연구원, 오프라인", n.AccessibleName);
        var changed = new List<string?>();
        n.PropertyChanged += (_, e) => changed.Add(e.PropertyName);
        n.IsOnline = true;
        n.IsSelectedForRoom = true;
        Assert.Equal("test8 · 선임연구원, 온라인, 선택됨", n.AccessibleName);
        Assert.Equal(2, changed.Count(p => p == nameof(OrgTreeNode.AccessibleName)));
    }

    [Fact]
    public void Unit_name_is_plain()
    {
        var u = new OrgTreeNode { Kind = OrgTreeNodeKind.Unit, DisplayName = "플랫폼개발팀" };
        Assert.Equal("플랫폼개발팀", u.AccessibleName);
    }
}

public sealed class MessageTimeGroupingTests
{
    private static readonly TimeSpan Kst = TimeSpan.FromHours(9);
    private const long T0 = 1_763_860_800_000; // 분 경계(정각)

    [Fact]
    public void Same_sender_same_minute_hides_earlier_time()
    {
        Assert.False(MessageGrouping.ShowsTime("a", T0 + 1_000, "a", T0 + 30_000, Kst));
    }

    [Fact]
    public void Shows_time_on_last_or_when_minute_sender_changes()
    {
        Assert.True(MessageGrouping.ShowsTime("a", T0 + 1_000, null, 0, Kst));          // 마지막
        Assert.True(MessageGrouping.ShowsTime("a", T0 + 1_000, "b", T0 + 2_000, Kst));  // 다른 사람
        Assert.True(MessageGrouping.ShowsTime("a", T0 + 59_000, "a", T0 + 61_000, Kst)); // 분이 바뀜
        Assert.True(MessageGrouping.ShowsTime("a", T0, "a", 0, Kst));                   // 다음이 전송 중
    }
}

public sealed class TalkMessageTextTests
{
    [Fact]
    public void Accessible_name_for_received_and_mine()
    {
        Assert.Equal("test8, 네, 확인했습니다., 10:21",
            TalkMessageText.AccessibleName("test8", false, "네, 확인했습니다.", "10:21", "", TalkMessageStatus.Received));
        Assert.Equal("나, 첫 줄 둘째 줄, 10:20, 읽음",
            TalkMessageText.AccessibleName("test7", true, "첫 줄\n둘째 줄", "10:20", "읽음", TalkMessageStatus.Delivered));
        Assert.Equal("나, 안녕, 10:20, 안 읽은 사람 2명",
            TalkMessageText.AccessibleName("test7", true, "안녕", "10:20", "2", TalkMessageStatus.Delivered));
        Assert.Equal("나, 안녕, 10:43, 전송 실패",
            TalkMessageText.AccessibleName("test7", true, "안녕", "10:43", "", TalkMessageStatus.Failed));
    }

    [Fact]
    public void Status_tooltip_explains_resend_only_when_possible()
    {
        Assert.Contains("재전송", TalkMessageText.StatusToolTip(TalkMessageStatus.Failed));
        Assert.Equal("전달됨", TalkMessageText.StatusToolTip(TalkMessageStatus.Delivered));
        Assert.False(TalkMessageText.CanResend(TalkMessageStatus.Sending));
    }
}

public sealed class RoomFileTextV3Tests
{
    [Fact]
    public void Head_matches_server_size_format()
    {
        Assert.Equal("\U0001F4CE 배포_패키지_v1.2.zip (24.0 MB)", RoomFileText.Head("배포_패키지_v1.2.zip", 24L * 1024 * 1024));
        Assert.Equal("\U0001F4CE a.txt (85 B)", RoomFileText.Head("a.txt", 85));
    }

    [Fact]
    public void Download_hint_added_once_to_file_messages_only()
    {
        Assert.Equal("\U0001F4CE a.txt (85 B)\n더블클릭하여 다운로드", RoomFileText.WithDownloadHint("\U0001F4CE a.txt (85 B)"));
        var already = "\U0001F4CE a.txt (85 B)\n더블클릭하여 다운로드";
        Assert.Equal(already, RoomFileText.WithDownloadHint(already));
        Assert.Equal("그냥 문장", RoomFileText.WithDownloadHint("그냥 문장"));
    }

    [Fact]
    public void Saved_text_has_no_local_path_and_still_parses_name()
    {
        var saved = RoomFileText.Saved("a.txt", 85);
        Assert.DoesNotContain(":\\", saved);
        Assert.Equal("a.txt", RoomFileText.ParseFileName(saved));
        Assert.Equal("a.txt", RoomFileText.ParseFileName(RoomFileText.Downloading("a.txt", 85, 40)));
        Assert.EndsWith("다운로드 중... 100%", RoomFileText.Downloading("a.txt", 85, 140));
    }
}

public sealed class DisplayFontTests
{
    [Fact]
    public void Default_setting_keeps_design_size_and_clamps()
    {
        Assert.Equal(13, DisplayFont.Body(14));
        Assert.Equal(16, DisplayFont.Body(17));
        Assert.Equal(7, DisplayFont.Body(2));
        Assert.Equal(47, DisplayFont.Body(99));
        Assert.True(DisplayFont.IsValidSetting(8));
        Assert.False(DisplayFont.IsValidSetting(49));
    }
}

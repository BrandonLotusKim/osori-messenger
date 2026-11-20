using OsoriClient.Core;

namespace OsoriClient.Core.Tests;

public class TalkStoreTests
{
    [Fact]
    public void GetOrCreate_ReturnsNewState_OnFirstCall()
    {
        var store = new TalkStore();

        var state = store.GetOrCreate("alice");

        Assert.Equal(0, state.UnreadCount);
        Assert.Equal(string.Empty, state.LastPreview);
        Assert.Equal(0, state.LastTs);
    }

    [Fact]
    public void RecordIncoming_IncrementsUnread_WhenNotSelected()
    {
        var store = new TalkStore();

        store.RecordIncoming("alice", "one", 1, isSelected: false);
        store.RecordIncoming("alice", "two", 2, isSelected: false);
        var state = store.RecordIncoming("alice", "three", 3, isSelected: false);

        Assert.Equal(3, state.UnreadCount);
    }

    [Fact]
    public void RecordIncoming_DoesNotIncrementUnread_WhenSelected()
    {
        var store = new TalkStore();

        store.RecordIncoming("alice", "one", 1, isSelected: true);
        store.RecordIncoming("alice", "two", 2, isSelected: true);
        var state = store.RecordIncoming("alice", "three", 3, isSelected: true);

        Assert.Equal(0, state.UnreadCount);
    }

    [Fact]
    public void RecordIncoming_TruncatesLongText()
    {
        var store = new TalkStore();
        var longText = new string('a', 50);

        var state = store.RecordIncoming("alice", longText, 1, isSelected: false);

        Assert.Equal(41, state.LastPreview.Length);
        Assert.Equal(new string('a', 40) + "…", state.LastPreview);
    }

    [Fact]
    public void RecordOutgoing_UpdatesPreviewButNotUnread()
    {
        var store = new TalkStore();
        store.RecordIncoming("alice", "hello", 1, isSelected: false);

        var state = store.RecordOutgoing("alice", "bye", 2);

        Assert.Equal(1, state.UnreadCount);
        Assert.Equal("bye", state.LastPreview);
        Assert.Equal(2, state.LastTs);
    }

    [Fact]
    public void MarkRead_ResetsUnreadCount()
    {
        var store = new TalkStore();
        for (var i = 0; i < 5; i++)
        {
            store.RecordIncoming("alice", $"msg-{i}", i + 1, isSelected: false);
        }

        store.MarkRead("alice");

        var state = store.GetOrCreate("alice");
        Assert.Equal(0, state.UnreadCount);
    }
}

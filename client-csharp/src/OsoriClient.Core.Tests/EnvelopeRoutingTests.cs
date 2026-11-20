using Osori.Proto.V1;

namespace OsoriClient.Core.Tests;

public sealed class EnvelopeRoutingTests
{
    [Fact]
    public void DispatchEnvelopeCore_HistoryResponse_RaisesHistoryReceived()
    {
        var envelope = new Envelope
        {
            TalkHistoryRes = new TalkHistoryResponse
            {
                HasMore = true
            }
        };
        envelope.TalkHistoryRes.Talks.Add(new TalkReceived
        {
            ServerTalkId = "msg-1",
            FromUserId = "alice",
            Text = "hello",
            ServerTs = 123L
        });

        object? actualSender = null;
        TalkHistoryResponse? actualResponse = null;
        var sender = new object();

        MessengerClient.DispatchEnvelopeCore(
            envelope,
            sender,
            messageReceived: null,
            historyReceived: (eventSender, response) =>
            {
                actualSender = eventSender;
                actualResponse = response;
            },
            presenceChanged: null,
            heartbeatAckReceived: null);

        Assert.Same(sender, actualSender);
        Assert.NotNull(actualResponse);
        Assert.True(actualResponse!.HasMore);
        Assert.Single(actualResponse.Talks);
        Assert.Equal("alice", actualResponse.Talks[0].FromUserId);
    }

    [Fact]
    public void DispatchEnvelopeCore_MessageReceived_RaisesMessageReceived()
    {
        var envelope = new Envelope
        {
            TalkReceived = new TalkReceived
            {
                ServerTalkId = "msg-99",
                FromUserId = "bob",
                Text = "direct hit",
                ServerTs = 456L
            }
        };

        TalkReceivedEventArgs? captured = null;
        var sender = new object();

        MessengerClient.DispatchEnvelopeCore(
            envelope,
            sender,
            messageReceived: (_, args) => captured = args,
            historyReceived: null,
            presenceChanged: null,
            heartbeatAckReceived: null);

        Assert.NotNull(captured);
        Assert.Equal("bob", captured!.Message.FromUserId);
        Assert.Equal("direct hit", captured.Message.Text);
    }

    [Fact]
    public void DispatchEnvelopeCore_PresenceEvent_RaisesPresenceChanged()
    {
        var envelope = new Envelope
        {
            PresenceEvent = new PresenceEvent
            {
                UserId = "carol",
                State = PresenceEvent.Types.State.Online,
                ServerTs = 789L
            }
        };

        PresenceEvent? captured = null;

        MessengerClient.DispatchEnvelopeCore(
            envelope,
            sender: new object(),
            messageReceived: null,
            historyReceived: null,
            presenceChanged: (_, ev) => captured = ev,
            heartbeatAckReceived: null);

        Assert.NotNull(captured);
        Assert.Equal("carol", captured!.UserId);
        Assert.Equal(PresenceEvent.Types.State.Online, captured.State);
    }

    [Fact]
    public void DispatchEnvelopeCore_HeartbeatAck_RaisesHeartbeatAckReceived()
    {
        var envelope = new Envelope
        {
            HeartbeatAck = new HeartbeatAck
            {
                ClientTs = 1000L,
                ServerTs = 1010L
            }
        };

        HeartbeatAck? captured = null;

        MessengerClient.DispatchEnvelopeCore(
            envelope,
            sender: new object(),
            messageReceived: null,
            historyReceived: null,
            presenceChanged: null,
            heartbeatAckReceived: (_, ack) => captured = ack);

        Assert.NotNull(captured);
        Assert.Equal(1000L, captured!.ClientTs);
        Assert.Equal(1010L, captured.ServerTs);
    }

    [Fact]
    public void DispatchEnvelopeCore_NullHandlers_DoesNotThrow()
    {
        // 모든 handler 가 null 이어도 예외 없이 통과해야 한다 — 핸들러를 구독하지 않은 호출자 보호.
        var envelope = new Envelope
        {
            TalkReceived = new TalkReceived { FromUserId = "x", Text = "y", ServerTs = 1L }
        };

        var ex = Record.Exception(() =>
            MessengerClient.DispatchEnvelopeCore(
                envelope,
                sender: new object(),
                messageReceived: null,
                historyReceived: null,
                presenceChanged: null,
                heartbeatAckReceived: null));

        Assert.Null(ex);
    }

    [Fact]
    public void DispatchEnvelopeCore_RoomMessageReceived_RaisesRoomMessagePushed()
    {
        var envelope = new Envelope
        {
            TalkRoomReceived = new TalkRoomReceived
            {
                ServerTalkId = "rmsg-1",
                RoomId = "room-1",
                FromUserId = "dave",
                Text = "hey team",
                ServerTs = 2025L
            }
        };

        TalkRoomReceived? captured = null;

        MessengerClient.DispatchEnvelopeCore(
            envelope,
            sender: new object(),
            messageReceived: null,
            historyReceived: null,
            presenceChanged: null,
            heartbeatAckReceived: null,
            roomMessagePushed: (_, m) => captured = m);

        Assert.NotNull(captured);
        Assert.Equal("room-1", captured!.RoomId);
        Assert.Equal("dave", captured.FromUserId);
        Assert.Equal("hey team", captured.Text);
    }

    [Fact]
    public void DispatchEnvelopeCore_FileAvailable_RaisesFileAvailablePushed()
    {
        var envelope = new Envelope
        {
            FileAvailable = new FileAvailableNotification
            {
                UploadId = "upload-1",
                FromUserId = "eve",
                Filename = "slides.pdf",
                TotalSize = 123456L,
                MimeType = "application/pdf",
                ServerTs = 3030L
            }
        };

        FileAvailableNotification? captured = null;

        MessengerClient.DispatchEnvelopeCore(
            envelope,
            sender: new object(),
            messageReceived: null,
            historyReceived: null,
            presenceChanged: null,
            heartbeatAckReceived: null,
            roomMessagePushed: null,
            fileAvailablePushed: (_, n) => captured = n);

        Assert.NotNull(captured);
        Assert.Equal("upload-1", captured!.UploadId);
        Assert.Equal("eve", captured.FromUserId);
        Assert.Equal("slides.pdf", captured.Filename);
        Assert.Equal(123456L, captured.TotalSize);
    }

    [Fact]
    public void DispatchEnvelopeCore_RoomMemberEvent_RaisesRoomMemberChanged()
    {
        var envelope = new Envelope
        {
            TalkRoomMemberEvent = new TalkRoomMemberEvent
            {
                RoomId = "room-1",
                EventType = TalkRoomMemberEvent.Types.EventType.MembersAdded,
                ActorUserId = "alice",
                ServerTs = 4040L,
            }
        };
        envelope.TalkRoomMemberEvent.AffectedUserIds.Add("carol");
        envelope.TalkRoomMemberEvent.MemberIds.Add("alice");
        envelope.TalkRoomMemberEvent.MemberIds.Add("carol");

        TalkRoomMemberEvent? captured = null;

        MessengerClient.DispatchEnvelopeCore(
            envelope,
            sender: new object(),
            messageReceived: null,
            historyReceived: null,
            presenceChanged: null,
            heartbeatAckReceived: null,
            roomMemberChanged: (_, ev) => captured = ev);

        Assert.NotNull(captured);
        Assert.Equal("room-1", captured!.RoomId);
        Assert.Equal(TalkRoomMemberEvent.Types.EventType.MembersAdded, captured.EventType);
        Assert.Equal(new[] { "carol" }, captured.AffectedUserIds);
        Assert.Equal(new[] { "alice", "carol" }, captured.MemberIds);
    }

    [Fact]
    public void DispatchEnvelopeCore_RoomRenamed_RaisesRoomRenamed()
    {
        var envelope = new Envelope
        {
            TalkRoomRenamed = new TalkRoomRenamed
            {
                RoomId = "room-2",
                NewName = "새 이름",
                ServerTs = 5050L,
            }
        };

        TalkRoomRenamed? captured = null;

        MessengerClient.DispatchEnvelopeCore(
            envelope,
            sender: new object(),
            messageReceived: null,
            historyReceived: null,
            presenceChanged: null,
            heartbeatAckReceived: null,
            roomRenamed: (_, ev) => captured = ev);

        Assert.NotNull(captured);
        Assert.Equal("room-2", captured!.RoomId);
        Assert.Equal("새 이름", captured.NewName);
    }

    [Fact]
    public void DispatchEnvelopeCore_RoomMessageReceived_WithoutHandler_DoesNotThrow()
    {
        var envelope = new Envelope
        {
            TalkRoomReceived = new TalkRoomReceived
            {
                RoomId = "room-x",
                FromUserId = "ghost",
                Text = "vanished"
            }
        };

        var ex = Record.Exception(() =>
            MessengerClient.DispatchEnvelopeCore(
                envelope,
                sender: new object(),
                messageReceived: null,
                historyReceived: null,
                presenceChanged: null,
                heartbeatAckReceived: null,
                roomMessagePushed: null));

        Assert.Null(ex);
    }
}

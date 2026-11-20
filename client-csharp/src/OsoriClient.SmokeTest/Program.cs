using System.IO;
using System.Linq;
using OsoriClient.Core;
using Osori.Proto.V1;

// 오소리 메신저 C# 클라이언트 smoke test.
// WPF UI 를 띄우지 않고도 MessengerClient 가 실제 서버와 통신 가능한지 확인.
//
// 시나리오 9단:
//   1. alice/bob 로그인 + 1:1 메시지
//   2. 오프라인 큐 + 재로그인 시 outbox flush
//   3. 자동 재접속 후 메시지 송수신
//   4. 파일 업로드 + 다운로드 라운드트립
//   5. 조직도 snapshot
//   6. 방 생성 + 참여
//   7. 메시지 검색 (키워드 매칭 + 빈 결과)
//   8. (다른 단계와 통합)
//   9. 쪽지 송신 + 푸시 + 받은/보낸쪽지함 + 읽음 + 삭제
//
// 사용법 (서버가 ws://localhost:7071/ws 에 떠있어야 함):
//   dotnet run --project client-csharp/src/OsoriClient.SmokeTest

var serverUri = new Uri(Environment.GetEnvironmentVariable("OSORI_SMOKE_URL") ?? "ws://localhost:7071/ws");
Console.WriteLine($"[smoke] server = {serverUri}");

var overallCts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
MessengerClient? alice = null;
MessengerClient? bob = null;
MessengerClient? bob2 = null;
MessengerClient? charlie = null;

try
{
    alice = new MessengerClient();
    bob = new MessengerClient();

    var received = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
    bob.TalkReceived += (_, e) =>
    {
        Console.WriteLine($"[smoke] bob received: from={e.Message.FromUserId} text={e.Message.Text}");
        received.TrySetResult(e.Message.Text);
    };
    bob.PresenceChanged += (_, e) =>
    {
        Console.WriteLine($"[smoke] bob presence: user={e.UserId} state={e.State}");
    };

    Console.WriteLine("[smoke] alice connect + login");
    await alice.ConnectAsync(serverUri, acceptSelfSignedTls: false, overallCts.Token);
    var aliceLogin = await alice.LoginAsync("alice", password: "", deviceId: "smoke-alice", overallCts.Token);
    Console.WriteLine($"[smoke] alice login status={aliceLogin.Status} token={aliceLogin.SessionToken[..Math.Min(8, aliceLogin.SessionToken.Length)]}...");

    Console.WriteLine("[smoke] bob connect + login");
    await bob.ConnectAsync(serverUri, acceptSelfSignedTls: false, overallCts.Token);
    var bobLogin = await bob.LoginAsync("bob", password: "", deviceId: "smoke-bob", overallCts.Token);
    Console.WriteLine($"[smoke] bob login status={bobLogin.Status} token={bobLogin.SessionToken[..Math.Min(8, bobLogin.SessionToken.Length)]}...");

    await Task.Delay(200, overallCts.Token); // presence 이벤트가 먼저 도달하도록 잠깐 대기

    var text = $"hello from alice {DateTime.Now:HH:mm:ss}";
    Console.WriteLine($"[smoke] alice → bob : {text}");
    var ack = await alice.SendTalkAsync("bob", text, overallCts.Token);
    Console.WriteLine($"[smoke] ack result={ack.Result} serverMsgId={ack.ServerTalkId}");

    using var waitCts = CancellationTokenSource.CreateLinkedTokenSource(overallCts.Token);
    waitCts.CancelAfter(TimeSpan.FromSeconds(5));
    string receivedText;
    try
    {
        receivedText = await received.Task.WaitAsync(waitCts.Token);
    }
    catch (OperationCanceledException)
    {
        Console.Error.WriteLine("[smoke] FAIL: bob 이 5초 내 메시지를 수신하지 못했습니다.");
        Environment.Exit(2);
        return;
    }

    if (receivedText != text)
    {
        Console.Error.WriteLine($"[smoke] FAIL: 수신 본문 불일치. 기대={text} 실제={receivedText}");
        Environment.Exit(3);
        return;
    }

    Console.WriteLine("[smoke] OK — 로그인 + 송신 + 수신 전체 검증 통과");

    Console.WriteLine("[smoke] bob logout + dispose");
    await bob.LogoutAsync(overallCts.Token);
    await bob.DisposeAsync();
    bob = null;

    var offlineText = $"offline from alice {DateTime.Now:HH:mm:ss}";
    Console.WriteLine($"[smoke] alice → bob (offline) : {offlineText}");
    var offlineAck = await alice.SendTalkAsync("bob", offlineText, overallCts.Token);
    Console.WriteLine($"[smoke] offline ack result={offlineAck.Result} serverMsgId={offlineAck.ServerTalkId}");
    if (offlineAck.Result != Osori.Proto.V1.TalkSendAck.Types.Result.Queued)
    {
        Console.Error.WriteLine($"[smoke] FAIL: 오프라인 ack 결과가 QUEUED 가 아닙니다. 실제={offlineAck.Result}");
        Environment.Exit(4);
        return;
    }

    bob2 = new MessengerClient();
    Console.WriteLine("[smoke] bob reconnect + login");
    await bob2.ConnectAsync(serverUri, acceptSelfSignedTls: false, overallCts.Token);
    var bob2Login = await bob2.LoginAsync("bob", password: "", deviceId: "smoke-bob-relogin", overallCts.Token);
    Console.WriteLine($"[smoke] bob2 login status={bob2Login.Status} token={bob2Login.SessionToken[..Math.Min(8, bob2Login.SessionToken.Length)]}...");

    var history = await bob2.GetHistoryAsync(0, 50, overallCts.Token);
    var restored = history.Talks.FirstOrDefault(message =>
        string.Equals(message.FromUserId, "alice", StringComparison.OrdinalIgnoreCase) &&
        string.Equals(message.Text, offlineText, StringComparison.Ordinal));

    if (restored is null)
    {
        Console.Error.WriteLine("[smoke] FAIL: history 에 오프라인 메시지가 없습니다.");
        Environment.Exit(4);
        return;
    }

    if (!string.Equals(restored.Text, offlineText, StringComparison.Ordinal))
    {
        Console.Error.WriteLine($"[smoke] FAIL: history 본문 불일치. 기대={offlineText} 실제={restored.Text}");
        Environment.Exit(5);
        return;
    }

    Console.WriteLine("[smoke] OK — history sync 검증 통과");

    //: LastSyncStore 영속성 독립 검증 (임시 디렉토리 사용, 서버 무관)
    Console.WriteLine("[smoke] LastSyncStore 영속성 검증 시작");
    var tempStorePath = Path.Combine(Path.GetTempPath(), $"osori-smoke-{Guid.NewGuid():N}.json");
    try
    {
        var store1 = new LastSyncStore(tempStorePath);
        if (store1.Get("bob") != 0L)
        {
            Console.Error.WriteLine("[smoke] FAIL: LastSyncStore 초기 bob 값이 0이 아닙니다.");
            Environment.Exit(6);
            return;
        }

        store1.Set("bob", 1234567890L);
        store1.Set("carol", 9876543210L);

        // 새 인스턴스로 재로드해서 영속성 확인
        var store2 = new LastSyncStore(tempStorePath);
        if (store2.Get("bob") != 1234567890L)
        {
            Console.Error.WriteLine($"[smoke] FAIL: LastSyncStore 재로드 bob={store2.Get("bob")} 기대=1234567890");
            Environment.Exit(6);
            return;
        }
        if (store2.Get("carol") != 9876543210L)
        {
            Console.Error.WriteLine($"[smoke] FAIL: LastSyncStore 재로드 carol={store2.Get("carol")} 기대=9876543210");
            Environment.Exit(6);
            return;
        }

        // 단조 증가 보호 — 과거 값으로 덮어쓰기 시도
        store2.Set("bob", 100L);
        if (store2.Get("bob") != 1234567890L)
        {
            Console.Error.WriteLine("[smoke] FAIL: LastSyncStore 단조 증가 보호 실패");
            Environment.Exit(6);
            return;
        }

        // Clear 검증
        store2.Clear("bob");
        var store3 = new LastSyncStore(tempStorePath);
        if (store3.Get("bob") != 0L)
        {
            Console.Error.WriteLine("[smoke] FAIL: LastSyncStore Clear 후 재로드 bob 값이 0이 아닙니다.");
            Environment.Exit(6);
            return;
        }
        if (store3.Get("carol") != 9876543210L)
        {
            Console.Error.WriteLine("[smoke] FAIL: LastSyncStore Clear 가 다른 사용자에 영향을 주었습니다.");
            Environment.Exit(6);
            return;
        }

        Console.WriteLine("[smoke] OK — LastSyncStore 영속성 검증 통과");
    }
    finally
    {
        try { if (File.Exists(tempStorePath)) File.Delete(tempStorePath); } catch { }
    }

    //: Reconnect 통합 시나리오 — 실제 WebSocket drop 후 auto-reconnect 전체 경로 검증
    Console.WriteLine("[smoke] reconnect 시나리오 시작");
    charlie = new MessengerClient { AutoReconnectEnabled = true };
    var reconnectedTcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
    charlie.Reconnected += (_, _) =>
    {
        Console.WriteLine("[smoke] charlie Reconnected 이벤트 수신");
        reconnectedTcs.TrySetResult(true);
    };
    charlie.ReconnectFailed += (_, reason) =>
    {
        Console.Error.WriteLine($"[smoke] charlie ReconnectFailed: {reason}");
        reconnectedTcs.TrySetResult(false);
    };

    await charlie.ConnectAsync(serverUri, acceptSelfSignedTls: false, overallCts.Token);
    var charlieLogin = await charlie.LoginAsync("charlie", password: "", deviceId: "smoke-charlie", overallCts.Token);
    Console.WriteLine($"[smoke] charlie login status={charlieLogin.Status}");

    Console.WriteLine("[smoke] charlie WebSocket 강제 종료 (auto-reconnect 유도)");
    await charlie.ForceCloseSocketForTestAsync();

    // 재연결 대기 (최대 15초 — 초기 백오프 1s + 여유)
    using var reconnectWaitCts = CancellationTokenSource.CreateLinkedTokenSource(overallCts.Token);
    reconnectWaitCts.CancelAfter(TimeSpan.FromSeconds(15));
    bool reconnected;
    try
    {
        reconnected = await reconnectedTcs.Task.WaitAsync(reconnectWaitCts.Token);
    }
    catch (OperationCanceledException)
    {
        Console.Error.WriteLine("[smoke] FAIL: 15초 내 Reconnected 이벤트가 오지 않았습니다.");
        Environment.Exit(7);
        return;
    }

    if (!reconnected)
    {
        Console.Error.WriteLine("[smoke] FAIL: ReconnectFailed 이벤트가 먼저 발생했습니다.");
        Environment.Exit(8);
        return;
    }

    // 재연결 후 실제 통신 가능한지 재검증
    var postReconnectText = $"post-reconnect from charlie {DateTime.Now:HH:mm:ss}";
    var postAck = await charlie.SendTalkAsync("alice", postReconnectText, overallCts.Token);
    if (postAck.Result != Osori.Proto.V1.TalkSendAck.Types.Result.Delivered &&
        postAck.Result != Osori.Proto.V1.TalkSendAck.Types.Result.Queued)
    {
        Console.Error.WriteLine($"[smoke] FAIL: 재연결 후 TalkSend 결과={postAck.Result}");
        Environment.Exit(9);
        return;
    }

    Console.WriteLine($"[smoke] OK — reconnect 통합 시나리오 통과 (post-send result={postAck.Result})");

    await charlie.LogoutAsync(overallCts.Token);

    // ========================================================================
    ///c: File upload + download round trip scenario
    // alice uploads a file to bob2, bob2 receives FileAvailablePushed event,
    // bob2 downloads the bytes and verifies they match the original content.
    // ========================================================================
    Console.WriteLine("[smoke] 파일 송수신 시나리오 시작");

    var fileReceived = new TaskCompletionSource<FileAvailableNotification>(TaskCreationOptions.RunContinuationsAsynchronously);
    bob2.FileAvailablePushed += (_, notice) =>
    {
        Console.WriteLine($"[smoke] bob2 file notification: upload_id={notice.UploadId} filename={notice.Filename} size={notice.TotalSize}");
        fileReceived.TrySetResult(notice);
    };

    var tempFile = Path.GetTempFileName();
    var originalBytes = Enumerable.Range(0, 4096).Select(i => (byte)(i % 251)).ToArray();
    await File.WriteAllBytesAsync(tempFile, originalBytes, overallCts.Token);
    Console.WriteLine($"[smoke] alice → bob 파일 업로드 시작 ({originalBytes.Length} bytes)");

    var uploadAck = await alice.UploadFileAsync(tempFile, "bob", ct: overallCts.Token);
    Console.WriteLine($"[smoke] 업로드 complete ack: upload_id={uploadAck.UploadId} total_bytes={uploadAck.TotalBytes} result={uploadAck.Result}");
    if (uploadAck.Result != FileUploadCompleteAck.Types.Result.Ok)
    {
        Console.Error.WriteLine($"[smoke] FAIL: 업로드 완료 ack 실패 result={uploadAck.Result}");
        Environment.Exit(10);
        return;
    }

    using var fileNotifyCts = CancellationTokenSource.CreateLinkedTokenSource(overallCts.Token);
    fileNotifyCts.CancelAfter(TimeSpan.FromSeconds(5));
    FileAvailableNotification notification;
    try
    {
        notification = await fileReceived.Task.WaitAsync(fileNotifyCts.Token);
    }
    catch (OperationCanceledException)
    {
        Console.Error.WriteLine("[smoke] FAIL: 5초 내 FileAvailablePushed 이벤트 없음");
        Environment.Exit(11);
        return;
    }

    if (notification.UploadId != uploadAck.UploadId)
    {
        Console.Error.WriteLine($"[smoke] FAIL: upload_id 불일치 expected={uploadAck.UploadId} got={notification.UploadId}");
        Environment.Exit(12);
        return;
    }

    Console.WriteLine("[smoke] bob2 DownloadFileAsync 호출");
    var downloaded = await bob2.DownloadFileAsync(notification.UploadId, overallCts.Token);
    if (downloaded.Bytes.Length != originalBytes.Length)
    {
        Console.Error.WriteLine($"[smoke] FAIL: 다운로드 크기 불일치 expected={originalBytes.Length} got={downloaded.Bytes.Length}");
        Environment.Exit(13);
        return;
    }
    if (!downloaded.Bytes.SequenceEqual(originalBytes))
    {
        Console.Error.WriteLine("[smoke] FAIL: 다운로드 바이트가 원본과 다름");
        Environment.Exit(14);
        return;
    }
    Console.WriteLine($"[smoke] OK — 파일 송수신 시나리오 통과 (bytes={downloaded.Bytes.Length}, filename={downloaded.Filename})");

    try { File.Delete(tempFile); } catch { /* best-effort */ }

    // ========================================
    // 6) 조직도 snapshot 시나리오
    // ========================================
    Console.WriteLine("[smoke] 조직도 snapshot 시나리오 시작");
    var orgList = await alice.GetOrgListAsync(overallCts.Token);
    if (orgList.Result != OrgListResponse.Types.Result.Ok)
    {
        throw new InvalidOperationException($"OrgList failed result={orgList.Result}");
    }
    Console.WriteLine($"[smoke] OrgList units={orgList.UnitCount} members={orgList.MemberCount} clientReqId={orgList.ClientReqId}");
    foreach (var u in orgList.Units)
    {
        Console.WriteLine($"[smoke]   unit  depth={u.Depth} path={u.Path} parent={(u.ParentPath.Length == 0 ? "(ROOT)" : u.ParentPath)}");
    }
    foreach (var m in orgList.Members)
    {
        Console.WriteLine($"[smoke]   member {m.UserId} org={m.OrgPath} title={m.Title} email={m.Email}");
    }
    if (orgList.UnitCount < 1 || orgList.MemberCount < 1)
    {
        throw new InvalidOperationException("OrgList empty — 시드 데이터 필요 (scripts/seed-org-sample.sql)");
    }
    Console.WriteLine("[smoke] OK — 조직도 snapshot 시나리오 통과");

    // ========================================
    // 7) 방 만들기 + 참여 시나리오, room.created audit 트리거)
    // ========================================
    Console.WriteLine("[smoke] 방 만들기 시나리오 시작");
    var roomName = $"smoke-room-{DateTimeOffset.UtcNow.ToUnixTimeSeconds()}";
    var createAck = await alice.CreateRoomAsync(roomName, overallCts.Token);
    if (createAck.Result != TalkRoomCreateAck.Types.Result.Ok)
    {
        throw new InvalidOperationException($"CreateRoom failed result={createAck.Result}");
    }
    Console.WriteLine($"[smoke] room created roomId={createAck.RoomId} name={roomName}");

    var joinAck = await bob2.JoinRoomAsync(createAck.RoomId, overallCts.Token);
    if (joinAck.Result != TalkRoomJoinAck.Types.Result.Ok && joinAck.Result != TalkRoomJoinAck.Types.Result.AlreadyMember)
    {
        throw new InvalidOperationException($"JoinRoom failed result={joinAck.Result}");
    }
    Console.WriteLine($"[smoke] bob2 joined room result={joinAck.Result}");
    // 방에 메시지 3건 적재 (alice 2, bob2 1) — 그 다음 9b RoomHistory 검증에 사용
    await alice.SendRoomMessageAsync(createAck.RoomId, "room hello #1", overallCts.Token);
    await bob2.SendRoomMessageAsync(createAck.RoomId, "room hello #2 (bob)", overallCts.Token);
    await alice.SendRoomMessageAsync(createAck.RoomId, "room hello #3", overallCts.Token);
    Console.WriteLine("[smoke] OK — 방 만들기 시나리오 통과 (메시지 3건 적재)");

    // ========================================
    // 7b) 방 히스토리 시나리오, TalkRoomHistoryRequest/Response 풀스택)
    // alice 가 직접 만든 방 → 멤버 → OK + 3건 반환
    // charlie 가 join 안 한 방 → NOT_A_MEMBER
    // ========================================
    Console.WriteLine("[smoke] 방 히스토리 시나리오 시작");
    var roomHist = await alice.GetRoomHistoryAsync(createAck.RoomId, sinceMillis: 0, limit: 50, ct: overallCts.Token);
    if (roomHist.Status != TalkRoomHistoryResponse.Types.Status.Ok)
    {
        throw new InvalidOperationException($"RoomHistory(alice) status={roomHist.Status}, expected Ok");
    }
    if (roomHist.Talks.Count < 3)
    {
        throw new InvalidOperationException($"RoomHistory(alice) 메시지 부족: got={roomHist.Talks.Count}, expected>=3");
    }
    var texts = roomHist.Talks.Select(m => m.Text).ToList();
    Console.WriteLine($"[smoke] room history alice: {roomHist.Talks.Count}건, hasMore={roomHist.HasMore}, texts=[{string.Join(" | ", texts)}]");

    // limit=2 → has_more=true 검증
    var pageOne = await alice.GetRoomHistoryAsync(createAck.RoomId, sinceMillis: 0, limit: 2, ct: overallCts.Token);
    if (pageOne.Status != TalkRoomHistoryResponse.Types.Status.Ok || pageOne.Talks.Count != 2 || !pageOne.HasMore)
    {
        throw new InvalidOperationException($"RoomHistory page1 검증 실패 status={pageOne.Status} count={pageOne.Talks.Count} hasMore={pageOne.HasMore}");
    }

    // charlie 는 이 방 멤버가 아니므로 NOT_A_MEMBER 받아야 함
    await using (var notMember = new MessengerClient())
    {
        await notMember.ConnectAsync(serverUri, acceptSelfSignedTls: false, overallCts.Token);
        await notMember.LoginAsync("charlie", password: "", deviceId: "smoke-charlie-roomhist", overallCts.Token);
        var denied = await notMember.GetRoomHistoryAsync(createAck.RoomId, sinceMillis: 0, limit: 10, ct: overallCts.Token);
        if (denied.Status != TalkRoomHistoryResponse.Types.Status.NotAMember)
        {
            throw new InvalidOperationException($"RoomHistory(charlie) 권한 검증 실패: expected NotAMember, got {denied.Status}");
        }
        await notMember.LogoutAsync(overallCts.Token);
        Console.WriteLine("[smoke] room history charlie(비멤버) NOT_A_MEMBER 거부 OK");
    }
    Console.WriteLine("[smoke] OK — 방 히스토리 시나리오 통과 (멤버 OK + limit/has_more + 비멤버 거부)");

    // ========================================
    // 8) 메시지 검색 시나리오, MSG_SEARCH_REQ/RES 풀스택 라우팅 증명)
    // 이전 시나리오에서 alice↔bob 간 "hello from alice" 가 최소 2건(온라인+오프라인) 적재됨.
    // ========================================
    Console.WriteLine("[smoke] 메시지 검색 시나리오 시작");
    var searchKeyword = "hello from alice";
    var searchRes = await alice.SearchMessagesAsync(searchKeyword, limit: 50, ct: overallCts.Token);
    if (searchRes.Result != TalkSearchResponse.Types.Result.Ok)
    {
        throw new InvalidOperationException($"SearchMessages failed result={searchRes.Result}");
    }
    Console.WriteLine($"[smoke] 검색 '{searchKeyword}' matched={searchRes.MatchedCount} hasMore={searchRes.HasMore}");
    if (searchRes.MatchedCount == 0)
    {
        throw new InvalidOperationException("SearchMessages: expected >=1 match, got 0");
    }
    foreach (var hit in searchRes.Talks)
    {
        Console.WriteLine($"[smoke]   hit from={hit.FromUserId} to={hit.ToUserId} text='{hit.Text}' ts={hit.ServerTs}");
    }
    var emptyRes = await alice.SearchMessagesAsync("this-keyword-never-appears-xyz-999", limit: 10, ct: overallCts.Token);
    if (emptyRes.Result != TalkSearchResponse.Types.Result.Ok || emptyRes.MatchedCount != 0)
    {
        throw new InvalidOperationException($"SearchMessages empty expected Ok+0 but got result={emptyRes.Result} matched={emptyRes.MatchedCount}");
    }
    Console.WriteLine("[smoke] OK — 메시지 검색 시나리오 통과 (키워드 매칭 + 없는 키워드 빈 결과)");

    // ========================================
    // 9) 쪽지(message) 시나리오
    // alice → bob 쪽지 발송 → bob2 가 MessagePushed 이벤트 수신 →
    // bob2 GetMessageInboxAsync 로 목록 확인 → MarkMessageReadAsync → DeleteMessageAsync.
    // ========================================
    Console.WriteLine("[smoke] 쪽지 시나리오 시작");
    var memoReceived = new TaskCompletionSource<MessageReceived>(TaskCreationOptions.RunContinuationsAsynchronously);
    bob2.MessagePushed += (_, m) =>
    {
        Console.WriteLine($"[smoke] bob2 memo push: from={m.FromUserId} title='{m.Title}' id={m.ServerMessageId}");
        memoReceived.TrySetResult(m);
    };

    var memoTitle = $"smoke memo {DateTime.Now:HH:mm:ss}";
    var memoBody = "오소리 메신저 smoke 9단 — 쪽지 라운드트립 검증";
    var sendAck = await alice.SendMessageAsync(new[] { "bob" }, memoTitle, memoBody, null, overallCts.Token);
    Console.WriteLine($"[smoke] message send ack: result={sendAck.Result} serverMessageId={sendAck.ServerMessageId}");
    if (sendAck.Result != MessageSendAck.Types.Result.Ok)
    {
        throw new InvalidOperationException($"SendMessage failed result={sendAck.Result}");
    }

    using (var memoCts = CancellationTokenSource.CreateLinkedTokenSource(overallCts.Token))
    {
        memoCts.CancelAfter(TimeSpan.FromSeconds(5));
        try
        {
            var pushed = await memoReceived.Task.WaitAsync(memoCts.Token);
            if (pushed.ServerMessageId != sendAck.ServerMessageId)
            {
                throw new InvalidOperationException($"memo push id mismatch sent={sendAck.ServerMessageId} got={pushed.ServerMessageId}");
            }
        }
        catch (OperationCanceledException)
        {
            throw new InvalidOperationException("5초 내 MessagePushed 이벤트 없음");
        }
    }

    var inbox = await bob2.GetMessageInboxAsync(0, 20, overallCts.Token);
    if (inbox.Status != MessageListResponse.Types.Status.Ok)
    {
        throw new InvalidOperationException($"GetMessageInbox failed status={inbox.Status}");
    }
    var found = inbox.Entries.FirstOrDefault(e => e.ServerMessageId == sendAck.ServerMessageId);
    if (found is null)
    {
        throw new InvalidOperationException($"inbox 에서 발송한 쪽지({sendAck.ServerMessageId}) 를 찾지 못함");
    }
    Console.WriteLine($"[smoke] inbox entries={inbox.Entries.Count} hasMore={inbox.HasMore} 발송 쪽지 발견");

    var readAck = await bob2.MarkMessageReadAsync(sendAck.ServerMessageId, overallCts.Token);
    if (readAck.Result != MessageMarkReadAck.Types.Result.Ok)
    {
        throw new InvalidOperationException($"MarkMessageRead failed result={readAck.Result}");
    }
    Console.WriteLine($"[smoke] markRead ok readAt={readAck.ReadAt}");

    var sentBox = await alice.GetMessageSentAsync(0, 20, overallCts.Token);
    if (sentBox.Status != MessageListResponse.Types.Status.Ok || !sentBox.Entries.Any(e => e.ServerMessageId == sendAck.ServerMessageId))
    {
        throw new InvalidOperationException("alice 보낸쪽지함에 발송 메모 누락");
    }
    Console.WriteLine($"[smoke] sent entries={sentBox.Entries.Count} 발송자 측 발견");

    var delAck = await bob2.DeleteMessageAsync(sendAck.ServerMessageId, overallCts.Token);
    if (delAck.Result != MessageDeleteAck.Types.Result.Ok)
    {
        throw new InvalidOperationException($"DeleteMessage failed result={delAck.Result}");
    }
    Console.WriteLine("[smoke] OK — 쪽지 시나리오 통과 (송신 + 푸시 + 받은쪽지함 + 읽음 + 보낸쪽지함 + 삭제)");

    await alice.LogoutAsync(overallCts.Token);
    await bob2.LogoutAsync(overallCts.Token);
    Console.WriteLine("[smoke] 정상 종료");
}
finally
{
    if (charlie is not null)
    {
        await charlie.DisposeAsync();
    }

    if (bob2 is not null)
    {
        await bob2.DisposeAsync();
    }

    if (bob is not null)
    {
        await bob.DisposeAsync();
    }

    if (alice is not null)
    {
        await alice.DisposeAsync();
    }
}

using Osori.Proto.V1;

namespace OsoriClient.Core.Tests;

/// <summary>결과 코드 → 한국어 문구(내부 열거값 노출 금지) 검증.</summary>
public sealed class ResultMessagesTests
{
    [Fact]
    public void Login_InvalidCredentials_IsKorean_NoEnumName()
    {
        var text = ResultMessages.Login(LoginResponse.Types.Status.InvalidCredentials);
        Assert.Equal("아이디 또는 비밀번호가 올바르지 않습니다.", text);
        Assert.DoesNotContain("InvalidCredentials", text);
    }

    [Fact]
    public void RateLimited_SameNoticeAcrossRequestKinds()
    {
        Assert.Equal(ResultMessages.RateLimited, ResultMessages.RoomSend(TalkRoomSendAck.Types.Result.RateLimited));
        Assert.Equal(ResultMessages.RateLimited, ResultMessages.MessageSend(MessageSendAck.Types.Result.RateLimited));
        Assert.Equal(ResultMessages.RateLimited, ResultMessages.FileUploadStart(FileUploadStartAck.Types.Result.RateLimited));
        Assert.Equal(ResultMessages.RateLimited, ResultMessages.RoomCreate(TalkRoomCreateAck.Types.Result.RateLimited));
        Assert.Equal(ResultMessages.RateLimited, ResultMessages.RoomInvite(TalkRoomInviteAck.Types.Result.RateLimited));
        Assert.Equal(ResultMessages.RateLimited, ResultMessages.RoomRename(TalkRoomRenameAck.Types.Result.RateLimited));
        Assert.Equal(ResultMessages.RateLimited, ResultMessages.RoomLeave(TalkRoomLeaveAck.Types.Result.RateLimited));
        Assert.Contains("너무 빠르게", ResultMessages.RateLimited);
    }

    [Fact]
    public void AllEnumValues_MapToNonEmptyKorean_WithoutEnumNames()
    {
        void Check<T>(Func<T, string> f, params T[] skip) where T : struct, Enum
        {
            foreach (var v in Enum.GetValues<T>())
            {
                if (skip.Contains(v)) continue;
                var s = f(v);
                Assert.False(string.IsNullOrWhiteSpace(s), $"{typeof(T).Name}.{v}");
                Assert.DoesNotContain(v.ToString(), s);
            }
        }
        Check<LoginResponse.Types.Status>(ResultMessages.Login);
        Check<TalkRoomSendAck.Types.Result>(ResultMessages.RoomSend, TalkRoomSendAck.Types.Result.Ok);
        Check<MessageSendAck.Types.Result>(ResultMessages.MessageSend);
        Check<FileUploadStartAck.Types.Result>(ResultMessages.FileUploadStart);
        Check<TalkSearchResponse.Types.Result>(ResultMessages.Search);
    }

    [Fact]
    public void ServerRejectedException_IsInvalidOperation_WithKoreanMessage()
    {
        var ex = new ServerRejectedException(ResultMessages.FileUploadStart(FileUploadStartAck.Types.Result.FileTooLarge), "FileTooLarge");
        Assert.IsAssignableFrom<InvalidOperationException>(ex);
        Assert.Equal("파일이 너무 큽니다.", ex.Message);
        Assert.Equal("FileTooLarge", ex.Code);
    }
}

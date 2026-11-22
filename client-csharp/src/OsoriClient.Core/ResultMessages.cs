using Osori.Proto.V1;

namespace OsoriClient.Core;

/// <summary>
/// 서버 응답 결과 코드를 사용자에게 보여 줄 한국어 문구로 바꾼다.
/// 화면에 `InvalidCredentials`, `RateLimited` 같은 내부 열거값이 그대로 나오지 않게 한다.
/// </summary>
public static class ResultMessages
{
    public const string RateLimited = "너무 빠르게 보내고 있습니다. 잠시 후 다시 시도해 주세요.";
    private const string ServerError = "서버에서 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
    private const string Unknown = "알 수 없는 오류가 났습니다.";

    public static string Login(LoginResponse.Types.Status status) => status switch
    {
        LoginResponse.Types.Status.Ok => "로그인되었습니다.",
        LoginResponse.Types.Status.InvalidCredentials => "아이디 또는 비밀번호가 올바르지 않습니다.",
        LoginResponse.Types.Status.AlreadyOnline => "이미 다른 곳에서 로그인되어 있습니다.",
        LoginResponse.Types.Status.VersionMismatch => "프로그램 버전이 서버와 맞지 않습니다. 최신 버전으로 업데이트해 주세요.",
        LoginResponse.Types.Status.ServerError => ServerError,
        _ => Unknown,
    };

    public static string RoomSend(TalkRoomSendAck.Types.Result r) => r switch
    {
        TalkRoomSendAck.Types.Result.Ok => "",
        TalkRoomSendAck.Types.Result.NotMember => "이 대화방의 멤버가 아니라 보낼 수 없습니다.",
        TalkRoomSendAck.Types.Result.RoomNotFound => "대화방을 찾을 수 없습니다.",
        TalkRoomSendAck.Types.Result.RateLimited => RateLimited,
        TalkRoomSendAck.Types.Result.ServerError => ServerError,
        _ => Unknown,
    };

    public static string RoomCreate(TalkRoomCreateAck.Types.Result r) => r switch
    {
        TalkRoomCreateAck.Types.Result.InvalidName => "대화방 이름이 올바르지 않습니다.",
        TalkRoomCreateAck.Types.Result.DuplicateMembers => "같은 구성원의 대화방이 이미 있습니다.",
        TalkRoomCreateAck.Types.Result.RateLimited => RateLimited,
        TalkRoomCreateAck.Types.Result.ServerError => ServerError,
        _ => Unknown,
    };

    public static string RoomInvite(TalkRoomInviteAck.Types.Result r) => r switch
    {
        TalkRoomInviteAck.Types.Result.RoomNotFound => "대화방을 찾을 수 없습니다.",
        TalkRoomInviteAck.Types.Result.NotMember => "이 대화방의 멤버가 아니라 초대할 수 없습니다.",
        TalkRoomInviteAck.Types.Result.NoValidTargets => "초대할 사람이 없습니다(이미 모두 참여 중).",
        TalkRoomInviteAck.Types.Result.RateLimited => RateLimited,
        TalkRoomInviteAck.Types.Result.ServerError => ServerError,
        _ => Unknown,
    };

    public static string RoomRename(TalkRoomRenameAck.Types.Result r) => r switch
    {
        TalkRoomRenameAck.Types.Result.RoomNotFound => "대화방을 찾을 수 없습니다.",
        TalkRoomRenameAck.Types.Result.NotMember => "이 대화방의 멤버가 아니라 이름을 바꿀 수 없습니다.",
        TalkRoomRenameAck.Types.Result.InvalidName => "이름은 1~255자로 입력해 주세요.",
        TalkRoomRenameAck.Types.Result.RateLimited => RateLimited,
        TalkRoomRenameAck.Types.Result.ServerError => ServerError,
        _ => Unknown,
    };

    public static string RoomLeave(TalkRoomLeaveAck.Types.Result r) => r switch
    {
        TalkRoomLeaveAck.Types.Result.RoomNotFound => "대화방을 찾을 수 없습니다.",
        TalkRoomLeaveAck.Types.Result.NotMember => "이미 나간 대화방입니다.",
        TalkRoomLeaveAck.Types.Result.RateLimited => RateLimited,
        TalkRoomLeaveAck.Types.Result.ServerError => ServerError,
        _ => Unknown,
    };

    public static string MessageSend(MessageSendAck.Types.Result r) => r switch
    {
        MessageSendAck.Types.Result.InvalidRequest => "받는 사람과 제목을 확인해 주세요.",
        MessageSendAck.Types.Result.PartialFailure => "일부 받는 사람에게 보내지 못했습니다.",
        MessageSendAck.Types.Result.RateLimited => RateLimited,
        MessageSendAck.Types.Result.ServerError => ServerError,
        _ => Unknown,
    };

    public static string FileUploadStart(FileUploadStartAck.Types.Result r) => r switch
    {
        FileUploadStartAck.Types.Result.InvalidTarget => "받는 사람이 올바르지 않습니다.",
        FileUploadStartAck.Types.Result.FileTooLarge => "파일이 너무 큽니다.",
        FileUploadStartAck.Types.Result.RateLimited => RateLimited,
        FileUploadStartAck.Types.Result.ServerError => ServerError,
        _ => Unknown,
    };

    public static string Search(TalkSearchResponse.Types.Result r) => r switch
    {
        TalkSearchResponse.Types.Result.RateLimited => "검색을 너무 자주 했습니다. 잠시 후 다시 시도해 주세요.",
        TalkSearchResponse.Types.Result.ServerError => "검색하지 못했습니다. 잠시 후 다시 시도해 주세요.",
        _ => Unknown,
    };
}

/// <summary>서버가 거절한 요청(결과 코드 포함)을 호출부가 구분할 수 있게 하는 예외.</summary>
public sealed class ServerRejectedException : InvalidOperationException
{
    public ServerRejectedException(string userMessage, string code) : base(userMessage)
    {
        Code = code;
    }

    /// <summary>원래 결과 코드 이름(로그용).</summary>
    public string Code { get; }
}

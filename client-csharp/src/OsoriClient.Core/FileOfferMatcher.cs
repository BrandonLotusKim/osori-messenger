using System;

namespace OsoriClient.Core;

/// <summary>파일 수신 알림이 이 대화창 것인지 판별한다.</summary>
public static class FileOfferMatcher
{
    /// <param name="offerRoomId">알림의 방 ID(방에 올린 파일이면 값 있음, 1:1 직접 전송이면 빈 값)</param>
    /// <param name="offerFromUserId">보낸 사람</param>
    /// <param name="windowRoomId">이 창이 방 모드면 그 방 ID, 아니면 null</param>
    /// <param name="windowPeerUserId">이 창의 1:1 상대(없으면 null)</param>
    public static bool Matches(string? offerRoomId, string? offerFromUserId, string? windowRoomId, string? windowPeerUserId)
    {
        if (!string.IsNullOrEmpty(offerRoomId))
            return !string.IsNullOrEmpty(windowRoomId)
                && string.Equals(offerRoomId, windowRoomId, StringComparison.OrdinalIgnoreCase);
        return windowPeerUserId is not null
            && string.Equals(offerFromUserId, windowPeerUserId, StringComparison.OrdinalIgnoreCase);
    }
}

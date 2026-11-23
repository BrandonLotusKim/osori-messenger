namespace OsoriClient.Core;

/// <summary>
/// 설정의 "글꼴 크기(pt)"를 화면 글자 크기로 바꾸는 규칙. 기본값 14 일 때 대화 말풍선·쪽지 본문이
/// 원래 디자인 크기(13)가 되도록 1 작게 쓰고, 설정 허용 범위(8~48)를 벗어나면 잘라 낸다.
/// </summary>
public static class DisplayFont
{
    public const int MinSetting = 8;
    public const int MaxSetting = 48;

    /// <summary>대화 말풍선·입력칸·쪽지 본문 글자 크기.</summary>
    public static double Body(int settingSize) => Math.Clamp(settingSize, MinSetting, MaxSetting) - 1;

    /// <summary>설정값이 허용 범위인지.</summary>
    public static bool IsValidSetting(int settingSize) => settingSize is >= MinSetting and <= MaxSetting;
}

using System;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;

namespace OsoriClient.Wpf.Security;

/// <summary>
/// DPAPI 래퍼 — 자동로그인 비밀번호를 현재 Windows 사용자 계정 scope 로 암호화/복호화한다 (OC-D01).
///
/// 보안 모델:
///   - DPAPI 는 같은 Windows 사용자 계정에서만 복호화 가능 — 다른 계정/PC 이동 시 복호화 실패.
///   - `_entropy` 는 앱 단위 추가 시드. 다른 앱이 같은 계정이라도 이 entropy 없이는 복호화 못 함.
///   - `CurrentUser` scope 사용 (Machine scope 는 로컬 관리자면 복호화 가능해 배제).
///
/// 한계:
///   - 사용자가 Windows 계정을 재설정하면 복호화 불가 → 자동로그인 실패 → 재입력 유도.
///   - 이 기능으로 서버 인증을 강화하진 않음. 순수 편의성.
/// </summary>
[SupportedOSPlatform("windows")]
public static class CredentialProtector
{
    // 앱 전용 entropy — 바뀌면 기존 저장 자격이 무효화되니 유지할 것.
    private static readonly byte[] _entropy = Encoding.UTF8.GetBytes("com.osori.messenger.v1.autologin");

    /// <summary>
    /// 평문 비밀번호를 암호화해 저장용 Base64 문자열로 반환.
    /// 빈 입력은 빈 문자열 반환.
    /// </summary>
    public static string Protect(string plainText)
    {
        if (string.IsNullOrEmpty(plainText)) return string.Empty;
        var plainBytes = Encoding.UTF8.GetBytes(plainText);
        var cipher = ProtectedData.Protect(plainBytes, _entropy, DataProtectionScope.CurrentUser);
        return Convert.ToBase64String(cipher);
    }

    /// <summary>
    /// 저장된 Base64 cipher 를 복호화. 실패(손상/다른 계정/entropy 변경) 시 null 반환.
    /// null 을 받으면 호출자는 자동로그인 꺼두고 사용자에게 재입력 안내.
    /// </summary>
    public static string? Unprotect(string? cipherBase64)
    {
        if (string.IsNullOrEmpty(cipherBase64)) return null;
        try
        {
            var cipher = Convert.FromBase64String(cipherBase64);
            var plainBytes = ProtectedData.Unprotect(cipher, _entropy, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(plainBytes);
        }
        catch (Exception)
        {
            return null;
        }
    }
}

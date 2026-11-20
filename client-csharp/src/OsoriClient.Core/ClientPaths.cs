namespace OsoriClient.Core;

/// <summary>
/// 클라이언트 로컬 데이터 디렉토리 해석.
///
/// 기본: <c>%LOCALAPPDATA%\OsoriClient</c>
/// 환경변수 <c>OSORI_PROFILE</c> 이 설정돼 있으면
/// <c>%LOCALAPPDATA%\OsoriClient\profiles\&lt;프로필&gt;</c> 로 격리한다.
///
/// 같은 PC 에서 클라이언트를 여러 개 동시에 띄울 때(App 의 <c>multi</c> 인자)
/// 설정/동기화 파일이 서로 덮어쓰지 않도록 하기 위한 것.
/// </summary>
public static class ClientPaths
{
    public const string ProfileEnvVar = "OSORI_PROFILE";

    /// <summary>설정·동기화 파일이 저장되는 베이스 디렉토리.</summary>
    public static string BaseDir
    {
        get
        {
            var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var root = Path.Combine(localAppData, "OsoriClient");

            var profile = Environment.GetEnvironmentVariable(ProfileEnvVar);
            if (string.IsNullOrWhiteSpace(profile))
            {
                return root;
            }

            return Path.Combine(root, "profiles", Sanitize(profile));
        }
    }

    /// <summary>
    /// 2026-09-17 추가 — OSORI_PROFILE(멀티 인스턴스 격리)과 무관하게 항상 같은 경로.
    /// 로그인 자격증명(LoginCredentialsStore)처럼 "어느 계정으로 로그인했는지"를 기준으로
    /// 저장해야 하는 데이터는 프로세스 PID 기반 프로필 안에 넣으면 재실행마다 새 프로필이라
    /// 절대 기억되지 않는다 — multi 모드에서 자동로그인/비밀번호저장/마지막 아이디가 전혀
    /// 동작하지 않던 버그의 원인이 이것이었다.
    /// </summary>
    public static string SharedRootDir
    {
        get
        {
            var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            return Path.Combine(localAppData, "OsoriClient");
        }
    }

    private static string Sanitize(string value)
    {
        foreach (var invalid in Path.GetInvalidFileNameChars())
        {
            value = value.Replace(invalid, '_');
        }
        return value.Trim();
    }
}

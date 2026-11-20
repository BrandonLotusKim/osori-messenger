namespace OsoriClient.Core.Tests;

/// <summary>
/// ClientPaths.BaseDir 이 OSORI_PROFILE 환경변수에 따라 격리 디렉토리를 주는지 검증.
/// (App 의 multi 인자 → 같은 PC 다중 인스턴스 시 설정 파일 충돌 방지)
///
/// 환경변수를 건드리므로 이 클래스는 병렬 실행에서 제외한다.
/// </summary>
[Collection("env-mutating")]
public sealed class ClientPathsTests : IDisposable
{
    private readonly string? _original =
        Environment.GetEnvironmentVariable(ClientPaths.ProfileEnvVar);

    [Fact]
    public void BaseDir_NoProfile_UsesRoot()
    {
        Environment.SetEnvironmentVariable(ClientPaths.ProfileEnvVar, null);

        var dir = ClientPaths.BaseDir;

        Assert.EndsWith(Path.Combine("OsoriClient"), dir);
        Assert.DoesNotContain("profiles", dir);
    }

    [Fact]
    public void BaseDir_WithProfile_IsIsolatedSubdir()
    {
        Environment.SetEnvironmentVariable(ClientPaths.ProfileEnvVar, "multi-1234");

        var dir = ClientPaths.BaseDir;

        Assert.EndsWith(Path.Combine("OsoriClient", "profiles", "multi-1234"), dir);
    }

    [Fact]
    public void BaseDir_ProfileWithInvalidChars_IsSanitized()
    {
        Environment.SetEnvironmentVariable(ClientPaths.ProfileEnvVar, "a/b:c");

        var dir = ClientPaths.BaseDir;

        Assert.EndsWith(Path.Combine("profiles", "a_b_c"), dir);
    }

    [Fact]
    public void BaseDir_BlankProfile_FallsBackToRoot()
    {
        Environment.SetEnvironmentVariable(ClientPaths.ProfileEnvVar, "   ");

        var dir = ClientPaths.BaseDir;

        Assert.DoesNotContain("profiles", dir);
    }

    public void Dispose()
        => Environment.SetEnvironmentVariable(ClientPaths.ProfileEnvVar, _original);
}

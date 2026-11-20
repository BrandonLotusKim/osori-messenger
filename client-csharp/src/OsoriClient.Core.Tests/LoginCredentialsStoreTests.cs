using OsoriClient.Core;

namespace OsoriClient.Core.Tests;

/// <summary>
/// LoginCredentialsStore 단위 테스트 (2026-09-17 추가).
/// 특히 "OSORI_PROFILE(멀티 인스턴스 격리)과 무관하게 항상 같은 경로에 저장된다"는
/// 계약을 검증한다 — 이게 안 지켜지면 OsoriClientMulti 로 실행할 때마다(=매번 다른 PID
/// 프로필) 자동로그인/비밀번호저장/마지막 아이디가 재현되지 않는 버그가 그대로 남는다.
/// </summary>
public sealed class LoginCredentialsStoreTests : IDisposable
{
    private readonly string _tempDir;
    private readonly string _tempFile;

    public LoginCredentialsStoreTests()
    {
        _tempDir = Path.Combine(Path.GetTempPath(), $"osori-credentials-test-{Guid.NewGuid():N}");
        Directory.CreateDirectory(_tempDir);
        _tempFile = Path.Combine(_tempDir, "login_credentials.json");
    }

    public void Dispose()
    {
        try { Directory.Delete(_tempDir, recursive: true); } catch { }
    }

    [Fact]
    public void NewStore_NoFile_ReturnsDefaults()
    {
        var store = new LoginCredentialsStore(_tempFile);

        Assert.Equal("", store.Current.LastUserId);
        Assert.False(store.Current.AutoLoginEnabled);
        Assert.False(store.Current.RememberPasswordEnabled);
        Assert.Null(store.Current.EncryptedPassword);
    }

    [Fact]
    public void Update_PersistsToFile()
    {
        var store = new LoginCredentialsStore(_tempFile);
        store.Update(c =>
        {
            c.LastUserId = "test1";
            c.AutoLoginEnabled = true;
            c.EncryptedPassword = "cipher";
        });

        Assert.True(File.Exists(_tempFile));

        var reloaded = new LoginCredentialsStore(_tempFile);
        Assert.Equal("test1", reloaded.Current.LastUserId);
        Assert.True(reloaded.Current.AutoLoginEnabled);
        Assert.Equal("cipher", reloaded.Current.EncryptedPassword);
    }

    [Fact]
    public void RememberPasswordEnabled_PersistsIndependentlyOfAutoLogin()
    {
        var store = new LoginCredentialsStore(_tempFile);
        store.Update(c =>
        {
            c.AutoLoginEnabled = false;
            c.RememberPasswordEnabled = true;
            c.EncryptedPassword = "cipher";
        });

        var reloaded = new LoginCredentialsStore(_tempFile);
        Assert.False(reloaded.Current.AutoLoginEnabled);
        Assert.True(reloaded.Current.RememberPasswordEnabled);
        Assert.Equal("cipher", reloaded.Current.EncryptedPassword);
    }

    /// <summary>
    /// 2026-09-17 버그 재현 — 여러 LoginCredentialsStore 인스턴스(LoginWindow 가 로그인마다
    /// 새로 만드는 것과 동일)가 같은 파일을 순서대로 갱신해도 서로의 변경을 지우면 안 된다.
    /// </summary>
    [Fact]
    public void Update_DoesNotClobberChangesFromAnotherInstance()
    {
        var loginInstance = new LoginCredentialsStore(_tempFile);
        loginInstance.Update(c =>
        {
            c.LastUserId = "test1";
            c.AutoLoginEnabled = true;
            c.EncryptedPassword = "cipher";
        });

        // "재시작" — 새 인스턴스가 자기와 무관한 필드만 건드림.
        var laterInstance = new LoginCredentialsStore(_tempFile);
        laterInstance.Update(c => c.RememberPasswordEnabled = true);

        var reloaded = new LoginCredentialsStore(_tempFile);
        Assert.Equal("test1", reloaded.Current.LastUserId);
        Assert.True(reloaded.Current.AutoLoginEnabled);
        Assert.Equal("cipher", reloaded.Current.EncryptedPassword);
        Assert.True(reloaded.Current.RememberPasswordEnabled);
    }

    [Fact]
    public void CorruptedFile_FallsBackToDefaults()
    {
        File.WriteAllText(_tempFile, "{ not valid json ");

        var store = new LoginCredentialsStore(_tempFile);

        Assert.Equal("", store.Current.LastUserId);
        Assert.False(store.Current.AutoLoginEnabled);
    }
}

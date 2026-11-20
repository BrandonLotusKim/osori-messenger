using OsoriClient.Core;

namespace OsoriClient.Core.Tests;

/// <summary>
/// SettingsStore 단위 테스트.
/// 임시 파일 경로를 써서 기본값/업데이트/재로드/리셋/손상 파일 처리 검증.
/// 2026-09-17: 로그인 자격증명(LastUserId/AutoLoginEnabled/RememberPasswordEnabled/
/// EncryptedPassword)은 LoginCredentialsStore 로 이전 — 그쪽 테스트는 LoginCredentialsStoreTests 참고.
/// </summary>
public sealed class SettingsStoreTests : IDisposable
{
    private readonly string _tempDir;
    private readonly string _tempFile;

    public SettingsStoreTests()
    {
        _tempDir = Path.Combine(Path.GetTempPath(), $"osori-settings-test-{Guid.NewGuid():N}");
        Directory.CreateDirectory(_tempDir);
        _tempFile = Path.Combine(_tempDir, "settings.json");
    }

    public void Dispose()
    {
        try { Directory.Delete(_tempDir, recursive: true); } catch { }
    }

    [Fact]
    public void NewStore_NoFile_ReturnsDefaults()
    {
        var store = new SettingsStore(_tempFile);

        Assert.Equal("127.0.0.1", store.Current.ServerHost);
        Assert.Equal(7071, store.Current.ServerPort);
        Assert.False(store.Current.UseTls);
        Assert.True(store.Current.AutoReconnect);
        Assert.Equal(14, store.Current.FontSize);
        Assert.Equal("light", store.Current.Theme);
    }

    [Fact]
    public void Update_PersistsToFile()
    {
        var store = new SettingsStore(_tempFile);
        store.Update(s =>
        {
            s.ServerHost = "messenger.corp.example";
            s.ServerPort = 8443;
            s.UseTls = true;
            s.FontSize = 18;
            s.Theme = "dark";
        });

        Assert.True(File.Exists(_tempFile));

        // 재로드해서 값 유지 확인
        var reloaded = new SettingsStore(_tempFile);
        Assert.Equal("messenger.corp.example", reloaded.Current.ServerHost);
        Assert.Equal(8443, reloaded.Current.ServerPort);
        Assert.True(reloaded.Current.UseTls);
        Assert.Equal(18, reloaded.Current.FontSize);
        Assert.Equal("dark", reloaded.Current.Theme);
    }

    [Fact]
    public void Current_ReturnsCloneNotLive()
    {
        var store = new SettingsStore(_tempFile);
        var snapshot = store.Current;
        snapshot.FontSize = 99;

        // 스냅샷 수정해도 내부 상태는 그대로여야 한다
        Assert.Equal(14, store.Current.FontSize);
    }

    [Fact]
    public void Replace_OverwritesAllFields()
    {
        var store = new SettingsStore(_tempFile);
        store.Update(s => s.ServerHost = "original");

        var next = new ClientSettings
        {
            ServerHost = "192.168.0.1",
            ServerPort = 9000,
            Theme = "dark",
        };
        store.Replace(next);

        Assert.Equal("192.168.0.1", store.Current.ServerHost);
        Assert.Equal(9000, store.Current.ServerPort);
        Assert.Equal("dark", store.Current.Theme);
    }

    [Fact]
    public void ResetToDefaults_ClearsChanges()
    {
        var store = new SettingsStore(_tempFile);
        store.Update(s =>
        {
            s.ServerHost = "custom";
            s.FontSize = 22;
            s.Theme = "dark";
        });

        store.ResetToDefaults();

        Assert.Equal("127.0.0.1", store.Current.ServerHost);
        Assert.Equal(14, store.Current.FontSize);
        Assert.Equal("light", store.Current.Theme);
    }

    [Fact]
    public void CorruptedFile_FallsBackToDefaults()
    {
        // 깨진 JSON 저장
        File.WriteAllText(_tempFile, "{ this is not valid json ");

        var store = new SettingsStore(_tempFile);

        Assert.Equal("127.0.0.1", store.Current.ServerHost);
        Assert.Equal(7071, store.Current.ServerPort);
    }

    [Fact]
    public void EmptyFile_FallsBackToDefaults()
    {
        File.WriteAllText(_tempFile, "");

        var store = new SettingsStore(_tempFile);

        Assert.Equal("127.0.0.1", store.Current.ServerHost);
    }

    /// <summary>
    /// 2026-09-17 버그 수정 검증 — LoginWindow/MainWindow/TalkWindow 처럼 여러 SettingsStore
    /// 인스턴스가 같은 파일을 동시에 쓰는 상황을 재현한다. 예전엔 각 인스턴스가 생성 시점에
    /// 한 번만 읽은 캐시를 그대로 썼다가 저장해서, 나중에 저장하는 쪽이 먼저 저장된 값을
    /// (자기가 안 건드린 필드까지) 통째로 지워버렸다.
    /// </summary>
    [Fact]
    public void Update_DoesNotClobberChangesFromAnotherInstance()
    {
        var firstStore = new SettingsStore(_tempFile);
        var secondStore = new SettingsStore(_tempFile);

        // 첫 번째 인스턴스가 먼저 뭔가를 저장.
        firstStore.Update(s =>
        {
            s.ServerHost = "messenger.corp.example";
            s.Theme = "dark";
        });

        // 두 번째 인스턴스(구성 시점이 첫 번째보다 이르더라도)가 자기와 무관한 필드(창 크기)만 저장.
        secondStore.Update(s =>
        {
            s.MainWindowBounds = new WindowBounds { X = 10, Y = 20, Width = 300, Height = 400 };
        });

        var reloaded = new SettingsStore(_tempFile);
        Assert.Equal("messenger.corp.example", reloaded.Current.ServerHost);
        Assert.Equal("dark", reloaded.Current.Theme);
        Assert.NotNull(reloaded.Current.MainWindowBounds);
        Assert.Equal(300, reloaded.Current.MainWindowBounds!.Width);
    }

    [Fact]
    public void CustomPath_IsHonored()
    {
        var path1 = Path.Combine(_tempDir, "a.json");
        var path2 = Path.Combine(_tempDir, "b.json");

        var store1 = new SettingsStore(path1);
        store1.Update(s => s.ServerHost = "alice-host");

        var store2 = new SettingsStore(path2);
        store2.Update(s => s.ServerHost = "bob-host");

        Assert.Equal("alice-host", new SettingsStore(path1).Current.ServerHost);
        Assert.Equal("bob-host", new SettingsStore(path2).Current.ServerHost);
    }
}

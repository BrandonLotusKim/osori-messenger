using System.Text;

namespace OsoriClient.Core.Tests;

public sealed class LastSyncStoreTests : IDisposable
{
    private readonly string _path = Path.Combine(Path.GetTempPath(), $"osori-test-{Guid.NewGuid():N}.json");

    [Fact]
    public void Get_UnknownUser_ReturnsZero()
    {
        var store = new LastSyncStore(_path);

        var value = store.Get("unknown-user");

        Assert.Equal(0L, value);
    }

    [Fact]
    public void SetThenGet_Persists_AcrossInstances()
    {
        var store = new LastSyncStore(_path);
        store.Set("alice", 123456789L);

        var reloaded = new LastSyncStore(_path);

        Assert.Equal(123456789L, reloaded.Get("alice"));
    }

    [Fact]
    public void Clear_RemovesEntry()
    {
        var store = new LastSyncStore(_path);
        store.Set("alice", 100L);

        store.Clear("alice");

        var reloaded = new LastSyncStore(_path);
        Assert.Equal(0L, reloaded.Get("alice"));
    }

    [Fact]
    public void CorruptFile_GracefulRecovery()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        File.WriteAllText(_path, "{ this is not valid json }", Encoding.UTF8);

        var store = new LastSyncStore(_path);

        Assert.Equal(0L, store.Get("alice"));
    }

    public void Dispose()
    {
        if (File.Exists(_path))
        {
            File.Delete(_path);
        }
    }
}

using System.Collections.Generic;
using System.Linq;

namespace OsoriClient.Core;

public sealed class TalkStore
{
    private readonly Dictionary<string, TalkState> _states = new(StringComparer.Ordinal);
    private readonly object _lock = new();

    public TalkState GetOrCreate(string counterpartId)
    {
        lock (_lock)
        {
            return GetOrCreateCore(counterpartId);
        }
    }

    public TalkState RecordIncoming(string counterpartId, string text, long serverTs, bool isSelected)
    {
        lock (_lock)
        {
            var state = GetOrCreateCore(counterpartId);
            state.LastPreview = BuildPreview(text);
            state.LastTs = serverTs;
            if (!isSelected)
            {
                state.UnreadCount++;
            }

            return state;
        }
    }

    public TalkState RecordOutgoing(string counterpartId, string text, long serverTs)
    {
        lock (_lock)
        {
            var state = GetOrCreateCore(counterpartId);
            state.LastPreview = BuildPreview(text);
            state.LastTs = serverTs;
            return state;
        }
    }

    public void MarkRead(string counterpartId)
    {
        lock (_lock)
        {
            if (_states.TryGetValue(counterpartId, out var state))
            {
                state.UnreadCount = 0;
            }
        }
    }

    public IReadOnlyCollection<string> Counterparts
    {
        get
        {
            lock (_lock)
            {
                return _states.Keys.ToArray();
            }
        }
    }

    private TalkState GetOrCreateCore(string counterpartId)
    {
        if (!_states.TryGetValue(counterpartId, out var state))
        {
            state = new TalkState();
            _states[counterpartId] = state;
        }

        return state;
    }

    private static string BuildPreview(string text)
    {
        return text.Length > 40 ? text.Substring(0, 40) + "…" : text;
    }
}

public sealed class TalkState
{
    public int UnreadCount { get; set; }

    public string LastPreview { get; set; } = string.Empty;

    public long LastTs { get; set; }
}

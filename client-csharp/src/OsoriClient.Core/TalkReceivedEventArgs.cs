using Osori.Proto.V1;

namespace OsoriClient.Core;

public sealed class TalkReceivedEventArgs : EventArgs
{
    public TalkReceivedEventArgs(TalkReceived message) => Message = message;

    public TalkReceived Message { get; }
}

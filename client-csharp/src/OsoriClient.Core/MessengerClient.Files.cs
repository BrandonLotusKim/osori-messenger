using System.IO;
using Google.Protobuf;
using Osori.Proto.V1;

namespace OsoriClient.Core;

/// <summary>파일 업로드(청크 분할 전송)/다운로드(진행률 콜백 포함).</summary>
public sealed partial class MessengerClient
{
    /// <summary> 진행률 콜백 인자. bytesReceived/totalBytes (총 0이면 알 수 없음).</summary>
    public sealed record DownloadProgress(string UploadId, long BytesReceived, long TotalBytes)
    {
        public double Fraction => TotalBytes > 0 ? (double)BytesReceived / TotalBytes : 0.0;
    }

    public async Task<DownloadedFile> DownloadFileAsync(
        string uploadId,
        IProgress<DownloadProgress>? progress,
        CancellationToken ct = default)
    {
        return await DownloadFileInternalAsync(uploadId, progress, ct);
    }

    public async Task<DownloadedFile> DownloadFileAsync(string uploadId, CancellationToken ct = default)
    {
        return await DownloadFileInternalAsync(uploadId, progress: null, ct);
    }

    private async Task<DownloadedFile> DownloadFileInternalAsync(
        string uploadId,
        IProgress<DownloadProgress>? progress,
        CancellationToken ct)
    {
        var clientReqId = Guid.NewGuid().ToString("N");
        var collector = new DownloadCollector();
        if (!_activeDownloads.TryAdd(uploadId, collector))
        {
            throw new InvalidOperationException($"Download already in progress for upload_id={uploadId}");
        }

        try
        {
            using var registration = ct.Register(() => collector.Tcs.TrySetCanceled(ct));

            var requestEnvelope = new Envelope
            {
                FileDownloadReq = new FileDownloadRequest
                {
                    UploadId = uploadId,
                    ClientReqId = clientReqId
                }
            };
            var startResponse = await SendRequestAsync(requestEnvelope, Envelope.PayloadOneofCase.FileDownloadStart, ct);
            var start = startResponse.FileDownloadStart;
            if (start.Result != FileDownloadStart.Types.Result.Ok)
            {
                throw new InvalidOperationException($"File download rejected: {start.Result}");
            }

            collector.ExpectedSize = start.TotalSize;
            collector.Progress = progress;
            collector.UploadIdForProgress = start.UploadId;
            progress?.Report(new DownloadProgress(start.UploadId, 0, start.TotalSize));
            var bytes = await collector.Tcs.Task;
            progress?.Report(new DownloadProgress(start.UploadId, start.TotalSize, start.TotalSize));
            return new DownloadedFile(
                UploadId: start.UploadId,
                Filename: start.Filename,
                MimeType: start.MimeType,
                TotalSize: start.TotalSize,
                Bytes: bytes);
        }
        finally
        {
            _activeDownloads.TryRemove(uploadId, out _);
        }
    }

    public async Task<FileUploadCompleteAck> UploadFileAsync(
        string filePath,
        string toUserId,
        int chunkSize = 64 * 1024,
        string mimeType = "",
        CancellationToken ct = default)
    {
        if (chunkSize <= 0 || chunkSize > 1 * 1024 * 1024)
        {
            throw new ArgumentOutOfRangeException(nameof(chunkSize), "chunkSize must be 1..1048576");
        }

        var bytes = await File.ReadAllBytesAsync(filePath, ct);
        var filename = Path.GetFileName(filePath);
        var clientReqId = Guid.NewGuid().ToString("N");

        var startEnvelope = new Envelope
        {
            FileUploadStart = new FileUploadStart
            {
                ClientReqId = clientReqId,
                ToUserId = toUserId,
                Filename = filename,
                TotalSize = bytes.Length,
                MimeType = mimeType
            }
        };
        var startResponse = await SendRequestAsync(startEnvelope, Envelope.PayloadOneofCase.FileUploadStartAck, ct);
        var startAck = startResponse.FileUploadStartAck;
        if (startAck.Result != FileUploadStartAck.Types.Result.Ok)
        {
            throw new InvalidOperationException($"File upload start rejected: {startAck.Result}");
        }
        var uploadId = startAck.UploadId;

        var seq = 0;
        for (var offset = 0; offset < bytes.Length; offset += chunkSize)
        {
            ct.ThrowIfCancellationRequested();
            var size = Math.Min(chunkSize, bytes.Length - offset);
            var chunkEnvelope = new Envelope
            {
                Sequence = (ulong)Interlocked.Increment(ref _sequence),
                FileUploadChunk = new FileUploadChunk
                {
                    UploadId = uploadId,
                    Seq = seq,
                    Data = ByteString.CopyFrom(bytes, offset, size)
                }
            };
            await SendAsync(chunkEnvelope, ct);
            seq++;
        }

        var completeEnvelope = new Envelope
        {
            FileUploadComplete = new FileUploadComplete
            {
                UploadId = uploadId,
                ClientReqId = clientReqId
            }
        };
        var completeResponse = await SendRequestAsync(completeEnvelope, Envelope.PayloadOneofCase.FileUploadCompleteAck, ct);
        var completeAck = completeResponse.FileUploadCompleteAck;
        if (completeAck.Result != FileUploadCompleteAck.Types.Result.Ok)
        {
            throw new InvalidOperationException($"File upload complete rejected: {completeAck.Result}");
        }
        return completeAck;
    }

    private void HandleDownloadChunk(FileDownloadChunk chunk)
    {
        if (!_activeDownloads.TryGetValue(chunk.UploadId, out var collector))
        {
            return;
        }
        collector.Append(chunk.Data.ToByteArray(), chunk.IsLast);
    }

    private sealed class DownloadCollector
    {
        private readonly MemoryStream _buffer = new();
        public long ExpectedSize { get; set; }
        public IProgress<DownloadProgress>? Progress { get; set; }
        public string UploadIdForProgress { get; set; } = "";
        public TaskCompletionSource<byte[]> Tcs { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public void Append(byte[] data, bool isLast)
        {
            _buffer.Write(data, 0, data.Length);
            if (!isLast && Progress is not null && !string.IsNullOrEmpty(UploadIdForProgress))
            {
                Progress.Report(new DownloadProgress(UploadIdForProgress, _buffer.Length, ExpectedSize));
            }
            if (isLast)
            {
                Tcs.TrySetResult(_buffer.ToArray());
            }
        }
    }

    public sealed record DownloadedFile(string UploadId, string Filename, string MimeType, long TotalSize, byte[] Bytes);
}

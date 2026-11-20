using System.Globalization;
using System.Windows.Data;
using OsoriClient.Core;

namespace OsoriClient.Wpf.Converters;

public sealed class TalkMessageStatusToLabelConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var status = value is TalkMessageStatus typedStatus ? typedStatus : TalkMessageStatus.Failed;
        return status switch
        {
            TalkMessageStatus.Sending => "송신 중",
            TalkMessageStatus.Delivered => "전달",
            TalkMessageStatus.Queued => "대기",
            TalkMessageStatus.RateLimited => "제한",
            TalkMessageStatus.InvalidTarget => "대상없음",
            TalkMessageStatus.Failed => "실패",
            TalkMessageStatus.ServerError => "서버오류",
            TalkMessageStatus.Received => string.Empty,
            _ => string.Empty
        };
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
    {
        throw new NotSupportedException();
    }
}

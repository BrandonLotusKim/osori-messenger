using System.Globalization;
using System.Windows.Data;
using System.Windows.Media;
using OsoriClient.Core;

namespace OsoriClient.Wpf.Converters;

public sealed class TalkMessageStatusToBrushConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var status = value is TalkMessageStatus typedStatus ? typedStatus : TalkMessageStatus.Failed;
        var color = status switch
        {
            TalkMessageStatus.Sending => "#9E9E9E",
            TalkMessageStatus.Delivered => "#4CAF50",
            TalkMessageStatus.Queued => "#FFA726",
            TalkMessageStatus.RateLimited => "#FB8C00",
            TalkMessageStatus.InvalidTarget => "#E53935",
            TalkMessageStatus.Failed => "#E53935",
            TalkMessageStatus.ServerError => "#B71C1C",
            TalkMessageStatus.Received => "Transparent",
            _ => "Transparent"
        };

        return new BrushConverter().ConvertFromString(color) ?? Brushes.Transparent;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
    {
        throw new NotSupportedException();
    }
}

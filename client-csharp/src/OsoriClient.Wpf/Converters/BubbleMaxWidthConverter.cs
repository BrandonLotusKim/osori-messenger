using System.Globalization;
using System.Windows.Data;

namespace OsoriClient.Wpf.Converters;

/// <summary>
/// 말풍선 MaxWidth 를 대화 목록의 실제 폭에 맞춰 계산 — 고정값(예: 380)이면
/// 창을 좁힐 때 아바타/상태 배지와 겹쳐 잘려 보이므로, 컨테이너 폭에서
/// 아바타·상태·여백 몫(ConverterParameter, px)을 뺀 값을 쓴다.
/// </summary>
public sealed class BubbleMaxWidthConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is not double width)
        {
            return 260d;
        }

        var reserve = parameter is not null && double.TryParse(parameter.ToString(), out var p) ? p : 100d;
        return Math.Max(140d, width - reserve);
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
    {
        throw new NotSupportedException();
    }
}

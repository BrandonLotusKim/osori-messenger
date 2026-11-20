using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace OsoriClient.Wpf.Helpers;

/// <summary>
/// WindowChrome 커스텀 창의 모서리를 DWM(윈도우 11 DWMWA_WINDOW_CORNER_PREFERENCE)에
/// 직접 맡겨 라운딩한다. WindowChrome 만으로는 OS 자동 라운딩 경계가 앨리어싱되어
/// 픽셀이 깨진 것처럼 보이므로, DWM 에 명시적으로 위임해 카톡 등 네이티브 앱과
/// 동일한 안티앨리어싱된 모서리를 얻는다. 윈도우 10 이하에서는 무시된다(HRESULT 미확인).
/// </summary>
internal static class WindowCornerHelper
{
    private const int DwmwaWindowCornerPreference = 33;
    private const int DwmwcpRound = 2;

    [DllImport("dwmapi.dll", PreserveSig = true)]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int valueSize);

    public static void ApplyRoundCorners(Window window)
    {
        if (PresentationSource.FromVisual(window) is HwndSource)
        {
            Apply(window);
        }
        else
        {
            window.SourceInitialized += OnSourceInitialized;
        }
    }

    private static void OnSourceInitialized(object? sender, EventArgs e)
    {
        var window = (Window)sender!;
        window.SourceInitialized -= OnSourceInitialized;
        Apply(window);
    }

    private static void Apply(Window window)
    {
        var hwnd = new WindowInteropHelper(window).Handle;
        if (hwnd == IntPtr.Zero)
        {
            return;
        }

        int preference = DwmwcpRound;
        DwmSetWindowAttribute(hwnd, DwmwaWindowCornerPreference, ref preference, sizeof(int));
    }
}

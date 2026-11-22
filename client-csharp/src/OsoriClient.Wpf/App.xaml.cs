using System;
using System.Diagnostics;
using System.Linq;
using System.Threading;
using System.Windows;
using OsoriClient.Core;
using WinForms = System.Windows.Forms;

namespace OsoriClient.Wpf;

public partial class App : Application
{
    /// <summary>다중 인스턴스 차단용 Mutex. 사용자 단위로 하나의 클라이언트만 실행.</summary>
    private const string SingleInstanceMutexName = @"Global\OsoriMessenger.WpfClient.SingleInstance";
    private Mutex? _singleInstanceMutex;

    /// <summary>시스템 트레이 아이콘 (WinForms NotifyIcon).</summary>
    private WinForms.NotifyIcon? _trayIcon;

    /// <summary>다른 컴포넌트가 공유하는 SettingsStore. App 수명 동안 단일.</summary>
    public static SettingsStore Settings { get; private set; } = new();

    /// <summary>"실제 종료" 의도 플래그. 트레이 메뉴 "종료" 또는 명시적 Shutdown 시 set.</summary>
    public static bool ForceShutdownRequested { get; private set; }

    /// <summary>multi 인자로 기동됐는지 — 같은 PC 다중 인스턴스 허용 모드.</summary>
    public static bool MultiInstanceMode { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
        // "multi" 인자: 같은 PC 에서 클라이언트를 여러 개 동시에 띄운다 (개발/테스트용).
        // - 단일 인스턴스 Mutex 를 건너뛴다
        // - 설정/동기화 파일을 프로세스별 프로필로 격리한다 (OSORI_PROFILE)
        // - 트레이 아이콘은 만들지 않는다 (인스턴스마다 같은 아이콘이 뜨면 혼란)
        MultiInstanceMode = e.Args.Any(a =>
            a.Equals("multi", StringComparison.OrdinalIgnoreCase)
            || a.Equals("--multi", StringComparison.OrdinalIgnoreCase)
            || a.Equals("/multi", StringComparison.OrdinalIgnoreCase));

        if (MultiInstanceMode)
        {
            Environment.SetEnvironmentVariable(
                ClientPaths.ProfileEnvVar, "multi-" + Environment.ProcessId);
            Settings = new SettingsStore();
        }
        else
        {
            _singleInstanceMutex = new Mutex(
                initiallyOwned: true, SingleInstanceMutexName, out bool createdNew);

            if (!createdNew)
            {
                ActivateExistingInstance();
                Shutdown();
                return;
            }
        }

        base.OnStartup(e);

        // 글로벌 에러 boundary — 가장 먼저 설치해서 InitializeTrayIcon 도중 예외도 잡음
        InstallGlobalErrorHandlers();

        // 트레이 아이콘은 Mutex 통과 + 정상 기동 시에만 생성 (multi 모드에선 생략)
        if (!MultiInstanceMode)
        {
            InitializeTrayIcon();
        }

        // 디자인 캡처 모드 — 모든 Window 를 사용자 화면 밖(-5000,-5000) 으로 즉시 이동
        if (Environment.GetEnvironmentVariable("OSORI_OFFSCREEN") == "1")
        {
            EventManager.RegisterClassHandler(typeof(Window),
                Window.LoadedEvent,
                new RoutedEventHandler(OnAnyWindowLoadedOffscreen));
        }

        // 디자인 캡처 덤프 — 모든 Window 가 Loaded 후 N ms 뒤 RenderTargetBitmap 자동 저장
        if (Environment.GetEnvironmentVariable("OSORI_CAPTURE_DUMP") == "1"
            || Environment.GetEnvironmentVariable("OSORI_VERIFY") == "1")
        {
            EventManager.RegisterClassHandler(typeof(Window),
                Window.LoadedEvent,
                new RoutedEventHandler(OnAnyWindowLoadedCaptureDump));
        }
    }

    private static void OnAnyWindowLoadedOffscreen(object sender, RoutedEventArgs e)
    {
        if (sender is Window w)
        {
            w.WindowState = WindowState.Normal;
            w.Left = -5000;
            w.Top = -5000;
            w.Topmost = false;
            w.ShowInTaskbar = false;
        }
    }

    private static void OnAnyWindowLoadedCaptureDump(object sender, RoutedEventArgs e)
    {
        if (sender is not Window w) return;

        // OSORI_TAB 환경변수 — MainWindow 의 시작 탭 강제 (캡처용)
        if (w.GetType().Name == "MainWindow")
        {
            var tab = Environment.GetEnvironmentVariable("OSORI_TAB");
            if (!string.IsNullOrEmpty(tab))
            {
                string? radioName = tab switch
                {
                    "talk" => "TabTalk",
                    "org" => "TabOrg",
                    "message" => "TabMessage",
                    "settings" => "TabSettings",
                    _ => null,
                };
                if (radioName != null && w.FindName(radioName) is System.Windows.Controls.Primitives.ToggleButton rb)
                {
                    rb.IsChecked = true;
                }
            }
        }

        // 300ms / 1200ms 두 번 — 짧게 한 번(렌더 직후), 길게 한 번(탭 전환/UI 갱신 후)
        ScheduleCaptureSnapshot(w, 300);
        ScheduleCaptureSnapshot(w, 1200);

        // OSORI_VERIFY 모드 — 실제 DWM CopyFromScreen + 픽셀 색 stdout
        if (Environment.GetEnvironmentVariable("OSORI_VERIFY") == "1")
        {
            var t = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1500) };
            t.Tick += (_, _) =>
            {
                t.Stop();
                DoDwmCapture(w, w.GetType().Name);
            };
            t.Start();
        }
    }

    [System.Runtime.InteropServices.DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int pvAttribute, int cbAttribute);

    private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private const int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19;

    /// <summary>모든 Window 의 시스템 타이틀바를 다크모드로 (Win10 1809+ DWM API).
    /// WindowStyle=None 인 Window 는 영향 없음 (그쪽은 자체 커스텀 타이틀바).</summary>
    private static void OnAnyWindowSourceInitializedDarkTitle(object? sender, EventArgs e)
    {
        if (sender is not Window w) return;
        try
        {
            var hwnd = new System.Windows.Interop.WindowInteropHelper(w).Handle;
            if (hwnd == IntPtr.Zero) return;
            int useDark = 1;
            // 최신 빌드 → fallback
            if (DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, ref useDark, sizeof(int)) != 0)
            {
                DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, ref useDark, sizeof(int));
            }
        }
        catch { /* 다크 타이틀바 실패는 시각만 영향 */ }
    }

    private static void VerifyLog(string msg)
    {
        try
        {
            System.IO.Directory.CreateDirectory(System.IO.Path.Combine(System.IO.Path.GetTempPath(), "osori_caps"));
            System.IO.File.AppendAllText(System.IO.Path.Combine(System.IO.Path.GetTempPath(), "osori_caps", "verify.log"),
                $"[{DateTime.Now:HH:mm:ss.fff}] {msg}{Environment.NewLine}");
        }
        catch { /* 무시 */ }
    }

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr hWnd, out CapRect lpRect);

    [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential)]
    private struct CapRect { public int Left, Top, Right, Bottom; }

    /// <summary>OSORI_VERIFY=1 — 실제 DWM CopyFromScreen 캡처 + 픽셀 색 stdout 출력. PowerShell 우회용.</summary>
    public static void DoDwmCapture(Window w, string label)
    {
        try
        {
            // Topmost true → 사용자 화면 위로 → 캡처 → false. SetForegroundWindow 보안 우회.
            w.Topmost = true;
            w.Activate();
            // 활성 후 짧은 timer 로 다시 캡처
            var sub = new System.Windows.Threading.DispatcherTimer
            {
                Interval = TimeSpan.FromMilliseconds(400),
            };
            sub.Tick += (_, _) =>
            {
                sub.Stop();
                try
                {
                    var hwnd = new System.Windows.Interop.WindowInteropHelper(w).Handle;
                    if (hwnd == IntPtr.Zero) { VerifyLog("[VERIFY] no hwnd"); return; }
                    GetWindowRect(hwnd, out var rect);
                    int width = rect.Right - rect.Left, height = rect.Bottom - rect.Top;
                    if (width < 1 || height < 1) return;
                    using var bmp = new System.Drawing.Bitmap(width, height);
                    using (var g = System.Drawing.Graphics.FromImage(bmp))
                    {
                        g.CopyFromScreen(rect.Left, rect.Top, 0, 0, new System.Drawing.Size(width, height));
                    }
                    var dir = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "osori_caps");
                    System.IO.Directory.CreateDirectory(dir);
                    var path = System.IO.Path.Combine(dir, $"verify_{label}_{DateTime.Now:HHmmss}.png");
                    bmp.Save(path);
                    VerifyLog($"[VERIFY] {label} saved: {path} {width}x{height}");

                    // 핵심 픽셀 색상 stdout (사용자 보고용)
                    if (w is LoginWindow)
                    {
                        // 로그인 버튼 영역 — Form Margin 32,0 / 화면 width 380 / 버튼 폭 ~316 + 좌측 ProgressRing
                        // 버튼 중앙 약 (215, height-205)
                        var bg = bmp.GetPixel(215, height - 205);
                        var glyph = bmp.GetPixel(180, height - 200);
                        VerifyLog($"[VERIFY] LoginButton bg(215,{height - 205})=R{bg.R}G{bg.G}B{bg.B} glyph(180,{height - 200})=R{glyph.R}G{glyph.G}B{glyph.B}");
                    }
                    if (w is MainWindow)
                    {
                        // 본문 중앙 색 (사이드바 제외 — 약 x=200, y=300)
                        var body = bmp.GetPixel(200, 300);
                        VerifyLog($"[VERIFY] MainWindow body(200,300)=R{body.R}G{body.G}B{body.B}");
                    }
                }
                catch (Exception ex2)
                {
                    VerifyLog($"[VERIFY] inner FAIL: {ex2.Message}");
                }
                finally
                {
                    try { w.Topmost = false; } catch { }
                }
            };
            sub.Start();
        }
        catch (Exception ex)
        {
            VerifyLog($"[VERIFY] outer FAIL: {ex.Message}");
        }
    }

    private static void ScheduleCaptureSnapshot(Window w, int delayMs)
    {
        var timer = new System.Windows.Threading.DispatcherTimer
        {
            Interval = TimeSpan.FromMilliseconds(delayMs),
        };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            try
            {
                int w2 = (int)w.ActualWidth, h2 = (int)w.ActualHeight;
                if (w2 < 1 || h2 < 1) return;
                var rtb = new System.Windows.Media.Imaging.RenderTargetBitmap(
                    w2, h2, 96, 96, System.Windows.Media.PixelFormats.Pbgra32);
                rtb.Render(w);
                var enc = new System.Windows.Media.Imaging.PngBitmapEncoder();
                enc.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(rtb));
                var dir = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "osori_caps");
                System.IO.Directory.CreateDirectory(dir);
                var ts = DateTime.Now.ToString("HHmmss_fff");
                var name = $"{w.GetType().Name}_{delayMs}_{ts}.png";
                using var fs = System.IO.File.Create(System.IO.Path.Combine(dir, name));
                enc.Save(fs);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine("[CaptureDump] " + ex.Message);
            }
        };
        timer.Start();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try
        {
            _trayIcon?.Dispose();
            _trayIcon = null;
        }
        catch
        {
            // 정리 실패는 조용히 무시.
        }

        try
        {
            _singleInstanceMutex?.ReleaseMutex();
        }
        catch (ApplicationException)
        {
            // 다른 스레드 소유였거나 이미 해제. 무시.
        }
        _singleInstanceMutex?.Dispose();
        _singleInstanceMutex = null;

        base.OnExit(e);
    }

    // ════════ 글로벌 에러 boundary ════════
    private void InstallGlobalErrorHandlers()
    {
        // UI 스레드 (Dispatcher) 미처리 예외 — 사용자에게 다이얼로그
        DispatcherUnhandledException += (_, args) =>
        {
            LogCrash("UI", args.Exception);
            ShowCrashDialog(args.Exception, recoverable: true);
            args.Handled = true; // 앱 계속 살림
        };

        // 백그라운드 도메인 미처리 예외 — 다이얼로그 후 종료
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
        {
            var ex = args.ExceptionObject as Exception;
            LogCrash("Domain", ex);
            if (ex is not null) ShowCrashDialog(ex, recoverable: false);
        };

        // Task scheduler 비관찰 예외 — 로그만, 다이얼로그는 띄우지 않음 (시끄러움)
        System.Threading.Tasks.TaskScheduler.UnobservedTaskException += (_, args) =>
        {
            LogCrash("Task", args.Exception);
            args.SetObserved();
        };
    }

    private static void LogCrash(string source, Exception? ex)
    {
        if (ex is null) return;
        try
        {
            var dir = System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "OsoriMessenger", "logs");
            System.IO.Directory.CreateDirectory(dir);
            var path = System.IO.Path.Combine(dir, "crash.log");
            var entry = $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] [{source}] {ex.GetType().Name}: {ex.Message}\n{ex}\n\n";
            System.IO.File.AppendAllText(path, entry);
        }
        catch
        {
            // 로그 쓰기 실패는 무시 — 이미 죽어가는 중일 수도
        }
    }

    private static void ShowCrashDialog(Exception ex, bool recoverable)
    {
        try
        {
            var title = recoverable ? "오류 발생" : "치명적 오류";
            var prefix = recoverable
                ? "예기치 못한 오류가 발생했지만 앱은 계속 실행됩니다."
                : "치명적인 오류가 발생해 앱을 종료해야 합니다.";
            var logHint = $"\n\n로그 위치:\n%LOCALAPPDATA%\\OsoriMessenger\\logs\\crash.log";
            var msg = $"{prefix}\n\n{ex.GetType().Name}: {ex.Message}{logHint}";
            MessageBox.Show(msg, title,
                MessageBoxButton.OK,
                recoverable ? MessageBoxImage.Warning : MessageBoxImage.Error);
        }
        catch
        {
            // 다이얼로그 자체도 실패하면 더 할 수 있는 게 없음
        }
    }

    private void InitializeTrayIcon()
    {
        try
        {
            var menu = new WinForms.ContextMenuStrip();
            var openItem = menu.Items.Add("열기");
            openItem.Click += (_, _) => ShowMainWindow();
            // 빠른 진입 — 새 쪽지 (메인 창 활성 + Ctrl+N 트리거)
            var newMessageItem = menu.Items.Add("새 쪽지 쓰기");
            newMessageItem.Click += (_, _) => TriggerNewMessageFromTray();
            menu.Items.Add(new WinForms.ToolStripSeparator());
            var exitItem = menu.Items.Add("종료");
            exitItem.Click += (_, _) => RequestShutdown();

            _trayIcon = new WinForms.NotifyIcon
            {
                Icon = System.Drawing.SystemIcons.Application,
                Text = "오소리 메신저",
                Visible = true,
                ContextMenuStrip = menu,
            };
            _trayIcon.DoubleClick += (_, _) => ShowMainWindow();
            // BalloonTip (Win10+ 자동 토스트 변환) 클릭 시 메인 창 활성화
            _trayIcon.BalloonTipClicked += (_, _) => ShowMainWindow();
        }
        catch
        {
            // 트레이 초기화 실패해도 앱은 계속 동작. 트레이 없는 일반 종료 흐름으로.
            _trayIcon = null;
        }
    }

    /// <summary>트레이 ContextMenu "새 쪽지 쓰기" — 메인 창 활성 + MessageCompose 다이얼로그 즉시 오픈.</summary>
    private static void TriggerNewMessageFromTray()
    {
        ShowMainWindow();
        if (Current?.Windows is null) return;
        foreach (Window w in Current.Windows)
        {
            if (w is MainWindow mw)
            {
                mw.OpenMessageComposeFromExternal();
                return;
            }
        }
    }

    /// <summary>트레이에서 메인 창 호출 — 숨김 상태면 보이고 활성화.</summary>
    public static void ShowMainWindow()
    {
        if (Current?.Windows is null) return;

        // MainWindow 우선, 없으면 LoginWindow
        Window? target = null;
        foreach (Window w in Current.Windows)
        {
            if (w is MainWindow)
            {
                target = w;
                break;
            }
            if (target is null && w is LoginWindow)
            {
                target = w;
            }
        }
        if (target is null) return;

        if (!target.IsVisible)
        {
            target.Show();
        }
        if (target.WindowState == WindowState.Minimized)
        {
            target.WindowState = WindowState.Normal;
        }
        target.Activate();
        target.Focus();
    }

    /// <summary>트레이 "종료" 또는 강제 종료 진입점. CloseToTray 옵션을 무시하고 실제 Shutdown.</summary>
    public static void RequestShutdown()
    {
        ForceShutdownRequested = true;
        Current?.Shutdown();
    }

    /// <summary>인앱 토스트 레벨.</summary>
    public enum ToastLevel { Info, Success, Warn, Error }

    /// <summary>
    ///: 활성 메인 창 안에 인앱 토스트를 띄움. 트레이 풍선 알림(<see cref="ShowToast"/>)과 별도 채널.
    /// 메인 창이 활성/visible 일 때 사용. 비활성/숨김이면 호출 무시(트레이가 대신 알림).
    /// </summary>
    public static void ShowInAppToast(string title, string body, ToastLevel level = ToastLevel.Info, int durationMs = 3000)
    {
        if (Current?.Windows is null) return;
        foreach (Window w in Current.Windows)
        {
            if (w is MainWindow mw && w.IsLoaded)
            {
                mw.ShowInAppToast(title, body, level, durationMs);
                return;
            }
        }
    }

    /// <summary>
    ///: 시스템 트레이 풍선 알림(Win 10 이상 자동 토스트 변환).
    /// 호출자가 IsActive 등 활성 창 여부를 미리 판단해 호출.
    /// </summary>
    public static void ShowToast(string title, string body, int durationMs = 3000)
    {
        if (Current is not App app || app._trayIcon is null)
        {
            return;
        }
        try
        {
            app._trayIcon.ShowBalloonTip(durationMs, title, body, WinForms.ToolTipIcon.Info);
        }
        catch
        {
            // 트레이 알림 실패는 무시.
        }
    }

    /// <summary>미읽음 카운트를 트레이 ToolTip 에 반영 + 아이콘 빨간점 + 카운트 오버레이.</summary>
    public static void UpdateTrayUnreadCount(int unread)
    {
        if (Current is not App app || app._trayIcon is null)
        {
            return;
        }
        try
        {
            app._trayIcon.Text = unread > 0 ? $"오소리 메신저 ({unread} 미읽음)" : "오소리 메신저";
            // 보강: 카운트별로 다른 아이콘 (1-9 / 9+). 무카운트일 때는 기본 아이콘.
            app._trayIcon.Icon = unread switch
            {
                <= 0 => GetTrayIconDefault(),
                _ => GetTrayIconWithCount(unread),
            };
        }
        catch
        {
            // ToolTip/아이콘 업데이트 실패는 무시.
        }
    }

    private static System.Drawing.Icon? _lastCountedIcon;
    private static int _lastCountedValue = -1;

    private static System.Drawing.Icon GetTrayIconWithCount(int count)
    {
        // 같은 카운트면 캐시 재사용 (incrementer 마다 새 hicon 만들지 않게)
        if (_lastCountedIcon != null && _lastCountedValue == count) return _lastCountedIcon;
        try
        {
            var baseIcon = System.Drawing.SystemIcons.Application;
            using var bmp = new System.Drawing.Bitmap(baseIcon.Width, baseIcon.Height);
            using (var g = System.Drawing.Graphics.FromImage(bmp))
            {
                g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAlias;
                g.DrawIcon(baseIcon, 0, 0);
                // 우하단 빨간 점
                int d = (int)Math.Round(baseIcon.Width * 0.55);
                int x = baseIcon.Width - d - 1;
                int y = baseIcon.Height - d - 1;
                using var dotBrush = new System.Drawing.SolidBrush(System.Drawing.Color.FromArgb(231, 76, 60));
                using var outlinePen = new System.Drawing.Pen(System.Drawing.Color.White, 2f);
                g.FillEllipse(dotBrush, x, y, d, d);
                g.DrawEllipse(outlinePen, x, y, d, d);
                // 카운트 텍스트
                var label = count > 9 ? "9+" : count.ToString();
                using var font = new System.Drawing.Font(
                    System.Drawing.FontFamily.GenericSansSerif,
                    d * 0.55f,
                    System.Drawing.FontStyle.Bold,
                    System.Drawing.GraphicsUnit.Pixel);
                using var textBrush = new System.Drawing.SolidBrush(System.Drawing.Color.White);
                var size = g.MeasureString(label, font);
                g.DrawString(label, font, textBrush,
                    x + (d - size.Width) / 2,
                    y + (d - size.Height) / 2);
            }
            var hIcon = bmp.GetHicon();
            var icon = (System.Drawing.Icon)System.Drawing.Icon.FromHandle(hIcon).Clone();
            NativeMethods.DestroyIcon(hIcon);
            _lastCountedIcon = icon;
            _lastCountedValue = count;
            return icon;
        }
        catch
        {
            return GetTrayIconRedDot();
        }
    }

    private static System.Drawing.Icon? _trayIconDefault;
    private static System.Drawing.Icon? _trayIconRedDot;

    private static System.Drawing.Icon GetTrayIconDefault()
    {
        return _trayIconDefault ??= System.Drawing.SystemIcons.Application;
    }

    private static System.Drawing.Icon GetTrayIconRedDot()
    {
        if (_trayIconRedDot is not null) return _trayIconRedDot;
        try
        {
            var baseIcon = System.Drawing.SystemIcons.Application;
            using var bmp = new System.Drawing.Bitmap(baseIcon.Width, baseIcon.Height);
            using (var g = System.Drawing.Graphics.FromImage(bmp))
            {
                g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                g.DrawIcon(baseIcon, 0, 0);
                // 우하단에 빨간 점 (지름 = 아이콘의 약 45%)
                int d = (int)Math.Round(baseIcon.Width * 0.45);
                int x = baseIcon.Width - d - 1;
                int y = baseIcon.Height - d - 1;
                using var dotBrush = new System.Drawing.SolidBrush(System.Drawing.Color.FromArgb(231, 76, 60));
                using var outlinePen = new System.Drawing.Pen(System.Drawing.Color.White, 2f);
                g.FillEllipse(dotBrush, x, y, d, d);
                g.DrawEllipse(outlinePen, x, y, d, d);
            }
            var hIcon = bmp.GetHicon();
            _trayIconRedDot = (System.Drawing.Icon)System.Drawing.Icon.FromHandle(hIcon).Clone();
            NativeMethods.DestroyIcon(hIcon);
            return _trayIconRedDot;
        }
        catch
        {
            return GetTrayIconDefault();
        }
    }

    private static void ActivateExistingInstance()
    {
        try
        {
            var current = Process.GetCurrentProcess();
            var existing = Process.GetProcessesByName(current.ProcessName)
                .Where(p => p.Id != current.Id)
                .OrderBy(p => p.StartTime)
                .FirstOrDefault();
            if (existing is null) return;

            var hWnd = existing.MainWindowHandle;
            if (hWnd == IntPtr.Zero) return;

            NativeMethods.ShowWindow(hWnd, NativeMethods.SW_RESTORE);
            NativeMethods.SetForegroundWindow(hWnd);
        }
        catch
        {
            // 활성화 실패해도 본 인스턴스는 어차피 종료 — 조용히 무시.
        }
    }

    private static class NativeMethods
    {
        public const int SW_RESTORE = 9;

        [System.Runtime.InteropServices.DllImport("user32.dll")]
        public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        [System.Runtime.InteropServices.DllImport("user32.dll")]
        public static extern bool SetForegroundWindow(IntPtr hWnd);

        [System.Runtime.InteropServices.DllImport("user32.dll", SetLastError = true)]
        public static extern bool DestroyIcon(IntPtr hIcon);
    }
}

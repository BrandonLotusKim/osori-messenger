using System;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using Osori.Proto.V1;
using OsoriClient.Core;
using OsoriClient.Wpf.Helpers;
using OsoriClient.Wpf.Security;

namespace OsoriClient.Wpf;

public partial class LoginWindow : Window
{
    private readonly SettingsStore _settings = new();

    // 2026-09-17 추가 — 로그인 자격증명(마지막 아이디/자동로그인/비밀번호 저장)은 OSORI_PROFILE
    // 을 안 타는 별도 저장소로 분리했다(자세한 이유는 LoginCredentialsStore 주석 참고). multi
    // 모드에서 실행할 때마다 프로필이 바뀌어서 이 정보가 전혀 재사용 안 되던 버그의 수정.
    private readonly LoginCredentialsStore _credentials = new();

    /// <summary>자동로그인 복호화된 비밀번호 임시 보관. UI 에 노출하지 않음.</summary>
    private string? _autoLoginPassword;
    private bool _isAutoLoginInProgress;
    private readonly bool _suppressAutoLogin;
    private CancellationTokenSource? _cts;

    /// <summary>
    /// App.xaml 의 StartupUri="LoginWindow.xaml" 이 앱 시작 시 이 창을 XAML 파서를 통해
    /// 생성하는데, XAML 은 C# 의 선택적 매개변수(기본값)를 인식하지 못하고 IL 상의 진짜
    /// 무인자 생성자만 찾는다 — 그래서 LoginWindow(bool suppressAutoLogin = false) 하나만
    /// 있으면 "일치하는 생성자를 찾을 수 없습니다" XamlParseException 으로 앱이 아예 안 뜬다
    /// (2026-09-17 발견). 무인자 오버로드를 명시적으로 남겨 앱 시작 경로는 그대로 두고,
    /// 로그아웃 후 재호출처럼 코드에서 직접 생성할 때만 아래 생성자를 쓴다.
    /// </summary>
    public LoginWindow() : this(suppressAutoLogin: false)
    {
    }

    /// <param name="suppressAutoLogin">
    /// 2026-09-17 버그 수정 — 명시적으로 "로그아웃"을 누른 직후 띄우는 LoginWindow 에는 true 를
    /// 넘긴다. 자동 로그인이 켜져 있으면 이 창이 뜨자마자 저장된 비밀번호로 즉시 재접속해버려서
    /// 로그아웃이 사실상 무의미했던 문제 — 저장된 설정 자체는 안 건드리고 이번 창 인스턴스에서만
    /// 자동 로그인 시도를 건너뛴다(체크박스/비밀번호 칸은 평소처럼 채워져 있어 사용자가 그냥
    /// 로그인 버튼만 누르면 됨).
    /// </param>
    public LoginWindow(bool suppressAutoLogin) : this(suppressAutoLogin, notice: null)
    {
    }

    /// <param name="notice">창이 뜰 때 상태 줄에 보여 줄 안내(예: 다른 곳 로그인으로 끊김).</param>
    public LoginWindow(bool suppressAutoLogin, string? notice)
    {
        _suppressAutoLogin = suppressAutoLogin;
        InitializeComponent();
        if (!string.IsNullOrWhiteSpace(notice))
        {
            Loaded += (_, _) => ShowStatus(notice!, isError: true);
        }
        WindowCornerHelper.ApplyRoundCorners(this);
        ApplySettingsToUi();
        UserIdCombo.TextChanged += UserIdCombo_OnTextChanged;
        PortTextBox.TextChanged += (_, _) => ClearFieldInvalid(PortTextBox);
        Loaded += LoginWindow_OnLoaded;
        KeyDown += LoginWindow_OnKeyDown;
    }

    private void ApplySettingsToUi()
    {
        var s = _settings.Current;
        HostTextBox.Text = s.ServerHost;
        PortTextBox.Text = s.ServerPort.ToString();
        UseTlsCheckBox.IsChecked = s.UseTls;

        var cred = _credentials.Current;
        if (!string.IsNullOrEmpty(cred.LastUserId))
        {
            UserIdCombo.Text = cred.LastUserId;
        }

        // 자동로그인/비밀번호저장 체크박스 상태 복원 (실제 자동 로그인 시도는 Loaded 에서)
        AutoLoginCheck.IsChecked = cred.AutoLoginEnabled;
        RememberPasswordCheck.IsChecked = cred.RememberPasswordEnabled;

        // 복호화된 비밀번호를 _autoLoginPassword 에 보관. UI 에 값을 보이지 않는다(자동
        // 로그인용으로만 씀). "비밀번호 저장"만 켜져 있고 자동 로그인은 아니면 비밀번호 칸에
        // 직접 채워서 사용자가 로그인 버튼만 누르면 되게 한다.
        if ((cred.AutoLoginEnabled || cred.RememberPasswordEnabled) && !string.IsNullOrEmpty(cred.EncryptedPassword))
        {
            var decrypted = CredentialProtector.Unprotect(cred.EncryptedPassword);
            if (decrypted is null)
            {
                // 복호화 실패: 둘 다 해제 + 사용자에게 안내
                _credentials.Update(cc =>
                {
                    cc.AutoLoginEnabled = false;
                    cc.RememberPasswordEnabled = false;
                    cc.EncryptedPassword = null;
                });
                AutoLoginCheck.IsChecked = false;
                RememberPasswordCheck.IsChecked = false;
                ShowStatus("저장된 로그인 정보를 불러올 수 없어 자동로그인/비밀번호 저장을 해제했습니다.", isError: true);
            }
            else
            {
                _autoLoginPassword = decrypted;
                if (cred.RememberPasswordEnabled)
                {
                    PasswordTextBox.Password = decrypted;
                }
            }
        }
    }

    private async void LoginWindow_OnLoaded(object sender, RoutedEventArgs e)
    {
        if (!_suppressAutoLogin && _autoLoginPassword is not null && AutoLoginCheck.IsChecked == true
            && !string.IsNullOrWhiteSpace(UserIdCombo.Text))
        {
            _isAutoLoginInProgress = true;
            ShowStatus("자동 로그인 중...");
            await TryConnectAsync(usingStoredPassword: true);
            _isAutoLoginInProgress = false;
            return;
        }

        // 기본 포커스
        if (string.IsNullOrEmpty(UserIdCombo.Text))
        {
            UserIdCombo.Focus();
        }
        else
        {
            PasswordTextBox.Focus();
        }

        // 디자인 캡처 모드 — OSORI_AUTO_LOGIN=<userId> 환경변수 시 자동 진입 (DPAPI 우회)
        var autoUser = Environment.GetEnvironmentVariable("OSORI_AUTO_LOGIN");
        if (!string.IsNullOrEmpty(autoUser))
        {
            UserIdCombo.Text = autoUser;
            await TryConnectAsync(usingStoredPassword: false);
        }
    }

    private void UserIdCombo_OnTextChanged(object sender, TextChangedEventArgs e)
    {
        ClearFieldInvalid(UserIdCombo);

        if (_isAutoLoginInProgress) return;

        // 사용자가 ID 를 수정(다른 계정)하면 저장된 자동로그인/비밀번호저장 해제 —
        // 다른 계정의 비밀번호가 그대로 남아 자동 전송되면 안 되므로 비밀번호 칸도 비운다.
        var current = _credentials.Current.LastUserId;
        var typed = UserIdCombo.Text.Trim();
        if (!string.Equals(typed, current, StringComparison.OrdinalIgnoreCase))
        {
            AutoLoginCheck.IsChecked = false;
            RememberPasswordCheck.IsChecked = false;
            PasswordTextBox.Clear();
            _autoLoginPassword = null;
        }
    }

    /// <summary>: 검증 실패 필드에 빨간 테두리 + 두께 2 인라인 에러 표시.</summary>
    private void SetFieldInvalid(Control c)
    {
        c.BorderBrush = (Brush)FindResource("ErrorBrush");
        c.BorderThickness = new Thickness(2);
    }

    /// <summary>입력이 변경되면 빨간 테두리를 제거하여 평시 스타일로 복원.</summary>
    private void ClearFieldInvalid(Control c)
    {
        c.ClearValue(Control.BorderBrushProperty);
        c.ClearValue(Control.BorderThicknessProperty);
    }

    private async void LoginWindow_OnKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && ConnectButton.IsEnabled)
        {
            e.Handled = true;
            if (_cts is not null)
            {
                CancelConnect();
                return;
            }

            await TryConnectAsync();
        }
        else if (e.Key == Key.Escape)
        {
            Close();
        }
    }

    private async void ConnectButton_OnClick(object sender, RoutedEventArgs e)
    {
        if (_cts is not null)
        {
            CancelConnect();
            return;
        }

        await TryConnectAsync();
    }

    private async System.Threading.Tasks.Task TryConnectAsync(bool usingStoredPassword = false)
    {
        var userId = UserIdCombo.Text.Trim();
        if (string.IsNullOrEmpty(userId))
        {
            ShowStatus("사용자 ID를 입력하세요.", isError: true);
            SetFieldInvalid(UserIdCombo);
            UserIdCombo.Focus();
            return;
        }

        if (!int.TryParse(PortTextBox.Text.Trim(), out var port) || port < 1 || port > 65535)
        {
            ShowStatus("포트가 올바르지 않습니다.", isError: true);
            SetFieldInvalid(PortTextBox);
            ServerExpander.IsExpanded = true;
            PortTextBox.Focus();
            return;
        }

        var password = usingStoredPassword
            ? _autoLoginPassword ?? string.Empty
            : PasswordTextBox.Password;

        DisposeCancellation();
        _cts = new CancellationTokenSource();
        SetConnectUiState(isBusy: true);

        ShowStatus(usingStoredPassword ? "자동 로그인 중..." : "서버에 연결 중...");
        var client = new MessengerClient();
        try
        {
            var scheme = UseTlsCheckBox.IsChecked == true ? "wss" : "ws";
            var host = HostTextBox.Text.Trim();
            var useTls = UseTlsCheckBox.IsChecked == true;
            var serverUri = new Uri($"{scheme}://{host}:{port}/ws");
            await client.ConnectAsync(serverUri, useTls, _cts.Token);

            ShowStatus("인증 중...");
            var response = await client.LoginAsync(
                userId,
                password,
                Environment.MachineName,
                _cts.Token);

            if (response.Status != LoginResponse.Types.Status.Ok)
            {
                ShowStatus(ResultMessages.Login(response.Status), isError: true);
                PasswordTextBox.Clear();
                PasswordTextBox.Focus();

                // 저장된 자격 증명 문제면 자동로그인/비밀번호저장 둘 다 해제
                if (usingStoredPassword)
                {
                    _credentials.Update(c =>
                    {
                        c.AutoLoginEnabled = false;
                        c.RememberPasswordEnabled = false;
                        c.EncryptedPassword = null;
                    });
                    AutoLoginCheck.IsChecked = false;
                    RememberPasswordCheck.IsChecked = false;
                    _autoLoginPassword = null;
                }

                await client.DisposeAsync();
                return;
            }

            // 로그인 성공: settings 갱신
            var autoLoginRequested = AutoLoginCheck.IsChecked == true;
            var rememberPasswordRequested = RememberPasswordCheck.IsChecked == true;
            _settings.Update(s =>
            {
                s.ServerHost = host;
                s.ServerPort = port;
                s.UseTls = useTls;
            });
            _credentials.Update(c =>
            {
                c.LastUserId = userId;
                c.AutoLoginEnabled = autoLoginRequested;
                c.RememberPasswordEnabled = rememberPasswordRequested;
                c.EncryptedPassword = (autoLoginRequested || rememberPasswordRequested)
                    ? CredentialProtector.Protect(password)
                    : null;
            });
            _settings.RecordSuccessfulLogin(userId);

            client.StartHeartbeat();
            var mainWindow = new MainWindow(client, userId);
            // App.xaml 의 StartupUri 때문에 Application.Current.MainWindow 는 기본적으로
            // LoginWindow 를 가리킨 채로 남아있는다(닫혀도 재할당되지 않음). 그 결과
            // "Application.Current.MainWindow is MainWindow" 로 실제 메인 창인지 확인하는
            // 코드(예: TalkWindow.InviteMoreToRoomAsync 의 1:1→새 그룹방 분기)가 항상 실패해서
            // 기능이 조용히 옛 동작(현재 방에 인원 추가)으로 폴백하는 버그가 있었다.
            Application.Current.MainWindow = mainWindow;
            mainWindow.Show();
            Close();
        }
        catch (OperationCanceledException)
        {
            ShowStatus("취소되었습니다.");
            await client.DisposeAsync();
        }
        catch (Exception ex)
        {
            ShowStatus($"연결 실패: {ex.Message}", isError: true);
            ServerExpander.IsExpanded = true;
            await client.DisposeAsync();
        }
        finally
        {
            DisposeCancellation();
            SetConnectUiState(isBusy: false);
        }
    }

    private void CancelConnect()
    {
        _cts?.Cancel();
    }

    private void SetConnectUiState(bool isBusy)
    {
        ConnectButton.IsEnabled = true;
        ConnectButton.Content = isBusy ? "취소" : "로그인";
        ConnectProgress.Visibility = isBusy ? Visibility.Visible : Visibility.Collapsed;
    }

    private void DisposeCancellation()
    {
        _cts?.Dispose();
        _cts = null;
    }

    private void ShowStatus(string text, bool isError = false)
    {
        StatusTextBlock.Text = text;
        StatusTextBlock.Foreground = isError
            ? (Brush)FindResource("ErrorBrush")
            : (Brush)FindResource("TextSecondaryBrush");
    }

    /// <summary>커스텀 타이틀바 닫기 버튼 — Window 종료.</summary>
    private void TitleCloseButton_OnClick(object sender, RoutedEventArgs e)
    {
        Close();
    }

    /// <summary>커스텀 타이틀바 최소화 버튼.</summary>
    private void MinimizeButton_Click(object sender, RoutedEventArgs e)
    {
        WindowState = WindowState.Minimized;
    }
}

param(
    [Parameter(Mandatory=$true)][int]$ProcId,
    [Parameter(Mandatory=$true)][string]$UserId,
    [string]$ServerHost = "127.0.0.1",
    [int]$Port = 7071
)

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Id $ProcId -ErrorAction Stop
$hwnd = $proc.MainWindowHandle
if ($hwnd -eq [IntPtr]::Zero) { Write-Error "No MainWindowHandle"; exit 1 }

$root = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
if ($null -eq $root) { Write-Error "AutomationElement not found"; exit 1 }

function FindById($parent, $aid) {
    $cond = New-Object System.Windows.Automation.PropertyCondition(
        [System.Windows.Automation.AutomationElement]::AutomationIdProperty, $aid)
    return $parent.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
}

function SetValue($el, $text) {
    $vp = $el.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
    $vp.SetValue($text)
}

function Invoke($el) {
    $ip = $el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
    $ip.Invoke()
}

# Host/Port 필요 시 덮어쓰기
$hostBox = FindById $root "HostTextBox"
if ($null -ne $hostBox) { SetValue $hostBox $ServerHost }

$portBox = FindById $root "PortTextBox"
if ($null -ne $portBox) { SetValue $portBox $Port.ToString() }

$userBox = FindById $root "UserIdTextBox"
if ($null -eq $userBox) { Write-Error "UserIdTextBox not found"; exit 1 }
SetValue $userBox $UserId

$btn = FindById $root "ConnectButton"
if ($null -eq $btn) { Write-Error "ConnectButton not found"; exit 1 }
Invoke $btn

Write-Host ("login submit PID={0} userId={1}" -f $ProcId, $UserId)

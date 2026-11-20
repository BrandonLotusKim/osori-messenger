param(
    [Parameter(Mandatory=$true)][int]$ProcId,
    [Parameter(Mandatory=$true)][string]$ToUserId,
    [Parameter(Mandatory=$true)][string]$Text
)

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Id $ProcId -ErrorAction Stop
$hwnd = $proc.MainWindowHandle
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

$to = FindById $root "TargetUserTextBox"
if ($null -eq $to) { Write-Error "TargetUserTextBox not found"; exit 1 }
SetValue $to $ToUserId

$msg = FindById $root "MessageTextBox"
if ($null -eq $msg) { Write-Error "MessageTextBox not found"; exit 1 }
SetValue $msg $Text

$btn = FindById $root "SendButton"
if ($null -eq $btn) { Write-Error "SendButton not found"; exit 1 }
Invoke $btn

Write-Host ("sent from PID={0} to={1} text=<{2}>" -f $ProcId, $ToUserId, $Text)

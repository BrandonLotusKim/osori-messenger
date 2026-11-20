param(
    [Parameter(Mandatory=$true)][int]$ProcId,
    [Parameter(Mandatory=$true)][string]$Path  # "방/방 만들기" 같이 / 구분
)

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Id $ProcId -ErrorAction Stop
$hwnd = $proc.MainWindowHandle
$root = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)

function FindMenuItem($parent, $name) {
    $cond = New-Object System.Windows.Automation.AndCondition(
        (New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
            [System.Windows.Automation.ControlType]::MenuItem)),
        (New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::NameProperty, $name)))
    return $parent.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
}

$parts = $Path -split '/'
$current = $root
foreach ($p in $parts) {
    $item = FindMenuItem $current $p
    if ($null -eq $item) {
        Write-Error "MenuItem '$p' 를 찾을 수 없습니다 (Path=$Path)"
        exit 1
    }
    $ep = $null
    try { $ep = $item.GetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern) } catch {}
    if ($null -ne $ep) {
        $ep.Expand()
        Start-Sleep -Milliseconds 200
    } else {
        # 잎 메뉴 → Invoke
        $ip = $item.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
        $ip.Invoke()
    }
    $current = $item
}
Write-Host ("menu OK PID={0} path={1}" -f $ProcId, $Path)

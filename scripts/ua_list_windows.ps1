param([Parameter(Mandatory=$true)][int]$ProcId)
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$desktop = [System.Windows.Automation.AutomationElement]::RootElement
$cond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $ProcId)
$wins = $desktop.FindAll([System.Windows.Automation.TreeScope]::Children, $cond)
foreach ($w in $wins) {
    $c = $w.Current
    Write-Host ("HWND=0x{0:X} name='{1}' cls='{2}'" -f $c.NativeWindowHandle, $c.Name, $c.ClassName)
}

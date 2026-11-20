param(
    [Parameter(Mandatory=$true)][int]$ProcId,
    [int]$Depth = 6
)

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Id $ProcId -ErrorAction Stop
$hwnd = $proc.MainWindowHandle
if ($hwnd -eq [IntPtr]::Zero) { Write-Error "No MainWindowHandle for PID $ProcId"; exit 1 }

$root = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
if ($null -eq $root) { Write-Error "AutomationElement not found"; exit 1 }

function Walk($el, $d, $maxD) {
    if ($d -gt $maxD) { return }
    $info = $el.Current
    $indent = "  " * $d
    $ctrl = $info.ControlType.ProgrammaticName
    $name = $info.Name
    $aid  = $info.AutomationId
    $cls  = $info.ClassName
    Write-Host ("{0}[{1}] aid='{2}' name='{3}' cls='{4}'" -f $indent, $ctrl, $aid, $name, $cls)
    $walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
    $child = $walker.GetFirstChild($el)
    while ($null -ne $child) {
        Walk $child ($d + 1) $maxD
        $child = $walker.GetNextSibling($child)
    }
}

Write-Host ("=== PID={0} HWND=0x{1:X} ===" -f $ProcId, $hwnd.ToInt64())
Walk $root 0 $Depth

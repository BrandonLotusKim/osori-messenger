param(
    [Parameter(Mandatory=$true)][int]$ProcId,
    [Parameter(Mandatory=$true)][string]$CounterpartId
)

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$proc = Get-Process -Id $ProcId -ErrorAction Stop
$hwnd = $proc.MainWindowHandle
$root = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
if ($null -eq $root) { Write-Error "AutomationElement not found"; exit 1 }

$listCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::AutomationIdProperty, "ConversationsListBox")
$list = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $listCond)
if ($null -eq $list) { Write-Error "ConversationsListBox not found"; exit 1 }

$walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
$item = $walker.GetFirstChild($list)
while ($null -ne $item) {
    # 항목 내부에서 상대방 ID 텍스트 블록 찾기
    $textCond = New-Object System.Windows.Automation.PropertyCondition(
        [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
        [System.Windows.Automation.ControlType]::Text)
    $texts = $item.FindAll([System.Windows.Automation.TreeScope]::Descendants, $textCond)
    foreach ($t in $texts) {
        if ($t.Current.Name -eq $CounterpartId) {
            # SelectionItemPattern 으로 선택
            $sip = $item.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern)
            $sip.Select()
            Write-Host ("selected conversation PID={0} counterpart={1}" -f $ProcId, $CounterpartId)
            exit 0
        }
    }
    $item = $walker.GetNextSibling($item)
}
Write-Error "counterpart '$CounterpartId' not in ConversationsListBox"
exit 1

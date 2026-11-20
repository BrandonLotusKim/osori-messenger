using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace OsoriClient.Core;

/// <summary>
/// 조직도 트리 노드 타입.
/// WPF TreeView 에서 단일 ItemTemplate 으로 부서/멤버 모두 그릴 수 있게 하나의 POCO 로 통합.
/// </summary>
public enum OrgTreeNodeKind
{
    Unit,
    Member,
}

/// <summary>
/// TreeView 바인딩용 단일 노드 POCO.
///   - Kind=Unit: 부서. Path/Name/Depth 사용. Children 에 하위 부서/멤버.
///   - Kind=Member: 사용자. UserId/DisplayName/Title/OrgPath 사용. Children 없음.
/// </summary>
public sealed class OrgTreeNode : INotifyPropertyChanged
{
    public OrgTreeNodeKind Kind { get; init; }

    // 공통
    public string DisplayName { get; init; } = "";
    public IList<OrgTreeNode> Children { get; } = new List<OrgTreeNode>();

    // Unit 전용
    public string Path { get; init; } = "";       // '오소리랩/RND/PLAT'
    public string ParentPath { get; init; } = ""; // 빈 문자열 = ROOT
    public int Depth { get; init; }

    // Member 전용
    public string UserId { get; init; } = "";
    public string Title { get; init; } = "";
    public string Email { get; init; } = "";
    public string Phone { get; init; } = "";
    public string Mobile { get; init; } = "";
    public string OrgPath { get; init; } = "";

    // 조직도 다중 선택(대화방 만들기용) 체크 상태 — 서버 데이터가 아니라 순수 클라이언트 UI 상태.
    // 서버에서 다시 그릴 때(OrgListResponse 갱신 등) 매번 새 OrgTreeNode 인스턴스가 만들어지므로
    // 항상 false 로 리셋된다(의도된 동작 — 갱신 후 선택 유지할 이유 없음).
    private bool _isSelectedForRoom;
    public bool IsSelectedForRoom
    {
        get => _isSelectedForRoom;
        set
        {
            if (_isSelectedForRoom == value) return;
            _isSelectedForRoom = value;
            OnPropertyChanged();
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));

    public override string ToString() =>
        Kind == OrgTreeNodeKind.Unit
            ? $"Unit[{Path}, children={Children.Count}]"
            : $"Member[{UserId} @ {OrgPath}]";
}

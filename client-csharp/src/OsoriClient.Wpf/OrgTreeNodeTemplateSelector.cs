using System.Windows;
using System.Windows.Controls;
using OsoriClient.Core;

namespace OsoriClient.Wpf;

/// <summary>
/// OrgTreeNode.Kind 에 따라 HierarchicalDataTemplate(부서) vs DataTemplate(멤버) 선택.
/// </summary>
public sealed class OrgTreeNodeTemplateSelector : DataTemplateSelector
{
    public DataTemplate? UnitTemplate { get; set; }
    public DataTemplate? MemberTemplate { get; set; }

    public override DataTemplate? SelectTemplate(object item, DependencyObject container)
    {
        if (item is OrgTreeNode node)
        {
            return node.Kind == OrgTreeNodeKind.Unit ? UnitTemplate : MemberTemplate;
        }
        return base.SelectTemplate(item, container);
    }
}

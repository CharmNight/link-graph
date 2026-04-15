import { describe, expect, it, vi } from "vitest";
import { buildEdgeActions, buildNodeActions, buildPaneActions } from "./actionSchema";

describe("actionSchema", () => {
  it("offers pane creation actions for any editable canvas", () => {
    const onAddNode = vi.fn();
    const onImportMermaid = vi.fn();
    const onFormatLayout = vi.fn();
    const onOpenAudit = vi.fn();
    const onClose = vi.fn();

    const factActions = buildPaneActions({
      analysisDisplayMode: "FACT_GRAPH",
      editable: true,
      visibleNodeCount: 2,
      hasGroupedSelection: false,
      position: { x: 120, y: 80 },
      onAddNode,
      onImportMermaid,
      onFormatLayout,
      onOpenAudit,
      onClose,
    });

    expect(factActions.map((action) => action.label)).toEqual([
      "新增方法节点",
      "新增说明节点",
      "导入 Mermaid",
      "一键格式化布局",
      "审计当前范围",
    ]);

    factActions[1]?.onSelect();
    expect(onAddNode).toHaveBeenCalledWith("DOC_PAGE", { x: 120, y: 80 });
    expect(onClose).toHaveBeenCalledTimes(1);

    const flowchartActions = buildPaneActions({
      analysisDisplayMode: "FLOWCHART",
      editable: true,
      visibleNodeCount: 3,
      hasGroupedSelection: true,
      position: { x: 200, y: 140 },
      onAddNode,
      onImportMermaid,
      onFormatLayout,
      onOpenAudit,
      onClose,
    });

    expect(flowchartActions.map((action) => action.label)).toEqual([
      "新增方法节点",
      "新增说明节点",
      "导入 Mermaid",
      "一键格式化布局",
      "审计已框选范围",
    ]);
  });

  it("keeps node actions available across modes but only exposes destructive actions when editable", () => {
    const onInspectNode = vi.fn();
    const onRequestSourceNavigation = vi.fn();
    const onRequestBeautification = vi.fn();
    const onRequestAudit = vi.fn();
    const onOpenAudit = vi.fn();
    const onToggleCollapseNode = vi.fn();
    const onExpandOverflowNode = vi.fn();
    const onFormatLayout = vi.fn();
    const onDeleteNodeSubtree = vi.fn();
    const onDeleteNode = vi.fn();
    const onClose = vi.fn();

    const editableActions = buildNodeActions({
      analysisDisplayMode: "FACT_GRAPH",
      editable: true,
      nodeId: "method:submit-order",
      canNavigateToSource: true,
      collapsed: false,
      overflowActionLabel: "继续展开此分支",
      onInspectNode,
      onRequestSourceNavigation,
      onRequestBeautification,
      onRequestAudit,
      onOpenAudit,
      onToggleCollapseNode,
      onExpandOverflowNode,
      onFormatLayout,
      onDeleteNodeSubtree,
      onDeleteNode,
      onClose,
    });

    expect(editableActions.map((action) => action.label)).toEqual([
      "编辑节点",
      "打开源码",
      "讲解当前链路",
      "审计当前节点",
      "设为审计范围起点",
      "折叠整个下游子树",
      "继续展开此分支",
      "一键格式化布局",
      "删除节点及子节点",
      "删除节点",
    ]);

    editableActions[6]?.onSelect();
    editableActions[9]?.onSelect();
    expect(onExpandOverflowNode).toHaveBeenCalledWith("method:submit-order");
    expect(onDeleteNode).toHaveBeenCalledWith("method:submit-order");

    const resourceActions = buildNodeActions({
      analysisDisplayMode: "RESOURCE_RELATION_VIEW",
      editable: true,
      nodeId: "db:orders",
      canNavigateToSource: false,
      collapsed: true,
      overflowActionLabel: null,
      onInspectNode,
      onRequestSourceNavigation,
      onRequestBeautification,
      onRequestAudit,
      onOpenAudit,
      onToggleCollapseNode,
      onExpandOverflowNode,
      onFormatLayout,
      onDeleteNodeSubtree,
      onDeleteNode,
      onClose,
    });

    expect(resourceActions.map((action) => action.label)).toEqual([
      "编辑节点",
      "讲解当前链路",
      "审计当前节点",
      "设为审计范围起点",
      "展开整个下游子树",
      "一键格式化布局",
      "删除节点及子节点",
      "删除节点",
    ]);
  });

  it("offers edge deletion for any editable view", () => {
    const onDeleteEdge = vi.fn();
    const onClose = vi.fn();

    const editableActions = buildEdgeActions({
      analysisDisplayMode: "FACT_GRAPH",
      editable: true,
      edgeId: "edge:1",
      onDeleteEdge,
      onClose,
    });
    expect(editableActions.map((action) => action.label)).toEqual(["删除连线"]);
    editableActions[0]?.onSelect();
    expect(onDeleteEdge).toHaveBeenCalledWith("edge:1");
    expect(onClose).toHaveBeenCalledTimes(1);

    const flowchartActions = buildEdgeActions({
      analysisDisplayMode: "FLOWCHART",
      editable: true,
      edgeId: "edge:2",
      onDeleteEdge,
      onClose,
    });
    expect(flowchartActions.map((action) => action.label)).toEqual(["删除连线"]);
  });
});

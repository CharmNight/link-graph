import type { AnalysisDisplayMode, GraphPosition } from "../../../types";

export interface GraphContextMenuAction {
  id: string;
  label: string;
  onSelect: () => void;
}

interface PaneActionSchemaInput {
  analysisDisplayMode: AnalysisDisplayMode;
  editable: boolean;
  visibleNodeCount: number;
  hasGroupedSelection: boolean;
  position?: GraphPosition;
  onAddNode: (kind: "METHOD" | "DOC_PAGE", position?: GraphPosition) => void;
  onImportMermaid: () => void;
  onFormatLayout: () => void;
  onOpenQa: () => void;
  onClose: () => void;
}

interface NodeActionSchemaInput {
  analysisDisplayMode: AnalysisDisplayMode;
  editable: boolean;
  nodeId: string;
  canNavigateToSource: boolean;
  collapsed: boolean;
  overflowActionLabel: string | null;
  invocationExpansionActionLabel?: string | null;
  expansionId?: string | null;
  onInspectNode: (nodeId: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
  onRequestBeautification: (selectedNodeId?: string) => void;
  onRequestQa: (selectedNodeId?: string) => void;
  onOpenQa: (selectedNodeId?: string) => void;
  onToggleCollapseNode: (nodeId: string) => void;
  onExpandOverflowNode: (nodeId: string) => void;
  onExpandInvocation?: (nodeId: string) => void;
  onRemoveInvocationExpansion?: (expansionId: string) => void;
  onFormatLayout: () => void;
  onDeleteNodeSubtree: (nodeId: string) => void;
  onDeleteNode: (nodeId: string) => void;
  onClose: () => void;
}

interface EdgeActionSchemaInput {
  analysisDisplayMode: AnalysisDisplayMode;
  editable: boolean;
  edgeId: string;
  onDeleteEdge: (edgeId: string) => void;
  onClose: () => void;
}

function allowMutatingActions(analysisDisplayMode: AnalysisDisplayMode, editable: boolean): boolean {
  return editable;
}

function makeAction(id: string, label: string, onSelect: () => void): GraphContextMenuAction {
  return { id, label, onSelect };
}

export function buildPaneActions({
  analysisDisplayMode,
  editable,
  visibleNodeCount,
  hasGroupedSelection,
  position,
  onAddNode,
  onImportMermaid,
  onFormatLayout,
  onOpenQa,
  onClose,
}: PaneActionSchemaInput): GraphContextMenuAction[] {
  const actions: GraphContextMenuAction[] = [];
  if (allowMutatingActions(analysisDisplayMode, editable)) {
    actions.push(
      makeAction("add-method", "新增方法节点", () => {
        onAddNode("METHOD", position);
        onClose();
      }),
      makeAction("add-doc", "新增说明节点", () => {
        onAddNode("DOC_PAGE", position);
        onClose();
      }),
    );
  }
  actions.push(
    makeAction("import-mermaid", "导入 Mermaid", () => {
      onImportMermaid();
      onClose();
    }),
  );
  if (visibleNodeCount > 0) {
    actions.push(
      makeAction("format-layout", "一键格式化布局", () => {
        onFormatLayout();
        onClose();
      }),
      makeAction("open-qa", hasGroupedSelection ? "问答已框选范围" : "问答当前范围", () => {
        onOpenQa();
        onClose();
      }),
    );
  }
  return actions;
}

export function buildNodeActions({
  analysisDisplayMode,
  editable,
  nodeId,
  canNavigateToSource,
  collapsed,
  overflowActionLabel,
  invocationExpansionActionLabel,
  expansionId,
  onInspectNode,
  onRequestSourceNavigation,
  onRequestBeautification,
  onRequestQa,
  onOpenQa,
  onToggleCollapseNode,
  onExpandOverflowNode,
  onExpandInvocation,
  onRemoveInvocationExpansion,
  onFormatLayout,
  onDeleteNodeSubtree,
  onDeleteNode,
  onClose,
}: NodeActionSchemaInput): GraphContextMenuAction[] {
  const actions: GraphContextMenuAction[] = [
    makeAction("inspect-node", "编辑节点", () => {
      onInspectNode(nodeId);
      onClose();
    }),
  ];
  if (canNavigateToSource) {
    actions.push(
      makeAction("open-source", "打开源码", () => {
        onRequestSourceNavigation(nodeId);
        onClose();
      }),
    );
  }
  if (invocationExpansionActionLabel && onExpandInvocation) {
    actions.push(
      makeAction("expand-invocation", invocationExpansionActionLabel, () => {
        onExpandInvocation(nodeId);
        onClose();
      }),
    );
  }
  actions.push(
    makeAction("beautify-node", "讲解当前链路", () => {
      onRequestBeautification(nodeId);
      onClose();
    }),
    makeAction("qa-node", "问答当前节点", () => {
      onRequestQa(nodeId);
      onClose();
    }),
    makeAction("set-qa-anchor", "设为问答范围起点", () => {
      onOpenQa(nodeId);
      onClose();
    }),
    makeAction("toggle-collapse", collapsed ? "展开整个下游子树" : "折叠整个下游子树", () => {
      onToggleCollapseNode(nodeId);
      onClose();
    }),
  );
  if (overflowActionLabel) {
    actions.push(
      makeAction("expand-overflow", overflowActionLabel, () => {
        onExpandOverflowNode(nodeId);
        onClose();
      }),
    );
  }
  if (expansionId && onRemoveInvocationExpansion) {
    actions.push(
      makeAction("remove-invocation-expansion", "移除此展开", () => {
        onRemoveInvocationExpansion(expansionId);
        onClose();
      }),
    );
  }
  actions.push(
    makeAction("format-layout", "一键格式化布局", () => {
      onFormatLayout();
      onClose();
    }),
  );
  if (allowMutatingActions(analysisDisplayMode, editable)) {
    actions.push(
      makeAction("delete-node-subtree", "删除节点及子节点", () => {
        onDeleteNodeSubtree(nodeId);
        onClose();
      }),
      makeAction("delete-node", "删除节点", () => {
        onDeleteNode(nodeId);
        onClose();
      }),
    );
  }
  return actions;
}

export function buildEdgeActions({
  analysisDisplayMode,
  editable,
  edgeId,
  onDeleteEdge,
  onClose,
}: EdgeActionSchemaInput): GraphContextMenuAction[] {
  if (!allowMutatingActions(analysisDisplayMode, editable)) {
    return [];
  }
  return [
    makeAction("delete-edge", "删除连线", () => {
      onDeleteEdge(edgeId);
      onClose();
    }),
  ];
}

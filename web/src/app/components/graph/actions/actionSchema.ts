// 画布右键菜单的动作 schema 构建器。
// 把"用户当前上下文（选中了什么、可编辑什么）"转换为具体的菜单项列表。
// 三种场景：画布空白处右键（Pane）、节点上右键（Node）、边上右键（Edge），
// 各自有不同的可用动作。
import type { AnalysisDisplayMode, GraphEditCommandKind, GraphPosition } from "../../../types";

/** 单个右键菜单项。 */
export interface GraphContextMenuAction {
  /** 动作 ID（用于 React key）。 */
  id: string;
  /** 展示文案。 */
  label: string;
  /** 点击时的回调。 */
  onSelect: () => void;
}

/** 画布空白处右键菜单的输入。 */
interface PaneActionSchemaInput {
  analysisDisplayMode: AnalysisDisplayMode;
  /** 当前视图是否可编辑。 */
  editable: boolean;
  /** 可见节点数。 */
  visibleNodeCount: number;
  /** 是否有多选。 */
  hasGroupedSelection: boolean;
  /** 右键位置的画布坐标。 */
  position?: GraphPosition;
  onAddNode: (kind: "METHOD" | "DOC_PAGE", position?: GraphPosition) => void;
  onImportMermaid: () => void;
  onFormatLayout: () => void;
  onOpenQa: () => void;
  onClose: () => void;
}

/** 节点上右键菜单的输入。 */
interface NodeActionSchemaInput {
  analysisDisplayMode: AnalysisDisplayMode;
  editable: boolean;
  nodeId: string;
  /** 权限校验回调：判断该节点是否可执行某种命令。 */
  canEditNode?: (command: GraphEditCommandKind) => boolean;
  /** 节点是否可跳转到源码。 */
  canNavigateToSource: boolean;
  /** 节点是否已折叠。 */
  collapsed: boolean;
  /** 溢出展开动作的标签；为 null 表示无溢出展开。 */
  overflowActionLabel: string | null;
  /** 调用展开动作的标签；为 null/undefined 表示无调用展开。 */
  invocationExpansionActionLabel?: string | null;
  /** 如果节点属于某个调用展开组，该组的 expansionId。 */
  expansionId?: string | null;
  onInspectNode: (nodeId: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
  onRequestBeautification: (selectedNodeId?: string) => void;
  onPrimeQuestionComposer: (selectedNodeId?: string) => void;
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

/** 边上右键菜单的输入。 */
interface EdgeActionSchemaInput {
  analysisDisplayMode: AnalysisDisplayMode;
  editable: boolean;
  edgeId: string;
  canEditEdge?: (command: GraphEditCommandKind) => boolean;
  onDeleteEdge: (edgeId: string) => void;
  onClose: () => void;
}

/** 判断是否允许变更类操作（增删节点/边）。当前仅取决于 editable 标志。 */
function allowMutatingActions(analysisDisplayMode: AnalysisDisplayMode, editable: boolean): boolean {
  return editable;
}

/** 判断节点是否可执行某种命令（editable + 权限回调通过）。 */
function canRunNodeCommand(
  editable: boolean,
  canEditNode: ((command: GraphEditCommandKind) => boolean) | undefined,
  command: GraphEditCommandKind,
): boolean {
  return editable && (canEditNode?.(command) ?? true);
}

/** 判断边是否可执行某种命令。 */
function canRunEdgeCommand(
  editable: boolean,
  canEditEdge: ((command: GraphEditCommandKind) => boolean) | undefined,
  command: GraphEditCommandKind,
): boolean {
  return editable && (canEditEdge?.(command) ?? true);
}

/** 构造一个菜单项的便捷工厂。 */
function makeAction(id: string, label: string, onSelect: () => void): GraphContextMenuAction {
  return { id, label, onSelect };
}

/**
 * 构建画布空白处右键菜单。
 *
 * 可编辑时提供"新增方法/说明节点"；始终提供"导入 Mermaid"；
 * 有可见节点时提供"格式化布局"和"问答"。
 */
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

/**
 * 构建节点右键菜单。
 *
 * 动作列表（按顺序）：编辑节点 → 打开源码（如果可跳转）→ 展开调用（如果有）→
 * 讲解链路 → 问答节点 → 设为问答目标 → 折叠/展开子树 → 展开溢出（如果有）→
 * 移除展开（如果属于展开组）→ 格式化布局 → 删除节点/子树（如果可编辑）。
 */
export function buildNodeActions({
  analysisDisplayMode,
  editable,
  nodeId,
  canEditNode,
  canNavigateToSource,
  collapsed,
  overflowActionLabel,
  invocationExpansionActionLabel,
  expansionId,
  onInspectNode,
  onRequestSourceNavigation,
  onRequestBeautification,
  onPrimeQuestionComposer,
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
      onPrimeQuestionComposer(nodeId);
      onClose();
    }),
    makeAction("set-qa-anchor", "设为问答目标", () => {
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
      ...(canRunNodeCommand(editable, canEditNode, "DELETE_NODE_SUBTREE")
        ? [
            makeAction("delete-node-subtree", "删除节点及子节点", () => {
              onDeleteNodeSubtree(nodeId);
              onClose();
            }),
          ]
        : []),
      ...(canRunNodeCommand(editable, canEditNode, "DELETE_NODE")
        ? [
            makeAction("delete-node", "删除节点", () => {
              onDeleteNode(nodeId);
              onClose();
            }),
          ]
        : []),
    );
  }
  return actions;
}

/**
 * 构建边右键菜单。
 * 仅在可编辑且允许 DELETE_EDGE 时提供"删除连线"。
 */
export function buildEdgeActions({
  analysisDisplayMode,
  editable,
  edgeId,
  canEditEdge,
  onDeleteEdge,
  onClose,
}: EdgeActionSchemaInput): GraphContextMenuAction[] {
  if (!allowMutatingActions(analysisDisplayMode, editable) || !canRunEdgeCommand(editable, canEditEdge, "DELETE_EDGE")) {
    return [];
  }
  return [
    makeAction("delete-edge", "删除连线", () => {
      onDeleteEdge(edgeId);
      onClose();
    }),
  ];
}

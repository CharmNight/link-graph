import type { CSSProperties } from "react";
import {
  Handle,
  MarkerType,
  Position,
  type Edge,
  type Node,
  type NodeProps,
  type NodeTypes,
} from "@xyflow/react";
import { nodeCardWidth } from "../../graphNodeSizing";
import type { NodeMeasuredSize, NodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import type { DraftCompareStatus, GraphProjectionIndex, LinkGraphEdge, LinkGraphNode } from "../../types";
import { edgeTypeLabel } from "../../labels";
import { FactGraphNodeCard } from "../../components/graph/nodes/FactGraphNodeCard";
import { isDecisionFlowScope, isFlowActionNode } from "../../components/graph/nodes/nodePresentation";
import { canEditNodeLayout } from "../../layoutEditability";
import { reactFlowNodeInternalsSignature } from "../../reactflow/nodeInternalsSignature";
import type { RoutedEdgeData } from "../../reactflow/RoutedEdge";
import { useStableNodeInternalsUpdate } from "../../reactflow/useStableNodeInternalsUpdate";
import { resolveGraphNodeHighlightClassName } from "../graphNodeHighlights";
import {
  draftCompareEdgeClassName,
  draftCompareEdgeStyle,
  draftCompareMarkerColor,
} from "../draftComparePresentation";

/** React Flow 中事实图节点携带的运行时数据：原始节点模型、选中/折叠/草稿比对等展示态，以及溢出展开、尺寸上报等回调。 */
interface FactGraphNodeData extends Record<string, unknown> {
  node: LinkGraphNode;
  selected?: boolean;
  collapsed: boolean;
  collapsedCount?: number;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  draftCompareStatus?: DraftCompareStatus;
  onExpandOverflow: () => void;
  onMeasure?: (size: NodeMeasuredSize) => void;
}

/** 构造事实图节点列表时所需的全部上下文：原始节点、各类展示状态、折叠信息以及尺寸注册表等。 */
interface BuildFactGraphNodesOptions {
  nodes: LinkGraphNode[];
  selectedNodeId: string | null;
  explanationFocusNodeId?: string | null;
  draftChangedNodeIds?: string[];
  draftCompareNodeStatuses?: Record<string, DraftCompareStatus>;
  collapsedNodeIds?: Iterable<string>;
  collapsedDescendantCountByNodeId?: Record<string, number>;
  projectionIndex?: GraphProjectionIndex | null;
  onExpandOverflowNode: (nodeId: string) => void;
  nodeSizeRegistry: NodeSizeRegistry;
}

/** 构造事实图边列表所需上下文：原始边集合以及可选的草稿比对状态，用于在比对模式下高亮差异。 */
interface BuildFactGraphEdgesOptions {
  edges: LinkGraphEdge[];
  draftCompareEdgeStatuses?: Record<string, DraftCompareStatus>;
}

// React Flow 中事实图节点的具体类型，绑定 data 形状与自定义节点 type 名。
type FactGraphFlowNode = Node<FactGraphNodeData, "factGraphNode">;
// 上述节点类型对应的 React Flow 组件 props 类型。
type FactGraphFlowNodeProps = NodeProps<FactGraphFlowNode>;

// 节点上连线手柄的基础样式：默认透明，仅在可连线时显示半透明圆形热区。
const FACT_GRAPH_HANDLE_STYLE_BASE: CSSProperties = {
  width: 16,
  height: 16,
  opacity: 0,
  background: "rgba(14, 139, 114, 0.6)",
  border: "2px solid rgba(255, 255, 255, 0.9)",
  borderRadius: "50%",
  transition: "opacity 0.15s ease",
};

/** 根据当前是否允许连线，返回连线手柄的可见性与指针交互样式。 */
function factGraphHandleStyle(isConnectable: boolean): CSSProperties {
  return {
    ...FACT_GRAPH_HANDLE_STYLE_BASE,
    opacity: isConnectable ? 0.28 : 0,
    pointerEvents: isConnectable ? "all" : "none",
    cursor: isConnectable ? "crosshair" : "default",
  };
}

/**
 * 事实图中单个节点的 React 渲染组件：
 * 渲染左右两侧的连线手柄与统一的节点卡片，
 * 并通过签名比对驱动节点内部状态的稳定更新，避免无谓重渲染。
 */
function FactGraphReactNode({ id, data, selected, isConnectable }: FactGraphFlowNodeProps) {
  const handleStyle = factGraphHandleStyle(isConnectable);
  const appSelected = data.selected === true || selected;
  const nodeInternalsSignature = [
    reactFlowNodeInternalsSignature(data.node),
    String(isConnectable),
    data.collapsed,
    String(data.collapsedCount ?? ""),
  ].join("\u0001");
  useStableNodeInternalsUpdate(id, nodeInternalsSignature);

  return (
    <div className={["fact-graph-react-node", isConnectable ? "is-connectable" : ""].join(" ").trim()}>
      <Handle id="target-left" type="target" position={Position.Left} style={handleStyle} />
      <Handle id="source-right" type="source" position={Position.Right} style={handleStyle} />
      <FactGraphNodeCard
        node={data.node}
        selected={appSelected}
        collapsed={data.collapsed}
        collapsedCount={data.collapsedCount}
        explanationFocused={data.explanationFocused}
        draftChanged={data.draftChanged}
        draftCompareStatus={data.draftCompareStatus}
        onMeasure={data.onMeasure}
        onExpandOverflow={data.onExpandOverflow}
      />
    </div>
  );
}

/** React Flow 识别的节点类型注册表：把字符串 type "factGraphNode" 映射到上面的渲染组件。 */
export const FACT_GRAPH_NODE_TYPES: NodeTypes = {
  factGraphNode: FactGraphReactNode,
};

/**
 * 根据节点的展示角色（锚点/上游/下游）以及流程类型（作用域/动作/决策），
 * 生成差异化的边框、背景渐变和阴影样式，让用户一眼区分节点所处的语义角色。
 */
function factGraphNodeStyle(node: LinkGraphNode) {
  const presentationRole = node.metadata?.["presentation.role"];
  const isFlowScope = node.type === "FLOW_SCOPE";
  const isFlowAction = isFlowActionNode(node);
  const isFlowDecision = isDecisionFlowScope(node);
  if (presentationRole === "ANCHOR") {
    return {
      width: nodeCardWidth(node),
      borderRadius: 18,
      border: "2px solid rgba(14, 139, 114, 0.48)",
      background: "linear-gradient(145deg, rgba(14, 139, 114, 0.18), var(--panel))",
      boxShadow: "0 14px 30px rgba(14, 139, 114, 0.18)",
      padding: 0,
    };
  }
  if (presentationRole === "UPSTREAM" && !isFlowScope && !isFlowAction) {
    return {
      width: nodeCardWidth(node),
      borderRadius: 18,
      border: "1px solid rgba(25, 90, 153, 0.28)",
      background: "linear-gradient(180deg, rgba(25, 90, 153, 0.12), var(--panel))",
      boxShadow: "0 6px 18px rgba(25, 90, 153, 0.08)",
      padding: 0,
    };
  }
  if (presentationRole === "DOWNSTREAM" && !isFlowScope && !isFlowAction) {
    return {
      width: nodeCardWidth(node),
      borderRadius: 18,
      border: "1px solid rgba(143, 79, 35, 0.26)",
      background: "linear-gradient(180deg, rgba(143, 79, 35, 0.12), var(--panel))",
      boxShadow: "0 6px 18px rgba(143, 79, 35, 0.08)",
      padding: 0,
    };
  }
  return {
    width: nodeCardWidth(node),
    borderRadius: 18,
    border: isFlowDecision
      ? "1px solid rgba(185, 104, 47, 0.34)"
      : isFlowScope
        ? "1px solid rgba(25, 90, 153, 0.28)"
        : isFlowAction
          ? "1px solid rgba(14, 139, 114, 0.24)"
          : "1px solid rgba(44, 32, 22, 0.18)",
    background: isFlowDecision
      ? "linear-gradient(145deg, rgba(185, 104, 47, 0.16), var(--panel))"
      : isFlowScope
        ? "linear-gradient(180deg, rgba(25, 90, 153, 0.12), var(--panel))"
        : isFlowAction
          ? "linear-gradient(135deg, rgba(14, 139, 114, 0.16), var(--panel))"
          : "var(--panel)",
    boxShadow: isFlowDecision
      ? "0 10px 24px rgba(185, 104, 47, 0.12)"
      : isFlowScope
        ? "0 6px 18px rgba(25, 90, 153, 0.08)"
        : isFlowAction
          ? "0 8px 18px rgba(14, 139, 114, 0.1)"
          : "0 2px 8px rgba(49, 33, 20, 0.05)",
    padding: 0,
  };
}

/** 决定边上显示的文字：优先用边自带标签，控制流/调用/包含类边不重复显示类型名，其余补上类型中文文案。 */
function factGraphEdgeLabel(edge: LinkGraphEdge): string | undefined {
  if (edge.label?.trim()) {
    return edge.label.trim();
  }
  if (edge.type === "CALL" || edge.type === "CONTAINS_FLOW" || edge.type === "CONTROL_FLOW") {
    return undefined;
  }
  return edgeTypeLabel(edge.type);
}

/** 把边类型映射为 CSS 类名，让样式表能按边类型应用不同的线条颜色与虚线效果。 */
function factGraphEdgeClassName(edge: LinkGraphEdge): string {
  switch (edge.type) {
    case "CONTAINS_FLOW":
      return "edge-contains-flow";
    case "CONTROL_FLOW":
      return "edge-control-flow";
    case "CALL":
      return "edge-call";
    default:
      return "edge-domain";
  }
}

/** 按边类型给出线条颜色、粗细、透明度等内联样式，区分调用、控制流、包含等语义。 */
function factGraphEdgeStyle(edge: LinkGraphEdge) {
  switch (edge.type) {
    case "CONTAINS_FLOW":
      return {
        stroke: "var(--edge-info)",
        strokeWidth: 1.8,
        strokeDasharray: "7 5",
        opacity: 0.84,
      };
    case "CONTROL_FLOW":
      return {
        stroke: "var(--edge-info)",
        strokeWidth: 2.1,
        opacity: 0.94,
      };
    case "CALL":
      return {
        stroke: "var(--edge-warning)",
        strokeWidth: 1.9,
        opacity: 0.92,
      };
    default:
      return {
        stroke: "var(--edge-neutral)",
        strokeWidth: 1.6,
        opacity: 0.88,
      };
  }
}

/**
 * 把领域节点模型批量转换为 React Flow 节点：
 * 计算高亮类名、选中态、是否可拖拽（按投影权限判定），
 * 并把折叠/草稿比对/溢出回调等运行时数据塞入 node.data。
 */
export function buildFactGraphNodes({
  nodes,
  selectedNodeId,
  explanationFocusNodeId = null,
  draftChangedNodeIds = [],
  draftCompareNodeStatuses = {},
  collapsedNodeIds = [],
  collapsedDescendantCountByNodeId = {},
  projectionIndex = null,
  onExpandOverflowNode,
  nodeSizeRegistry,
}: BuildFactGraphNodesOptions): FactGraphFlowNode[] {
  const collapsedNodeIdSet = new Set(collapsedNodeIds);
  const draftChangedNodeIdSet = new Set(draftChangedNodeIds);
  return nodes.map((node) => ({
    id: node.id,
    type: "factGraphNode",
    className: resolveGraphNodeHighlightClassName({
      nodeId: node.id,
      explanationFocusNodeId,
      draftChangedNodeIdSet,
      draftCompareStatus: draftCompareNodeStatuses[node.id],
    }) || undefined,
    selected: selectedNodeId === node.id,
    draggable: canEditNodeLayout(node, "FACT_GRAPH", projectionIndex),
    position: node.position ?? { x: 80, y: 88 },
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    data: {
      node,
      selected: selectedNodeId === node.id,
      collapsed: collapsedNodeIdSet.has(node.id),
      collapsedCount: collapsedDescendantCountByNodeId[node.id],
      explanationFocused: explanationFocusNodeId === node.id,
      draftChanged: draftChangedNodeIdSet.has(node.id),
      draftCompareStatus: draftCompareNodeStatuses[node.id],
      onExpandOverflow: () => onExpandOverflowNode(node.id),
      onMeasure: nodeSizeRegistry.reporter(node.id),
    },
    style: factGraphNodeStyle(node),
  }));
}

/**
 * 把领域边模型批量转换为 React Flow 边：
 * 设置标签、走线类型、CSS 类名、内联样式与箭头颜色，
 * 并叠加草稿比对模式的差异样式，使新增/删除/变更的边在比对视图中突出显示。
 */
export function buildFactGraphEdges({
  edges,
  draftCompareEdgeStatuses = {},
}: BuildFactGraphEdgesOptions): Array<Edge<RoutedEdgeData, "routedEdge">> {
  return edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: factGraphEdgeLabel(edge),
    type: "routedEdge",
    className: draftCompareEdgeClassName(factGraphEdgeClassName(edge), draftCompareEdgeStatuses[edge.id]),
    data: {
      route: edge.route,
    },
    style: draftCompareEdgeStyle(factGraphEdgeStyle(edge), draftCompareEdgeStatuses[edge.id]),
    markerEnd: {
      type: MarkerType.ArrowClosed,
      width: 20,
      height: 20,
      color: draftCompareMarkerColor(edge.type === "CALL" ? "#8f4f23" : "#5f5a53", draftCompareEdgeStatuses[edge.id]),
    },
  }));
}

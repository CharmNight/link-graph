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
import {
  FLOWCHART_DECISION_MIN_HEIGHT,
  FLOWCHART_MERGE_WIDTH,
  flowchartNodeCardWidth,
} from "../../graphNodeSizing";
import type { NodeMeasuredSize, NodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import type { DraftCompareStatus, GraphProjectionIndex, LinkGraphEdge, LinkGraphNode } from "../../types";
import { edgeTypeLabel } from "../../labels";
import { FlowchartNodeCard } from "../../components/graph/nodes/FlowchartNodeCard";
import { flowchartKind } from "../../components/graph/nodes/nodePresentation";
import { canEditNodeLayout } from "../../layoutEditability";
import { reactFlowNodeInternalsSignature } from "../../reactflow/nodeInternalsSignature";
import type { RoutedEdgeData } from "../../reactflow/RoutedEdge";
import { useStableNodeInternalsUpdate } from "../../reactflow/useStableNodeInternalsUpdate";
import {
  buildIncomingControlFlowIndex,
  buildMergeTargetPortLayout,
  buildOutgoingControlFlowIndex,
  flowchartDecisionPortHandleStyle,
  flowchartMergeTargetHandleStyle,
  flowchartMergeTargetPortId,
  hasExceptionControlFlowOutlet,
  isDecisionFallthroughEdge,
  resolveDecisionSourcePort,
  resolveDecisionTargetPort,
} from "./decisionPortGeometry";
import { resolveGraphNodeHighlightClassName } from "../graphNodeHighlights";
import {
  draftCompareEdgeClassName,
  draftCompareEdgeStyle,
  draftCompareMarkerColor,
} from "../draftComparePresentation";

/** 流程图节点在 React Flow 中承载的运行时数据，包含原始节点、选中/异常/汇聚等展示状态和尺寸回调。 */
interface FlowchartNodeData extends Record<string, unknown> {
  node: LinkGraphNode;
  selected?: boolean;
  hasExceptionSource: boolean;
  mergeLeftTargetCount: number;
  mergeRightTargetCount: number;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  draftCompareStatus?: DraftCompareStatus;
  onMeasure?: (size: NodeMeasuredSize) => void;
}

interface InvocationExpansionSummaryNodeData extends Record<string, unknown> {
  node: LinkGraphNode;
  selected?: boolean;
}

/** 构造流程图 React Flow 节点列表时所需的全部入参：节点、边、选中态、解释聚焦、草稿比较与投影信息。 */
interface BuildFlowchartNodesOptions {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  selectedNodeId: string | null;
  explanationFocusNodeId?: string | null;
  draftChangedNodeIds?: string[];
  draftCompareNodeStatuses?: Record<string, DraftCompareStatus>;
  projectionIndex?: GraphProjectionIndex | null;
  nodeSizeRegistry: NodeSizeRegistry;
}

/** 构造流程图 React Flow 边列表时所需的入参：边集合、节点索引以及可选的草稿比较状态。 */
interface BuildFlowchartEdgesOptions {
  edges: LinkGraphEdge[];
  nodeIndex: Map<string, LinkGraphNode>;
  draftCompareEdgeStatuses?: Record<string, DraftCompareStatus>;
}

/** 流程图节点在 React Flow 中的具体节点类型别名，绑定 flowchartNode 类型与 FlowchartNodeData。 */
type FlowchartFlowNode =
  | Node<FlowchartNodeData, "flowchartNode">
  | Node<InvocationExpansionSummaryNodeData, "invocationExpansionSummaryNode">;
/** React Flow 注入给单个流程图节点的属性类型，包含 id、数据、连接能力、选中状态等。 */
type FlowchartFlowNodeProps = NodeProps<Node<FlowchartNodeData, "flowchartNode">>;
type InvocationExpansionSummaryNodeProps = NodeProps<Node<InvocationExpansionSummaryNodeData, "invocationExpansionSummaryNode">>;

// 节点元数据中保存"投影来源节点 ID 列表"的字段名，用于在融合/投影节点上回溯原始节点身份。
const FLOWCHART_ALIAS_IDS_KEY = "flowchart.projectedFromNodeIds";

/** 流程图节点上可视化连接句柄的标识联合类型，覆盖四个方向上的源/目标句柄。 */
type VisibleFlowchartHandleId =
  | "target-top"
  | "target-right"
  | "target-bottom"
  | "target-left"
  | "source-top"
  | "source-right"
  | "source-bottom"
  | "source-left";

// 所有流程图连接句柄共享的基础样式：固定大小、隐藏、带主题色边框，便于在 hover/连接时再叠加透明度。
const FLOWCHART_HANDLE_STYLE_BASE: CSSProperties = {
  width: 16,
  height: 16,
  opacity: 0,
  background: "rgba(14, 139, 114, 0.6)",
  border: "2px solid rgba(255, 255, 255, 0.9)",
  borderRadius: "50%",
  transition: "opacity 0.12s ease",
};

/** 根据可连接状态与句柄方向，返回带交互反馈的句柄样式：源句柄在可连接时显示并启用指针事件。 */
function flowchartHandleStyle(
  isConnectable: boolean,
  handleDirection: "source" | "target",
): CSSProperties {
  const shouldActivate = isConnectable && handleDirection === "source";
  return {
    ...FLOWCHART_HANDLE_STYLE_BASE,
    opacity: shouldActivate ? 0.28 : 0,
    pointerEvents: handleDirection === "source" ? (isConnectable ? "all" : "none") : "none",
    cursor: shouldActivate ? "crosshair" : "default",
  };
}

/** 让辅助句柄（如汇聚节点的多入口）保持隐藏且不响应指针，仅作为路由锚点存在。 */
function flowchartAuxiliaryHandleStyle(baseStyle: CSSProperties): CSSProperties {
  return {
    ...baseStyle,
    opacity: 0,
    pointerEvents: "none",
  };
}

/** 把基础句柄样式按其所在方向对齐到节点边缘（上/下贴边水平居中，左/右贴边垂直居中）。 */
function flowchartFlushHandleStyle(
  position: Position,
  baseStyle: CSSProperties,
): CSSProperties {
  switch (position) {
    case Position.Top:
      return {
        ...baseStyle,
        transform: "translate(-50%, 0)",
      };
    case Position.Bottom:
      return {
        ...baseStyle,
        transform: "translate(-50%, 0)",
      };
    case Position.Left:
      return {
        ...baseStyle,
        top: "50%",
        transform: "translate(0, -50%)",
      };
    case Position.Right:
      return {
        ...baseStyle,
        top: "50%",
        transform: "translate(0, -50%)",
      };
    default:
      return baseStyle;
  }
}

/**
 * 决定某个可视化句柄的最终样式：决策节点上的源/目标句柄走专用菱形端口布局，
 * 其他节点则按方向贴边显示，保证句柄位置与节点几何形状吻合。
 */
function flowchartVisibleHandleStyle(
  kind: string,
  handleId: VisibleFlowchartHandleId,
  position: Position,
  baseStyle: CSSProperties,
): CSSProperties {
  if (
    kind === "DECISION"
    && (
      handleId === "target-top"
      || handleId === "target-left"
      || handleId === "target-right"
      || handleId === "source-left"
      || handleId === "source-right"
      || handleId === "source-bottom"
    )
  ) {
    return flowchartDecisionPortHandleStyle(handleId, baseStyle);
  }
  return flowchartFlushHandleStyle(position, baseStyle);
}

/** 节点上需要渲染的四向目标（入口）句柄配置，统一驱动 React Flow 渲染逻辑。 */
const FLOWCHART_VISIBLE_TARGET_HANDLES: Array<{
  id: Extract<VisibleFlowchartHandleId, `target-${string}`>;
  position: Position;
}> = [
  { id: "target-top", position: Position.Top },
  { id: "target-right", position: Position.Right },
  { id: "target-bottom", position: Position.Bottom },
  { id: "target-left", position: Position.Left },
];

/** 节点上需要渲染的四向源（出口）句柄配置，与目标句柄对称覆盖四个方向。 */
const FLOWCHART_VISIBLE_SOURCE_HANDLES: Array<{
  id: Extract<VisibleFlowchartHandleId, `source-${string}`>;
  position: Position;
}> = [
  { id: "source-top", position: Position.Top },
  { id: "source-right", position: Position.Right },
  { id: "source-bottom", position: Position.Bottom },
  { id: "source-left", position: Position.Left },
];

/** 为节点外壳返回内联样式，目前仅决策节点需要强制最小高度以容纳菱形布局。 */
function flowchartNodeShellStyle(kind: string): CSSProperties | undefined {
  if (kind !== "DECISION") {
    return undefined;
  }
  return {
    minHeight: FLOWCHART_DECISION_MIN_HEIGHT,
  };
}

/**
 * 流程图节点的 React 实现：根据节点类型渲染外壳、源/目标句柄和内容卡片，
 * 决策/普通节点使用四向句柄，汇聚节点额外按计数渲染左右两侧的多入口句柄，
 * 同时通过签名比较稳定地同步 React Flow 的内部状态。
 */
function FlowchartReactNode({ id, data, isConnectable, selected }: FlowchartFlowNodeProps) {
  const kind = flowchartKind(data.node);
  const appSelected = data.selected === true || selected;
  const visibleTargetHandleStyle = flowchartHandleStyle(isConnectable, "target");
  const visibleSourceHandleStyle = flowchartHandleStyle(isConnectable, "source");
  const auxiliaryHandleStyle = flowchartAuxiliaryHandleStyle(visibleTargetHandleStyle);
  const mergeLeftTargetCount = kind === "MERGE" ? Math.max(1, data.mergeLeftTargetCount) : 0;
  const mergeRightTargetCount = kind === "MERGE" ? Math.max(1, data.mergeRightTargetCount) : 0;
  const nodeInternalsSignature = [
    reactFlowNodeInternalsSignature(data.node),
    kind,
    String(isConnectable),
    String(mergeLeftTargetCount),
    String(mergeRightTargetCount),
  ].join("\u0001");
  useStableNodeInternalsUpdate(id, nodeInternalsSignature);

  return (
    <div
      className={[
        "flowchart-react-node",
        `kind-${kind.toLowerCase()}`,
        appSelected ? "is-selected" : "",
        isConnectable ? "is-connectable" : "",
      ].join(" ").trim()}
      style={flowchartNodeShellStyle(kind)}
    >
      {FLOWCHART_VISIBLE_SOURCE_HANDLES.map(({ id: handleId, position }) => (
        <Handle
          key={handleId}
          id={handleId}
          type="source"
          position={position}
          isConnectableStart={isConnectable}
          isConnectableEnd={false}
          style={flowchartVisibleHandleStyle(kind, handleId, position, visibleSourceHandleStyle)}
        />
      ))}
      {kind === "MERGE" ? (
        <>
          {Array.from({ length: mergeLeftTargetCount }, (_, index) => (
            <Handle
              key={flowchartMergeTargetPortId("left", index)}
              id={flowchartMergeTargetPortId("left", index)}
              type="target"
              position={Position.Left}
              isConnectableStart={false}
              isConnectableEnd={isConnectable}
              style={flowchartMergeTargetHandleStyle("left", index, mergeLeftTargetCount, auxiliaryHandleStyle)}
            />
          ))}
          {Array.from({ length: mergeRightTargetCount }, (_, index) => (
            <Handle
              key={flowchartMergeTargetPortId("right", index)}
              id={flowchartMergeTargetPortId("right", index)}
              type="target"
              position={Position.Right}
              isConnectableStart={false}
              isConnectableEnd={isConnectable}
              style={flowchartMergeTargetHandleStyle("right", index, mergeRightTargetCount, auxiliaryHandleStyle)}
            />
          ))}
        </>
      ) : null}
      {FLOWCHART_VISIBLE_TARGET_HANDLES.map(({ id: handleId, position }) => (
        <Handle
          key={handleId}
          id={handleId}
          type="target"
          position={position}
          isConnectableStart={false}
          isConnectableEnd={isConnectable}
          style={flowchartVisibleHandleStyle(kind, handleId, position, visibleTargetHandleStyle)}
        />
      ))}
      <FlowchartNodeCard
        node={data.node}
        selected={appSelected}
        explanationFocused={data.explanationFocused}
        draftChanged={data.draftChanged}
        draftCompareStatus={data.draftCompareStatus}
        onMeasure={data.onMeasure}
      />
    </div>
  );
}

function InvocationExpansionSummaryReactNode({ data, selected }: InvocationExpansionSummaryNodeProps) {
  const metadata = data.node.metadata ?? {};
  const nodeCount = metadata["linkGraph.expansion.summary.nodeCount"] ?? "0";
  const branchCount = metadata["linkGraph.expansion.summary.branchCount"] ?? "0";
  const returnCount = metadata["linkGraph.expansion.summary.returnCount"] ?? "0";
  const childExpansionCount = metadata["linkGraph.expansion.summary.childExpansionCount"] ?? "0";
  const hasBorrowedRoot = metadata["linkGraph.expansion.summary.hasBorrowedRoot"] === "true";
  return (
    <div className={["flowchart-invocation-summary", selected || data.selected ? "is-selected" : ""].join(" ").trim()}>
      <Handle id="target-left" type="target" position={Position.Left} isConnectableStart={false} />
      <Handle id="source-right" type="source" position={Position.Right} isConnectableEnd={false} />
      <div className="flowchart-invocation-summary__eyebrow">调用展开摘要</div>
      <div className="flowchart-invocation-summary__title" title={data.node.title}>{data.node.title}</div>
      <div className="flowchart-invocation-summary__metrics">
        <span>{nodeCount} 节点</span>
        <span>{branchCount} 分支</span>
        <span>{returnCount} 返回</span>
        {childExpansionCount !== "0" ? <span>{childExpansionCount} 子展开</span> : null}
      </div>
      {hasBorrowedRoot ? <div className="flowchart-invocation-summary__shared">共享入口</div> : null}
    </div>
  );
}

/** 注册给 React Flow 的流程图节点类型映射。 */
export const FLOWCHART_NODE_TYPES: NodeTypes = {
  flowchartNode: FlowchartReactNode,
  invocationExpansionSummaryNode: InvocationExpansionSummaryReactNode,
};

function isInvocationExpansionSummaryNode(node: LinkGraphNode): boolean {
  return node.metadata?.["flowchart.synthetic"] === "invocation-expansion-summary";
}

/** 根据节点类型生成节点外壳的内联样式，覆盖宽度、最小高度、圆角、边框、背景与阴影等视觉差异。 */
function flowchartNodeStyle(node: LinkGraphNode) {
  if (isInvocationExpansionSummaryNode(node)) {
    return {
      width: 280,
      minHeight: 132,
      borderRadius: 8,
      border: "1px solid rgba(25, 90, 153, 0.34)",
      background: "rgba(255, 255, 255, 0.94)",
      boxShadow: "0 10px 24px rgba(35, 42, 48, 0.12)",
      padding: 0,
    };
  }
  const kind = flowchartKind(node);
  return {
    width: flowchartNodeCardWidth(node),
    minHeight: kind === "DECISION"
      ? FLOWCHART_DECISION_MIN_HEIGHT
      : kind === "MERGE"
        ? FLOWCHART_MERGE_WIDTH
        : undefined,
    borderRadius: kind === "DECISION" ? 0 : kind === "TERMINAL" ? 999 : kind === "MERGE" ? "50%" : 18,
    border: kind === "DECISION"
      ? "none"
      : kind === "ENTRY"
        ? "1px solid rgba(25, 90, 153, 0.42)"
        : "1px solid rgba(44, 32, 22, 0.18)",
    background: kind === "ENTRY"
      ? "linear-gradient(180deg, rgba(25, 90, 153, 0.18), var(--panel))"
      : kind === "TERMINAL"
        ? "linear-gradient(180deg, rgba(14, 139, 114, 0.16), var(--panel))"
        : kind === "MERGE"
        ? "linear-gradient(180deg, rgba(95, 90, 83, 0.16), var(--panel))"
        : kind === "DECISION"
            ? "transparent"
            : "var(--panel)",
    boxShadow: kind === "DECISION" ? "none" : "0 8px 18px rgba(44, 32, 22, 0.09)",
    padding: 0,
    overflow: kind === "DECISION" ? "visible" : undefined,
  };
}

/** 将边标签规范化为大写无空白的统一形式，便于条件分支匹配（如 EXCEPTION）。 */
function normalizedFlowLabel(edge: LinkGraphEdge): string {
  return edge.label?.trim().toUpperCase() ?? "";
}

/** 返回流程图边上要显示的文字：纯数字标签和流程/调用类边不显示，其他无标签的边用类型名称兜底。 */
function flowchartEdgeLabel(edge: LinkGraphEdge): string | undefined {
  if (edge.label?.trim()) {
    if (/^\d+$/.test(edge.label.trim())) {
      return undefined;
    }
    return edge.label.trim();
  }
  if (edge.type === "CALL" || edge.type === "CONTAINS_FLOW" || edge.type === "CONTROL_FLOW") {
    return undefined;
  }
  return edgeTypeLabel(edge.type);
}

/**
 * 解析一条边在源节点上应使用的句柄 ID：优先复用边自身记录的 sourceHandle，
 * 否则按节点类型决定（决策节点交给专用解析，异常边走右侧，其余走底部）。
 */
function resolveFlowSourceHandleId(
  edge: LinkGraphEdge,
  nodeIndex: Map<string, LinkGraphNode>,
  outgoingEdges?: LinkGraphEdge[],
): string | undefined {
  if (edge.sourceHandle) {
    return edge.sourceHandle;
  }
  if (edge.type !== "CONTROL_FLOW") {
    return undefined;
  }
  const sourceNode = nodeIndex.get(edge.source);
  if (!sourceNode) {
    return undefined;
  }
  switch (flowchartKind(sourceNode)) {
    case "DECISION":
      return resolveDecisionSourcePort(sourceNode, nodeIndex.get(edge.target), {
        edge,
        outgoingEdges,
        nodeIndex,
      });
    case "TERMINAL":
      return undefined;
    default:
      if (hasExceptionControlFlowOutlet(sourceNode, outgoingEdges) && normalizedFlowLabel(edge) === "EXCEPTION") {
        return "source-right";
      }
      return "source-bottom";
  }
}

/**
 * 解析一条边在目标节点上应使用的句柄 ID：优先复用边自身记录的 targetHandle，
 * 决策节点交给专用解析，普通节点用顶部入口，汇聚节点查表或退回到顶部入口。
 */
function resolveFlowTargetHandleId(
  edge: LinkGraphEdge,
  nodeIndex: Map<string, LinkGraphNode>,
  outgoingEdges?: LinkGraphEdge[],
  mergeTargetHandleByEdgeId?: Map<string, string>,
): string | undefined {
  if (edge.targetHandle) {
    return edge.targetHandle;
  }
  if (edge.type !== "CONTROL_FLOW") {
    return undefined;
  }
  const targetNode = nodeIndex.get(edge.target);
  if (!targetNode) {
    return undefined;
  }
  if (flowchartKind(targetNode) === "DECISION") {
    return resolveDecisionTargetPort(nodeIndex.get(edge.source), targetNode, edge);
  }
  if (flowchartKind(targetNode) !== "MERGE") {
    return "target-top";
  }
  return mergeTargetHandleByEdgeId?.get(edge.id)
    ?? (isDecisionFallthroughEdge(edge, outgoingEdges, nodeIndex) ? "target-top" : "target-top");
}

/** 根据边类型给出基础描边样式：流程控制类边为实线主色，其他关系类边为虚线警示色。 */
function flowchartEdgeStyle(edge: LinkGraphEdge) {
  switch (edge.type) {
    case "CONTAINS_FLOW":
    case "CONTROL_FLOW":
      return {
        stroke: "var(--edge-info)",
        strokeWidth: 2.2,
        opacity: 0.96,
      };
    default:
      return {
        stroke: "var(--edge-warning)",
        strokeWidth: 1.8,
        strokeDasharray: "6 4",
        opacity: 0.86,
      };
  }
}

/** 从节点元数据中读取投影来源节点 ID 列表（逗号分隔），用于回溯被融合/投影节点的原始身份。 */
function projectedAliasNodeIds(node: LinkGraphNode): string[] {
  const rawAliasNodeIds = node.metadata?.[FLOWCHART_ALIAS_IDS_KEY];
  if (!rawAliasNodeIds) {
    return [];
  }
  return rawAliasNodeIds
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

/** 判断节点是否匹配某个期望 ID：自身 ID 相等或投影来源列表中包含该 ID 都算命中。 */
function nodeMatchesProjectedId(node: LinkGraphNode, expectedNodeId: string): boolean {
  return node.id === expectedNodeId || projectedAliasNodeIds(node).includes(expectedNodeId);
}

/**
 * 查找节点的草稿比较状态：先看精确 ID 命中，再遍历投影来源 ID 找状态，
 * 用于让融合节点也能反映其原始节点在草稿对比中的变化。
 */
function resolveProjectedDraftCompareStatus(
  node: LinkGraphNode,
  draftCompareNodeStatuses: Record<string, DraftCompareStatus>,
): DraftCompareStatus | undefined {
  const exactStatus = draftCompareNodeStatuses[node.id];
  if (exactStatus) {
    return exactStatus;
  }
  return projectedAliasNodeIds(node)
    .map((aliasNodeId) => draftCompareNodeStatuses[aliasNodeId])
    .find(Boolean);
}

/**
 * 把图谱节点转换为 React Flow 渲染所需的节点数组：构建控制流索引、计算汇聚端口计数，
 * 整合选中/解释聚焦/草稿变更/草稿对比等展示态并写入 data 与 className，供节点组件消费。
 */
export function buildFlowchartNodes({
  nodes,
  edges,
  selectedNodeId,
  explanationFocusNodeId = null,
  draftChangedNodeIds = [],
  draftCompareNodeStatuses = {},
  projectionIndex = null,
  nodeSizeRegistry,
}: BuildFlowchartNodesOptions): FlowchartFlowNode[] {
  const nodeIndex = new Map(nodes.map((node) => [node.id, node]));
  const draftChangedNodeIdSet = new Set(draftChangedNodeIds);
  const outgoingControlFlowBySource = buildOutgoingControlFlowIndex(edges);
  const incomingControlFlowByTarget = buildIncomingControlFlowIndex(edges);
  const mergeTargetPortLayout = buildMergeTargetPortLayout(
    edges,
    nodeIndex,
    outgoingControlFlowBySource,
    incomingControlFlowByTarget,
  );
  return nodes.map((node) => {
    if (isInvocationExpansionSummaryNode(node)) {
      return {
        id: node.id,
        type: "invocationExpansionSummaryNode",
        className: "flowchart-rf-node kind-invocation-expansion-summary",
        selected: selectedNodeId === node.id,
        draggable: canEditNodeLayout(node, "FLOWCHART", projectionIndex),
        position: node.position ?? { x: 80, y: 88 },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        data: {
          node,
          selected: selectedNodeId === node.id,
        },
        style: flowchartNodeStyle(node),
      };
    }
    const kind = flowchartKind(node);
    const mergeTargetPortCounts = mergeTargetPortLayout.countsByNodeId.get(node.id);
    const projectedDraftChanged = Array.from(draftChangedNodeIdSet)
      .some((draftChangedNodeId) => nodeMatchesProjectedId(node, draftChangedNodeId));
    const projectedDraftCompareStatus = resolveProjectedDraftCompareStatus(node, draftCompareNodeStatuses);
    return {
      id: node.id,
      type: "flowchartNode",
      className: resolveGraphNodeHighlightClassName({
        baseClassName: `flowchart-rf-node kind-${kind.toLowerCase()}`,
        nodeId: node.id,
        explanationFocusNodeId,
        draftChangedNodeIdSet: projectedDraftChanged ? new Set([node.id]) : new Set(),
        draftCompareStatus: projectedDraftCompareStatus,
      }),
      selected: selectedNodeId === node.id,
      draggable: canEditNodeLayout(node, "FLOWCHART", projectionIndex),
      position: node.position ?? { x: 80, y: 88 },
      sourcePosition: Position.Bottom,
      targetPosition: Position.Top,
      data: {
        node,
        selected: selectedNodeId === node.id,
        hasExceptionSource: hasExceptionControlFlowOutlet(node, outgoingControlFlowBySource.get(node.id)),
        mergeLeftTargetCount: mergeTargetPortCounts?.leftCount ?? 0,
        mergeRightTargetCount: mergeTargetPortCounts?.rightCount ?? 0,
        explanationFocused: explanationFocusNodeId === node.id,
        draftChanged: projectedDraftChanged,
        draftCompareStatus: projectedDraftCompareStatus,
        onMeasure: nodeSizeRegistry.reporter(node.id),
      },
      style: flowchartNodeStyle(node),
    };
  });
}

/**
 * 把图谱边转换为 React Flow 渲染所需的边数组：解析每条边的源/目标句柄、
 * 标签与样式，叠加草稿对比状态相关的 className/样式/箭头颜色，统一交给 RoutedEdge 渲染。
 */
export function buildFlowchartEdges({
  edges,
  nodeIndex,
  draftCompareEdgeStatuses = {},
}: BuildFlowchartEdgesOptions): Array<Edge<RoutedEdgeData, "routedEdge">> {
  const outgoingControlFlowBySource = buildOutgoingControlFlowIndex(edges);
  const incomingControlFlowByTarget = buildIncomingControlFlowIndex(edges);
  const mergeTargetPortLayout = buildMergeTargetPortLayout(
    edges,
    nodeIndex,
    outgoingControlFlowBySource,
    incomingControlFlowByTarget,
  );
  return edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: flowchartEdgeLabel(edge),
    sourceHandle: resolveFlowSourceHandleId(edge, nodeIndex, outgoingControlFlowBySource.get(edge.source)),
    targetHandle: resolveFlowTargetHandleId(
      edge,
      nodeIndex,
      outgoingControlFlowBySource.get(edge.source),
      mergeTargetPortLayout.targetHandleByEdgeId,
    ),
    type: "routedEdge",
    className: draftCompareEdgeClassName("edge-domain", draftCompareEdgeStatuses[edge.id]),
    data: {
      route: edge.route,
    },
    style: draftCompareEdgeStyle(flowchartEdgeStyle(edge), draftCompareEdgeStatuses[edge.id]),
    markerEnd: {
      type: MarkerType.ArrowClosed,
      width: 20,
      height: 20,
      color: draftCompareMarkerColor(
        edge.type === "CONTROL_FLOW" || edge.type === "CONTAINS_FLOW" ? "#195a99" : "#8f4f23",
        draftCompareEdgeStatuses[edge.id],
      ),
    },
  }));
}

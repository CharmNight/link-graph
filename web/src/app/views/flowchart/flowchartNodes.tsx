import { useLayoutEffect, type CSSProperties } from "react";
import {
  Handle,
  MarkerType,
  Position,
  useUpdateNodeInternals,
  type Edge,
  type Node,
  type NodeProps,
  type NodeTypes,
} from "@xyflow/react";
import {
  FLOWCHART_DECISION_MIN_HEIGHT,
  FLOWCHART_DECISION_WIDTH,
  FLOWCHART_MERGE_WIDTH,
  flowchartNodeCardWidth,
} from "../../graphNodeSizing";
import type { NodeMeasuredSize, NodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import type { DraftCompareStatus, GraphProjectionIndex, LinkGraphEdge, LinkGraphNode } from "../../types";
import { edgeTypeLabel } from "../../labels";
import { FlowchartNodeCard } from "../../components/graph/nodes/FlowchartNodeCard";
import { flowchartKind } from "../../components/graph/nodes/nodePresentation";
import { canEditNodeLayout } from "../../layoutEditability";
import type { RoutedEdgeData } from "../../reactflow/RoutedEdge";
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

interface BuildFlowchartEdgesOptions {
  edges: LinkGraphEdge[];
  nodeIndex: Map<string, LinkGraphNode>;
  draftCompareEdgeStatuses?: Record<string, DraftCompareStatus>;
}

type FlowchartFlowNode = Node<FlowchartNodeData, "flowchartNode">;
type FlowchartFlowNodeProps = NodeProps<FlowchartFlowNode>;

const FLOWCHART_ALIAS_IDS_KEY = "flowchart.projectedFromNodeIds";

type VisibleFlowchartHandleId =
  | "target-top"
  | "target-right"
  | "target-bottom"
  | "target-left"
  | "source-top"
  | "source-right"
  | "source-bottom"
  | "source-left";

const FLOWCHART_HANDLE_STYLE_BASE: CSSProperties = {
  width: 16,
  height: 16,
  opacity: 0,
  background: "rgba(14, 139, 114, 0.6)",
  border: "2px solid rgba(255, 255, 255, 0.9)",
  borderRadius: "50%",
  transition: "opacity 0.12s ease",
};

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

function flowchartAuxiliaryHandleStyle(baseStyle: CSSProperties): CSSProperties {
  return {
    ...baseStyle,
    opacity: 0,
    pointerEvents: "none",
  };
}

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

const FLOWCHART_VISIBLE_TARGET_HANDLES: Array<{
  id: Extract<VisibleFlowchartHandleId, `target-${string}`>;
  position: Position;
}> = [
  { id: "target-top", position: Position.Top },
  { id: "target-right", position: Position.Right },
  { id: "target-bottom", position: Position.Bottom },
  { id: "target-left", position: Position.Left },
];

const FLOWCHART_VISIBLE_SOURCE_HANDLES: Array<{
  id: Extract<VisibleFlowchartHandleId, `source-${string}`>;
  position: Position;
}> = [
  { id: "source-top", position: Position.Top },
  { id: "source-right", position: Position.Right },
  { id: "source-bottom", position: Position.Bottom },
  { id: "source-left", position: Position.Left },
];

function flowchartNodeShellStyle(kind: string): CSSProperties | undefined {
  if (kind !== "DECISION") {
    return undefined;
  }
  return {
    minHeight: FLOWCHART_DECISION_MIN_HEIGHT,
  };
}

function FlowchartReactNode({ id, data, isConnectable, selected }: FlowchartFlowNodeProps) {
  const kind = flowchartKind(data.node);
  const updateNodeInternals = useUpdateNodeInternals();
  const appSelected = data.selected === true || selected;
  const visibleTargetHandleStyle = flowchartHandleStyle(isConnectable, "target");
  const visibleSourceHandleStyle = flowchartHandleStyle(isConnectable, "source");
  const auxiliaryHandleStyle = flowchartAuxiliaryHandleStyle(visibleTargetHandleStyle);
  const mergeLeftTargetCount = kind === "MERGE" ? Math.max(1, data.mergeLeftTargetCount) : 0;
  const mergeRightTargetCount = kind === "MERGE" ? Math.max(1, data.mergeRightTargetCount) : 0;

  useLayoutEffect(() => {
    updateNodeInternals(id);
  }, [appSelected, data.node, id, isConnectable, selected, updateNodeInternals]);

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

export const FLOWCHART_NODE_TYPES: NodeTypes = {
  flowchartNode: FlowchartReactNode,
};

function flowchartNodeStyle(node: LinkGraphNode) {
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
      ? "linear-gradient(180deg, rgba(25, 90, 153, 0.18), rgba(255, 255, 255, 0.98))"
      : kind === "TERMINAL"
        ? "linear-gradient(180deg, rgba(14, 139, 114, 0.16), rgba(255, 255, 255, 0.98))"
        : kind === "MERGE"
        ? "linear-gradient(180deg, rgba(95, 90, 83, 0.16), rgba(255, 255, 255, 0.98))"
        : kind === "DECISION"
            ? "transparent"
            : "#fffdfa",
    boxShadow: kind === "DECISION" ? "none" : "0 8px 18px rgba(44, 32, 22, 0.09)",
    padding: 0,
    overflow: kind === "DECISION" ? "visible" : undefined,
  };
}

function normalizedFlowLabel(edge: LinkGraphEdge): string {
  return edge.label?.trim().toUpperCase() ?? "";
}

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

function flowchartEdgeStyle(edge: LinkGraphEdge) {
  switch (edge.type) {
    case "CONTAINS_FLOW":
    case "CONTROL_FLOW":
      return {
        stroke: "#195a99",
        strokeWidth: 2.2,
        opacity: 0.96,
      };
    default:
      return {
        stroke: "#8f4f23",
        strokeWidth: 1.8,
        strokeDasharray: "6 4",
        opacity: 0.86,
      };
  }
}

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

function nodeMatchesProjectedId(node: LinkGraphNode, expectedNodeId: string): boolean {
  return node.id === expectedNodeId || projectedAliasNodeIds(node).includes(expectedNodeId);
}

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

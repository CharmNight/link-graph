import { useLayoutEffect, useRef, type CSSProperties } from "react";
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
import { edgeTypeLabel, nodeTypeLabel, relationConfidenceLabel } from "../../labels";
import type { NodeMeasuredSize, NodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { nodeCardWidth } from "../../graphNodeSizing";
import { measureNodeContentBox } from "../../components/graph/nodes/measureNodeContentBox";
import { GraphNodeStateBadges } from "../../components/graph/nodes/GraphNodeStateBadges";
import type { DraftCompareStatus, LinkGraphEdge, LinkGraphNode } from "../../types";
import { canEditNodeLayout } from "../../layoutEditability";
import type { RoutedEdgeData } from "../../reactflow/RoutedEdge";
import { resolveGraphNodeHighlightClassName } from "../graphNodeHighlights";
import {
  draftCompareEdgeClassName,
  draftCompareEdgeStyle,
  draftCompareMarkerColor,
} from "../draftComparePresentation";

interface ArchitectureNodeData extends Record<string, unknown> {
  node: LinkGraphNode;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  draftCompareStatus?: DraftCompareStatus;
  onMeasure?: (size: NodeMeasuredSize) => void;
}

interface BuildArchitectureNodesOptions {
  nodes: LinkGraphNode[];
  selectedNodeId: string | null;
  explanationFocusNodeId?: string | null;
  draftChangedNodeIds?: string[];
  draftCompareNodeStatuses?: Record<string, DraftCompareStatus>;
  nodeSizeRegistry: NodeSizeRegistry;
}

interface BuildArchitectureEdgesOptions {
  edges: LinkGraphEdge[];
  draftCompareEdgeStatuses?: Record<string, DraftCompareStatus>;
}

type ArchitectureFlowNode = Node<ArchitectureNodeData, "architectureGraphNode">;
type ArchitectureFlowNodeProps = NodeProps<ArchitectureFlowNode>;

const HANDLE_STYLE: CSSProperties = {
  width: 16,
  height: 16,
  opacity: 0,
  background: "rgba(41, 83, 107, 0.6)",
  border: "2px solid rgba(255, 255, 255, 0.9)",
  borderRadius: "50%",
};

function handleStyle(isConnectable: boolean): CSSProperties {
  return {
    ...HANDLE_STYLE,
    opacity: isConnectable ? 0.26 : 0,
    pointerEvents: isConnectable ? "all" : "none",
  };
}

function ArchitectureNodeCard({
  node,
  selected,
  explanationFocused = false,
  draftChanged = false,
  draftCompareStatus,
  onMeasure,
}: {
  node: LinkGraphNode;
  selected: boolean;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  draftCompareStatus?: DraftCompareStatus;
  onMeasure?: (size: NodeMeasuredSize) => void;
}) {
  const rootRef = useRef<HTMLDivElement | null>(null);
  const countText = node.metadata?.["architecture.package.classCount"]
    ?? node.metadata?.["architecture.classCount"]
    ?? null;

  useLayoutEffect(() => {
    const size = measureNodeContentBox(rootRef.current);
    if (size) {
      onMeasure?.(size);
    }
  }, [node, onMeasure]);

  return (
    <div
      ref={rootRef}
      className={["flow-node-card", "resource-node-card", selected ? "is-selected" : ""].join(" ").trim()}
      data-node-id={node.id}
    >
      <GraphNodeStateBadges selected={selected} explanationFocused={explanationFocused} draftChanged={draftChanged} />
      <div className="flow-node-head">
        <span className="flow-node-doc">{nodeTypeLabel(node.type)}</span>
        <div className="flow-node-tags">
          {countText ? <span className="badge hierarchy-badge">{countText} 类</span> : null}
          {draftCompareStatus ? <span className={`badge draft-compare-${draftCompareStatus.toLowerCase()}`}>{draftCompareStatus}</span> : null}
        </div>
      </div>
      <strong className="flow-node-owner" title={node.title}>{node.title}</strong>
      <span className="flow-node-signature" title={node.signature ?? node.location ?? node.doc ?? node.id}>
        {node.metadata?.["architecture.qualifiedName"] ?? node.signature ?? node.doc ?? node.id}
      </span>
    </div>
  );
}

function ArchitectureReactNode({ id, data, selected, isConnectable }: ArchitectureFlowNodeProps) {
  const updateNodeInternals = useUpdateNodeInternals();
  const style = handleStyle(isConnectable);

  useLayoutEffect(() => {
    updateNodeInternals(id);
  }, [data.node, id, isConnectable, selected, updateNodeInternals]);

  return (
    <div className={["resource-relation-react-node", isConnectable ? "is-connectable" : ""].join(" ").trim()}>
      <Handle id="target-left" type="target" position={Position.Left} style={style} />
      <Handle id="source-right" type="source" position={Position.Right} style={style} />
      <ArchitectureNodeCard
        node={data.node}
        selected={selected}
        explanationFocused={data.explanationFocused}
        draftChanged={data.draftChanged}
        draftCompareStatus={data.draftCompareStatus}
        onMeasure={data.onMeasure}
      />
    </div>
  );
}

export const ARCHITECTURE_GRAPH_NODE_TYPES: NodeTypes = {
  architectureGraphNode: ArchitectureReactNode,
};

function architectureNodeStyle(node: LinkGraphNode) {
  return {
    width: nodeCardWidth(node),
    borderRadius: 8,
    border: "1px solid rgba(41, 83, 107, 0.18)",
    background: node.type === "LAYER" ? "#f7fbf8" : "#fbfcff",
    boxShadow: "0 4px 14px rgba(28, 49, 64, 0.06)",
    padding: 0,
  };
}

function architectureEdgeStyle(edge: LinkGraphEdge) {
  if (
    edge.type === "REFLECTS_TO"
    || edge.metadata?.["jvm.relation.confidence"] === "AMBIGUOUS"
    || edge.metadata?.["jvm.relation.confidence"] === "RUNTIME_REQUIRED"
  ) {
    return { stroke: "#8d6b2f", strokeWidth: 1.5, strokeDasharray: "7 5", opacity: 0.86 };
  }
  return { stroke: "#29536b", strokeWidth: 1.7, opacity: 0.9 };
}

function architectureEdgeLabel(edge: LinkGraphEdge): string {
  const base = edge.label?.trim() || edgeTypeLabel(edge.type);
  const confidence = relationConfidenceLabel(edge.metadata?.["jvm.relation.confidence"]);
  return confidence && confidence !== "静态确认" ? `${base} · ${confidence}` : base;
}

export function buildArchitectureGraphNodes({
  nodes,
  selectedNodeId,
  explanationFocusNodeId = null,
  draftChangedNodeIds = [],
  draftCompareNodeStatuses = {},
  nodeSizeRegistry,
}: BuildArchitectureNodesOptions): ArchitectureFlowNode[] {
  const draftChangedNodeIdSet = new Set(draftChangedNodeIds);
  return nodes.map((node) => ({
    id: node.id,
    type: "architectureGraphNode",
    className: resolveGraphNodeHighlightClassName({
      nodeId: node.id,
      explanationFocusNodeId,
      draftChangedNodeIdSet,
      draftCompareStatus: draftCompareNodeStatuses[node.id],
    }) || undefined,
    selected: selectedNodeId === node.id,
    draggable: canEditNodeLayout(node, "ARCHITECTURE_GRAPH"),
    position: node.position ?? { x: 80, y: 88 },
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    data: {
      node,
      explanationFocused: explanationFocusNodeId === node.id,
      draftChanged: draftChangedNodeIdSet.has(node.id),
      draftCompareStatus: draftCompareNodeStatuses[node.id],
      onMeasure: nodeSizeRegistry.reporter(node.id),
    },
    style: architectureNodeStyle(node),
  }));
}

export function buildArchitectureGraphEdges({
  edges,
  draftCompareEdgeStatuses = {},
}: BuildArchitectureEdgesOptions): Array<Edge<RoutedEdgeData, "routedEdge">> {
  return edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: architectureEdgeLabel(edge),
    type: "routedEdge",
    className: draftCompareEdgeClassName("edge-resource-relation", draftCompareEdgeStatuses[edge.id]),
    data: { route: edge.route },
    style: draftCompareEdgeStyle(architectureEdgeStyle(edge), draftCompareEdgeStatuses[edge.id]),
    markerEnd: {
      type: MarkerType.ArrowClosed,
      width: 20,
      height: 20,
      color: draftCompareMarkerColor("#29536b", draftCompareEdgeStatuses[edge.id]),
    },
  }));
}

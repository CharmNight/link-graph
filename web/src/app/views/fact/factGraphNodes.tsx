import { useLayoutEffect } from "react";
import {
  Handle,
  MarkerType,
  Position,
  useUpdateNodeInternals,
  type CSSProperties,
  type Edge,
  type Node,
  type NodeProps,
} from "@xyflow/react";
import { nodeCardWidth } from "../../graphNodeSizing";
import type { NodeMeasuredSize, NodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import type { LinkGraphEdge, LinkGraphNode } from "../../types";
import { edgeTypeLabel } from "../../labels";
import { FactGraphNodeCard } from "../../components/graph/nodes/FactGraphNodeCard";
import { isDecisionFlowScope, isFlowActionNode } from "../../components/graph/nodes/nodePresentation";
import { canEditNodeLayout } from "../../layoutEditability";
import type { RoutedEdgeData } from "../../reactflow/RoutedEdge";
import { resolveGraphNodeHighlightClassName } from "../graphNodeHighlights";

interface FactGraphNodeData {
  node: LinkGraphNode;
  collapsed: boolean;
  collapsedCount?: number;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  onExpandOverflow: () => void;
  onMeasure?: (size: NodeMeasuredSize) => void;
}

interface BuildFactGraphNodesOptions {
  nodes: LinkGraphNode[];
  selectedNodeId: string | null;
  explanationFocusNodeId?: string | null;
  draftChangedNodeIds?: string[];
  collapsedNodeIds?: Iterable<string>;
  collapsedDescendantCountByNodeId?: Record<string, number>;
  onExpandOverflowNode: (nodeId: string) => void;
  nodeSizeRegistry: NodeSizeRegistry;
}

interface BuildFactGraphEdgesOptions {
  edges: LinkGraphEdge[];
}

const FACT_GRAPH_HANDLE_STYLE_BASE: CSSProperties = {
  width: 16,
  height: 16,
  opacity: 0,
  background: "rgba(14, 139, 114, 0.6)",
  border: "2px solid rgba(255, 255, 255, 0.9)",
  borderRadius: "50%",
  transition: "opacity 0.15s ease",
};

function factGraphHandleStyle(isConnectable: boolean): CSSProperties {
  return {
    ...FACT_GRAPH_HANDLE_STYLE_BASE,
    opacity: isConnectable ? 0.28 : 0,
    pointerEvents: isConnectable ? "all" : "none",
    cursor: isConnectable ? "crosshair" : "default",
  };
}

function FactGraphReactNode({ id, data, selected, isConnectable }: NodeProps<FactGraphNodeData>) {
  const updateNodeInternals = useUpdateNodeInternals();
  const handleStyle = factGraphHandleStyle(isConnectable);

  useLayoutEffect(() => {
    updateNodeInternals(id);
  }, [data.collapsed, data.collapsedCount, data.node, id, isConnectable, selected, updateNodeInternals]);

  return (
    <div className={["fact-graph-react-node", isConnectable ? "is-connectable" : ""].join(" ").trim()}>
      <Handle id="target-left" type="target" position={Position.Left} style={handleStyle} />
      <Handle id="source-right" type="source" position={Position.Right} style={handleStyle} />
      <FactGraphNodeCard
        node={data.node}
        selected={selected}
        collapsed={data.collapsed}
        collapsedCount={data.collapsedCount}
        explanationFocused={data.explanationFocused}
        draftChanged={data.draftChanged}
        onMeasure={data.onMeasure}
        onExpandOverflow={data.onExpandOverflow}
      />
    </div>
  );
}

export const FACT_GRAPH_NODE_TYPES = {
  factGraphNode: FactGraphReactNode,
};

function factGraphNodeStyle(node: LinkGraphNode) {
  const isFlowScope = node.type === "FLOW_SCOPE";
  const isFlowAction = isFlowActionNode(node);
  const isFlowDecision = isDecisionFlowScope(node);
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
      ? "linear-gradient(145deg, rgba(185, 104, 47, 0.12), rgba(255, 255, 255, 0.98))"
      : isFlowScope
        ? "linear-gradient(180deg, rgba(25, 90, 153, 0.08), rgba(255, 255, 255, 0.96))"
        : isFlowAction
          ? "linear-gradient(135deg, rgba(14, 139, 114, 0.12), rgba(255, 255, 255, 0.98))"
          : "#fffdfa",
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

function factGraphEdgeLabel(edge: LinkGraphEdge): string | undefined {
  if (edge.label?.trim()) {
    return edge.label.trim();
  }
  if (edge.type === "CALL" || edge.type === "CONTAINS_FLOW" || edge.type === "CONTROL_FLOW") {
    return undefined;
  }
  return edgeTypeLabel(edge.type);
}

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

function factGraphEdgeStyle(edge: LinkGraphEdge) {
  switch (edge.type) {
    case "CONTAINS_FLOW":
      return {
        stroke: "#195a99",
        strokeWidth: 1.8,
        strokeDasharray: "7 5",
        opacity: 0.84,
      };
    case "CONTROL_FLOW":
      return {
        stroke: "#195a99",
        strokeWidth: 2.1,
        opacity: 0.94,
      };
    case "CALL":
      return {
        stroke: "#8f4f23",
        strokeWidth: 1.9,
        opacity: 0.92,
      };
    default:
      return {
        stroke: "#5f5a53",
        strokeWidth: 1.6,
        opacity: 0.88,
      };
  }
}

export function buildFactGraphNodes({
  nodes,
  selectedNodeId,
  explanationFocusNodeId = null,
  draftChangedNodeIds = [],
  collapsedNodeIds = [],
  collapsedDescendantCountByNodeId = {},
  onExpandOverflowNode,
  nodeSizeRegistry,
}: BuildFactGraphNodesOptions): Array<Node<FactGraphNodeData>> {
  const collapsedNodeIdSet = new Set(collapsedNodeIds);
  const draftChangedNodeIdSet = new Set(draftChangedNodeIds);
  return nodes.map((node) => ({
    id: node.id,
    type: "factGraphNode",
    className: resolveGraphNodeHighlightClassName({
      nodeId: node.id,
      explanationFocusNodeId,
      draftChangedNodeIdSet,
    }) || undefined,
    selected: selectedNodeId === node.id,
    draggable: canEditNodeLayout(node),
    position: node.position ?? { x: 80, y: 88 },
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    data: {
      node,
      collapsed: collapsedNodeIdSet.has(node.id),
      collapsedCount: collapsedDescendantCountByNodeId[node.id],
      explanationFocused: explanationFocusNodeId === node.id,
      draftChanged: draftChangedNodeIdSet.has(node.id),
      onExpandOverflow: () => onExpandOverflowNode(node.id),
      onMeasure: (size) => nodeSizeRegistry.set(node.id, size),
    },
    style: factGraphNodeStyle(node),
  }));
}

export function buildFactGraphEdges({
  edges,
}: BuildFactGraphEdgesOptions): Array<Edge<RoutedEdgeData>> {
  return edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: factGraphEdgeLabel(edge),
    type: "routedEdge",
    className: factGraphEdgeClassName(edge),
    data: {
      route: edge.route,
    },
    style: factGraphEdgeStyle(edge),
    markerEnd: {
      type: MarkerType.ArrowClosed,
      width: 20,
      height: 20,
      color: edge.type === "CALL" ? "#8f4f23" : "#5f5a53",
    },
  }));
}

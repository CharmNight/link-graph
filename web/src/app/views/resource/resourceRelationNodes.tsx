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
import { nodeCardWidth } from "../../graphNodeSizing";
import type { NodeMeasuredSize, NodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import type { DraftCompareStatus, LinkGraphEdge, LinkGraphNode } from "../../types";
import { edgeTypeLabel } from "../../labels";
import { ResourceRelationNodeCard } from "../../components/graph/nodes/ResourceRelationNodeCard";
import { canEditNodeLayout } from "../../layoutEditability";
import type { RoutedEdgeData } from "../../reactflow/RoutedEdge";
import { resolveGraphNodeHighlightClassName } from "../graphNodeHighlights";
import {
  draftCompareEdgeClassName,
  draftCompareEdgeStyle,
  draftCompareMarkerColor,
} from "../draftComparePresentation";

interface ResourceRelationNodeData extends Record<string, unknown> {
  node: LinkGraphNode;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  draftCompareStatus?: DraftCompareStatus;
  onMeasure?: (size: NodeMeasuredSize) => void;
}

interface BuildResourceRelationNodesOptions {
  nodes: LinkGraphNode[];
  selectedNodeId: string | null;
  explanationFocusNodeId?: string | null;
  draftChangedNodeIds?: string[];
  draftCompareNodeStatuses?: Record<string, DraftCompareStatus>;
  nodeSizeRegistry: NodeSizeRegistry;
}

interface BuildResourceRelationEdgesOptions {
  edges: LinkGraphEdge[];
  draftCompareEdgeStatuses?: Record<string, DraftCompareStatus>;
}

type ResourceRelationFlowNode = Node<ResourceRelationNodeData, "resourceRelationNode">;
type ResourceRelationFlowNodeProps = NodeProps<ResourceRelationFlowNode>;

const RESOURCE_HANDLE_STYLE_BASE: CSSProperties = {
  width: 16,
  height: 16,
  opacity: 0,
  background: "rgba(95, 90, 83, 0.52)",
  border: "2px solid rgba(255, 255, 255, 0.9)",
  borderRadius: "50%",
  transition: "opacity 0.12s ease",
};

function resourceHandleStyle(isConnectable: boolean): CSSProperties {
  return {
    ...RESOURCE_HANDLE_STYLE_BASE,
    opacity: isConnectable ? 0.28 : 0,
    pointerEvents: isConnectable ? "all" : "none",
    cursor: isConnectable ? "crosshair" : "default",
  };
}

function ResourceRelationReactNode({ id, data, selected, isConnectable }: ResourceRelationFlowNodeProps) {
  const updateNodeInternals = useUpdateNodeInternals();
  const handleStyle = resourceHandleStyle(isConnectable);

  useLayoutEffect(() => {
    updateNodeInternals(id);
  }, [data.node, id, isConnectable, selected, updateNodeInternals]);

  return (
    <div className={["resource-relation-react-node", isConnectable ? "is-connectable" : ""].join(" ").trim()}>
      <Handle id="target-left" type="target" position={Position.Left} style={handleStyle} />
      <Handle id="source-right" type="source" position={Position.Right} style={handleStyle} />
      <ResourceRelationNodeCard
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

export const RESOURCE_RELATION_NODE_TYPES: NodeTypes = {
  resourceRelationNode: ResourceRelationReactNode,
};

function resourceNodeStyle(node: LinkGraphNode) {
  return {
    width: nodeCardWidth(node),
    borderRadius: 18,
    border: "1px solid rgba(44, 32, 22, 0.18)",
    background: "#fffdf8",
    boxShadow: "0 4px 14px rgba(49, 33, 20, 0.06)",
    padding: 0,
  };
}

function resourceEdgeLabel(edge: LinkGraphEdge): string | undefined {
  if (edge.label?.trim()) {
    return edge.label.trim();
  }
  if (edge.type === "CALL" || edge.type === "CONTAINS_FLOW" || edge.type === "CONTROL_FLOW") {
    return undefined;
  }
  return edgeTypeLabel(edge.type);
}

function resourceEdgeStyle() {
  return {
    stroke: "#5f5a53",
    strokeWidth: 1.6,
    opacity: 0.88,
  };
}

export function buildResourceRelationNodes({
  nodes,
  selectedNodeId,
  explanationFocusNodeId = null,
  draftChangedNodeIds = [],
  draftCompareNodeStatuses = {},
  nodeSizeRegistry,
}: BuildResourceRelationNodesOptions): ResourceRelationFlowNode[] {
  const draftChangedNodeIdSet = new Set(draftChangedNodeIds);
  return nodes.map((node) => ({
    id: node.id,
    type: "resourceRelationNode",
    className: resolveGraphNodeHighlightClassName({
      nodeId: node.id,
      explanationFocusNodeId,
      draftChangedNodeIdSet,
      draftCompareStatus: draftCompareNodeStatuses[node.id],
    }) || undefined,
    selected: selectedNodeId === node.id,
    draggable: canEditNodeLayout(node),
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
    style: resourceNodeStyle(node),
  }));
}

export function buildResourceRelationEdges({
  edges,
  draftCompareEdgeStatuses = {},
}: BuildResourceRelationEdgesOptions): Array<Edge<RoutedEdgeData, "routedEdge">> {
  return edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: resourceEdgeLabel(edge),
    type: "routedEdge",
    className: draftCompareEdgeClassName("edge-resource-relation", draftCompareEdgeStatuses[edge.id]),
    data: {
      route: edge.route,
    },
    style: draftCompareEdgeStyle(resourceEdgeStyle(), draftCompareEdgeStatuses[edge.id]),
    markerEnd: {
      type: MarkerType.ArrowClosed,
      width: 20,
      height: 20,
      color: draftCompareMarkerColor("#5f5a53", draftCompareEdgeStatuses[edge.id]),
    },
  }));
}

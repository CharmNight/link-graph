import { Position } from "@xyflow/react";
import type { LayoutOptions } from "elkjs/lib/elk-api";
import { resolveMeasuredNodeSize, executeElkLayout, type ElkNodePortDefinition } from "../../reactflow/elkGraph";
import { reanchorRouteEnd, reanchorRouteStart } from "../../reactflow/orthogonalRoute";
import type { MeasuredLayoutRequest } from "../../reactflow/useMeasuredLayout";
import type { GraphPosition, LinkGraphEdge, LinkGraphNode } from "../../types";
import {
  buildIncomingControlFlowIndex,
  buildMergeTargetPortLayout,
  buildOutgoingControlFlowIndex,
  flowchartDecisionPortPoint,
  flowchartMergeTargetPortId,
  hasExceptionControlFlowOutlet,
  isDecisionFallthroughEdge,
  type FlowchartMergeTargetPortCounts,
  type FlowchartDecisionPortId,
  resolveDecisionSourcePort,
} from "./decisionPortGeometry";

const FLOWCHART_LAYOUT_OPTIONS: LayoutOptions = {
  "elk.algorithm": "layered",
  "org.eclipse.elk.direction": "DOWN",
  "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
  "org.eclipse.elk.partitioning.activate": "true",
  "org.eclipse.elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
  "org.eclipse.elk.layered.spacing.nodeNodeBetweenLayers": "112",
  "org.eclipse.elk.layered.spacing.edgeNodeBetweenLayers": "48",
  "org.eclipse.elk.spacing.nodeNode": "64",
  "org.eclipse.elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
  "org.eclipse.elk.layered.crossingMinimization.forceNodeModelOrder": "true",
};

function flowchartKind(node?: MeasuredLayoutRequest["nodes"][number]): string {
  return node?.metadata?.["flowchart.kind"] ?? "PROCESS";
}

function normalizedFlowLabel(edge?: MeasuredLayoutRequest["edges"][number]): string {
  return edge?.label?.trim().toUpperCase() ?? "";
}

function flowEdgeRole(edge?: LinkGraphEdge): string {
  return edge?.metadata?.["flow.edgeRole"]?.toUpperCase() ?? "";
}

function flowScopeCategory(node?: LinkGraphNode): string {
  return node?.metadata?.["flow.scopeCategory"] ?? "";
}

function flowPortDefinitions(
  node: MeasuredLayoutRequest["nodes"][number],
  outgoingEdges?: LinkGraphEdge[],
  mergeTargetPortCounts?: FlowchartMergeTargetPortCounts,
): ElkNodePortDefinition[] {
  switch (flowchartKind(node)) {
    case "DECISION":
      return [
        { id: "target-top", side: "NORTH" },
        { id: "source-left", side: "WEST" },
        { id: "source-right", side: "EAST" },
        { id: "source-bottom", side: "SOUTH" },
      ];
    case "MERGE":
      return [
        { id: "target-top", side: "NORTH" },
        ...Array.from({ length: mergeTargetPortCounts?.leftCount ?? 1 }, (_, index) => ({
          id: flowchartMergeTargetPortId("left", index),
          side: "WEST" as const,
        })),
        ...Array.from({ length: mergeTargetPortCounts?.rightCount ?? 1 }, (_, index) => ({
          id: flowchartMergeTargetPortId("right", index),
          side: "EAST" as const,
        })),
        { id: "source-bottom", side: "SOUTH" },
      ];
    case "TERMINAL":
      return [{ id: "target-top", side: "NORTH" }];
    default:
      return [
        { id: "target-top", side: "NORTH" },
        { id: "source-right", side: "EAST" },
        { id: "source-bottom", side: "SOUTH" },
      ];
  }
}

function decisionPortPosition(portId: FlowchartDecisionPortId): Position {
  switch (portId) {
    case "source-left":
      return Position.Left;
    case "source-right":
      return Position.Right;
    case "source-bottom":
      return Position.Bottom;
    case "target-top":
    default:
      return Position.Top;
  }
}

function projectDecisionAttachmentPoint(
  edge: LinkGraphEdge,
  node: LinkGraphNode | undefined,
  oppositeNode: LinkGraphNode | undefined,
  size: { width: number; height: number } | undefined,
  outgoingControlFlowBySource: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
): LinkGraphEdge {
  if (!node || flowchartKind(node) !== "DECISION" || !edge.route || !size || edge.route.sections.length === 0) {
    return edge;
  }
  if (edge.source === node.id) {
    const outgoingEdges = outgoingControlFlowBySource.get(edge.source);
    const sourcePort = resolveSourcePort(
      edge,
      "DECISION",
      node,
      oppositeNode,
      outgoingEdges,
      nodeIndex,
    ) as FlowchartDecisionPortId | undefined;
    if (sourcePort) {
      return {
        ...edge,
        route: reanchorRouteStart(
          edge.route,
          flowchartDecisionPortPoint(sourcePort, node, size),
          decisionPortPosition(sourcePort),
        ),
      };
    }
  }
  if (edge.target === node.id) {
    const targetPort = resolveTargetPort(edge, "DECISION", oppositeNode, node);
    if (targetPort === "target-top") {
      return {
        ...edge,
        route: reanchorRouteEnd(
          edge.route,
          flowchartDecisionPortPoint("target-top", node, size),
          Position.Top,
        ),
      };
    }
  }
  return edge;
}

function resolveSourcePort(
  edge: MeasuredLayoutRequest["edges"][number],
  sourceNodeKind: string,
  sourceNode?: LinkGraphNode,
  targetNode?: LinkGraphNode,
  outgoingEdges?: LinkGraphEdge[],
  nodeIndex?: Map<string, LinkGraphNode>,
): string | undefined {
  if (edge.sourceHandle) {
    return edge.sourceHandle;
  }
  if (edge.type !== "CONTROL_FLOW") {
    return undefined;
  }
  if (sourceNodeKind !== "DECISION") {
    if (sourceNodeKind === "TERMINAL") {
      return undefined;
    }
    const sourceNodeForPort = sourceNode ?? nodeIndex?.get(edge.source);
    if (normalizedFlowLabel(edge) === "EXCEPTION" && hasExceptionControlFlowOutlet(sourceNodeForPort, outgoingEdges)) {
      return "source-right";
    }
    return "source-bottom";
  }
  return resolveDecisionSourcePort(sourceNode, targetNode, {
    edge,
    outgoingEdges,
    nodeIndex,
  });
}

function resolveTargetPort(
  edge: MeasuredLayoutRequest["edges"][number],
  targetNodeKind: string,
  sourceNode?: LinkGraphNode,
  targetNode?: LinkGraphNode,
  outgoingEdges?: LinkGraphEdge[],
  nodeIndex?: Map<string, LinkGraphNode>,
  mergeTargetPort?: string,
): string | undefined {
  if (edge.targetHandle) {
    return edge.targetHandle;
  }
  if (edge.type !== "CONTROL_FLOW") {
    return undefined;
  }
  if (targetNodeKind !== "MERGE") {
    return "target-top";
  }
  if (mergeTargetPort) {
    return mergeTargetPort;
  }
  if (isDecisionFallthroughEdge(edge, outgoingEdges, nodeIndex)) {
    return "target-top";
  }
  if (sourceNode?.position && targetNode?.position) {
    if (sourceNode.position.x < targetNode.position.x - 1) {
      return flowchartMergeTargetPortId("left", 0);
    }
    if (sourceNode.position.x > targetNode.position.x + 1) {
      return flowchartMergeTargetPortId("right", 0);
    }
  }
  return "target-top";
}

function buildLayoutNodes(
  nodes: MeasuredLayoutRequest["nodes"],
  anchorNodeId: string | null | undefined,
  sizeSnapshot: MeasuredLayoutRequest["sizeSnapshot"],
  nodeSizeIndex: Map<string, { width: number; height: number }>,
  outgoingControlFlowBySource: Map<string, LinkGraphEdge[]>,
  mergeTargetPortCountsByNode: Map<string, FlowchartMergeTargetPortCounts>,
  partitionByNodeId: Map<string, number>,
) {
  return nodes.map((node) => {
    const size = resolveMeasuredNodeSize(node, sizeSnapshot, "FLOWCHART");
    nodeSizeIndex.set(node.id, size);
    const hasExceptionSource = hasExceptionControlFlowOutlet(node, outgoingControlFlowBySource.get(node.id));
    const mergeTargetPortCounts = mergeTargetPortCountsByNode.get(node.id);
    return {
      node,
      ...size,
      ports: flowPortDefinitions(node, outgoingControlFlowBySource.get(node.id), mergeTargetPortCounts),
      metadata: {
        ...(hasExceptionSource ? { "flowchart.hasExceptionSource": "true" } : {}),
        ...(mergeTargetPortCounts
          ? {
              "flowchart.mergeLeftTargetCount": String(mergeTargetPortCounts.leftCount),
              "flowchart.mergeRightTargetCount": String(mergeTargetPortCounts.rightCount),
            }
          : {}),
      },
      layoutOptions: {
        "org.eclipse.elk.portConstraints": "FIXED_SIDE",
        "org.eclipse.elk.partitioning.partition": String(partitionByNodeId.get(node.id) ?? 0),
        ...(node.id === anchorNodeId
          ? { "org.eclipse.elk.layered.layering.layerConstraint": "FIRST" }
          : {}),
      },
    };
  });
}

function buildPartitionIndex(
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
): Map<string, number> {
  const nodeIndex = new Map(nodes.map((node) => [node.id, node]));
  const partitions = new Map<string, number>();
  nodes.forEach((node) => partitions.set(node.id, 0));
  edges.forEach((edge) => {
    if (edge.type !== "CONTROL_FLOW") {
      return;
    }
    const sourceNode = nodeIndex.get(edge.source);
    if (flowScopeCategory(sourceNode) === "LOOP_PRE_TEST" && flowEdgeRole(edge) === "LOOP_EXIT") {
      partitions.set(edge.target, 1);
    }
  });
  return partitions;
}

function flowEdgeLayoutOptions(
  edge: LinkGraphEdge,
  nodeIndex: Map<string, LinkGraphNode>,
): LayoutOptions | undefined {
  const edgeRole = flowEdgeRole(edge);
  if (edgeRole === "LOOP_BACK") {
    return {
      "org.eclipse.elk.layered.priority.direction": "0",
    };
  }
  const targetScopeCategory = flowScopeCategory(nodeIndex.get(edge.target));
  if (targetScopeCategory === "LOOP_POST_TEST") {
    return {
      "org.eclipse.elk.layered.priority.direction": "12",
    };
  }
  if (edgeRole === "LOOP_BODY" || edgeRole === "LOOP_EXIT") {
    return {
      "org.eclipse.elk.layered.priority.direction": "10",
    };
  }
  return undefined;
}

export async function layoutFlowchartView({
  nodes,
  edges,
  anchorNodeId,
  sizeSnapshot,
}: MeasuredLayoutRequest) {
  const nodeIndex = new Map(nodes.map((node) => [node.id, node]));
  const outgoingControlFlowBySource = buildOutgoingControlFlowIndex(edges);
  const incomingControlFlowByTarget = buildIncomingControlFlowIndex(edges);
  const nodeSizeIndex = new Map<string, { width: number; height: number }>();
  const partitionByNodeId = buildPartitionIndex(nodes, edges);
  const initialLayoutNodes = buildLayoutNodes(
    nodes,
    anchorNodeId,
    sizeSnapshot,
    nodeSizeIndex,
    outgoingControlFlowBySource,
    new Map(),
    partitionByNodeId,
  );
  const initialLayout = await executeElkLayout({
    mode: "FLOWCHART",
    layoutOptions: FLOWCHART_LAYOUT_OPTIONS,
    nodes: initialLayoutNodes,
    edges: edges.map((edge) => ({ edge })),
  });
  const initialNodeIndex = new Map(initialLayout.nodes.map((node) => [node.id, node]));
  const mergeTargetPortLayout = buildMergeTargetPortLayout(
    edges,
    initialNodeIndex,
    outgoingControlFlowBySource,
    incomingControlFlowByTarget,
  );
  const layoutNodes = buildLayoutNodes(
    nodes,
    anchorNodeId,
    sizeSnapshot,
    nodeSizeIndex,
    outgoingControlFlowBySource,
    mergeTargetPortLayout.countsByNodeId,
    partitionByNodeId,
  );
  const laidOut = await executeElkLayout({
    mode: "FLOWCHART",
    layoutOptions: FLOWCHART_LAYOUT_OPTIONS,
    nodes: layoutNodes,
    edges: edges.map((edge) => {
      const sourceNode = initialNodeIndex.get(edge.source);
      const targetNode = initialNodeIndex.get(edge.target);
      const outgoingEdges = outgoingControlFlowBySource.get(edge.source);
      return {
        edge,
        sourcePort: resolveSourcePort(
          edge,
          flowchartKind(nodeIndex.get(edge.source)),
          sourceNode,
          targetNode,
          outgoingEdges,
          nodeIndex,
        ),
        targetPort: resolveTargetPort(
          edge,
          flowchartKind(nodeIndex.get(edge.target)),
          sourceNode,
          targetNode,
          outgoingEdges,
          nodeIndex,
          mergeTargetPortLayout.targetHandleByEdgeId.get(edge.id),
        ),
        layoutOptions: flowEdgeLayoutOptions(edge, nodeIndex),
      };
    }),
  });
  const laidOutNodeIndex = new Map(laidOut.nodes.map((node) => [node.id, node]));

  return {
    nodes: laidOut.nodes,
    edges: laidOut.edges.map((edge) => {
      const sourceNode = laidOutNodeIndex.get(edge.source);
      const targetNode = laidOutNodeIndex.get(edge.target);
      const withProjectedSource = projectDecisionAttachmentPoint(
        edge,
        sourceNode,
        targetNode,
        nodeSizeIndex.get(edge.source),
        outgoingControlFlowBySource,
        nodeIndex,
      );
      return projectDecisionAttachmentPoint(
        withProjectedSource,
        targetNode,
        sourceNode,
        nodeSizeIndex.get(edge.target),
        outgoingControlFlowBySource,
        nodeIndex,
      );
    }),
  };
}

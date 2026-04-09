import type { CSSProperties } from "@xyflow/react";
import type { GraphPosition, LinkGraphEdge, LinkGraphNode } from "../../types";

export type FlowchartDecisionPortId =
  | "target-top"
  | "source-left"
  | "source-right"
  | "source-bottom";

export type FlowchartDecisionSourcePortId =
  | "source-left"
  | "source-right"
  | "source-bottom";

export type FlowchartMergeTargetPortId =
  | "target-top"
  | `target-left-${number}`
  | `target-right-${number}`;

export interface FlowchartMergeTargetPortCounts {
  leftCount: number;
  rightCount: number;
}

export interface FlowchartMergeTargetPortLayout {
  targetHandleByEdgeId: Map<string, FlowchartMergeTargetPortId>;
  countsByNodeId: Map<string, FlowchartMergeTargetPortCounts>;
}

interface DecisionSourcePortResolutionOptions {
  edge?: LinkGraphEdge;
  outgoingEdges?: LinkGraphEdge[];
  nodeIndex?: Map<string, LinkGraphNode>;
}

interface DecisionPortGeometry {
  xRatio: number;
  yRatio: number;
  style?: CSSProperties;
}

function normalizedFlowLabel(edge: LinkGraphEdge | undefined): string {
  return edge?.label?.trim().toUpperCase() ?? "";
}

function semanticDecisionOutgoingEdges(outgoingEdges: LinkGraphEdge[] | undefined): LinkGraphEdge[] {
  if (!outgoingEdges || outgoingEdges.length === 0) {
    return [];
  }
  const authoredEdges = outgoingEdges.filter((edge) => edge.sourceTag !== "DRAFT_MANUAL");
  return authoredEdges.length > 0 ? authoredEdges : outgoingEdges;
}

const DECISION_PORT_GEOMETRY: Record<FlowchartDecisionPortId, DecisionPortGeometry> = {
  "target-top": {
    xRatio: 0.5,
    yRatio: 0,
    style: {
      transform: "translate(-50%, 0)",
    },
  },
  "source-left": {
    xRatio: 0,
    yRatio: 0.5,
    style: {
      top: "50%",
      transform: "translate(0, -50%)",
    },
  },
  "source-right": {
    xRatio: 1,
    yRatio: 0.5,
    style: {
      top: "50%",
      transform: "translate(0, -50%)",
    },
  },
  "source-bottom": {
    xRatio: 0.5,
    yRatio: 1,
    style: {
      transform: "translate(-50%, 0)",
    },
  },
};

function flowchartKind(node?: LinkGraphNode): string {
  return node?.metadata?.["flowchart.kind"] ?? "PROCESS";
}

export function buildOutgoingControlFlowIndex(edges: LinkGraphEdge[]): Map<string, LinkGraphEdge[]> {
  const index = new Map<string, LinkGraphEdge[]>();
  edges.forEach((edge) => {
    if (edge.type !== "CONTROL_FLOW") {
      return;
    }
    const current = index.get(edge.source) ?? [];
    current.push(edge);
    index.set(edge.source, current);
  });
  return index;
}

export function buildIncomingControlFlowIndex(edges: LinkGraphEdge[]): Map<string, LinkGraphEdge[]> {
  const index = new Map<string, LinkGraphEdge[]>();
  edges.forEach((edge) => {
    if (edge.type !== "CONTROL_FLOW") {
      return;
    }
    const current = index.get(edge.target) ?? [];
    current.push(edge);
    index.set(edge.target, current);
  });
  return index;
}

export function hasExceptionControlFlowOutlet(
  node: LinkGraphNode | undefined,
  outgoingEdges: LinkGraphEdge[] | undefined,
): boolean {
  if (!node || flowchartKind(node) === "DECISION" || flowchartKind(node) === "TERMINAL") {
    return false;
  }
  return (outgoingEdges ?? []).some(
    (edge) => edge.type === "CONTROL_FLOW" && normalizedFlowLabel(edge) === "EXCEPTION",
  );
}

export function isDecisionFallthroughEdge(
  edge: LinkGraphEdge | undefined,
  outgoingEdges: LinkGraphEdge[] | undefined,
  nodeIndex: Map<string, LinkGraphNode> | undefined,
): boolean {
  const semanticOutgoingEdges = semanticDecisionOutgoingEdges(outgoingEdges);
  if (!edge || semanticOutgoingEdges.length !== 2 || !nodeIndex) {
    return false;
  }
  const currentBranch = semanticOutgoingEdges.find((candidate) => candidate.id === edge.id);
  if (!currentBranch) {
    return false;
  }
  const targetKinds = semanticOutgoingEdges.map((candidate) => flowchartKind(nodeIndex.get(candidate.target)));
  const currentTargetKind = flowchartKind(nodeIndex.get(currentBranch.target));
  const terminalTargetCount = targetKinds.filter((kind) => kind === "TERMINAL").length;
  if (terminalTargetCount === 1) {
    return currentTargetKind !== "TERMINAL";
  }
  const mergeTargetCount = targetKinds.filter((kind) => kind === "MERGE").length;
  if (mergeTargetCount === 1) {
    return currentTargetKind === "MERGE";
  }
  return false;
}

type FlowchartMergeTargetSide = "top" | "left" | "right";

function fallbackMergeTargetSide(edge: LinkGraphEdge | undefined): FlowchartMergeTargetSide {
  switch (normalizedFlowLabel(edge)) {
    case "TRUE":
      return "left";
    case "FALSE":
    case "DEFAULT":
    case "EXCEPTION":
      return "right";
    default:
      return "top";
  }
}

function resolveMergeTargetSide(
  edge: LinkGraphEdge,
  sourceNode: LinkGraphNode | undefined,
  targetNode: LinkGraphNode | undefined,
  outgoingEdges: LinkGraphEdge[] | undefined,
  nodeIndex: Map<string, LinkGraphNode>,
): FlowchartMergeTargetSide {
  if (isDecisionFallthroughEdge(edge, outgoingEdges, nodeIndex)) {
    return "top";
  }
  if (sourceNode?.position && targetNode?.position) {
    if (sourceNode.position.x < targetNode.position.x - 1) {
      return "left";
    }
    if (sourceNode.position.x > targetNode.position.x + 1) {
      return "right";
    }
  }
  return fallbackMergeTargetSide(edge);
}

function compareMergeSideEdges(
  left: LinkGraphEdge,
  right: LinkGraphEdge,
  nodeIndex: Map<string, LinkGraphNode>,
): number {
  const leftSource = nodeIndex.get(left.source);
  const rightSource = nodeIndex.get(right.source);
  const yDiff = (leftSource?.position?.y ?? 0) - (rightSource?.position?.y ?? 0);
  if (Math.abs(yDiff) > 1) {
    return yDiff;
  }
  const xDiff = (leftSource?.position?.x ?? 0) - (rightSource?.position?.x ?? 0);
  if (Math.abs(xDiff) > 1) {
    return xDiff;
  }
  return left.id.localeCompare(right.id);
}

export function flowchartMergeTargetPortId(
  side: "left" | "right",
  index: number,
): `target-left-${number}` | `target-right-${number}` {
  return `target-${side}-${index}`;
}

export function readFlowchartMergeTargetPortCount(
  node: LinkGraphNode | undefined,
  side: "left" | "right",
): number {
  const key = side === "left"
    ? "flowchart.mergeLeftTargetCount"
    : "flowchart.mergeRightTargetCount";
  const parsed = Number(node?.metadata?.[key]);
  if (!Number.isFinite(parsed)) {
    return 1;
  }
  return Math.max(0, Math.round(parsed));
}

function mergeTargetOffsetPercent(index: number, count: number): number {
  if (count <= 1) {
    return 50;
  }
  return 28 + (44 * index) / (count - 1);
}

export function flowchartMergeTargetHandleStyle(
  side: "left" | "right",
  index: number,
  count: number,
  baseStyle: CSSProperties,
): CSSProperties {
  return {
    ...baseStyle,
    top: `${mergeTargetOffsetPercent(index, count)}%`,
    transform: "translate(0, -50%)",
  };
}

export function buildMergeTargetPortLayout(
  edges: LinkGraphEdge[],
  nodeIndex: Map<string, LinkGraphNode>,
  outgoingControlFlowBySource: Map<string, LinkGraphEdge[]>,
  incomingControlFlowByTarget: Map<string, LinkGraphEdge[]>,
): FlowchartMergeTargetPortLayout {
  const targetHandleByEdgeId = new Map<string, FlowchartMergeTargetPortId>();
  const countsByNodeId = new Map<string, FlowchartMergeTargetPortCounts>();

  incomingControlFlowByTarget.forEach((incomingEdges, targetNodeId) => {
    const targetNode = nodeIndex.get(targetNodeId);
    if (!targetNode || flowchartKind(targetNode) !== "MERGE") {
      return;
    }

    const leftEdges: LinkGraphEdge[] = [];
    const rightEdges: LinkGraphEdge[] = [];
    incomingEdges.forEach((edge) => {
      const side = resolveMergeTargetSide(
        edge,
        nodeIndex.get(edge.source),
        targetNode,
        outgoingControlFlowBySource.get(edge.source),
        nodeIndex,
      );
      if (side === "top") {
        targetHandleByEdgeId.set(edge.id, "target-top");
        return;
      }
      if (side === "left") {
        leftEdges.push(edge);
        return;
      }
      rightEdges.push(edge);
    });

    leftEdges.sort((left, right) => compareMergeSideEdges(left, right, nodeIndex));
    rightEdges.sort((left, right) => compareMergeSideEdges(left, right, nodeIndex));
    leftEdges.forEach((edge, index) => {
      targetHandleByEdgeId.set(edge.id, flowchartMergeTargetPortId("left", index));
    });
    rightEdges.forEach((edge, index) => {
      targetHandleByEdgeId.set(edge.id, flowchartMergeTargetPortId("right", index));
    });
    countsByNodeId.set(targetNodeId, {
      leftCount: leftEdges.length,
      rightCount: rightEdges.length,
    });
  });

  return {
    targetHandleByEdgeId,
    countsByNodeId,
  };
}

export function flowchartDecisionPortPoint(
  portId: FlowchartDecisionPortId,
  node: LinkGraphNode,
  size: { width: number; height: number },
): GraphPosition {
  const position = node.position ?? { x: 0, y: 0 };
  const geometry = DECISION_PORT_GEOMETRY[portId];
  return {
    x: position.x + size.width * geometry.xRatio,
    y: position.y + size.height * geometry.yRatio,
  };
}

export function flowchartDecisionPortHandleStyle(
  portId: FlowchartDecisionPortId,
  baseStyle: CSSProperties,
): CSSProperties {
  const geometry = DECISION_PORT_GEOMETRY[portId];
  return geometry.style ? { ...baseStyle, ...geometry.style } : baseStyle;
}

export function resolveDecisionSourcePort(
  sourceNode: LinkGraphNode | undefined,
  targetNode: LinkGraphNode | undefined,
  options: DecisionSourcePortResolutionOptions = {},
): FlowchartDecisionSourcePortId {
  if (isDecisionFallthroughEdge(options.edge, options.outgoingEdges, options.nodeIndex)) {
    return "source-bottom";
  }
  if (!sourceNode?.position || !targetNode?.position) {
    return "source-bottom";
  }
  if (targetNode.position.x + 1 < sourceNode.position.x) {
    return "source-left";
  }
  if (targetNode.position.x > sourceNode.position.x + 1) {
    return "source-right";
  }
  return "source-bottom";
}

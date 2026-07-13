import type { CSSProperties } from "react";
import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import {
  FLOWCHART_DECISION_MIN_HEIGHT,
  FLOWCHART_MERGE_WIDTH,
  flowchartNodeCardWidth,
} from "../../graphNodeSizing";
import { projectedAliasNodeIds } from "../../appGraphSupport";
import type { LinkGraphNode } from "../../types";
import {
  flowchartKind,
  methodDisplayFromSignature,
} from "../../components/graph/nodes/nodePresentation";
import type {
  FlowchartInvocationExpansionEntry,
  FlowchartInvocationExpansionRegistry,
} from "./flowchartLayoutModel";

const DEFAULT_FLOWCHART_NODE_HEIGHT = 156;
const FRAME_PADDING_X = 34;
const FRAME_PADDING_Y = 28;

interface FlowchartInvocationExpansionFramesProps {
  nodes: LinkGraphNode[];
  registry: FlowchartInvocationExpansionRegistry;
  nodeSizes?: ReadonlyMap<string, NodeMeasuredSize>;
}

interface ExpansionFrame {
  expansionId: string;
  sourceTitle: string;
  targetTitle: string;
  style: CSSProperties;
}

function nodeHeight(node: LinkGraphNode): number {
  const kind = flowchartKind(node);
  if (kind === "DECISION") {
    return FLOWCHART_DECISION_MIN_HEIGHT;
  }
  if (kind === "MERGE") {
    return FLOWCHART_MERGE_WIDTH;
  }
  return DEFAULT_FLOWCHART_NODE_HEIGHT;
}

function nodeBounds(
  node: LinkGraphNode,
  nodeSizes?: ReadonlyMap<string, NodeMeasuredSize>,
) {
  const position = node.position ?? { x: 0, y: 0 };
  const measuredSize = nodeSizes?.get(node.id);
  const width = Math.max(measuredSize?.width ?? 0, flowchartNodeCardWidth(node));
  const height = Math.max(measuredSize?.height ?? 0, nodeHeight(node));
  return {
    left: position.x,
    right: position.x + width,
    top: position.y,
    bottom: position.y + height,
  };
}

function expansionTitle(
  entry: FlowchartInvocationExpansionEntry,
  nodeIndex: Map<string, LinkGraphNode>,
): string {
  return methodDisplayFromSignature(entry.targetSignature ?? undefined)
    ?? methodDisplayFromSignature(entry.rootNodeId ? nodeIndex.get(entry.rootNodeId)?.signature : undefined)
    ?? (entry.rootNodeId ? nodeIndex.get(entry.rootNodeId)?.title : null)
    ?? entry.targetSignature
    ?? entry.rootNodeId
    ?? entry.expansionId;
}

function sourceInvocationTitle(
  entry: FlowchartInvocationExpansionEntry,
  nodeIndex: Map<string, LinkGraphNode>,
): string {
  const sourceNodeId = entry.sourceInvocationNodeId;
  const sourceNode = sourceNodeId
    ? nodeIndex.get(sourceNodeId)
      ?? Array.from(nodeIndex.values()).find((node) => projectedAliasNodeIds(node).includes(sourceNodeId))
    : null;
  return sourceNode?.title?.trim()
    || entry.sourceInvocationNodeId
    || "来源调用";
}

function frameNodes(
  entry: FlowchartInvocationExpansionEntry,
  nodeIndex: Map<string, LinkGraphNode>,
): LinkGraphNode[] {
  return Array.from(new Set([...entry.ownedNodeIds, ...entry.borrowedNodeIds]))
    .map((nodeId) => nodeIndex.get(nodeId))
    .filter((node): node is LinkGraphNode => Boolean(node?.position));
}

function buildExpansionFrame(
  entry: FlowchartInvocationExpansionEntry,
  nodeIndex: Map<string, LinkGraphNode>,
  nodeSizes?: ReadonlyMap<string, NodeMeasuredSize>,
): ExpansionFrame | null {
  if (entry.state !== "expanded") {
    return null;
  }
  const nodes = frameNodes(entry, nodeIndex);
  if (nodes.length === 0) {
    return null;
  }
  const bounds = nodes.map((node) => nodeBounds(node, nodeSizes));
  const left = Math.min(...bounds.map((bound) => bound.left));
  const right = Math.max(...bounds.map((bound) => bound.right));
  const top = Math.min(...bounds.map((bound) => bound.top));
  const bottom = Math.max(...bounds.map((bound) => bound.bottom));
  return {
    expansionId: entry.expansionId,
    sourceTitle: sourceInvocationTitle(entry, nodeIndex),
    targetTitle: expansionTitle(entry, nodeIndex),
    style: {
      transform: `translate(${Math.round(left - FRAME_PADDING_X)}px, ${Math.round(top - FRAME_PADDING_Y)}px)`,
      width: Math.round(right - left + FRAME_PADDING_X * 2),
      height: Math.round(bottom - top + FRAME_PADDING_Y * 2),
    },
  };
}

export function FlowchartInvocationExpansionFrames({
  nodes,
  registry,
  nodeSizes,
}: FlowchartInvocationExpansionFramesProps) {
  if (registry.entries.length === 0) {
    return null;
  }
  const nodeIndex = new Map(nodes.map((node) => [node.id, node]));
  const frames = registry.entries
    .map((entry) => buildExpansionFrame(entry, nodeIndex, nodeSizes))
    .filter((frame): frame is ExpansionFrame => frame !== null);
  if (frames.length === 0) {
    return null;
  }
  return (
    <>
      {frames.map((frame) => (
        <section
          key={frame.expansionId}
          className="flowchart-invocation-expansion-frame"
          data-testid={`flowchart-invocation-expansion-frame-${frame.expansionId}`}
          aria-label={`调用展开 ${frame.sourceTitle} 到 ${frame.targetTitle}`}
          style={frame.style}
        >
          <div
            className="flowchart-invocation-expansion-frame__header"
            data-testid={`flowchart-invocation-expansion-label-${frame.expansionId}`}
            data-compact="true"
          >
            <span>调用展开</span>
            <strong
              className="flowchart-invocation-expansion-frame__source"
              title={frame.sourceTitle}
            >
              {frame.sourceTitle}
            </strong>
            <i aria-hidden="true">→</i>
            <strong
              className="flowchart-invocation-expansion-frame__target"
              title={frame.targetTitle}
            >
              {frame.targetTitle}
            </strong>
          </div>
        </section>
      ))}
    </>
  );
}

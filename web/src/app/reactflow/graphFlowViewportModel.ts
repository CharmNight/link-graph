import type {
  AnalysisDisplayMode,
  GraphPosition,
  LinkGraphEdge,
  LinkGraphNode,
} from "../types";

const DRAG_POSITION_EPSILON = 0.5;

export function hashText(value: string): string {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = ((hash * 31) + value.charCodeAt(index)) >>> 0;
  }
  return hash.toString(36);
}

export function locateAnchorButtonLabel(viewportMode: AnalysisDisplayMode): string {
  switch (viewportMode) {
    case "CLASS_DIAGRAM":
      return "定位当前类";
    case "ARCHITECTURE_GRAPH":
      return "定位当前架构";
    case "RESOURCE_RELATION_VIEW":
      return "定位当前资源";
    case "FLOWCHART":
      return "定位当前步骤";
    default:
      return "定位当前方法";
  }
}

export function summarizeGraphShapeSignature(signature: string) {
  return {
    length: signature.length,
    hash: hashText(signature),
  };
}

export function fallbackPosition(index: number): GraphPosition {
  return {
    x: 80 + (index % 3) * 400,
    y: 88 + Math.floor(index / 3) * 220,
  };
}

export function resolveNodePosition(node: LinkGraphNode, index: number): GraphPosition {
  return node.position ?? fallbackPosition(index);
}

export function positionsMatch(
  left: GraphPosition | undefined,
  right: GraphPosition | undefined,
): boolean {
  if (!left || !right) {
    return false;
  }
  return Math.abs(left.x - right.x) <= DRAG_POSITION_EPSILON
    && Math.abs(left.y - right.y) <= DRAG_POSITION_EPSILON;
}

export function resolveAnchorNode(
  nodes: LinkGraphNode[],
  anchorNodeId: string | null | undefined,
  selectedNodeId: string | null,
): LinkGraphNode | null {
  return nodes.find((node) => node.id === anchorNodeId)
    ?? nodes.find((node) => node.id === selectedNodeId)
    ?? nodes.find((node) => node.type === "METHOD")
    ?? nodes[0]
    ?? null;
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function rounded(value: number): number {
  return Math.round(value * 1000) / 1000;
}

export interface GraphContentBounds {
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
  width: number;
  height: number;
}

export function graphContentBounds(
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
  nodeViewportSize: (node: LinkGraphNode) => { width: number; height: number },
): GraphContentBounds | null {
  if (nodes.length === 0) {
    return null;
  }
  let minX = Number.POSITIVE_INFINITY;
  let minY = Number.POSITIVE_INFINITY;
  let maxX = Number.NEGATIVE_INFINITY;
  let maxY = Number.NEGATIVE_INFINITY;
  const includePoint = (point: GraphPosition) => {
    minX = Math.min(minX, point.x);
    minY = Math.min(minY, point.y);
    maxX = Math.max(maxX, point.x);
    maxY = Math.max(maxY, point.y);
  };
  nodes.forEach((node) => {
    const position = node.position ?? { x: 0, y: 0 };
    const size = nodeViewportSize(node);
    includePoint(position);
    includePoint({
      x: position.x + size.width,
      y: position.y + size.height,
    });
  });
  edges.forEach((edge) => {
    edge.route?.sections.forEach((section) => {
      includePoint(section.startPoint);
      section.bendPoints?.forEach(includePoint);
      includePoint(section.endPoint);
    });
  });
  if (!Number.isFinite(minX) || !Number.isFinite(minY) || !Number.isFinite(maxX) || !Number.isFinite(maxY)) {
    return null;
  }
  return {
    minX,
    minY,
    maxX,
    maxY,
    width: Math.max(1, maxX - minX),
    height: Math.max(1, maxY - minY),
  };
}

export function graphViewportContentSignature(
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
  nodeViewportSize: (node: LinkGraphNode) => { width: number; height: number },
): string {
  return [
    ...nodes.map((node) => {
      const position = node.position ?? { x: 0, y: 0 };
      const size = nodeViewportSize(node);
      return `${node.id}:${rounded(position.x)},${rounded(position.y)},${rounded(size.width)}x${rounded(size.height)}`;
    }),
    ...edges.map((edge) => {
      const routePointSignature = edge.route?.sections
        .flatMap((section) => [section.startPoint, ...(section.bendPoints ?? []), section.endPoint])
        .map((point) => `${rounded(point.x)},${rounded(point.y)}`)
        .join(";") ?? "";
      return `${edge.id}:${edge.source}->${edge.target}:${routePointSignature}`;
    }),
  ].join("|");
}

export function reactFlowPaddingPixels(size: number, padding: number): number {
  if (padding <= 0) {
    return 0;
  }
  return Math.floor((size - size / (1 + padding)) / 2);
}

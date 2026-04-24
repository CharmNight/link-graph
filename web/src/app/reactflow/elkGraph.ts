import ELK from "elkjs/lib/elk.bundled.js";
import { resolveFlowchartKind } from "../flowchartKind";
import type { ElkEdgeSection, ElkExtendedEdge, ElkNode, LayoutOptions } from "elkjs/lib/elk-api";
import {
  FLOWCHART_DECISION_MIN_HEIGHT,
  FLOWCHART_DECISION_WIDTH,
  FLOWCHART_MERGE_WIDTH,
  FLOWCHART_TERMINAL_WIDTH,
  flowchartNodeCardWidth,
  nodeCardWidth,
} from "../graphNodeSizing";
import type { NodeMeasuredSize } from "../graph/nodeSizeRegistry";
import type {
  AnalysisDisplayMode,
  GraphPosition,
  LinkGraphEdge,
  LinkGraphEdgeRoute,
  LinkGraphEdgeRouteSection,
  LinkGraphNode,
} from "../types";

const elk = new ELK();
const DEFAULT_NODE_HEIGHT = 156;
const DEFAULT_ORIGIN: GraphPosition = { x: 120, y: 96 };

export interface ElkNodePortDefinition {
  id: string;
  side: "NORTH" | "SOUTH" | "WEST" | "EAST";
}

export interface ElkNodeDefinition {
  node: LinkGraphNode;
  width: number;
  height: number;
  ports?: ElkNodePortDefinition[];
  layoutOptions?: LayoutOptions;
  metadata?: Record<string, string>;
}

export interface ElkEdgeDefinition {
  edge: LinkGraphEdge;
  sourcePort?: string;
  targetPort?: string;
  layoutOptions?: LayoutOptions;
}

export interface ExecuteElkLayoutOptions {
  mode: AnalysisDisplayMode;
  layoutOptions: LayoutOptions;
  nodes: ElkNodeDefinition[];
  edges: ElkEdgeDefinition[];
  origin?: GraphPosition;
}

export interface ExecutedElkLayoutResult {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
}

function portId(nodeId: string, portName: string): string {
  return `${nodeId}/${portName}`;
}

function applyLayoutPosition(
  node: LinkGraphNode,
  position: GraphPosition,
  mode: AnalysisDisplayMode,
  metadata?: Record<string, string>,
): LinkGraphNode {
  return {
    ...node,
    position,
    metadata: {
      ...(node.metadata ?? {}),
      ...(metadata ?? {}),
      "ui.x": String(position.x),
      "ui.y": String(position.y),
      "layout.mode": mode,
    },
  };
}

function roundPosition(value: number): number {
  return Math.round(value);
}

function offsetPoint(
  point: { x: number; y: number },
  bounds: { minX: number; minY: number },
  origin: GraphPosition,
): GraphPosition {
  return {
    x: origin.x + roundPosition(point.x - bounds.minX),
    y: origin.y + roundPosition(point.y - bounds.minY),
  };
}

function childBounds(children: ElkNode[]) {
  if (children.length === 0) {
    return { minX: 0, minY: 0 };
  }
  let minX = Number.POSITIVE_INFINITY;
  let minY = Number.POSITIVE_INFINITY;
  children.forEach((child) => {
    minX = Math.min(minX, child.x ?? 0);
    minY = Math.min(minY, child.y ?? 0);
  });
  return {
    minX: Number.isFinite(minX) ? minX : 0,
    minY: Number.isFinite(minY) ? minY : 0,
  };
}

function routeSection(
  section: ElkEdgeSection,
  bounds: { minX: number; minY: number },
  origin: GraphPosition,
): LinkGraphEdgeRouteSection | null {
  if (!section.startPoint || !section.endPoint) {
    return null;
  }
  return {
    startPoint: offsetPoint(section.startPoint, bounds, origin),
    bendPoints: section.bendPoints?.map((point) => offsetPoint(point, bounds, origin)),
    endPoint: offsetPoint(section.endPoint, bounds, origin),
  };
}

function edgeRoute(
  edge: ElkExtendedEdge | undefined,
  bounds: { minX: number; minY: number },
  origin: GraphPosition,
): LinkGraphEdgeRoute | undefined {
  const sections = edge?.sections
    ?.map((section) => routeSection(section, bounds, origin))
    .filter((section): section is LinkGraphEdgeRouteSection => section !== null);
  return sections && sections.length > 0
    ? { sections }
    : undefined;
}

export async function executeElkLayout({
  mode,
  layoutOptions,
  nodes,
  edges,
  origin = DEFAULT_ORIGIN,
}: ExecuteElkLayoutOptions): Promise<ExecutedElkLayoutResult> {
  const graph: ElkNode = {
    id: "root",
    layoutOptions,
    children: nodes.map((definition) => ({
      id: definition.node.id,
      width: definition.width,
      height: definition.height,
      layoutOptions: definition.layoutOptions,
      ports: definition.ports?.map((port) => ({
        id: portId(definition.node.id, port.id),
        layoutOptions: {
          "org.eclipse.elk.port.side": port.side,
        },
      })),
    })),
    edges: edges.map((definition) => ({
      id: definition.edge.id,
      sources: [definition.sourcePort ? portId(definition.edge.source, definition.sourcePort) : definition.edge.source],
      targets: [definition.targetPort ? portId(definition.edge.target, definition.targetPort) : definition.edge.target],
      layoutOptions: definition.layoutOptions,
    })),
  };

  const laidOutGraph = await elk.layout(graph);
  const children = laidOutGraph.children ?? [];
  const childIndex = new Map(children.map((child) => [child.id, child]));
  const edgeIndex = new Map(((laidOutGraph.edges as ElkExtendedEdge[] | undefined) ?? []).map((edge) => [edge.id, edge]));
  const bounds = childBounds(children);

  return {
    nodes: nodes.map((definition, index) => {
      const laidOutChild = childIndex.get(definition.node.id);
      const fallbackX = origin.x + index * 40;
      const fallbackY = origin.y + index * 24;
      const position = laidOutChild
        ? {
            x: origin.x + roundPosition((laidOutChild.x ?? 0) - bounds.minX),
            y: origin.y + roundPosition((laidOutChild.y ?? 0) - bounds.minY),
          }
        : {
            x: fallbackX,
            y: fallbackY,
          };
      return applyLayoutPosition(definition.node, position, mode, definition.metadata);
    }),
    edges: edges.map((definition) => ({
      ...definition.edge,
      route: edgeRoute(edgeIndex.get(definition.edge.id), bounds, origin),
    })),
  };
}

export function resolveMeasuredNodeSize(
  node: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  mode: AnalysisDisplayMode,
): NodeMeasuredSize {
  if (mode === "FLOWCHART") {
    const kind = resolveFlowchartKind(node);
    const minimumSize = {
      width: flowchartNodeCardWidth(node),
      height: kind === "DECISION"
        ? FLOWCHART_DECISION_MIN_HEIGHT
        : kind === "MERGE"
          ? FLOWCHART_MERGE_WIDTH
          : kind === "TERMINAL"
            ? FLOWCHART_TERMINAL_WIDTH
            : 0,
    };
    const measured = sizeSnapshot.get(node.id);
    if (measured) {
      return {
        width: Math.max(measured.width, minimumSize.width),
        height: Math.max(measured.height, minimumSize.height),
      };
    }
    if (kind === "DECISION") {
      return { width: minimumSize.width, height: FLOWCHART_DECISION_MIN_HEIGHT };
    }
    if (kind === "MERGE") {
      return { width: minimumSize.width, height: FLOWCHART_MERGE_WIDTH };
    }
    if (kind === "TERMINAL") {
      return { width: minimumSize.width, height: FLOWCHART_TERMINAL_WIDTH };
    }
    return {
      width: minimumSize.width,
      height: DEFAULT_NODE_HEIGHT,
    };
  }
  const measured = sizeSnapshot.get(node.id);
  if (measured) {
    return measured;
  }
  return {
    width: nodeCardWidth(node),
    height: DEFAULT_NODE_HEIGHT,
  };
}

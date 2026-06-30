// ELK 图布局引擎的封装：把图谱节点/边转换为 ELK 输入格式，运行布局，再转回项目内部格式。
// 主要职责：
// - 把 LinkGraphNode/Edge 转换为 ELK 节点/边（含 ports、layoutOptions）；
// - 调用 ELK 进行布局（异步，惰性加载 ELK 引擎）；
// - 把 ELK 输出的位置/路由转回项目格式；
// - 处理流程图节点的最小尺寸约束（DECISION/MERGE/TERMINAL）。
import { resolveFlowchartKind } from "../flowchartKind";
import type { ElkEdgeSection, ElkExtendedEdge, ElkNode, LayoutOptions } from "elkjs/lib/elk-api";
import { createElkLayoutEngine } from "./elkLayoutEngine";
import {
  FLOWCHART_DECISION_MIN_HEIGHT,
  FLOWCHART_MERGE_WIDTH,
  FLOWCHART_TERMINAL_WIDTH,
  flowchartNodeCardWidth,
  nodeCardWidth,
} from "../graphNodeSizing";
import { measureDuration, measureStart, traceLinkGraph } from "../debug";
import type { NodeMeasuredSize } from "../graph/nodeSizeRegistry";
import type {
  AnalysisDisplayMode,
  GraphPosition,
  LinkGraphEdge,
  LinkGraphEdgeRoute,
  LinkGraphEdgeRouteSection,
  LinkGraphNode,
} from "../types";

// ELK 引擎的惰性单例；首次调用 layout 时才创建
let elkEnginePromise: ReturnType<typeof createElkLayoutEngine> | null = null;
/** 默认节点高度（无测量值时使用）。 */
const DEFAULT_NODE_HEIGHT = 156;
/** 默认原点坐标（节点从该点开始排布）。 */
const DEFAULT_ORIGIN: GraphPosition = { x: 120, y: 96 };

/** 单个 ELK 节点端口定义。 */
export interface ElkNodePortDefinition {
  /** 端口 ID。 */
  id: string;
  /** 端口所在边（北/南/东/西）。 */
  side: "NORTH" | "SOUTH" | "WEST" | "EAST";
}

/** ELK 输入：单个节点的定义。 */
export interface ElkNodeDefinition {
  /** 项目节点对象。 */
  node: LinkGraphNode;
  /** 节点宽度。 */
  width: number;
  /** 节点高度。 */
  height: number;
  /** 端口列表；可空。 */
  ports?: ElkNodePortDefinition[];
  /** 节点级布局选项；可空。 */
  layoutOptions?: LayoutOptions;
  /** 额外元数据；可空。 */
  metadata?: Record<string, string>;
}

/** ELK 输入：单条边的定义。 */
export interface ElkEdgeDefinition {
  /** 项目边对象。 */
  edge: LinkGraphEdge;
  /** 起点端口名；可空。 */
  sourcePort?: string;
  /** 终点端口名；可空。 */
  targetPort?: string;
  /** 边级布局选项；可空。 */
  layoutOptions?: LayoutOptions;
}

/** ELK 布局执行的完整输入。 */
export interface ExecuteElkLayoutOptions {
  /** 当前展示模式。 */
  mode: AnalysisDisplayMode;
  /** 顶层布局选项。 */
  layoutOptions: LayoutOptions;
  /** 节点定义列表。 */
  nodes: ElkNodeDefinition[];
  /** 边定义列表。 */
  edges: ElkEdgeDefinition[];
  /** 原点坐标；默认 DEFAULT_ORIGIN。 */
  origin?: GraphPosition;
}

/** ELK 布局执行结果：带新位置与路由的节点/边列表。 */
export interface ExecutedElkLayoutResult {
  /** 带新位置的节点列表。 */
  nodes: LinkGraphNode[];
  /** 带新路由的边列表。 */
  edges: LinkGraphEdge[];
}

/** 构造端口 ID：用 "节点ID/端口名" 格式，便于 ELK 跨节点引用。 */
function portId(nodeId: string, portName: string): string {
  return `${nodeId}/${portName}`;
}

/**
 * 把布局位置应用到节点上。
 *
 * 同时写入 position 字段和 metadata 中的 ui.x/ui.y/layout.mode，
 * 让序列化、布局状态等都可读。
 */
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

/** 数值取整（用于避免浮点噪声）。 */
function roundPosition(value: number): number {
  return Math.round(value);
}

/**
 * 把 ELK 内部坐标转换为带原点偏移的项目坐标。
 * 减去 bounds.minX/Y 让坐标系归零，再加上 origin.x/y 做整体平移。
 */
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

/**
 * 计算 ELK 子节点的边界（左上角坐标）。
 * 用于把 ELK 内部坐标系平移到项目坐标系。
 */
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

/**
 * 把单个 ELK 边段转换为项目段结构。
 * 缺少起止点时返回 null（视为无效段）。
 */
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

/**
 * 把 ELK 边转换为项目路由。
 * 过滤掉无效段；无有效段时返回 undefined（表示该边无路由）。
 */
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

/** 统计所有节点上声明的端口总数。 */
function countPorts(nodes: ElkNodeDefinition[]): number {
  return nodes.reduce((total, definition) => total + (definition.ports?.length ?? 0), 0);
}

/**
 * 汇总路由复杂度：段数 + 转折点数。
 * 用于埋点，便于诊断"布局结果过于复杂"等问题。
 */
function summarizeRouteComplexity(edges: LinkGraphEdge[]) {
  return edges.reduce(
    (summary, edge) => {
      const sections = edge.route?.sections ?? [];
      summary.edgeSectionCount += sections.length;
      sections.forEach((section) => {
        summary.bendPointCount += section.bendPoints?.length ?? 0;
      });
      return summary;
    },
    {
      edgeSectionCount: 0,
      bendPointCount: 0,
    },
  );
}

/**
 * 取 ELK 布局引擎（惰性单例）。
 * 首次调用时通过 createElkLayoutEngine 创建；后续直接复用。
 */
function getElkLayoutEngine(): ReturnType<typeof createElkLayoutEngine> {
  elkEnginePromise ??= createElkLayoutEngine();
  return elkEnginePromise;
}

/**
 * 执行一次 ELK 布局。
 *
 * 流程：
 * 1) 构造 ELK 输入（root 节点 + children + edges，含 ports 与 layoutOptions）；
 * 2) 调用 ELK 异步布局；
 * 3) 后处理：把 ELK 输出的位置/路由转回项目格式；
 * 4) 埋点：记录各阶段耗时与路由复杂度。
 *
 * 缺失 ELK 输出的节点会使用基于 index 的兜底位置。
 *
 * @return 带新位置与路由的节点/边列表
 */
export async function executeElkLayout({
  mode,
  layoutOptions,
  nodes,
  edges,
  origin = DEFAULT_ORIGIN,
}: ExecuteElkLayoutOptions): Promise<ExecutedElkLayoutResult> {
  const startedAt = measureStart();
  // 构造 ELK 输入
  const graph: ElkNode = {
    id: "root",
    layoutOptions,
    children: nodes.map((definition) => ({
      id: definition.node.id,
      width: definition.width,
      height: definition.height,
      layoutOptions: definition.layoutOptions,
      ports: definition.ports?.map((port) => ({
        // 端口 ID 用 "节点ID/端口名" 形式，便于 ELK 跨节点引用
        id: portId(definition.node.id, port.id),
        layoutOptions: {
          "org.eclipse.elk.port.side": port.side,
        },
      })),
    })),
    edges: edges.map((definition) => ({
      id: definition.edge.id,
      // 有端口时通过端口 ID 引用，否则直接通过节点 ID
      sources: [definition.sourcePort ? portId(definition.edge.source, definition.sourcePort) : definition.edge.source],
      targets: [definition.targetPort ? portId(definition.edge.target, definition.targetPort) : definition.edge.target],
      layoutOptions: definition.layoutOptions,
    })),
  };
  const buildGraphDurationMs = measureDuration(startedAt);

  // ELK 布局
  const elkStartedAt = measureStart();
  const elk = await getElkLayoutEngine();
  const laidOutGraph = await elk.layout(graph);
  const elkDurationMs = measureDuration(elkStartedAt);

  // 后处理
  const postProcessStartedAt = measureStart();
  const children = laidOutGraph.children ?? [];
  // 索引 child 与 edge，便于按 ID 查询
  const childIndex = new Map(children.map((child) => [child.id, child]));
  const edgeIndex = new Map(((laidOutGraph.edges as ElkExtendedEdge[] | undefined) ?? []).map((edge) => [edge.id, edge]));
  const bounds = childBounds(children);

  const result = {
    nodes: nodes.map((definition, index) => {
      const laidOutChild = childIndex.get(definition.node.id);
      // 兜底位置：按 index 网格排布，避免 ELK 缺失导致节点堆叠
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
  const routeComplexity = summarizeRouteComplexity(result.edges);
  const postProcessDurationMs = measureDuration(postProcessStartedAt);
  traceLinkGraph("elkLayout.complete", {
    mode,
    nodeCount: nodes.length,
    edgeCount: edges.length,
    portCount: countPorts(nodes),
    childCount: children.length,
    routedEdgeCount: result.edges.filter((edge) => edge.route).length,
    ...routeComplexity,
    buildGraphDurationMs,
    elkDurationMs,
    postProcessDurationMs,
    totalDurationMs: measureDuration(startedAt),
  });
  return result;
}

/**
 * 解析节点的最终尺寸。
 *
 * 流程图模式下按节点种类（DECISION/MERGE/TERMINAL）应用最小尺寸约束；
 * 其他模式直接使用测量尺寸或默认尺寸。
 *
 * 测量尺寸存在时与最小尺寸取最大值，保证不会小于最小要求。
 */
export function resolveMeasuredNodeSize(
  node: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  mode: AnalysisDisplayMode,
): NodeMeasuredSize {
  if (mode === "FLOWCHART") {
    const kind = resolveFlowchartKind(node);
    // 不同种类的最小尺寸约束
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
      // 测量尺寸与最小尺寸取最大值
      return {
        width: Math.max(measured.width, minimumSize.width),
        height: Math.max(measured.height, minimumSize.height),
      };
    }
    // 无测量尺寸：按种类回退
    if (kind === "DECISION") {
      return { width: minimumSize.width, height: FLOWCHART_DECISION_MIN_HEIGHT };
    }
    if (kind === "MERGE") {
      return { width: minimumSize.width, height: FLOWCHART_MERGE_WIDTH };
    }
    if (kind === "TERMINAL") {
      return { width: minimumSize.width, height: FLOWCHART_TERMINAL_WIDTH };
    }
    // 普通流程节点：用默认高度
    return {
      width: minimumSize.width,
      height: DEFAULT_NODE_HEIGHT,
    };
  }
  // 非流程图模式：优先使用测量尺寸
  const measured = sizeSnapshot.get(node.id);
  if (measured) {
    return measured;
  }
  // 无测量尺寸：用默认尺寸（宽度按节点类型推断）
  return {
    width: nodeCardWidth(node),
    height: DEFAULT_NODE_HEIGHT,
  };
}

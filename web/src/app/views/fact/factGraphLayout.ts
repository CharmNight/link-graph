import type { LayoutOptions } from "elkjs/lib/elk-api";
import { measureDuration, measureStart, traceLinkGraph } from "../../debug";
import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import {
  executeElkLayout,
  resolveMeasuredNodeSize,
  type ElkEdgeDefinition,
  type ElkNodeDefinition,
} from "../../reactflow/elkGraph";
import type { LayoutSizeSignatureResolver, MeasuredLayoutRequest } from "../../reactflow/useMeasuredLayout";

/**
 * 事实图布局时传给 ELK 的全局配置：
 * 采用分层布局算法（layered）、自左向右流动，
 * 启用分区让上游/当前/下游三类节点各自成块，
 * 并使用正交折线走线、Brandes-Koepf 节点排布策略，
 * 同时设定层间与节点间的留白以平衡密度与可读性。
 */
export const FACT_GRAPH_LAYOUT_OPTIONS: LayoutOptions = {
  "elk.algorithm": "layered",
  "org.eclipse.elk.direction": "RIGHT",
  "org.eclipse.elk.partitioning.activate": "true",
  "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
  "org.eclipse.elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
  "org.eclipse.elk.layered.spacing.nodeNodeBetweenLayers": "176",
  "org.eclipse.elk.layered.spacing.edgeNodeBetweenLayers": "92",
  "org.eclipse.elk.spacing.nodeNode": "88",
};

// 节点相对锚点的流向：上游调用链、当前方法相关、下游被调用链。
type FactDirection = "UPSTREAM" | "CURRENT" | "DOWNSTREAM";

// 这些节点类型无论实际拓扑如何，都视为"当前方法流程"的一部分（如流程作用域、动作、合并、终止）。
const CURRENT_FACT_NODE_TYPES = new Set(["FLOW_SCOPE", "FLOW_ACTION", "MERGE", "TERMINAL"]);
// 在节点尚未完成测量时用作兜底的空尺寸快照，避免布局阶段拿到 undefined。
const EMPTY_SIZE_SNAPSHOT = new Map<string, NodeMeasuredSize>();
// 把三类方向映射到 ELK 分区编号，用于把同类节点约束到同一纵向分块。
const PARTITION_INDEX: Record<FactDirection, number> = {
  UPSTREAM: 0,
  CURRENT: 1,
  DOWNSTREAM: 2,
};

/** 一次 ELK 布局参数实验的入参：固定图结构，仅改变布局选项以观察效果。 */
interface FactElkOptionExperimentRequest {
  nodes: ElkNodeDefinition[];
  edges: ElkEdgeDefinition[];
  baseOptions: LayoutOptions;
}

/** 构造一个三类方向计数都归零的统计对象，供布局前统计节点分布使用。 */
function emptyDirectionCounts(): Record<FactDirection, number> {
  return {
    UPSTREAM: 0,
    CURRENT: 0,
    DOWNSTREAM: 0,
  };
}

/** 由边集合构造"源节点 -> 目标节点列表"的邻接表，用于下游 BFS 遍历。 */
function buildOutgoing(edges: MeasuredLayoutRequest["edges"]) {
  const outgoing = new Map<string, string[]>();
  edges.forEach((edge) => {
    const nextTargets = outgoing.get(edge.source) ?? [];
    nextTargets.push(edge.target);
    outgoing.set(edge.source, nextTargets);
  });
  return outgoing;
}

/** 由边集合构造"目标节点 -> 源节点列表"的反向邻接表，用于上游 BFS 遍历。 */
function buildIncoming(edges: MeasuredLayoutRequest["edges"]) {
  const incoming = new Map<string, string[]>();
  edges.forEach((edge) => {
    const nextSources = incoming.get(edge.target) ?? [];
    nextSources.push(edge.source);
    incoming.set(edge.target, nextSources);
  });
  return incoming;
}

/**
 * 从起始节点出发做广度优先遍历，返回每个可达节点到起点的跳数。
 * 跳数即节点在调用链中的层级深度，用于决定它属于第几层上游/下游。
 */
function bfs(startId: string, adjacency: Map<string, string[]>): Map<string, number> {
  const distances = new Map<string, number>([[startId, 0]]);
  const queue = [startId];
  while (queue.length > 0) {
    const current = queue.shift();
    if (!current) {
      continue;
    }
    const baseDistance = distances.get(current) ?? 0;
    (adjacency.get(current) ?? []).forEach((nextId) => {
      if (distances.has(nextId)) {
        return;
      }
      distances.set(nextId, baseDistance + 1);
      queue.push(nextId);
    });
  }
  return distances;
}

/**
 * 解析本次布局的锚点节点：
 * 优先使用外部传入的 anchorNodeId（如果它确实在当前节点集合中），
 * 否则退而取第一个 METHOD 节点，再不行就取首个节点，
 * 找不到时返回 null 让上层短路。
 */
function resolveAnchorNodeId(
  nodes: MeasuredLayoutRequest["nodes"],
  anchorNodeId?: string | null,
): string | null {
  if (anchorNodeId && nodes.some((node) => node.id === anchorNodeId)) {
    return anchorNodeId;
  }
  return nodes.find((node) => node.type === "METHOD")?.id ?? nodes[0]?.id ?? null;
}

/** 根据方向与深度生成给 UI 显示的层级标签，例如"当前锚点"、"上游 2 层"。 */
function factDirectionLabel(direction: FactDirection, depth: number): string {
  if (direction === "CURRENT") {
    return depth <= 0 ? "当前锚点" : "当前方法流程";
  }
  return `${direction === "UPSTREAM" ? "上游" : "下游"} ${Math.max(depth, 1)} 层`;
}

/** 把方向映射为泳道标识，供画布上的泳道（lane）叠层按方向分组渲染。 */
function factPresentationLaneId(direction: FactDirection): string {
  if (direction === "UPSTREAM") {
    return "upstream";
  }
  if (direction === "DOWNSTREAM") {
    return "downstream";
  }
  return "current";
}

/** 把方向映射为节点展示角色（ANCHOR/UPSTREAM/DOWNSTREAM），驱动卡片样式差异化。 */
function factPresentationRole(direction: FactDirection): string {
  return direction === "CURRENT" ? "ANCHOR" : direction;
}

/**
 * 判定单个节点相对锚点的方向与层级深度：
 * - 锚点本身是 CURRENT 深度 0；
 * - 流程类节点只要在下游可达集合里就归为 CURRENT，深度按下游跳数；
 * - 只在上游可达的归 UPSTREAM，只在下游可达的归 DOWNSTREAM；
 * - 都不可达时兜底为 CURRENT 深度 1，避免节点丢失归属。
 */
function resolveFactDirection(
  node: MeasuredLayoutRequest["nodes"][number],
  anchorId: string,
  upstreamDistances: Map<string, number>,
  downstreamDistances: Map<string, number>,
): { direction: FactDirection; depth: number } {
  if (node.id === anchorId) {
    return { direction: "CURRENT", depth: 0 };
  }
  if (CURRENT_FACT_NODE_TYPES.has(node.type) && downstreamDistances.has(node.id)) {
    return { direction: "CURRENT", depth: downstreamDistances.get(node.id) ?? 1 };
  }
  if (upstreamDistances.has(node.id) && !downstreamDistances.has(node.id)) {
    return { direction: "UPSTREAM", depth: upstreamDistances.get(node.id) ?? 1 };
  }
  if (downstreamDistances.has(node.id)) {
    return { direction: "DOWNSTREAM", depth: downstreamDistances.get(node.id) ?? 1 };
  }
  if (upstreamDistances.has(node.id)) {
    return { direction: "UPSTREAM", depth: upstreamDistances.get(node.id) ?? 1 };
  }
  return { direction: "CURRENT", depth: 1 };
}

/**
 * 取一个"保守"的节点尺寸用于布局：
 * 用无快照时的回退尺寸作为下限，再与已测量尺寸取较大值，
 * 防止测量缺失时节点被排得过紧导致重叠。
 */
function resolveConservativeFactLayoutSize(
  node: MeasuredLayoutRequest["nodes"][number],
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): NodeMeasuredSize {
  const fallback = resolveMeasuredNodeSize(node, EMPTY_SIZE_SNAPSHOT, "FACT_GRAPH");
  const measured = sizeSnapshot.get(node.id);
  return {
    width: Math.max(measured?.width ?? fallback.width, fallback.width),
    height: Math.max(measured?.height ?? fallback.height, fallback.height),
  };
}

/**
 * 生成布局缓存键：把每个节点的"保守尺寸"拼接成字符串。
 * 只要任一节点的尺寸发生变化，签名就不同，从而触发重新布局。
 */
export const factGraphLayoutSizeSignature: LayoutSizeSignatureResolver = (nodes, sizeSnapshot) =>
  nodes
    .map((node) => {
      const size = resolveConservativeFactLayoutSize(node, sizeSnapshot);
      return `${node.id}:${size.width}x${size.height}`;
    })
    .join("::");

/** 复制一份布局选项并删除指定键，用于在实验变体里临时关闭某些策略。 */
function omitLayoutOptions(
  options: LayoutOptions,
  keys: string[],
): LayoutOptions {
  const nextOptions = { ...options };
  keys.forEach((key) => {
    delete nextOptions[key];
  });
  return nextOptions;
}

/**
 * 基于基准布局选项派生一组实验变体：
 * 例如改用 POLYLINE 走线、关闭强制模型顺序、关闭分区、改用简单节点排布等，
 * 用于在调试模式下对照不同 ELK 参数对布局质量的影响。
 */
function factElkExperimentVariants(baseOptions: LayoutOptions): Array<{ variant: string; layoutOptions: LayoutOptions }> {
  return [
    {
      variant: "polyline-routing",
      layoutOptions: {
        ...baseOptions,
        "org.eclipse.elk.edgeRouting": "POLYLINE",
      },
    },
    {
      variant: "no-forced-model-order",
      layoutOptions: omitLayoutOptions(baseOptions, [
        "org.eclipse.elk.layered.crossingMinimization.forceNodeModelOrder",
      ]),
    },
    {
      variant: "no-model-order",
      layoutOptions: omitLayoutOptions(baseOptions, [
        "org.eclipse.elk.layered.considerModelOrder.strategy",
        "org.eclipse.elk.layered.crossingMinimization.forceNodeModelOrder",
      ]),
    },
    {
      variant: "no-partitioning",
      layoutOptions: {
        ...baseOptions,
        "org.eclipse.elk.partitioning.activate": "false",
      },
    },
    {
      variant: "simple-node-placement",
      layoutOptions: {
        ...baseOptions,
        "org.eclipse.elk.layered.nodePlacement.strategy": "SIMPLE",
      },
    },
  ];
}

/**
 * 依次跑完所有实验变体，并把每种变体的耗时、成功率、走线条数写入 trace，
 * 便于在排查布局质量问题时回溯比较不同 ELK 配置的差异。失败变体也会被记录。
 */
export async function runFactElkOptionExperiments({
  nodes,
  edges,
  baseOptions,
}: FactElkOptionExperimentRequest): Promise<void> {
  for (const experiment of factElkExperimentVariants(baseOptions)) {
    const startedAt = measureStart();
    try {
      const result = await executeElkLayout({
        mode: "FACT_GRAPH",
        layoutOptions: experiment.layoutOptions,
        nodes,
        edges,
      });
      traceLinkGraph("factGraphLayout.elkOptionExperiment", {
        variant: experiment.variant,
        nodeCount: nodes.length,
        edgeCount: edges.length,
        routedEdgeCount: result.edges.filter((edge) => edge.route).length,
        durationMs: measureDuration(startedAt),
      });
    } catch (error) {
      traceLinkGraph("factGraphLayout.elkOptionExperiment.failed", {
        variant: experiment.variant,
        nodeCount: nodes.length,
        edgeCount: edges.length,
        errorMessage: error instanceof Error ? error.message : String(error),
        durationMs: measureDuration(startedAt),
      });
    }
  }
}

/**
 * 事实图主布局入口：
 * 1. 锁定锚点节点；
 * 2. 构造前向/反向邻接表，分别 BFS 得到上/下游层级；
 * 3. 据此把每个节点归类为上游/当前/下游并写入分区、泳道、角色等元数据；
 * 4. 把准备好的结构交给 ELK 执行实际坐标排布。
 * 全过程附带多段计时 trace，便于性能分析。
 */
export async function layoutFactGraphView({
  nodes,
  edges,
  anchorNodeId,
  sizeSnapshot,
}: MeasuredLayoutRequest) {
  const startedAt = measureStart();
  const anchorId = resolveAnchorNodeId(nodes, anchorNodeId);
  if (!anchorId) {
    return { nodes, edges };
  }
  const adjacencyStartedAt = measureStart();
  const outgoing = buildOutgoing(edges);
  const incoming = buildIncoming(edges);
  const adjacencyDurationMs = measureDuration(adjacencyStartedAt);
  const traversalStartedAt = measureStart();
  const upstreamDistances = bfs(anchorId, incoming);
  const downstreamDistances = bfs(anchorId, outgoing);
  const traversalDurationMs = measureDuration(traversalStartedAt);

  const definitionStartedAt = measureStart();
  const directionCounts = emptyDirectionCounts();
  const layoutNodes = nodes.map((node) => {
    const direction = resolveFactDirection(node, anchorId, upstreamDistances, downstreamDistances);
    directionCounts[direction.direction] += 1;
    return {
      node,
      ...resolveMeasuredNodeSize(node, sizeSnapshot, "FACT_GRAPH"),
      metadata: {
        "layout.direction": direction.direction,
        "layout.levelLabel": factDirectionLabel(direction.direction, direction.depth),
        "presentation.laneId": factPresentationLaneId(direction.direction),
        "presentation.role": factPresentationRole(direction.direction),
        "presentation.priority": String(PARTITION_INDEX[direction.direction] * 10 + 10),
        "presentation.compact": String(direction.direction !== "CURRENT"),
      },
      layoutOptions: {
        "org.eclipse.elk.partitioning.partition": String(PARTITION_INDEX[direction.direction]),
      },
    };
  });
  const layoutEdges = edges.map((edge) => ({ edge }));
  const definitionDurationMs = measureDuration(definitionStartedAt);
  traceLinkGraph("factGraphLayout.prepared", {
    nodeCount: nodes.length,
    edgeCount: edges.length,
    anchorNodeId: anchorId,
    upstreamReachableCount: upstreamDistances.size,
    downstreamReachableCount: downstreamDistances.size,
    directionCounts,
    measuredSizeCount: sizeSnapshot.size,
    adjacencyDurationMs,
    traversalDurationMs,
    definitionDurationMs,
    totalPreElkDurationMs: measureDuration(startedAt),
  });
  return executeElkLayout({
    mode: "FACT_GRAPH",
    layoutOptions: FACT_GRAPH_LAYOUT_OPTIONS,
    nodes: layoutNodes,
    edges: layoutEdges,
  });
}

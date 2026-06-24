import type { LinkGraphEdge, LinkGraphNode } from "../../types";
import {
  edgeSortKey,
  LANE_RANK,
  nodeSortKey,
  type ClassDiagramLane,
  type ClassLaneEntry,
} from "./classDiagramLayoutModel";
import {
  isClassDiagramDependencyRelation,
  isClassDiagramHierarchyRelation,
} from "./classDiagramRelations";

/**
 * 类图拓扑结构计算结果，刻画以锚点节点为中心的引用网络。
 * 包含正反向邻接关系、节点到锚点的最短跳数、以及按泳道归类的节点列表，
 * 供后续布局算法按层级（父类/调用方/被调用方/数据节点）摆放节点。
 */
export interface ClassDiagramTopology {
  /** 当前作为视图中心的节点 ID，所有距离与泳道划分都以它为基准。 */
  anchorId: string;
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  /** 以目标节点为键的反向邻接表，用于查询"谁引用了此节点"。 */
  incoming: Map<string, LinkGraphEdge[]>;
  /** 以源节点为键的正向邻接表，用于查询"此节点引用了谁"。 */
  outgoing: Map<string, LinkGraphEdge[]>;
  /** 沿 incoming 方向到锚点的最短跳数，反映节点在调用链上游的位置。 */
  incomingDistances: Map<string, number>;
  /** 沿 outgoing 方向到锚点的最短跳数，反映节点在调用链下游的位置。 */
  outgoingDistances: Map<string, number>;
  /** 已判定泳道与深度的节点条目，按泳道优先级和深度排序后的结果。 */
  nodesWithLane: ClassLaneEntry[];
  /** 按泳道分组的节点桶，便于布局时按列批量取用。 */
  laneBuckets: Map<ClassDiagramLane, ClassLaneEntry[]>;
}

/**
 * 类图拓扑构建器，负责把原始节点和边转换为带泳道信息的拓扑结构。
 * 内部按 BFS 计算到锚点的最短距离，再依据边类型与距离判定每个节点归属的泳道，
 * 是布局阶段之前的核心预处理组件。
 */
export class ClassDiagramTopologyBuilder {
  /**
   * 主入口：根据锚点节点构造拓扑视图。
   * 若无法在输入中确定锚点则返回 null，调用方需自行处理空值。
   */
  build(nodes: LinkGraphNode[], edges: LinkGraphEdge[], anchorNodeId?: string | null): ClassDiagramTopology | null {
    const anchorId = this.resolveAnchorNodeId(nodes, anchorNodeId);
    if (!anchorId) {
      return null;
    }
    const incoming = this.buildIncoming(edges);
    const outgoing = this.buildOutgoing(edges);
    const incomingDistances = this.shortestDistances(anchorId, incoming, "source");
    const outgoingDistances = this.shortestDistances(anchorId, outgoing, "target");
    const nodesWithLane = nodes
      .map((node) => ({
        node,
        ...this.resolveLane(node, anchorId, incomingDistances, outgoingDistances, incoming, outgoing),
      }))
      .sort((left, right) =>
        LANE_RANK[left.lane] - LANE_RANK[right.lane]
        || left.depth - right.depth
        || nodeSortKey(left.node).localeCompare(nodeSortKey(right.node)),
      );
    const laneBuckets = new Map<ClassDiagramLane, ClassLaneEntry[]>();
    nodesWithLane.forEach((entry) => {
      laneBuckets.set(entry.lane, [...(laneBuckets.get(entry.lane) ?? []), entry]);
    });
    return {
      anchorId,
      nodes,
      edges,
      incoming,
      outgoing,
      incomingDistances,
      outgoingDistances,
      nodesWithLane,
      laneBuckets,
    };
  }

  /**
   * 确定本次拓扑的锚点节点 ID。
   * 优先沿用调用方传入的候选 ID（需校验其确实存在于节点集合中），
   * 否则退化到第一个 CLASS 类型节点，再退化为首个节点，都没有时返回 null。
   */
  private resolveAnchorNodeId(
    nodes: LinkGraphNode[],
    anchorNodeId?: string | null,
  ): string | null {
    if (anchorNodeId && nodes.some((node) => node.id === anchorNodeId)) {
      return anchorNodeId;
    }
    return nodes.find((node) => node.type === "CLASS")?.id ?? nodes[0]?.id ?? null;
  }

  /**
   * 构造反向邻接表：以边的 target 为键聚合，便于查询某节点的所有入边。
   */
  private buildIncoming(edges: LinkGraphEdge[]) {
    const incoming = new Map<string, LinkGraphEdge[]>();
    edges.forEach((edge) => {
      incoming.set(edge.target, [...(incoming.get(edge.target) ?? []), edge]);
    });
    return incoming;
  }

  /**
   * 构造正向邻接表：以边的 source 为键聚合，便于查询某节点的所有出边。
   */
  private buildOutgoing(edges: LinkGraphEdge[]) {
    const outgoing = new Map<string, LinkGraphEdge[]>();
    edges.forEach((edge) => {
      outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge]);
    });
    return outgoing;
  }

  /**
   * 以锚点为根做广度优先搜索，计算所有可达节点到锚点的最短跳数。
   * direction 决定扩展方向：source 表示沿入边向上游走，target 表示沿出边向下游走。
   * 扩展邻居时按 edgeSortKey 排序，保证相同跳数下遍历顺序稳定、结果可复现。
   */
  private shortestDistances(
    anchorId: string,
    adjacency: Map<string, LinkGraphEdge[]>,
    direction: "source" | "target",
  ) {
    // 到锚点的跳数表，锚点本身为 0
    const distances = new Map<string, number>([[anchorId, 0]]);
    // 广度优先遍历队列，按入队顺序逐层向外扩展
    const queue = [anchorId];
    while (queue.length > 0) {
      const current = queue.shift();
      if (!current) {
        continue;
      }
      // 当前节点到锚点的累积跳数，作为后续邻居跳数计算的基数
      const baseDistance = distances.get(current) ?? 0;
      (adjacency.get(current) ?? [])
        .slice()
        .sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right)))
        .forEach((edge) => {
          const nextId = direction === "source" ? edge.source : edge.target;
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
   * 判定单个节点归属的泳道以及在泳道内的深度。
   * 优先级：锚点自身 > 与锚点有继承/实现关系的直系父类 > 与锚点直接相邻的入/出节点
   * > 间接的数据依赖节点（按距离推断）> 仅出现在上游或下游的节点 > 其他关联节点。
   */
  private resolveLane(
    node: LinkGraphNode,
    anchorId: string,
    incomingDistances: Map<string, number>,
    outgoingDistances: Map<string, number>,
    incoming: Map<string, LinkGraphEdge[]>,
    outgoing: Map<string, LinkGraphEdge[]>,
  ): { lane: ClassDiagramLane; depth: number } {
    if (node.id === anchorId) {
      // 锚点节点固定处于 ANCHOR 泳道，深度归零
      return { lane: "ANCHOR", depth: 0 };
    }
    // 锚点的直系出边和入边集合，用于判定与当前节点的关系亲密程度
    const anchorOutgoingEdges = outgoing.get(anchorId) ?? [];
    const anchorIncomingEdges = incoming.get(anchorId) ?? [];
    // 若当前节点与锚点之间存在继承/实现关系（父类或子类），归入 PARENT 泳道
    const hasAnchorHierarchyEdge = anchorOutgoingEdges.some((edge) => edge.target === node.id && isClassDiagramHierarchyRelation(edge))
      || anchorIncomingEdges.some((edge) => edge.source === node.id && isClassDiagramHierarchyRelation(edge));
    if (hasAnchorHierarchyEdge) {
      return { lane: "PARENT", depth: outgoingDistances.get(node.id) ?? incomingDistances.get(node.id) ?? 1 };
    }
    // 检测当前节点是否与锚点存在直接连边，区分出入方向
    const directAnchorEdge = this.hasDirectAnchorEdge(node.id, anchorOutgoingEdges, anchorIncomingEdges);
    // 仅有入边、没有出边的节点视为纯粹的调用方（如服务的上游消费者）
    if (directAnchorEdge.incoming && !directAnchorEdge.outgoing) {
      return { lane: "INCOMING", depth: 1 };
    }
    // 只要存在指向当前节点的出边，就归入 OUTGOING 泳道（被锚点调用的一方）
    if (directAnchorEdge.outgoing) {
      return { lane: "OUTGOING", depth: 1 };
    }
    // 当节点属于间接数据依赖（如 DTO 通过中间层被引用），或本身是数据型节点类型，归入 DATA 泳道
    if (
      this.hasSecondaryDataEdge(node.id, incomingDistances, outgoingDistances, incoming)
      || (outgoingDistances.has(node.id) && this.isDataNode(node))
    ) {
      return { lane: "DATA", depth: outgoingDistances.get(node.id) ?? incomingDistances.get(node.id) ?? 1 };
    }
    if (incomingDistances.has(node.id) && !outgoingDistances.has(node.id)) {
      return { lane: "INCOMING", depth: incomingDistances.get(node.id) ?? 1 };
    }
    if (outgoingDistances.has(node.id)) {
      return { lane: "OUTGOING", depth: outgoingDistances.get(node.id) ?? 1 };
    }
    if (incomingDistances.has(node.id)) {
      return { lane: "INCOMING", depth: incomingDistances.get(node.id) ?? 1 };
    }
    return { lane: "RELATED", depth: 1 };
  }

  /**
   * 判断节点是否属于"数据型"类型（枚举、记录、对象），
   * 同时兼容节点 type 字段和 JVM 元数据两种来源，避免类型信息缺失时漏判。
   */
  private isDataNode(node: LinkGraphNode): boolean {
    return node.type === "ENUM"
      || node.type === "RECORD"
      || node.type === "OBJECT"
      || node.metadata?.["jvm.class.kind"] === "ENUM"
      || node.metadata?.["jvm.class.kind"] === "RECORD"
      || node.metadata?.["jvm.class.kind"] === "OBJECT";
  }

  /**
   * 给定某节点 ID，判断它与锚点之间是否存在直接连边，并返回方向标志。
   * incoming 为 true 表示锚点存在指向该节点的入边（即该节点是锚点的来源），
   * outgoing 为 true 表示锚点存在指向该节点的出边（即该节点是锚点的去向）。
   */
  private hasDirectAnchorEdge(
    nodeId: string,
    anchorOutgoingEdges: LinkGraphEdge[],
    anchorIncomingEdges: LinkGraphEdge[],
  ): { incoming: boolean; outgoing: boolean } {
    return {
      incoming: anchorIncomingEdges.some((edge) => edge.source === nodeId),
      outgoing: anchorOutgoingEdges.some((edge) => edge.target === nodeId),
    };
  }

  /**
   * 判定节点是否属于"间接数据依赖"：当节点离锚点的出向距离大于 1，
   * 自身没有入向路径、但其入边来源位于锚点下游且为依赖关系时，
   * 视为通过中间节点串联的数据节点（如 Service 内部用到的 DTO）。
   */
  private hasSecondaryDataEdge(
    nodeId: string,
    incomingDistances: Map<string, number>,
    outgoingDistances: Map<string, number>,
    incoming: Map<string, LinkGraphEdge[]>,
  ): boolean {
    const distance = outgoingDistances.get(nodeId);
    if (!distance || distance <= 1) {
      return false;
    }
    return (incoming.get(nodeId) ?? []).some((edge) =>
      isClassDiagramDependencyRelation(edge)
      && outgoingDistances.has(edge.source)
      && !incomingDistances.has(nodeId),
    );
  }
}

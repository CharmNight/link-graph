import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import { traceLinkGraph } from "../../debug";
import type { LinkGraphEdge, LinkGraphNode } from "../../types";
import {
  ANCHOR_ROUTE_SOURCE_MARGIN_X,
  ANCHOR_ROUTE_TARGET_MARGIN_X,
  DATA_ANCHOR_GAP,
  DATA_COLUMN_VERTICAL_STAGGER,
  DATA_RIGHT_OFFSET,
  FALLBACK_HEIGHT,
  LANE_COLUMN_LIMIT,
  MIN_NODE_WIDTH,
  ORIGIN,
  PARENT_ANCHOR_GAP,
  RELATED_LANE_GAP,
  STACK_GAP,
  Y_GAP,
  edgeSortKey,
  laneColumnGap,
  laneColumnWidth,
  measuredNodeSize,
  nodeSortKey,
  type ClassDiagramLane,
  type ClassLaneEntry,
} from "./classDiagramLayoutModel";
import {
  isClassDiagramDependencyRelation,
  isClassDiagramHierarchyRelation,
} from "./classDiagramRelations";
import type { ClassDiagramTopology } from "./classDiagramTopology";

/** 类图布局放置结果，包含锚点节点 ID、已布局节点列表、节点索引和按泳道分组的桶。 */
export interface ClassDiagramPlacement {
  anchorId: string;
  nodes: LinkGraphNode[];
  nodeIndex: Map<string, LinkGraphNode>;
  laneBuckets: Map<ClassDiagramLane, ClassLaneEntry[]>;
}

/** 节点的展示元数据，用于驱动前端渲染时的角色、泳道标识、优先级与紧凑模式。 */
interface ClassDiagramPresentationMetadata {
  laneId: string;
  role: "ANCHOR" | "INTERFACE" | "CALLER" | "COLLABORATOR" | "OUTPUT" | "TYPE";
  priority: number;
  compact: boolean;
}

/** 泳道分列布局方案，记录每个节点落入的列号、列总数以及按列分组的节点列表。 */
interface LaneColumnPlan {
  columnByNodeId: Map<string, number>;
  columnCount: number;
  nodesByColumn: Map<number, ClassLaneEntry[]>;
}

// 数据泳道多列布局时相邻列之间的 Y 方向错位，避免列间水平连线重叠。
const COLUMN_STAGGER = DATA_COLUMN_VERTICAL_STAGGER;

/**
 * 类图节点放置引擎：负责将拓扑分组后的节点按泳道（父类/入向/锚点/出向/数据/相关）
 * 划分到画布的合理坐标，确保连线清晰、视觉层级明确，并提供可观测的展示元数据。
 */
export class ClassDiagramPlacementEngine {
  /**
   * 根据拓扑结构与节点尺寸快照执行布局，输出每个节点的最终坐标以及展示元数据。
   * 内部依次完成：克隆分桶、桶内排序、列方案构建、横纵向坐标推导、节点写入。
   */
  place(
    topology: ClassDiagramTopology,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): ClassDiagramPlacement {
    const laneBuckets = this.cloneLaneBuckets(topology.laneBuckets);
    laneBuckets.forEach((bucket, lane) => {
      this.sortLaneBucket(
        bucket,
        lane,
        topology.anchorId,
        topology.incoming,
        topology.outgoing,
        topology.incomingDistances,
        topology.outgoingDistances,
      );
    });

    // 取出各泳道的节点桶（输入向 / 输出向 / 数据 / 相关），后续按桶构建列方案与坐标。
    const incomingBucket = laneBuckets.get("INCOMING") ?? [];
    const outgoingBucket = laneBuckets.get("OUTGOING") ?? [];
    const dataBucket = laneBuckets.get("DATA") ?? [];
    const relatedBucket = laneBuckets.get("RELATED") ?? [];

    // 依据桶内节点与依赖关系，分别为四个非锚点泳道构建列分配方案。
    const incomingPlan = this.buildLaneColumnPlan(incomingBucket, topology, "INCOMING");
    const outgoingPlan = this.buildLaneColumnPlan(outgoingBucket, topology, "OUTGOING");
    const dataPlan = this.buildLaneColumnPlan(dataBucket, topology, "DATA");
    const relatedPlan = this.buildLaneColumnPlan(relatedBucket, topology, "RELATED");

    // 各泳道在水平方向占用的总宽度（依赖列数与单列宽度）。
    const incomingLaneWidth = laneColumnWidth("INCOMING", incomingPlan.columnCount);
    const outgoingLaneWidth = laneColumnWidth("OUTGOING", outgoingPlan.columnCount);
    const dataLaneWidth = laneColumnWidth("DATA", dataPlan.columnCount);

    // 根据锚点出入向扇出边数量推导连线路由通道的宽度，避免锚点附近边重叠。
    const incomingLaneGap = this.anchorFanoutLaneGap(this.anchorIncomingFanoutCount(topology.anchorId, incomingBucket, topology.incoming));
    const outgoingLaneGap = this.anchorFanoutLaneGap(this.anchorOutgoingFanoutCount(topology.anchorId, outgoingBucket, topology.outgoing));

    // 自左向右推导：入向泳道 → 锚点 → 出向泳道 → 数据泳道 → 相关泳道的水平起始坐标。
    const anchorX = ORIGIN.x + incomingLaneWidth + incomingLaneGap;
    const anchorWidth = MIN_NODE_WIDTH;
    const outgoingX = anchorX + anchorWidth + outgoingLaneGap;
    const dataX = outgoingX + outgoingLaneWidth + DATA_RIGHT_OFFSET;
    const relatedX = dataX + dataLaneWidth + RELATED_LANE_GAP;

    // 泳道水平起始坐标映射，所有节点放置时共用。
    const laneX: Record<ClassDiagramLane, number> = {
      PARENT: anchorX,
      INCOMING: ORIGIN.x,
      ANCHOR: anchorX,
      OUTGOING: outgoingX,
      DATA: dataX,
      RELATED: relatedX,
    };

    const parentBucket = laneBuckets.get("PARENT") ?? [];
    const anchorBucket = laneBuckets.get("ANCHOR") ?? [];

    /**
     * 计算同一桶内按堆叠方式（无多列）排列后的总高度，用于后续居中布局。
     * 节点之间的 STACK_GAP 决定了视觉堆叠的间距。
     */
    const measuredColumnHeight = (
      bucket: ClassLaneEntry[],
    ): number => {
      if (bucket.length === 0) {
        return 0;
      }
      return bucket.reduce((height, entry, index) => {
        const size = measuredNodeSize(entry.node, sizeSnapshot, entry.lane);
        return height + size.height + (index === bucket.length - 1 ? 0 : STACK_GAP);
      }, 0);
    };

    // 入向 / 出向泳道的纵向堆叠总高度，决定它们相对锚点居中所需的高度。
    const incomingHeight = measuredColumnHeight(incomingBucket);
    const outgoingHeight = measuredColumnHeight(outgoingBucket);

    // 父类泳道堆叠在锚点上方，其总高度决定锚点的起始 Y。
    const parentStackHeight = parentBucket.reduce((height, entry, index) => {
      const size = measuredNodeSize(entry.node, sizeSnapshot, entry.lane);
      return height + size.height + (index === parentBucket.length - 1 ? 0 : STACK_GAP);
    }, 0);

    // 锚点起始 Y：上方有父类时留出间距；否则使用顶部 Y 间距。
    const anchorStartY = parentStackHeight > 0
      ? ORIGIN.y + parentStackHeight + PARENT_ANCHOR_GAP
      : ORIGIN.y + Y_GAP;
    // 锚点高度优先取实测值，缺失时使用兜底高度（保证无尺寸场景也能渲染）。
    const anchorHeight = anchorBucket[0]
      ? measuredNodeSize(anchorBucket[0].node, sizeSnapshot, "ANCHOR").height
      : FALLBACK_HEIGHT;
    const anchorCenterY = anchorStartY + anchorHeight / 2;

    // 给定一侧泳道总高度，返回其相对锚点垂直居中的起始 Y（不小于画布顶部留白）。
    const centeredBaseStartY = (totalHeight: number): number =>
      Math.max(ORIGIN.y, Math.round(anchorCenterY - totalHeight / 2));

    // 入向 / 出向泳道的基础 Y（同一泳道内多列共享，再按列号叠加错位）。
    const incomingBaseStartY = centeredBaseStartY(incomingHeight);
    const outgoingBaseStartY = centeredBaseStartY(outgoingHeight);

    // 列号映射为 Y 偏移：每多一列整体下移一个错位量，形成阶梯式布局。
    const columnStartYFor = (baseStartY: number, column: number): number =>
      baseStartY + column * COLUMN_STAGGER;

    // 上半部分（锚点 / 入向 / 出向）的底边，作为数据泳道的起始参考。
    const nonDataBottom = Math.max(
      anchorStartY + anchorHeight,
      incomingBaseStartY + incomingHeight,
      outgoingBaseStartY + outgoingHeight,
    );
    // 数据泳道放置在上半部分下方，留出固定间距；相关泳道则从顶部开始垂直延展。
    const dataBaseStartY = nonDataBottom + DATA_ANCHOR_GAP;
    const relatedBaseStartY = ORIGIN.y + Y_GAP;

    // 已放置节点的累加数组，所有泳道处理完成后返回给上层。
    const placedNodes: LinkGraphNode[] = [];
    /**
     * 通用列堆叠放置：对每个非父、非锚点泳道，按"列"由左向右展开，
     * 同一列内部按桶内顺序自上而下堆叠，并写入布局/展示相关元数据。
     */
    const placeColumnStack = (
      lane: ClassDiagramLane,
      plan: LaneColumnPlan,
      baseStartY: number,
    ) => {
      plan.nodesByColumn.forEach((bucket, column) => {
        const columnX = laneX[lane] + column * (MIN_NODE_WIDTH + laneColumnGap(lane));
        const columnStartY = columnStartYFor(baseStartY, column);
        let cursorY = columnStartY;
        bucket.forEach((entry, index) => {
          const size = measuredNodeSize(entry.node, sizeSnapshot, lane);
          const x = Math.round(columnX);
          const y = Math.round(cursorY);
          const presentation = this.resolvePresentationMetadata(entry.node, lane);
          placedNodes.push({
            ...entry.node,
            position: { x, y },
            metadata: {
              ...(entry.node.metadata ?? {}),
              "layout.mode": "CLASS_DIAGRAM",
              "layout.direction": lane,
              "layout.levelLabel": this.classLayoutLabel(lane, entry.depth),
              "layout.estimatedHeight": String(size.height),
              "layout.column": String(column),
              "layout.row": String(index),
              "presentation.role": presentation.role,
              "presentation.laneId": presentation.laneId,
              "presentation.priority": String(presentation.priority),
              "presentation.compact": String(presentation.compact),
              "ui.x": String(x),
              "ui.y": String(y),
            },
          });
          cursorY += size.height + STACK_GAP;
        });
      });
    };

    // 父类泳道（继承/实现目标）单独处理：仅单列、紧贴锚点上方堆叠。
    parentBucket.forEach((entry, index) => {
      const size = measuredNodeSize(entry.node, sizeSnapshot, "PARENT");
      const x = Math.round(laneX.PARENT);
      const y = Math.round(ORIGIN.y + index * (size.height + STACK_GAP));
      const presentation = this.resolvePresentationMetadata(entry.node, "PARENT");
      placedNodes.push({
        ...entry.node,
        position: { x, y },
        metadata: {
          ...(entry.node.metadata ?? {}),
          "layout.mode": "CLASS_DIAGRAM",
          "layout.direction": "PARENT",
          "layout.levelLabel": this.classLayoutLabel("PARENT", entry.depth),
          "layout.estimatedHeight": String(size.height),
          "layout.column": "0",
          "layout.row": String(index),
          "presentation.role": presentation.role,
          "presentation.laneId": presentation.laneId,
          "presentation.priority": String(presentation.priority),
          "presentation.compact": String(presentation.compact),
          "ui.x": String(x),
          "ui.y": String(y),
        },
      });
    });

    // 锚点节点单独处理：固定单列、位于父类泳道下方，是整个类图的视觉焦点。
    anchorBucket.forEach((entry) => {
      const size = measuredNodeSize(entry.node, sizeSnapshot, "ANCHOR");
      const x = Math.round(laneX.ANCHOR);
      const y = Math.round(anchorStartY);
      const presentation = this.resolvePresentationMetadata(entry.node, "ANCHOR");
      placedNodes.push({
        ...entry.node,
        position: { x, y },
        metadata: {
          ...(entry.node.metadata ?? {}),
          "layout.mode": "CLASS_DIAGRAM",
          "layout.direction": "ANCHOR",
          "layout.levelLabel": this.classLayoutLabel("ANCHOR", entry.depth),
          "layout.estimatedHeight": String(size.height),
          "layout.column": "0",
          "layout.row": "0",
          "presentation.role": presentation.role,
          "presentation.laneId": presentation.laneId,
          "presentation.priority": String(presentation.priority),
          "presentation.compact": String(presentation.compact),
          "ui.x": String(x),
          "ui.y": String(y),
        },
      });
    });

    // 依次对入向、出向、数据、相关泳道执行列堆叠放置。
    placeColumnStack("INCOMING", incomingPlan, incomingBaseStartY);
    placeColumnStack("OUTGOING", outgoingPlan, outgoingBaseStartY);
    placeColumnStack("DATA", dataPlan, dataBaseStartY);
    placeColumnStack("RELATED", relatedPlan, relatedBaseStartY);

    // 节点索引：将已放置节点按 ID 索引以便后续连线/选中时 O(1) 查找。
    const nodeIndex = new Map(placedNodes.map((node) => [node.id, node]));
    // 重叠统计：检测同列内是否存在重叠节点，用于调试与布局质量分析。
    const overlapSummary = this.laneOverlapSummary(placedNodes, sizeSnapshot);
    // 上报本次布局完成事件，附泳道分布、列数、坐标、重叠信息，供性能/质量分析。
    traceLinkGraph("classDiagramLayout.placement.complete", {
      nodeCount: placedNodes.length,
      edgeCount: topology.edges.length,
      anchorNodeId: topology.anchorId,
      laneCounts: Object.fromEntries(
        (["PARENT", "INCOMING", "ANCHOR", "OUTGOING", "DATA", "RELATED"] as ClassDiagramLane[])
          .map((lane) => [lane, laneBuckets.get(lane)?.length ?? 0]),
      ),
      laneColumns: Object.fromEntries([
        ["INCOMING", incomingPlan.columnCount],
        ["OUTGOING", outgoingPlan.columnCount],
        ["DATA", dataPlan.columnCount],
        ["RELATED", relatedPlan.columnCount],
      ]),
      laneX,
      anchorStartY,
      maxNodeHeight: Math.max(...placedNodes.map((node) => measuredNodeSize(node, sizeSnapshot).height)),
      ...overlapSummary,
    });
    return {
      anchorId: topology.anchorId,
      nodes: placedNodes,
      nodeIndex,
      laneBuckets,
    };
  }

  /**
   * 根据节点所处泳道推导其展示元数据：角色、泳道标识、优先级（数字越小优先级越高）、
   * 是否使用紧凑布局等。这些元数据驱动前端的样式渲染与可见性控制。
   */
  private resolvePresentationMetadata(
    node: LinkGraphNode,
    lane: ClassDiagramLane,
  ): ClassDiagramPresentationMetadata {
    switch (lane) {
      case "ANCHOR":
        return { laneId: "anchor", role: "ANCHOR", priority: 30, compact: false };
      case "PARENT":
        return this.isAbstractionNode(node)
          ? { laneId: "abstraction", role: "INTERFACE", priority: 10, compact: true }
          : { laneId: "abstraction", role: "TYPE", priority: 45, compact: true };
      case "INCOMING":
        return { laneId: "caller", role: "CALLER", priority: 20, compact: true };
      case "OUTGOING":
        return { laneId: "collaborator", role: "COLLABORATOR", priority: 40, compact: true };
      case "DATA":
        return { laneId: "output", role: "OUTPUT", priority: 50, compact: true };
      case "RELATED":
        return { laneId: "collaborator", role: "TYPE", priority: 45, compact: true };
    }
  }

  /** 判断该节点是否为抽象类型（接口或抽象类），用于决定其展示角色（接口/类型）。 */
  private isAbstractionNode(node: LinkGraphNode): boolean {
    return node.type === "INTERFACE"
      || node.metadata?.["jvm.class.kind"] === "INTERFACE"
      || node.metadata?.["jvm.class.abstract"] === "true";
  }

  /** 深拷贝泳道分桶，避免直接修改上游拓扑数据造成副作用。 */
  private cloneLaneBuckets(source: Map<ClassDiagramLane, ClassLaneEntry[]>): Map<ClassDiagramLane, ClassLaneEntry[]> {
    return new Map(Array.from(source, ([lane, bucket]) => [lane, [...bucket]]));
  }

  /**
   * 根据泳道与深度生成展示在前端的层级标签，例如"继承/实现""入向关系 N 层"，
   * 用于在 UI 上标识节点的语义角色与距离锚点的拓扑深度。
   */
  private classLayoutLabel(lane: ClassDiagramLane, depth: number): string {
    switch (lane) {
      case "PARENT":
        return "继承/实现";
      case "INCOMING":
        return `入向关系 ${Math.max(depth, 1)} 层`;
      case "ANCHOR":
        return "当前类型";
      case "OUTGOING":
        return `出向关系 ${Math.max(depth, 1)} 层`;
      case "DATA":
        return "数据结构";
      case "RELATED":
        return "相关类型";
    }
  }

  /**
   * 统计布局后各泳道内同列节点之间的纵向重叠情况：重叠数量与最小垂直间距。
   * 用于事后评估布局质量、辅助调整 STACK_GAP 等布局参数。
   */
  private laneOverlapSummary(
    nodes: LinkGraphNode[],
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ) {
    let overlapCount = 0;
    let minVerticalGap = Number.POSITIVE_INFINITY;
    const lanes = new Map<string, LinkGraphNode[]>();
    nodes.forEach((node) => {
      const lane = node.metadata?.["layout.direction"] ?? "UNKNOWN";
      const column = node.metadata?.["layout.column"] ?? "0";
      lanes.set(`${lane}:${column}`, [...(lanes.get(`${lane}:${column}`) ?? []), node]);
    });
    lanes.forEach((laneNodes) => {
      const ordered = laneNodes
        .filter((node) => node.position)
        .sort((left, right) => (left.position?.y ?? 0) - (right.position?.y ?? 0));
      for (let index = 1; index < ordered.length; index += 1) {
        const previous = ordered[index - 1];
        const current = ordered[index];
        if (!previous?.position || !current?.position) {
          continue;
        }
        const previousBottom = previous.position.y + measuredNodeSize(previous, sizeSnapshot).height;
        const gap = current.position.y - previousBottom;
        minVerticalGap = Math.min(minVerticalGap, gap);
        if (gap < 0) {
          overlapCount += 1;
        }
      }
    });
    return {
      overlapCount,
      minVerticalGap: Number.isFinite(minVerticalGap) ? Math.round(minVerticalGap) : null,
    };
  }

  /**
   * 为桶内节点计算排序用的稳定键。
   * 优先级原则：锚点 > 直连锚点的关系边 > 跨越两侧泳道的关系边 > 兜底 ID 排序。
   * 同一直连关系的节点会被聚拢，使连线路径更短、视觉更紧凑。
   */
  private laneNodeOrderKey(
    nodeId: string,
    lane: ClassDiagramLane,
    anchorId: string,
    incoming: Map<string, LinkGraphEdge[]>,
    outgoing: Map<string, LinkGraphEdge[]>,
    incomingDistances: Map<string, number>,
    outgoingDistances: Map<string, number>,
  ): string {
    if (nodeId === anchorId) {
      return "0|anchor";
    }
    const anchorIncomingEdge = (incoming.get(anchorId) ?? [])
      .filter((edge) => edge.source === nodeId)
      .sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right)))[0];
    const anchorOutgoingEdge = (outgoing.get(anchorId) ?? [])
      .filter((edge) => edge.target === nodeId)
      .sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right)))[0];
    if (lane === "INCOMING" && anchorIncomingEdge) {
      return `0|${edgeSortKey(anchorIncomingEdge)}`;
    }
    if ((lane === "OUTGOING" || lane === "PARENT") && anchorOutgoingEdge) {
      return `0|${edgeSortKey(anchorOutgoingEdge)}`;
    }
    const incomingEdge = (incoming.get(nodeId) ?? [])
      .filter((edge) => outgoingDistances.has(edge.source))
      .sort((left, right) =>
        (outgoingDistances.get(left.source) ?? 99) - (outgoingDistances.get(right.source) ?? 99)
        || edgeSortKey(left).localeCompare(edgeSortKey(right)),
      )[0];
    const outgoingEdge = (outgoing.get(nodeId) ?? [])
      .filter((edge) => incomingDistances.has(edge.target))
      .sort((left, right) =>
        (incomingDistances.get(left.target) ?? 99) - (incomingDistances.get(right.target) ?? 99)
        || edgeSortKey(left).localeCompare(edgeSortKey(right)),
      )[0];
    return `1|${incomingEdge ? edgeSortKey(incomingEdge) : ""}|${outgoingEdge ? edgeSortKey(outgoingEdge) : ""}|${nodeId}`;
  }

  /**
   * 对桶内节点进行排序，先用 laneNodeOrderKey 建立基础顺序；
   * 然后对入向/出向/数据/相关泳道执行拓扑排序（基于非层级关系边），
   * 让存在依赖关系的数据节点按依赖方向自顶向下排列，提升可读性。
   */
  private sortLaneBucket(
    bucket: ClassLaneEntry[],
    lane: ClassDiagramLane,
    anchorId: string,
    incoming: Map<string, LinkGraphEdge[]>,
    outgoing: Map<string, LinkGraphEdge[]>,
    incomingDistances: Map<string, number>,
    outgoingDistances: Map<string, number>,
  ) {
    bucket.sort((left, right) =>
      this.laneNodeOrderKey(left.node.id, lane, anchorId, incoming, outgoing, incomingDistances, outgoingDistances)
        .localeCompare(this.laneNodeOrderKey(right.node.id, lane, anchorId, incoming, outgoing, incomingDistances, outgoingDistances))
      || left.depth - right.depth
      || nodeSortKey(left.node).localeCompare(nodeSortKey(right.node)),
    );
    if (bucket.length <= 1 || lane === "ANCHOR" || lane === "PARENT") {
      return;
    }
    const nodeIds = new Set(bucket.map((entry) => entry.node.id));
    const baseOrder = new Map(bucket.map((entry, index) => [entry.node.id, index]));
    const adjacency = new Map<string, string[]>();
    const indegree = new Map(bucket.map((entry) => [entry.node.id, 0]));
    const seenPairs = new Set<string>();
    bucket
      .flatMap((entry) => outgoing.get(entry.node.id) ?? [])
      .filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target) && edge.source !== edge.target && !isClassDiagramHierarchyRelation(edge))
      .sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right)))
      .forEach((edge) => {
        const pairKey = `${edge.source}->${edge.target}`;
        if (seenPairs.has(pairKey)) {
          return;
        }
        seenPairs.add(pairKey);
        adjacency.set(edge.source, [...(adjacency.get(edge.source) ?? []), edge.target]);
        indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1);
      });
    if (seenPairs.size === 0) {
      return;
    }
    const entryById = new Map(bucket.map((entry) => [entry.node.id, entry]));
    const compareBaseOrder = (left: string, right: string) => (baseOrder.get(left) ?? 0) - (baseOrder.get(right) ?? 0);
    const ready = bucket
      .map((entry) => entry.node.id)
      .filter((nodeId) => (indegree.get(nodeId) ?? 0) === 0)
      .sort(compareBaseOrder);
    const ordered: ClassLaneEntry[] = [];
    const consumed = new Set<string>();
    while (ready.length > 0) {
      const nodeId = ready.shift()!;
      if (consumed.has(nodeId)) {
        continue;
      }
      consumed.add(nodeId);
      const entry = entryById.get(nodeId);
      if (entry) {
        ordered.push(entry);
      }
      (adjacency.get(nodeId) ?? []).sort(compareBaseOrder).forEach((targetId) => {
        const nextIndegree = (indegree.get(targetId) ?? 0) - 1;
        indegree.set(targetId, nextIndegree);
        if (nextIndegree === 0) {
          ready.push(targetId);
          ready.sort(compareBaseOrder);
        }
      });
    }
    if (ordered.length === bucket.length) {
      bucket.splice(0, bucket.length, ...ordered);
      return;
    }
    const orderedIds = new Set(ordered.map((entry) => entry.node.id));
    bucket.splice(0, bucket.length, ...ordered, ...bucket.filter((entry) => !orderedIds.has(entry.node.id)));
  }

  /**
   * 为指定泳道构建列方案：基于桶内依赖关系分配列号，限制不超过该泳道允许的最大列数；
   * 再在每列内部按 laneNodeOrderKey 排序，得到最终的 nodesByColumn。
   */
  private buildLaneColumnPlan(
    bucket: ClassLaneEntry[],
    topology: ClassDiagramTopology,
    lane: ClassDiagramLane,
  ): LaneColumnPlan {
    if (bucket.length === 0) {
      return {
        columnByNodeId: new Map(),
        columnCount: 0,
        nodesByColumn: new Map(),
      };
    }
    const limit = LANE_COLUMN_LIMIT[lane];
    const columnByNodeId = this.resolveLaneColumns(bucket, topology, limit);
    let columnCount = 0;
    const nodesByColumn = new Map<number, ClassLaneEntry[]>();
    bucket.forEach((entry) => {
      const column = Math.min(columnByNodeId.get(entry.node.id) ?? 0, limit - 1);
      columnByNodeId.set(entry.node.id, column);
      columnCount = Math.max(columnCount, column + 1);
      nodesByColumn.set(column, [...(nodesByColumn.get(column) ?? []), entry]);
    });
    nodesByColumn.forEach((columnBucket) => {
      columnBucket.sort((left, right) =>
        this.laneNodeOrderKey(left.node.id, lane, topology.anchorId, topology.incoming, topology.outgoing, topology.incomingDistances, topology.outgoingDistances)
          .localeCompare(this.laneNodeOrderKey(right.node.id, lane, topology.anchorId, topology.incoming, topology.outgoing, topology.incomingDistances, topology.outgoingDistances))
        || left.depth - right.depth
        || nodeSortKey(left.node).localeCompare(nodeSortKey(right.node)),
      );
    });
    return { columnByNodeId, columnCount, nodesByColumn };
  }

  /**
   * 基于桶内依赖关系边（如类型依赖）计算每个节点应落入的列号：
   * 依赖目标节点的列号至少为源节点列号 + 1，并通过迭代多轮收敛至稳定。
   * 列号上限为 limit，超过则被钳制。
   */
  private resolveLaneColumns(
    bucket: ClassLaneEntry[],
    topology: ClassDiagramTopology,
    limit: number,
  ): Map<string, number> {
    if (limit <= 1 || bucket.length <= 1) {
      return new Map(bucket.map((entry) => [entry.node.id, 0]));
    }
    const nodeIds = new Set(bucket.map((entry) => entry.node.id));
    const columns = new Map(bucket.map((entry) => [entry.node.id, 0]));
    const dependencies = bucket
      .flatMap((entry) => topology.outgoing.get(entry.node.id) ?? [])
      .filter((edge) =>
        isClassDiagramDependencyRelation(edge)
        && edge.source !== edge.target
        && nodeIds.has(edge.source)
        && nodeIds.has(edge.target),
      )
      .sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right)));

    if (dependencies.length === 0) {
      return columns;
    }

    for (let pass = 0; pass < Math.max(1, bucket.length); pass += 1) {
      let changed = false;
      dependencies.forEach((edge) => {
        const sourceColumn = columns.get(edge.source) ?? 0;
        const nextColumn = Math.min(sourceColumn + 1, limit - 1);
        if ((columns.get(edge.target) ?? 0) < nextColumn) {
          columns.set(edge.target, nextColumn);
          changed = true;
        }
      });
      if (!changed) {
        break;
      }
    }
    return columns;
  }

  /** 根据锚点出入向扇出边数量计算泳道间距，扇出越多通道越宽，避免锚点附近边交叉。 */
  private anchorFanoutLaneGap(edgeCount: number): number {
    const fanoutCount = Math.max(1, edgeCount);
    const channelGap = fanoutCount <= 1
      ? 56
      : fanoutCount <= 2 ? 72
      : 84;
    const readableCorridorWidth = ANCHOR_ROUTE_SOURCE_MARGIN_X
      + ANCHOR_ROUTE_TARGET_MARGIN_X
      + (fanoutCount + 1) * channelGap;
    return Math.max(128, readableCorridorWidth);
  }

  /** 统计锚点指向出向泳道的扇出边数（排除继承等层级关系），用于计算泳道间距。 */
  private anchorOutgoingFanoutCount(
    anchorId: string,
    outgoingBucket: ClassLaneEntry[],
    outgoing: Map<string, LinkGraphEdge[]>,
  ): number {
    const outgoingNodeIds = new Set(outgoingBucket.map((entry) => entry.node.id));
    return (outgoing.get(anchorId) ?? []).filter((edge) =>
      outgoingNodeIds.has(edge.target)
      && !isClassDiagramHierarchyRelation(edge),
    ).length;
  }

  /** 统计入向泳道节点指向锚点的扇出边数（排除继承等层级关系），用于计算泳道间距。 */
  private anchorIncomingFanoutCount(
    anchorId: string,
    incomingBucket: ClassLaneEntry[],
    incoming: Map<string, LinkGraphEdge[]>,
  ): number {
    const incomingNodeIds = new Set(incomingBucket.map((entry) => entry.node.id));
    return (incoming.get(anchorId) ?? []).filter((edge) =>
      incomingNodeIds.has(edge.source)
      && !isClassDiagramHierarchyRelation(edge),
    ).length;
  }
}

import { architectureGraphNodeCardWidth } from "../../graphNodeSizing";
import { measureDuration, measureStart, traceLinkGraph } from "../../debug";
import { resolveMeasuredNodeSize } from "../../reactflow/elkGraph";
import { buildOrthogonalEdgeRoute, type OrthogonalRect } from "../../reactflow/orthogonalEdgeRouting";
import type { MeasuredLayoutRequest } from "../../reactflow/useMeasuredLayout";
import type { GraphPosition, LinkGraphEdge, LinkGraphNode } from "../../types";
import { architectureLaneSortValue } from "./architectureDisplayLayers";

/** 架构图左上角原点偏移：为顶部工具栏/侧栏留出可视边距，避免节点贴边。 */
const ARCHITECTURE_LAYOUT_ORIGIN: GraphPosition = { x: 120, y: 96 };
/** 不同泳道列之间的水平间距，控制整体图横向密度。 */
const ARCHITECTURE_COLUMN_GAP = 136;
/** 同一泳道内节点的垂直间距，避免节点重叠并留出边走线空间。 */
const ARCHITECTURE_ROW_GAP = 64;
/** 测量失败时的兜底节点高度，保证布局可读。 */
const ARCHITECTURE_DEFAULT_NODE_HEIGHT = 116;
/** 项目结构视图里相邻 band（横向分组）之间的垂直留白。 */
const STRUCTURE_LAYOUT_BAND_GAP = 72;
/** 项目结构视图里同 band 内的列间距。 */
const STRUCTURE_LAYOUT_COLUMN_GAP = 72;
/** 项目结构视图里同 band 内的行间距。 */
const STRUCTURE_LAYOUT_ROW_GAP = 50;
/** band 内左右内边距，给 band 标题和节点之间留呼吸感。 */
const STRUCTURE_LAYOUT_BAND_PADDING_X = 48;
/** band 上下内边距，配合 band 头部高度形成视觉包裹感。 */
const STRUCTURE_LAYOUT_BAND_PADDING_Y = 40;
/** band 顶部标题条高度，用于摆放 band 名称标签。 */
const STRUCTURE_LAYOUT_BAND_HEADER = 34;

/** 泳道标识：即"entry/application/domain/data/resource/external"这种展示层名称。 */
type ArchitectureLayoutLane = string;

/** 单个节点的布局上下文：聚合了原始节点、所属泳道、拓扑层级、尺寸等布局所需信息。 */
interface ArchitectureLayoutNode {
  node: LinkGraphNode;
  lane: ArchitectureLayoutLane;
  laneIndex: number;
  topologyRank: number;
  laneOrder: number;
  width: number;
  height: number;
}

/** 架构图布局入口的请求参数；额外支持切换"项目结构视图"分支布局。 */
export interface ArchitectureGraphLayoutOptions extends MeasuredLayoutRequest {
  projectStructureView?: boolean;
}

/** 生成边的去重键：source/target/handle 拼接，用于折叠重复连线。 */
function architectureLayoutEdgeKey(edge: LinkGraphEdge): string {
  return [
    edge.source,
    edge.target,
    edge.sourceHandle ?? "",
    edge.targetHandle ?? "",
  ].join("\u0000");
}

/**
 * 折叠同源同目标的重复边：架构图里同一对节点常有多条关系（调用/路由/实现），
 * 布局阶段会先按 source+target+handle 合并，避免堆叠遮挡。同时重新分配
 * layout 专用边 id，避免与原始边 id 冲突。
 */
function compactArchitectureLayoutEdges(edges: LinkGraphEdge[]): LinkGraphEdge[] {
  const edgeGroups = new Map<string, LinkGraphEdge>();
  edges.forEach((edge) => {
    const key = architectureLayoutEdgeKey(edge);
    if (!edgeGroups.has(key)) {
      edgeGroups.set(key, {
        ...edge,
        id: `architecture-layout-edge:${edge.source}->${edge.target}:${edgeGroups.size}`,
      });
    }
  });
  return [...edgeGroups.values()];
}

/**
 * 取节点所属泳道：默认 application，让没有显式泳道信息的节点仍能落入中部业务层。
 */
function architectureLayoutLane(node: MeasuredLayoutRequest["nodes"][number]): ArchitectureLayoutLane {
  return node.metadata?.["presentation.laneId"] ?? "application";
}

/** 泳道转换为顶部到底部的顺序索引，决定 y 方向排布。 */
function architectureLayoutLaneIndex(lane: ArchitectureLayoutLane): number {
  return architectureLaneSortValue(lane);
}

/**
 * 按节点 kind（模块/服务/资源 等）赋予优先级：粗粒度结构节点排在前面，
 * 让顶层架构元素先排出来，类/资源等细粒度排到后面。
 */
function architectureNodeKindPriority(node: MeasuredLayoutRequest["nodes"][number]): number {
  const nodeKind = node.metadata?.["architecture.node.kind"] ?? node.type;
  switch (nodeKind) {
    case "MODULE":
      return 0;
    case "LAYER":
      return 1;
    case "SERVICE":
      return 2;
    case "COMPONENT":
      return 3;
    case "PACKAGE":
      return 4;
    case "RESOURCE":
      return 5;
    case "LIBRARY":
    case "JDK":
      return 6;
    default:
      return 7;
  }
}

/** 按节点业务角色（API/数据/外部 等）排优先级：入口/接口在前，外部依赖在后。 */
function architectureNodeRolePriority(node: MeasuredLayoutRequest["nodes"][number]): number {
  switch (node.metadata?.["indexed.nodeRole"]) {
    case "API":
    case "ENTRY":
      return 0;
    case "SERVICE":
      return 1;
    case "DATA":
      return 2;
    case "CONFIG":
      return 3;
    case "RESOURCE":
      return 4;
    case "EXTERNAL":
      return 6;
    default:
      return 5;
  }
}

/**
 * 生成节点排序键：把"泳道序号 + 角色优先级 + 类型优先级 + 全限定名"拼成可比较的字符串，
 * 让同一泳道里语义相近的节点能聚在一起，肉眼上看更连贯。
 */
function architectureLayoutSortKey(node: MeasuredLayoutRequest["nodes"][number]): string {
  return [
    String(architectureLayoutLaneIndex(architectureLayoutLane(node))).padStart(2, "0"),
    String(architectureNodeRolePriority(node)).padStart(2, "0"),
    String(architectureNodeKindPriority(node)).padStart(2, "0"),
    node.metadata?.["architecture.qualifiedName"] ?? "",
    node.title,
    node.id,
  ].join("\u0000");
}

/**
 * BFS 计算从起始节点出发的跳数距离：用于确定相对锚点的拓扑层级。
 * 跳过已访问节点和图外节点，保证连通分量外节点不会被错误计入。
 */
function graphDistances(
  startNodeId: string,
  edgesByNode: Map<string, string[]>,
  nodeIds: Set<string>,
): Map<string, number> {
  const distances = new Map<string, number>([[startNodeId, 0]]);
  const queue = [startNodeId];
  for (let cursor = 0; cursor < queue.length; cursor += 1) {
    const nodeId = queue[cursor]!;
    const distance = distances.get(nodeId) ?? 0;
    (edgesByNode.get(nodeId) ?? []).forEach((nextNodeId) => {
      if (!nodeIds.has(nextNodeId) || distances.has(nextNodeId)) {
        return;
      }
      distances.set(nextNodeId, distance + 1);
      queue.push(nextNodeId);
    });
  }
  return distances;
}

/**
 * 计算每个节点相对于锚点（即问答目标）的拓扑层级：
 * - 锚点自身为 0
 * - 下游节点（被锚点调用）取正距离
 * - 上游节点（调用锚点）取负距离
 * 用于按"离锚点跳数"在 x 方向排布，让流向从左到右更直观。
 */
function architectureTopologyRanks(
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
  anchorNodeId: string | null | undefined,
): Map<string, number> {
  const nodeIds = new Set(nodes.map((node) => node.id));
  const outgoing = new Map<string, string[]>();
  const incoming = new Map<string, string[]>();
  edges.forEach((edge) => {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target) || edge.source === edge.target) {
      return;
    }
    outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge.target]);
    incoming.set(edge.target, [...(incoming.get(edge.target) ?? []), edge.source]);
  });
  const anchorId = anchorNodeId && nodeIds.has(anchorNodeId)
    ? anchorNodeId
    : null;
  if (!anchorId) {
    return new Map(nodes.map((node) => [node.id, 0]));
  }

  const downstream = graphDistances(anchorId, outgoing, nodeIds);
  const upstream = graphDistances(anchorId, incoming, nodeIds);
  return new Map(nodes.map((node) => {
    const downstreamDistance = downstream.get(node.id);
    const upstreamDistance = upstream.get(node.id);
    if (node.id === anchorId) {
      return [node.id, 0];
    }
    if (downstreamDistance != null && upstreamDistance != null) {
      return [
        node.id,
        downstreamDistance <= upstreamDistance ? downstreamDistance : -upstreamDistance,
      ];
    }
    if (downstreamDistance != null) {
      return [node.id, downstreamDistance];
    }
    if (upstreamDistance != null) {
      return [node.id, -upstreamDistance];
    }
    return [node.id, 0];
  }));
}

/**
 * 计算普通架构视图的边端点：从源节点右侧中点出发，到目标节点左侧中点，
 * 构成左右贯通的横向走线。
 */
function routeAttachmentPoints(
  source: ArchitectureLayoutNode,
  target: ArchitectureLayoutNode,
): { startPoint: GraphPosition; endPoint: GraphPosition } | null {
  if (!source.node.position || !target.node.position) {
    return null;
  }
  return {
    startPoint: {
      x: source.node.position.x + source.width,
      y: source.node.position.y + source.height / 2,
    },
    endPoint: {
      x: target.node.position.x,
      y: target.node.position.y + target.height / 2,
    },
  };
}

/**
 * 项目结构视图的边端点计算：根据两节点中心偏移自动选择走"右→左"或"下→上"。
 * 上下偏移较大时走纵向，否则走横向，匹配 band 内的多列网格布局。
 */
function structureRouteAttachmentPoints(
  source: ArchitectureLayoutNode,
  target: ArchitectureLayoutNode,
): { startPoint: GraphPosition; endPoint: GraphPosition; startSide: "right" | "bottom"; endSide: "left" | "top" } | null {
  if (!source.node.position || !target.node.position) {
    return null;
  }
  const sourceCenterX = source.node.position.x + source.width / 2;
  const sourceCenterY = source.node.position.y + source.height / 2;
  const targetCenterX = target.node.position.x + target.width / 2;
  const targetCenterY = target.node.position.y + target.height / 2;
  if (Math.abs(targetCenterY - sourceCenterY) > source.height * 0.7) {
    return {
      startPoint: {
        x: sourceCenterX,
        y: source.node.position.y + source.height,
      },
      endPoint: {
        x: targetCenterX,
        y: target.node.position.y,
      },
      startSide: "bottom",
      endSide: "top",
    };
  }
  return {
    startPoint: {
      x: source.node.position.x + source.width,
      y: sourceCenterY,
    },
    endPoint: {
      x: target.node.position.x,
      y: targetCenterY,
    },
    startSide: "right",
    endSide: "left",
  };
}

/** 把布局节点转成正交路由所需的矩形信息；位置缺失时返回 null 表示不参与路由。 */
function nodeRect(definition: ArchitectureLayoutNode): OrthogonalRect | null {
  if (!definition.node.position) {
    return null;
  }
  return {
    id: definition.node.id,
    x: definition.node.position.x,
    y: definition.node.position.y,
    width: definition.width,
    height: definition.height,
  };
}

/** 计算所有节点的外接矩形（minX/minY/maxX/maxY），用于布局后的视口自适应与调试埋点。 */
function architectureBounds(nodes: ArchitectureLayoutNode[]) {
  const rects = nodes.map(nodeRect).filter((rect): rect is OrthogonalRect => rect !== null);
  if (rects.length === 0) {
    return null;
  }
  const minX = Math.min(...rects.map((rect) => rect.x));
  const minY = Math.min(...rects.map((rect) => rect.y));
  const maxX = Math.max(...rects.map((rect) => rect.x + rect.width));
  const maxY = Math.max(...rects.map((rect) => rect.y + rect.height));
  return {
    minX,
    minY,
    maxX,
    maxY,
    width: maxX - minX,
    height: maxY - minY,
  };
}

/** 项目结构视图的 band 标识：把所有泳道合并成 4 个粗粒度分组。 */
type ProjectStructureBand = "entry" | "application" | "domain" | "data";

/** 单个 band 的布局规格：包含节点桶、网格列/行数、内容尺寸、最终位置。 */
interface ProjectStructureBandLayout {
  band: ProjectStructureBand;
  bucket: ArchitectureLayoutNode[];
  columnCount: number;
  rowCount: number;
  contentWidth: number;
  contentHeight: number;
  width: number;
  height: number;
  x: number;
  y: number;
}

/**
 * 把节点映射到项目结构视图的 band：
 * - entry/domain 原样保留
 * - data/resource/external 全部归入 data band（项目结构里都视为"底层"）
 * - 其余进入 application band
 */
function projectStructureBand(node: LinkGraphNode): ProjectStructureBand {
  const lane = architectureLayoutLane(node);
  if (lane === "entry") {
    return "entry";
  }
  if (lane === "domain") {
    return "domain";
  }
  if (lane === "data" || lane === "resource" || lane === "external") {
    return "data";
  }
  return "application";
}

/** band 在垂直方向上的顺序号，从上到下排列。 */
function projectStructureBandIndex(band: ProjectStructureBand): number {
  switch (band) {
    case "entry":
      return 0;
    case "application":
      return 1;
    case "domain":
      return 2;
    case "data":
      return 3;
  }
}

/**
 * 估算 band 内每行容纳多少列：
 * - 单节点用 1 列
 * - 否则用开方缩放，限制在 2-3 列之间，让 band 既不太宽也不会拉得很长。
 */
function projectStructureColumnCount(nodeCount: number): number {
  if (nodeCount <= 1) {
    return 1;
  }
  return Math.min(3, Math.max(2, Math.ceil(Math.sqrt(nodeCount * 1.35))));
}

/** 取节点排序键用于边排序，保证同端点边顺序稳定。 */
function edgeNodeOrder(nodeIndex: Map<string, ArchitectureLayoutNode>, nodeId: string): string {
  const node = nodeIndex.get(nodeId)?.node;
  return node ? architectureLayoutSortKey(node) : nodeId;
}

/** 生成项目结构视图的边排序键：按 source/target 的 lane 和节点排序键拼合，使同泳道边聚合。 */
function structureEdgeOrderKey(edge: LinkGraphEdge, nodeIndex: Map<string, ArchitectureLayoutNode>): string {
  return [
    String(nodeIndex.get(edge.source)?.laneIndex ?? 0).padStart(2, "0"),
    String(nodeIndex.get(edge.target)?.laneIndex ?? 0).padStart(2, "0"),
    edgeNodeOrder(nodeIndex, edge.source),
    edgeNodeOrder(nodeIndex, edge.target),
    edge.id,
  ].join("\u0000");
}

/**
 * 给同一端点上的多条边分配槽位序号（0..count-1），用于在端点宽度上把多条平行边错开。
 * 同端点边先按结构排序键排序，再依次编号，保证视觉稳定。
 */
function edgeSlots(
  edges: LinkGraphEdge[],
  nodeIndex: Map<string, ArchitectureLayoutNode>,
  nodeIdForEdge: (edge: LinkGraphEdge) => string,
): Map<string, { index: number; count: number }> {
  const grouped = new Map<string, LinkGraphEdge[]>();
  edges.forEach((edge) => {
    const nodeId = nodeIdForEdge(edge);
    grouped.set(nodeId, [...(grouped.get(nodeId) ?? []), edge]);
  });
  const slots = new Map<string, { index: number; count: number }>();
  grouped.forEach((groupEdges) => {
    const ordered = [...groupEdges].sort((left, right) =>
      structureEdgeOrderKey(left, nodeIndex).localeCompare(structureEdgeOrderKey(right, nodeIndex)),
    );
    ordered.forEach((edge, index) => {
      slots.set(architectureLayoutEdgeKey(edge), {
        index,
        count: ordered.length,
      });
    });
  });
  return slots;
}

/**
 * 把槽位序号转换为端点垂直/水平偏移量：
 * 单条边返回 0；多条边以 42px 为最大间距均分，使它们均匀分布在节点中心两侧。
 */
function slotOffset(slot: { index: number; count: number } | undefined, availableSize: number): number {
  if (!slot || slot.count <= 1) {
    return 0;
  }
  const spacing = Math.min(42, availableSize / (slot.count + 1));
  return Math.round((slot.index - (slot.count - 1) / 2) * spacing);
}

/**
 * 项目结构视图布局：把节点按 entry/application/domain/data 四个 band 自上而下排列，
 * 每个 band 内按估算的列数摆成网格，最后为每条边计算正交走线（含多边错开）。
 */
async function layoutProjectStructureGraphView({
  nodes,
  edges,
  sizeSnapshot,
  startedAt,
}: MeasuredLayoutRequest & { startedAt: number }) {
  const layoutNodes = [...nodes].sort((left, right) => architectureLayoutSortKey(left).localeCompare(architectureLayoutSortKey(right)));
  const bandBuckets = new Map<ProjectStructureBand, ArchitectureLayoutNode[]>();
  const layoutDefinitions = layoutNodes.map((node): ArchitectureLayoutNode => {
    const band = projectStructureBand(node);
    const bandIndex = projectStructureBandIndex(band);
    const measuredSize = resolveMeasuredNodeSize(node, sizeSnapshot, "ARCHITECTURE_GRAPH");
    const definition = {
      node,
      lane: band,
      laneIndex: bandIndex,
      topologyRank: bandIndex,
      laneOrder: 0,
      width: architectureGraphNodeCardWidth(),
      height: Math.min(measuredSize.height || ARCHITECTURE_DEFAULT_NODE_HEIGHT, ARCHITECTURE_DEFAULT_NODE_HEIGHT),
    };
    bandBuckets.set(band, [...(bandBuckets.get(band) ?? []), definition]);
    return definition;
  });
  const columnWidth = Math.max(...layoutDefinitions.map((definition) => definition.width), architectureGraphNodeCardWidth());
  const rowHeight = Math.max(...layoutDefinitions.map((definition) => definition.height), ARCHITECTURE_DEFAULT_NODE_HEIGHT);
  const bandLayouts: ProjectStructureBandLayout[] = [];
  let nextBandContentY = ARCHITECTURE_LAYOUT_ORIGIN.y;
  let nextLayoutRow = 0;
  (["entry", "application", "domain", "data"] as ProjectStructureBand[]).forEach((band) => {
    const bucket = [...(bandBuckets.get(band) ?? [])].sort((left, right) =>
      architectureLayoutSortKey(left.node).localeCompare(architectureLayoutSortKey(right.node)),
    );
    if (bucket.length === 0) {
      return;
    }
    const columnCount = projectStructureColumnCount(bucket.length);
    const rowCount = Math.ceil(bucket.length / columnCount);
    const contentWidth = columnCount * columnWidth + (columnCount - 1) * STRUCTURE_LAYOUT_COLUMN_GAP;
    const contentHeight = rowCount * rowHeight + (rowCount - 1) * STRUCTURE_LAYOUT_ROW_GAP;
    const bandLayout: ProjectStructureBandLayout = {
      band,
      bucket,
      columnCount,
      rowCount,
      contentWidth,
      contentHeight,
      width: contentWidth + STRUCTURE_LAYOUT_BAND_PADDING_X * 2,
      height: contentHeight + STRUCTURE_LAYOUT_BAND_PADDING_Y * 2 + STRUCTURE_LAYOUT_BAND_HEADER,
      x: ARCHITECTURE_LAYOUT_ORIGIN.x,
      y: nextBandContentY,
    };
    bandLayouts.push(bandLayout);
    nextBandContentY += bandLayout.height + STRUCTURE_LAYOUT_BAND_GAP;
  });
  const structureWidth = Math.max(...bandLayouts.map((bandLayout) => bandLayout.width), columnWidth + STRUCTURE_LAYOUT_BAND_PADDING_X * 2);
  bandLayouts.forEach((bandLayout) => {
    const bandStartRow = nextLayoutRow;
    const contentX = bandLayout.x + STRUCTURE_LAYOUT_BAND_PADDING_X + (structureWidth - bandLayout.width) / 2;
    const contentY = bandLayout.y + STRUCTURE_LAYOUT_BAND_HEADER + STRUCTURE_LAYOUT_BAND_PADDING_Y;
    bandLayout.bucket.forEach((definition, index) => {
      const row = Math.floor(index / bandLayout.columnCount);
      const column = index % bandLayout.columnCount;
      definition.laneOrder = index;
      const position = {
        x: Math.round(contentX + column * (columnWidth + STRUCTURE_LAYOUT_COLUMN_GAP)),
        y: Math.round(contentY + row * (rowHeight + STRUCTURE_LAYOUT_ROW_GAP)),
      };
      definition.node = {
        ...definition.node,
        position,
        metadata: {
          ...(definition.node.metadata ?? {}),
          "architecture.layoutMode": "PROJECT_STRUCTURE",
          "architecture.layoutBand": bandLayout.band,
          "architecture.layoutBandX": String(Math.round(bandLayout.x)),
          "architecture.layoutBandY": String(Math.round(bandLayout.y)),
          "architecture.layoutBandWidth": String(Math.round(structureWidth)),
          "architecture.layoutBandHeight": String(Math.round(bandLayout.height)),
          "architecture.layoutLane": definition.lane,
          "architecture.layoutLaneIndex": String(definition.laneIndex),
          "architecture.layoutTopologyRank": String(definition.topologyRank),
          "architecture.layoutLaneOrder": String(index),
          "architecture.layoutRow": String(bandStartRow + row),
          "architecture.layoutColumn": String(column),
          "architecture.layoutNodeWidth": String(definition.width),
          "architecture.layoutNodeHeight": String(definition.height),
          "ui.x": String(position.x),
          "ui.y": String(position.y),
          "layout.mode": "ARCHITECTURE_GRAPH",
        },
      };
    });
    nextLayoutRow += bandLayout.rowCount;
  });

  const layoutEdges = compactArchitectureLayoutEdges(edges);
  const nodeIndex = new Map(layoutDefinitions.map((definition) => [definition.node.id, definition]));
  const obstacleRects = layoutDefinitions.map(nodeRect).filter((rect): rect is OrthogonalRect => rect !== null);
  const sourceSlots = edgeSlots(layoutEdges, nodeIndex, (edge) => edge.source);
  const targetSlots = edgeSlots(layoutEdges, nodeIndex, (edge) => edge.target);
  const routeByLayoutEdgeKey = new Map(
    layoutEdges.map((edge) => {
      const source = nodeIndex.get(edge.source);
      const target = nodeIndex.get(edge.target);
      const attachment = source && target ? structureRouteAttachmentPoints(source, target) : null;
      if (!source || !target || !attachment) {
        return [architectureLayoutEdgeKey(edge), undefined] as const;
      }
      return [
        architectureLayoutEdgeKey(edge),
        buildOrthogonalEdgeRoute({
          startPoint: attachment.startSide === "bottom"
            ? {
              x: attachment.startPoint.x + slotOffset(sourceSlots.get(architectureLayoutEdgeKey(edge)), source.width),
              y: attachment.startPoint.y,
            }
            : {
              x: attachment.startPoint.x,
              y: attachment.startPoint.y + slotOffset(sourceSlots.get(architectureLayoutEdgeKey(edge)), source.height),
            },
          startSide: attachment.startSide,
          startRect: nodeRect(source) ?? {
            id: source.node.id,
            x: attachment.startPoint.x - source.width / 2,
            y: attachment.startPoint.y - source.height,
            width: source.width,
            height: source.height,
          },
          endPoint: attachment.endSide === "top"
            ? {
              x: attachment.endPoint.x + slotOffset(targetSlots.get(architectureLayoutEdgeKey(edge)), target.width),
              y: attachment.endPoint.y,
            }
            : {
              x: attachment.endPoint.x,
              y: attachment.endPoint.y + slotOffset(targetSlots.get(architectureLayoutEdgeKey(edge)), target.height),
            },
          endSide: attachment.endSide,
          endRect: nodeRect(target) ?? {
            id: target.node.id,
            x: attachment.endPoint.x - target.width / 2,
            y: attachment.endPoint.y,
            width: target.width,
            height: target.height,
          },
          obstacleRects,
        }),
      ] as const;
    }),
  );
  traceLinkGraph("architectureLayout.projectStructure.complete", {
    nodeCount: nodes.length,
    edgeCount: layoutEdges.length,
    bandCounts: Object.fromEntries([...bandBuckets.entries()].map(([band, bucket]) => [band, bucket.length])),
    bandLayouts: bandLayouts.map((bandLayout) => ({
      band: bandLayout.band,
      columns: bandLayout.columnCount,
      rows: bandLayout.rowCount,
      bounds: {
        x: bandLayout.x,
        y: bandLayout.y,
        width: structureWidth,
        height: bandLayout.height,
      },
    })),
    bounds: architectureBounds(layoutDefinitions),
    totalDurationMs: measureDuration(startedAt),
  });
  return {
    nodes: layoutDefinitions.map((definition) => definition.node),
    edges: edges.map((edge) => ({
      ...edge,
      route: routeByLayoutEdgeKey.get(architectureLayoutEdgeKey(edge)),
    })),
  };
}

/**
 * 架构图主布局入口：
 * - 项目结构视图直接走 band 布局
 * - 默认按"泳道 × 拓扑层级"组织：同一泳道纵向排列，不同拓扑层级横向推进，
 *   让从锚点出发的依赖关系在 x 方向逐层展开。同时为每条边生成正交走线。
 */
export async function layoutArchitectureGraphView({
  nodes,
  edges,
  anchorNodeId,
  sizeSnapshot,
  projectStructureView = false,
}: ArchitectureGraphLayoutOptions) {
  const startedAt = measureStart();
  if (projectStructureView) {
    return layoutProjectStructureGraphView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId,
      sizeSnapshot,
      reason: "graph",
      startedAt,
    });
  }
  const layoutNodes = [...nodes].sort((left, right) => architectureLayoutSortKey(left).localeCompare(architectureLayoutSortKey(right)));
  const topologyRanks = architectureTopologyRanks(layoutNodes, edges, anchorNodeId);
  const laneBuckets = new Map<ArchitectureLayoutLane, Map<number, ArchitectureLayoutNode[]>>();
  const layoutDefinitions = layoutNodes.map((node): ArchitectureLayoutNode => {
    const lane = architectureLayoutLane(node);
    const laneIndex = architectureLayoutLaneIndex(lane);
    const topologyRank = topologyRanks.get(node.id) ?? 0;
    const measuredSize = resolveMeasuredNodeSize(node, sizeSnapshot, "ARCHITECTURE_GRAPH");
    const definition = {
      node,
      lane,
      laneIndex,
      topologyRank,
      laneOrder: 0,
      width: measuredSize.width || architectureGraphNodeCardWidth(),
      height: measuredSize.height || ARCHITECTURE_DEFAULT_NODE_HEIGHT,
    };
    const laneBucket = laneBuckets.get(lane) ?? new Map<number, ArchitectureLayoutNode[]>();
    const rankBucket = laneBucket.get(topologyRank) ?? [];
    rankBucket.push(definition);
    laneBucket.set(topologyRank, rankBucket);
    laneBuckets.set(lane, laneBucket);
    return definition;
  });
  const presentLanes = [...laneBuckets.keys()].sort((left, right) =>
    architectureLayoutLaneIndex(left) - architectureLayoutLaneIndex(right) || left.localeCompare(right),
  );
  let nextX = ARCHITECTURE_LAYOUT_ORIGIN.x;
  presentLanes.forEach((lane) => {
    const laneBucket = laneBuckets.get(lane) ?? new Map<number, ArchitectureLayoutNode[]>();
    const ranks = [...laneBucket.keys()].sort((left, right) => left - right);
    ranks.forEach((rank) => {
      const bucket = laneBucket.get(rank) ?? [];
      const columnWidth = Math.max(...bucket.map((item) => item.width), architectureGraphNodeCardWidth());
      bucket.forEach((definition, index) => {
        definition.laneOrder = index;
        const position = {
          x: Math.round(nextX),
          y: Math.round(ARCHITECTURE_LAYOUT_ORIGIN.y + index * (definition.height + ARCHITECTURE_ROW_GAP)),
        };
        definition.node = {
          ...definition.node,
          position,
          metadata: {
            ...(definition.node.metadata ?? {}),
            "architecture.layoutLane": definition.lane,
            "architecture.layoutLaneIndex": String(definition.laneIndex),
            "architecture.layoutTopologyRank": String(definition.topologyRank),
            "architecture.layoutLaneOrder": String(index),
            "ui.x": String(position.x),
            "ui.y": String(position.y),
            "layout.mode": "ARCHITECTURE_GRAPH",
          },
        };
      });
      nextX += columnWidth + ARCHITECTURE_COLUMN_GAP;
    });
  });

  const layoutEdges = compactArchitectureLayoutEdges(edges);
  const nodeIndex = new Map(layoutDefinitions.map((definition) => [definition.node.id, definition]));
  const obstacleRects = layoutDefinitions.map(nodeRect).filter((rect): rect is OrthogonalRect => rect !== null);
  const routeByLayoutEdgeKey = new Map(
    layoutEdges.map((edge) => {
      const source = nodeIndex.get(edge.source);
      const target = nodeIndex.get(edge.target);
      const attachment = source && target ? routeAttachmentPoints(source, target) : null;
      if (!source || !target || !attachment) {
        return [architectureLayoutEdgeKey(edge), undefined] as const;
      }
      return [
        architectureLayoutEdgeKey(edge),
        buildOrthogonalEdgeRoute({
          startPoint: attachment.startPoint,
          startSide: "right",
          startRect: nodeRect(source) ?? {
            id: source.node.id,
            x: attachment.startPoint.x - source.width,
            y: attachment.startPoint.y - source.height / 2,
            width: source.width,
            height: source.height,
          },
          endPoint: attachment.endPoint,
          endSide: "left",
          endRect: nodeRect(target) ?? {
            id: target.node.id,
            x: attachment.endPoint.x,
            y: attachment.endPoint.y - target.height / 2,
            width: target.width,
            height: target.height,
          },
          obstacleRects,
        }),
      ] as const;
    }),
  );
  traceLinkGraph("architectureLayout.complete", {
    nodeCount: nodes.length,
    edgeCount: layoutEdges.length,
    originalEdgeCount: edges.length,
    laneCounts: Object.fromEntries(presentLanes.map((lane) => [
      lane,
      [...(laneBuckets.get(lane)?.values() ?? [])].reduce((total, bucket) => total + bucket.length, 0),
    ])),
    laneColumns: Object.fromEntries(presentLanes.map((lane) => [
      lane,
      [...(laneBuckets.get(lane)?.keys() ?? [])].sort((left, right) => left - right),
    ])),
    bounds: architectureBounds(layoutDefinitions),
    totalDurationMs: measureDuration(startedAt),
  });
  return {
    nodes: layoutDefinitions.map((definition) => definition.node),
    edges: edges.map((edge) => ({
      ...edge,
      route: routeByLayoutEdgeKey.get(architectureLayoutEdgeKey(edge)),
    })),
  };
}

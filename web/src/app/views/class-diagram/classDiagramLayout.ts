import type { LayoutOptions } from "elkjs/lib/elk-api";
import type { LinkGraphEdge, LinkGraphNode } from "../../types";
import { executeElkLayout } from "../../reactflow/elkGraph";
import type { MeasuredLayoutRequest } from "../../reactflow/useMeasuredLayout";
import {
  BOTTOM_PORT,
  LEFT_PORT,
  RIGHT_PORT,
  SOURCE_LEFT_PORT,
  SOURCE_TOP_PORT,
  TARGET_BOTTOM_PORT,
  TARGET_RIGHT_PORT,
  TOP_PORT,
  measuredNodeSize,
} from "./classDiagramLayoutModel";
import { ClassDiagramTopologyBuilder } from "./classDiagramTopology";
import {
  ClassDiagramPlacementEngine,
  type ClassDiagramPlacement,
} from "./classDiagramPlacement";
import { ClassDiagramRoutingEngine } from "./classDiagramRouting";
import { ClassDiagramReadabilityScorer } from "./classDiagramReadability";

/**
 * ELK 分层布局算法的参数预设：从左向右分层、正交边走线、固定端口方向，
 * 同时强制按模型顺序排布节点以减少交叉，整体保证类图层级清晰、读图路径稳定。
 */
const CLASS_DIAGRAM_LAYOUT_OPTIONS: LayoutOptions = {
  "elk.algorithm": "layered",
  "org.eclipse.elk.direction": "RIGHT",
  "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
  "org.eclipse.elk.layered.nodePlacement.strategy": "NETWORK_SIMPLEX",
  "org.eclipse.elk.layered.spacing.nodeNodeBetweenLayers": "220",
  "org.eclipse.elk.layered.spacing.edgeNodeBetweenLayers": "120",
  "org.eclipse.elk.spacing.nodeNode": "104",
  "org.eclipse.elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
  "org.eclipse.elk.layered.crossingMinimization.forceNodeModelOrder": "true",
  "org.eclipse.elk.portConstraints": "FIXED_SIDE",
  "org.eclipse.elk.layered.mergeEdges": "false",
};

/**
 * 类图布局流水线，把拓扑分析、节点摆放、边路由和可读性评分组合在一起。
 * 各引擎之间通过共享 readabilityScorer 形成闭环，便于评分时复用同一套指标。
 */
interface ClassDiagramPipeline {
  topologyBuilder: ClassDiagramTopologyBuilder;
  placementEngine: ClassDiagramPlacementEngine;
  readabilityScorer: ClassDiagramReadabilityScorer;
  routingEngine: ClassDiagramRoutingEngine;
}

/**
 * 构造一条新的类图布局流水线实例。
 * readabilityScorer 被同时注入到 routingEngine，使路由阶段可以利用评分反馈调整走线。
 */
function createClassDiagramPipeline(): ClassDiagramPipeline {
  const readabilityScorer = new ClassDiagramReadabilityScorer();
  return {
    topologyBuilder: new ClassDiagramTopologyBuilder(),
    placementEngine: new ClassDiagramPlacementEngine(),
    readabilityScorer,
    routingEngine: new ClassDiagramRoutingEngine(readabilityScorer),
  };
}

/**
 * 读取节点的显式坐标，优先使用 position 字段，其次回退到 metadata 中的 ui.x/ui.y。
 * 任一来源都不可用时返回 null，表示该节点没有用户指定的固定位置。
 */
function explicitPosition(node: LinkGraphNode) {
  if (node.position) {
    return node.position;
  }
  const x = Number(node.metadata?.["ui.x"]);
  const y = Number(node.metadata?.["ui.y"]);
  return Number.isFinite(x) && Number.isFinite(y) ? { x, y } : null;
}

/**
 * 判断当前布局请求是否应原样保留所有节点的显式坐标。
 * 仅当请求触发原因是位置调整（position），且全部节点都提供了有效坐标时才成立，
 * 避免部分节点缺坐标导致整体错位。
 */
function shouldPreserveExplicitPositions(request: MeasuredLayoutRequest): boolean {
  return request.reason === "position"
    && request.nodes.length > 0
    && request.nodes.every((node) => explicitPosition(node) !== null);
}

/**
 * 在已有摆放结果上覆盖用户指定的显式坐标，同时把坐标写回 metadata，
 * 保证 UI 回写到后端时仍能取到一致的位置信息。返回新的 placement 对象。
 */
function withPreservedExplicitPositions(
  placement: ClassDiagramPlacement,
  requestNodes: LinkGraphNode[],
): ClassDiagramPlacement {
  // 收集所有带有显式坐标的节点，便于后续按 ID 查询
  const explicitPositionsById = new Map(
    requestNodes
      .map((node) => [node.id, explicitPosition(node)] as const)
      .filter((entry): entry is readonly [string, NonNullable<ReturnType<typeof explicitPosition>>] => entry[1] !== null),
  );
  const nodes = placement.nodes.map((node) => {
    const position = explicitPositionsById.get(node.id);
    if (!position) {
      return node;
    }
    return {
      ...node,
      position,
      metadata: {
        ...(node.metadata ?? {}),
        "ui.x": String(position.x),
        "ui.y": String(position.y),
      },
    };
  });
  return {
    ...placement,
    nodes,
    nodeIndex: new Map(nodes.map((node) => [node.id, node])),
  };
}

/**
 * 走自定义手写布局流水线：拓扑分析 → 节点摆放（可选保留显式位置）→ 边路由 → 可读性评分。
 * 拓扑构建失败时直接回退原始输入，避免在空数据上继续做无意义的计算。
 */
function runManualClassDiagramPipeline(
  request: MeasuredLayoutRequest,
  pipeline: ClassDiagramPipeline,
): { nodes: LinkGraphNode[]; edges: LinkGraphEdge[] } {
  const topology = pipeline.topologyBuilder.build(request.nodes, request.edges, request.anchorNodeId);
  if (!topology) {
    return { nodes: request.nodes, edges: request.edges };
  }
  const placement = shouldPreserveExplicitPositions(request)
    ? withPreservedExplicitPositions(
        pipeline.placementEngine.place(topology, request.sizeSnapshot),
        request.nodes,
      )
    : pipeline.placementEngine.place(topology, request.sizeSnapshot);
  const edges = pipeline.routingEngine.routeClassDiagramEdges(topology, placement, request.sizeSnapshot);
  const report = pipeline.readabilityScorer.scoreClassDiagramReadability(
    { nodes: placement.nodes, edges },
    request.sizeSnapshot,
  );
  return report.acceptedLayout;
}

/**
 * 走 ELK 自动布局流水线：把节点和端口（四向八端口）交给 ELK 计算，
 * 再由路由引擎修复端口坐标、保留显式走线，最后做可读性评分筛选。
 */
async function runElkClassDiagramPipeline(
  request: MeasuredLayoutRequest,
  pipeline: ClassDiagramPipeline,
): Promise<{ nodes: LinkGraphNode[]; edges: LinkGraphEdge[] }> {
  const elkLayout = await executeElkLayout({
    mode: "CLASS_DIAGRAM",
    layoutOptions: CLASS_DIAGRAM_LAYOUT_OPTIONS,
    nodes: request.nodes.map((node) => ({
      node,
      ...measuredNodeSize(node, request.sizeSnapshot, node.id === request.anchorNodeId ? "ANCHOR" : undefined),
      ports: [
        { id: TOP_PORT, side: "NORTH" },
        { id: SOURCE_TOP_PORT, side: "NORTH" },
        { id: RIGHT_PORT, side: "EAST" },
        { id: TARGET_RIGHT_PORT, side: "EAST" },
        { id: BOTTOM_PORT, side: "SOUTH" },
        { id: TARGET_BOTTOM_PORT, side: "SOUTH" },
        { id: LEFT_PORT, side: "WEST" },
        { id: SOURCE_LEFT_PORT, side: "WEST" },
      ],
    })),
    edges: request.edges.map((edge) => ({
      edge: {
        ...edge,
        metadata: {
          ...(edge.metadata ?? {}),
          "layout.route": "class-diagram-elk",
          "layout.labelPlacement": edge.metadata?.["layout.labelPlacement"] ?? "target-stub",
        },
      },
    })),
  });
  const repairedLayout = pipeline.routingEngine.repairAndPreserveClassDiagramRoutes(elkLayout, request.sizeSnapshot);
  const report = pipeline.readabilityScorer.scoreClassDiagramReadability(repairedLayout, request.sizeSnapshot);
  return report.acceptedLayout;
}

/**
 * 类图视图布局对外入口。
 * 节点数较少或仅为位置调整时使用手写流水线（响应更快、可控性强）；
 * 节点数超过阈值（32）时切换到 ELK 自动布局以获得更好的整体分层效果。
 */
export async function layoutClassDiagramView(request: MeasuredLayoutRequest) {
  const pipeline = createClassDiagramPipeline();
  if (request.nodes.length <= 32 || request.reason === "position") {
    return runManualClassDiagramPipeline(request, pipeline);
  }
  return runElkClassDiagramPipeline(request, pipeline);
}

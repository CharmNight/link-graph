import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import type { LinkGraphEdge, LinkGraphNode } from "../../types";
import {
  BOTTOM_PORT,
  EDGE_CROSSING_REPAIR_NODE_CLEARANCE,
  LEFT_PORT,
  RIGHT_PORT,
  SOURCE_LEFT_PORT,
  SOURCE_TOP_PORT,
  TARGET_BOTTOM_PORT,
  TARGET_RIGHT_PORT,
  TOP_PORT,
  dataOuterSide,
  dataOuterSideAwayFromTarget,
  dataRow,
  edgeSortKey,
  laneColumn,
  laneOf,
  nodeBounds,
  nodeBottom,
  nodeCenterX,
  nodeCenterY,
  nodeLeft,
  nodeRight,
  nodeTop,
  portWithSlot,
  sideFanoutSlot,
  sourceSideForPort,
  type RouteChannel,
} from "./classDiagramLayoutModel";
import {
  classDiagramRelationKind,
  isClassDiagramHierarchyRelation,
  isClassDiagramRoutedStructuralRelation,
} from "./classDiagramRelations";

/** 端点方向的联合类型，来源于 sourceSideForPort 的返回值（上/下/左/右）。 */
type EndpointSide = ReturnType<typeof sourceSideForPort>;

/**
 * 生成节点上某条边在其邻接关系中的稳定排序键。
 * 键的前缀为对端节点的中心 Y（补零 8 位），保证同一方向上的连线按目标位置排开，
 * 再叠加关系种类与边本身的排序键，避免并列时出现歧义。
 */
export function relationEndpointOrderKey(
  edge: LinkGraphEdge,
  nodeId: string,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): string {
  const peerId = edge.source === nodeId ? edge.target : edge.source;
  const peer = nodeIndex.get(peerId);
  const peerCenter = peer ? nodeCenterY(peer, sizeSnapshot) : 0;
  return `${Math.round(peerCenter).toString().padStart(8, "0")}|${classDiagramRelationKind(edge)}|${edgeSortKey(edge)}`;
}

/**
 * 计算当前边在其所在端口侧（同侧、同节点）的兄弟边集合中的扇出槽位号。
 * 兄弟边按对端 Y 中心排序后均匀分布于节点该侧，避免端口重叠。
 *
 * 性能：旧实现用 `localeCompare` 对零填充字符串排序，每对比较都要做字符串字典序比较；
 * 新实现预计算 peer center numeric，sort 用 numeric compare，减少分配与字典序比较开销。
 */
export function relationFanoutSlot(
  edge: LinkGraphEdge,
  nodeId: string,
  edgeIndex: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  port: string,
): number {
  const side = sourceSideForPort(port);
  const siblings = (edgeIndex.get(nodeId) ?? [edge])
    .filter((candidate) => nodeIndex.has(candidate.source) && nodeIndex.has(candidate.target))
    .filter((candidate) => relationEndpointSide(candidate, nodeId, nodeIndex, sizeSnapshot) === side)
    .map((candidate) => {
      // 前面 filter 已确保 source/target 都在 nodeIndex 中，这里用 `!` 告知 TS
      const peer = nodeIndex.get(candidate.source === nodeId ? candidate.target : candidate.source)!;
      return {
        candidate,
        // 预计算 peer center numeric 作为 sort key，避免 sort 回调里重复调用 nodeCenterY
        peerCenterY: nodeCenterY(peer, sizeSnapshot),
        orderKey: relationEndpointOrderKey(candidate, nodeId, nodeIndex, sizeSnapshot),
      };
    })
    .sort((left, right) => {
      // 主要按 Y 中心数值升序；同 Y 时退到 orderKey 字典序，保持稳定排序。
      if (left.peerCenterY !== right.peerCenterY) {
        return left.peerCenterY - right.peerCenterY;
      }
      return left.orderKey.localeCompare(right.orderKey);
    })
    .map((entry) => entry.candidate);
  const index = Math.max(0, siblings.findIndex((candidate) => candidate.id === edge.id));
  return sideFanoutSlot(index, siblings.length);
}

/**
 * 计算当前边在其所在节点的非层级兄弟边集合中的通道（第几条/共几条），
 * 用于在两条泳道之间的多通道走廊上均匀分布连线。
 */
export function relationFanoutChannel(
  edge: LinkGraphEdge,
  nodeId: string,
  edgeIndex: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): RouteChannel {
  const siblings = (edgeIndex.get(nodeId) ?? [edge])
    .filter((candidate) => nodeIndex.has(candidate.source) && nodeIndex.has(candidate.target))
    .filter((candidate) => !isClassDiagramHierarchyRelation(candidate))
    .sort((left, right) =>
      relationEndpointOrderKey(left, nodeId, nodeIndex, sizeSnapshot)
        .localeCompare(relationEndpointOrderKey(right, nodeId, nodeIndex, sizeSnapshot)),
    );
  const index = Math.max(0, siblings.findIndex((candidate) => candidate.id === edge.id));
  return {
    index,
    count: Math.max(1, siblings.length),
  };
}

/** 纵向连线时，根据源/目标 Y 中心决定源节点应使用的方向（源在下方走顶部，否则走底部）。 */
function verticalSourceSideForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): EndpointSide {
  return nodeCenterY(source, sizeSnapshot) > nodeCenterY(target, sizeSnapshot) ? "top" : "bottom";
}

/** 纵向连线时，根据源/目标 Y 中心决定目标节点应使用的方向（与源侧相反）。 */
function verticalTargetSideForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): EndpointSide {
  return nodeCenterY(source, sizeSnapshot) > nodeCenterY(target, sizeSnapshot) ? "bottom" : "top";
}

/** 横向连线时，根据源/目标 X 中心决定源节点应使用的方向（源在右方走左边，否则走右边）。 */
function horizontalSourceSideForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): EndpointSide {
  return nodeCenterX(source, sizeSnapshot) > nodeCenterX(target, sizeSnapshot) ? "left" : "right";
}

/** 横向连线时，根据源/目标 X 中心决定目标节点应使用的方向（与源侧相反）。 */
function horizontalTargetSideForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): EndpointSide {
  return nodeCenterX(source, sizeSnapshot) > nodeCenterX(target, sizeSnapshot) ? "right" : "left";
}

/** 计算两节点水平方向的重叠像素数（无重叠返回 0），用于判断是否视觉同列。 */
function nodeHorizontalOverlap(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): number {
  return Math.max(0, Math.min(nodeRight(source, sizeSnapshot), nodeRight(target, sizeSnapshot)) - Math.max(nodeLeft(source), nodeLeft(target)));
}

/** 计算两节点的纵向净间距（取上方节点底与下方节点顶之差），用于判断是否可走纵向连线。 */
function nodeVerticalGap(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): number {
  const upperBottom = Math.min(nodeBottom(source, sizeSnapshot), nodeBottom(target, sizeSnapshot));
  const lowerTop = Math.max(nodeTop(source), nodeTop(target));
  return Math.max(0, lowerTop - upperBottom);
}

/**
 * 判断两节点是否在视觉上处于同一列：同列号且中心距或重叠比例满足阈值。
 * 这是后续是否启用纵向堆叠端口的关键依据。
 */
function visuallySameColumn(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): boolean {
  if (laneColumn(source) !== laneColumn(target)) {
    return false;
  }
  const sourceWidth = nodeRight(source, sizeSnapshot) - nodeLeft(source);
  const targetWidth = nodeRight(target, sizeSnapshot) - nodeLeft(target);
  const narrowWidth = Math.max(1, Math.min(sourceWidth, targetWidth));
  const centerDelta = Math.abs(nodeCenterX(source, sizeSnapshot) - nodeCenterX(target, sizeSnapshot));
  const overlap = nodeHorizontalOverlap(source, target, sizeSnapshot);
  return centerDelta <= Math.max(48, narrowWidth * 0.35) || overlap >= narrowWidth * 0.45;
}

/** 判断两节点是否适合使用纵向（顶/底）端口连线：需视觉同列且纵向间距足够。 */
export function shouldUseVerticalStackPorts(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): boolean {
  if (!visuallySameColumn(source, target, sizeSnapshot)) {
    return false;
  }
  return nodeVerticalGap(source, target, sizeSnapshot) >= 24
    || Math.abs(nodeCenterY(source, sizeSnapshot) - nodeCenterY(target, sizeSnapshot)) >= 80;
}

/** 纵向连线时返回源节点端口名：源在上方走顶端口，否则走底端口。 */
function verticalSourcePortForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): string {
  return verticalSourceSideForRoute(source, target, sizeSnapshot) === "top" ? SOURCE_TOP_PORT : BOTTOM_PORT;
}

/** 纵向连线时返回目标节点端口名：目标侧方向决定使用顶/底端口。 */
function verticalTargetPortForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): string {
  return verticalTargetSideForRoute(source, target, sizeSnapshot) === "bottom" ? TARGET_BOTTOM_PORT : TOP_PORT;
}

/** 横向连线时返回源节点端口名（含扇出槽位）：左/右端口附加兄弟边扇出的槽位号。 */
function horizontalSourcePortForRoute(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexBySource: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
): string {
  const port = horizontalSourceSideForRoute(source, target, sizeSnapshot) === "left" ? SOURCE_LEFT_PORT : RIGHT_PORT;
  return portWithSlot(port, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, port));
}

/** 横向连线时返回目标节点端口名（含扇出槽位）：左/右端口附加兄弟边扇出的槽位号。 */
function horizontalTargetPortForRoute(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexByTarget: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
): string {
  const port = horizontalTargetSideForRoute(source, target, sizeSnapshot) === "right" ? TARGET_RIGHT_PORT : LEFT_PORT;
  return portWithSlot(port, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, port));
}

/**
 * 推导某条边相对于指定节点（源/目标）应使用的端点方向。
 * 内部委托给 preferredSourceSide / preferredTargetSide；无法判断时返回 null。
 */
function relationEndpointSide(
  edge: LinkGraphEdge,
  nodeId: string,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): EndpointSide | null {
  const source = nodeIndex.get(edge.source);
  const target = nodeIndex.get(edge.target);
  if (!source?.position || !target?.position) {
    return null;
  }
  if (edge.source === nodeId) {
    return preferredSourceSide(edge, source, target, nodeIndex, sizeSnapshot);
  }
  if (edge.target === nodeId) {
    return preferredTargetSide(edge, source, target, nodeIndex, sizeSnapshot);
  }
  return null;
}

/**
 * 推导源节点的首选出边方向。
 * 按关系类型分层决策：层级关系按视觉同列选择纵向/横向；结构关系按泳道组合决定四向；
 * 同泳道同列内优先纵向；其余走横向并按方向给出左/右。
 */
function preferredSourceSide(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): EndpointSide {
  if (isClassDiagramHierarchyRelation(edge)) {
    return shouldUseVerticalStackPorts(source, target, sizeSnapshot)
      ? verticalSourceSideForRoute(source, target, sizeSnapshot)
      : horizontalSourceSideForRoute(source, target, sizeSnapshot);
  }
  if (laneOf(source) === laneOf(target) && laneOf(source) !== "ANCHOR" && laneColumn(source) === laneColumn(target)) {
    return nodeCenterY(source, sizeSnapshot) <= nodeCenterY(target, sizeSnapshot) ? "bottom" : "top";
  }
  if (isClassDiagramRoutedStructuralRelation(edge)) {
    if (isCrossLaneSecondaryStructuralEdge(edge, source, target)) {
      return bottomApproachClear(source, nodeIndex, sizeSnapshot) ? "bottom" : "left";
    }
    if (laneOf(source) !== "DATA" && laneOf(target) !== "DATA") {
      if (laneOf(source) === laneOf(target) && laneColumn(source) === laneColumn(target)) {
        return source.position!.y > target.position!.y ? "top" : "bottom";
      }
      return source.position!.x > target.position!.x ? "left" : "right";
    }
    if (laneOf(source) !== "DATA" && laneOf(target) === "DATA") {
      return target.position!.x >= source.position!.x ? "right" : "left";
    }
    if (laneOf(source) === "DATA" && laneOf(target) !== "DATA") {
      return dataOuterSideAwayFromTarget(source, target);
    }
    return dataOuterSide(source);
  }
  return source.position!.x > target.position!.x ? "left" : "right";
}

/**
 * 推导目标节点的首选入边方向。
 * 与 preferredSourceSide 对称：按关系类型分层决策，并针对跨泳道次级结构边做特殊处理。
 */
function preferredTargetSide(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): EndpointSide {
  if (isClassDiagramHierarchyRelation(edge)) {
    return shouldUseVerticalStackPorts(source, target, sizeSnapshot)
      ? verticalTargetSideForRoute(source, target, sizeSnapshot)
      : horizontalTargetSideForRoute(source, target, sizeSnapshot);
  }
  if (laneOf(source) === laneOf(target) && laneOf(source) !== "ANCHOR" && laneColumn(source) === laneColumn(target)) {
    return nodeCenterY(source, sizeSnapshot) <= nodeCenterY(target, sizeSnapshot) ? "top" : "bottom";
  }
  if (isClassDiagramRoutedStructuralRelation(edge)) {
    if (isCrossLaneSecondaryStructuralEdge(edge, source, target)) {
      return bottomApproachClear(target, nodeIndex, sizeSnapshot) ? "bottom" : "right";
    }
    if (laneOf(source) !== "DATA" && laneOf(target) !== "DATA") {
      if (laneOf(source) === laneOf(target) && laneColumn(source) === laneColumn(target)) {
        return source.position!.y > target.position!.y ? "bottom" : "top";
      }
      return source.position!.x > target.position!.x ? "right" : "left";
    }
    if (laneOf(target) === "DATA" && laneOf(source) !== "DATA") {
      return dataRow(target) === 0 ? "top" : dataOuterSide(target);
    }
    if (laneOf(source) === "DATA" && laneOf(target) !== "DATA") {
      return target.position!.x >= source.position!.x ? "right" : "left";
    }
    return dataOuterSide(target);
  }
  return source.position!.x > target.position!.x ? "right" : "left";
}

/**
 * 根据关系类型与泳道组合决定源节点的具体端口名（含扇出槽位）。
 * 涵盖层级关系、同泳道同列、结构关系（含数据泳道）以及锚点/出向等场景。
 */
export function sourcePortForRoute(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexBySource: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
) {
  if (isClassDiagramHierarchyRelation(edge)) {
    if (shouldUseVerticalStackPorts(source, target, sizeSnapshot)) {
      return verticalSourcePortForRoute(source, target, sizeSnapshot);
    }
    return horizontalSourcePortForRoute(edge, source, target, sizeSnapshot, edgeIndexBySource, nodeIndex);
  }
  if (laneOf(source) === laneOf(target) && laneOf(source) !== "ANCHOR" && laneColumn(source) === laneColumn(target)) {
    return nodeCenterY(source, sizeSnapshot) <= nodeCenterY(target, sizeSnapshot) ? BOTTOM_PORT : SOURCE_TOP_PORT;
  }
  if (isClassDiagramRoutedStructuralRelation(edge)) {
    if (laneOf(source) !== "DATA" && laneOf(target) !== "DATA") {
      if (laneOf(source) === "ANCHOR" && laneOf(target) === "OUTGOING") {
        return portWithSlot(RIGHT_PORT, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, RIGHT_PORT));
      }
      if (laneOf(source) === laneOf(target) && laneColumn(source) === laneColumn(target)) {
        return source.position!.y > target.position!.y ? SOURCE_TOP_PORT : BOTTOM_PORT;
      }
      return source.position!.x > target.position!.x
        ? SOURCE_LEFT_PORT
        : portWithSlot(RIGHT_PORT, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, RIGHT_PORT));
    }
    if (laneOf(source) !== "DATA" && laneOf(target) === "DATA") {
      return target.position!.x >= source.position!.x ? RIGHT_PORT : SOURCE_LEFT_PORT;
    }
    if (laneOf(source) === "DATA" && laneOf(target) !== "DATA") {
      return dataOuterSideAwayFromTarget(source, target) === "left" ? SOURCE_LEFT_PORT : RIGHT_PORT;
    }
    return dataOuterSide(source) === "left" ? SOURCE_LEFT_PORT : RIGHT_PORT;
  }
  if (laneOf(source) === "ANCHOR" && laneOf(target) === "OUTGOING") {
    return portWithSlot(RIGHT_PORT, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, RIGHT_PORT));
  }
  if (source.position!.x > target.position!.x) {
    return SOURCE_LEFT_PORT;
  }
  return portWithSlot(RIGHT_PORT, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, RIGHT_PORT));
}

/**
 * 根据关系类型与泳道组合决定目标节点的具体端口名（含扇出槽位）。
 * 与 sourcePortForRoute 对称，处理锚点入向、数据泳道入向、跨泳道等场景。
 */
export function targetPortForRoute(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexByTarget: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
) {
  if (isClassDiagramHierarchyRelation(edge)) {
    if (shouldUseVerticalStackPorts(source, target, sizeSnapshot)) {
      return verticalTargetPortForRoute(source, target, sizeSnapshot);
    }
    return horizontalTargetPortForRoute(edge, source, target, sizeSnapshot, edgeIndexByTarget, nodeIndex);
  }
  if (laneOf(source) === laneOf(target) && laneOf(source) !== "ANCHOR" && laneColumn(source) === laneColumn(target)) {
    return nodeCenterY(source, sizeSnapshot) <= nodeCenterY(target, sizeSnapshot) ? TOP_PORT : TARGET_BOTTOM_PORT;
  }
  if (isClassDiagramRoutedStructuralRelation(edge)) {
    if (laneOf(source) !== "DATA" && laneOf(target) !== "DATA") {
      if (laneOf(source) === "ANCHOR" && laneOf(target) === "OUTGOING") {
        return portWithSlot(LEFT_PORT, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, LEFT_PORT));
      }
      if (laneOf(source) === "INCOMING" && laneOf(target) === "ANCHOR") {
        return portWithSlot(LEFT_PORT, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, LEFT_PORT));
      }
      if (laneOf(source) === laneOf(target) && laneColumn(source) === laneColumn(target)) {
        return source.position!.y > target.position!.y ? TARGET_BOTTOM_PORT : TOP_PORT;
      }
      return source.position!.x > target.position!.x
        ? TARGET_RIGHT_PORT
        : portWithSlot(LEFT_PORT, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, LEFT_PORT));
    }
    if (laneOf(target) === "DATA" && laneOf(source) !== "DATA") {
      return dataRow(target) === 0 ? TOP_PORT : dataOuterSide(target) === "left" ? LEFT_PORT : TARGET_RIGHT_PORT;
    }
    if (laneOf(source) === "DATA" && laneOf(target) !== "DATA") {
      // DATA-lane 作为源、非 DATA 目标：目标端口左右选择仅取决于 X 相对位置，
      // 与 Y 高低无关（参见 preferredTargetSide 同一场景的实现）。
      // 旧实现写了两个一模一样的分支，属于死代码 —— 这里合并为单条返回。
      return target.position!.x >= source.position!.x ? TARGET_RIGHT_PORT : LEFT_PORT;
    }
    return dataOuterSide(target) === "left" ? LEFT_PORT : TARGET_RIGHT_PORT;
  }
  if (source.position!.x > target.position!.x) {
    return TARGET_RIGHT_PORT;
  }
  return portWithSlot(LEFT_PORT, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, LEFT_PORT));
}

/**
 * 同时返回水平连线的源/目标端口对。
 * 依据 X 中心位置选择左/右出向，并分别为源/目标附加兄弟边的扇出槽位号。
 */
export function horizontalPortPairForRoute(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexBySource: Map<string, LinkGraphEdge[]>,
  edgeIndexByTarget: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
): { sourcePort: string; targetPort: string } {
  if (nodeCenterX(source, sizeSnapshot) <= nodeCenterX(target, sizeSnapshot)) {
    return {
      sourcePort: portWithSlot(RIGHT_PORT, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, RIGHT_PORT)),
      targetPort: portWithSlot(LEFT_PORT, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, LEFT_PORT)),
    };
  }
  return {
    sourcePort: portWithSlot(SOURCE_LEFT_PORT, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, SOURCE_LEFT_PORT)),
    targetPort: portWithSlot(TARGET_RIGHT_PORT, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, TARGET_RIGHT_PORT)),
  };
}

/**
 * 同时返回纵向连线的源/目标端口对。
 * 依据 Y 中心位置选择顶/底端口，固定槽位（不参与扇出）。
 */
export function verticalPortPairForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): { sourcePort: string; targetPort: string } {
  return nodeCenterY(source, sizeSnapshot) <= nodeCenterY(target, sizeSnapshot)
    ? { sourcePort: BOTTOM_PORT, targetPort: TOP_PORT }
    : { sourcePort: SOURCE_TOP_PORT, targetPort: TARGET_BOTTOM_PORT };
}

/** 判断边是否是"跨泳道次级结构边"：从入向泳道直达出向泳道的结构关系。 */
export function isCrossLaneSecondaryStructuralEdge(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
): boolean {
  return isClassDiagramRoutedStructuralRelation(edge)
    && laneOf(source) === "INCOMING"
    && laneOf(target) === "OUTGOING";
}

/**
 * 检查节点正下方是否有足够的净空允许走底部端口：遍历其他节点，
 * 若有节点位于该节点正下方且在水平净空范围内（带边距），则视为净空被占用。
 */
export function bottomApproachClear(
  node: LinkGraphNode,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): boolean {
  const bounds = nodeBounds(node, sizeSnapshot);
  const centerX = nodeCenterX(node, sizeSnapshot);
  return !Array.from(nodeIndex.values()).some((candidate) => {
    if (candidate.id === node.id) {
      return false;
    }
    const candidateBounds = nodeBounds(candidate, sizeSnapshot);
    return candidateBounds.top >= bounds.bottom - 0.5
      && centerX > candidateBounds.left + EDGE_CROSSING_REPAIR_NODE_CLEARANCE
      && centerX < candidateBounds.right - EDGE_CROSSING_REPAIR_NODE_CLEARANCE;
  });
}

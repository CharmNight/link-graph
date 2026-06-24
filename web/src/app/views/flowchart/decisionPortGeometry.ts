import type { CSSProperties } from "react";
import { resolveFlowchartKind } from "../../flowchartKind";
import type { GraphPosition, LinkGraphEdge, LinkGraphNode } from "../../types";

/** 决策节点上所有可能用到的逻辑端口标识，覆盖三个入口（顶/左/右）与三个出口（左/右/底）。 */
export type FlowchartDecisionPortId =
  | "target-top"
  | "target-left"
  | "target-right"
  | "source-left"
  | "source-right"
  | "source-bottom";

/** 决策节点上可作为出口的端口标识子集，用于约束决策出边解析结果。 */
export type FlowchartDecisionSourcePortId =
  | "source-left"
  | "source-right"
  | "source-bottom";

/** 决策节点上可作为入口的端口标识子集，用于约束决策入边解析结果。 */
export type FlowchartDecisionTargetPortId =
  | "target-top"
  | "target-left"
  | "target-right";

/** 汇聚节点的入口端口标识：顶部单一入口加上左右两侧的编号入口，对应多个汇入分支。 */
export type FlowchartMergeTargetPortId =
  | "target-top"
  | `target-left-${number}`
  | `target-right-${number}`;

/** 汇聚节点左右两侧入口的计数结果，用于决定要渲染多少个目标句柄。 */
export interface FlowchartMergeTargetPortCounts {
  leftCount: number;
  rightCount: number;
}

/** 汇聚节点的端口布局结果：每条入边对应的目标端口句柄以及每个节点的左右入口计数。 */
export interface FlowchartMergeTargetPortLayout {
  targetHandleByEdgeId: Map<string, FlowchartMergeTargetPortId>;
  countsByNodeId: Map<string, FlowchartMergeTargetPortCounts>;
}

/** 解析决策源端口时所需的上下文：当前边、源节点的全部出边以及节点索引，供循环/穿透等判断使用。 */
interface DecisionSourcePortResolutionOptions {
  edge?: LinkGraphEdge;
  outgoingEdges?: LinkGraphEdge[];
  nodeIndex?: Map<string, LinkGraphNode>;
}

/** 决策端口的几何描述：横纵方向上的占比，以及可选的内联样式（用于句柄定位）。 */
interface DecisionPortGeometry {
  xRatio: number;
  yRatio: number;
  style?: CSSProperties;
}

/** 把边标签规范化为大写无空白的统一形式，便于条件分支匹配（如 TRUE/FALSE/EXCEPTION）。 */
function normalizedFlowLabel(edge: LinkGraphEdge | undefined): string {
  return edge?.label?.trim().toUpperCase() ?? "";
}

// 决策节点各逻辑端口在节点包围盒中的相对位置（横纵占比）以及对应的 CSS 定位样式，
// 用于在布局后把连线锚点精确贴到菱形的真实分支点上。
const DECISION_PORT_GEOMETRY: Record<FlowchartDecisionPortId, DecisionPortGeometry> = {
  "target-top": {
    xRatio: 0.5,
    yRatio: 0,
    style: {
      transform: "translate(-50%, 0)",
    },
  },
  "target-left": {
    xRatio: 0,
    yRatio: 0.5,
    style: {
      top: "50%",
      transform: "translate(0, -50%)",
    },
  },
  "target-right": {
    xRatio: 1,
    yRatio: 0.5,
    style: {
      top: "50%",
      transform: "translate(0, -50%)",
    },
  },
  "source-left": {
    xRatio: 0,
    yRatio: 0.5,
    style: {
      top: "50%",
      transform: "translate(0, -50%)",
    },
  },
  "source-right": {
    xRatio: 1,
    yRatio: 0.5,
    style: {
      top: "50%",
      transform: "translate(0, -50%)",
    },
  },
  "source-bottom": {
    xRatio: 0.5,
    yRatio: 1,
    style: {
      transform: "translate(-50%, 0)",
    },
  },
};

/** 包装节点类型解析逻辑，集中处理节点元数据缺失等边界情况。 */
function flowchartKind(node?: LinkGraphNode): string {
  return resolveFlowchartKind(node);
}

/** 读取节点所处的流程作用域类别（如循环前置/后置条件），用于推断循环相关边的端口选择。 */
function flowScopeCategory(node?: LinkGraphNode): string {
  return node?.metadata?.["flow.scopeCategory"] ?? "";
}

/** 提取边的流程角色元数据（循环体/回边/退出等），用于在循环结构中选择合适的源/目标端口。 */
function flowEdgeRole(edge?: LinkGraphEdge): string {
  return edge?.metadata?.["flow.edgeRole"]?.toUpperCase() ?? "";
}

/** 按源节点构建控制流出边索引，便于快速查询某个节点发出的所有控制流边。 */
export function buildOutgoingControlFlowIndex(edges: LinkGraphEdge[]): Map<string, LinkGraphEdge[]> {
  const index = new Map<string, LinkGraphEdge[]>();
  edges.forEach((edge) => {
    if (edge.type !== "CONTROL_FLOW") {
      return;
    }
    const current = index.get(edge.source) ?? [];
    current.push(edge);
    index.set(edge.source, current);
  });
  return index;
}

/** 按目标节点构建控制流入边索引，便于快速查询某个节点收到的所有控制流边。 */
export function buildIncomingControlFlowIndex(edges: LinkGraphEdge[]): Map<string, LinkGraphEdge[]> {
  const index = new Map<string, LinkGraphEdge[]>();
  edges.forEach((edge) => {
    if (edge.type !== "CONTROL_FLOW") {
      return;
    }
    const current = index.get(edge.target) ?? [];
    current.push(edge);
    index.set(edge.target, current);
  });
  return index;
}

/** 判断普通节点是否存在异常出口：决策与终端节点不参与，其余节点只要有 EXCEPTION 标签的出边即为真。 */
export function hasExceptionControlFlowOutlet(
  node: LinkGraphNode | undefined,
  outgoingEdges: LinkGraphEdge[] | undefined,
): boolean {
  if (!node || flowchartKind(node) === "DECISION" || flowchartKind(node) === "TERMINAL") {
    return false;
  }
  return (outgoingEdges ?? []).some(
    (edge) => edge.type === "CONTROL_FLOW" && normalizedFlowLabel(edge) === "EXCEPTION",
  );
}

/**
 * 判断一条决策出边是否属于"穿透"分支：当决策只有两条出边、其中一条指向终端/汇聚节点时，
 * 另一条（非终端/汇聚目标）边视为穿透分支，应使用底部出口以避免与分支端口重叠。
 */
export function isDecisionFallthroughEdge(
  edge: LinkGraphEdge | undefined,
  outgoingEdges: LinkGraphEdge[] | undefined,
  nodeIndex: Map<string, LinkGraphNode> | undefined,
): boolean {
  const semanticOutgoingEdges = outgoingEdges ?? [];
  if (!edge || semanticOutgoingEdges.length !== 2 || !nodeIndex) {
    return false;
  }
  const currentBranch = semanticOutgoingEdges.find((candidate) => candidate.id === edge.id);
  if (!currentBranch) {
    return false;
  }
  const targetKinds = semanticOutgoingEdges.map((candidate) => flowchartKind(nodeIndex.get(candidate.target)));
  const currentTargetKind = flowchartKind(nodeIndex.get(currentBranch.target));
  const terminalTargetCount = targetKinds.filter((kind) => kind === "TERMINAL").length;
  if (terminalTargetCount === 1) {
    return currentTargetKind !== "TERMINAL";
  }
  const mergeTargetCount = targetKinds.filter((kind) => kind === "MERGE").length;
  if (mergeTargetCount === 1) {
    return currentTargetKind === "MERGE";
  }
  return false;
}

/** 汇聚节点的入口侧别：顶部、左侧、右侧，对应三组目标端口集合。 */
type FlowchartMergeTargetSide = "top" | "left" | "right";

/** 在无法用几何关系判断时，根据边的语义标签选择汇聚入口侧别（TRUE 走左、FALSE/EXCEPTION 走右、其余顶部）。 */
function fallbackMergeTargetSide(edge: LinkGraphEdge | undefined): FlowchartMergeTargetSide {
  switch (normalizedFlowLabel(edge)) {
    case "TRUE":
      return "left";
    case "FALSE":
    case "DEFAULT":
    case "EXCEPTION":
      return "right";
    default:
      return "top";
  }
}

/** 综合穿透分支判断、源/目标节点几何相对位置和语义标签，决定一条入边应进入汇聚节点的哪一侧。 */
function resolveMergeTargetSide(
  edge: LinkGraphEdge,
  sourceNode: LinkGraphNode | undefined,
  targetNode: LinkGraphNode | undefined,
  outgoingEdges: LinkGraphEdge[] | undefined,
  nodeIndex: Map<string, LinkGraphNode>,
): FlowchartMergeTargetSide {
  if (isDecisionFallthroughEdge(edge, outgoingEdges, nodeIndex)) {
    return "top";
  }
  if (sourceNode?.position && targetNode?.position) {
    if (sourceNode.position.x < targetNode.position.x - 1) {
      return "left";
    }
    if (sourceNode.position.x > targetNode.position.x + 1) {
      return "right";
    }
  }
  return fallbackMergeTargetSide(edge);
}

/** 同一侧汇聚入口的排序比较：先按源节点 Y 坐标、再按 X 坐标，最后用边 ID 保证稳定顺序。 */
function compareMergeSideEdges(
  left: LinkGraphEdge,
  right: LinkGraphEdge,
  nodeIndex: Map<string, LinkGraphNode>,
): number {
  const leftSource = nodeIndex.get(left.source);
  const rightSource = nodeIndex.get(right.source);
  const yDiff = (leftSource?.position?.y ?? 0) - (rightSource?.position?.y ?? 0);
  if (Math.abs(yDiff) > 1) {
    return yDiff;
  }
  const xDiff = (leftSource?.position?.x ?? 0) - (rightSource?.position?.x ?? 0);
  if (Math.abs(xDiff) > 1) {
    return xDiff;
  }
  return left.id.localeCompare(right.id);
}

/** 根据侧别与序号生成汇聚节点的目标端口 ID 字符串（如 target-left-0、target-right-2）。 */
export function flowchartMergeTargetPortId(
  side: "left" | "right",
  index: number,
): `target-left-${number}` | `target-right-${number}` {
  return `target-${side}-${index}`;
}

/** 从节点元数据中读取指定侧别的入口计数，缺失或非法时回退为 1，保证至少渲染一个句柄。 */
export function readFlowchartMergeTargetPortCount(
  node: LinkGraphNode | undefined,
  side: "left" | "right",
): number {
  const key = side === "left"
    ? "flowchart.mergeLeftTargetCount"
    : "flowchart.mergeRightTargetCount";
  const parsed = Number(node?.metadata?.[key]);
  if (!Number.isFinite(parsed)) {
    return 1;
  }
  return Math.max(0, Math.round(parsed));
}

/** 计算某一侧第 index 个汇聚入口在节点纵向上的偏移百分比，多个入口时均匀分布在 28%~72% 区间。 */
function mergeTargetOffsetPercent(index: number, count: number): number {
  if (count <= 1) {
    return 50;
  }
  return 28 + (44 * index) / (count - 1);
}

/** 根据侧别、序号与总数，把基础句柄样式定位到汇聚节点侧边的正确纵向位置上。 */
export function flowchartMergeTargetHandleStyle(
  side: "left" | "right",
  index: number,
  count: number,
  baseStyle: CSSProperties,
): CSSProperties {
  return {
    ...baseStyle,
    top: `${mergeTargetOffsetPercent(index, count)}%`,
    transform: "translate(0, -50%)",
  };
}

/**
 * 为所有汇聚节点计算入边端口布局：遍历每个汇聚节点的入边，决定侧别并在同侧内排序分配序号，
 * 输出"边 ID 到端口 ID"的映射以及"节点 ID 到左右入口计数"的映射，供布局与渲染共用。
 */
export function buildMergeTargetPortLayout(
  edges: LinkGraphEdge[],
  nodeIndex: Map<string, LinkGraphNode>,
  outgoingControlFlowBySource: Map<string, LinkGraphEdge[]>,
  incomingControlFlowByTarget: Map<string, LinkGraphEdge[]>,
): FlowchartMergeTargetPortLayout {
  const targetHandleByEdgeId = new Map<string, FlowchartMergeTargetPortId>();
  const countsByNodeId = new Map<string, FlowchartMergeTargetPortCounts>();

  incomingControlFlowByTarget.forEach((incomingEdges, targetNodeId) => {
    const targetNode = nodeIndex.get(targetNodeId);
    if (!targetNode || flowchartKind(targetNode) !== "MERGE") {
      return;
    }

    const leftEdges: LinkGraphEdge[] = [];
    const rightEdges: LinkGraphEdge[] = [];
    incomingEdges.forEach((edge) => {
      const side = resolveMergeTargetSide(
        edge,
        nodeIndex.get(edge.source),
        targetNode,
        outgoingControlFlowBySource.get(edge.source),
        nodeIndex,
      );
      if (side === "top") {
        targetHandleByEdgeId.set(edge.id, "target-top");
        return;
      }
      if (side === "left") {
        leftEdges.push(edge);
        return;
      }
      rightEdges.push(edge);
    });

    leftEdges.sort((left, right) => compareMergeSideEdges(left, right, nodeIndex));
    rightEdges.sort((left, right) => compareMergeSideEdges(left, right, nodeIndex));
    leftEdges.forEach((edge, index) => {
      targetHandleByEdgeId.set(edge.id, flowchartMergeTargetPortId("left", index));
    });
    rightEdges.forEach((edge, index) => {
      targetHandleByEdgeId.set(edge.id, flowchartMergeTargetPortId("right", index));
    });
    countsByNodeId.set(targetNodeId, {
      leftCount: leftEdges.length,
      rightCount: rightEdges.length,
    });
  });

  return {
    targetHandleByEdgeId,
    countsByNodeId,
  };
}

/** 根据决策端口 ID 与节点尺寸计算端口的绝对坐标，用于布局后把连线锚点贴到菱形真实分支点上。 */
export function flowchartDecisionPortPoint(
  portId: FlowchartDecisionPortId,
  node: LinkGraphNode,
  size: { width: number; height: number },
): GraphPosition {
  const position = node.position ?? { x: 0, y: 0 };
  const geometry = DECISION_PORT_GEOMETRY[portId];
  return {
    x: position.x + size.width * geometry.xRatio,
    y: position.y + size.height * geometry.yRatio,
  };
}

/** 把基础句柄样式与决策端口自带的定位样式叠加，得到 React Flow 句柄的最终内联样式。 */
export function flowchartDecisionPortHandleStyle(
  portId: FlowchartDecisionPortId,
  baseStyle: CSSProperties,
): CSSProperties {
  const geometry = DECISION_PORT_GEOMETRY[portId];
  return geometry.style ? { ...baseStyle, ...geometry.style } : baseStyle;
}

/**
 * 推断决策节点上一条出边应使用的源端口：循环结构按角色（循环体/退出/回边）选择底部或左右端口，
 * 穿透分支用底部，其余按源/目标节点的横向相对位置选择左/右/底，让连线方向自然贴合分支走向。
 */
export function resolveDecisionSourcePort(
  sourceNode: LinkGraphNode | undefined,
  targetNode: LinkGraphNode | undefined,
  options: DecisionSourcePortResolutionOptions = {},
): FlowchartDecisionSourcePortId {
  const scopeCategory = flowScopeCategory(sourceNode);
  const edgeRole = flowEdgeRole(options.edge);
  if (scopeCategory === "LOOP_PRE_TEST") {
    if (edgeRole === "LOOP_BODY") {
      return "source-bottom";
    }
    if (edgeRole === "LOOP_EXIT") {
      if (targetNode?.position && sourceNode?.position) {
        return targetNode.position.x < sourceNode.position.x ? "source-left" : "source-right";
      }
      return "source-right";
    }
  }
  if (scopeCategory === "LOOP_POST_TEST") {
    if (edgeRole === "LOOP_EXIT") {
      return "source-bottom";
    }
    if (edgeRole === "LOOP_BACK") {
      if (targetNode?.position && sourceNode?.position) {
        return targetNode.position.x < sourceNode.position.x ? "source-left" : "source-right";
      }
      return "source-left";
    }
  }
  if (isDecisionFallthroughEdge(options.edge, options.outgoingEdges, options.nodeIndex)) {
    return "source-bottom";
  }
  if (!sourceNode?.position || !targetNode?.position) {
    return "source-bottom";
  }
  if (targetNode.position.x + 1 < sourceNode.position.x) {
    return "source-left";
  }
  if (targetNode.position.x > sourceNode.position.x + 1) {
    return "source-right";
  }
  return "source-bottom";
}

/**
 * 推断决策节点上一条入边应使用的目标端口：循环前置条件下的回边根据源节点相对位置选左/右入口，
 * 其余情况统一从顶部入口进入，避免与决策分支出边冲突。
 */
export function resolveDecisionTargetPort(
  sourceNode: LinkGraphNode | undefined,
  targetNode: LinkGraphNode | undefined,
  edge: LinkGraphEdge | undefined,
): FlowchartDecisionTargetPortId {
  const targetScopeCategory = flowScopeCategory(targetNode);
  const edgeRole = flowEdgeRole(edge);
  if (targetScopeCategory === "LOOP_PRE_TEST" && edgeRole === "LOOP_BACK") {
    if (!sourceNode?.position || !targetNode?.position) {
      return "target-left";
    }
    return sourceNode.position.x <= targetNode.position.x ? "target-left" : "target-right";
  }
  return "target-top";
}

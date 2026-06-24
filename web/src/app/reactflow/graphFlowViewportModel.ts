// 图画布视口相关的纯函数工具集合。
// 主要职责：
// - 文本哈希、坐标比较等通用工具；
// - 锚点节点解析、节点位置兜底；
// - 图内容边界计算与签名（用于判断是否需要重 fit）；
// - 视口 padding 计算等。
import type {
  AnalysisDisplayMode,
  GraphPosition,
  LinkGraphEdge,
  LinkGraphNode,
} from "../types";

/** 拖动位置比较的容差（像素）；小于此值视为位置未变。 */
const DRAG_POSITION_EPSILON = 0.5;

/**
 * 简单字符串哈希（DJBX33X 变体）。
 * 用于生成图形状签名等场景，不需要密码学强度。
 */
export function hashText(value: string): string {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = ((hash * 31) + value.charCodeAt(index)) >>> 0;
  }
  return hash.toString(36);
}

/**
 * 取"定位锚点"按钮的文案。
 * 按展示模式区分：类图/架构图/资源关系/流程图各自有不同的"当前对象"。
 */
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

/** 把图形状签名摘要为长度+哈希，便于埋点。 */
export function summarizeGraphShapeSignature(signature: string) {
  return {
    length: signature.length,
    hash: hashText(signature),
  };
}

/**
 * 兜底坐标生成器：按索引把节点排成 3 列网格。
 * 用于节点缺少坐标时的初版排版。
 */
export function fallbackPosition(index: number): GraphPosition {
  return {
    x: 80 + (index % 3) * 400,
    y: 88 + Math.floor(index / 3) * 220,
  };
}

/** 解析节点坐标；缺失时用兜底坐标。 */
export function resolveNodePosition(node: LinkGraphNode, index: number): GraphPosition {
  return node.position ?? fallbackPosition(index);
}

/**
 * 判断两个坐标是否"足够接近"（在容差内）。
 * 任一坐标缺失返回 false（不能视为相等）。
 */
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

/**
 * 解析视图的锚点节点。
 *
 * 优先级：
 * 1) 显式锚点 ID；
 * 2) 当前选中节点 ID；
 * 3) 第一个方法类型节点；
 * 4) 第一个节点；
 * 5) 都没有时返回 null。
 *
 * 这种多级回退保证总能找到一个合理的"焦点"。
 */
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

/** 数值夹紧到 [min, max] 区间。 */
export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/** 把数值四舍五入到 3 位小数；用于签名构造避免浮点噪声。 */
export function rounded(value: number): number {
  return Math.round(value * 1000) / 1000;
}

/** 图内容边界框。 */
export interface GraphContentBounds {
  /** 最左 x。 */
  minX: number;
  /** 最上 y。 */
  minY: number;
  /** 最右 x。 */
  maxX: number;
  /** 最下 y。 */
  maxY: number;
  /** 宽度。 */
  width: number;
  /** 高度。 */
  height: number;
}

/**
 * 计算图内容的边界框（包含所有节点与边路由）。
 *
 * 节点：按"位置 + 尺寸"计算占用矩形；
 * 边：把所有路由点纳入边界。
 * 空节点列表返回 null。
 *
 * @param nodes 节点列表
 * @param edges 边列表
 * @param nodeViewportSize 给定节点返回其尺寸的回调
 */
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
  // 把单个点纳入边界
  const includePoint = (point: GraphPosition) => {
    minX = Math.min(minX, point.x);
    minY = Math.min(minY, point.y);
    maxX = Math.max(maxX, point.x);
    maxY = Math.max(maxY, point.y);
  };
  // 节点：左上角与右下角都纳入
  nodes.forEach((node) => {
    const position = node.position ?? { x: 0, y: 0 };
    const size = nodeViewportSize(node);
    includePoint(position);
    includePoint({
      x: position.x + size.width,
      y: position.y + size.height,
    });
  });
  // 边：所有路由点都纳入（避免 fit 时边被裁切）
  edges.forEach((edge) => {
    edge.route?.sections.forEach((section) => {
      includePoint(section.startPoint);
      section.bendPoints?.forEach(includePoint);
      includePoint(section.endPoint);
    });
  });
  // 无任何有效点时返回 null（理论上不会发生，nodes 非空时至少有节点角点）
  if (!Number.isFinite(minX) || !Number.isFinite(minY) || !Number.isFinite(maxX) || !Number.isFinite(maxY)) {
    return null;
  }
  return {
    minX,
    minY,
    maxX,
    maxY,
    // 宽高至少为 1，避免后续 fit 计算除零
    width: Math.max(1, maxX - minX),
    height: Math.max(1, maxY - minY),
  };
}

/**
 * 计算图内容的"形状签名"。
 *
 * 把节点（位置+尺寸）与边（路由点序列）拼接为单字符串，
 * 用于判断两次渲染间图形状是否变化（触发重 fit）。
 */
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
      // 边的路由点序列拼接为 "x,y;x,y;..."
      const routePointSignature = edge.route?.sections
        .flatMap((section) => [section.startPoint, ...(section.bendPoints ?? []), section.endPoint])
        .map((point) => `${rounded(point.x)},${rounded(point.y)}`)
        .join(";") ?? "";
      return `${edge.id}:${edge.source}->${edge.target}:${routePointSignature}`;
    }),
  ].join("|");
}

/**
 * 计算"图渲染提交"埋点的签名。
 *
 * 综合图形状、边界框、选中状态、ResizeObserver 支持情况，
 * 让埋点能区分"什么状态下真正发生了提交"。
 */
export function graphRenderCommitTraceSignature(args: {
  graphShapeSignature: string;
  bounds: GraphContentBounds | null;
  selectedNodeId: string | null;
  selectedGroupNodeCount: number;
  supportsResizeObserver: boolean;
}): string {
  const boundsSignature = args.bounds
    ? [
        args.bounds.minX,
        args.bounds.minY,
        args.bounds.maxX,
        args.bounds.maxY,
        args.bounds.width,
        args.bounds.height,
      ].join(":")
    : "none";
  return [
    args.graphShapeSignature,
    boundsSignature,
    args.selectedNodeId ?? "",
    args.selectedGroupNodeCount,
    args.supportsResizeObserver ? "resize-observer" : "no-resize-observer",
  ].join("|");
}

/**
 * 根据尺寸与 padding 比例计算实际像素 padding。
 *
 * padding 是相对比例（0.1 = 10%），结果按 floor 取整。
 * 公式：(size - size / (1 + padding)) / 2，等价于"两侧均分多出来的空间"。
 */
export function reactFlowPaddingPixels(size: number, padding: number): number {
  if (padding <= 0) {
    return 0;
  }
  return Math.floor((size - size / (1 + padding)) / 2);
}

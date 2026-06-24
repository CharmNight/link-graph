import type { LinkGraphNode } from "./types";

/**
 * 判定节点在流程图中属于"分支决策"语义的种类集合。当节点的 flow.kind 元数据命中这里任意一项时，
 * 该节点会被渲染为 DECISION 形状（菱形），与流程图传统约定一致。
 */
const DECISION_FLOW_SCOPE_KINDS = new Set([
  "IF",
  "SWITCH",
  "FOREACH",
  "FOR",
  "WHILE",
  "DO_WHILE",
]);

/**
 * 取出节点元数据中的 flow.kind 字段并标准化（去首尾空白、转大写），便于后续与种类集合做不区分大小写的匹配。
 * 缺失该字段时返回空串，表示未知种类。
 */
function normalizedFlowKind(node: Pick<LinkGraphNode, "metadata">): string {
  return node.metadata?.["flow.kind"]?.trim().toUpperCase() ?? "";
}

/**
 * 综合节点类型与元数据解析其在流程图视图中应使用的图形种类（PROCESS / DECISION / MERGE / TERMINAL 等）。
 * 优先级：空节点回退到 PROCESS；FLOW_SCOPE 且命中决策种类为 DECISION；MERGE/TERMINAL 直接对应同种类；
 * 其余情况以元数据 flowchart.kind 为准，再缺失时回退到 PROCESS。
 * 该结果是渲染层选择节点形状与图标的依据。
 */
export function resolveFlowchartKind(node?: Pick<LinkGraphNode, "type" | "metadata"> | null): string {
  // 入参缺失（例如选中空）时统一视为普通处理节点，避免渲染层处理 null 分支
  if (!node) {
    return "PROCESS";
  }
  // 流程作用域节点中若元数据表明是分支类（IF/SWITCH/循环），按决策节点渲染
  if (node.type === "FLOW_SCOPE" && DECISION_FLOW_SCOPE_KINDS.has(normalizedFlowKind(node))) {
    return "DECISION";
  }
  // 合并节点的语义固定，直接返回，避免依赖可能缺失的元数据
  if (node.type === "MERGE") {
    return "MERGE";
  }
  // 终止节点（方法入口/出口等）固定为 TERMINAL 形状
  if (node.type === "TERMINAL") {
    return "TERMINAL";
  }
  // 兜底：优先采用后端在元数据里给出的 flowchart.kind，否则视为普通处理节点
  return node.metadata?.["flowchart.kind"] ?? "PROCESS";
}

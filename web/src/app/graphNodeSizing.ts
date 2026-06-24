import { resolveFlowchartKind } from "./flowchartKind";
import type { LinkGraphNode } from "./types";

// ====== 节点卡片宽度常量 ======
// 通用节点卡片默认宽度，适用于多数视图下的节点。
export const DEFAULT_NODE_CARD_WIDTH = 408;
// 架构图节点卡片宽度，比默认略窄以容纳更多组件节点。
export const ARCHITECTURE_NODE_CARD_WIDTH = 340;
// 类图节点作为锚点（主类）时的卡片宽度。
export const CLASS_DIAGRAM_NODE_CARD_WIDTH = 360;
// 类图节点处于紧凑模式（如关联类）时的卡片宽度。
export const CLASS_DIAGRAM_COMPACT_NODE_CARD_WIDTH = 248;
// 流程图中"动作"节点的固定宽度。
export const FLOW_ACTION_NODE_CARD_WIDTH = 324;
// 流程图中"决策（IF）"节点的固定宽度，预留条件文本空间。
export const FLOW_DECISION_NODE_CARD_WIDTH = 368;

// ====== 流程图节点尺寸常量 ======
// 流程图普通处理节点宽度。
export const FLOWCHART_PROCESS_WIDTH = 324;
// 流程图决策节点宽度。
export const FLOWCHART_DECISION_WIDTH = 272;
// 决策节点最小高度，避免条件分支较少时被压扁。
export const FLOWCHART_DECISION_MIN_HEIGHT = 228;
// 流程图入口节点宽度。
export const FLOWCHART_ENTRY_WIDTH = 324;
// 流程图终止节点宽度。
export const FLOWCHART_TERMINAL_WIDTH = 280;
// 流程图合并节点的窄宽度，因为合并通常只需一个连接点。
export const FLOWCHART_MERGE_WIDTH = 132;

/**
 * 判断节点标题是否看起来"像代码片段"（例如包含括号、点号、驼峰等），
 * 用于决定是否给该节点加额外宽度以避免长代码被截断。
 *
 * 判定阈值：
 * - 长度必须 ≥ 28，太短的标题不值得额外扩展；
 * - 含有小括号、点号、等号比较或驼峰单词，则视为代码样貌。
 */
function isLongCodeLikeTitle(title?: string | null): boolean {
  const normalizedTitle = title?.trim() ?? "";
  if (normalizedTitle.length < 28) {
    return false;
  }
  return normalizedTitle.includes("(")
    || normalizedTitle.includes(")")
    || normalizedTitle.includes(".")
    || normalizedTitle.includes("==")
    || /[a-z][A-Z]/.test(normalizedTitle);
}

/**
 * 对于代码样貌的标题，按流程图节点种类追加额外宽度，
 * 让长方法签名、条件表达式等内容能完整显示。
 * 非代码样貌或不需要额外空间的种类返回 0。
 */
function extraFlowchartNodeWidth(node: Pick<LinkGraphNode, "type" | "metadata" | "title">): number {
  if (!isLongCodeLikeTitle(node.title)) {
    return 0;
  }
  const kind = resolveFlowchartKind(node);
  if (kind === "DECISION") {
    return 88;
  }
  if (kind === "PROCESS" || kind === "SUBROUTINE") {
    return 56;
  }
  return 0;
}

/**
 * 解析普通节点卡片宽度。优先按节点类型与元数据给出特化宽度，
 * 兜底回到 DEFAULT_NODE_CARD_WIDTH。
 */
export function nodeCardWidth(node: Pick<LinkGraphNode, "type" | "metadata" | "title">): number {
  // 流程动作节点固定宽度
  if (node.type === "FLOW_ACTION") {
    return FLOW_ACTION_NODE_CARD_WIDTH;
  }
  // 流程作用域中的 IF 决策节点使用决策宽度
  if (node.type === "FLOW_SCOPE" && node.metadata?.["flow.kind"] === "IF") {
    return FLOW_DECISION_NODE_CARD_WIDTH;
  }
  return DEFAULT_NODE_CARD_WIDTH;
}

/**
 * 架构图节点卡片宽度。架构图节点尺寸统一，无差异化处理。
 */
export function architectureGraphNodeCardWidth(): number {
  return ARCHITECTURE_NODE_CARD_WIDTH;
}

/**
 * 类图节点卡片宽度。宽度决策依据：
 * 1) 元数据 presentation.compact 显式给出时直接采用；
 * 2) 否则按节点角色 presentation.role（ANCHOR 用大卡，其他用小卡）；
 * 3) 角色缺失时按 layout.direction=ANCHOR 区分；
 * 4) 节点缺失时返回默认大卡。
 */
export function classDiagramNodeCardWidth(node?: Pick<LinkGraphNode, "type" | "metadata" | "title"> | null): number {
  if (!node) {
    return CLASS_DIAGRAM_NODE_CARD_WIDTH;
  }
  const compactMetadata = node.metadata?.["presentation.compact"];
  if (compactMetadata === "true") {
    return CLASS_DIAGRAM_COMPACT_NODE_CARD_WIDTH;
  }
  if (compactMetadata === "false") {
    return CLASS_DIAGRAM_NODE_CARD_WIDTH;
  }
  const presentationRole = node.metadata?.["presentation.role"];
  if (presentationRole) {
    // 锚点角色（被讲解的主类）使用大卡，关联类使用紧凑卡
    return presentationRole === "ANCHOR"
      ? CLASS_DIAGRAM_NODE_CARD_WIDTH
      : CLASS_DIAGRAM_COMPACT_NODE_CARD_WIDTH;
  }
  // 兜底：按布局方向判断，处于锚点方向的视为大卡
  return node.metadata?.["layout.direction"] === "ANCHOR"
    ? CLASS_DIAGRAM_NODE_CARD_WIDTH
    : CLASS_DIAGRAM_COMPACT_NODE_CARD_WIDTH;
}

/**
 * 流程图节点卡片宽度。先按流程图种类取基础宽度，
 * 再叠加代码样貌标题所需的额外宽度，避免长文本被截断。
 */
export function flowchartNodeCardWidth(node: Pick<LinkGraphNode, "type" | "metadata" | "title">): number {
  const kind = resolveFlowchartKind(node);
  switch (kind) {
    case "DECISION":
      return FLOWCHART_DECISION_WIDTH + extraFlowchartNodeWidth(node);
    case "MERGE":
      return FLOWCHART_MERGE_WIDTH;
    case "TERMINAL":
      return FLOWCHART_TERMINAL_WIDTH;
    case "ENTRY":
      return FLOWCHART_ENTRY_WIDTH;
    default:
      return FLOWCHART_PROCESS_WIDTH + extraFlowchartNodeWidth(node);
  }
}

/**
 * 助手域共用类型定义。
 *
 * 这里集中了与"助手（assistant）"工作台相关的纯类型：动作 ID、意图分类、轮次种类，
 * 以及索引图新鲜度与可见性原因等"被助手 UI 与底层索引共同消费"的状态描述。
 *
 * 从 `app/types.ts` 拆出以便助手相关模块按需导入；`types.ts` 仍 re-export 这些类型，
 * 兼容现有 100+ 个旧 import 路径。
 */

/**
 * 助手能力 ID：描述类、解释结构、解释流程、追问上下文、生成实现、检查变更。
 */
export type AssistantActionId =
  | "DESCRIBE_CLASS"
  | "EXPLAIN_STRUCTURE"
  | "EXPLAIN_FLOW"
  | "ASK_CONTEXT"
  | "GENERATE_IMPLEMENTATION"
  | "CHECK_CHANGE";

/**
 * 助手意图分类：描述类、解释代码、询问代码、生成代码、检查变更。
 */
export type AssistantIntent =
  | "DESCRIBE_CLASS"
  | "EXPLAIN_CODE"
  | "ASK_CODE"
  | "GENERATE_CODE"
  | "CHECK_CHANGE";

/**
 * 助手轮次的种类：解释、问答、生成方案、代码草稿、检查结果。
 */
export type AssistantTurnKind =
  | "EXPLANATION"
  | "QA"
  | "GENERATION_PLAN"
  | "CODE_DRAFT"
  | "CHECK_RESULT";

/**
 * 索引图新鲜度描述：状态、过期原因、待索引文件信息。
 */
export interface IndexedGraphFreshness {
  /** 新鲜度状态 */
  state: "FRESH" | "STALE" | "BUILDING" | string;
  /** 过期原因 */
  dirtyReason?: string | null;
  /** 待重新索引的文件数 */
  pendingFileCount: number;
  /** 待处理文件样本（用于展示） */
  pendingFileSamples: string[];
  /** 上次完成索引的时间戳 */
  lastIndexedAtEpochMillis?: number | null;
  /** 自何时起过期 */
  staleSinceEpochMillis?: number | null;
}

/**
 * 元素可见性原因：用于解释为什么某些元素被隐藏或折叠。
 */
export interface IndexedGraphVisibilityReason {
  /** 原因代码（用于 UI 国际化） */
  code: string;
  /** 默认展示文案 */
  label: string;
  /** 受影响节点数 */
  nodeCount?: number;
  /** 受影响边数 */
  edgeCount?: number;
}

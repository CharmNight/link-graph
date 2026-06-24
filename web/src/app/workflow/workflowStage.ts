/** 工作流阶段枚举：理解 → 证据 → 问答 → 草稿 → 代码，对应引导式操作五步。 */
export type WorkflowStage = "understand" | "evidence" | "qa" | "draft" | "code";

/** 阶段运行态的细分，覆盖空闲、当前、完成、阻塞、运行中、失败几种情况。 */
export type WorkflowStageStatus = "idle" | "active" | "done" | "blocked" | "running" | "failed";

/** 单个阶段的展示元数据：ID、各长度标题、用途说明。 */
export interface WorkflowStageDefinition {
  /** 阶段标识。 */
  id: WorkflowStage;
  /** 完整标题（引导条主位置用）。 */
  label: string;
  /** 短标题（窄空间场景用）。 */
  shortLabel: string;
  /** 该阶段的目的说明，用于首次进入时的提示。 */
  purpose: string;
}

/**
 * 五阶段定义常量。顺序即引导条上的展示顺序，
 * 各阶段的 purpose 文案刻意强调"做什么"而非"是什么"，让用户快速理解每步价值。
 */
export const WORKFLOW_STAGE_DEFINITIONS: WorkflowStageDefinition[] = [
  {
    id: "understand",
    label: "理解链路",
    shortLabel: "理解",
    purpose: "让用户知道链路入口、主路径、分支和副作用",
  },
  {
    id: "evidence",
    label: "核验证据",
    shortLabel: "证据",
    purpose: "总结证据强度、缺口、选中节点和风险线索",
  },
  {
    id: "qa",
    label: "风险问答",
    shortLabel: "问答",
    purpose: "针对证据缺口继续取证、确认候选变更",
  },
  {
    id: "draft",
    label: "草稿确认",
    shortLabel: "草稿",
    purpose: "把已确认的业务意图沉淀为草稿",
  },
  {
    id: "code",
    label: "代码落地",
    shortLabel: "代码",
    purpose: "生成、查看、写入代码 diff",
  },
];

/**
 * 按阶段 ID 查询定义。未匹配时回退到第一阶段（understand），
 * 让 UI 在异常输入下也能拿到一个可渲染的默认值。
 */
export function getWorkflowStageDefinition(stage: WorkflowStage): WorkflowStageDefinition {
  return WORKFLOW_STAGE_DEFINITIONS.find((definition) => definition.id === stage)
    ?? WORKFLOW_STAGE_DEFINITIONS[0];
}

/**
 * 把阶段运行态转换为中文标签，用于引导条等需要展示文字的位置。
 */
export function workflowStageStatusLabel(status: WorkflowStageStatus): string {
  switch (status) {
    case "active":
      return "当前";
    case "done":
      return "完成";
    case "blocked":
      return "阻塞";
    case "running":
      return "进行中";
    case "failed":
      return "失败";
    case "idle":
    default:
      return "未开始";
  }
}

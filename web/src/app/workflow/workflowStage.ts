export type WorkflowStage = "understand" | "evidence" | "qa" | "draft" | "code";

export type WorkbenchTab = "explanation" | "audit" | "draft" | "code";

export type WorkflowStageStatus = "idle" | "active" | "done" | "blocked" | "running" | "failed";

export interface WorkflowStageDefinition {
  id: WorkflowStage;
  label: string;
  shortLabel: string;
  purpose: string;
}

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

export function workflowStageToWorkbenchTab(stage: WorkflowStage): WorkbenchTab | null {
  switch (stage) {
    case "understand":
      return "explanation";
    case "qa":
      return "audit";
    case "draft":
      return "draft";
    case "code":
      return "code";
    case "evidence":
      return null;
  }
}

export function workbenchTabToWorkflowStage(tab: WorkbenchTab): WorkflowStage {
  switch (tab) {
    case "explanation":
      return "understand";
    case "audit":
      return "qa";
    case "draft":
      return "draft";
    case "code":
      return "code";
  }
}

export function getWorkflowStageDefinition(stage: WorkflowStage): WorkflowStageDefinition {
  return WORKFLOW_STAGE_DEFINITIONS.find((definition) => definition.id === stage)
    ?? WORKFLOW_STAGE_DEFINITIONS[0];
}

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
      return "待处理";
  }
}

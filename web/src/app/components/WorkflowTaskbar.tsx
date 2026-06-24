import type { OperationFeedback } from "../types";
import type { WorkflowStage } from "../workflow/workflowStage";
import { getWorkflowStageDefinition } from "../workflow/workflowStage";
import { Button } from "./Button";

/**
 * 顶栏只读阶段标签：阶段序号 + 阶段短名。
 * 取代旧的五段状态条 —— 阶段不再做导航，完整导航交给左侧 WorkflowStageNav。
 */
const STAGE_LABEL: Record<WorkflowStage, string> = {
  understand: "理解",
  evidence: "证据",
  qa: "问答",
  draft: "草稿",
  code: "代码",
};

/** 阶段序号（1-based），用于 badge 中"N/5"格式。 */
const STAGE_INDEX: Record<WorkflowStage, number> = {
  understand: 1,
  evidence: 2,
  qa: 3,
  draft: 4,
  code: 5,
};

const TOTAL_STAGES = 5;

interface WorkflowTaskbarProps {
  title: string;
  path?: string | null;
  activeStage: WorkflowStage;
  riskCount: number;
  draftCandidateCount: number;
  operationFeedback?: OperationFeedback | null;
  primaryActionLabel: string;
  /** 主操作副标题（展示具体动作与阶段位置），补充主标题"下一步"以外的语义。 */
  primaryActionSubtitle?: string;
  primaryActionDisabled?: boolean;
  onImportMermaid: () => void;
  onExportMermaid: () => void;
  onShowDiff: () => void;
  onRequestSync: () => void;
  onOpenSettings: () => void;
  onPrimaryAction: () => void;
}

export function WorkflowTaskbar({
  title,
  path = null,
  activeStage,
  riskCount,
  draftCandidateCount,
  operationFeedback = null,
  primaryActionLabel,
  primaryActionSubtitle,
  primaryActionDisabled = false,
  onImportMermaid,
  onExportMermaid,
  onShowDiff,
  onRequestSync,
  onOpenSettings,
  onPrimaryAction,
}: WorkflowTaskbarProps) {
  const stageDefinition = getWorkflowStageDefinition(activeStage);
  return (
    <header className="workflow-taskbar" role="banner" aria-label="链路任务栏">
      <div className="workflow-taskbar-title">
        <p className="eyebrow">Link Workflow</p>
        <h1 title={title}>{title}</h1>
        {path ? <p className="workflow-taskbar-path" title={path}>{path}</p> : null}
        <div className="workflow-taskbar-signals" aria-label="任务状态">
          <span className="risk-pill">风险 {riskCount}</span>
          <span className="app-pill">候选 {draftCandidateCount}</span>
          {operationFeedback ? (
            <span className={`status-pill feedback-${operationFeedback.level.toLowerCase()}`}>
              {operationFeedback.message}
            </span>
          ) : null}
        </div>
      </div>

      <div className="workflow-taskbar-actions" aria-label="全局动作">
        {/* 阶段 badge 精简为进度指示（N/总 · 阶段名），完整导航交给左侧 WorkflowStageNav */}
        <span
          className="stage-badge"
          role="status"
          title={stageDefinition.purpose}
        >
          {STAGE_INDEX[activeStage]}/{TOTAL_STAGES} · {STAGE_LABEL[activeStage]}
        </span>
        {primaryActionSubtitle ? (
          <span className="primary-action-subtitle">{primaryActionSubtitle}</span>
        ) : null}
        <Button
          variant="primary"
          disabled={primaryActionDisabled}
          onClick={onPrimaryAction}
        >
          {primaryActionLabel}
        </Button>
        <Button onClick={onRequestSync}>同步预览</Button>
        <Button onClick={onShowDiff}>对比代码</Button>
        <Button onClick={onImportMermaid}>导入 Mermaid</Button>
        <Button onClick={onExportMermaid}>导出 Mermaid</Button>
        <Button onClick={onOpenSettings}>设置</Button>
      </div>
    </header>
  );
}

import type { OperationFeedback } from "../types";
import type { WorkflowStage, WorkflowStageStatus } from "../workflow/workflowStage";
import { WORKFLOW_STAGE_DEFINITIONS, workflowStageStatusLabel } from "../workflow/workflowStage";

const ASSISTANT_STATUS_LABELS: Record<WorkflowStage, string> = {
  understand: "理解代码",
  evidence: "核验证据",
  qa: "代码问答",
  draft: "草稿确认",
  code: "代码落地",
};

interface WorkflowTaskbarProps {
  title: string;
  path?: string | null;
  activeStage: WorkflowStage;
  stageStates: Record<WorkflowStage, WorkflowStageStatus>;
  assistantStatusLabels?: Record<WorkflowStage, string>;
  riskCount: number;
  draftCandidateCount: number;
  operationFeedback?: OperationFeedback | null;
  primaryActionLabel: string;
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
  stageStates,
  assistantStatusLabels = ASSISTANT_STATUS_LABELS,
  riskCount,
  draftCandidateCount,
  operationFeedback = null,
  primaryActionLabel,
  primaryActionDisabled = false,
  onImportMermaid,
  onExportMermaid,
  onShowDiff,
  onRequestSync,
  onOpenSettings,
  onPrimaryAction,
}: WorkflowTaskbarProps) {
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

      <div className="assistant-status-strip" role="group" aria-label="AI 工作状态">
        <span className="assistant-status-heading">AI 状态</span>
        {WORKFLOW_STAGE_DEFINITIONS.map((stage) => {
          const status = stageStates[stage.id];
          return (
            <span
              key={stage.id}
              className={`assistant-status-item status-${status}${stage.id === activeStage ? " is-current" : ""}`}
              title={stage.purpose}
            >
              <span className="assistant-status-dot" aria-hidden="true" />
              <span className="assistant-status-copy">
                <span className="assistant-status-label">{assistantStatusLabels[stage.id]}</span>
                <span className="assistant-status-value">{workflowStageStatusLabel(status)}</span>
              </span>
            </span>
          );
        })}
      </div>

      <div className="workflow-taskbar-actions" aria-label="全局动作">
        <button
          type="button"
          className="primary-button"
          disabled={primaryActionDisabled}
          onClick={onPrimaryAction}
        >
          {primaryActionLabel}
        </button>
        <button type="button" className="ghost-button" onClick={onRequestSync}>同步预览</button>
        <button type="button" className="ghost-button" onClick={onShowDiff}>对比代码</button>
        <button type="button" className="ghost-button" onClick={onImportMermaid}>导入 Mermaid</button>
        <button type="button" className="ghost-button" onClick={onExportMermaid}>导出 Mermaid</button>
        <button type="button" className="ghost-button" onClick={onOpenSettings}>设置</button>
      </div>
    </header>
  );
}

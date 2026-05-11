import type { OperationFeedback } from "../types";
import {
  WORKFLOW_STAGE_DEFINITIONS,
  type WorkflowStage,
  type WorkflowStageStatus,
  workflowStageStatusLabel,
} from "../workflow/workflowStage";

interface WorkflowTaskbarProps {
  title: string;
  path?: string | null;
  activeStage: WorkflowStage;
  stageStates: Record<WorkflowStage, WorkflowStageStatus>;
  riskCount: number;
  draftCandidateCount: number;
  operationFeedback?: OperationFeedback | null;
  primaryActionLabel: string;
  primaryActionDisabled?: boolean;
  onStageChange: (stage: WorkflowStage) => void;
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
  riskCount,
  draftCandidateCount,
  operationFeedback = null,
  primaryActionLabel,
  primaryActionDisabled = false,
  onStageChange,
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

      <ol className="workflow-stage-strip" aria-label="工作流阶段">
        {WORKFLOW_STAGE_DEFINITIONS.map((stage, index) => {
          const status = activeStage === stage.id ? "active" : stageStates[stage.id] ?? "idle";
          return (
            <li key={stage.id}>
              <button
                type="button"
                className={`workflow-stage-step workflow-stage-step-${status}`}
                aria-current={activeStage === stage.id ? "step" : undefined}
                onClick={() => onStageChange(stage.id)}
              >
                <span className="workflow-stage-num">{index + 1}</span>
                <span className="workflow-stage-copy">
                  <span className="workflow-stage-label">{stage.label}</span>
                  <span className="workflow-stage-status">{workflowStageStatusLabel(status)}</span>
                </span>
              </button>
            </li>
          );
        })}
      </ol>

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

import { useEffect, useRef, type ReactNode } from "react";
import {
  WORKFLOW_STAGE_DEFINITIONS,
  type WorkflowStage,
  type WorkflowStageStatus,
  workflowStageStatusLabel,
} from "../workflow/workflowStage";

interface StageWorkbenchProps {
  activeStage: WorkflowStage;
  stageStates: Record<WorkflowStage, WorkflowStageStatus>;
  onStageChange: (stage: WorkflowStage) => void;
  content: ReactNode;
}

export function StageWorkbench({
  activeStage,
  stageStates,
  onStageChange,
  content,
}: StageWorkbenchProps) {
  const bodyRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (bodyRef.current) {
      bodyRef.current.scrollTop = 0;
    }
  }, [activeStage]);

  return (
    <aside className="stage-workbench" aria-label="阶段工作台">
      <div className="stage-workbench-tabs" role="tablist" aria-label="工作流阶段切换">
        {WORKFLOW_STAGE_DEFINITIONS.map((stage) => {
          const selected = activeStage === stage.id;
          const status = selected ? "active" : stageStates[stage.id] ?? "idle";
          return (
            <button
              key={stage.id}
              id={`workflow-tab-${stage.id}`}
              type="button"
              role="tab"
              aria-selected={selected}
              aria-controls={`workflow-panel-${stage.id}`}
              aria-label={stage.shortLabel}
              className={selected ? "stage-workbench-tab active" : "stage-workbench-tab"}
              onClick={() => onStageChange(stage.id)}
            >
              <span>{stage.shortLabel}</span>
              <span className="stage-workbench-tab-status" aria-hidden="true">{workflowStageStatusLabel(status)}</span>
            </button>
          );
        })}
      </div>
      <div
        ref={bodyRef}
        id={`workflow-panel-${activeStage}`}
        role="tabpanel"
        aria-labelledby={`workflow-tab-${activeStage}`}
        className="stage-workbench-panel m-scrollbar"
      >
        {content}
      </div>
    </aside>
  );
}

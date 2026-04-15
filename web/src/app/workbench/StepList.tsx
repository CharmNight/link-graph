import { useEffect, useMemo, useState, type MouseEvent as ReactMouseEvent } from "react";
import { stepKindLabel } from "../labels";
import type { GraphBeautificationStep, ResultEvidenceReference } from "../types";
import { GraphContextMenu } from "../components/graph/actions/GraphContextMenu";

interface StepListProps {
  steps: GraphBeautificationStep[];
  selectedStepId?: string | null;
  onSelectStep: (stepId: string) => void;
  onLocateStepNode: (stepId: string) => void;
  onInspectStepNode: (stepId: string) => void;
  onHoverStep?: (stepId: string) => void;
  onLeaveStep?: () => void;
  showTitle?: boolean;
}

interface StepContextMenuState {
  stepId: string;
  x: number;
  y: number;
}

function summarizeReference(reference?: ResultEvidenceReference) {
  if (!reference) {
    return "未定位到代码";
  }
  if (reference.filePath) {
    const filename = reference.filePath.split("/").filter(Boolean).pop() ?? reference.filePath;
    return reference.startLine ? `${filename}:${reference.startLine}` : filename;
  }
  return reference.nodeId ?? "当前图节点";
}

function summarizeSnippet(snippet?: string | null): string {
  const normalized = snippet?.replace(/\s+/g, " ").trim() ?? "";
  if (!normalized) {
    return "当前步骤还没有提取到可读代码片段。";
  }
  const preview = normalized.length > 88 ? `${normalized.slice(0, 88).trimEnd()}...` : normalized;
  return `代码片段：${preview}`;
}

export function StepList({
  steps,
  selectedStepId,
  onSelectStep,
  onLocateStepNode,
  onInspectStepNode,
  onHoverStep = () => undefined,
  onLeaveStep = () => undefined,
  showTitle = true,
}: StepListProps) {
  const [contextMenu, setContextMenu] = useState<StepContextMenuState | null>(null);

  useEffect(() => {
    if (!contextMenu) {
      return undefined;
    }

    function handleWindowPointerDown(event: PointerEvent) {
      const target = event.target;
      if (
        target instanceof Element &&
        (target.closest(".canvas-context-menu") || target.closest("[data-step-context-trigger='true']"))
      ) {
        return;
      }
      setContextMenu(null);
    }

    function handleWindowKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setContextMenu(null);
      }
    }

    window.addEventListener("pointerdown", handleWindowPointerDown);
    window.addEventListener("keydown", handleWindowKeyDown);
    return () => {
      window.removeEventListener("pointerdown", handleWindowPointerDown);
      window.removeEventListener("keydown", handleWindowKeyDown);
    };
  }, [contextMenu]);

  const contextMenuActions = useMemo(() => {
    if (!contextMenu) {
      return [];
    }
    return [
      {
        id: "locate-step-node",
        label: "定位到图中节点",
        onSelect: () => {
          onLocateStepNode(contextMenu.stepId);
          setContextMenu(null);
        },
      },
      {
        id: "inspect-step-node",
        label: "编辑节点",
        onSelect: () => {
          onInspectStepNode(contextMenu.stepId);
          setContextMenu(null);
        },
      },
    ];
  }, [contextMenu, onInspectStepNode, onLocateStepNode]);

  function handleContextMenu(event: ReactMouseEvent<HTMLButtonElement>, stepId: string) {
    event.preventDefault();
    event.stopPropagation();
    setContextMenu({
      stepId,
      x: event.clientX,
      y: event.clientY,
    });
  }

  return (
    <aside className="workbench-step-list">
      {showTitle ? <p className="eyebrow">步骤列表</p> : null}
      <div className="workbench-step-items">
        {steps.map((step) => (
          <button
            key={step.stepId}
            type="button"
            data-step-context-trigger="true"
            className={selectedStepId === step.stepId ? "workbench-step-item active" : "workbench-step-item"}
            onClick={() => onSelectStep(step.stepId)}
            onContextMenu={(event) => handleContextMenu(event, step.stepId)}
            onMouseEnter={() => onHoverStep(step.stepId)}
            onMouseLeave={onLeaveStep}
            onFocus={() => onHoverStep(step.stepId)}
            onBlur={onLeaveStep}
          >
            <span className="workbench-step-kind">{stepKindLabel(step.kind)}</span>
            <strong>{step.title}</strong>
            <span className="workbench-step-snippet">{summarizeSnippet(step.codeSnippet)}</span>
            <span className="workbench-step-ref">代码位置：{summarizeReference(step.evidence[0]?.references[0])}</span>
          </button>
        ))}
      </div>
      {contextMenu ? <GraphContextMenu x={contextMenu.x} y={contextMenu.y} actions={contextMenuActions} /> : null}
    </aside>
  );
}

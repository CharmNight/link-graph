import { useEffect, useMemo, useState, type MouseEvent as ReactMouseEvent } from "react";
import { Button } from "../../components/Button";
import { GraphContextMenu } from "../../components/graph/actions/GraphContextMenu";
import { formatResultEvidenceReference, stepGranularityLabel, stepKindLabel } from "../../labels";
import type { AssistantTurn, ResultEvidenceReference, StepGranularity } from "../../types";
import type { AssistantArtifactAccess } from "../assistantArtifacts";
import { AssistantFailureNotice } from "./AssistantFailureNotice";
import { AssistantPromptDisclosure } from "./AssistantPromptDisclosure";
import { AssistantTurnHeader } from "./AssistantTurnFrame";

interface ExplanationTurnCardProps extends AssistantArtifactAccess {
  turn: AssistantTurn;
  turnIndex?: number;
  isLatest?: boolean;
  onRevealReference: (reference: ResultEvidenceReference) => void;
  selectedStepId?: string | null;
  currentGranularity?: StepGranularity;
  granularityRequestRunning?: boolean;
  historyTrail?: string[];
  previousSessionLabel?: string | null;
  onSelectStep?: (stepId: string) => void;
  onLocateStepNode?: (stepId: string) => void;
  onInspectStepNode?: (stepId: string) => void;
  onHoverStep?: (stepId: string) => void;
  onLeaveStep?: () => void;
  onGranularityChange?: (granularity: StepGranularity) => void;
  onFollowUpStep?: (stepId: string, question?: string) => void;
  onReturnToPrevious?: () => void;
  onOpenHistory?: (historyIndex: number) => void;
  canReturnToPrevious?: boolean;
}

interface StepContextMenuState {
  stepId: string;
  x: number;
  y: number;
}

export function ExplanationTurnCard({
  turn,
  turnIndex,
  isLatest = false,
  onRevealReference,
  selectedStepId,
  currentGranularity,
  granularityRequestRunning = false,
  historyTrail = [],
  previousSessionLabel,
  onSelectStep,
  onLocateStepNode,
  onInspectStepNode,
  onHoverStep,
  onLeaveStep,
  onGranularityChange,
  onFollowUpStep,
  onReturnToPrevious,
  onOpenHistory,
  canReturnToPrevious = false,
  resolveArtifactText,
  onRequestArtifact,
}: ExplanationTurnCardProps) {
  const result = turn.explanation;
  const [contextMenu, setContextMenu] = useState<StepContextMenuState | null>(null);
  const granularity = currentGranularity ?? result?.granularity ?? "BUSINESS";
  const historyItems = historyTrail.slice(0, -1);
  const granularityOptions: StepGranularity[] = ["BUSINESS", "METHOD_CALL", "CODE_SEMANTIC"];
  const granularityHelpText = granularityRequestRunning
    ? "正在生成回答，完成后可重新选择粒度。"
    : "选择后会重新生成一轮解释，并追加到底部；不会改写当前回答。";

  useEffect(() => {
    if (!contextMenu) {
      return undefined;
    }
    function closeContextMenu(event: PointerEvent) {
      const target = event.target;
      if (
        target instanceof Element &&
        (target.closest(".canvas-context-menu") || target.closest("[data-step-context-trigger='true']"))
      ) {
        return;
      }
      setContextMenu(null);
    }
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setContextMenu(null);
      }
    }
    window.addEventListener("pointerdown", closeContextMenu);
    window.addEventListener("keydown", closeOnEscape);
    return () => {
      window.removeEventListener("pointerdown", closeContextMenu);
      window.removeEventListener("keydown", closeOnEscape);
    };
  }, [contextMenu]);

  const contextMenuActions = useMemo(() => {
    if (!contextMenu) {
      return [];
    }
    return [
      {
        id: "locate-explanation-step-node",
        label: "定位到图中节点",
        onSelect: () => {
          onLocateStepNode?.(contextMenu.stepId);
          setContextMenu(null);
        },
      },
      {
        id: "inspect-explanation-step-node",
        label: "编辑节点",
        onSelect: () => {
          onInspectStepNode?.(contextMenu.stepId);
          setContextMenu(null);
        },
      },
    ];
  }, [contextMenu, onInspectStepNode, onLocateStepNode]);

  function handleStepContextMenu(event: ReactMouseEvent<HTMLButtonElement>, stepId: string) {
    if (!onLocateStepNode && !onInspectStepNode) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    setContextMenu({
      stepId,
      x: event.clientX,
      y: event.clientY,
    });
  }

  return (
    <article className="assistant-turn-card assistant-turn-explanation">
      <AssistantTurnHeader turn={turn} turnIndex={turnIndex} isLatest={isLatest} />
      {!result && turn.failure ? (
        <AssistantFailureNotice failure={turn.failure} />
      ) : !result ? (
        <p className="muted assistant-result-text">讲解结果尚未返回。</p>
      ) : (
        <div className="assistant-card-flow">
          <div className="assistant-explanation-tools">
            {historyItems.length > 0 && onOpenHistory ? (
              <div className="panel-actions" aria-label="讲解历史路径">
                {historyItems.map((label, index) => (
                  <Button
                    key={`${label}-${index}`}
                    compact
                    className="assistant-wrap-token"
                    aria-label={`讲解历史：${label}`}
                    onClick={() => onOpenHistory(index)}
                  >
                    {label}
                  </Button>
                ))}
              </div>
            ) : null}
            {canReturnToPrevious && onReturnToPrevious ? (
              <Button
                compact
                className="assistant-wrap-token"
                aria-label={`返回上一讲解：${previousSessionLabel ?? "上一讲解"}`}
                onClick={onReturnToPrevious}
              >
                返回上一讲解
              </Button>
            ) : null}
            {onGranularityChange ? (
              <section className="assistant-explanation-rerun" aria-label="重新解释粒度">
                <div className="assistant-explanation-rerun-copy">
                  <strong className="assistant-result-text">重新解释粒度</strong>
                  <p className="muted assistant-result-text">{granularityHelpText}</p>
                </div>
                <div className="workbench-granularity-group" aria-label="重新解释粒度选项">
                  {granularityOptions.map((option) => {
                    const label = stepGranularityLabel(option);
                    const isCurrent = granularity === option;
                    return (
                      <button
                        key={option}
                        type="button"
                        aria-label={`按${label}重新解释`}
                        className={isCurrent ? "workbench-granularity active" : "workbench-granularity"}
                        disabled={granularityRequestRunning}
                        onClick={() => onGranularityChange(option)}
                      >
                        <span>{label}</span>
                        {isCurrent ? <span className="workbench-granularity-current">当前</span> : null}
                      </button>
                    );
                  })}
                </div>
              </section>
            ) : null}
          </div>
          <AssistantPromptDisclosure
            promptPreview={result.promptPreview}
            promptPreviewArtifactId={result.promptPreviewArtifactId}
            resolveArtifactText={resolveArtifactText}
            onRequestArtifact={onRequestArtifact}
          />
          {result.steps.map((step) => (
            <section
              key={step.stepId}
              className={selectedStepId === step.stepId ? "assistant-evidence-block active" : "assistant-evidence-block"}
            >
              <button
                type="button"
                data-step-context-trigger="true"
                className="assistant-step-selector"
                onClick={() => onSelectStep?.(step.stepId)}
                onContextMenu={(event) => handleStepContextMenu(event, step.stepId)}
                onMouseEnter={() => onHoverStep?.(step.stepId)}
                onMouseLeave={() => onLeaveStep?.()}
                onFocus={() => onHoverStep?.(step.stepId)}
                onBlur={() => onLeaveStep?.()}
              >
                <strong className="assistant-result-text">{step.title}</strong>
                <span className="badge">{stepKindLabel(step.kind)}</span>
              </button>
              <p className="assistant-result-text">{step.description}</p>
              {step.evidence.length > 0 ? (
                <div className="assistant-evidence-list">
                  {step.evidence.map((finding) => (
                    <div key={finding.id} className="assistant-evidence-item">
                      <strong className="assistant-result-text">{finding.claim}</strong>
                      {finding.references.map((reference, index) => (
                        <Button
                          key={`${finding.id}:${index}`}
                          compact
                          className="assistant-wrap-token"
                          onClick={() => onRevealReference(reference)}
                        >
                          {formatResultEvidenceReference(reference)}
                        </Button>
                      ))}
                    </div>
                  ))}
                </div>
              ) : null}
              {step.followUpQuestions[0] && onFollowUpStep ? (
                <Button
                  compact
                  className="assistant-wrap-token"
                  onClick={() => onFollowUpStep(step.stepId, step.followUpQuestions[0])}
                >
                  继续追问
                </Button>
              ) : null}
            </section>
          ))}
          {contextMenu ? <GraphContextMenu x={contextMenu.x} y={contextMenu.y} actions={contextMenuActions} /> : null}
        </div>
      )}
    </article>
  );
}

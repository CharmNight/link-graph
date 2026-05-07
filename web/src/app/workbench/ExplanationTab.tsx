import { useEffect, useRef, useState } from "react";
import { stepGranularityLabel } from "../labels";
import type {
  ExplanationWorkbenchState,
  ResultEvidenceReference,
  StepGranularity,
  WorkbenchSectionId,
  WorkbenchSectionPreferences,
} from "../types";
import { StepDetail } from "./StepDetail";
import { StepList } from "./StepList";
import { WorkbenchSection } from "./WorkbenchSection";
import { resolveEffectiveWorkbenchSectionPreferences } from "./workbenchSections";
import { RequestPromptDisclosure } from "../components/RequestPromptDisclosure";

interface ExplanationTabProps {
  state: ExplanationWorkbenchState;
  onSelectStep: (stepId: string) => void;
  onLocateStepNode: (stepId: string) => void;
  onInspectStepNode: (stepId: string) => void;
  onGranularityChange: (granularity: StepGranularity) => void;
  onHoverStep?: (stepId: string) => void;
  onLeaveStep?: () => void;
  onAddToDraft: (stepId: string) => void;
  onDrillDown: (stepId: string) => void;
  onFollowUp: (stepId: string, question?: string) => void;
  onRevealReference: (reference: ResultEvidenceReference) => void;
  onReturnToPrevious?: () => void;
  onOpenHistory?: (historyIndex: number) => void;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
  sectionPreferences?: WorkbenchSectionPreferences | null;
  onSectionPreferenceChange?: (sectionId: WorkbenchSectionId, expanded: boolean) => void;
}

export function ExplanationTab({
  state,
  onSelectStep,
  onLocateStepNode,
  onInspectStepNode,
  onGranularityChange,
  onHoverStep = () => undefined,
  onLeaveStep = () => undefined,
  onAddToDraft,
  onDrillDown,
  onFollowUp,
  onRevealReference,
  onReturnToPrevious = () => undefined,
  onOpenHistory = () => undefined,
  resolveArtifactText,
  onRequestArtifact,
  sectionPreferences,
  onSectionPreferenceChange = () => undefined,
}: ExplanationTabProps) {
  const layoutRef = useRef<HTMLDivElement | null>(null);
  const [layoutHeight, setLayoutHeight] = useState<number | null>(null);
  const [localSectionPreferences, setLocalSectionPreferences] = useState<WorkbenchSectionPreferences>(
    () => sectionPreferences ?? {},
  );
  const [promptSectionExpanded, setPromptSectionExpanded] = useState(true);
  const rawSectionPreferences = sectionPreferences ?? localSectionPreferences;
  const steps = state.result?.steps ?? [];
  const selectedStep = steps.find((step) => step.stepId === state.selectedStepId) ?? steps[0] ?? null;
  const granularityOptions: StepGranularity[] = ["BUSINESS", "METHOD_CALL", "CODE_SEMANTIC"];
  const currentSessionLabel = state.currentSessionLabel ?? "当前链路讲解";
  const historyTrail = state.historyTrail ?? [currentSessionLabel];
  const historyItems = historyTrail.slice(0, -1);
  const effectiveSectionPreferences = resolveEffectiveWorkbenchSectionPreferences({
    tab: "explanation",
    preferences: rawSectionPreferences,
    layoutHeight,
  });

  useEffect(() => {
    if (sectionPreferences == null) {
      return;
    }
    setLocalSectionPreferences(sectionPreferences);
  }, [sectionPreferences]);

  useEffect(() => {
    const layoutNode = layoutRef.current;
    if (!layoutNode) {
      return undefined;
    }

    function measureLayoutHeight() {
      const nextHeight = layoutNode?.getBoundingClientRect().height ?? 0;
      setLayoutHeight(nextHeight > 0 ? Math.round(nextHeight) : null);
    }

    measureLayoutHeight();
    window.addEventListener("resize", measureLayoutHeight);

    if (typeof ResizeObserver === "undefined") {
      return () => {
        window.removeEventListener("resize", measureLayoutHeight);
      };
    }

    const observer = new ResizeObserver(() => {
      measureLayoutHeight();
    });
    observer.observe(layoutNode);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", measureLayoutHeight);
    };
  }, []);

  function handleSectionToggle(sectionId: WorkbenchSectionId, expanded: boolean) {
    if (sectionPreferences == null) {
      setLocalSectionPreferences((current) => ({
        ...current,
        [sectionId]: expanded,
      }));
    }
    onSectionPreferenceChange(sectionId, expanded);
  }

  return (
    <section className="workbench-tab explanation-tab">
      <div className="workbench-tab-head">
        <div className="workbench-explanation-head">
          <p className="eyebrow">讲解</p>
          <h2>步骤化阅读器</h2>
          {historyItems.length > 0 ? (
            <div className="workbench-explanation-history" aria-label="讲解历史路径">
              {historyItems.map((label, index) => (
                <button
                  key={`${label}-${index}`}
                  type="button"
                  className="ghost-button compact"
                  onClick={() => onOpenHistory(index)}
                  aria-label={`讲解历史：${label}`}
                >
                  {label}
                </button>
              ))}
            </div>
          ) : null}
          <div className="workbench-explanation-session-row">
            <span className="workbench-session-label">{currentSessionLabel}</span>
            {state.canReturnToPrevious && state.previousSessionLabel ? (
              <button
                type="button"
                className="ghost-button compact"
                onClick={onReturnToPrevious}
                aria-label={`返回上一讲解：${state.previousSessionLabel}`}
              >
                返回上一讲解
              </button>
            ) : null}
          </div>
        </div>
        <div className="workbench-granularity-group" aria-label="讲解维度切换">
          {granularityOptions.map((granularity) => (
            <button
              key={granularity}
              type="button"
              aria-pressed={state.granularity === granularity}
              className={state.granularity === granularity ? "workbench-granularity active" : "workbench-granularity"}
              onClick={() => onGranularityChange(granularity)}
            >
              {stepGranularityLabel(granularity)}
            </button>
          ))}
        </div>
      </div>
      <div ref={layoutRef} className="workbench-tab-body workbench-page-flow explanation-layout">
        {state.result?.promptPreview?.trim() || state.result?.promptPreviewArtifactId ? (
          <WorkbenchSection
            title="提示词"
            expanded={promptSectionExpanded}
            onToggle={setPromptSectionExpanded}
          >
            <RequestPromptDisclosure
              promptPreview={state.result?.promptPreview ?? null}
              promptPreviewArtifactId={state.result?.promptPreviewArtifactId ?? null}
              promptPreviewAvailable={Boolean(state.result?.promptPreview?.trim() || state.result?.promptPreviewArtifactId)}
              resolveArtifactText={resolveArtifactText}
              onRequestArtifact={onRequestArtifact}
            />
          </WorkbenchSection>
        ) : null}
        <WorkbenchSection
          sectionId="explanation.step-list"
          title="步骤列表"
          expanded={effectiveSectionPreferences["explanation.step-list"] ?? true}
          onToggle={(nextExpanded) => handleSectionToggle("explanation.step-list", nextExpanded)}
          meta={<span className="badge">{steps.length}</span>}
        >
          <StepList
            steps={steps}
            selectedStepId={selectedStep?.stepId ?? null}
            onSelectStep={onSelectStep}
            onLocateStepNode={onLocateStepNode}
            onInspectStepNode={onInspectStepNode}
            onHoverStep={onHoverStep}
            onLeaveStep={onLeaveStep}
            showTitle={false}
          />
        </WorkbenchSection>
        <WorkbenchSection
          title="步骤详情"
          expanded={effectiveSectionPreferences["explanation.step-detail"] ?? true}
          onToggle={(nextExpanded) => handleSectionToggle("explanation.step-detail", nextExpanded)}
        >
          <StepDetail
            step={selectedStep}
            onAddToDraft={onAddToDraft}
            onDrillDown={onDrillDown}
            onFollowUp={onFollowUp}
            onRevealReference={onRevealReference}
            showEyebrow={false}
          />
        </WorkbenchSection>
      </div>
    </section>
  );
}

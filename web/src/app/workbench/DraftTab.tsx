import { useEffect, useRef, useState } from "react";
import type {
  AsyncRequestState,
  DraftImplementationSuggestionState,
  DraftWorkbenchEntry,
  DraftWorkbenchViewState,
  StageEligibilityDecision,
  WorkbenchSectionId,
  WorkbenchSectionPreferences,
} from "../types";
import { DraftChangePanel } from "./DraftChangePanel";
import { DraftDetailPanel } from "./DraftDetailPanel";
import { DraftNotePanel } from "./DraftNotePanel";
import { GenerationPlanPanel } from "../components/GenerationPlanPanel";
import { WorkbenchSection } from "./WorkbenchSection";
import { resolveEffectiveWorkbenchSectionPreferences } from "./workbenchSections";

interface DraftTabProps {
  state: DraftWorkbenchViewState;
  implementationSuggestion?: DraftImplementationSuggestionState | null;
  implementationSuggestionRequestState?: AsyncRequestState | null;
  implementationSuggestionEligibilityDecision?: StageEligibilityDecision | null;
  draftVersion?: number | null;
  codeDiffStatus?: "MISSING" | "RUNNING" | "FRESH" | "STALE" | "FAILED";
  codeDiffDraftVersion?: number | null;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
  onRequestGeneratePlan?: () => void;
  onOpenAuditWorkbench?: () => void;
  onToggleCompare: () => void;
  onSelectEntry: (entryId: string) => void;
  onLocateChangeNode: (entryId: string) => void;
  onUnconfirmChange: (entryId: string) => void;
  onOpenNote: (entryId: string) => void;
  onLocateNoteNode: (entryId: string) => void;
  resolveNodeTitle: (nodeId: string) => string;
  sectionPreferences?: WorkbenchSectionPreferences | null;
  onSectionPreferenceChange?: (sectionId: WorkbenchSectionId, expanded: boolean) => void;
}

export function DraftTab({
  state,
  implementationSuggestion = null,
  implementationSuggestionRequestState = null,
  implementationSuggestionEligibilityDecision = null,
  draftVersion = null,
  codeDiffStatus = "MISSING",
  codeDiffDraftVersion = null,
  resolveArtifactText,
  onRequestArtifact,
  onRequestGeneratePlan = () => undefined,
  onOpenAuditWorkbench,
  onToggleCompare,
  onSelectEntry,
  onLocateChangeNode,
  onUnconfirmChange,
  onOpenNote,
  onLocateNoteNode,
  resolveNodeTitle,
  sectionPreferences,
  onSectionPreferenceChange = () => undefined,
}: DraftTabProps) {
  const layoutRef = useRef<HTMLDivElement | null>(null);
  const [layoutHeight, setLayoutHeight] = useState<number | null>(null);
  const [localSectionPreferences, setLocalSectionPreferences] = useState<WorkbenchSectionPreferences>(
    () => sectionPreferences ?? {},
  );
  const rawSectionPreferences = sectionPreferences ?? localSectionPreferences;
  const selectedEntry = state.draftState.draftChanges.find((entry) => entry.entryId === state.selectedEntryId)
    ?? state.draftState.draftNotes.find((entry) => entry.entryId === state.selectedEntryId)
    ?? state.draftState.draftChanges[0]
    ?? state.draftState.draftNotes[0]
    ?? null;
  const compareAvailable = selectedEntry?.kind === "CHANGE";
  const effectiveSectionPreferences = resolveEffectiveWorkbenchSectionPreferences({
    tab: "draft",
    preferences: rawSectionPreferences,
    layoutHeight,
  });
  const detailTitle = resolveDraftDetailTitle(selectedEntry);
  const compareModeLabel = compareAvailable
    ? state.compareMode === "after" ? "当前显示：修改后" : "当前显示：前后对比"
    : "当前显示：说明项";
  const implementationSuggestionStatusLabel = resolveDerivedArtifactStatusLabel(
    implementationSuggestion?.status ?? "MISSING",
    implementationSuggestion?.generationPlanDraftVersion ?? null,
  );
  const codeDiffStatusLabel = resolveDerivedArtifactStatusLabel(codeDiffStatus, codeDiffDraftVersion);

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
      const nextHeight = layoutNode.getBoundingClientRect().height;
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
    <section className="workbench-tab draft-tab overflow-auto block m-scrollbar">
      <div className="workbench-tab-head mb10px">
        <div>
          <p className="eyebrow">草稿</p>
          <h2>统一业务真相层</h2>
          <p className="muted">默认看修改后，可一键切换前后对比；刚确认的问答变更会直接出现在这里。</p>
          <div className="panel-actions">
            <span className="badge">草稿版本 {draftVersion != null ? `v${draftVersion}` : "未建立"}</span>
            <span className="badge">实现建议：{implementationSuggestionStatusLabel}</span>
            <span className="badge">代码 diff：{codeDiffStatusLabel}</span>
          </div>
        </div>
        <div className="workbench-draft-head-actions flex-col md:flex-row shrink-0">
          <span className="workbench-compare-mode">{compareModeLabel}</span>
          <button type="button" className="workbench-compare-mode " onClick={onToggleCompare} disabled={!compareAvailable}>
            {compareAvailable ? (state.compareMode === "after" ? "一键对比前后" : "切回修改后") : "说明项无需前后对比"}
          </button>
        </div>
      </div>
      <div ref={layoutRef} className="workbench-tab-body draft-layout block">
        <div className="workbench-draft-sidebar mb-10px">
          <WorkbenchSection
            title="草稿变更项"
            expanded={effectiveSectionPreferences["draft.change-list"] ?? true}
            onToggle={(nextExpanded) => handleSectionToggle("draft.change-list", nextExpanded)}
            meta={<span className="badge">{state.draftState.draftChanges.length}</span>}
            minBodyHeight={160}
          >
            <DraftChangePanel
              changes={state.draftState.draftChanges}
              selectedEntryId={selectedEntry?.entryId ?? null}
              onSelectEntry={onSelectEntry}
              showTitle={false}
            />
          </WorkbenchSection>
          <WorkbenchSection
            title="草稿说明项"
            expanded={effectiveSectionPreferences["draft.note-list"] ?? false}
            onToggle={(nextExpanded) => handleSectionToggle("draft.note-list", nextExpanded)}
            meta={<span className="badge">{state.draftState.draftNotes.length}</span>}
            minBodyHeight={140}
          >
            <DraftNotePanel
              notes={state.draftState.draftNotes}
              selectedEntryId={selectedEntry?.entryId ?? null}
              onSelectEntry={onSelectEntry}
              showTitle={false}
            />
          </WorkbenchSection>
        </div>
        <WorkbenchSection
          title={detailTitle}
          expanded={effectiveSectionPreferences["draft.detail"] ?? true}
          onToggle={(nextExpanded) => handleSectionToggle("draft.detail", nextExpanded)}
          meta={selectedEntry ? <span className="badge">{selectedEntry.kind === "CHANGE" ? "变更" : "说明"}</span> : null}
          minBodyHeight={260}
        >
          <DraftDetailPanel
            entry={selectedEntry}
            compareMode={state.compareMode}
            onLocateChangeNode={onLocateChangeNode}
            onUnconfirmChange={onUnconfirmChange}
            onOpenNote={onOpenNote}
            onLocateNoteNode={onLocateNoteNode}
            resolveNodeTitle={resolveNodeTitle}
            showTitle={false}
          />
        </WorkbenchSection>
        <div className="workbench-draft-implementation-suggestion">
          <GenerationPlanPanel
            plan={implementationSuggestion?.summary ? {
              source: implementationSuggestion.source ?? "MOCK",
              summary: implementationSuggestion.summary,
              warnings: implementationSuggestion.warnings,
              promptPreview: implementationSuggestion.promptPreview ?? null,
              promptPreviewArtifactId: implementationSuggestion.promptPreviewArtifactId ?? null,
              items: implementationSuggestion.items,
            } : null}
            requestState={implementationSuggestionRequestState}
            eligibilityDecision={implementationSuggestionEligibilityDecision}
            draftVersion={draftVersion}
            generationPlanDraftVersion={implementationSuggestion?.generationPlanDraftVersion ?? null}
            resolveArtifactText={resolveArtifactText}
            onRequestArtifact={onRequestArtifact}
            onOpenDraftWorkbench={() => undefined}
            onOpenAuditWorkbench={onOpenAuditWorkbench}
            onRequestGeneratePlan={onRequestGeneratePlan}
          />
        </div>
      </div>
    </section>
  );
}

function resolveDraftDetailTitle(entry: DraftWorkbenchEntry | null): string {
  if (!entry) {
    return "草稿说明详情";
  }
  return entry.kind === "CHANGE" ? "草稿变更详情" : "草稿说明详情";
}

function resolveDerivedArtifactStatusLabel(
  status: "MISSING" | "RUNNING" | "FRESH" | "STALE" | "FAILED",
  sourceDraftVersion: number | null,
): string {
  switch (status) {
    case "RUNNING":
      return "生成中";
    case "FRESH":
      return sourceDraftVersion != null ? `最新（v${sourceDraftVersion}）` : "最新";
    case "STALE":
      return sourceDraftVersion != null ? `待刷新（v${sourceDraftVersion}）` : "待刷新";
    case "FAILED":
      return "失败";
    case "MISSING":
    default:
      return "未生成";
  }
}

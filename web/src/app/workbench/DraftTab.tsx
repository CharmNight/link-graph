import { useEffect, useRef, useState } from "react";
import type {
  AsyncRequestState,
  DraftValidationState,
  DraftImplementationSuggestionState,
  DraftWorkbenchEntry,
  DraftWorkbenchViewState,
  GenerationPlanDiscussionSession,
  WorkbenchSectionId,
  WorkbenchSectionPreferences,
} from "../types";
import { DraftChangePanel } from "./DraftChangePanel";
import { DraftDetailPanel } from "./DraftDetailPanel";
import { DraftNotePanel } from "./DraftNotePanel";
import { GenerationPlanPanel } from "../components/GenerationPlanPanel";
import { DraftValidationPanel } from "./DraftValidationPanel";
import { WorkbenchSection } from "./WorkbenchSection";
import { resolveEffectiveWorkbenchSectionPreferences } from "./workbenchSections";

interface DraftTabProps {
  state: DraftWorkbenchViewState;
  implementationSuggestion?: DraftImplementationSuggestionState | null;
  implementationSuggestionRequestState?: AsyncRequestState | null;
  draftValidationState?: DraftValidationState | null;
  implementationSuggestionDiscussionQuestionDraft?: string;
  implementationSuggestionDiscussionSession?: GenerationPlanDiscussionSession | null;
  implementationSuggestionDiscussionRequestState?: AsyncRequestState | null;
  draftVersion?: number | null;
  codeDiffStatus?: "MISSING" | "RUNNING" | "FRESH" | "STALE" | "FAILED";
  codeDiffDraftVersion?: number | null;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
  onRequestGeneratePlan?: () => void;
  onImplementationSuggestionDiscussionQuestionDraftChange?: (value: string) => void;
  onSubmitImplementationSuggestionDiscussion?: () => void;
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
  draftValidationState = null,
  implementationSuggestionDiscussionQuestionDraft = "",
  implementationSuggestionDiscussionSession = null,
  implementationSuggestionDiscussionRequestState = null,
  draftVersion = null,
  codeDiffStatus = "MISSING",
  codeDiffDraftVersion = null,
  resolveArtifactText,
  onRequestArtifact,
  onRequestGeneratePlan = () => undefined,
  onImplementationSuggestionDiscussionQuestionDraftChange = () => undefined,
  onSubmitImplementationSuggestionDiscussion = () => undefined,
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
  const selectedEntryHasComparableDraftState = hasComparableDraftState(selectedEntry);
  const selectedEntryIsNote = selectedEntry?.kind === "NOTE";
  const compareAvailable = selectedEntry?.kind === "CHANGE";
  const effectiveSectionPreferences = resolveEffectiveWorkbenchSectionPreferences({
    tab: "draft",
    preferences: rawSectionPreferences,
    layoutHeight,
  });
  const detailTitle = resolveDraftDetailTitle(selectedEntry);
  const compareModeLabel = resolveCompareModeLabel(
    selectedEntry,
    state.compareMode,
    selectedEntryHasComparableDraftState,
  );
  const compareActionLabel = resolveCompareActionLabel(
    selectedEntry,
    state.compareMode,
    selectedEntryHasComparableDraftState,
  );
  const draftIntro = resolveDraftIntroCopy(
    selectedEntry,
    state.compareMode,
    selectedEntryHasComparableDraftState,
  );
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
    <section className="workbench-tab draft-tab m-scrollbar">
      <div className="workbench-tab-head mb10px">
        <div>
          <p className="eyebrow">草稿</p>
          <h2>统一业务真相层</h2>
          <p className="muted">{draftIntro}</p>
          <div className="panel-actions">
            <span className="badge">草稿版本 {draftVersion != null ? `v${draftVersion}` : "未建立"}</span>
            <span className="badge">实现建议：{implementationSuggestionStatusLabel}</span>
            <span className="badge">代码 diff：{codeDiffStatusLabel}</span>
          </div>
        </div>
        <div className="workbench-draft-head-actions flex-col md:flex-row shrink-0">
          <span className="workbench-compare-mode">{compareModeLabel}</span>
          <button type="button" className="workbench-compare-mode " onClick={onToggleCompare} disabled={!compareAvailable}>
            {compareActionLabel}
          </button>
        </div>
      </div>
      <div ref={layoutRef} className="workbench-tab-body draft-layout">
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
            expanded={selectedEntryIsNote || (effectiveSectionPreferences["draft.note-list"] ?? false)}
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
        <WorkbenchSection
          title="草稿验证"
          expanded={effectiveSectionPreferences["draft.validation"] ?? true}
          onToggle={(nextExpanded) => handleSectionToggle("draft.validation", nextExpanded)}
          minBodyHeight={180}
        >
          <DraftValidationPanel
            validationState={draftValidationState}
            onOpenAuditWorkbench={onOpenAuditWorkbench}
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
            discussionQuestionDraft={implementationSuggestionDiscussionQuestionDraft}
            discussionSession={implementationSuggestionDiscussionSession}
            discussionRequestState={implementationSuggestionDiscussionRequestState}
            draftVersion={draftVersion}
            generationPlanDraftVersion={implementationSuggestion?.generationPlanDraftVersion ?? null}
            resolveArtifactText={resolveArtifactText}
            onRequestArtifact={onRequestArtifact}
            onRequestGeneratePlan={onRequestGeneratePlan}
            onDiscussionQuestionDraftChange={onImplementationSuggestionDiscussionQuestionDraftChange}
            onSubmitDiscussion={onSubmitImplementationSuggestionDiscussion}
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

function hasComparableDraftState(entry: DraftWorkbenchEntry | null): boolean {
  if (!entry || entry.kind !== "CHANGE") {
    return false;
  }
  const beforeState = entry.beforeState?.trim();
  const afterState = entry.afterState?.trim();
  return Boolean(beforeState) && Boolean(afterState);
}

function resolveDraftIntroCopy(
  entry: DraftWorkbenchEntry | null,
  compareMode: "after" | "compare",
  hasComparableState: boolean,
): string {
  if (entry?.kind === "CHANGE" && hasComparableState) {
    return compareMode === "after"
      ? "当前条目已生成修改后状态，可切到前后对比查看完整变更。"
      : "当前条目正在展示前后对比，可直接核对修改前后的差异。";
  }
  if (entry?.kind === "CHANGE") {
    return compareMode === "after"
      ? "当前条目已锁定变更范围，可切到链路对比查看受影响节点与关系。"
      : "当前条目正在展示链路对比，可直接核对本次变更覆盖的节点与关系。";
  }
  return "说明项记录业务解释，不参与前后对比；刚确认的问答变更会直接出现在这里。";
}

function resolveCompareModeLabel(
  entry: DraftWorkbenchEntry | null,
  compareMode: "after" | "compare",
  hasComparableState: boolean,
): string {
  if (entry?.kind !== "CHANGE") {
    return "当前显示：说明项";
  }
  if (hasComparableState) {
    return compareMode === "after" ? "当前显示：修改后" : "当前显示：前后对比";
  }
  return compareMode === "after" ? "当前显示：变更意图" : "当前显示：链路对比";
}

function resolveCompareActionLabel(
  entry: DraftWorkbenchEntry | null,
  compareMode: "after" | "compare",
  hasComparableState: boolean,
): string {
  if (entry?.kind !== "CHANGE") {
    return "说明项无需前后对比";
  }
  if (hasComparableState) {
    return compareMode === "after" ? "一键对比前后" : "切回修改后";
  }
  return compareMode === "after" ? "切换链路对比" : "切回变更意图";
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

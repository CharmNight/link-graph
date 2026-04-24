import { useMemo } from "react";
import { buildDraftCompareProjection } from "../draftCompareProjection";
import type {
  AnalysisDisplayMode,
  AuditWorkbenchState,
  AsyncRequestState,
  DraftImplementationSuggestionState,
  DraftWorkbenchEntry,
  DraftWorkbenchState,
  DraftWorkbenchViewState,
  ExplanationWorkbenchState,
  FlowchartViewDocument,
  GenerationPlan,
  GeneratedCodeDraft,
  GraphBeautificationResult,
  LinkGraphDocument,
  LinkGraphNode,
  QaRequestRecoveryState,
  ResourceRelationViewDocument,
} from "../types";

type WorkbenchTab = "explanation" | "audit" | "draft" | "code";
type CodeDiffStatus = "MISSING" | "RUNNING" | "FRESH" | "STALE" | "FAILED";

interface ExplanationHistoryEntry {
  sessionLabel: string;
}

interface UseWorkbenchDerivedStateArgs {
  analysisDisplayMode: AnalysisDisplayMode;
  activeWorkbenchTab: WorkbenchTab;
  auditResult: AuditWorkbenchState["result"];
  auditRequestState: AsyncRequestState;
  qaRequestRecoveryState: QaRequestRecoveryState;
  auditQuestionDraft: string;
  auditTargetNodeIds: string[];
  auditTargetTitle: string | null;
  selectedAuditChangeId: string | null;
  selectedAuditThreadId: string | null;
  graphBeautificationResult: GraphBeautificationResult | null;
  graphBeautificationRequestState: AsyncRequestState;
  selectedExplanationStepId: string | null;
  selectedExplanationGranularity: ExplanationWorkbenchState["granularity"];
  hoveredExplanationStepId: string | null;
  explanationHistory: ExplanationHistoryEntry[];
  currentExplanationSessionLabel: string;
  draftWorkbenchState: DraftWorkbenchState;
  selectedDraftEntryId: string | null;
  draftCompareMode: "after" | "compare";
  draftGraph: LinkGraphDocument | null;
  semanticFactGraph: LinkGraphDocument | null;
  workspaceBaseGraph: LinkGraphDocument | null;
  factGraphView: { visibleGraph: LinkGraphDocument; anchorNodeId?: string | null };
  flowchartView: FlowchartViewDocument;
  resourceRelationView: ResourceRelationViewDocument;
  generationPlan: GenerationPlan | null;
  generationPlanRequestState: AsyncRequestState;
  generationPlanDraftVersion: number | null;
  draftVersion: number | null;
  generatedCodeDrafts: GeneratedCodeDraft[];
  generatedCodeDraftVersion: number | null;
  codeDraftRequestState: AsyncRequestState;
  resolveNodeOwnerSignature: (node: LinkGraphNode | null | undefined) => string | null;
  resolveEntryOwnerSignatures: (
    entry: DraftWorkbenchEntry | null,
    graph: LinkGraphDocument,
  ) => Set<string>;
  resolveDraftEntryTargetNodeIds: (entry: DraftWorkbenchEntry | null) => string[];
  resolveDisplayedNodeId: (nodeId: string | null | undefined, nodes: LinkGraphNode[]) => string | null;
  overlayDraftEntryOntoFlowchartView: (args: {
    view: FlowchartViewDocument;
    workingGraph: LinkGraphDocument | null;
    entry: DraftWorkbenchEntry | null;
    activeWorkbenchTab: WorkbenchTab;
    compareMode: "after" | "compare";
  }) => FlowchartViewDocument;
}

export function useWorkbenchDerivedState(args: UseWorkbenchDerivedStateArgs) {
  const activeViewGraph = args.analysisDisplayMode === "FLOWCHART"
    ? args.flowchartView.visibleGraph
    : args.analysisDisplayMode === "RESOURCE_RELATION_VIEW"
      ? args.resourceRelationView.visibleGraph
      : args.factGraphView.visibleGraph;

  const activeAnchorNodeId = args.analysisDisplayMode === "FLOWCHART"
    ? args.flowchartView.anchorNodeId ?? null
    : args.analysisDisplayMode === "RESOURCE_RELATION_VIEW"
      ? args.resourceRelationView.anchorNodeId ?? null
      : args.factGraphView.anchorNodeId ?? null;

  const activeMethodSignature = useMemo(() => {
    const activeAnchorNode = activeViewGraph.nodes.find((node) => node.id === activeAnchorNodeId) ?? null;
    return args.resolveNodeOwnerSignature(activeAnchorNode);
  }, [activeAnchorNodeId, activeViewGraph.nodes, args.resolveNodeOwnerSignature]);

  const filteredDraftWorkbenchState = useMemo(() => {
    if (!activeMethodSignature) {
      return args.draftWorkbenchState;
    }
    const filterEntries = (entries: DraftWorkbenchEntry[]) => entries.filter((entry) => {
      const ownerSignatures = args.resolveEntryOwnerSignatures(entry, args.draftGraph ?? { nodes: [], edges: [] });
      if (ownerSignatures.size > 0) {
        return ownerSignatures.has(activeMethodSignature);
      }
      return args.resolveDraftEntryTargetNodeIds(entry)
        .some((nodeId) => args.resolveDisplayedNodeId(nodeId, activeViewGraph.nodes) != null);
    });
    return {
      draftChanges: filterEntries(args.draftWorkbenchState.draftChanges),
      draftNotes: filterEntries(args.draftWorkbenchState.draftNotes),
    };
  }, [
    activeMethodSignature,
    activeViewGraph.nodes,
    args.draftGraph,
    args.draftWorkbenchState,
    args.resolveDisplayedNodeId,
    args.resolveDraftEntryTargetNodeIds,
    args.resolveEntryOwnerSignatures,
  ]);

  const draftState: DraftWorkbenchViewState = {
    draftState: filteredDraftWorkbenchState,
    compareMode: args.draftCompareMode,
    selectedEntryId: args.selectedDraftEntryId,
  };

  const selectedDraftEntry = useMemo(
    () =>
      filteredDraftWorkbenchState.draftChanges.find((entry) => entry.entryId === args.selectedDraftEntryId)
      ?? filteredDraftWorkbenchState.draftNotes.find((entry) => entry.entryId === args.selectedDraftEntryId)
      ?? filteredDraftWorkbenchState.draftChanges[0]
      ?? filteredDraftWorkbenchState.draftNotes[0]
      ?? null,
    [filteredDraftWorkbenchState.draftChanges, filteredDraftWorkbenchState.draftNotes, args.selectedDraftEntryId],
  );

  const presentedFlowchartView = useMemo(
    () => args.overlayDraftEntryOntoFlowchartView({
      view: args.flowchartView,
      workingGraph: args.draftGraph,
      entry: selectedDraftEntry,
      activeWorkbenchTab: args.activeWorkbenchTab,
      compareMode: args.draftCompareMode,
    }),
    [args.activeWorkbenchTab, args.draftCompareMode, args.draftGraph, args.flowchartView, args.overlayDraftEntryOntoFlowchartView, selectedDraftEntry],
  );

  const selectedExplanationStep = args.graphBeautificationResult?.steps.find((step) => step.stepId === args.selectedExplanationStepId)
    ?? args.graphBeautificationResult?.steps?.[0]
    ?? null;
  const hoveredExplanationStep = args.graphBeautificationResult?.steps.find((step) => step.stepId === args.hoveredExplanationStepId)
    ?? null;
  const explanationFocusNodeId = args.activeWorkbenchTab === "explanation"
    ? hoveredExplanationStep?.primaryNodeId ?? selectedExplanationStep?.primaryNodeId ?? null
    : null;

  const draftChangedNodeIds = useMemo(
    () => Array.from(new Set(filteredDraftWorkbenchState.draftChanges.flatMap((entry) => args.resolveDraftEntryTargetNodeIds(entry)))),
    [filteredDraftWorkbenchState.draftChanges, args.resolveDraftEntryTargetNodeIds],
  );

  const draftCompareProjection = useMemo(
    () => {
      const referenceGraph = args.analysisDisplayMode === "FACT_GRAPH"
        ? args.semanticFactGraph
        : args.workspaceBaseGraph;
      return buildDraftCompareProjection({
        compareMode: args.draftCompareMode,
        selectedEntry: selectedDraftEntry,
        visibleGraph: activeViewGraph,
        referenceGraph,
        workingGraph: args.draftGraph ?? { nodes: [], edges: [] },
      });
    },
    [activeViewGraph, args.analysisDisplayMode, args.draftCompareMode, args.draftGraph, args.semanticFactGraph, args.workspaceBaseGraph, selectedDraftEntry],
  );

  const explanationState: ExplanationWorkbenchState = {
    result: args.graphBeautificationResult,
    requestState: args.graphBeautificationRequestState,
    selectedStepId: args.selectedExplanationStepId,
    granularity: args.selectedExplanationGranularity,
    historyDepth: args.explanationHistory.length,
    canReturnToPrevious: args.explanationHistory.length > 0,
    historyTrail: [
      ...args.explanationHistory.map((entry) => entry.sessionLabel),
      args.currentExplanationSessionLabel,
    ],
    currentSessionLabel: args.currentExplanationSessionLabel,
    previousSessionLabel: args.explanationHistory[args.explanationHistory.length - 1]?.sessionLabel ?? null,
  };

  const auditState: AuditWorkbenchState = {
    result: args.auditResult,
    requestState: args.auditRequestState,
    qaRequestRecoveryState: args.qaRequestRecoveryState,
    selectedChangeId: args.selectedAuditChangeId,
    selectedThreadId: args.selectedAuditThreadId,
    questionDraft: args.auditQuestionDraft,
    scopeLabel: buildAuditScopeLabel(args.auditTargetNodeIds, args.auditTargetTitle),
  };

  const draftImplementationSuggestionState: DraftImplementationSuggestionState = {
    status: args.generationPlan?.summary
      ? (
        args.draftVersion != null
        && args.generationPlanDraftVersion != null
        && args.generationPlanDraftVersion < args.draftVersion
          ? "STALE"
          : "FRESH"
      )
      : args.generationPlanRequestState.phase === "RUNNING"
        ? "RUNNING"
        : args.generationPlanRequestState.phase === "FAILED" || args.generationPlanRequestState.phase === "TIMED_OUT"
          ? "FAILED"
          : "MISSING",
    summary: args.generationPlan?.summary ?? null,
    items: args.generationPlan?.items ?? [],
    source: args.generationPlan?.source ?? null,
    warnings: args.generationPlan?.warnings ?? [],
    promptPreview: args.generationPlan?.promptPreview ?? null,
    promptPreviewArtifactId: args.generationPlan?.promptPreviewArtifactId ?? null,
    draftVersion: args.draftVersion,
    generationPlanDraftVersion: args.generationPlanDraftVersion,
  };

  const codeDiffStatus: CodeDiffStatus = args.generatedCodeDrafts.length > 0
    ? (
      args.draftVersion != null
      && args.generatedCodeDraftVersion != null
      && args.generatedCodeDraftVersion < args.draftVersion
        ? "STALE"
        : "FRESH"
    )
    : args.codeDraftRequestState.phase === "RUNNING"
      ? "RUNNING"
      : args.codeDraftRequestState.phase === "FAILED" || args.codeDraftRequestState.phase === "TIMED_OUT"
        ? "FAILED"
        : "MISSING";

  return {
    explanationState,
    auditState,
    draftImplementationSuggestionState,
    codeDiffStatus,
    activeViewGraph,
    selectedDraftEntry,
    presentedFlowchartView,
    explanationFocusNodeId,
    draftChangedNodeIds,
    draftCompareProjection,
    draftState,
  };
}

function buildAuditScopeLabel(targetNodeIds: string[], targetTitle: string | null): string {
  if (targetNodeIds.length === 0) {
    return "当前范围：整张链路";
  }
  if (targetNodeIds.length === 1) {
    return `当前节点：${targetTitle ?? targetNodeIds[0]}`;
  }
  return `当前范围：${targetNodeIds.length} 个节点`;
}

import { useMemo } from "react";
import {
  activeAnchorNodeIdForDisplayMode,
  activeVisibleGraphForDisplayMode,
  type AssistantStageTarget,
} from "../appDisplaySelectors";
import { buildDraftCompareProjection } from "../draftCompareProjection";
import {
  collectDraftChangedNodeIds,
  selectDraftWorkbenchEntry,
} from "../workbenchDraftModel";
import {
  deriveCodeDiffStatus,
  deriveDraftImplementationSuggestionState,
  type CodeDiffStatus,
} from "../workbenchStatusModel";
import type {
  AnalysisDisplayMode,
  ArchitectureGraphViewDocument,
  AssistantQaViewState,
  AsyncRequestState,
  ClassDiagramViewDocument,
  DraftWorkbenchEntry,
  DraftWorkbenchState,
  DraftWorkbenchViewState,
  AssistantExplanationViewState,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GenerationPlan,
  GeneratedCodeDraft,
  GraphBeautificationResult,
  LinkGraphDocument,
  LinkGraphNode,
  QaRequestRecoveryState,
  ResourceRelationViewDocument,
  ReviewGraphViewDocument,
} from "../types";

interface ExplanationHistoryEntry {
  sessionLabel: string;
}

interface UseWorkbenchDerivedStateArgs {
  analysisDisplayMode: AnalysisDisplayMode;
  activeAssistantTarget: AssistantStageTarget;
  qaResult: AssistantQaViewState["result"];
  qaRequestState: AsyncRequestState;
  qaRequestRecoveryState: QaRequestRecoveryState;
  qaTargetNodeIds: string[];
  qaTargetTitle: string | null;
  selectedQaChangeId: string | null;
  selectedQaThreadId: string | null;
  graphBeautificationResult: GraphBeautificationResult | null;
  graphBeautificationRequestState: AsyncRequestState;
  selectedExplanationStepId: string | null;
  selectedExplanationGranularity: AssistantExplanationViewState["granularity"];
  hoveredExplanationStepId: string | null;
  explanationHistory: ExplanationHistoryEntry[];
  currentExplanationSessionLabel: string;
  draftWorkbenchState: DraftWorkbenchState;
  selectedDraftEntryId: string | null;
  draftCompareMode: "after" | "compare";
  draftGraph: LinkGraphDocument | null;
  semanticFactGraph: LinkGraphDocument | null;
  workspaceBaseGraph: LinkGraphDocument | null;
  factGraphView: FactGraphViewDocument;
  flowchartView: FlowchartViewDocument;
  resourceRelationView: ResourceRelationViewDocument;
  architectureGraphView: ArchitectureGraphViewDocument;
  classDiagramView: ClassDiagramViewDocument;
  reviewGraphView: ReviewGraphViewDocument;
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
    activeAssistantTarget: AssistantStageTarget;
    compareMode: "after" | "compare";
  }) => FlowchartViewDocument;
}

export function useWorkbenchDerivedState(args: UseWorkbenchDerivedStateArgs) {
  const activeDisplayGraphDocuments = {
    factGraphView: args.factGraphView,
    flowchartView: args.flowchartView,
    resourceRelationView: args.resourceRelationView,
    architectureGraphView: args.architectureGraphView,
    classDiagramView: args.classDiagramView,
    reviewGraphView: args.reviewGraphView,
  };
  const activeViewGraph = activeVisibleGraphForDisplayMode(args.analysisDisplayMode, activeDisplayGraphDocuments);
  const activeAnchorNodeId = activeAnchorNodeIdForDisplayMode(args.analysisDisplayMode, activeDisplayGraphDocuments);

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
    () => selectDraftWorkbenchEntry(filteredDraftWorkbenchState, args.selectedDraftEntryId),
    [filteredDraftWorkbenchState, args.selectedDraftEntryId],
  );

  const presentedFlowchartView = useMemo(
    () => args.overlayDraftEntryOntoFlowchartView({
      view: args.flowchartView,
      workingGraph: args.draftGraph,
      entry: selectedDraftEntry,
      activeAssistantTarget: args.activeAssistantTarget,
      compareMode: args.draftCompareMode,
    }),
    [args.activeAssistantTarget, args.draftCompareMode, args.draftGraph, args.flowchartView, args.overlayDraftEntryOntoFlowchartView, selectedDraftEntry],
  );

  const selectedExplanationStep = args.graphBeautificationResult?.steps.find((step) => step.stepId === args.selectedExplanationStepId)
    ?? args.graphBeautificationResult?.steps?.[0]
    ?? null;
  const hoveredExplanationStep = args.graphBeautificationResult?.steps.find((step) => step.stepId === args.hoveredExplanationStepId)
    ?? null;
  const explanationFocusNodeId = args.activeAssistantTarget === "explanation"
    ? hoveredExplanationStep?.primaryNodeId ?? selectedExplanationStep?.primaryNodeId ?? null
    : null;

  const draftChangedNodeIds = useMemo(
    () => collectDraftChangedNodeIds(filteredDraftWorkbenchState, args.resolveDraftEntryTargetNodeIds),
    [filteredDraftWorkbenchState, args.resolveDraftEntryTargetNodeIds],
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

  const explanationState: AssistantExplanationViewState = {
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

  const qaState: AssistantQaViewState = {
    result: args.qaResult,
    requestState: args.qaRequestState,
    qaRequestRecoveryState: args.qaRequestRecoveryState,
    selectedChangeId: args.selectedQaChangeId,
    selectedThreadId: args.selectedQaThreadId,
    scopeLabel: buildQaScopeLabel(args.qaTargetNodeIds, args.qaTargetTitle),
  };

  const draftImplementationSuggestionState = deriveDraftImplementationSuggestionState({
    generationPlan: args.generationPlan,
    generationPlanRequestState: args.generationPlanRequestState,
    generationPlanDraftVersion: args.generationPlanDraftVersion,
    draftVersion: args.draftVersion,
  });

  const codeDiffStatus: CodeDiffStatus = deriveCodeDiffStatus({
    generatedCodeDrafts: args.generatedCodeDrafts,
    generatedCodeDraftVersion: args.generatedCodeDraftVersion,
    draftVersion: args.draftVersion,
    codeDraftRequestState: args.codeDraftRequestState,
  });

  return {
    explanationState,
    qaState,
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

function buildQaScopeLabel(targetNodeIds: string[], targetTitle: string | null): string {
  if (targetNodeIds.length === 0) {
    return "当前范围：整张链路";
  }
  if (targetNodeIds.length === 1) {
    return `当前节点：${targetTitle ?? targetNodeIds[0]}`;
  }
  return `当前范围：${targetNodeIds.length} 个节点`;
}

import { useState } from "react";
import type {
  AnalysisDisplayMode,
  AsyncRequestState,
  DiffItem,
  DraftWorkbenchState,
  DraftPatchApplyResult,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GeneratedCodeDraft,
  GeneratedCodeDraftWriteReport,
  GraphBeautificationResult,
  GraphPatch,
  GraphPatchResult,
  GraphSurfaceExperimentFlags,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphNode,
  MermaidIssue,
  OperationFeedback,
  ResourceRelationViewDocument,
  SourceNavigationState,
} from "../types";
import type { RequestFailureNotice } from "./bridgeCommandTypes";

const DEFAULT_ANALYSIS_DISPLAY_MODE: AnalysisDisplayMode = "FLOWCHART";

interface UseWorkbenchStateArgs {
  initialState: LinkGraphBootstrapState;
  initialGraph: LinkGraphDocument;
  initialAnchorNodeId: string | null;
  resolveRequestState: (state?: AsyncRequestState | null) => AsyncRequestState;
  resolveReferenceFactGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument | null;
  resolveFactGraphView: (state: LinkGraphBootstrapState) => FactGraphViewDocument;
  resolveFlowchartView: (state: LinkGraphBootstrapState) => FlowchartViewDocument;
  resolveResourceRelationView: (state: LinkGraphBootstrapState) => ResourceRelationViewDocument;
  resolveWorkingGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument | null;
  resolveDesignBaselineGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument | null;
  resolveSourceNavigationState: (state: LinkGraphBootstrapState) => SourceNavigationState;
  normalizeGraphNodes: (
    nodes: LinkGraphNode[],
    edges: LinkGraphEdge[],
    anchorNodeId: string | null,
    analysisDisplayMode: AnalysisDisplayMode,
  ) => LinkGraphNode[];
}

export function useWorkbenchState({
  initialState,
  initialGraph,
  initialAnchorNodeId,
  resolveRequestState,
  resolveReferenceFactGraph,
  resolveFactGraphView,
  resolveFlowchartView,
  resolveResourceRelationView,
  resolveWorkingGraph,
  resolveDesignBaselineGraph,
  resolveSourceNavigationState,
  normalizeGraphNodes,
}: UseWorkbenchStateArgs) {
  const [nodes, setNodes] = useState<LinkGraphNode[]>(() =>
    normalizeGraphNodes(
      initialGraph.nodes,
      initialGraph.edges,
      initialAnchorNodeId,
      initialState.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE,
    ),
  );
  const [edges, setEdges] = useState<LinkGraphEdge[]>(() => initialGraph.edges);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(
    () => initialState.selectedNodeId ?? initialGraph.nodes[0]?.id ?? null,
  );
  const [analysisDisplayMode, setAnalysisDisplayMode] = useState<AnalysisDisplayMode>(
    () => initialState.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE,
  );
  const [anchorNodeId, setAnchorNodeId] = useState<string | null>(() => initialAnchorNodeId);
  const [detailNodeId, setDetailNodeId] = useState<string | null>(null);
  const [auditRequestState, setAuditRequestState] = useState<AsyncRequestState>(() => resolveRequestState(initialState.auditRequestState));
  const [auditTargetNodeIds, setAuditTargetNodeIds] = useState<string[]>([]);
  const [auditQuestionDraft, setAuditQuestionDraft] = useState<string>(() => initialState.auditResult?.question ?? "");
  const [selectionGroupNodeIds, setSelectionGroupNodeIds] = useState<string[]>([]);
  const [collapsedNodeIds, setCollapsedNodeIds] = useState<string[]>([]);
  const [factGraph, setFactGraph] = useState<LinkGraphDocument | null>(() => resolveReferenceFactGraph(initialState));
  const [factGraphView, setFactGraphView] = useState<FactGraphViewDocument>(() => resolveFactGraphView(initialState));
  const [flowchartView, setFlowchartView] = useState<FlowchartViewDocument>(() => resolveFlowchartView(initialState));
  const [resourceRelationView, setResourceRelationView] = useState<ResourceRelationViewDocument>(
    () => resolveResourceRelationView(initialState),
  );
  const [draftGraph, setDraftGraph] = useState<LinkGraphDocument | null>(() => resolveWorkingGraph(initialState));
  const [draftWorkbenchState, setDraftWorkbenchState] = useState<DraftWorkbenchState>(
    () => initialState.draftWorkbenchState ?? { draftChanges: [], draftNotes: [] },
  );
  const [designBaseline, setDesignBaseline] = useState<LinkGraphDocument | null>(() => resolveDesignBaselineGraph(initialState));
  const [draftPatchPreview, setDraftPatchPreview] = useState<GraphPatch | null>(() => initialState.draftPatchPreview ?? null);
  const [lastAppliedDraftPatchPreview, setLastAppliedDraftPatchPreview] = useState<GraphPatch | null>(null);
  const [canUndoDraftPatchApply, setCanUndoDraftPatchApply] = useState<boolean>(() => initialState.canUndoDraftPatchApply ?? false);
  const [lastAppliedDraftPatchSummary, setLastAppliedDraftPatchSummary] = useState<string | null>(
    () => initialState.lastAppliedDraftPatchSummary ?? null,
  );
  const [auditResult, setAuditResult] = useState<GraphPatchResult | null>(() => initialState.auditResult ?? null);
  const [diffReviewResult, setDiffReviewResult] = useState<GraphPatchResult | null>(() => initialState.diffReviewResult ?? null);
  const [mermaidIssues, setMermaidIssues] = useState<MermaidIssue[]>(() => initialState.mermaidIssues ?? []);
  const [diffItems, setDiffItems] = useState<DiffItem[]>(() => initialState.diffItems);
  const [syncPreviewItems, setSyncPreviewItems] = useState(() => initialState.syncPreviewItems);
  const [generationPlan, setGenerationPlan] = useState(() => initialState.generationPlan ?? null);
  const [generationPlanRequestState, setGenerationPlanRequestState] = useState<AsyncRequestState>(() => resolveRequestState(initialState.generationPlanRequestState));
  const [diffReviewRequestState, setDiffReviewRequestState] = useState<AsyncRequestState>(() => resolveRequestState(initialState.diffReviewRequestState));
  const [graphBeautificationResult, setGraphBeautificationResult] = useState<GraphBeautificationResult | null>(
    () => initialState.graphBeautificationResult ?? null,
  );
  const [graphBeautificationRequestState, setGraphBeautificationRequestState] = useState<AsyncRequestState>(() => resolveRequestState(initialState.graphBeautificationRequestState));
  const [generatedCodeDrafts, setGeneratedCodeDrafts] = useState<GeneratedCodeDraft[]>(() => initialState.generatedCodeDrafts ?? []);
  const [generatedCodeDraftWarnings, setGeneratedCodeDraftWarnings] = useState<string[]>(() => initialState.generatedCodeDraftWarnings ?? []);
  const [generatedCodeDraftSource, setGeneratedCodeDraftSource] = useState(() => initialState.generatedCodeDraftSource ?? null);
  const [generatedCodeDraftPromptPreview, setGeneratedCodeDraftPromptPreview] = useState(
    () => initialState.generatedCodeDraftPromptPreview ?? null,
  );
  const [generatedCodeDraftPromptPreviewArtifactId, setGeneratedCodeDraftPromptPreviewArtifactId] = useState<string | null>(
    () => initialState.generatedCodeDraftPromptPreviewArtifactId ?? null,
  );
  const [generatedCodeDraftWriteReport, setGeneratedCodeDraftWriteReport] = useState<GeneratedCodeDraftWriteReport | null>(
    () => initialState.generatedCodeDraftWriteReport ?? null,
  );
  const [lastDraftPatchApplyResult, setLastDraftPatchApplyResult] = useState<DraftPatchApplyResult | null>(
    () => initialState.lastDraftPatchApplyResult ?? null,
  );
  const [codeDraftRequestState, setCodeDraftRequestState] = useState<AsyncRequestState>(() => resolveRequestState(initialState.codeDraftRequestState));
  const [requestFailureNotice, setRequestFailureNotice] = useState<RequestFailureNotice | null>(null);
  const [sourceNavigationState, setSourceNavigationState] = useState<SourceNavigationState>(
    () => resolveSourceNavigationState(initialState),
  );
  const [operationFeedback, setOperationFeedback] = useState<OperationFeedback | null>(
    () => initialState.operationFeedback ?? null,
  );
  const [lastMessageType, setLastMessageType] = useState<string | null>(() => initialState.lastMessageType ?? null);
  const [graphSurfaceExperiments, setGraphSurfaceExperiments] = useState<GraphSurfaceExperimentFlags | null>(
    () => initialState.graphSurfaceExperiments ?? null,
  );
  const [artifactContents, setArtifactContents] = useState<Record<string, string>>(() => initialState.artifactContents ?? {});
  const [isImportDialogOpen, setImportDialogOpen] = useState(false);
  const [mermaidDraft, setMermaidDraft] = useState("");
  const [diffTargetItemIds, setDiffTargetItemIds] = useState<string[]>([]);

  return {
    nodes,
    setNodes,
    edges,
    setEdges,
    selectedNodeId,
    setSelectedNodeId,
    analysisDisplayMode,
    setAnalysisDisplayMode,
    anchorNodeId,
    setAnchorNodeId,
    detailNodeId,
    setDetailNodeId,
    auditRequestState,
    setAuditRequestState,
    auditTargetNodeIds,
    setAuditTargetNodeIds,
    auditQuestionDraft,
    setAuditQuestionDraft,
    selectionGroupNodeIds,
    setSelectionGroupNodeIds,
    collapsedNodeIds,
    setCollapsedNodeIds,
    factGraph,
    setFactGraph,
    factGraphView,
    setFactGraphView,
    flowchartView,
    setFlowchartView,
    resourceRelationView,
    setResourceRelationView,
    draftGraph,
    setDraftGraph,
    draftWorkbenchState,
    setDraftWorkbenchState,
    designBaseline,
    setDesignBaseline,
    draftPatchPreview,
    setDraftPatchPreview,
    lastAppliedDraftPatchPreview,
    setLastAppliedDraftPatchPreview,
    canUndoDraftPatchApply,
    setCanUndoDraftPatchApply,
    lastAppliedDraftPatchSummary,
    setLastAppliedDraftPatchSummary,
    auditResult,
    setAuditResult,
    diffReviewResult,
    setDiffReviewResult,
    mermaidIssues,
    setMermaidIssues,
    diffItems,
    setDiffItems,
    syncPreviewItems,
    setSyncPreviewItems,
    generationPlan,
    setGenerationPlan,
    generationPlanRequestState,
    setGenerationPlanRequestState,
    diffReviewRequestState,
    setDiffReviewRequestState,
    graphBeautificationResult,
    setGraphBeautificationResult,
    graphBeautificationRequestState,
    setGraphBeautificationRequestState,
    generatedCodeDrafts,
    setGeneratedCodeDrafts,
    generatedCodeDraftWarnings,
    setGeneratedCodeDraftWarnings,
    generatedCodeDraftSource,
    setGeneratedCodeDraftSource,
    generatedCodeDraftPromptPreview,
    setGeneratedCodeDraftPromptPreview,
    generatedCodeDraftPromptPreviewArtifactId,
    setGeneratedCodeDraftPromptPreviewArtifactId,
    generatedCodeDraftWriteReport,
    setGeneratedCodeDraftWriteReport,
    lastDraftPatchApplyResult,
    setLastDraftPatchApplyResult,
    codeDraftRequestState,
    setCodeDraftRequestState,
    requestFailureNotice,
    setRequestFailureNotice,
    sourceNavigationState,
    setSourceNavigationState,
    operationFeedback,
    setOperationFeedback,
    lastMessageType,
    setLastMessageType,
    graphSurfaceExperiments,
    setGraphSurfaceExperiments,
    artifactContents,
    setArtifactContents,
    isImportDialogOpen,
    setImportDialogOpen,
    mermaidDraft,
    setMermaidDraft,
    diffTargetItemIds,
    setDiffTargetItemIds,
  };
}

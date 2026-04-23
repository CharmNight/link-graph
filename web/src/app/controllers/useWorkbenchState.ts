import { useState } from "react";
import type { Dispatch, SetStateAction } from "react";
import type {
  AnalysisDisplayMode,
  AsyncRequestState,
  DiffItem,
  DraftPatchApplyResult,
  DraftValidationState,
  DraftWorkbenchState,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GeneratedCodeDraft,
  GeneratedCodeDraftWriteReport,
  GenerationPlan,
  GenerationPlanDiscussionSession,
  GraphBeautificationResult,
  GraphPatch,
  GraphPatchResult,
  GraphSurfaceExperimentFlags,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphNode,
  LlmResultSource,
  MermaidIssue,
  OperationFeedback,
  QaRequestRecoveryState,
  ResourceRelationViewDocument,
  SourceNavigationState,
  StageEligibilityDecision,
  SyncPreviewItem,
  WorkbenchSectionPreferences,
} from "../types";
import type { RequestFailureNotice } from "./bridgeCommandTypes";

const DEFAULT_ANALYSIS_DISPLAY_MODE: AnalysisDisplayMode = "FLOWCHART";

export interface WorkbenchCanvasState {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  selectedNodeId: string | null;
  analysisDisplayMode: AnalysisDisplayMode;
  anchorNodeId: string | null;
  referenceWorkingGraph: LinkGraphDocument | null;
  factGraph: LinkGraphDocument | null;
  factGraphView: FactGraphViewDocument;
  flowchartView: FlowchartViewDocument;
  resourceRelationView: ResourceRelationViewDocument;
  draftGraph: LinkGraphDocument | null;
}

export interface WorkbenchProjectionState {
  detailNodeId: string | null;
  draftWorkbenchState: DraftWorkbenchState;
  designBaseline: LinkGraphDocument | null;
  draftPatchPreview: GraphPatch | null;
  lastAppliedDraftPatchPreview: GraphPatch | null;
  canUndoDraftPatchApply: boolean;
  lastAppliedDraftPatchSummary: string | null;
  auditResult: GraphPatchResult | null;
  auditRequestState: AsyncRequestState;
  qaRequestRecoveryState: QaRequestRecoveryState;
  diffReviewResult: GraphPatchResult | null;
  diffReviewRequestState: AsyncRequestState;
  mermaidIssues: MermaidIssue[];
  diffItems: DiffItem[];
  syncPreviewItems: SyncPreviewItem[];
  draftVersion: number | null;
  generationPlan: GenerationPlan | null;
  generationPlanDraftVersion: number | null;
  generationPlanRequestState: AsyncRequestState;
  draftValidationState: DraftValidationState | null;
  generationPlanDiscussionSession: GenerationPlanDiscussionSession | null;
  generationPlanDiscussionRequestState: AsyncRequestState;
  graphBeautificationResult: GraphBeautificationResult | null;
  graphBeautificationRequestState: AsyncRequestState;
  generatedCodeDrafts: GeneratedCodeDraft[];
  generatedCodeDraftVersion: number | null;
  generatedCodeDraftWarnings: string[];
  generatedCodeDraftSource: LlmResultSource | null;
  generatedCodeDraftPromptPreview: string | null;
  generatedCodeDraftPromptPreviewArtifactId: string | null;
  generatedCodeDraftWriteReport: GeneratedCodeDraftWriteReport | null;
  lastDraftPatchApplyResult: DraftPatchApplyResult | null;
  codeDraftRequestState: AsyncRequestState;
  codeEligibilityDecision: StageEligibilityDecision | null;
  sourceNavigationState: SourceNavigationState;
  operationFeedback: OperationFeedback | null;
  workbenchSectionPreferences: WorkbenchSectionPreferences;
  lastMessageType: string | null;
  graphSurfaceExperiments: GraphSurfaceExperimentFlags | null;
  artifactContents: Record<string, string>;
}

interface UseWorkbenchStateArgs {
  initialState: LinkGraphBootstrapState;
  initialGraph: LinkGraphDocument;
  initialAnchorNodeId: string | null;
  resolveRequestState: (state?: AsyncRequestState | null) => AsyncRequestState;
  resolveReferenceWorkingGraph: (state: LinkGraphBootstrapState, displayMode?: AnalysisDisplayMode) => LinkGraphDocument | null;
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

function updateStateField<S, K extends keyof S>(
  setState: Dispatch<SetStateAction<S>>,
  key: K,
): Dispatch<SetStateAction<S[K]>> {
  return (value) => {
    setState((current) => ({
      ...current,
      [key]: typeof value === "function"
        ? (value as (currentValue: S[K]) => S[K])(current[key])
        : value,
    }));
  };
}

function buildInitialCanvasState({
  initialState,
  initialGraph,
  initialAnchorNodeId,
  resolveReferenceWorkingGraph,
  resolveReferenceFactGraph,
  resolveFactGraphView,
  resolveFlowchartView,
  resolveResourceRelationView,
  resolveWorkingGraph,
  normalizeGraphNodes,
}: Pick<
  UseWorkbenchStateArgs,
  | "initialState"
  | "initialGraph"
  | "initialAnchorNodeId"
  | "resolveReferenceWorkingGraph"
  | "resolveReferenceFactGraph"
  | "resolveFactGraphView"
  | "resolveFlowchartView"
  | "resolveResourceRelationView"
  | "resolveWorkingGraph"
  | "normalizeGraphNodes"
>): WorkbenchCanvasState {
  const analysisDisplayMode = initialState.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE;
  return {
    nodes: normalizeGraphNodes(
      initialGraph.nodes,
      initialGraph.edges,
      initialAnchorNodeId,
      analysisDisplayMode,
    ),
    edges: initialGraph.edges,
    selectedNodeId: initialState.selectedNodeId ?? initialGraph.nodes[0]?.id ?? null,
    analysisDisplayMode,
    anchorNodeId: initialAnchorNodeId,
    referenceWorkingGraph: resolveReferenceWorkingGraph(initialState, analysisDisplayMode),
    factGraph: resolveReferenceFactGraph(initialState),
    factGraphView: resolveFactGraphView(initialState),
    flowchartView: resolveFlowchartView(initialState),
    resourceRelationView: resolveResourceRelationView(initialState),
    draftGraph: resolveWorkingGraph(initialState),
  };
}

function buildInitialProjectionState(
  initialState: LinkGraphBootstrapState,
  resolveRequestState: (state?: AsyncRequestState | null) => AsyncRequestState,
  resolveDesignBaselineGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument | null,
  resolveSourceNavigationState: (state: LinkGraphBootstrapState) => SourceNavigationState,
): WorkbenchProjectionState {
  return {
    detailNodeId: null,
    draftWorkbenchState: initialState.draftWorkbenchState ?? { draftChanges: [], draftNotes: [] },
    designBaseline: resolveDesignBaselineGraph(initialState),
    draftPatchPreview: initialState.draftPatchPreview ?? null,
    lastAppliedDraftPatchPreview: null,
    canUndoDraftPatchApply: initialState.canUndoDraftPatchApply ?? false,
    lastAppliedDraftPatchSummary: initialState.lastAppliedDraftPatchSummary ?? null,
    auditResult: initialState.auditResult ?? null,
    auditRequestState: resolveRequestState(initialState.auditRequestState),
    qaRequestRecoveryState: initialState.qaRequestRecoveryState ?? { lastSubmittedRequest: null, lastFailedRequest: null },
    diffReviewResult: initialState.diffReviewResult ?? null,
    diffReviewRequestState: resolveRequestState(initialState.diffReviewRequestState),
    mermaidIssues: initialState.mermaidIssues ?? [],
    diffItems: initialState.diffItems,
    syncPreviewItems: initialState.syncPreviewItems,
    draftVersion: initialState.draftVersion ?? null,
    generationPlan: initialState.generationPlan ?? null,
    generationPlanDraftVersion: initialState.generationPlanDraftVersion ?? null,
    generationPlanRequestState: resolveRequestState(initialState.generationPlanRequestState),
    draftValidationState: initialState.draftValidationState ?? null,
    generationPlanDiscussionSession: initialState.generationPlanDiscussionSession ?? null,
    generationPlanDiscussionRequestState: resolveRequestState(initialState.generationPlanDiscussionRequestState),
    graphBeautificationResult: initialState.graphBeautificationResult ?? null,
    graphBeautificationRequestState: resolveRequestState(initialState.graphBeautificationRequestState),
    generatedCodeDrafts: initialState.generatedCodeDrafts ?? [],
    generatedCodeDraftVersion: initialState.generatedCodeDraftVersion ?? null,
    generatedCodeDraftWarnings: initialState.generatedCodeDraftWarnings ?? [],
    generatedCodeDraftSource: initialState.generatedCodeDraftSource ?? null,
    generatedCodeDraftPromptPreview: initialState.generatedCodeDraftPromptPreview ?? null,
    generatedCodeDraftPromptPreviewArtifactId: initialState.generatedCodeDraftPromptPreviewArtifactId ?? null,
    generatedCodeDraftWriteReport: initialState.generatedCodeDraftWriteReport ?? null,
    lastDraftPatchApplyResult: initialState.lastDraftPatchApplyResult ?? null,
    codeDraftRequestState: resolveRequestState(initialState.codeDraftRequestState),
    codeEligibilityDecision: initialState.codeEligibilityDecision ?? null,
    sourceNavigationState: resolveSourceNavigationState(initialState),
    operationFeedback: initialState.operationFeedback ?? null,
    workbenchSectionPreferences: initialState.workbenchSectionPreferences ?? {},
    lastMessageType: initialState.lastMessageType ?? null,
    graphSurfaceExperiments: initialState.graphSurfaceExperiments ?? null,
    artifactContents: initialState.artifactContents ?? {},
  };
}

export function useWorkbenchState({
  initialState,
  initialGraph,
  initialAnchorNodeId,
  resolveRequestState,
  resolveReferenceWorkingGraph,
  resolveReferenceFactGraph,
  resolveFactGraphView,
  resolveFlowchartView,
  resolveResourceRelationView,
  resolveWorkingGraph,
  resolveDesignBaselineGraph,
  resolveSourceNavigationState,
  normalizeGraphNodes,
}: UseWorkbenchStateArgs) {
  const [canvasState, setCanvasState] = useState<WorkbenchCanvasState>(() =>
    buildInitialCanvasState({
      initialState,
      initialGraph,
      initialAnchorNodeId,
      resolveReferenceWorkingGraph,
      resolveReferenceFactGraph,
      resolveFactGraphView,
      resolveFlowchartView,
      resolveResourceRelationView,
      resolveWorkingGraph,
      normalizeGraphNodes,
    }),
  );
  const [projectionState, setProjectionState] = useState<WorkbenchProjectionState>(() =>
    buildInitialProjectionState(
      initialState,
      resolveRequestState,
      resolveDesignBaselineGraph,
      resolveSourceNavigationState,
    ),
  );
  const [auditTargetNodeIds, setAuditTargetNodeIds] = useState<string[]>([]);
  const [auditQuestionDraft, setAuditQuestionDraft] = useState<string>(() => initialState.auditResult?.question ?? "");
  const [selectionGroupNodeIds, setSelectionGroupNodeIds] = useState<string[]>([]);
  const [collapsedNodeIds, setCollapsedNodeIds] = useState<string[]>([]);
  const [requestFailureNotice, setRequestFailureNotice] = useState<RequestFailureNotice | null>(null);
  const [isImportDialogOpen, setImportDialogOpen] = useState(false);
  const [mermaidDraft, setMermaidDraft] = useState("");
  const [diffTargetItemIds, setDiffTargetItemIds] = useState<string[]>([]);

  const canvasSetters = {
    setNodes: updateStateField(setCanvasState, "nodes"),
    setEdges: updateStateField(setCanvasState, "edges"),
    setSelectedNodeId: updateStateField(setCanvasState, "selectedNodeId"),
    setAnalysisDisplayMode: updateStateField(setCanvasState, "analysisDisplayMode"),
    setAnchorNodeId: updateStateField(setCanvasState, "anchorNodeId"),
    setReferenceWorkingGraph: updateStateField(setCanvasState, "referenceWorkingGraph"),
    setFactGraph: updateStateField(setCanvasState, "factGraph"),
    setFactGraphView: updateStateField(setCanvasState, "factGraphView"),
    setFlowchartView: updateStateField(setCanvasState, "flowchartView"),
    setResourceRelationView: updateStateField(setCanvasState, "resourceRelationView"),
    setDraftGraph: updateStateField(setCanvasState, "draftGraph"),
  };

  const projectionSetters = {
    setDetailNodeId: updateStateField(setProjectionState, "detailNodeId"),
    setDraftWorkbenchState: updateStateField(setProjectionState, "draftWorkbenchState"),
    setDesignBaseline: updateStateField(setProjectionState, "designBaseline"),
    setDraftPatchPreview: updateStateField(setProjectionState, "draftPatchPreview"),
    setLastAppliedDraftPatchPreview: updateStateField(setProjectionState, "lastAppliedDraftPatchPreview"),
    setCanUndoDraftPatchApply: updateStateField(setProjectionState, "canUndoDraftPatchApply"),
    setLastAppliedDraftPatchSummary: updateStateField(setProjectionState, "lastAppliedDraftPatchSummary"),
    setAuditResult: updateStateField(setProjectionState, "auditResult"),
    setAuditRequestState: updateStateField(setProjectionState, "auditRequestState"),
    setQaRequestRecoveryState: updateStateField(setProjectionState, "qaRequestRecoveryState"),
    setDiffReviewResult: updateStateField(setProjectionState, "diffReviewResult"),
    setDiffReviewRequestState: updateStateField(setProjectionState, "diffReviewRequestState"),
    setMermaidIssues: updateStateField(setProjectionState, "mermaidIssues"),
    setDiffItems: updateStateField(setProjectionState, "diffItems"),
    setSyncPreviewItems: updateStateField(setProjectionState, "syncPreviewItems"),
    setDraftVersion: updateStateField(setProjectionState, "draftVersion"),
    setGenerationPlan: updateStateField(setProjectionState, "generationPlan"),
    setGenerationPlanDraftVersion: updateStateField(setProjectionState, "generationPlanDraftVersion"),
    setGenerationPlanRequestState: updateStateField(setProjectionState, "generationPlanRequestState"),
    setDraftValidationState: updateStateField(setProjectionState, "draftValidationState"),
    setGenerationPlanDiscussionSession: updateStateField(setProjectionState, "generationPlanDiscussionSession"),
    setGenerationPlanDiscussionRequestState: updateStateField(setProjectionState, "generationPlanDiscussionRequestState"),
    setGraphBeautificationResult: updateStateField(setProjectionState, "graphBeautificationResult"),
    setGraphBeautificationRequestState: updateStateField(setProjectionState, "graphBeautificationRequestState"),
    setGeneratedCodeDrafts: updateStateField(setProjectionState, "generatedCodeDrafts"),
    setGeneratedCodeDraftVersion: updateStateField(setProjectionState, "generatedCodeDraftVersion"),
    setGeneratedCodeDraftWarnings: updateStateField(setProjectionState, "generatedCodeDraftWarnings"),
    setGeneratedCodeDraftSource: updateStateField(setProjectionState, "generatedCodeDraftSource"),
    setGeneratedCodeDraftPromptPreview: updateStateField(setProjectionState, "generatedCodeDraftPromptPreview"),
    setGeneratedCodeDraftPromptPreviewArtifactId: updateStateField(setProjectionState, "generatedCodeDraftPromptPreviewArtifactId"),
    setGeneratedCodeDraftWriteReport: updateStateField(setProjectionState, "generatedCodeDraftWriteReport"),
    setLastDraftPatchApplyResult: updateStateField(setProjectionState, "lastDraftPatchApplyResult"),
    setCodeDraftRequestState: updateStateField(setProjectionState, "codeDraftRequestState"),
    setCodeEligibilityDecision: updateStateField(setProjectionState, "codeEligibilityDecision"),
    setSourceNavigationState: updateStateField(setProjectionState, "sourceNavigationState"),
    setOperationFeedback: updateStateField(setProjectionState, "operationFeedback"),
    setWorkbenchSectionPreferences: updateStateField(setProjectionState, "workbenchSectionPreferences"),
    setLastMessageType: updateStateField(setProjectionState, "lastMessageType"),
    setGraphSurfaceExperiments: updateStateField(setProjectionState, "graphSurfaceExperiments"),
    setArtifactContents: updateStateField(setProjectionState, "artifactContents"),
  };

  return {
    canvasState,
    setCanvasState,
    canvasSetters,
    projectionState,
    setProjectionState,
    projectionSetters,
    auditTargetNodeIds,
    setAuditTargetNodeIds,
    auditQuestionDraft,
    setAuditQuestionDraft,
    selectionGroupNodeIds,
    setSelectionGroupNodeIds,
    collapsedNodeIds,
    setCollapsedNodeIds,
    requestFailureNotice,
    setRequestFailureNotice,
    isImportDialogOpen,
    setImportDialogOpen,
    mermaidDraft,
    setMermaidDraft,
    diffTargetItemIds,
    setDiffTargetItemIds,
  };
}

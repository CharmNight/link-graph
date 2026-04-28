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
  LinkGraphLayoutState,
  LinkGraphNode,
  LinkGraphSceneId,
  LinkGraphSceneState,
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
  currentSceneId: LinkGraphSceneId;
  sceneStates: Record<LinkGraphSceneId, LinkGraphSceneState>;
  workspaceGraph: LinkGraphDocument;
  workspaceBaseGraph: LinkGraphDocument | null;
  semanticFactGraph: LinkGraphDocument | null;
  workspaceRevision: number | null;
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
  resolveWorkspaceBaseGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument | null;
  resolveSemanticFactGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument | null;
  resolveFactGraphView: (state: LinkGraphBootstrapState) => FactGraphViewDocument;
  resolveFlowchartView: (state: LinkGraphBootstrapState) => FlowchartViewDocument;
  resolveResourceRelationView: (state: LinkGraphBootstrapState) => ResourceRelationViewDocument;
  resolveCurrentSceneState: (state: LinkGraphBootstrapState) => LinkGraphSceneState;
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
    setState((current) => {
      const nextValue = typeof value === "function"
        ? (value as (currentValue: S[K]) => S[K])(current[key])
        : value;
      if (Object.is(current[key], nextValue)) {
        return current;
      }
      return {
        ...current,
        [key]: nextValue,
      };
    });
  };
}

function resolveStateAction<T>(value: SetStateAction<T>, currentValue: T): T {
  return typeof value === "function"
    ? (value as (currentValue: T) => T)(currentValue)
    : value;
}

function createEmptySceneState(): LinkGraphSceneState {
  return {
    selectedNodeId: null,
    anchorNodeId: null,
    layoutState: {
      positions: {},
    },
    layoutRevision: 0,
    collapsedNodeIds: [],
  };
}

function sameNodeIdList(left: string[] | undefined, right: string[] | undefined): boolean {
  const normalizedLeft = left ?? [];
  const normalizedRight = right ?? [];
  if (normalizedLeft.length !== normalizedRight.length) {
    return false;
  }
  return normalizedLeft.every((nodeId, index) => nodeId === normalizedRight[index]);
}

function sameLayoutState(
  left: LinkGraphLayoutState | undefined,
  right: LinkGraphLayoutState | undefined,
): boolean {
  const leftPositions = left?.positions ?? {};
  const rightPositions = right?.positions ?? {};
  const leftKeys = Object.keys(leftPositions);
  const rightKeys = Object.keys(rightPositions);
  if (leftKeys.length !== rightKeys.length) {
    return false;
  }
  return leftKeys.every((nodeId) => {
    const leftPosition = leftPositions[nodeId];
    const rightPosition = rightPositions[nodeId];
    return rightPosition != null
      && leftPosition.x === rightPosition.x
      && leftPosition.y === rightPosition.y;
  });
}

function sameSceneFieldValue<K extends keyof LinkGraphSceneState>(
  key: K,
  left: LinkGraphSceneState[K],
  right: LinkGraphSceneState[K],
): boolean {
  if (key === "collapsedNodeIds") {
    return sameNodeIdList(left as string[] | undefined, right as string[] | undefined);
  }
  if (key === "layoutState") {
    return sameLayoutState(
      left as LinkGraphLayoutState | undefined,
      right as LinkGraphLayoutState | undefined,
    );
  }
  return Object.is(left, right);
}

function updateCurrentSceneStateField<K extends keyof LinkGraphSceneState>(
  setState: Dispatch<SetStateAction<WorkbenchCanvasState>>,
  key: K,
  mirrorField?: keyof Pick<WorkbenchCanvasState, "selectedNodeId" | "anchorNodeId">,
): Dispatch<SetStateAction<LinkGraphSceneState[K]>> {
  return (value) => {
    setState((current) => {
      const currentSceneId = current.currentSceneId;
      const currentSceneState = current.sceneStates[currentSceneId] ?? createEmptySceneState();
      const nextValue = resolveStateAction(value, currentSceneState[key]);
      const mirrorValue = mirrorField ? current[mirrorField] : undefined;
      if (
        sameSceneFieldValue(key, currentSceneState[key], nextValue)
        && (!mirrorField || Object.is(mirrorValue, nextValue))
      ) {
        return current;
      }
      const nextSceneState = {
        ...currentSceneState,
        [key]: nextValue,
      };

      return {
        ...current,
        ...(mirrorField
          ? {
              [mirrorField]: nextValue,
            }
          : {}),
        sceneStates: {
          ...current.sceneStates,
          [currentSceneId]: nextSceneState,
        },
      };
    });
  };
}

function updateCurrentSceneNodeField(
  setState: Dispatch<SetStateAction<WorkbenchCanvasState>>,
  key: "selectedNodeId" | "anchorNodeId",
  mirrorField: "selectedNodeId" | "anchorNodeId",
): Dispatch<SetStateAction<string | null>> {
  return (value) => {
    setState((current) => {
      const currentSceneId = current.currentSceneId;
      const currentSceneState = current.sceneStates[currentSceneId] ?? createEmptySceneState();
      const nextValue = resolveStateAction(value, currentSceneState[key] ?? null);
      if (
        Object.is(currentSceneState[key] ?? null, nextValue)
        && Object.is(current[mirrorField] ?? null, nextValue)
      ) {
        return current;
      }
      const nextSceneState = {
        ...currentSceneState,
        [key]: nextValue,
      };

      return {
        ...current,
        [mirrorField]: nextValue,
        sceneStates: {
          ...current.sceneStates,
          [currentSceneId]: nextSceneState,
        },
      };
    });
  };
}

function buildInitialCanvasState({
  initialState,
  initialGraph,
  initialAnchorNodeId,
  resolveWorkspaceBaseGraph,
  resolveSemanticFactGraph,
  resolveFactGraphView,
  resolveFlowchartView,
  resolveResourceRelationView,
  resolveCurrentSceneState,
  resolveWorkingGraph,
  normalizeGraphNodes,
}: Pick<
  UseWorkbenchStateArgs,
  | "initialState"
  | "initialGraph"
  | "initialAnchorNodeId"
  | "resolveWorkspaceBaseGraph"
  | "resolveSemanticFactGraph"
  | "resolveFactGraphView"
  | "resolveFlowchartView"
  | "resolveResourceRelationView"
  | "resolveCurrentSceneState"
  | "resolveWorkingGraph"
  | "normalizeGraphNodes"
>): WorkbenchCanvasState {
  const analysisDisplayMode = initialState.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE;
  const sceneState = resolveCurrentSceneState(initialState);
  return {
    nodes: normalizeGraphNodes(
      initialGraph.nodes,
      initialGraph.edges,
      initialAnchorNodeId,
      analysisDisplayMode,
    ),
    edges: initialGraph.edges,
    selectedNodeId: sceneState.selectedNodeId ?? initialGraph.nodes[0]?.id ?? null,
    analysisDisplayMode,
    anchorNodeId: initialAnchorNodeId ?? sceneState.anchorNodeId ?? initialGraph.nodes[0]?.id ?? null,
    currentSceneId: initialState.currentSceneId,
    sceneStates: initialState.sceneStates,
    workspaceGraph: resolveWorkingGraph(initialState) ?? initialGraph,
    workspaceBaseGraph: resolveWorkspaceBaseGraph(initialState),
    semanticFactGraph: resolveSemanticFactGraph(initialState),
    workspaceRevision: initialState.workspaceRevision ?? null,
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
  resolveWorkspaceBaseGraph,
  resolveSemanticFactGraph,
  resolveFactGraphView,
  resolveFlowchartView,
  resolveResourceRelationView,
  resolveCurrentSceneState,
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
      resolveWorkspaceBaseGraph,
      resolveSemanticFactGraph,
      resolveFactGraphView,
      resolveFlowchartView,
      resolveResourceRelationView,
      resolveCurrentSceneState,
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
  const [requestFailureNotice, setRequestFailureNotice] = useState<RequestFailureNotice | null>(null);
  const [isImportDialogOpen, setImportDialogOpen] = useState(false);
  const [mermaidDraft, setMermaidDraft] = useState("");
  const [diffTargetItemIds, setDiffTargetItemIds] = useState<string[]>([]);
  const collapsedNodeIds = canvasState.sceneStates[canvasState.currentSceneId]?.collapsedNodeIds ?? [];
  const setCollapsedNodeIds = updateCurrentSceneStateField(setCanvasState, "collapsedNodeIds");

  const canvasSetters = {
    setNodes: updateStateField(setCanvasState, "nodes"),
    setEdges: updateStateField(setCanvasState, "edges"),
    setSelectedNodeId: updateCurrentSceneNodeField(setCanvasState, "selectedNodeId", "selectedNodeId"),
    setAnalysisDisplayMode: updateStateField(setCanvasState, "analysisDisplayMode"),
    setAnchorNodeId: updateCurrentSceneNodeField(setCanvasState, "anchorNodeId", "anchorNodeId"),
    setCurrentSceneId: updateStateField(setCanvasState, "currentSceneId"),
    setSceneStates: updateStateField(setCanvasState, "sceneStates"),
    setWorkspaceGraph: updateStateField(setCanvasState, "workspaceGraph"),
    setWorkspaceBaseGraph: updateStateField(setCanvasState, "workspaceBaseGraph"),
    setSemanticFactGraph: updateStateField(setCanvasState, "semanticFactGraph"),
    setWorkspaceRevision: updateStateField(setCanvasState, "workspaceRevision"),
    setFactGraphView: updateStateField(setCanvasState, "factGraphView"),
    setFlowchartView: updateStateField(setCanvasState, "flowchartView"),
    setResourceRelationView: updateStateField(setCanvasState, "resourceRelationView"),
    setDraftGraph: updateStateField(setCanvasState, "draftGraph"),
    setSceneLayoutState: updateCurrentSceneStateField(setCanvasState, "layoutState"),
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

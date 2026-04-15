import { startTransition, useEffect, useMemo, useRef, useState } from "react";
import {
  applyDraftPatchPreview,
  clearDraftPatchPreview,
  getSampleMermaidIssues,
  getSampleSyncPreview,
  importMermaid,
  publishGraphChange,
  publishLayoutChange,
  publishNodeSelected,
  readBootstrapState,
  restoreDraftPatchPreview,
  requestAuditAsync,
  confirmAuditCandidateChange,
  unconfirmAuditCandidateChange,
  requestArtifactContent,
  requestDiffReviewAsync,
  requestGraphBeautificationAsync,
  undoLastDraftPatchApply,
  updateWorkbenchSectionPreference,
  applySingleCodeDraft,
} from "./api";
import { canNavigateToSource } from "./sourceNavigation";
import { AsyncRequestFailureDialog } from "./components/AsyncRequestFailureDialog";
import { CodeDraftPanel } from "./components/CodeDraftPanel";
import { DiffPanel } from "./components/DiffPanel";
import { GenerationPlanPanel } from "./components/GenerationPlanPanel";
import { IssuePanel } from "./components/IssuePanel";
import { Legend } from "./components/Legend";
import { MermaidImportDialog } from "./components/MermaidImportDialog";
import { PropertyPanel } from "./components/PropertyPanel";
import { SyncPreviewPanel } from "./components/SyncPreviewPanel";
import { FactGraphView } from "./views/fact/FactGraphView";
import { FlowchartView } from "./views/flowchart/FlowchartView";
import { ResourceRelationView } from "./views/resource/ResourceRelationView";
import {
  measureDuration,
  measureStart,
  summarizeBootstrapState,
  summarizeGraph,
  traceLinkGraph,
} from "./debug";
import { FifoQueue } from "./fifoQueue";
import {
  applyBootstrapEdgeRoutes,
  applyBootstrapNodePositions,
  applyLayoutOnlyNodePositions,
  clearStoredNodePosition,
  collectDownstreamSubtreeNodeIds,
  extractLayoutPayload,
  fallbackDesignPosition,
  graphLayoutSignature,
  graphSemanticSignature,
  hasRevision,
  normalizeGraphNodes,
  resolveNodePosition,
  sameNodeIdList,
  syncNodePosition,
} from "./graphState";
import { canEditNodeLayout } from "./layoutEditability";
import { nextManualNodeSequence } from "./manualNodeIds";
import type {
  AsyncRequestState,
  AnalysisDisplayMode,
  AuditWorkbenchState,
  CandidateDraftChange,
  DiffItem,
  DraftPatchPreviewSource,
  DraftWorkbenchEntry,
  DraftWorkbenchViewState,
  ExplanationWorkbenchState,
  GeneratedCodeDraft,
  GeneratedCodeDraftWriteReport,
  GraphBeautificationResult,
  GraphFocusRequest,
  LinkGraphLayoutState,
  GraphPosition,
  GraphPatch,
  GraphPatchResult,
  DraftPatchApplyResult,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GraphSurfaceExperimentFlags,
  LinkGraphDocument,
  LinkGraphBootstrapState,
  LinkGraphEdge,
  LinkGraphNode,
  MermaidIssue,
  OperationFeedback,
  ResultEvidenceReference,
  ResourceRelationViewDocument,
  SourceNavigationState,
  StepGranularity,
  WorkbenchSectionId,
  WorkbenchSectionPreferences,
} from "./types";
import { GraphWorkbench } from "./workbench/GraphWorkbench";
import { AuditTab } from "./workbench/AuditTab";
import { candidateCanConfirm } from "./workbench/candidateChangeSupport";
import { DraftTab } from "./workbench/DraftTab";
import { ExplanationTab } from "./workbench/ExplanationTab";
import { WorkbenchPropertyDrawer } from "./workbench/WorkbenchPropertyDrawer";
import { WorkbenchToolbar } from "./workbench/WorkbenchToolbar";
import { AUDIT_WORKBENCH_SECTION_IDS } from "./workbench/workbenchSections";
import type { RequestFailureNotice } from "./controllers/bridgeCommandTypes";
import { useBootstrapStateController } from "./controllers/useBootstrapStateController";
import { useBridgeCommandController } from "./controllers/useBridgeCommandController";
import { useSourceNavigationController } from "./controllers/useSourceNavigationController";
import { useWorkbenchCommandController } from "./controllers/useWorkbenchCommandController";
import { useWorkbenchState } from "./controllers/useWorkbenchState";
import { resolveToolbarFeedback } from "./asyncRequestStatus";

type WorkbenchTab = "explanation" | "audit" | "draft" | "plan" | "code";
type ExplanationRequestMode = "fresh" | "follow_up";
const DEFAULT_ANALYSIS_DISPLAY_MODE: AnalysisDisplayMode = "FLOWCHART";

interface ExplanationHistoryEntry {
  result: GraphBeautificationResult;
  requestState: AsyncRequestState;
  selectedStepId: string | null;
  granularity: StepGranularity;
  sessionLabel: string;
}

const DEFAULT_EXPLANATION_SESSION_LABEL = "当前链路讲解";

const IDLE_REQUEST_STATE: AsyncRequestState = {
  phase: "IDLE",
  requestId: null,
  scene: null,
  executionMode: null,
  statusMessage: null,
  errorMessage: null,
  detailMessage: null,
  startedAtEpochMillis: null,
  finishedAtEpochMillis: null,
  streaming: false,
  fallbackUsed: false,
  providerLabel: null,
  model: null,
  endpointSummary: null,
  promptPreviewAvailable: false,
};

const IDLE_SOURCE_NAVIGATION_STATE: SourceNavigationState = {
  phase: "IDLE",
  nodeId: null,
  result: null,
  targetPath: null,
  line: null,
  column: null,
  errorMessage: null,
};

const WORKBENCH_TABS: Array<{ id: WorkbenchTab; label: string }> = [
  { id: "explanation", label: "讲解" },
  { id: "audit", label: "审计" },
  { id: "draft", label: "草稿" },
  { id: "plan", label: "计划" },
  { id: "code", label: "代码" },
];

const REQUEST_ONLY_SELECTION_MESSAGE_TYPES = new Set([
  "requestAudit",
  "auditResult",
]);

function toDraftWorkbenchEntry(change: CandidateDraftChange): DraftWorkbenchEntry {
  return {
    entryId: `draft-${change.changeId}`,
    kind: "CHANGE",
    title: change.title,
    sourceChangeId: change.changeId,
    targetStepIds: change.targetStepIds,
    targetNodeIds: change.targetNodeIds,
    beforeState: change.beforeState ?? null,
    afterState: change.afterState ?? null,
    reason: change.reason,
    impactSummary: change.impactSummary,
    claimType: change.claimType ?? null,
    evidence: change.evidence ?? [],
  };
}

function resolveEvidenceTargetNodeId(
  targetNodeIds: string[],
  evidence?: Array<{ references: Array<{ nodeId?: string | null }> }>,
): string | null {
  return targetNodeIds[0]
    ?? evidence?.flatMap((finding) => finding.references).find((reference) => reference.nodeId)?.nodeId
    ?? null;
}

function updateGraphPatchResultCandidateStatus(
  result: GraphPatchResult | null,
  changeId: string,
  status: CandidateDraftChange["status"],
): GraphPatchResult | null {
  if (!result) {
    return result;
  }
  return {
    ...result,
    candidateChanges: result.candidateChanges.map((candidate) =>
      candidate.changeId === changeId ? { ...candidate, status } : candidate),
    newCandidateChanges: result.newCandidateChanges.map((candidate) =>
      candidate.changeId === changeId ? { ...candidate, status } : candidate),
    auditSession: result.auditSession
      ? {
          ...result.auditSession,
          candidateChanges: result.auditSession.candidateChanges.map((candidate) =>
            candidate.changeId === changeId ? { ...candidate, status } : candidate),
        }
      : null,
  };
}

const INITIAL_NODES: LinkGraphNode[] = [
  {
    id: "method:place-order",
    type: "METHOD",
    title: "OrderService.place",
    location: "src/main/java/com/example/OrderService.java:12:1",
    signature: "com.example.OrderService.place(java.lang.String):void",
    inputs: ["java.lang.String"],
    outputs: ["void"],
    doc: "创建订单并触发持久化处理。",
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    position: { x: 120, y: 96 },
    metadata: {
      "ui.x": "120",
      "ui.y": "96",
    },
  },
  {
    id: "sql:insert-order",
    type: "SQL",
    title: "insert into orders",
    inputs: [],
    outputs: [],
    certainty: "RULE_INFERRED",
    bindingStatus: "PARTIALLY_SYNCED",
    diffStatus: "MODIFIED",
    position: { x: 380, y: 196 },
    metadata: {
      "ui.x": "380",
      "ui.y": "196",
    },
  },
];

const INITIAL_EDGES: LinkGraphEdge[] = [
  {
    id: "call:place-order->insert-order",
    type: "CALL",
    source: "method:place-order",
    target: "sql:insert-order",
  },
];

const DIFF_ITEMS: DiffItem[] = [
  {
    id: "method:place-order",
    title: "OrderService.place",
    status: "MODIFIED",
    description: "Mermaid 设计期望存在草稿 DTO 分支，但当前代码尚未接入。",
  },
  {
    id: "sql:insert-order",
    title: "insert into orders",
    status: "ONLY_IN_CODE",
    description: "SQL 节点存在于代码中，但导入的设计图里没有。",
  },
];

function deriveFactGraphSummary(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
  anchorNodeId?: string | null,
) {
  return {
    anchorTitle: fullGraph.nodes.find((node) => node.id === anchorNodeId)?.title
      ?? visibleGraph.nodes.find((node) => node.id === anchorNodeId)?.title
      ?? null,
    visibleNodeCount: visibleGraph.nodes.length,
    fullNodeCount: fullGraph.nodes.length,
  };
}

function deriveFlowchartSummary(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument = visibleGraph,
) {
  const hiddenNodeCount = (fullGraph.nodes?.length ?? 0) - visibleGraph.nodes.length;
  const hiddenEdgeCount = (fullGraph.edges?.length ?? 0) - visibleGraph.edges.length;
  const incompleteNodeCount = visibleGraph.nodes.filter((node) => node.metadata?.["flow.incomplete"] === "true").length;
  const incompleteEdgeCount = visibleGraph.edges.filter((edge) => edge.metadata?.["flow.incomplete"] === "true").length;
  const syntheticEdgeCount = visibleGraph.edges.filter((edge) => edge.metadata?.["flow.synthetic"] === "true").length;
  const syntheticEntryEdgeCount = visibleGraph.edges.filter(
    (edge) => edge.metadata?.["flow.synthetic"] === "true" && edge.metadata?.["flow.provenance"] === "SYNTHETIC_PROJECTION",
  ).length;
  return {
    nodeCount: visibleGraph.nodes.length,
    branchCount: visibleGraph.nodes.filter((node) => node.metadata?.["flowchart.kind"] === "DECISION").length,
    exceptionPathCount: visibleGraph.edges.filter((edge) => edge.label?.trim().toUpperCase() === "EXCEPTION").length,
    fullNodeCount: fullGraph.nodes.length,
    fullEdgeCount: fullGraph.edges.length,
    hiddenNodeCount: Math.max(0, hiddenNodeCount),
    hiddenEdgeCount: Math.max(0, hiddenEdgeCount),
    truncated: hiddenNodeCount > 0 || hiddenEdgeCount > 0,
    incompleteNodeCount,
    incompleteEdgeCount,
    semanticallyIncomplete: incompleteNodeCount > 0 || incompleteEdgeCount > 0,
    syntheticEdgeCount,
    syntheticEntryEdgeCount,
  };
}

function deriveResourceRelationSummary(visibleGraph: LinkGraphDocument) {
  return {
    visibleNodeCount: visibleGraph.nodes.length,
    laneCounts: visibleGraph.nodes.reduce<Record<string, number>>((counts, node) => {
      const lane = node.metadata?.["resource.lane"] ?? "CODE";
      counts[lane] = (counts[lane] ?? 0) + 1;
      return counts;
    }, {}),
  };
}

const SAMPLE_STATE: LinkGraphBootstrapState = {
  analysisDisplayMode: DEFAULT_ANALYSIS_DISPLAY_MODE,
  visibleGraph: {
    nodes: INITIAL_NODES,
    edges: INITIAL_EDGES,
  },
  workingGraph: {
    nodes: INITIAL_NODES,
    edges: INITIAL_EDGES,
  },
  referenceFactGraph: {
    nodes: INITIAL_NODES,
    edges: INITIAL_EDGES,
  },
  factGraphView: {
    visibleGraph: {
      nodes: INITIAL_NODES,
      edges: INITIAL_EDGES,
    },
    fullGraph: {
      nodes: INITIAL_NODES,
      edges: INITIAL_EDGES,
    },
    anchorNodeId: INITIAL_NODES[0]?.id ?? null,
    summary: deriveFactGraphSummary(
      { nodes: INITIAL_NODES, edges: INITIAL_EDGES },
      { nodes: INITIAL_NODES, edges: INITIAL_EDGES },
      INITIAL_NODES[0]?.id ?? null,
    ),
  },
  flowchartView: {
    visibleGraph: {
      nodes: INITIAL_NODES,
      edges: INITIAL_EDGES,
    },
    fullGraph: {
      nodes: INITIAL_NODES,
      edges: INITIAL_EDGES,
    },
    anchorNodeId: INITIAL_NODES[0]?.id ?? null,
    summary: deriveFlowchartSummary(
      { nodes: INITIAL_NODES, edges: INITIAL_EDGES },
      { nodes: INITIAL_NODES, edges: INITIAL_EDGES },
    ),
  },
  resourceRelationView: {
    visibleGraph: {
      nodes: INITIAL_NODES,
      edges: INITIAL_EDGES,
    },
    fullGraph: {
      nodes: INITIAL_NODES,
      edges: INITIAL_EDGES,
    },
    anchorNodeId: INITIAL_NODES[0]?.id ?? null,
    summary: deriveResourceRelationSummary({ nodes: INITIAL_NODES, edges: INITIAL_EDGES }),
  },
  designBaselineGraph: null,
  draftPatchPreview: null,
  canUndoDraftPatchApply: false,
  lastAppliedDraftPatchSummary: null,
  auditResult: null,
  auditRequestState: IDLE_REQUEST_STATE,
  diffReviewResult: null,
  diffReviewRequestState: IDLE_REQUEST_STATE,
  mermaidIssues: getSampleMermaidIssues(),
  diffItems: DIFF_ITEMS,
  syncPreviewItems: getSampleSyncPreview(),
  generationPlan: {
    source: "MOCK",
    summary: "创建 DTO 并补齐服务接线。",
    warnings: ["当前计划来自本地规则推断。"],
    promptPreview: "提示词预览",
    items: [
      {
        id: "sample-plan-1",
        title: "新增 OrderDraftDto",
        description: "根据 Mermaid 设计生成 OrderDraftDto.java。",
        risk: "LOW",
        targetPath: "src/main/java/com/example/OrderDraftDto.java",
      },
    ],
  },
  generatedCodeDrafts: [
    {
      id: "draft-1",
      sourceNodeId: "class:orderdraftdto",
      title: "OrderDraftDto.java",
      targetPath: "src/main/java/com/example/OrderDraftDto.java",
      content: "package com.example;\n\npublic class OrderDraftDto {\n}",
      warnings: [],
    },
  ],
  generatedCodeDraftWarnings: [],
  generationPlanRequestState: IDLE_REQUEST_STATE,
  graphBeautificationRequestState: IDLE_REQUEST_STATE,
  codeDraftRequestState: IDLE_REQUEST_STATE,
  generatedCodeDraftWriteReport: {
    writtenFiles: ["src/main/java/com/example/OrderDraftDto.java"],
    skippedFiles: [],
    warnings: [],
  },
  selectedNodeId: INITIAL_NODES[0]?.id ?? null,
  sourceNavigationState: IDLE_SOURCE_NAVIGATION_STATE,
};

const EMPTY_STATE: LinkGraphBootstrapState = {
  analysisDisplayMode: DEFAULT_ANALYSIS_DISPLAY_MODE,
  visibleGraph: {
    nodes: [],
    edges: [],
  },
  workingGraph: {
    nodes: [],
    edges: [],
  },
  referenceFactGraph: null,
  factGraphView: {
    visibleGraph: {
      nodes: [],
      edges: [],
    },
    fullGraph: {
      nodes: [],
      edges: [],
    },
    anchorNodeId: null,
    summary: deriveFactGraphSummary({ nodes: [], edges: [] }, { nodes: [], edges: [] }, null),
  },
  flowchartView: {
    visibleGraph: {
      nodes: [],
      edges: [],
    },
    fullGraph: {
      nodes: [],
      edges: [],
    },
    anchorNodeId: null,
    summary: deriveFlowchartSummary({ nodes: [], edges: [] }, { nodes: [], edges: [] }),
  },
  resourceRelationView: {
    visibleGraph: {
      nodes: [],
      edges: [],
    },
    fullGraph: {
      nodes: [],
      edges: [],
    },
    anchorNodeId: null,
    summary: deriveResourceRelationSummary({ nodes: [], edges: [] }),
  },
  designBaselineGraph: null,
  draftPatchPreview: null,
  canUndoDraftPatchApply: false,
  lastAppliedDraftPatchSummary: null,
  auditResult: null,
  auditRequestState: IDLE_REQUEST_STATE,
  diffReviewResult: null,
  diffReviewRequestState: IDLE_REQUEST_STATE,
  mermaidIssues: [],
  diffItems: [],
  syncPreviewItems: [],
  generationPlan: null,
  generationPlanRequestState: IDLE_REQUEST_STATE,
  generatedCodeDrafts: [],
  generatedCodeDraftWarnings: [],
  graphBeautificationRequestState: IDLE_REQUEST_STATE,
  codeDraftRequestState: IDLE_REQUEST_STATE,
  generatedCodeDraftWriteReport: null,
  selectedNodeId: null,
  sourceNavigationState: IDLE_SOURCE_NAVIGATION_STATE,
  operationFeedback: null,
};

function resolveRequestState(state?: AsyncRequestState | null): AsyncRequestState {
  return {
    ...IDLE_REQUEST_STATE,
    ...(state ?? {}),
  };
}

function EmptyDock({ title, description }: { title: string; description: string }) {
  return (
    <section className="dock-empty">
      <strong>{title}</strong>
      <p className="muted">{description}</p>
    </section>
  );
}

function shouldUseSampleState(): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  if (window.linkGraphBootstrap || window.linkGraphBridge) {
    return false;
  }
  // 样例数据只用于本地浏览器预览，不能污染 JCEF 首屏和测试环境。
  return Boolean(import.meta.env.DEV && import.meta.env.MODE !== "test" && /^https?:$/i.test(window.location.protocol));
}

function resolveInitialState(): LinkGraphBootstrapState {
  const bootstrapState = readBootstrapState();
  if (bootstrapState) {
    traceLinkGraph("app.resolveInitialState.bootstrap", summarizeBootstrapState(bootstrapState));
    return bootstrapState;
  }
  traceLinkGraph("app.resolveInitialState.fallback", {
    useSampleState: shouldUseSampleState(),
  });
  return shouldUseSampleState() ? SAMPLE_STATE : EMPTY_STATE;
}

function resolveVisibleGraph(state: LinkGraphBootstrapState): LinkGraphDocument {
  return resolveActiveViewDocument(state).visibleGraph;
}

function resolveWorkingGraph(state: LinkGraphBootstrapState): LinkGraphDocument {
  return state.workingGraph ?? state.visibleGraph ?? EMPTY_STATE.workingGraph;
}

function resolveReferenceFactGraph(state: LinkGraphBootstrapState): LinkGraphDocument | null {
  return resolveFactGraphView(state).fullGraph;
}

function resolveDesignBaselineGraph(state: LinkGraphBootstrapState): LinkGraphDocument | null {
  return state.designBaselineGraph ?? null;
}

function resolveSourceNavigationState(state: LinkGraphBootstrapState): SourceNavigationState {
  return state.sourceNavigationState ?? IDLE_SOURCE_NAVIGATION_STATE;
}

function resolveAnchorNodeId(
  nodes: LinkGraphNode[],
  preferredNodeId?: string | null,
): string | null {
  if (preferredNodeId && nodes.some((node) => node.id === preferredNodeId)) {
    return preferredNodeId;
  }
  return nodes.find((node) => node.type === "METHOD")?.id ?? nodes[0]?.id ?? null;
}

function shouldResetAnchorNode(
  state: LinkGraphBootstrapState,
  graphChanged: boolean,
): boolean {
  if (graphChanged) {
    return true;
  }
  return state.lastMessageType === "loadGraph" || state.lastMessageType === "graphChanged";
}

function resolveFactGraphView(state: LinkGraphBootstrapState): FactGraphViewDocument {
  return state.factGraphView ?? EMPTY_STATE.factGraphView;
}

function resolveFlowchartView(state: LinkGraphBootstrapState): FlowchartViewDocument {
  return state.flowchartView ?? EMPTY_STATE.flowchartView;
}

function resolveResourceRelationView(state: LinkGraphBootstrapState): ResourceRelationViewDocument {
  return state.resourceRelationView ?? EMPTY_STATE.resourceRelationView;
}

function mergeVisibleGraphIntoFactFullGraph(
  currentView: FactGraphViewDocument,
  nextVisibleGraph: LinkGraphDocument,
): LinkGraphDocument {
  const nextVisibleNodeById = new Map(nextVisibleGraph.nodes.map((node) => [node.id, node]));
  const nextVisibleEdgeById = new Map(nextVisibleGraph.edges.map((edge) => [edge.id, edge]));
  const currentFullNodeIds = new Set(currentView.fullGraph.nodes.map((node) => node.id));
  const currentFullEdgeIds = new Set(currentView.fullGraph.edges.map((edge) => edge.id));
  const removedVisibleNodeIds = new Set(
    currentView.visibleGraph.nodes
      .filter((node) => !nextVisibleNodeById.has(node.id))
      .map((node) => node.id),
  );
  const removedVisibleEdgeIds = new Set(
    currentView.visibleGraph.edges
      .filter((edge) => !nextVisibleEdgeById.has(edge.id))
      .map((edge) => edge.id),
  );

  const mergedNodes = currentView.fullGraph.nodes
    .filter((node) => !removedVisibleNodeIds.has(node.id))
    .map((node) => nextVisibleNodeById.get(node.id) ?? node);
  nextVisibleGraph.nodes.forEach((node) => {
    if (!currentFullNodeIds.has(node.id)) {
      mergedNodes.push(node);
    }
  });

  const mergedEdges = currentView.fullGraph.edges
    .filter((edge) =>
      !removedVisibleEdgeIds.has(edge.id)
      && !removedVisibleNodeIds.has(edge.source)
      && !removedVisibleNodeIds.has(edge.target),
    )
    .map((edge) => nextVisibleEdgeById.get(edge.id) ?? edge)
    .filter((edge) => !removedVisibleNodeIds.has(edge.source) && !removedVisibleNodeIds.has(edge.target));
  nextVisibleGraph.edges.forEach((edge) => {
    if (!currentFullEdgeIds.has(edge.id)) {
      mergedEdges.push(edge);
    }
  });

  return {
    ...currentView.fullGraph,
    nodes: mergedNodes,
    edges: mergedEdges,
  };
}

function syncFactGraphViewDocument(
  currentView: FactGraphViewDocument,
  nextVisibleGraph: LinkGraphDocument,
  nextAnchorNodeId: string | null,
): FactGraphViewDocument {
  const fullGraph = mergeVisibleGraphIntoFactFullGraph(currentView, nextVisibleGraph);
  return {
    ...currentView,
    visibleGraph: nextVisibleGraph,
    fullGraph,
    anchorNodeId: nextAnchorNodeId,
    summary: deriveFactGraphSummary(nextVisibleGraph, fullGraph, nextAnchorNodeId),
  };
}

function applyBootstrapRoutesToDocument(
  nextDocument: LinkGraphDocument,
  currentDocument: LinkGraphDocument,
): LinkGraphDocument {
  const nextEdges = applyBootstrapEdgeRoutes(nextDocument.edges, currentDocument.edges);
  return nextEdges === nextDocument.edges
    ? nextDocument
    : {
        ...nextDocument,
        edges: nextEdges,
      };
}

function applyBootstrapRoutesToViewDocument<
  T extends {
    visibleGraph: LinkGraphDocument;
    fullGraph: LinkGraphDocument;
  },
>(
  nextView: T,
  currentView: T,
): T {
  return {
    ...nextView,
    visibleGraph: applyBootstrapRoutesToDocument(nextView.visibleGraph, currentView.visibleGraph),
    fullGraph: applyBootstrapRoutesToDocument(nextView.fullGraph, currentView.fullGraph),
  };
}

function reuseCurrentViewGraphs<
  T extends {
    visibleGraph: LinkGraphDocument;
    fullGraph: LinkGraphDocument;
  },
>(
  nextView: T,
  currentView: T,
  reuseCurrentGraphs: boolean,
): T {
  if (!reuseCurrentGraphs) {
    return nextView;
  }
  if (nextView.visibleGraph === currentView.visibleGraph && nextView.fullGraph === currentView.fullGraph) {
    return nextView;
  }
  return {
    ...nextView,
    visibleGraph: currentView.visibleGraph,
    fullGraph: currentView.fullGraph,
  };
}

function applyLayoutUpdatesToGraphDocument(
  currentGraph: LinkGraphDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): LinkGraphDocument {
  if (updates.length === 0 || currentGraph.nodes.length === 0) {
    return currentGraph;
  }
  const updateMap = new Map(updates.map((update) => [update.id, update.position]));
  let changed = false;
  const nextNodes = currentGraph.nodes.map((node) => {
    const nextPosition = updateMap.get(node.id);
    if (!nextPosition) {
      return node;
    }
    const currentPosition = resolveNodePosition(node);
    if (currentPosition?.x === nextPosition.x && currentPosition?.y === nextPosition.y) {
      return node;
    }
    changed = true;
    return syncNodePosition(node, nextPosition);
  });
  return changed
    ? {
        ...currentGraph,
        nodes: nextNodes,
        edges: currentGraph.edges,
      }
    : currentGraph;
}

function syncFlowchartViewLayout(
  currentView: FlowchartViewDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): FlowchartViewDocument {
  const visibleGraph = applyLayoutUpdatesToGraphDocument(currentView.visibleGraph, updates);
  const fullGraph = applyLayoutUpdatesToGraphDocument(currentView.fullGraph, updates);
  return {
    ...currentView,
    visibleGraph,
    fullGraph,
    summary: deriveFlowchartSummary(visibleGraph, fullGraph),
  };
}

function syncResourceRelationViewLayout(
  currentView: ResourceRelationViewDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): ResourceRelationViewDocument {
  const visibleGraph = applyLayoutUpdatesToGraphDocument(currentView.visibleGraph, updates);
  const fullGraph = applyLayoutUpdatesToGraphDocument(currentView.fullGraph, updates);
  return {
    ...currentView,
    visibleGraph,
    fullGraph,
    summary: deriveResourceRelationSummary(visibleGraph),
  };
}

function resolveActiveViewDocument(
  state: LinkGraphBootstrapState,
  displayMode: AnalysisDisplayMode = state.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE,
): FactGraphViewDocument | FlowchartViewDocument | ResourceRelationViewDocument {
  switch (displayMode) {
    case "FLOWCHART":
      return resolveFlowchartView(state);
    case "RESOURCE_RELATION_VIEW":
      return resolveResourceRelationView(state);
    case "FACT_GRAPH":
    default:
      return resolveFactGraphView(state);
  }
}

export function resolveAuditTargetNodeIds(
  targetNodeId: string | undefined,
  selectionGroupNodeIds: string[],
): string[] {
  if (targetNodeId) {
    return [targetNodeId];
  }
  return selectionGroupNodeIds.length > 1 ? selectionGroupNodeIds : [];
}

function resolveCollapsedDescendantSummary(
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
  collapsedNodeIds: string[],
): {
  hiddenNodeIds: Set<string>;
  descendantCountByNodeId: Record<string, number>;
} {
  if (collapsedNodeIds.length === 0) {
    return {
      hiddenNodeIds: new Set(),
      descendantCountByNodeId: {},
    };
  }
  const outgoingEdgeMap = new Map<string, string[]>();
  edges.forEach((edge) => {
    const nextTargets = outgoingEdgeMap.get(edge.source) ?? [];
    nextTargets.push(edge.target);
    outgoingEdgeMap.set(edge.source, nextTargets);
  });
  const nodeById = new Map(nodes.map((node) => [node.id, node]));

  const hiddenNodeIds = new Set<string>();
  const descendantCountByNodeId: Record<string, number> = {};
  collapsedNodeIds.forEach((collapsedNodeId) => {
    const descendants = new Set<string>();
    let overflowHiddenCount = 0;
    const queue = new FifoQueue([...(outgoingEdgeMap.get(collapsedNodeId) ?? [])]);
    while (true) {
      const nextNodeId = queue.dequeue();
      if (!nextNodeId) {
        break;
      }
      if (descendants.has(nextNodeId) || nextNodeId === collapsedNodeId) {
        continue;
      }
      descendants.add(nextNodeId);
      hiddenNodeIds.add(nextNodeId);
      const overflowCount = Number(
        nodeById.get(nextNodeId)?.metadata?.["linkGraph.overflow.hiddenMethodCount"]
          ?? nodeById.get(nextNodeId)?.metadata?.["linkGraph.hiddenNodeCount"],
      );
      if (Number.isFinite(overflowCount) && overflowCount > 0) {
        overflowHiddenCount += overflowCount;
      }
      queue.enqueue(...(outgoingEdgeMap.get(nextNodeId) ?? []));
    }
    descendantCountByNodeId[collapsedNodeId] = descendants.size + overflowHiddenCount;
  });
  return {
    hiddenNodeIds,
    descendantCountByNodeId,
  };
}

export function App() {
  const initialStateRef = useRef<LinkGraphBootstrapState | null>(null);
  if (!initialStateRef.current) {
    initialStateRef.current = resolveInitialState();
  }
  const initialState = initialStateRef.current;
  const initialGraph = resolveVisibleGraph(initialState);
  const initialAnchorNodeId = resolveAnchorNodeId(
    initialGraph.nodes,
    initialState.selectedNodeId ?? initialGraph.nodes[0]?.id ?? null,
  );
  const {
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
    sourceNavigationState: _sourceNavigationState,
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
  } = useWorkbenchState({
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
  });
  const bridgeCommands = useBridgeCommandController({
    setOperationFeedback,
    setRequestFailureNotice,
  });
  const sourceNavigationCommands = useSourceNavigationController({
    nodes,
    selectNode: (nodeId) => {
      setSelectedNodeId(nodeId);
    },
    setSourceNavigationState,
    setOperationFeedback,
    bridgeCommands,
  });
  const workbenchCommands = useWorkbenchCommandController({
    setGenerationPlan,
    setGenerationPlanRequestState,
    setGeneratedCodeDrafts,
    setGeneratedCodeDraftWarnings,
    setGeneratedCodeDraftSource,
    setGeneratedCodeDraftPromptPreview,
    setGeneratedCodeDraftPromptPreviewArtifactId,
    setGeneratedCodeDraftWriteReport,
    setCodeDraftRequestState,
    bridgeCommands,
  });
  const toolbarFeedback = useMemo(() => resolveToolbarFeedback({
    operationFeedback,
    lastMessageType,
    requestStates: [
      auditRequestState,
      diffReviewRequestState,
      graphBeautificationRequestState,
      generationPlanRequestState,
      codeDraftRequestState,
    ],
  }), [
    auditRequestState,
    codeDraftRequestState,
    diffReviewRequestState,
    generationPlanRequestState,
    graphBeautificationRequestState,
    lastMessageType,
    operationFeedback,
  ]);
  const [activeWorkbenchTab, setActiveWorkbenchTab] = useState<WorkbenchTab>("explanation");
  const [workbenchSectionPreferences, setWorkbenchSectionPreferences] = useState<WorkbenchSectionPreferences>(
    () => initialState.workbenchSectionPreferences ?? {},
  );
  const [selectedExplanationStepId, setSelectedExplanationStepId] = useState<string | null>(
    () => graphBeautificationResult?.steps?.[0]?.stepId ?? null,
  );
  const [selectedExplanationGranularity, setSelectedExplanationGranularity] = useState<StepGranularity>(
    () => graphBeautificationResult?.granularity ?? "BUSINESS",
  );
  const [explanationHistory, setExplanationHistory] = useState<ExplanationHistoryEntry[]>([]);
  const [currentExplanationSessionLabel, setCurrentExplanationSessionLabel] = useState(DEFAULT_EXPLANATION_SESSION_LABEL);
  const [hoveredExplanationStepId, setHoveredExplanationStepId] = useState<string | null>(null);
  const [selectedAuditChangeId, setSelectedAuditChangeId] = useState<string | null>(null);
  const [selectedAuditLeadId, setSelectedAuditLeadId] = useState<string | null>(null);
  const [auditSourceLeadId, setAuditSourceLeadId] = useState<string | null>(null);
  const [selectedDraftEntryId, setSelectedDraftEntryId] = useState<string | null>(
    () => initialState.draftWorkbenchState?.draftChanges[0]?.entryId
      ?? initialState.draftWorkbenchState?.draftNotes[0]?.entryId
      ?? null,
  );
  const [draftCompareMode, setDraftCompareMode] = useState<"after" | "compare">("after");
  const [focusNodeRequest, setFocusNodeRequest] = useState<GraphFocusRequest | null>(null);
  const semanticRevisionRef = useRef<number | null>(initialState.semanticRevision ?? null);
  const layoutRevisionRef = useRef<number | null>(initialState.layoutRevision ?? null);
  const nextManualNodeIdRef = useRef(nextManualNodeSequence(initialGraph.nodes));
  const nodesRef = useRef(nodes);
  const edgesRef = useRef(edges);
  const draftGraphRef = useRef(draftGraph);
  const anchorNodeIdRef = useRef(anchorNodeId);
  const analysisDisplayModeRef = useRef(analysisDisplayMode);
  const pendingExplanationDrillTargetRef = useRef<string | null>(null);
  const nextFocusRequestNonceRef = useRef(0);
  const explanationLocalOverrideRef = useRef(false);
  const pendingExplanationRequestModeRef = useRef<ExplanationRequestMode | null>(null);
  const requestFailureSignatureRef = useRef<Record<string, string | null>>({
    audit: null,
    diff: null,
    plan: null,
    beautification: null,
    drafts: null,
  });
  const interactionProbeRef = useRef<{
    scheduled: boolean;
    selection: { ids: string[]; startedAt: number } | null;
    inspect: { nodeId: string; startedAt: number } | null;
    move: { nodeId: string; position: GraphPosition; startedAt: number } | null;
    source: { nodeId: string; startedAt: number } | null;
  }>({
    scheduled: false,
    selection: null,
    inspect: null,
    move: null,
    source: null,
  });
  const interactionProbeTimerIdsRef = useRef<number[]>([]);

  useEffect(() => {
    nodesRef.current = nodes;
  }, [nodes]);

  useEffect(() => {
    edgesRef.current = edges;
  }, [edges]);

  useEffect(() => {
    draftGraphRef.current = draftGraph;
  }, [draftGraph]);

  useEffect(() => {
    anchorNodeIdRef.current = anchorNodeId;
  }, [anchorNodeId]);

  useEffect(() => {
    setSelectedDraftEntryId((current) => {
      const allEntries = draftWorkbenchState.draftChanges.concat(draftWorkbenchState.draftNotes);
      if (current && allEntries.some((entry) => entry.entryId === current)) {
        return current;
      }
      return draftWorkbenchState.draftChanges[0]?.entryId
        ?? draftWorkbenchState.draftNotes[0]?.entryId
        ?? null;
    });
  }, [draftWorkbenchState.draftChanges, draftWorkbenchState.draftNotes]);

  useEffect(() => {
    analysisDisplayModeRef.current = analysisDisplayMode;
  }, [analysisDisplayMode]);

  const hasConfirmedDraftChanges = draftWorkbenchState.draftChanges.length > 0;

  function syncManualNodeIdCounters(nextNodes: Array<{ id: string }>) {
    nextManualNodeIdRef.current = Math.max(
      nextManualNodeIdRef.current,
      nextManualNodeSequence(nextNodes),
    );
  }

  function trackRequestFailure(
    scope: keyof typeof requestFailureSignatureRef.current,
    title: string,
    requestState: AsyncRequestState,
  ) {
    if (requestState.phase !== "FAILED" && requestState.phase !== "TIMED_OUT") {
      requestFailureSignatureRef.current[scope] = null;
      return;
    }

    const signature = [
      requestState.phase,
      requestState.statusMessage ?? "",
      requestState.errorMessage ?? "",
      requestState.detailMessage ?? "",
      requestState.startedAtEpochMillis ?? "",
      requestState.finishedAtEpochMillis ?? "",
    ].join("|");
    if (requestFailureSignatureRef.current[scope] === signature) {
      return;
    }
    requestFailureSignatureRef.current[scope] = signature;
    setRequestFailureNotice({
      title: requestState.statusMessage?.trim() || `${title}失败`,
      message: requestState.errorMessage?.trim() || `${title}失败，请检查模型设置、网络与插件日志。`,
      detailMessage: requestState.detailMessage?.trim() || null,
    });
  }

  function maybeCompleteSourceNavigationProbe(nextSourceNavigationState: SourceNavigationState) {
    const pendingSource = interactionProbeRef.current.source;
    if (!pendingSource) {
      return;
    }
    if (nextSourceNavigationState.nodeId !== pendingSource.nodeId) {
      return;
    }
    if (nextSourceNavigationState.phase === "IDLE" || nextSourceNavigationState.phase === "RUNNING") {
      return;
    }
    traceLinkGraph("probe.app.sourceNavigation.completed", {
      nodeId: pendingSource.nodeId,
      phase: nextSourceNavigationState.phase,
      result: nextSourceNavigationState.result ?? null,
      targetPath: nextSourceNavigationState.targetPath ?? null,
      errorMessage: nextSourceNavigationState.errorMessage ?? null,
      durationMs: measureDuration(pendingSource.startedAt),
    });
    interactionProbeRef.current.source = null;
  }

  function resolveArtifactText(artifactId: string): string | null {
    return artifactContents[artifactId] ?? null;
  }

  function handleRequestArtifact(artifactId: string) {
    if (artifactContents[artifactId]) {
      return;
    }
    requestArtifactContent([artifactId]);
  }

  function applyBootstrapState(nextState: LinkGraphBootstrapState) {
    const startedAt = measureStart();
    const currentNodes = nodesRef.current;
    const currentEdges = edgesRef.current;
    const currentDraftGraph = draftGraphRef.current;
    const effectiveCurrentDraftGraph = currentDraftGraph ?? {
      nodes: currentNodes,
      edges: currentEdges,
    };
    const currentAnchorNodeId = anchorNodeIdRef.current;
    const currentAnalysisDisplayMode = analysisDisplayModeRef.current;
    const nextAnalysisDisplayMode = nextState.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE;
    const analysisDisplayModeChanged = nextAnalysisDisplayMode !== currentAnalysisDisplayMode;
    const nextSourceNavigationState = resolveSourceNavigationState(nextState);
    let nextFactGraphView = applyBootstrapRoutesToViewDocument(resolveFactGraphView(nextState), factGraphView);
    let nextFlowchartView = applyBootstrapRoutesToViewDocument(resolveFlowchartView(nextState), flowchartView);
    let nextResourceRelationView = applyBootstrapRoutesToViewDocument(
      resolveResourceRelationView(nextState),
      resourceRelationView,
    );
    const nextWorkingGraph = applyBootstrapRoutesToDocument(resolveWorkingGraph(nextState), effectiveCurrentDraftGraph);
    traceLinkGraph("app.applyBootstrapState.start", {
      bootstrap: summarizeBootstrapState(nextState),
      currentGraph: summarizeGraph({ nodes: currentNodes, edges: currentEdges }),
    });
    const visibleGraph = resolveActiveViewDocument({
      ...nextState,
      factGraphView: nextFactGraphView,
      flowchartView: nextFlowchartView,
      resourceRelationView: nextResourceRelationView,
    }, nextAnalysisDisplayMode).visibleGraph;
    const revisionsAvailable = hasRevision(nextState.semanticRevision) || hasRevision(nextState.layoutRevision);
    const semanticRevisionAdvanced = hasRevision(nextState.semanticRevision)
      && nextState.semanticRevision !== semanticRevisionRef.current;
    const layoutRevisionAdvanced = hasRevision(nextState.layoutRevision)
      && nextState.layoutRevision !== layoutRevisionRef.current;
    let nextVisibleGraphWithPositionsCache: LinkGraphDocument | null = null;
    function nextVisibleGraphWithPositions() {
      if (nextVisibleGraphWithPositionsCache) {
        return nextVisibleGraphWithPositionsCache;
      }
      nextVisibleGraphWithPositionsCache = {
        ...visibleGraph,
        nodes: applyBootstrapNodePositions(
          visibleGraph.nodes,
          currentNodes,
          nextState.layoutState,
          !analysisDisplayModeChanged,
        ),
      };
      return nextVisibleGraphWithPositionsCache;
    }
    const visibleSemanticChanged = revisionsAvailable
      ? analysisDisplayModeChanged
        || (
          semanticRevisionAdvanced
          && graphSemanticSignature({ nodes: currentNodes, edges: currentEdges }) !== graphSemanticSignature(visibleGraph)
        )
      : analysisDisplayModeChanged
        || graphSemanticSignature({ nodes: currentNodes, edges: currentEdges }) !== graphSemanticSignature(visibleGraph);
    const visibleLayoutChanged = revisionsAvailable
      ? layoutRevisionAdvanced
        && graphLayoutSignature(currentNodes) !== graphLayoutSignature(nextVisibleGraphWithPositions().nodes)
      : graphLayoutSignature(currentNodes) !== graphLayoutSignature(nextVisibleGraphWithPositions().nodes);
    const semanticGraphChanged = revisionsAvailable
      ? visibleSemanticChanged
      : visibleSemanticChanged;
    const layoutGraphChanged = revisionsAvailable
      ? semanticGraphChanged || (layoutRevisionAdvanced && visibleLayoutChanged)
      : semanticGraphChanged || visibleLayoutChanged;
    const nextGraph = semanticGraphChanged || layoutGraphChanged
      ? nextVisibleGraphWithPositions()
      : {
          nodes: currentNodes,
          edges: currentEdges,
        };
    const reuseCurrentViewGraphsWhenStable = !semanticGraphChanged && !layoutGraphChanged;
    nextFactGraphView = reuseCurrentViewGraphs(nextFactGraphView, factGraphView, reuseCurrentViewGraphsWhenStable);
    nextFlowchartView = reuseCurrentViewGraphs(nextFlowchartView, flowchartView, reuseCurrentViewGraphsWhenStable);
    nextResourceRelationView = reuseCurrentViewGraphs(
      nextResourceRelationView,
      resourceRelationView,
      reuseCurrentViewGraphsWhenStable,
    );
    const requestedDraftPatchFocusNodeId = nextState.lastMessageType === "draftPatchApplied"
      ? nextState.lastDraftPatchApplyResult?.focusNodeId ?? null
      : null;
    const nextSelectedNodeId = nextState.selectedNodeId ?? nextGraph.nodes[0]?.id ?? null;
    const shouldPreserveLocalSelection = Boolean(
      selectedNodeId
      && !semanticGraphChanged
      && !layoutGraphChanged
      && REQUEST_ONLY_SELECTION_MESSAGE_TYPES.has(nextState.lastMessageType ?? "")
      && nextGraph.nodes.some((node) => node.id === selectedNodeId),
    );
    const effectiveSelectedNodeId = requestedDraftPatchFocusNodeId && nextGraph.nodes.some((node) => node.id === requestedDraftPatchFocusNodeId)
      ? requestedDraftPatchFocusNodeId
      : shouldPreserveLocalSelection
        ? selectedNodeId
        : nextSelectedNodeId;
    const nextAnchorNodeId = resolveAnchorNodeId(
      nextGraph.nodes,
      shouldResetAnchorNode(nextState, semanticGraphChanged)
        ? effectiveSelectedNodeId
        : currentAnchorNodeId ?? effectiveSelectedNodeId,
    );
    const nextNodes = semanticGraphChanged
      ? normalizeGraphNodes(
          nextGraph.nodes,
          nextGraph.edges,
          nextAnchorNodeId,
          nextState.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE,
        )
      : layoutGraphChanged
        ? applyLayoutOnlyNodePositions(currentNodes, nextGraph.nodes)
        : currentNodes;
    syncManualNodeIdCounters(nextNodes);
    let nextDraftGraph = effectiveCurrentDraftGraph;
    let nextDraftGraphWithPositions = effectiveCurrentDraftGraph;
    nextDraftGraph = nextWorkingGraph;
    nextDraftGraphWithPositions = nextDraftGraph.nodes.length > 0
      ? {
          ...nextDraftGraph,
          nodes: applyBootstrapNodePositions(
            nextDraftGraph.nodes,
            effectiveCurrentDraftGraph.nodes,
            nextState.layoutState,
            !analysisDisplayModeChanged,
          ),
        }
      : nextDraftGraph;
    const workingSemanticChanged = nextState.workingGraph != null
      ? revisionsAvailable
        ? semanticRevisionAdvanced
          && graphSemanticSignature(effectiveCurrentDraftGraph) !== graphSemanticSignature(nextDraftGraph)
        : graphSemanticSignature(effectiveCurrentDraftGraph) !== graphSemanticSignature(nextDraftGraph)
      : semanticGraphChanged;
    const workingLayoutChanged = nextState.workingGraph != null
      ? graphLayoutSignature(effectiveCurrentDraftGraph.nodes) !== graphLayoutSignature(nextDraftGraphWithPositions.nodes)
      : layoutGraphChanged;
    const draftSemanticChanged = revisionsAvailable
      ? workingSemanticChanged
      : workingSemanticChanged;
    const draftLayoutChanged = revisionsAvailable
      ? draftSemanticChanged || (layoutRevisionAdvanced && workingLayoutChanged)
      : draftSemanticChanged || workingLayoutChanged;
    const nextDraftGraphNodes = draftSemanticChanged && nextDraftGraphWithPositions.nodes.length > 0
      ? normalizeGraphNodes(
          nextDraftGraphWithPositions.nodes,
          nextDraftGraphWithPositions.edges,
          nextAnchorNodeId,
          nextState.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE,
        )
      : draftLayoutChanged
        ? applyLayoutOnlyNodePositions(effectiveCurrentDraftGraph.nodes, nextDraftGraphWithPositions.nodes)
        : effectiveCurrentDraftGraph.nodes;
    traceLinkGraph("app.applyBootstrapState.computed", {
      lastMessageType: nextState.lastMessageType ?? null,
      activeWorkbenchTab,
      analysisDisplayModeChanged,
      currentAnalysisDisplayMode,
      nextAnalysisDisplayMode,
      semanticGraphChanged,
      layoutGraphChanged,
      visibleSemanticChanged,
      visibleLayoutChanged,
      draftSemanticChanged,
      draftLayoutChanged,
      semanticRevisionAdvanced,
      layoutRevisionAdvanced,
      currentSemanticRevision: semanticRevisionRef.current,
      nextSemanticRevision: nextState.semanticRevision ?? null,
      currentLayoutRevision: layoutRevisionRef.current,
      nextLayoutRevision: nextState.layoutRevision ?? null,
      reuseCurrentViewGraphsWhenStable,
      nextAnchorNodeId,
      nextSelectedNodeId,
      nextGraph: summarizeGraph(nextGraph),
      normalizedGraph: summarizeGraph({ nodes: nextNodes, edges: nextGraph.edges }),
      durationMs: measureDuration(startedAt),
    });
    traceLinkGraph("app.applyBootstrapState.mutationPlan", {
      willSetNodes: semanticGraphChanged || layoutGraphChanged,
      willSetEdges: semanticGraphChanged,
      nextSelectedNodeId: effectiveSelectedNodeId,
      nextAnchorNodeId,
      lastMessageType: nextState.lastMessageType ?? null,
      activeWorkbenchTab,
    });
    if (semanticGraphChanged || layoutGraphChanged) {
      setNodes(nextNodes);
    }
    if (semanticGraphChanged) {
      setEdges(nextGraph.edges);
    }
    setFactGraphView(
      nextAnalysisDisplayMode === "FACT_GRAPH"
        ? {
            ...nextFactGraphView,
            anchorNodeId: nextAnchorNodeId,
            summary: deriveFactGraphSummary(
              nextFactGraphView.visibleGraph,
              nextFactGraphView.fullGraph,
              nextAnchorNodeId,
            ),
          }
        : nextFactGraphView,
    );
    setFlowchartView(
      nextAnalysisDisplayMode === "FLOWCHART"
        ? {
            ...nextFlowchartView,
            anchorNodeId: nextAnchorNodeId,
          }
        : nextFlowchartView,
    );
    setResourceRelationView(
      nextAnalysisDisplayMode === "RESOURCE_RELATION_VIEW"
        ? {
            ...nextResourceRelationView,
            anchorNodeId: nextAnchorNodeId,
          }
        : nextResourceRelationView,
    );
    setFactGraph(resolveReferenceFactGraph(nextState));
    setAnalysisDisplayMode(nextAnalysisDisplayMode);
    setDraftGraph(nextDraftGraphWithPositions.nodes.length > 0
      ? {
          ...nextDraftGraphWithPositions,
          nodes: nextDraftGraphNodes,
        }
      : nextDraftGraphWithPositions);
    setDraftWorkbenchState(nextState.draftWorkbenchState ?? { draftChanges: [], draftNotes: [] });
    setDesignBaseline(resolveDesignBaselineGraph(nextState));
    setDraftPatchPreview(nextState.draftPatchPreview ?? null);
    setCanUndoDraftPatchApply(nextState.canUndoDraftPatchApply ?? false);
    setLastAppliedDraftPatchSummary(nextState.lastAppliedDraftPatchSummary ?? null);
    setLastDraftPatchApplyResult(nextState.lastDraftPatchApplyResult ?? null);
    if (!(nextState.canUndoDraftPatchApply ?? false) && !nextState.lastAppliedDraftPatchSummary) {
      setLastAppliedDraftPatchPreview(null);
    }
    setAuditResult(nextState.auditResult ?? null);
    setAuditRequestState(resolveRequestState(nextState.auditRequestState));
    setDiffReviewResult(nextState.diffReviewResult ?? null);
    setDiffReviewRequestState(resolveRequestState(nextState.diffReviewRequestState));
    setAnchorNodeId(nextAnchorNodeId);
    setSelectedNodeId(effectiveSelectedNodeId);
    setMermaidIssues(nextState.mermaidIssues ?? []);
    setDiffItems(nextState.diffItems);
    setSyncPreviewItems(nextState.syncPreviewItems);
    setGenerationPlan(nextState.generationPlan ?? null);
    setGenerationPlanRequestState(resolveRequestState(nextState.generationPlanRequestState));
    if (!explanationLocalOverrideRef.current) {
      setGraphBeautificationResult(nextState.graphBeautificationResult ?? null);
      setGraphBeautificationRequestState(resolveRequestState(nextState.graphBeautificationRequestState));
    }
    setGeneratedCodeDrafts(nextState.generatedCodeDrafts ?? []);
    setGeneratedCodeDraftWarnings(nextState.generatedCodeDraftWarnings ?? []);
    setGeneratedCodeDraftSource(nextState.generatedCodeDraftSource ?? null);
    setGeneratedCodeDraftPromptPreview(nextState.generatedCodeDraftPromptPreview ?? null);
    setGeneratedCodeDraftPromptPreviewArtifactId(nextState.generatedCodeDraftPromptPreviewArtifactId ?? null);
    setGeneratedCodeDraftWriteReport(nextState.generatedCodeDraftWriteReport ?? null);
    setCodeDraftRequestState(resolveRequestState(nextState.codeDraftRequestState));
    setSourceNavigationState(nextSourceNavigationState);
    setOperationFeedback(nextState.operationFeedback ?? null);
    setWorkbenchSectionPreferences(nextState.workbenchSectionPreferences ?? {});
    setLastMessageType(nextState.lastMessageType ?? null);
    setGraphSurfaceExperiments(nextState.graphSurfaceExperiments ?? null);
    if (nextState.artifactContents) {
      setArtifactContents((current) => ({
        ...current,
        ...nextState.artifactContents,
      }));
    }
    maybeCompleteSourceNavigationProbe(nextSourceNavigationState);
    if (requestedDraftPatchFocusNodeId && nextNodes.some((node) => node.id === requestedDraftPatchFocusNodeId)) {
      setDetailNodeId(requestedDraftPatchFocusNodeId);
    }
    if (semanticGraphChanged) {
      setSelectionGroupNodeIds([]);
      setCollapsedNodeIds([]);
      setDiffTargetItemIds([]);
    } else {
      setSelectionGroupNodeIds((current) => current.filter((nodeId) => nextNodes.some((node) => node.id === nodeId)));
      setCollapsedNodeIds((current) => current.filter((nodeId) => nextNodes.some((node) => node.id === nodeId)));
      setDiffTargetItemIds((current) => current.filter((itemId) => nextState.diffItems.some((item) => item.id === itemId)));
    }
    if (hasRevision(nextState.semanticRevision)) {
      semanticRevisionRef.current = nextState.semanticRevision;
    }
    if (hasRevision(nextState.layoutRevision)) {
      layoutRevisionRef.current = nextState.layoutRevision;
    }
    setDetailNodeId((current) => (current && nextNodes.some((node) => node.id === current) ? current : null));
  }

  const { lastAppliedSnapshotRevisionRef } = useBootstrapStateController({
    initialRevision: initialState.snapshotRevision ?? Number.NEGATIVE_INFINITY,
    applyBootstrapState,
  });

  const selectedNode = nodes.find((node) => node.id === selectedNodeId) ?? null;
  const detailNode = nodes.find((node) => node.id === detailNodeId) ?? null;
  const auditTargetTitle = auditTargetNodeIds.length === 1
    ? nodes.find((node) => node.id === auditTargetNodeIds[0])?.title ?? null
    : null;

  function resolveAuditScope(targetNodeId?: string) {
    const nodeIds = resolveAuditTargetNodeIds(targetNodeId, selectionGroupNodeIds);
    return {
      nodeIds,
      title: nodeIds.length === 1
        ? nodes.find((node) => node.id === nodeIds[0])?.title ?? null
        : null,
    };
  }

  function buildDefaultAuditQuestion(targetNodeIds: string[], targetTitle: string | null): string {
    if (targetNodeIds.length === 0) {
      return "请审计当前整张链路图，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。";
    }
    if (targetNodeIds.length === 1) {
      return `请审计节点“${targetTitle ?? targetNodeIds[0]}”及其直接关联链路，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。`;
    }
    return `请审计当前选中的 ${targetNodeIds.length} 个节点及其关联链路，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。`;
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
  const collapsedSummary = useMemo(
    () => resolveCollapsedDescendantSummary(nodes, edges, collapsedNodeIds),
    [nodes, edges, collapsedNodeIds],
  );
  const hiddenNodeIdSet = collapsedSummary.hiddenNodeIds;
  const hiddenNodeIds = useMemo(
    () => Array.from(hiddenNodeIdSet),
    [hiddenNodeIdSet],
  );

  function clearLocalDerivedGraphState() {
    setDraftPatchPreview(null);
    setLastAppliedDraftPatchPreview(null);
    setCanUndoDraftPatchApply(false);
    setLastAppliedDraftPatchSummary(null);
    setAuditResult(null);
    setAuditRequestState(IDLE_REQUEST_STATE);
    setDiffReviewResult(null);
    setDiffReviewRequestState(IDLE_REQUEST_STATE);
    setSyncPreviewItems([]);
    setGenerationPlan(null);
    setGenerationPlanRequestState(IDLE_REQUEST_STATE);
    setGraphBeautificationResult(null);
    setGraphBeautificationRequestState(IDLE_REQUEST_STATE);
    setGeneratedCodeDrafts([]);
    setGeneratedCodeDraftWarnings([]);
    setGeneratedCodeDraftSource(null);
    setGeneratedCodeDraftPromptPreview(null);
    setGeneratedCodeDraftPromptPreviewArtifactId(null);
    setGeneratedCodeDraftWriteReport(null);
    setLastDraftPatchApplyResult(null);
    setCodeDraftRequestState(IDLE_REQUEST_STATE);
  }

  function syncGraph(
    nextNodes: LinkGraphNode[],
    nextEdges: LinkGraphEdge[],
    nextSelectedNodeId: string | null = selectedNodeId,
    options?: {
      forceRelayout?: boolean;
    },
  ) {
    const startedAt = measureStart();
    const nextAnchorNodeId = resolveAnchorNodeId(
      nextNodes,
      anchorNodeIdRef.current ?? nextSelectedNodeId,
    );
    const nodesForLayout = options?.forceRelayout ? nextNodes.map(clearStoredNodePosition) : nextNodes;
    const laidOutNodes = normalizeGraphNodes(
      nodesForLayout,
      nextEdges,
      nextAnchorNodeId,
      analysisDisplayMode,
    );
    traceLinkGraph("app.syncGraph", {
      nextAnchorNodeId,
      nextSelectedNodeId,
      inputGraph: summarizeGraph({ nodes: nodesForLayout, edges: nextEdges }),
      laidOutGraph: summarizeGraph({ nodes: laidOutNodes, edges: nextEdges }),
      durationMs: measureDuration(startedAt),
    });
    setNodes(laidOutNodes);
    setEdges(nextEdges);
    setAnchorNodeId(nextAnchorNodeId);
    setSelectedNodeId(nextSelectedNodeId);
    syncManualNodeIdCounters(laidOutNodes);
    setCollapsedNodeIds((current) => current.filter((nodeId) => laidOutNodes.some((node) => node.id === nodeId)));
    if (detailNodeId && !laidOutNodes.some((node) => node.id === detailNodeId)) {
      setDetailNodeId(null);
    }
    setDraftGraph((current) => ({
      ...(current ?? { nodes: [], edges: [] }),
      nodes: laidOutNodes,
      edges: nextEdges,
    }));
    if (analysisDisplayMode === "FACT_GRAPH") {
      setFactGraphView((current) =>
        syncFactGraphViewDocument(current, { nodes: laidOutNodes, edges: nextEdges }, nextAnchorNodeId),
      );
    } else if (analysisDisplayMode === "FLOWCHART") {
      const nextGraph = { nodes: laidOutNodes, edges: nextEdges };
      setFlowchartView((current) => ({
        ...current,
        visibleGraph: nextGraph,
        fullGraph: nextGraph,
        anchorNodeId: nextAnchorNodeId,
        summary: deriveFlowchartSummary(nextGraph, nextGraph),
      }));
    } else if (analysisDisplayMode === "RESOURCE_RELATION_VIEW") {
      const nextGraph = { nodes: laidOutNodes, edges: nextEdges };
      setResourceRelationView((current) => ({
        ...current,
        visibleGraph: nextGraph,
        fullGraph: nextGraph,
        anchorNodeId: nextAnchorNodeId,
        summary: deriveResourceRelationSummary(nextGraph),
      }));
    }
    setAuditTargetNodeIds((current) => current.filter((nodeId) => laidOutNodes.some((node) => node.id === nodeId)));
    clearLocalDerivedGraphState();
    publishGraphChange(laidOutNodes, nextEdges);
    publishLayoutChange(extractLayoutPayload(laidOutNodes));
  }

  function syncSelectedNodeToBridge(nodeId: string) {
    bridgeCommands.runBridgeCommand("节点选中同步", () => publishNodeSelected(nodeId), {
      announceFailure: false,
      failureFeedbackLevel: "WARNING",
      failureMessage: "当前节点选择未同步到 IDE。",
    });
  }

  function handleSelectNode(nodeId: string) {
    startTransition(() => {
      const nextNodeId = nodeId || null;
      setSelectedNodeId((current) => (current === nextNodeId ? current : nextNodeId));
      setSelectionGroupNodeIds((current) => (current.length === 0 ? current : []));
      if (nextNodeId) {
        syncSelectedNodeToBridge(nextNodeId);
      }
    });
  }

  function handleInspectNode(nodeId: string) {
    startTransition(() => {
      setSelectedNodeId((current) => (current === nodeId ? current : nodeId));
      setDetailNodeId((current) => (current === nodeId ? current : nodeId));
      setSelectionGroupNodeIds((current) => (current.length === 0 ? current : []));
      syncSelectedNodeToBridge(nodeId);
    });
  }

  function handleSelectionGroupChange(nodeIds: string[]) {
    startTransition(() => {
      const uniqueNodeIds = Array.from(new Set(nodeIds));
      const nextGroupNodeIds = uniqueNodeIds.length > 1 ? uniqueNodeIds : [];
      setSelectionGroupNodeIds((current) =>
        sameNodeIdList(current, nextGroupNodeIds) ? current : nextGroupNodeIds
      );
      if (uniqueNodeIds.length === 1) {
        const nextNodeId = uniqueNodeIds[0];
        setSelectedNodeId((current) => (current === nextNodeId ? current : nextNodeId));
      }
    });
  }

  function handleRequestSourceNavigation(nodeId: string) {
    sourceNavigationCommands.handleRequestSourceNavigation(nodeId);
  }

  function handleOpenImportMermaid() {
    setMermaidDraft("");
    setImportDialogOpen(true);
  }

  function handleOpenAudit(targetNodeId?: string) {
    const scope = resolveAuditScope(targetNodeId);
    setAuditTargetNodeIds(scope.nodeIds);
    setAuditQuestionDraft(buildDefaultAuditQuestion(scope.nodeIds, scope.title));
    setAuditSourceLeadId(null);
    setActiveWorkbenchTab("audit");
  }

  function handleRequestGenerationPlan() {
    traceLinkGraph("app.requestGenerationPlan.intent", {
      activeWorkbenchTab,
      selectedNodeId,
      analysisDisplayMode,
      generationPlanRequestPhase: generationPlanRequestState.phase,
      hasGenerationPlan: generationPlan != null,
    });
    setActiveWorkbenchTab("plan");
    workbenchCommands.handleRequestGenerationPlan();
  }

  function handleRequestCodeDrafts() {
    setActiveWorkbenchTab("code");
    workbenchCommands.handleRequestCodeDrafts();
  }

  function handleRequestScopedAudit(targetNodeId?: string) {
    const scope = resolveAuditScope(targetNodeId);
    const question = buildDefaultAuditQuestion(scope.nodeIds, scope.title);
    setAuditTargetNodeIds(scope.nodeIds);
    setAuditQuestionDraft(question);
    setAuditSourceLeadId(null);
    handleRequestAudit(question, scope.nodeIds);
  }

  function handleConfirmImportMermaid() {
    const mermaid = mermaidDraft.trim();
    if (mermaid.length === 0) {
      setOperationFeedback({
        level: "WARNING",
        message: "请输入 Mermaid 内容后再导入。",
      });
      return;
    }
    bridgeCommands.runBridgeCommand("导入 Mermaid", () => importMermaid(mermaid), {
      onAccepted: () => {
        setImportDialogOpen(false);
      },
      successFeedback: {
        level: "INFO",
        message: "已提交 Mermaid 导入请求，正在校验链路图。",
      },
    });
  }

  function buildManualNode(
    kind: "METHOD" | "DOC_PAGE",
    nextIndex: number,
    position: GraphPosition,
  ): LinkGraphNode {
    if (kind === "DOC_PAGE") {
      return {
        id: `design-note:${nextIndex}`,
        type: "DOC_PAGE",
        title: `说明${nextIndex}`,
        inputs: [],
        outputs: [],
        doc: "请填写业务说明",
        certainty: "PROVEN",
        bindingStatus: "DESIGN_ONLY",
        sourceTag: "DRAFT_MANUAL",
        position,
        metadata: {
          "linkGraph.manual": "true",
          "ui.x": String(position.x),
          "ui.y": String(position.y),
        },
      };
    }
    return {
      id: `design:${nextIndex}`,
      type: "METHOD",
      title: `新方法${nextIndex}`,
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "DESIGN_ONLY",
      sourceTag: "DRAFT_MANUAL",
      position,
      metadata: {
        "linkGraph.manual": "true",
        "ui.x": String(position.x),
        "ui.y": String(position.y),
      },
    };
  }

  function routeMidpoint(route?: LinkGraphEdge["route"]): GraphPosition | null {
    const points = route?.sections.flatMap((section) => [
      section.startPoint,
      ...(section.bendPoints ?? []),
      section.endPoint,
    ]) ?? [];
    if (points.length < 2) {
      return null;
    }
    const segments = points.slice(1).map((point, index) => {
      const startPoint = points[index]!;
      const endPoint = point;
      return {
        startPoint,
        endPoint,
        length: Math.hypot(endPoint.x - startPoint.x, endPoint.y - startPoint.y),
      };
    });
    const totalLength = segments.reduce((sum, segment) => sum + segment.length, 0);
    if (totalLength <= 0) {
      return points[Math.floor(points.length / 2)] ?? null;
    }
    const midpointOffset = totalLength / 2;
    let traversed = 0;
    for (const segment of segments) {
      if (traversed + segment.length >= midpointOffset) {
        const ratio = (midpointOffset - traversed) / segment.length;
        return {
          x: segment.startPoint.x + (segment.endPoint.x - segment.startPoint.x) * ratio,
          y: segment.startPoint.y + (segment.endPoint.y - segment.startPoint.y) * ratio,
        };
      }
      traversed += segment.length;
    }
    return segments[segments.length - 1]?.endPoint ?? null;
  }

  function resolveEdgeInsertPosition(edge: LinkGraphEdge): GraphPosition {
    const routePosition = routeMidpoint(edge.route);
    if (routePosition) {
      return routePosition;
    }
    const sourceNode = nodes.find((node) => node.id === edge.source);
    const targetNode = nodes.find((node) => node.id === edge.target);
    const sourcePosition = sourceNode ? resolveNodePosition(sourceNode) : null;
    const targetPosition = targetNode ? resolveNodePosition(targetNode) : null;
    if (sourcePosition && targetPosition) {
      return {
        x: (sourcePosition.x + targetPosition.x) / 2,
        y: (sourcePosition.y + targetPosition.y) / 2,
      };
    }
    return fallbackDesignPosition(nodes.length);
  }

  function handleAddNode(kind: "METHOD" | "DOC_PAGE", position?: GraphPosition) {
    startTransition(() => {
      const nextPosition = position ?? fallbackDesignPosition(nodes.length);
      const nextIndex = nextManualNodeIdRef.current++;
      const nextNode = buildManualNode(kind, nextIndex, nextPosition);
      syncGraph([...nodes, nextNode], edges, nextNode.id);
      setDetailNodeId(nextNode.id);
    });
  }

  function handleDeleteNode(nodeId: string) {
    startTransition(() => {
      const nextNodes = nodes.filter((node) => node.id !== nodeId);
      const nextEdges = edges.filter((edge) => edge.source !== nodeId && edge.target !== nodeId);
      syncGraph(nextNodes, nextEdges, selectedNodeId === nodeId ? null : selectedNodeId);
    });
  }

  function handleDeleteNodeSubtree(nodeId: string) {
    startTransition(() => {
      const deletedNodeIds = collectDownstreamSubtreeNodeIds(nodeId, nodes, edges);
      if (deletedNodeIds.size === 0) {
        return;
      }
      const nextNodes = nodes.filter((node) => !deletedNodeIds.has(node.id));
      const nextEdges = edges.filter((edge) => !deletedNodeIds.has(edge.source) && !deletedNodeIds.has(edge.target));
      const nextSelectedNodeId = selectedNodeId && deletedNodeIds.has(selectedNodeId) ? null : selectedNodeId;
      syncGraph(nextNodes, nextEdges, nextSelectedNodeId);
    });
  }

  function handleUpdateNode(nextNode: LinkGraphNode) {
    startTransition(() => {
      const previousNode = nodes.find((node) => node.id === nextNode.id);
      const mergedNode = previousNode?.position ? syncNodePosition(nextNode, previousNode.position) : nextNode;
      syncGraph(
        nodes.map((node) => (node.id === mergedNode.id ? mergedNode : node)),
        edges,
        mergedNode.id,
      );
      setDetailNodeId(mergedNode.id);
    });
  }

  function handleCreateEdge(
    sourceId: string,
    targetId: string,
    sourceHandle?: string | null,
    targetHandle?: string | null,
  ) {
    startTransition(() => {
      const nextEdgeId = `design-link:${sourceId}->${targetId}`;
      if (edges.some((edge) =>
        edge.source === sourceId
        && edge.target === targetId
        && (edge.sourceHandle ?? null) === (sourceHandle ?? null)
        && (edge.targetHandle ?? null) === (targetHandle ?? null)
      )) {
        return;
      }
      const nextEdgeType = analysisDisplayMode === "FLOWCHART" ? "CONTROL_FLOW" : "CALL";
      syncGraph(
        nodes,
        [
          ...edges,
          {
            id: nextEdgeId,
            type: nextEdgeType,
            source: sourceId,
            target: targetId,
            sourceHandle: sourceHandle ?? null,
            targetHandle: targetHandle ?? null,
            sourceTag: "DRAFT_MANUAL",
          },
        ],
      );
    });
  }

  function handleDeleteEdge(edgeId: string) {
    startTransition(() => {
      syncGraph(
        nodes,
        edges.filter((edge) => edge.id !== edgeId),
      );
    });
  }

  function handleInsertNodeIntoEdge(edgeId: string, kind: "METHOD" | "DOC_PAGE") {
    startTransition(() => {
      const targetEdge = edges.find((edge) => edge.id === edgeId);
      if (!targetEdge) {
        return;
      }
      const nextIndex = nextManualNodeIdRef.current++;
      const nextNode = buildManualNode(kind, nextIndex, resolveEdgeInsertPosition(targetEdge));
      const nextEdgeType = analysisDisplayMode === "FLOWCHART" ? "CONTROL_FLOW" : targetEdge.type;
      const nextEdges = edges
        .filter((edge) => edge.id !== edgeId)
        .concat(
          {
            id: `${edgeId}:before`,
            type: nextEdgeType,
            source: targetEdge.source,
            target: nextNode.id,
            label: targetEdge.label,
            metadata: targetEdge.metadata,
            sourceTag: "DRAFT_MANUAL",
          },
          {
            id: `${edgeId}:after`,
            type: nextEdgeType,
            source: nextNode.id,
            target: targetEdge.target,
            sourceTag: "DRAFT_MANUAL",
          },
        );
      syncGraph([...nodes, nextNode], nextEdges, nextNode.id);
      setDetailNodeId(nextNode.id);
    });
  }

  function handleMoveNode(nodeId: string, position: GraphPosition) {
    const currentNode = nodes.find((node) => node.id === nodeId);
    if (!currentNode || !canEditNodeLayout(currentNode)) {
      return;
    }
    startTransition(() => {
      const layoutUpdates = [{ id: nodeId, position }];
      const nextNodes = nodes.map((node) => (node.id === nodeId ? syncNodePosition(node, position) : node));
      setNodes(nextNodes);
      setDraftGraph((current) => current
        ? {
            ...current,
            nodes: nextNodes,
          }
        : current);
      if (analysisDisplayMode === "FACT_GRAPH") {
        setFactGraphView((current) =>
          syncFactGraphViewDocument(
            current,
            { nodes: nextNodes, edges },
            current.anchorNodeId ?? anchorNodeIdRef.current ?? null,
          ),
        );
      } else if (analysisDisplayMode === "FLOWCHART") {
        setFlowchartView((current) => syncFlowchartViewLayout(current, layoutUpdates));
      } else if (analysisDisplayMode === "RESOURCE_RELATION_VIEW") {
        setResourceRelationView((current) => syncResourceRelationViewLayout(current, layoutUpdates));
      }
      traceLinkGraph("app.layoutPublished", {
        reason: "single-node-drag",
        updateCount: 1,
        nodeIds: [nodeId],
      });
      publishLayoutChange([
        {
          nodeId,
          x: position.x,
          y: position.y,
        },
      ]);
    });
  }

  function handleMoveNodes(updates: Array<{ id: string; position: GraphPosition }>) {
    const editableUpdates = updates.filter((update) => {
      const currentNode = nodes.find((node) => node.id === update.id);
      return Boolean(currentNode && canEditNodeLayout(currentNode));
    });
    if (editableUpdates.length === 0) {
      return;
    }
    startTransition(() => {
      const updateMap = new Map(editableUpdates.map((item) => [item.id, item.position]));
      const nextNodes = nodes.map((node) => {
        const nextPosition = updateMap.get(node.id);
        return nextPosition ? syncNodePosition(node, nextPosition) : node;
      });
      setNodes(nextNodes);
      setDraftGraph((current) => current
        ? {
            ...current,
            nodes: nextNodes,
          }
        : current);
      if (analysisDisplayMode === "FACT_GRAPH") {
        setFactGraphView((current) =>
          syncFactGraphViewDocument(
            current,
            { nodes: nextNodes, edges },
            current.anchorNodeId ?? anchorNodeIdRef.current ?? null,
          ),
        );
      } else if (analysisDisplayMode === "FLOWCHART") {
        setFlowchartView((current) => syncFlowchartViewLayout(current, editableUpdates));
      } else if (analysisDisplayMode === "RESOURCE_RELATION_VIEW") {
        setResourceRelationView((current) => syncResourceRelationViewLayout(current, editableUpdates));
      }
      traceLinkGraph("app.layoutPublished", {
        reason: "group-drag",
        updateCount: editableUpdates.length,
        nodeIds: editableUpdates.slice(0, 8).map((update) => update.id),
      });
      publishLayoutChange(
        editableUpdates.map((update) => ({
          nodeId: update.id,
          x: update.position.x,
          y: update.position.y,
        })),
      );
    });
  }

  function handleToggleCollapseNode(nodeId: string) {
    startTransition(() => {
      const nextCollapsedNodeIds = collapsedNodeIds.includes(nodeId)
        ? collapsedNodeIds.filter((item) => item !== nodeId)
        : [...collapsedNodeIds, nodeId];
      const nextHiddenNodeIds = resolveCollapsedDescendantSummary(nodes, edges, nextCollapsedNodeIds).hiddenNodeIds;

      setCollapsedNodeIds(nextCollapsedNodeIds);
      setSelectionGroupNodeIds((current) => current.filter((item) => !nextHiddenNodeIds.has(item)));
      setAuditTargetNodeIds((current) => current.filter((item) => !nextHiddenNodeIds.has(item)));
      if (selectedNodeId && nextHiddenNodeIds.has(selectedNodeId)) {
        setSelectedNodeId(nodeId);
      }
      if (detailNodeId && nextHiddenNodeIds.has(detailNodeId)) {
        setDetailNodeId(null);
      }
    });
  }

  function handleExpandOverflowNode(nodeId: string) {
    const nodeTitle = nodes.find((node) => node.id === nodeId)?.title ?? nodeId;
    workbenchCommands.handleExpandOverflowNode(nodeId, nodeTitle);
  }

  function handleRequestAudit(question: string, targetNodeIds: string[] = auditTargetNodeIds) {
    const normalizedQuestion = question.trim();
    if (normalizedQuestion.length === 0) {
      return;
    }
    setAuditQuestionDraft(normalizedQuestion);
    setAuditTargetNodeIds(targetNodeIds);
    bridgeCommands.submitAsyncBridgeCommand("审计", () => requestAuditAsync(normalizedQuestion, targetNodeIds, auditSourceLeadId), {
      applyRejectedRequestState: setAuditRequestState,
      applySubmittedRequestState: (requestState) => {
        setAuditRequestState(requestState);
        setAuditResult(null);
      },
      onAccepted: () => {
        setActiveWorkbenchTab("audit");
      },
      successFeedback: {
        level: "INFO",
        message: `已发起审计请求${targetNodeIds.length > 0 ? "，范围为当前节点。" : "，范围为整个链路。"}`,
      },
    });
  }

  function handleFormatLayout() {
    startTransition(() => {
      syncGraph(nodes, edges, selectedNodeId, { forceRelayout: true });
      setOperationFeedback({
        level: "INFO",
        message: "已重新整理当前画布布局。",
      });
    });
  }

  function handleFocusDiffItem(itemId: string) {
    setDiffTargetItemIds([itemId]);
    if (nodes.some((node) => node.id === itemId)) {
      handleInspectNode(itemId);
      return;
    }
    setOperationFeedback({
      level: "INFO",
      message: `已聚焦差异项：${itemId}`,
    });
  }

  useEffect(() => {
    const pendingSelection = interactionProbeRef.current.selection;
    if (!pendingSelection) {
      return;
    }
    if (!sameNodeIdList(selectionGroupNodeIds, pendingSelection.ids)) {
      return;
    }
    traceLinkGraph("probe.app.selection.completed", {
      nodeIds: pendingSelection.ids,
      durationMs: measureDuration(pendingSelection.startedAt),
    });
    interactionProbeRef.current.selection = null;
  }, [selectionGroupNodeIds]);

  useEffect(() => {
    const pendingInspect = interactionProbeRef.current.inspect;
    if (!pendingInspect || detailNodeId !== pendingInspect.nodeId || detailNode?.id !== pendingInspect.nodeId) {
      return;
    }
    traceLinkGraph("probe.app.inspect.completed", {
      nodeId: pendingInspect.nodeId,
      durationMs: measureDuration(pendingInspect.startedAt),
    });
    interactionProbeRef.current.inspect = null;
  }, [detailNodeId, detailNode]);

  useEffect(() => {
    const pendingTargetNodeId = pendingExplanationDrillTargetRef.current;
    if (!pendingTargetNodeId) {
      return;
    }
    const targetNode = nodes.find((node) => node.id === pendingTargetNodeId);
    if (!targetNode) {
      return;
    }
    pendingExplanationDrillTargetRef.current = null;
    setSelectedNodeId(targetNode.id);
    requestViewportFocus(targetNode.id);
    setDetailNodeId(targetNode.id);
    setOperationFeedback({
      level: "INFO",
      message: `已自动定位到展开后的被调方法：${targetNode.title}`,
    });
  }, [nodes]);

  useEffect(() => {
    const pendingMove = interactionProbeRef.current.move;
    if (!pendingMove) {
      return;
    }
    const movedNode = nodes.find((node) => node.id === pendingMove.nodeId);
    const position = movedNode?.position;
    if (!position) {
      return;
    }
    if (Math.abs(position.x - pendingMove.position.x) > 0.5 || Math.abs(position.y - pendingMove.position.y) > 0.5) {
      return;
    }
    traceLinkGraph("probe.app.move.completed", {
      nodeId: pendingMove.nodeId,
      position,
      durationMs: measureDuration(pendingMove.startedAt),
    });
    interactionProbeRef.current.move = null;
  }, [nodes]);

  useEffect(() => {
    return () => {
      interactionProbeTimerIdsRef.current.forEach((timerId) => window.clearTimeout(timerId));
      interactionProbeTimerIdsRef.current = [];
    };
  }, []);

  useEffect(() => {
    if (typeof window === "undefined" || window.__linkGraphInteractionProbe !== true) {
      return;
    }
    if (interactionProbeRef.current.scheduled || nodes.length < 100) {
      return;
    }
    const targetNode = nodes.find((node) => canNavigateToSource(node)) ?? nodes[0];
    if (!targetNode) {
      return;
    }
    interactionProbeRef.current.scheduled = true;
    traceLinkGraph("probe.app.start", {
      graph: summarizeGraph({ nodes, edges }),
      targetNodeId: targetNode.id,
    });

    interactionProbeTimerIdsRef.current.forEach((timerId) => window.clearTimeout(timerId));
    interactionProbeTimerIdsRef.current = [];
    const registerProbeTimer = (callback: () => void, delayMs: number) => {
      const timerId = window.setTimeout(callback, delayMs);
      interactionProbeTimerIdsRef.current.push(timerId);
    };
    const selectionIds = nodes.slice(0, Math.min(3, nodes.length)).map((node) => node.id);
    if (selectionIds.length > 1) {
      interactionProbeRef.current.selection = {
        ids: selectionIds,
        startedAt: measureStart(),
      };
      registerProbeTimer(() => handleSelectionGroupChange(selectionIds), 40);
    }

    registerProbeTimer(() => {
      interactionProbeRef.current.inspect = {
        nodeId: targetNode.id,
        startedAt: measureStart(),
      };
      handleInspectNode(targetNode.id);
    }, 120);

    registerProbeTimer(() => {
      const latestNode = nodesRef.current.find((node) => node.id === targetNode.id) ?? targetNode;
      const basePosition = latestNode.position ?? fallbackDesignPosition(0);
      const nextPosition = {
        x: basePosition.x + 24,
        y: basePosition.y + 12,
      };
      interactionProbeRef.current.move = {
        nodeId: targetNode.id,
        position: nextPosition,
        startedAt: measureStart(),
      };
      handleMoveNode(targetNode.id, nextPosition);
    }, 220);

    if (canNavigateToSource(targetNode)) {
      registerProbeTimer(() => {
        interactionProbeRef.current.source = {
          nodeId: targetNode.id,
          startedAt: measureStart(),
        };
        traceLinkGraph("probe.app.sourceNavigation.start", {
          nodeId: targetNode.id,
        });
        handleRequestSourceNavigation(targetNode.id);
      }, 340);
    }
  }, [edges, nodes]);

  function handleRequestDiffReview(question: string) {
    bridgeCommands.submitAsyncBridgeCommand("差异问答", () => requestDiffReviewAsync(question, diffTargetItemIds), {
      applyRejectedRequestState: setDiffReviewRequestState,
      applySubmittedRequestState: (requestState) => {
        setDiffReviewResult(null);
        setDiffReviewRequestState(requestState);
      },
      successFeedback: {
        level: "INFO",
        message: diffTargetItemIds.length > 0
          ? "已提交焦点差异问答请求，正在生成解释和修订草稿。"
          : "已提交差异问答请求，正在生成解释和修订草稿。",
      },
    });
  }

  function handleRequestGraphBeautification(focusNodeId?: string) {
    const focusNode = focusNodeId ? nodes.find((node) => node.id === focusNodeId) ?? null : null;
    const explanationFocus = focusNode
      ? `请重点讲解节点“${focusNode.title}”在当前链路中的作用、上下游关系与关键分支。`
      : undefined;
    explanationLocalOverrideRef.current = false;
    pendingExplanationRequestModeRef.current = "fresh";
    bridgeCommands.submitAsyncBridgeCommand(
      "链路讲解",
      () => requestGraphBeautificationAsync({
        goal: "",
        preferredStyle: undefined,
        explanationFocus,
        granularity: selectedExplanationGranularity,
      }),
      {
        applyRejectedRequestState: setGraphBeautificationRequestState,
        applySubmittedRequestState: (requestState) => {
          setExplanationHistory([]);
          setCurrentExplanationSessionLabel(DEFAULT_EXPLANATION_SESSION_LABEL);
          setGraphBeautificationResult(null);
          setGraphBeautificationRequestState(requestState);
        },
        onAccepted: () => {
          setActiveWorkbenchTab("explanation");
        },
        successFeedback: {
          level: "INFO",
          message: focusNode ? `已请求讲解节点：${focusNode.title}` : "已请求生成链路讲解。",
        },
      },
    );
  }

  function handleChangeExplanationGranularity(granularity: StepGranularity) {
    setSelectedExplanationGranularity(granularity);
    explanationLocalOverrideRef.current = false;
    pendingExplanationRequestModeRef.current = "fresh";
    bridgeCommands.submitAsyncBridgeCommand(
      "链路讲解",
      () => requestGraphBeautificationAsync({
        goal: "",
        preferredStyle: undefined,
        explanationFocus: undefined,
        granularity,
      }),
      {
        applyRejectedRequestState: setGraphBeautificationRequestState,
        applySubmittedRequestState: (requestState) => {
          setExplanationHistory([]);
          setCurrentExplanationSessionLabel(DEFAULT_EXPLANATION_SESSION_LABEL);
          setGraphBeautificationResult(null);
          setGraphBeautificationRequestState(requestState);
        },
        onAccepted: () => {
          setSelectedExplanationStepId(null);
          setActiveWorkbenchTab("explanation");
        },
        successFeedback: {
          level: "INFO",
          message: `已切换讲解维度：${granularity === "BUSINESS" ? "业务级" : granularity === "METHOD_CALL" ? "方法调用级" : "代码语义级"}`,
        },
      },
    );
  }

  useEffect(() => {
    trackRequestFailure("audit", "链路审计", auditRequestState);
  }, [
    auditRequestState.phase,
    auditRequestState.statusMessage,
    auditRequestState.errorMessage,
    auditRequestState.detailMessage,
    auditRequestState.startedAtEpochMillis,
    auditRequestState.finishedAtEpochMillis,
  ]);

  useEffect(() => {
    trackRequestFailure("diff", "差异问答", diffReviewRequestState);
  }, [
    diffReviewRequestState.phase,
    diffReviewRequestState.statusMessage,
    diffReviewRequestState.errorMessage,
    diffReviewRequestState.detailMessage,
    diffReviewRequestState.startedAtEpochMillis,
    diffReviewRequestState.finishedAtEpochMillis,
  ]);

  useEffect(() => {
    trackRequestFailure("plan", "实现计划", generationPlanRequestState);
  }, [
    generationPlanRequestState.phase,
    generationPlanRequestState.statusMessage,
    generationPlanRequestState.errorMessage,
    generationPlanRequestState.detailMessage,
    generationPlanRequestState.startedAtEpochMillis,
    generationPlanRequestState.finishedAtEpochMillis,
  ]);

  useEffect(() => {
    trackRequestFailure("beautification", "链路讲解", graphBeautificationRequestState);
  }, [
    graphBeautificationRequestState.phase,
    graphBeautificationRequestState.statusMessage,
    graphBeautificationRequestState.errorMessage,
    graphBeautificationRequestState.detailMessage,
    graphBeautificationRequestState.startedAtEpochMillis,
    graphBeautificationRequestState.finishedAtEpochMillis,
  ]);

  useEffect(() => {
    trackRequestFailure("drafts", "代码草稿", codeDraftRequestState);
  }, [
    codeDraftRequestState.phase,
    codeDraftRequestState.statusMessage,
    codeDraftRequestState.errorMessage,
    codeDraftRequestState.detailMessage,
    codeDraftRequestState.startedAtEpochMillis,
    codeDraftRequestState.finishedAtEpochMillis,
  ]);

  useEffect(() => {
    const firstStepId = graphBeautificationResult?.steps?.[0]?.stepId ?? null;
    setSelectedExplanationStepId((current) => {
      if (!graphBeautificationResult?.steps?.length) {
        return null;
      }
      return graphBeautificationResult.steps.some((step) => step.stepId === current) ? current : firstStepId;
    });
    setHoveredExplanationStepId((current) =>
      graphBeautificationResult?.steps?.some((step) => step.stepId === current) ? current : null,
    );
  }, [graphBeautificationResult]);

  useEffect(() => {
    if (!graphBeautificationResult?.granularity) {
      return;
    }
    setSelectedExplanationGranularity(graphBeautificationResult.granularity);
  }, [graphBeautificationResult?.granularity]);

  useEffect(() => {
    const firstChangeId = auditResult?.candidateChanges?.[0]?.changeId ?? null;
    setSelectedAuditChangeId((current) => {
      if (!auditResult?.candidateChanges?.length) {
        return null;
      }
      return auditResult.candidateChanges.some((change) => change.changeId === current) ? current : firstChangeId;
    });
  }, [auditResult]);

  useEffect(() => {
    const firstLeadId = auditResult?.investigationLeads?.[0]?.leadId ?? null;
    setSelectedAuditLeadId((current) => {
      if (!auditResult?.investigationLeads?.length) {
        return null;
      }
      return auditResult.investigationLeads.some((lead) => lead.leadId === current) ? current : firstLeadId;
    });
  }, [auditResult]);

  const explanationState: ExplanationWorkbenchState = {
    result: graphBeautificationResult,
    requestState: graphBeautificationRequestState,
    selectedStepId: selectedExplanationStepId,
    granularity: selectedExplanationGranularity,
    historyDepth: explanationHistory.length,
    canReturnToPrevious: explanationHistory.length > 0,
    historyTrail: [
      ...explanationHistory.map((entry) => entry.sessionLabel),
      currentExplanationSessionLabel,
    ],
    currentSessionLabel: currentExplanationSessionLabel,
    previousSessionLabel: explanationHistory[explanationHistory.length - 1]?.sessionLabel ?? null,
  };

  const auditState: AuditWorkbenchState = {
    result: auditResult,
    requestState: auditRequestState,
    selectedChangeId: selectedAuditChangeId,
    selectedLeadId: selectedAuditLeadId,
    questionDraft: auditQuestionDraft,
    scopeLabel: buildAuditScopeLabel(auditTargetNodeIds, auditTargetTitle),
  };

  const draftState: DraftWorkbenchViewState = {
    draftState: draftWorkbenchState,
    compareMode: draftCompareMode,
    selectedEntryId: selectedDraftEntryId,
  };
  const selectedExplanationStep = graphBeautificationResult?.steps.find((step) => step.stepId === selectedExplanationStepId)
    ?? graphBeautificationResult?.steps?.[0]
    ?? null;
  const hoveredExplanationStep = graphBeautificationResult?.steps.find((step) => step.stepId === hoveredExplanationStepId)
    ?? null;
  const explanationFocusNodeId = activeWorkbenchTab === "explanation"
    ? hoveredExplanationStep?.primaryNodeId ?? selectedExplanationStep?.primaryNodeId ?? null
    : null;
  const draftChangedNodeIds = useMemo(
    () => Array.from(new Set(draftWorkbenchState.draftChanges.flatMap((entry) => entry.targetNodeIds))),
    [draftWorkbenchState.draftChanges],
  );

  function handleAddExplanationNoteToDraft(stepId: string) {
    const step = graphBeautificationResult?.steps.find((item) => item.stepId === stepId);
    if (!step) {
      return;
    }
    const targetNodeId = step.primaryNodeId
      ?? step.evidence.flatMap((finding) => finding.references).find((reference) => reference.nodeId)?.nodeId
      ?? null;
    const nextEntry: DraftWorkbenchEntry = {
      entryId: `draft-note:${step.stepId}`,
      kind: "NOTE",
      title: step.title,
      sourceChangeId: null,
      targetStepIds: [step.stepId],
      targetNodeIds: targetNodeId ? [targetNodeId] : [],
      beforeState: null,
      afterState: step.description,
      reason: "从讲解步骤加入草稿说明项。",
      impactSummary: "",
      claimType: "EXPLANATION_NOTE",
      evidence: step.evidence,
    };
    setDraftWorkbenchState((current) => ({
      ...current,
      draftNotes: current.draftNotes.some((entry) => entry.entryId === nextEntry.entryId)
        ? current.draftNotes
        : current.draftNotes.concat(nextEntry),
    }));
    setSelectedDraftEntryId(nextEntry.entryId);
    setActiveWorkbenchTab("draft");
  }

  function resolveDraftNodeTitle(nodeId: string) {
    return nodes.find((node) => node.id === nodeId)?.title ?? nodeId;
  }

  function handleSelectDraftEntry(entryId: string) {
    setSelectedDraftEntryId(entryId);
  }

  function requestViewportFocus(nodeId: string) {
    nextFocusRequestNonceRef.current += 1;
    setFocusNodeRequest({
      nodeId,
      nonce: nextFocusRequestNonceRef.current,
    });
  }

  function handleLocateDraftChangeNode(entryId: string) {
    const change = draftWorkbenchState.draftChanges.find((entry) => entry.entryId === entryId);
    if (!change) {
      return;
    }
    const targetNodeId = change.targetNodeIds[0] ?? null;
    if (!targetNodeId) {
      setOperationFeedback({
        level: "WARNING",
        message: "这条草稿变更当前没有可定位的图节点。",
      });
      return;
    }
    setSelectedDraftEntryId(entryId);
    selectExplanationTargetNode(targetNodeId, { focusViewport: true });
    setOperationFeedback({
      level: "INFO",
      message: `已定位到草稿变更对应节点：${resolveDraftNodeTitle(targetNodeId)}`,
    });
  }

  function handleOpenDraftNote(entryId: string) {
    const note = draftWorkbenchState.draftNotes.find((entry) => entry.entryId === entryId);
    if (!note) {
      return;
    }
    const targetStepId = note.targetStepIds[0] ?? null;
    if (targetStepId && graphBeautificationResult?.steps.some((step) => step.stepId === targetStepId)) {
      setActiveWorkbenchTab("explanation");
      handleSelectExplanationStep(targetStepId);
      return;
    }
    const targetNodeId = note.targetNodeIds[0] ?? null;
    if (targetNodeId) {
      setActiveWorkbenchTab("explanation");
      selectExplanationTargetNode(targetNodeId, { focusViewport: true });
      setOperationFeedback({
        level: "INFO",
        message: `已根据草稿说明定位到图节点：${targetNodeId}`,
      });
      return;
    }
    setOperationFeedback({
      level: "WARNING",
      message: "这条草稿说明当前没有可回到的讲解步骤或图节点。",
    });
  }

  function handleLocateDraftNoteNode(entryId: string) {
    const note = draftWorkbenchState.draftNotes.find((entry) => entry.entryId === entryId);
    if (!note) {
      return;
    }
    const targetNodeId = note.targetNodeIds[0] ?? null;
    if (!targetNodeId) {
      setOperationFeedback({
        level: "WARNING",
        message: "这条草稿说明当前没有可定位的图节点。",
      });
      return;
    }
    selectExplanationTargetNode(targetNodeId, { focusViewport: true });
    const targetNode = nodes.find((node) => node.id === targetNodeId) ?? null;
    setOperationFeedback({
      level: "INFO",
      message: `已根据草稿说明定位到图节点：${targetNode?.title ?? targetNodeId}`,
    });
  }

  function handleHoverExplanationStep(stepId: string) {
    setHoveredExplanationStepId(stepId);
  }

  function handleLeaveExplanationStep() {
    setHoveredExplanationStepId(null);
  }

  function handleFollowUpExplanationStep(stepId: string, customQuestion?: string) {
    const step = graphBeautificationResult?.steps.find((item) => item.stepId === stepId);
    if (!step) {
      return;
    }
    const followUpQuestion = customQuestion?.trim()
      || step.followUpQuestions[0]
      || "请继续解释这一步的关键输入、条件和输出。";
    const nextSessionLabel = `围绕 ${step.title} 继续讲解`;
    explanationLocalOverrideRef.current = false;
    pendingExplanationRequestModeRef.current = "follow_up";
    bridgeCommands.submitAsyncBridgeCommand(
      "链路讲解追问",
      () => requestGraphBeautificationAsync({
        goal: "",
        preferredStyle: undefined,
        explanationFocus: undefined,
        granularity: selectedExplanationGranularity,
        followUp: {
          stepId: step.stepId,
          stepTitle: step.title,
          question: followUpQuestion,
        },
      }),
      {
        applyRejectedRequestState: setGraphBeautificationRequestState,
        applySubmittedRequestState: (requestState) => {
          if (graphBeautificationResult) {
            setExplanationHistory((current) => current.concat({
              result: graphBeautificationResult,
              requestState: graphBeautificationRequestState,
              selectedStepId: selectedExplanationStepId,
              granularity: selectedExplanationGranularity,
              sessionLabel: currentExplanationSessionLabel,
            }));
          }
          setCurrentExplanationSessionLabel(nextSessionLabel);
          setGraphBeautificationResult(null);
          setGraphBeautificationRequestState(requestState);
        },
        onAccepted: () => {
          setSelectedExplanationStepId(step.stepId);
          setActiveWorkbenchTab("explanation");
        },
        successFeedback: {
          level: "INFO",
          message: `已围绕步骤“${step.title}”继续请求讲解。`,
        },
      },
    );
  }

  function handleReturnToPreviousExplanation() {
    handleOpenExplanationHistory(explanationHistory.length - 1);
  }

  function handleOpenExplanationHistory(historyIndex: number) {
    setExplanationHistory((current) => {
      const snapshot = current[historyIndex];
      if (!snapshot) {
        return current;
      }
      explanationLocalOverrideRef.current = true;
      pendingExplanationRequestModeRef.current = null;
      setGraphBeautificationResult(snapshot.result);
      setGraphBeautificationRequestState(snapshot.requestState);
      setSelectedExplanationStepId(snapshot.selectedStepId);
      setSelectedExplanationGranularity(snapshot.granularity);
      setCurrentExplanationSessionLabel(snapshot.sessionLabel);
      setHoveredExplanationStepId(null);
      return current.slice(0, historyIndex);
    });
  }

  function handleSelectExplanationStep(stepId: string) {
    setSelectedExplanationStepId(stepId);
    const targetNodeId = resolveExplanationStepTargetNodeId(stepId);
    if (!targetNodeId) {
      return;
    }
    selectExplanationTargetNode(targetNodeId);
  }

  function resolveExplanationStepTargetNodeId(stepId: string) {
    const step = graphBeautificationResult?.steps.find((item) => item.stepId === stepId);
    return step?.primaryNodeId
      ?? step?.evidence.flatMap((finding) => finding.references).find((reference) => reference.nodeId)?.nodeId
      ?? null;
  }

  function handleLocateExplanationStepNode(stepId: string) {
    setSelectedExplanationStepId(stepId);
    const targetNodeId = resolveExplanationStepTargetNodeId(stepId);
    if (!targetNodeId) {
      setOperationFeedback({
        level: "WARNING",
        message: "当前步骤没有可定位的图节点。",
      });
      return;
    }
    selectExplanationTargetNode(targetNodeId, { focusViewport: true });
    const targetNode = nodes.find((node) => node.id === targetNodeId) ?? null;
    setOperationFeedback({
      level: "INFO",
      message: `已定位到图中节点：${targetNode?.title ?? targetNodeId}`,
    });
  }

  function handleInspectExplanationStepNode(stepId: string) {
    setSelectedExplanationStepId(stepId);
    const targetNodeId = resolveExplanationStepTargetNodeId(stepId);
    if (!targetNodeId) {
      setOperationFeedback({
        level: "WARNING",
        message: "当前步骤没有可编辑的图节点。",
      });
      return;
    }
    handleInspectNode(targetNodeId);
  }

  function selectExplanationTargetNode(nodeId: string, options?: { focusViewport?: boolean }) {
    setSelectedNodeId((current) => (current === nodeId ? current : nodeId));
    setSelectionGroupNodeIds((current) => (current.length === 0 ? current : []));
    if (options?.focusViewport) {
      requestViewportFocus(nodeId);
    }
    syncSelectedNodeToBridge(nodeId);
  }

  function handleDrillDownExplanationStep(stepId: string) {
    const step = graphBeautificationResult?.steps.find((item) => item.stepId === stepId);
    const targetNodeId = step?.downstreamTargets[0] ?? null;
    if (!targetNodeId) {
      setOperationFeedback({
        level: "WARNING",
        message: "当前步骤没有可继续下钻的被调方法。",
      });
      return;
    }
    const targetNode = nodes.find((node) => node.id === targetNodeId) ?? null;
    if (!targetNode) {
      const downstreamOverflowNode = nodes.find(
        (node) => node.metadata?.["linkGraph.overflow.direction"] === "DOWNSTREAM",
      );
      if (!downstreamOverflowNode) {
        setOperationFeedback({
          level: "WARNING",
          message: "当前图中还没有展示这个被调方法，请先扩展链路范围。",
        });
        return;
      }
      pendingExplanationDrillTargetRef.current = targetNodeId;
      handleExpandOverflowNode(downstreamOverflowNode.id);
      setOperationFeedback({
        level: "INFO",
        message: "当前图中未展示该被调方法，已尝试自动展开下游链路。",
      });
      return;
    }
    setSelectedNodeId(targetNodeId);
    requestViewportFocus(targetNodeId);
    setDetailNodeId(targetNodeId);
    setOperationFeedback({
      level: "INFO",
      message: `已定位到被调方法：${targetNode.title}`,
    });
  }

  function handleRevealExplanationReference(reference: ResultEvidenceReference) {
    if (reference.nodeId) {
      setDetailNodeId(reference.nodeId);
      return;
    }
    setOperationFeedback({
      level: "WARNING",
      message: "当前引用没有可直接跳转的节点标识。",
    });
  }

  function activateAuditSection(sectionId: WorkbenchSectionId) {
    if (!AUDIT_WORKBENCH_SECTION_IDS.includes(sectionId)) {
      return;
    }
    AUDIT_WORKBENCH_SECTION_IDS.forEach((auditSectionId) => {
      updateWorkbenchSectionPreference(auditSectionId, auditSectionId === sectionId);
    });
  }

  function handleSelectAuditChange(changeId: string) {
    setSelectedAuditChangeId(changeId);
    const change = auditResult?.candidateChanges.find((item) => item.changeId === changeId) ?? null;
    const targetNodeId = change ? resolveEvidenceTargetNodeId(change.targetNodeIds, change.evidence) : null;
    if (!targetNodeId || !nodes.some((node) => node.id === targetNodeId)) {
      return;
    }
    selectExplanationTargetNode(targetNodeId, { focusViewport: true });
  }

  function handleSelectAuditLead(leadId: string) {
    setSelectedAuditLeadId(leadId);
    const lead = auditResult?.investigationLeads.find((item) => item.leadId === leadId) ?? null;
    const targetNodeId = lead ? resolveEvidenceTargetNodeId(lead.targetNodeIds, lead.evidence) : null;
    if (!targetNodeId || !nodes.some((node) => node.id === targetNodeId)) {
      return;
    }
    selectExplanationTargetNode(targetNodeId, { focusViewport: true });
  }

  function handleInvestigateAuditLead(leadId: string) {
    const lead = auditResult?.investigationLeads.find((item) => item.leadId === leadId) ?? null;
    if (!lead) {
      return;
    }
    setSelectedAuditLeadId(leadId);
    setAuditSourceLeadId(leadId);
    const nextQuestion = lead.recommendedQuestion.trim()
      || `请继续取证：核对“${lead.title}”对应的直接源码证据。`;
    setAuditQuestionDraft(nextQuestion);
    setAuditTargetNodeIds(lead.targetNodeIds);
    const targetNodeId = resolveEvidenceTargetNodeId(lead.targetNodeIds, lead.evidence);
    if (targetNodeId && nodes.some((node) => node.id === targetNodeId)) {
      selectExplanationTargetNode(targetNodeId, { focusViewport: true });
    }
    activateAuditSection("audit.composer");
    setActiveWorkbenchTab("audit");
    bridgeCommands.submitAsyncBridgeCommand("审计", () => requestAuditAsync(nextQuestion, lead.targetNodeIds, leadId), {
      applyRejectedRequestState: setAuditRequestState,
      applySubmittedRequestState: (requestState) => {
        setAuditRequestState(requestState);
        setAuditResult(null);
      },
      successFeedback: {
        level: "INFO",
        message: `已围绕风险线索“${lead.title}”自动发起继续取证。`,
      },
    });
  }

  function handleConfirmCandidateChange(changeId: string) {
    const candidate = auditResult?.candidateChanges.find((item) => item.changeId === changeId) ?? null;
    if (candidate && !candidateCanConfirm(candidate)) {
      setOperationFeedback({
        level: "WARNING",
        message: "当前候选变更缺少直接证据，不能直接确认进草稿。",
      });
      return;
    }
    bridgeCommands.runBridgeCommand("确认候选变更", () => confirmAuditCandidateChange(changeId), {
      onAccepted: () => {
        if (candidate) {
          const nextEntry = toDraftWorkbenchEntry(candidate);
          setDraftWorkbenchState((current) => ({
            ...current,
            draftChanges: current.draftChanges
              .filter((entry) => entry.sourceChangeId !== candidate.changeId)
              .concat(nextEntry),
          }));
          setSelectedDraftEntryId(nextEntry.entryId);
          setAuditResult((current) => updateGraphPatchResultCandidateStatus(current, changeId, "CONFIRMED"));
          if (nextEntry.targetNodeIds[0]) {
            selectExplanationTargetNode(nextEntry.targetNodeIds[0]);
          }
        }
        setActiveWorkbenchTab("draft");
      },
      successFeedback: {
        level: "SUCCESS",
        message: "已确认候选变更并写入草稿层。",
      },
    });
  }

  function handleUnconfirmDraftChange(entryId: string) {
    const entry = draftWorkbenchState.draftChanges.find((item) => item.entryId === entryId) ?? null;
    const changeId = entry?.sourceChangeId ?? null;
    if (!entry || !changeId) {
      return;
    }
    bridgeCommands.runBridgeCommand("取消确认候选变更", () => unconfirmAuditCandidateChange(changeId), {
      onAccepted: () => {
        setDraftWorkbenchState((current) => ({
          ...current,
          draftChanges: current.draftChanges.filter((item) => item.entryId !== entryId),
        }));
        setAuditResult((current) => updateGraphPatchResultCandidateStatus(current, changeId, "PENDING_CONFIRMATION"));
      },
      successFeedback: {
        level: "INFO",
        message: "已取消确认该候选变更，并从草稿层移除。",
      },
    });
  }

  function handleWorkbenchSectionPreferenceChange(sectionId: WorkbenchSectionId, expanded: boolean) {
    updateWorkbenchSectionPreference(sectionId, expanded);
  }

  function handleWriteSingleCodeDraft(draftId: string) {
    bridgeCommands.runBridgeCommand("写入单个代码草稿", () => applySingleCodeDraft(draftId));
  }

  function renderWorkbenchPanel() {
    switch (activeWorkbenchTab) {
      case "plan":
        return (
          <GenerationPlanPanel
            plan={generationPlan}
            requestState={generationPlanRequestState}
            hasConfirmedDraftChanges={hasConfirmedDraftChanges}
            resolveArtifactText={resolveArtifactText}
            onRequestArtifact={handleRequestArtifact}
            onOpenDraftWorkbench={() => setActiveWorkbenchTab("draft")}
            onRequestGeneratePlan={handleRequestGenerationPlan}
          />
        );
      case "code":
        return (
          <CodeDraftPanel
            drafts={generatedCodeDrafts}
            warnings={generatedCodeDraftWarnings}
            requestState={codeDraftRequestState}
            source={generatedCodeDraftSource}
            promptPreview={generatedCodeDraftPromptPreview}
            promptPreviewArtifactId={generatedCodeDraftPromptPreviewArtifactId}
            resolveArtifactText={resolveArtifactText}
            onRequestArtifact={handleRequestArtifact}
            writeReport={generatedCodeDraftWriteReport}
            hasPlan={generationPlan != null}
            hasConfirmedDraftChanges={hasConfirmedDraftChanges}
            onOpenDraftWorkbench={() => setActiveWorkbenchTab("draft")}
            onRequestPlan={handleRequestGenerationPlan}
            onRequestDrafts={handleRequestCodeDrafts}
            onWriteDrafts={workbenchCommands.handleWriteDrafts}
            onWriteSingleDraft={handleWriteSingleCodeDraft}
            onOpenDraft={workbenchCommands.handleOpenDraft}
          />
        );
      case "audit":
        return (
          <AuditTab
            state={auditState}
            onQuestionDraftChange={setAuditQuestionDraft}
            onSubmitQuestion={() => handleRequestAudit(auditQuestionDraft)}
            onSelectChange={handleSelectAuditChange}
            onConfirmChange={handleConfirmCandidateChange}
            onSelectLead={handleSelectAuditLead}
            onInvestigateLead={handleInvestigateAuditLead}
            sectionPreferences={workbenchSectionPreferences}
            onSectionPreferenceChange={handleWorkbenchSectionPreferenceChange}
          />
        );
      case "draft":
        return (
          <DraftTab
            state={draftState}
            onToggleCompare={() => setDraftCompareMode((current) => current === "after" ? "compare" : "after")}
            onSelectEntry={handleSelectDraftEntry}
            onLocateChangeNode={handleLocateDraftChangeNode}
            onUnconfirmChange={handleUnconfirmDraftChange}
            onOpenNote={handleOpenDraftNote}
            onLocateNoteNode={handleLocateDraftNoteNode}
            resolveNodeTitle={resolveDraftNodeTitle}
            sectionPreferences={workbenchSectionPreferences}
            onSectionPreferenceChange={handleWorkbenchSectionPreferenceChange}
          />
        );
      case "explanation":
      default:
        return (
          <ExplanationTab
            state={explanationState}
            onSelectStep={handleSelectExplanationStep}
            onLocateStepNode={handleLocateExplanationStepNode}
            onInspectStepNode={handleInspectExplanationStepNode}
            onGranularityChange={handleChangeExplanationGranularity}
            onHoverStep={handleHoverExplanationStep}
            onLeaveStep={handleLeaveExplanationStep}
            onAddToDraft={handleAddExplanationNoteToDraft}
            onDrillDown={handleDrillDownExplanationStep}
            onFollowUp={handleFollowUpExplanationStep}
            onRevealReference={handleRevealExplanationReference}
            onReturnToPrevious={handleReturnToPreviousExplanation}
            onOpenHistory={handleOpenExplanationHistory}
            sectionPreferences={workbenchSectionPreferences}
            onSectionPreferenceChange={handleWorkbenchSectionPreferenceChange}
          />
        );
    }
  }

  const stageProps = {
    selectedNodeId,
    focusNodeRequest,
    explanationFocusNodeId,
    draftChangedNodeIds,
    selectedGroupNodeIds: selectionGroupNodeIds,
    hiddenNodeIds,
    collapsedNodeIds,
    collapsedDescendantCountByNodeId: collapsedSummary.descendantCountByNodeId,
    experiments: graphSurfaceExperiments,
    onAddNode: handleAddNode,
    onSelectNode: handleSelectNode,
    onSelectionGroupChange: handleSelectionGroupChange,
    onInspectNode: handleInspectNode,
    onDeleteNode: handleDeleteNode,
    onDeleteNodeSubtree: handleDeleteNodeSubtree,
    onCreateEdge: handleCreateEdge,
    onDeleteEdge: handleDeleteEdge,
    onInsertNodeIntoEdge: handleInsertNodeIntoEdge,
    onMoveNode: handleMoveNode,
    onMoveNodes: handleMoveNodes,
    onFormatLayout: handleFormatLayout,
    onRequestBeautification: handleRequestGraphBeautification,
    onRequestSourceNavigation: handleRequestSourceNavigation,
    onRequestAudit: handleRequestScopedAudit,
    onToggleCollapseNode: handleToggleCollapseNode,
    onOpenAudit: handleOpenAudit,
    onImportMermaid: handleOpenImportMermaid,
    onExpandOverflowNode: handleExpandOverflowNode,
  };

  const stage = analysisDisplayMode === "FLOWCHART"
    ? (
      <FlowchartView
        {...stageProps}
        view={flowchartView}
      />
    )
    : analysisDisplayMode === "RESOURCE_RELATION_VIEW"
      ? (
        <ResourceRelationView
          {...stageProps}
          view={resourceRelationView}
        />
      )
      : (
        <FactGraphView
          {...stageProps}
          view={factGraphView}
        />
      );

  return (
    <GraphWorkbench
      toolbar={(
        <WorkbenchToolbar
        analysisDisplayMode={analysisDisplayMode}
        operationFeedback={toolbarFeedback}
        onRequestAnalysisDisplayMode={workbenchCommands.handleRequestAnalysisDisplayMode}
        onImportMermaid={handleOpenImportMermaid}
        onExportMermaid={workbenchCommands.handleExportMermaid}
        onShowDiff={workbenchCommands.handleShowDiffMode}
        onRequestSync={workbenchCommands.handleRequestSyncPreview}
        onRequestGenerationPlan={handleRequestGenerationPlan}
        onRequestGraphBeautification={() => {
          handleRequestGraphBeautification();
        }}
        onRequestCodeDrafts={handleRequestCodeDrafts}
        onOpenSettings={workbenchCommands.handleOpenSettings}
        />
      )}
      legend={(
        <Legend
          analysisDisplayMode={analysisDisplayMode}
          hasExplanationFocus={explanationFocusNodeId != null}
          hasDraftChanges={draftChangedNodeIds.length > 0}
        />
      )}
      dialogs={(
        <>
          <MermaidImportDialog
        open={isImportDialogOpen}
        value={mermaidDraft}
        onChange={setMermaidDraft}
        onCancel={() => setImportDialogOpen(false)}
        onConfirm={handleConfirmImportMermaid}
          />

          <AsyncRequestFailureDialog
        open={requestFailureNotice !== null}
        title={requestFailureNotice?.title ?? ""}
        message={requestFailureNotice?.message ?? ""}
        detailMessage={requestFailureNotice?.detailMessage ?? null}
        onClose={() => setRequestFailureNotice(null)}
          />
        </>
      )}
      stage={stage}
      workbench={(
        <section className="workbench-shell">
          <div className="workbench-tab-nav" role="tablist" aria-label="工作台切换">
            {WORKBENCH_TABS.map((tab) => (
              <button
                key={tab.id}
                id={`workbench-tab-${tab.id}`}
                type="button"
                role="tab"
                aria-selected={activeWorkbenchTab === tab.id}
                aria-controls={`workbench-panel-${tab.id}`}
                className={activeWorkbenchTab === tab.id ? "workbench-tab-button active" : "workbench-tab-button"}
                onClick={() => setActiveWorkbenchTab(tab.id)}
              >
                {tab.label}
              </button>
            ))}
          </div>
          <div
            id={`workbench-panel-${activeWorkbenchTab}`}
            role="tabpanel"
            aria-labelledby={`workbench-tab-${activeWorkbenchTab}`}
            className="workbench-panel-body"
          >
            {renderWorkbenchPanel()}
          </div>
        </section>
      )}
      propertyDrawer={(
        <WorkbenchPropertyDrawer
        selectedNode={detailNode}
        onUpdateNode={handleUpdateNode}
        onDeleteNode={handleDeleteNode}
        onDeleteNodeSubtree={handleDeleteNodeSubtree}
        onRequestSourceNavigation={handleRequestSourceNavigation}
        onClose={() => setDetailNodeId(null)}
        />
      )}
    />
  );
}

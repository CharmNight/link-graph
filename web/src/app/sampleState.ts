import { readBootstrapState } from "./api";
import { resolveFlowchartKind } from "./flowchartKind";
import type {
  AnalysisDisplayMode,
  ArchitectureGraphViewDocument,
  AsyncRequestState,
  ClassDiagramViewDocument,
  FactGraphViewDocument,
  FlowchartViewDocument,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphLayoutState,
  LinkGraphNode,
  LinkGraphSceneId,
  LinkGraphSceneState,
  QaRequestRecoveryState,
  ResourceRelationViewDocument,
  ReviewGraphViewDocument,
  SourceNavigationState,
} from "./types";

export const DEFAULT_ANALYSIS_DISPLAY_MODE: AnalysisDisplayMode = "FLOWCHART";
export const DEFAULT_SCENE_ID: LinkGraphSceneId = "WORKSPACE_FLOWCHART";

export const IDLE_REQUEST_STATE: AsyncRequestState = {
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

export const IDLE_SOURCE_NAVIGATION_STATE: SourceNavigationState = {
  phase: "IDLE",
  nodeId: null,
  result: null,
  targetPath: null,
  line: null,
  column: null,
  errorMessage: null,
};

export const EMPTY_QA_REQUEST_RECOVERY_STATE: QaRequestRecoveryState = {
  lastSubmittedRequest: null,
  lastFailedRequest: null,
};

const EMPTY_DOCUMENT: LinkGraphDocument = {
  nodes: [],
  edges: [],
};

const EMPTY_LAYOUT_STATE: LinkGraphLayoutState = {
  positions: {},
};

function sampleSyncPreview() {
  return [
    {
      id: "create-order-draft",
      title: "新增 DTO",
      description: "生成 OrderDraftDto.java",
      risk: "LOW" as const,
    },
    {
      id: "wire-place-draft",
      title: "补齐服务调用",
      description: "把 controller 流程接到 placeDraft 服务",
      risk: "MEDIUM" as const,
    },
  ];
}

function sampleMermaidIssues() {
  return [
    {
      category: "SEMANTIC" as const,
      code: "missing-method-signature",
      message: "方法节点 'design:submit-order' 缺少 signature 元数据。",
      line: 3,
      nodeId: "design:submit-order",
    },
  ];
}

function createSceneState(
  selectedNodeId: string | null = null,
  anchorNodeId: string | null = selectedNodeId,
  layoutState: LinkGraphLayoutState = EMPTY_LAYOUT_STATE,
): LinkGraphSceneState {
  return {
    selectedNodeId,
    anchorNodeId,
    layoutState,
    layoutRevision: 0,
    collapsedNodeIds: [],
  };
}

function createDefaultSceneStates(
  selectedNodeId: string | null = null,
  layoutState: LinkGraphLayoutState = EMPTY_LAYOUT_STATE,
): Record<LinkGraphSceneId, LinkGraphSceneState> {
  return {
    WORKSPACE_FACT: createSceneState(selectedNodeId, selectedNodeId, layoutState),
    WORKSPACE_FLOWCHART: createSceneState(selectedNodeId, selectedNodeId, layoutState),
    WORKSPACE_RESOURCE_RELATION: createSceneState(selectedNodeId, selectedNodeId, layoutState),
    WORKSPACE_ARCHITECTURE_GRAPH: createSceneState(selectedNodeId, selectedNodeId, layoutState),
    WORKSPACE_CLASS_DIAGRAM: createSceneState(selectedNodeId, selectedNodeId, layoutState),
    WORKSPACE_REVIEW_GRAPH: createSceneState(selectedNodeId, selectedNodeId, layoutState),
    DIFF: createSceneState(null, null, EMPTY_LAYOUT_STATE),
  };
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
  const hiddenNodeCount = Math.max(0, fullGraph.nodes.length - visibleGraph.nodes.length);
  const hiddenEdgeCount = Math.max(0, fullGraph.edges.length - visibleGraph.edges.length);
  const incompleteNodeCount = visibleGraph.nodes.filter((node) => node.metadata?.["flow.incomplete"] === "true").length;
  const incompleteEdgeCount = visibleGraph.edges.filter((edge) => edge.metadata?.["flow.incomplete"] === "true").length;
  const syntheticEdgeCount = visibleGraph.edges.filter((edge) => edge.metadata?.["flow.synthetic"] === "true").length;
  const syntheticEntryEdgeCount = visibleGraph.edges.filter(
    (edge) => edge.metadata?.["flow.synthetic"] === "true" && edge.metadata?.["flow.provenance"] === "SYNTHETIC_PROJECTION",
  ).length;
  return {
    nodeCount: visibleGraph.nodes.length,
    branchCount: visibleGraph.nodes.filter((node) => resolveFlowchartKind(node) === "DECISION").length,
    exceptionPathCount: visibleGraph.edges.filter((edge) => edge.label?.trim().toUpperCase() === "EXCEPTION").length,
    fullNodeCount: fullGraph.nodes.length,
    fullEdgeCount: fullGraph.edges.length,
    hiddenNodeCount,
    hiddenEdgeCount,
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

function deriveArchitectureGraphSummary(visibleGraph: LinkGraphDocument) {
  return {
    moduleCount: visibleGraph.nodes.filter((node) => node.type === "MODULE").length,
    packageCount: visibleGraph.nodes.filter((node) => node.type === "PACKAGE").length,
    serviceCount: visibleGraph.nodes.filter((node) => node.type === "SERVICE").length,
    resourceCount: visibleGraph.nodes.filter((node) => node.type === "RESOURCE").length,
    layerCount: visibleGraph.nodes.filter((node) => node.type === "LAYER").length,
    relationCount: visibleGraph.edges.length,
    classCount: visibleGraph.nodes.filter((node) => node.metadata?.["architecture.classCount"] != null)
      .reduce((sum, node) => sum + (Number(node.metadata?.["architecture.classCount"]) || 0), 0),
    truncated: visibleGraph.truncated === true,
  };
}

function deriveClassDiagramSummary(visibleGraph: LinkGraphDocument) {
  return {
    classCount: visibleGraph.nodes.filter((node) => node.type === "CLASS").length,
    fieldCount: visibleGraph.nodes
      .map((node) => Number(node.metadata?.["uml.field.count"] ?? "0"))
      .filter(Number.isFinite)
      .reduce((sum, count) => sum + count, 0),
    interfaceCount: visibleGraph.nodes.filter((node) => node.type === "INTERFACE").length,
    enumCount: visibleGraph.nodes.filter((node) => node.type === "ENUM").length,
    annotationCount: visibleGraph.nodes.filter((node) => node.type === "ANNOTATION").length,
    recordCount: visibleGraph.nodes.filter((node) => node.type === "RECORD").length,
    objectCount: visibleGraph.nodes.filter((node) => node.type === "OBJECT").length,
    relationCount: visibleGraph.edges.length,
    spiProviderCount: 0,
    reflectionRelationCount: 0,
  };
}

function packageFromSignature(signature?: string | null): string | null {
  if (!signature) {
    return null;
  }
  const owner = signature.split("(")[0] ?? signature;
  const index = owner.lastIndexOf(".");
  return index > 0 ? owner.slice(0, index) : null;
}

function deriveReviewGraphSummary(visibleGraph: LinkGraphDocument) {
  return {
    changedSymbolCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "CHANGED").length,
    upstreamCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "UPSTREAM").length,
    downstreamCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "DOWNSTREAM").length,
    relatedTestCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "RELATED_TEST").length,
    affectedPackageCount: Array.from(new Set(visibleGraph.nodes
      .map((node) => node.metadata?.["architecture.package"] ?? packageFromSignature(node.signature))
      .filter(Boolean))).length,
    affectedModuleCount: Array.from(new Set(visibleGraph.nodes
      .map((node) => node.metadata?.["architecture.module"])
      .filter(Boolean))).length,
    evidenceRefCount: visibleGraph.edges.filter((edge) => edge.metadata?.["review.edgeRole"] === "RELATION").length,
    truncated: Boolean(visibleGraph.truncated),
    hiddenNodeCount: 0,
    hiddenEdgeCount: 0,
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
      "resource.lane": "DATA",
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

const INITIAL_GRAPH: LinkGraphDocument = {
  nodes: INITIAL_NODES,
  edges: INITIAL_EDGES,
};

const INITIAL_SELECTED_NODE_ID = INITIAL_NODES[0]?.id ?? null;
const INITIAL_LAYOUT_STATE: LinkGraphLayoutState = {
  positions: Object.fromEntries(
    INITIAL_NODES
      .filter((node) => node.position)
      .map((node) => [node.id, node.position!]),
  ),
};

const EMPTY_PROJECTION_INDEX = {
  nodeMappings: {},
  edgeMappings: {},
};

function buildFactGraphView(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): FactGraphViewDocument {
  return {
    visibleGraph,
    fullGraph,
    anchorNodeId,
    projectionIndex: EMPTY_PROJECTION_INDEX,
    summary: deriveFactGraphSummary(visibleGraph, fullGraph, anchorNodeId),
  };
}

function buildFlowchartView(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): FlowchartViewDocument {
  return {
    visibleGraph,
    fullGraph,
    anchorNodeId,
    projectionIndex: EMPTY_PROJECTION_INDEX,
    summary: deriveFlowchartSummary(visibleGraph, fullGraph),
  };
}

function buildResourceRelationView(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): ResourceRelationViewDocument {
  return {
    visibleGraph,
    fullGraph,
    anchorNodeId,
    projectionIndex: EMPTY_PROJECTION_INDEX,
    summary: deriveResourceRelationSummary(visibleGraph),
  };
}

function buildArchitectureGraphView(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): ArchitectureGraphViewDocument {
  return {
    visibleGraph,
    fullGraph,
    anchorNodeId,
    projectionIndex: EMPTY_PROJECTION_INDEX,
    summary: deriveArchitectureGraphSummary(visibleGraph),
  };
}

function buildClassDiagramView(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): ClassDiagramViewDocument {
  return {
    visibleGraph,
    fullGraph,
    anchorNodeId,
    projectionIndex: EMPTY_PROJECTION_INDEX,
    summary: deriveClassDiagramSummary(visibleGraph),
  };
}

function buildReviewGraphView(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): ReviewGraphViewDocument {
  return {
    visibleGraph,
    fullGraph,
    anchorNodeId,
    projectionIndex: EMPTY_PROJECTION_INDEX,
    summary: deriveReviewGraphSummary(visibleGraph),
  };
}

export const SAMPLE_STATE: LinkGraphBootstrapState = {
  analysisDisplayMode: DEFAULT_ANALYSIS_DISPLAY_MODE,
  currentSceneId: DEFAULT_SCENE_ID,
  sceneStates: createDefaultSceneStates(INITIAL_SELECTED_NODE_ID, INITIAL_LAYOUT_STATE),
  workspaceGraph: INITIAL_GRAPH,
  workspaceBaseGraph: INITIAL_GRAPH,
  semanticFactGraph: INITIAL_GRAPH,
  factGraphView: buildFactGraphView(INITIAL_GRAPH, INITIAL_GRAPH, INITIAL_SELECTED_NODE_ID),
  flowchartView: buildFlowchartView(INITIAL_GRAPH, INITIAL_GRAPH, INITIAL_SELECTED_NODE_ID),
  resourceRelationView: buildResourceRelationView(INITIAL_GRAPH, INITIAL_GRAPH, INITIAL_SELECTED_NODE_ID),
  architectureGraphView: buildArchitectureGraphView(EMPTY_DOCUMENT, EMPTY_DOCUMENT, null),
  classDiagramView: buildClassDiagramView(EMPTY_DOCUMENT, EMPTY_DOCUMENT, null),
  reviewGraphView: buildReviewGraphView(EMPTY_DOCUMENT, EMPTY_DOCUMENT, null),
  designBaselineGraph: null,
  draftPatchPreview: null,
  draftWorkbenchState: { draftChanges: [], draftNotes: [] },
  canUndoDraftPatchApply: false,
  lastAppliedDraftPatchSummary: null,
  qaResult: null,
  qaRequestState: IDLE_REQUEST_STATE,
  qaRequestRecoveryState: EMPTY_QA_REQUEST_RECOVERY_STATE,
  diffReviewResult: null,
  diffReviewRequestState: IDLE_REQUEST_STATE,
  graphBeautificationResult: null,
  graphBeautificationRequestState: IDLE_REQUEST_STATE,
  mermaidIssues: sampleMermaidIssues(),
  diffItems: [
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
  ],
  syncPreviewItems: sampleSyncPreview(),
  draftVersion: 0,
  generationPlan: {
    source: "LOCAL_RULE",
    summary: "创建 DTO 并补齐服务接线。",
    warnings: ["当前实现建议来自本地规则推断。"],
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
  draftValidationState: null,
  generationPlanDiscussionSession: null,
  generationPlanDiscussionRequestState: IDLE_REQUEST_STATE,
  codeDraftRequestState: IDLE_REQUEST_STATE,
  codeEligibilityDecision: null,
  generatedCodeDraftWriteReport: {
    writtenFiles: ["src/main/java/com/example/OrderDraftDto.java"],
    skippedFiles: [],
    warnings: [],
  },
  sourceNavigationState: IDLE_SOURCE_NAVIGATION_STATE,
  operationFeedback: null,
  workspaceRevision: 0,
  semanticRevision: 0,
  snapshotRevision: 0,
};

export const EMPTY_STATE: LinkGraphBootstrapState = {
  analysisDisplayMode: DEFAULT_ANALYSIS_DISPLAY_MODE,
  currentSceneId: DEFAULT_SCENE_ID,
  sceneStates: createDefaultSceneStates(),
  workspaceGraph: EMPTY_DOCUMENT,
  workspaceBaseGraph: EMPTY_DOCUMENT,
  semanticFactGraph: EMPTY_DOCUMENT,
  factGraphView: buildFactGraphView(EMPTY_DOCUMENT, EMPTY_DOCUMENT, null),
  flowchartView: buildFlowchartView(EMPTY_DOCUMENT, EMPTY_DOCUMENT, null),
  resourceRelationView: buildResourceRelationView(EMPTY_DOCUMENT, EMPTY_DOCUMENT, null),
  architectureGraphView: buildArchitectureGraphView(EMPTY_DOCUMENT, EMPTY_DOCUMENT, null),
  classDiagramView: buildClassDiagramView(EMPTY_DOCUMENT, EMPTY_DOCUMENT, null),
  reviewGraphView: buildReviewGraphView(EMPTY_DOCUMENT, EMPTY_DOCUMENT, null),
  designBaselineGraph: null,
  draftPatchPreview: null,
  draftWorkbenchState: { draftChanges: [], draftNotes: [] },
  canUndoDraftPatchApply: false,
  lastAppliedDraftPatchSummary: null,
  qaResult: null,
  qaRequestState: IDLE_REQUEST_STATE,
  qaRequestRecoveryState: EMPTY_QA_REQUEST_RECOVERY_STATE,
  diffReviewResult: null,
  diffReviewRequestState: IDLE_REQUEST_STATE,
  graphBeautificationResult: null,
  graphBeautificationRequestState: IDLE_REQUEST_STATE,
  mermaidIssues: [],
  diffItems: [],
  syncPreviewItems: [],
  draftVersion: 0,
  generationPlan: null,
  generatedCodeDrafts: [],
  generatedCodeDraftWarnings: [],
  generationPlanRequestState: IDLE_REQUEST_STATE,
  draftValidationState: null,
  generationPlanDiscussionSession: null,
  generationPlanDiscussionRequestState: IDLE_REQUEST_STATE,
  codeDraftRequestState: IDLE_REQUEST_STATE,
  codeEligibilityDecision: null,
  sourceNavigationState: IDLE_SOURCE_NAVIGATION_STATE,
  operationFeedback: null,
  workspaceRevision: 0,
  semanticRevision: 0,
  snapshotRevision: 0,
};

function shouldUseSampleState(): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  if (window.linkGraphBootstrap || window.linkGraphBridge) {
    return false;
  }
  return Boolean(import.meta.env.DEV && import.meta.env.MODE !== "test" && /^https?:$/i.test(window.location.protocol));
}

export function resolveInitialState(args: {
  emptyState: LinkGraphBootstrapState;
  sampleState: LinkGraphBootstrapState;
}): LinkGraphBootstrapState {
  const bootstrapState = readBootstrapState();
  if (bootstrapState) {
    return bootstrapState;
  }
  return shouldUseSampleState() ? args.sampleState : args.emptyState;
}

export function resolveWorkingGraph(state: LinkGraphBootstrapState): LinkGraphDocument {
  return state.workspaceGraph ?? EMPTY_DOCUMENT;
}

export function resolveWorkspaceBaseGraph(state: LinkGraphBootstrapState): LinkGraphDocument | null {
  return state.workspaceBaseGraph ?? null;
}

export function resolveSemanticFactGraph(state: LinkGraphBootstrapState): LinkGraphDocument | null {
  return state.semanticFactGraph ?? null;
}

export function resolveFactGraphView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState = EMPTY_STATE,
): FactGraphViewDocument {
  return state.factGraphView ?? emptyState.factGraphView!;
}

export function resolveFlowchartView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState = EMPTY_STATE,
): FlowchartViewDocument {
  return state.flowchartView ?? emptyState.flowchartView!;
}

export function resolveResourceRelationView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState = EMPTY_STATE,
): ResourceRelationViewDocument {
  return state.resourceRelationView ?? emptyState.resourceRelationView!;
}

export function resolveArchitectureGraphView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState = EMPTY_STATE,
): ArchitectureGraphViewDocument {
  return state.architectureGraphView ?? emptyState.architectureGraphView!;
}

export function resolveClassDiagramView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState = EMPTY_STATE,
): ClassDiagramViewDocument {
  return state.classDiagramView ?? emptyState.classDiagramView!;
}

export function resolveReviewGraphView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState = EMPTY_STATE,
): ReviewGraphViewDocument {
  return state.reviewGraphView ?? emptyState.reviewGraphView!;
}

export function resolveDesignBaselineGraph(state: LinkGraphBootstrapState): LinkGraphDocument | null {
  return state.designBaselineGraph ?? null;
}

export function resolveSourceNavigationState(
  state: LinkGraphBootstrapState,
  idleState: SourceNavigationState = IDLE_SOURCE_NAVIGATION_STATE,
): SourceNavigationState {
  return state.sourceNavigationState ?? idleState;
}

export function resolveRequestState(state?: AsyncRequestState | null): AsyncRequestState {
  return {
    ...IDLE_REQUEST_STATE,
    ...(state ?? {}),
  };
}

export function resolveCurrentSceneState(
  state: LinkGraphBootstrapState,
): LinkGraphSceneState {
  return state.sceneStates[state.currentSceneId] ?? createSceneState();
}

export function resolveActiveViewDocument(
  state: LinkGraphBootstrapState,
  displayMode: AnalysisDisplayMode = state.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE,
): FactGraphViewDocument | FlowchartViewDocument | ResourceRelationViewDocument | ArchitectureGraphViewDocument | ClassDiagramViewDocument | ReviewGraphViewDocument {
  switch (displayMode) {
    case "FLOWCHART":
      return resolveFlowchartView(state, EMPTY_STATE);
    case "RESOURCE_RELATION_VIEW":
      return resolveResourceRelationView(state, EMPTY_STATE);
    case "ARCHITECTURE_GRAPH":
      return resolveArchitectureGraphView(state, EMPTY_STATE);
    case "CLASS_DIAGRAM":
      return resolveClassDiagramView(state, EMPTY_STATE);
    case "REVIEW_GRAPH":
      return resolveReviewGraphView(state, EMPTY_STATE);
    case "FACT_GRAPH":
    default:
      return resolveFactGraphView(state, EMPTY_STATE);
  }
}

export function resolveInitialAnchorNodeId(state: LinkGraphBootstrapState): string | null {
  const initialGraph = resolveActiveViewDocument(state).visibleGraph;
  const sceneState = resolveCurrentSceneState(state);
  return resolveAnchorNodeId(
    initialGraph.nodes,
    sceneState.anchorNodeId ?? sceneState.selectedNodeId ?? initialGraph.nodes[0]?.id ?? null,
  );
}

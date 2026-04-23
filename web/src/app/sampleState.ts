import { getSampleMermaidIssues, getSampleSyncPreview, readBootstrapState } from "./api";
import {
  deriveFactGraphSummary,
  deriveFlowchartSummary,
  deriveResourceRelationSummary,
  resolveAnchorNodeId,
} from "./appGraphSupport";
import { summarizeBootstrapState, traceLinkGraph } from "./debug";
import { resolveWorkingGraphDocument } from "./workingGraphDocument";
import type {
  AnalysisDisplayMode,
  AsyncRequestState,
  DiffItem,
  FactGraphViewDocument,
  FlowchartViewDocument,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphNode,
  QaRequestRecoveryState,
  ResourceRelationViewDocument,
  SourceNavigationState,
} from "./types";

export const DEFAULT_ANALYSIS_DISPLAY_MODE: AnalysisDisplayMode = "FLOWCHART";

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

export const SAMPLE_STATE: LinkGraphBootstrapState = {
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
  qaRequestRecoveryState: EMPTY_QA_REQUEST_RECOVERY_STATE,
  diffReviewResult: null,
  diffReviewRequestState: IDLE_REQUEST_STATE,
  mermaidIssues: getSampleMermaidIssues(),
  diffItems: DIFF_ITEMS,
  syncPreviewItems: getSampleSyncPreview(),
  generationPlan: {
    source: "MOCK",
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
  graphBeautificationRequestState: IDLE_REQUEST_STATE,
  codeDraftRequestState: IDLE_REQUEST_STATE,
  codeEligibilityDecision: null,
  generatedCodeDraftWriteReport: {
    writtenFiles: ["src/main/java/com/example/OrderDraftDto.java"],
    skippedFiles: [],
    warnings: [],
  },
  selectedNodeId: INITIAL_NODES[0]?.id ?? null,
  sourceNavigationState: IDLE_SOURCE_NAVIGATION_STATE,
};

export const EMPTY_STATE: LinkGraphBootstrapState = {
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
  qaRequestRecoveryState: EMPTY_QA_REQUEST_RECOVERY_STATE,
  diffReviewResult: null,
  diffReviewRequestState: IDLE_REQUEST_STATE,
  mermaidIssues: [],
  diffItems: [],
  syncPreviewItems: [],
  generationPlan: null,
  generationPlanRequestState: IDLE_REQUEST_STATE,
  draftValidationState: null,
  generationPlanDiscussionSession: null,
  generationPlanDiscussionRequestState: IDLE_REQUEST_STATE,
  generatedCodeDrafts: [],
  generatedCodeDraftWarnings: [],
  graphBeautificationRequestState: IDLE_REQUEST_STATE,
  codeDraftRequestState: IDLE_REQUEST_STATE,
  codeEligibilityDecision: null,
  generatedCodeDraftWriteReport: null,
  selectedNodeId: null,
  sourceNavigationState: IDLE_SOURCE_NAVIGATION_STATE,
  operationFeedback: null,
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
    traceLinkGraph("app.resolveInitialState.bootstrap", summarizeBootstrapState(bootstrapState));
    return bootstrapState;
  }
  traceLinkGraph("app.resolveInitialState.fallback", {
    useSampleState: shouldUseSampleState(),
  });
  return shouldUseSampleState() ? args.sampleState : args.emptyState;
}

export function resolveWorkingGraph(state: LinkGraphBootstrapState): LinkGraphDocument {
  return resolveWorkingGraphDocument(state);
}

export function resolveReferenceWorkingGraph(
  state: LinkGraphBootstrapState,
  displayMode: AnalysisDisplayMode = state.analysisDisplayMode ?? "FACT_GRAPH",
): LinkGraphDocument | null {
  if (state.referenceWorkingGraph) {
    return state.referenceWorkingGraph;
  }
  switch (displayMode) {
    case "FLOWCHART":
      return state.flowchartView?.fullGraph ?? state.workingGraph ?? null;
    case "RESOURCE_RELATION_VIEW":
      return state.resourceRelationView?.fullGraph ?? state.workingGraph ?? null;
    case "FACT_GRAPH":
    default:
      return state.factGraphView?.fullGraph ?? state.referenceFactGraph ?? state.workingGraph ?? null;
  }
}

export function resolveFactGraphView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState,
): FactGraphViewDocument {
  return state.factGraphView ?? emptyState.factGraphView;
}

export function resolveFlowchartView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState,
): FlowchartViewDocument {
  return state.flowchartView ?? emptyState.flowchartView;
}

export function resolveResourceRelationView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState,
): ResourceRelationViewDocument {
  return state.resourceRelationView ?? emptyState.resourceRelationView;
}

export function resolveReferenceFactGraph(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState,
): LinkGraphDocument | null {
  return resolveFactGraphView(state, emptyState).fullGraph;
}

export function resolveDesignBaselineGraph(state: LinkGraphBootstrapState): LinkGraphDocument | null {
  return state.designBaselineGraph ?? null;
}

export function resolveSourceNavigationState(
  state: LinkGraphBootstrapState,
  idleState: SourceNavigationState,
): SourceNavigationState {
  return state.sourceNavigationState ?? idleState;
}

export function resolveRequestState(state?: AsyncRequestState | null): AsyncRequestState {
  return {
    ...IDLE_REQUEST_STATE,
    ...(state ?? {}),
  };
}

export function resolveActiveViewDocument(
  state: LinkGraphBootstrapState,
  displayMode: AnalysisDisplayMode = state.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE,
): FactGraphViewDocument | FlowchartViewDocument | ResourceRelationViewDocument {
  switch (displayMode) {
    case "FLOWCHART":
      return resolveFlowchartView(state, EMPTY_STATE);
    case "RESOURCE_RELATION_VIEW":
      return resolveResourceRelationView(state, EMPTY_STATE);
    case "FACT_GRAPH":
    default:
      return resolveFactGraphView(state, EMPTY_STATE);
  }
}

export function resolveInitialAnchorNodeId(state: LinkGraphBootstrapState): string | null {
  const initialGraph = resolveActiveViewDocument(state).visibleGraph;
  return resolveAnchorNodeId(
    initialGraph.nodes,
    state.selectedNodeId ?? initialGraph.nodes[0]?.id ?? null,
  );
}

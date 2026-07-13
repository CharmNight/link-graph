import { readBootstrapState } from "./api";
import { resolveFlowchartKind } from "./flowchartKind";
import type {
  AnalysisDisplayMode,
  AssistantResultStore,
  AssistantSessionState,
  ArchitectureGraphViewDocument,
  AsyncRequestState,
  ClassDiagramViewDocument,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GraphViewPresentation,
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

/** 默认的分析展示模式（流程图）。 */
export const DEFAULT_ANALYSIS_DISPLAY_MODE: AnalysisDisplayMode = "FLOWCHART";
/** 默认场景 ID（工作台流程图场景）。 */
export const DEFAULT_SCENE_ID: LinkGraphSceneId = "WORKSPACE_FLOWCHART";

/** 异步请求空闲态：所有字段归零，未在执行任何请求。 */
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

/** 源码导航空闲态：当前未触发任何源码跳转。 */
export const IDLE_SOURCE_NAVIGATION_STATE: SourceNavigationState = {
  phase: "IDLE",
  nodeId: null,
  result: null,
  targetPath: null,
  line: null,
  column: null,
  errorMessage: null,
};

/** QA 请求恢复态初始值：无上次提交，也无上次失败记录。 */
export const EMPTY_QA_REQUEST_RECOVERY_STATE: QaRequestRecoveryState = {
  lastSubmittedRequest: null,
  lastFailedRequest: null,
};

/** 索引图（架构图 / 类图 / 评审图）请求状态初始值：均为空闲态。 */
const DEFAULT_INDEXED_GRAPH_REQUEST_STATES = {
  ARCHITECTURE: IDLE_REQUEST_STATE,
  CLASS_DIAGRAM: IDLE_REQUEST_STATE,
  REVIEW: IDLE_REQUEST_STATE,
};

/** 默认的助手会话状态：默认意图为代码解释，未锁定上下文，turns 为空。 */
const DEFAULT_ASSISTANT_SESSION_STATE: AssistantSessionState = {
  sessionId: "assistant-session",
  activeIntent: "EXPLAIN_CODE",
  contextLocked: false,
  context: {
    selectedNodeIds: [],
    selectedDiffItemIds: [],
    analysisDisplayMode: DEFAULT_ANALYSIS_DISPLAY_MODE,
    currentSceneId: DEFAULT_SCENE_ID,
    selectedMethodSignature: null,
    scopeLabel: "",
  },
  composer: {
    draft: "",
    target: {
      kind: "NewTask",
    },
  },
  nextResultSequence: 1,
  turns: [],
};

/** 助手结果存储空对象：无任何已生成的结果。 */
const EMPTY_ASSISTANT_RESULT_STORE: AssistantResultStore = {};

/** 空图文档：节点和边均为空。 */
const EMPTY_DOCUMENT: LinkGraphDocument = {
  nodes: [],
  edges: [],
};

/** 空布局状态：positions 为空对象。 */
const EMPTY_LAYOUT_STATE: LinkGraphLayoutState = {
  positions: {},
};

/** 示例同步预览项：演示两步建议（生成 DTO、补齐服务调用）。 */
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

/** 示例 Mermaid 问题列表：演示一个语义类问题（方法节点缺少 signature）。 */
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

/** 构造一个场景状态（包含选中节点、锚点、布局状态等）。 */
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

/** 构造所有场景的默认状态表（6 个工作台场景 + 1 个 DIFF 场景）。 */
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

/** 解析锚点节点 ID：优先使用 preferredNodeId（若存在），否则取首个 METHOD 节点，最后兜底到首节点。 */
function resolveAnchorNodeId(
  nodes: LinkGraphNode[],
  preferredNodeId?: string | null,
): string | null {
  if (preferredNodeId && nodes.some((node) => node.id === preferredNodeId)) {
    return preferredNodeId;
  }
  return nodes.find((node) => node.type === "METHOD")?.id ?? nodes[0]?.id ?? null;
}

/** 派生事实图的摘要：锚点标题、可见/全量节点数、隐藏节点/边数、是否截断。 */
function deriveFactGraphSummary(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
  anchorNodeId?: string | null,
) {
  const hiddenCounts = deriveSampleOnlyHiddenCounts(visibleGraph, fullGraph);
  return {
    anchorTitle: fullGraph.nodes.find((node) => node.id === anchorNodeId)?.title
      ?? visibleGraph.nodes.find((node) => node.id === anchorNodeId)?.title
      ?? null,
    visibleNodeCount: visibleGraph.nodes.length,
    fullNodeCount: fullGraph.nodes.length,
    hiddenNodeCount: hiddenCounts.hiddenNodeCount,
    hiddenEdgeCount: hiddenCounts.hiddenEdgeCount,
    truncated: hiddenCounts.hiddenNodeCount > 0 || hiddenCounts.hiddenEdgeCount > 0,
  };
}

/** 派生流程图摘要：节点/分支/异常路径数、合成边/合成入口边数、不完整标记统计等。 */
function deriveFlowchartSummary(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument = visibleGraph,
) {
  const hiddenCounts = deriveSampleOnlyHiddenCounts(visibleGraph, fullGraph);
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
    hiddenNodeCount: hiddenCounts.hiddenNodeCount,
    hiddenEdgeCount: hiddenCounts.hiddenEdgeCount,
    truncated: hiddenCounts.hiddenNodeCount > 0 || hiddenCounts.hiddenEdgeCount > 0,
    incompleteNodeCount,
    incompleteEdgeCount,
    semanticallyIncomplete: incompleteNodeCount > 0 || incompleteEdgeCount > 0,
    syntheticEdgeCount,
    syntheticEntryEdgeCount,
  };
}

/** 计算"样本专用"的隐藏节点/边数：全量图中存在但可见图中不存在的节点/边数量。 */
function deriveSampleOnlyHiddenCounts(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
): { hiddenNodeCount: number; hiddenEdgeCount: number } {
  const fullNodeIds = new Set(fullGraph.nodes.map((node) => node.id));
  const fullEdgeIds = new Set(fullGraph.edges.map((edge) => edge.id));
  const visibleOriginalNodeIds = new Set(visibleGraph.nodes
    .map((node) => node.id)
    .filter((nodeId) => fullNodeIds.has(nodeId)));
  const visibleOriginalEdgeIds = new Set(visibleGraph.edges
    .map((edge) => edge.id)
    .filter((edgeId) => fullEdgeIds.has(edgeId)));
  return {
    hiddenNodeCount: Math.max(0, fullNodeIds.size - visibleOriginalNodeIds.size),
    hiddenEdgeCount: Math.max(0, fullEdgeIds.size - visibleOriginalEdgeIds.size),
  };
}

/** 派生资源关系图摘要：可见节点/关系/资源数、回退原因、泳道分布。 */
function deriveResourceRelationSummary(visibleGraph: LinkGraphDocument) {
  const resourceCount = visibleGraph.nodes.filter(isResourceRelationNode).length;
  return {
    visibleNodeCount: visibleGraph.nodes.length,
    relationCount: visibleGraph.edges.length,
    resourceCount,
    fallbackReason: visibleGraph.edges.length > 0
      ? "NONE"
      : resourceCount === 0
        ? "NO_RESOURCE_UNITS"
        : "NO_BINDING_RELATIONS",
    laneCounts: visibleGraph.nodes.reduce<Record<string, number>>((counts, node) => {
      const lane = node.metadata?.["resource.lane"] ?? "CODE";
      counts[lane] = (counts[lane] ?? 0) + 1;
      return counts;
    }, {}),
  };
}

/** 判断节点是否属于资源关系节点（带泳道元数据 / 类型含 RESOURCE / 是常见资源类型）。 */
function isResourceRelationNode(node: LinkGraphNode): boolean {
  return node.metadata?.["resource.lane"] != null ||
    node.type.includes("RESOURCE") ||
    ["SQL", "HTTP_ENDPOINT", "MQ_TOPIC", "CONFIG_ITEM"].includes(node.type);
}

/** 派生架构图摘要：模块/包/服务/组件/资源/层/库/JDK 数量及关系数等。 */
function deriveArchitectureGraphSummary(visibleGraph: LinkGraphDocument) {
  return {
    moduleCount: visibleGraph.nodes.filter((node) => node.type === "MODULE").length,
    packageCount: visibleGraph.nodes.filter((node) => node.type === "PACKAGE").length,
    serviceCount: visibleGraph.nodes.filter((node) => node.type === "SERVICE").length,
    componentCount: visibleGraph.nodes.filter((node) => node.type === "COMPONENT").length,
    resourceCount: visibleGraph.nodes.filter((node) => node.type === "RESOURCE").length,
    layerCount: visibleGraph.nodes.filter((node) => node.type === "LAYER").length,
    libraryCount: visibleGraph.nodes.filter((node) => node.type === "LIBRARY").length,
    jdkCount: visibleGraph.nodes.filter((node) => node.metadata?.["architecture.node.kind"] === "JDK").length,
    relationCount: visibleGraph.edges.length,
    classCount: visibleGraph.nodes.filter((node) => node.metadata?.["architecture.classCount"] != null)
      .reduce((sum, node) => sum + (Number(node.metadata?.["architecture.classCount"]) || 0), 0),
    truncated: visibleGraph.truncated === true,
  };
}

/** 派生类图摘要：类/字段/接口/枚举/注解/record/object 数量及关系数等。 */
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
    relationCompleteness: "COMPLETE",
    scopeTypeCount: visibleGraph.nodes.length,
    projectTypeCount: visibleGraph.nodes.length,
    projectClassCount: visibleGraph.nodes.filter((node) => node.type === "CLASS").length,
    scopeBasis: "CLASS_NEIGHBORHOOD",
    anchorTypeNodeId: visibleGraph.nodes[0]?.id ?? null,
    anchorTypeTitle: visibleGraph.nodes[0]?.title ?? null,
    anchorTypeQualifiedName: visibleGraph.nodes[0]?.signature ?? null,
    neighborhoodLimit: visibleGraph.nodes.length,
    memberLimit: 5,
    neighborhoodCandidateTypeCount: visibleGraph.nodes.length,
    neighborhoodTruncated: false,
  };
}

/** 从方法 signature 中提取包名（取最后一个点之前的部分）。 */
function packageFromSignature(signature?: string | null): string | null {
  if (!signature) {
    return null;
  }
  const owner = signature.split("(")[0] ?? signature;
  const index = owner.lastIndexOf(".");
  return index > 0 ? owner.slice(0, index) : null;
}

/** 派生评审图摘要：变更符号/上游/下游/相关测试数量、受影响包/模块数等。 */
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
    selectedDiffItemIds: [],
    maxChangedNodes: 120,
    maxUpstreamNodes: 40,
    maxDownstreamNodes: 40,
    maxRelatedTestNodes: 40,
  };
}

/** 初始示例节点列表（用于 SAMPLE_STATE）：包含一个方法节点和一个 SQL 节点。 */
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
    confidence: "VERIFIED",
    binding: "CODE_BOUND",
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
    confidence: "INFERRED",
    binding: "PARTIAL",
    diffStatus: "MODIFIED",
    position: { x: 380, y: 196 },
    metadata: {
      "ui.x": "380",
      "ui.y": "196",
      "resource.lane": "DATA",
    },
  },
];

/** 初始示例边列表（用于 SAMPLE_STATE）：一条 CALL 类型的边连接方法到 SQL。 */
const INITIAL_EDGES: LinkGraphEdge[] = [
  {
    id: "call:place-order->insert-order",
    type: "CALL",
    source: "method:place-order",
    target: "sql:insert-order",
  },
];

/** 初始示例图：节点和边组合。 */
const INITIAL_GRAPH: LinkGraphDocument = {
  nodes: INITIAL_NODES,
  edges: INITIAL_EDGES,
};

/** 初始选中的节点 ID：首个节点。 */
const INITIAL_SELECTED_NODE_ID = INITIAL_NODES[0]?.id ?? null;
/** 初始布局状态：从初始节点中提取有坐标的节点。 */
const INITIAL_LAYOUT_STATE: LinkGraphLayoutState = {
  positions: Object.fromEntries(
    INITIAL_NODES
      .filter((node) => node.position)
      .map((node) => [node.id, node.position!]),
  ),
};

/** 空的投影索引：节点和边的映射均为空对象。 */
const EMPTY_PROJECTION_INDEX = {
  nodeMappings: {},
  edgeMappings: {},
};

/** 空的图视图表现层配置：target/lanes/controls 均为空值。 */
const EMPTY_GRAPH_VIEW_PRESENTATION: GraphViewPresentation = {
  target: {
    nodeId: null,
    title: "",
    subtitle: "",
    location: null,
  },
  lanes: [],
  hiddenBuckets: [],
  controls: {
    primaryScope: "",
    availableScopes: [],
    searchable: true,
    expandable: true,
  },
};

/** 构造事实图视图文档（包含可见图、全量图、锚点、投影索引、摘要、表现层）。 */
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
    presentation: EMPTY_GRAPH_VIEW_PRESENTATION,
  };
}

/** 构造流程图视图文档。 */
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

/** 构造资源关系图视图文档。 */
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

/** 构造架构图视图文档（含表现层）。 */
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
    presentation: EMPTY_GRAPH_VIEW_PRESENTATION,
  };
}

/** 构造类图视图文档（含表现层）。 */
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
    presentation: EMPTY_GRAPH_VIEW_PRESENTATION,
  };
}

/** 构造评审图视图文档。 */
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

/**
 * 示例 bootstrap 状态。
 * 在开发模式且没有真实 bridge / bootstrap 数据时使用，演示完整的初始数据：
 * 初始示例图 + QA / 助手会话 / 生成计划 / 代码草稿等。
 */
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
  indexedGraphRequestStates: DEFAULT_INDEXED_GRAPH_REQUEST_STATES,
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
      commandKind: "CREATE_FILE",
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
  assistantSessionState: {
    ...DEFAULT_ASSISTANT_SESSION_STATE,
    context: {
      ...DEFAULT_ASSISTANT_SESSION_STATE.context,
      selectedNodeIds: [INITIAL_SELECTED_NODE_ID],
      selectedMethodSignature: "com.example.OrderService.place(OrderDraft):Order",
      scopeLabel: "OrderService.place",
    },
  },
  assistantResultStore: EMPTY_ASSISTANT_RESULT_STORE,
  workspaceRevision: 0,
  semanticRevision: 0,
  snapshotRevision: 0,
};

/** 空 bootstrap 状态：所有视图均为空，请求状态均为空闲，无选中节点。 */
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
  indexedGraphRequestStates: DEFAULT_INDEXED_GRAPH_REQUEST_STATES,
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
  assistantSessionState: DEFAULT_ASSISTANT_SESSION_STATE,
  assistantResultStore: EMPTY_ASSISTANT_RESULT_STORE,
  workspaceRevision: 0,
  semanticRevision: 0,
  snapshotRevision: 0,
};

/** 判断是否应该使用示例状态：仅在 dev 模式 + http(s) 协议 + 无 bridge / bootstrap 时为 true。 */
function shouldUseSampleState(): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  if (window.linkGraphBootstrap || window.linkGraphBridge) {
    return false;
  }
  return Boolean(import.meta.env.DEV && import.meta.env.MODE !== "test" && /^https?:$/i.test(window.location.protocol));
}

/**
 * 解析初始状态：
 * - 优先使用真实 bootstrap 状态（从后端注入）；
 * - 否则在符合条件时使用 sampleState；
 * - 都不满足时退化为 emptyState。
 */
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

/** 解析当前工作图（缺失时退化为空文档）。 */
export function resolveWorkingGraph(state: LinkGraphBootstrapState): LinkGraphDocument {
  return state.workspaceGraph ?? EMPTY_DOCUMENT;
}

/** 解析工作台基线图（缺失时返回 null）。 */
export function resolveWorkspaceBaseGraph(state: LinkGraphBootstrapState): LinkGraphDocument | null {
  return state.workspaceBaseGraph ?? null;
}

/** 解析语义事实图（缺失时返回 null）。 */
export function resolveSemanticFactGraph(state: LinkGraphBootstrapState): LinkGraphDocument | null {
  return state.semanticFactGraph ?? null;
}

/** 解析事实图视图文档（缺失时使用 emptyState 中的版本）。 */
export function resolveFactGraphView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState = EMPTY_STATE,
): FactGraphViewDocument {
  return state.factGraphView ?? emptyState.factGraphView!;
}

/** 解析流程图视图文档（缺失时使用 emptyState 中的版本）。 */
export function resolveFlowchartView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState = EMPTY_STATE,
): FlowchartViewDocument {
  return state.flowchartView ?? emptyState.flowchartView!;
}

/** 解析资源关系图视图文档（缺失时使用 emptyState 中的版本）。 */
export function resolveResourceRelationView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState = EMPTY_STATE,
): ResourceRelationViewDocument {
  return state.resourceRelationView ?? emptyState.resourceRelationView!;
}

/** 解析架构图视图文档（缺失时使用 emptyState 中的版本）。 */
export function resolveArchitectureGraphView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState = EMPTY_STATE,
): ArchitectureGraphViewDocument {
  return state.architectureGraphView ?? emptyState.architectureGraphView!;
}

/** 解析类图视图文档（缺失时使用 emptyState 中的版本）。 */
export function resolveClassDiagramView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState = EMPTY_STATE,
): ClassDiagramViewDocument {
  return state.classDiagramView ?? emptyState.classDiagramView!;
}

/** 解析评审图视图文档（缺失时使用 emptyState 中的版本）。 */
export function resolveReviewGraphView(
  state: LinkGraphBootstrapState,
  emptyState: LinkGraphBootstrapState = EMPTY_STATE,
): ReviewGraphViewDocument {
  return state.reviewGraphView ?? emptyState.reviewGraphView!;
}

/** 解析设计基线图（缺失时返回 null）。 */
export function resolveDesignBaselineGraph(state: LinkGraphBootstrapState): LinkGraphDocument | null {
  return state.designBaselineGraph ?? null;
}

/** 解析源码导航状态（缺失时使用 idleState）。 */
export function resolveSourceNavigationState(
  state: LinkGraphBootstrapState,
  idleState: SourceNavigationState = IDLE_SOURCE_NAVIGATION_STATE,
): SourceNavigationState {
  return state.sourceNavigationState ?? idleState;
}

/** 解析请求状态：用 IDLE_REQUEST_STATE 作为基础，合并外部传入的状态字段。 */
export function resolveRequestState(state?: AsyncRequestState | null): AsyncRequestState {
  return {
    ...IDLE_REQUEST_STATE,
    ...(state ?? {}),
  };
}

/** 解析当前场景状态（缺失时构造一个空场景）。 */
export function resolveCurrentSceneState(
  state: LinkGraphBootstrapState,
): LinkGraphSceneState {
  return state.sceneStates[state.currentSceneId] ?? createSceneState();
}

/** 按展示模式解析当前激活的视图文档（流程图 / 资源关系 / 架构 / 类图 / 评审 / 事实图）。 */
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

/** 解析初始锚点节点 ID：综合当前激活视图的节点和当前场景的锚点/选中节点。 */
export function resolveInitialAnchorNodeId(state: LinkGraphBootstrapState): string | null {
  const initialGraph = resolveActiveViewDocument(state).visibleGraph;
  const sceneState = resolveCurrentSceneState(state);
  return resolveAnchorNodeId(
    initialGraph.nodes,
    sceneState.anchorNodeId ?? sceneState.selectedNodeId ?? initialGraph.nodes[0]?.id ?? null,
  );
}

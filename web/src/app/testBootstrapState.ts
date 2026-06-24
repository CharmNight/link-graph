/**
 * 测试用 bootstrap 状态构建模块。
 * 主要用于在测试环境中，将简化的输入数据补全为完整的 LinkGraphBootstrapState，
 * 包括为不同视图（事实图、流程图、资源关系、架构、类图、评审图）构造对应文档，
 * 以及重建助手会话历史。
 */
import { resolveFlowchartKind } from "./flowchartKind";
import type { AssistantTurnKind } from "./assistant/assistantTypes";
import type {
  AssistantContextSnapshot,
  AssistantResultStore,
  AssistantSessionState,
  AssistantTurnRef,
  FactGraphViewDocument,
  FlowchartViewDocument,
  ArchitectureGraphViewDocument,
  ClassDiagramViewDocument,
  GraphViewPresentation,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphLayoutState,
  LinkGraphNode,
  ResourceRelationViewDocument,
  ReviewGraphViewDocument,
} from "./types";
import {
  EMPTY_STATE,
  resolveActiveViewDocument,
  resolveCurrentSceneState,
} from "./sampleState";

/** 空的图谱文档占位对象，节点和边列表均为空。 */
const EMPTY_DOCUMENT: LinkGraphDocument = {
  nodes: [],
  edges: [],
};

/** 空的图谱视图展示配置占位，不包含任何聚焦目标、泳道或作用域。 */
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

/** 兼容旧版本测试输入的字段集合，用于在新字段命名之外仍接受旧字段。 */
export interface LegacyTestBootstrapState {
  visibleGraph?: LinkGraphDocument;
  workingGraph?: LinkGraphDocument;
  referenceWorkingGraph?: LinkGraphDocument | null;
  referenceFactGraph?: LinkGraphDocument | null;
  selectedNodeId?: string | null;
  anchorNodeId?: string | null;
  layoutState?: LinkGraphLayoutState;
  layoutRevision?: number;
}

/** 测试 bootstrap 输入类型，组合了标准 bootstrap 字段（部分可选）与遗留兼容字段。 */
export type TestBootstrapStateInput = Partial<LinkGraphBootstrapState> & LegacyTestBootstrapState;

/** 完整的测试 bootstrap 状态，将输入中所有可选字段强制具体化为非空结构。 */
export type TestBootstrapState = LinkGraphBootstrapState & {
  visibleGraph: LinkGraphDocument;
  workingGraph: LinkGraphDocument;
  referenceWorkingGraph: LinkGraphDocument | null;
  referenceFactGraph: LinkGraphDocument | null;
  selectedNodeId: string | null;
  anchorNodeId: string | null;
  layoutState: LinkGraphLayoutState;
  layoutRevision: number;
};

/** 判断输入对象是否显式包含某个 bootstrap 字段（即使值为 undefined 也算）。 */
function hasOwnInputField(
  state: TestBootstrapStateInput,
  field: keyof LinkGraphBootstrapState,
): boolean {
  return Object.prototype.hasOwnProperty.call(state, field);
}

/** 根据分析显示模式推导当前激活的场景 ID，若已显式指定则直接返回。 */
function resolveSceneId(
  state: Partial<LinkGraphBootstrapState>,
): LinkGraphBootstrapState["currentSceneId"] {
  if (state.currentSceneId) {
    return state.currentSceneId;
  }
  switch (state.analysisDisplayMode) {
    case "FACT_GRAPH":
      return "WORKSPACE_FACT";
    case "RESOURCE_RELATION_VIEW":
      return "WORKSPACE_RESOURCE_RELATION";
    case "ARCHITECTURE_GRAPH":
      return "WORKSPACE_ARCHITECTURE_GRAPH";
    case "CLASS_DIAGRAM":
      return "WORKSPACE_CLASS_DIAGRAM";
    case "REVIEW_GRAPH":
      return "WORKSPACE_REVIEW_GRAPH";
    case "FLOWCHART":
    default:
      return "WORKSPACE_FLOWCHART";
  }
}

/** 解析锚点节点 ID：优先使用预设值（若存在于节点列表中），否则退回到第一个 METHOD 类型或首个节点。 */
function resolveAnchorNodeId(
  nodes: LinkGraphNode[],
  preferredNodeId?: string | null,
): string | null {
  if (preferredNodeId && nodes.some((node) => node.id === preferredNodeId)) {
    return preferredNodeId;
  }
  return nodes.find((node) => node.type === "METHOD")?.id ?? nodes[0]?.id ?? null;
}

/** 构建事实图谱视图文档，包含可见图谱、完整图谱及隐藏元素统计摘要。 */
function buildFactGraphViewDocument(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): FactGraphViewDocument {
  const hiddenCounts = deriveSampleOnlyHiddenCounts(visibleGraph, fullGraph);
  return {
    visibleGraph,
    fullGraph,
    anchorNodeId,
    summary: {
      anchorTitle: fullGraph.nodes.find((node) => node.id === anchorNodeId)?.title
        ?? visibleGraph.nodes.find((node) => node.id === anchorNodeId)?.title
        ?? null,
      visibleNodeCount: visibleGraph.nodes.length,
      fullNodeCount: fullGraph.nodes.length,
      hiddenNodeCount: hiddenCounts.hiddenNodeCount,
      hiddenEdgeCount: hiddenCounts.hiddenEdgeCount,
      truncated: hiddenCounts.hiddenNodeCount > 0 || hiddenCounts.hiddenEdgeCount > 0,
    },
    presentation: EMPTY_GRAPH_VIEW_PRESENTATION,
  };
}

/** 计算在完整图谱中存在、但未被包含在可见图谱中的节点和边的数量。 */
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

/** 构建流程图视图文档，统计分支节点、异常路径以及语义不完整的节点/边数量。 */
function buildFlowchartViewDocument(
  visibleGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): FlowchartViewDocument {
  const fullGraph = visibleGraph;
  const incompleteNodeCount = visibleGraph.nodes.filter((node) => node.metadata?.["flow.incomplete"] === "true").length;
  const incompleteEdgeCount = visibleGraph.edges.filter((edge) => edge.metadata?.["flow.incomplete"] === "true").length;
  return {
    visibleGraph,
    fullGraph,
    anchorNodeId,
    summary: {
      nodeCount: visibleGraph.nodes.length,
      branchCount: visibleGraph.nodes.filter((node) => resolveFlowchartKind(node) === "DECISION").length,
      exceptionPathCount: visibleGraph.edges.filter((edge) => edge.label?.trim().toUpperCase() === "EXCEPTION").length,
      fullNodeCount: fullGraph.nodes.length,
      fullEdgeCount: fullGraph.edges.length,
      hiddenNodeCount: 0,
      hiddenEdgeCount: 0,
      truncated: false,
      incompleteNodeCount,
      incompleteEdgeCount,
      semanticallyIncomplete: incompleteNodeCount > 0 || incompleteEdgeCount > 0,
      syntheticEdgeCount: visibleGraph.edges.filter((edge) => edge.metadata?.["flow.synthetic"] === "true").length,
      syntheticEntryEdgeCount: 0,
    },
  };
}

/** 构建资源关系视图文档，统计资源单元数量、关系数量以及各泳道分布。 */
function buildResourceRelationViewDocument(
  visibleGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): ResourceRelationViewDocument {
  const resourceCount = visibleGraph.nodes.filter(isResourceRelationNode).length;
  const laneCounts = visibleGraph.nodes.reduce<Record<string, number>>((counts, node) => {
    const lane = node.metadata?.["resource.lane"] ?? "CODE";
    counts[lane] = (counts[lane] ?? 0) + 1;
    return counts;
  }, {});
  return {
    visibleGraph,
    fullGraph: visibleGraph,
    anchorNodeId,
    summary: {
      visibleNodeCount: visibleGraph.nodes.length,
      relationCount: visibleGraph.edges.length,
      resourceCount,
      fallbackReason: visibleGraph.edges.length > 0
        ? "NONE"
        : resourceCount === 0
          ? "NO_RESOURCE_UNITS"
          : "NO_BINDING_RELATIONS",
      laneCounts,
    },
  };
}

/** 判断给定节点是否属于资源关系类型（含资源泳道标记或常见资源类型）。 */
function isResourceRelationNode(node: LinkGraphNode): boolean {
  return node.metadata?.["resource.lane"] != null ||
    node.type.includes("RESOURCE") ||
    ["SQL", "HTTP_ENDPOINT", "MQ_TOPIC", "CONFIG_ITEM"].includes(node.type);
}

/** 构建架构视图文档，统计模块、包、服务、组件、资源、层级等结构元素的数量。 */
function buildArchitectureGraphViewDocument(
  visibleGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): ArchitectureGraphViewDocument {
  return {
    visibleGraph,
    fullGraph: visibleGraph,
    anchorNodeId,
    summary: {
      moduleCount: visibleGraph.nodes.filter((node) => node.type === "MODULE").length,
      packageCount: visibleGraph.nodes.filter((node) => node.type === "PACKAGE").length,
      serviceCount: visibleGraph.nodes.filter((node) => node.type === "SERVICE").length,
      componentCount: visibleGraph.nodes.filter((node) => node.type === "COMPONENT").length,
      resourceCount: visibleGraph.nodes.filter((node) => node.type === "RESOURCE").length,
      layerCount: visibleGraph.nodes.filter((node) => node.type === "LAYER").length,
      relationCount: visibleGraph.edges.length,
      classCount: visibleGraph.nodes
        .map((node) => Number(node.metadata?.["architecture.classCount"] ?? node.metadata?.["architecture.package.classCount"] ?? "0"))
        .filter(Number.isFinite)
        .reduce((sum, count) => sum + count, 0),
      truncated: visibleGraph.truncated === true,
    },
    presentation: EMPTY_GRAPH_VIEW_PRESENTATION,
  };
}

/** 构建 UML 类图视图文档，统计类、接口、枚举、注解、记录等类型数量及字段总数。 */
function buildClassDiagramViewDocument(
  visibleGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): ClassDiagramViewDocument {
  return {
    visibleGraph,
    fullGraph: visibleGraph,
    anchorNodeId,
    summary: {
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
    },
    presentation: EMPTY_GRAPH_VIEW_PRESENTATION,
  };
}

/** 构建评审视图文档，统计变更符号、上下游影响、相关测试节点及证据引用数量。 */
function buildReviewGraphViewDocument(
  visibleGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): ReviewGraphViewDocument {
  return {
    visibleGraph,
    fullGraph: visibleGraph,
    anchorNodeId,
    summary: {
      changedSymbolCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "CHANGED").length,
      upstreamCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "UPSTREAM").length,
      downstreamCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "DOWNSTREAM").length,
      relatedTestCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "RELATED_TEST").length,
      affectedPackageCount: 0,
      affectedModuleCount: 0,
      evidenceRefCount: visibleGraph.edges.filter((edge) => edge.metadata?.["review.edgeRole"] === "RELATION").length,
      selectedDiffItemIds: [],
      maxChangedNodes: 120,
      maxUpstreamNodes: 40,
      maxDownstreamNodes: 40,
      maxRelatedTestNodes: 40,
    },
  };
}

/** 根据当前 bootstrap 状态与选中节点构建助手上下文快照。 */
function assistantContextFromState(
  state: LinkGraphBootstrapState,
  selectedNodeId: string | null,
): AssistantContextSnapshot {
  return {
    selectedNodeIds: selectedNodeId ? [selectedNodeId] : [],
    selectedDiffItemIds: state.diffItems?.map((item) => item.id) ?? [],
    analysisDisplayMode: state.analysisDisplayMode ?? null,
    currentSceneId: state.currentSceneId ?? null,
    selectedMethodSignature: selectedNodeId
      ? state.workspaceGraph.nodes.find((node) => node.id === selectedNodeId)?.signature ?? null
      : null,
    scopeLabel: selectedNodeId
      ? state.workspaceGraph.nodes.find((node) => node.id === selectedNodeId)?.title ?? ""
      : "",
  };
}

/** 构建一个助手回合引用对象，由类型、结果 ID、源消息类型与序号组合出 turnId。 */
function assistantTurnRef(
  kind: AssistantTurnKind,
  resultId: string,
  sourceMessageType: string,
  sequence: number,
  context: AssistantContextSnapshot,
): AssistantTurnRef {
  return {
    turnId: `${sourceMessageType}:${kind.toLowerCase()}:${sequence}`,
    kind,
    sourceMessageType,
    resultId,
    createdAtEpochMillis: sequence,
    context,
  };
}

/** 根据 bootstrap 状态中已有的结果项（解释、问答、评审、生成计划、代码草稿）重建助手历史会话与结果存储。 */
function materializeAssistantHistory(
  state: LinkGraphBootstrapState,
  selectedNodeId: string | null,
): {
  assistantSessionState: AssistantSessionState;
  assistantResultStore: AssistantResultStore;
} {
  const context = assistantContextFromState(state, selectedNodeId);
  const turns: AssistantTurnRef[] = [];
  const assistantResultStore: AssistantResultStore = {};
  let sequence = 1;

  if (state.graphBeautificationResult) {
    const resultId = "test-explanation:current";
    assistantResultStore[resultId] = {
      kind: "EXPLANATION",
      explanation: state.graphBeautificationResult,
    };
    turns.push(assistantTurnRef("EXPLANATION", resultId, "graphBeautificationResult", sequence++, context));
  }
  if (state.qaResult) {
    const resultId = "test-qa:current";
    assistantResultStore[resultId] = {
      kind: "QA",
      qa: state.qaResult,
    };
    turns.push(assistantTurnRef("QA", resultId, "qaResult", sequence++, context));
  }
  if (state.diffReviewResult) {
    const resultId = "test-check:current";
    assistantResultStore[resultId] = {
      kind: "CHECK_RESULT",
      check: state.diffReviewResult,
    };
    turns.push(assistantTurnRef("CHECK_RESULT", resultId, "diffReviewResult", sequence++, context));
  }
  if (state.generationPlan || state.generationPlanDiscussionSession) {
    const resultId = "test-generation-plan:current";
    assistantResultStore[resultId] = {
      kind: "GENERATION_PLAN",
      generationPlan: state.generationPlan ?? null,
      generationDiscussionSession: state.generationPlanDiscussionSession ?? null,
    };
    turns.push(assistantTurnRef("GENERATION_PLAN", resultId, "generationPlanResult", sequence++, context));
  }
  if ((state.generatedCodeDrafts ?? []).length > 0) {
    const resultId = "test-code-draft:current";
    assistantResultStore[resultId] = {
      kind: "CODE_DRAFT",
      generationPlan: state.generationPlan ?? null,
      generationDiscussionSession: state.generationPlanDiscussionSession ?? null,
      codeDrafts: state.generatedCodeDrafts ?? [],
      codeDraftWarnings: state.generatedCodeDraftWarnings ?? [],
    };
    turns.push(assistantTurnRef("CODE_DRAFT", resultId, "codeDraftResult", sequence++, context));
  }

  return {
    assistantSessionState: {
      sessionId: "assistant-session-test",
      activeIntent: state.assistantSessionState?.activeIntent ?? "EXPLAIN_CODE",
      contextLocked: false,
      context,
      composer: state.assistantSessionState?.composer ?? {
        draft: "",
        target: {
          kind: "NewTask",
        },
      },
      nextResultSequence: state.assistantSessionState?.nextResultSequence ?? 1,
      turns,
    },
    assistantResultStore,
  };
}

/**
 * 将测试 bootstrap 输入具象化为完整状态。
 * 处理流程：解析场景 ID，规范化场景状态，构建各视图文档与助手历史，最终合并产出可用的 TestBootstrapState。
 */
export function materializeThreeViewDocuments(
  state: TestBootstrapStateInput,
): TestBootstrapState {
  const currentSceneId = resolveSceneId(state);
  const baseSceneState = (state.sceneStates ?? EMPTY_STATE.sceneStates)[currentSceneId] ?? EMPTY_STATE.sceneStates[currentSceneId];
  const sceneStates = state.sceneStates ?? {
    ...EMPTY_STATE.sceneStates,
    [currentSceneId]: {
      ...baseSceneState,
      selectedNodeId: state.selectedNodeId ?? baseSceneState.selectedNodeId ?? null,
      anchorNodeId: state.anchorNodeId ?? baseSceneState.anchorNodeId ?? state.selectedNodeId ?? null,
      layoutState: state.layoutState ?? baseSceneState.layoutState,
      layoutRevision: state.layoutRevision ?? baseSceneState.layoutRevision,
    },
  };
  const workingGraph = state.workspaceGraph ?? state.workingGraph ?? EMPTY_DOCUMENT;
  const workspaceBaseGraph = state.workspaceBaseGraph ?? state.referenceWorkingGraph ?? workingGraph;
  const semanticFactGraph = state.semanticFactGraph ?? state.referenceFactGraph ?? workingGraph;
  const normalizedState: LinkGraphBootstrapState = {
    ...EMPTY_STATE,
    ...state,
    currentSceneId,
    sceneStates,
    workspaceGraph: workingGraph,
    workspaceBaseGraph,
    semanticFactGraph,
  };
  const visibleGraph = state.visibleGraph ?? resolveActiveViewDocument(normalizedState).visibleGraph;
  const factFullGraph = semanticFactGraph ?? workingGraph;
  const sceneState = resolveCurrentSceneState(normalizedState);
  const anchorNodeId = resolveAnchorNodeId(
    visibleGraph.nodes,
    sceneState.anchorNodeId ?? sceneState.selectedNodeId ?? null,
  );
  const selectedNodeId = sceneState.selectedNodeId ?? null;
  const assistantHistory = hasOwnInputField(state, "assistantSessionState") || hasOwnInputField(state, "assistantResultStore")
    ? {
        assistantSessionState: normalizedState.assistantSessionState,
        assistantResultStore: normalizedState.assistantResultStore,
      }
    : materializeAssistantHistory(normalizedState, selectedNodeId);

  return {
    ...normalizedState,
    assistantSessionState: assistantHistory.assistantSessionState,
    assistantResultStore: assistantHistory.assistantResultStore,
    workspaceGraph: workingGraph,
    workspaceBaseGraph,
    semanticFactGraph,
    factGraphView: buildFactGraphViewDocument(visibleGraph, factFullGraph, anchorNodeId),
    flowchartView: buildFlowchartViewDocument(visibleGraph, anchorNodeId),
    resourceRelationView: buildResourceRelationViewDocument(visibleGraph, anchorNodeId),
    architectureGraphView: buildArchitectureGraphViewDocument(visibleGraph, anchorNodeId),
    classDiagramView: buildClassDiagramViewDocument(visibleGraph, anchorNodeId),
    reviewGraphView: buildReviewGraphViewDocument(visibleGraph, anchorNodeId),
    sceneStates,
    visibleGraph,
    workingGraph,
    referenceWorkingGraph: workspaceBaseGraph,
    referenceFactGraph: semanticFactGraph,
    selectedNodeId,
    anchorNodeId,
    layoutState: sceneState.layoutState,
    layoutRevision: sceneState.layoutRevision,
  };
}

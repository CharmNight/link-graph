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

/** 讲解历史快照的最小子集；本 hook 只关心会话标签，用于拼装历史轨迹。 */
interface ExplanationHistoryEntry {
  /** 该历史快照对应的人类可读会话标签。 */
  sessionLabel: string;
}

/**
 * 工作台派生状态计算 Hook 的入参集合。
 *
 * 包含原始来源状态（QA、讲解、草稿、各视图文档、生成计划等）以及若干
 * 纯函数式解析器（owner 签名、目标节点、节点显示 ID、草稿叠加等）。
 * Hook 内部不会派生来源，只做组合与投影。
 */
interface UseWorkbenchDerivedStateArgs {
  /** 当前分析展示模式（事实图 / 流程图 / 资源关系 / 架构 / 类图 / 审查图）。 */
  analysisDisplayMode: AnalysisDisplayMode;
  /** 当前助理聚焦的阶段目标（解释 / QA / 草稿实现建议等），影响节点聚焦与叠加。 */
  activeAssistantTarget: AssistantStageTarget;
  /** 当前 QA 结果。 */
  qaResult: AssistantQaViewState["result"];
  /** 当前 QA 请求状态。 */
  qaRequestState: AsyncRequestState;
  /** QA 请求失败后的恢复状态（重试策略等）。 */
  qaRequestRecoveryState: QaRequestRecoveryState;
  /** QA 作用的目标节点 ID 列表（空表示整张图）。 */
  qaTargetNodeIds: string[];
  /** QA 作用目标的人类可读标题（用于范围标签）。 */
  qaTargetTitle: string | null;
  /** 当前选中的 QA 变更 ID。 */
  selectedQaChangeId: string | null;
  /** 当前选中的 QA 会话线程 ID。 */
  selectedQaThreadId: string | null;
  /** 链路讲解结果（含步骤、粒度等）。 */
  graphBeautificationResult: GraphBeautificationResult | null;
  /** 链路讲解请求状态。 */
  graphBeautificationRequestState: AsyncRequestState;
  /** 当前选中的讲解步骤 ID。 */
  selectedExplanationStepId: string | null;
  /** 当前选中的讲解粒度。 */
  selectedExplanationGranularity: AssistantExplanationViewState["granularity"];
  /** 鼠标悬停的讲解步骤 ID。 */
  hoveredExplanationStepId: string | null;
  /** 讲解历史记录（用于拼装轨迹与上一条标签）。 */
  explanationHistory: ExplanationHistoryEntry[];
  /** 当前讲解会话的人类可读标签。 */
  currentExplanationSessionLabel: string;
  /** 草稿工作台原始状态（变更 + 备注）。 */
  draftWorkbenchState: DraftWorkbenchState;
  /** 当前选中的草稿条目 ID。 */
  selectedDraftEntryId: string | null;
  /** 草稿对比模式：after = 只看应用后；compare = 前后对比。 */
  draftCompareMode: "after" | "compare";
  /** 当前工作图（含草稿叠加），用于归属计算与叠加渲染。 */
  draftGraph: LinkGraphDocument | null;
  /** 语义层事实图（用作事实图模式下的对比基准）。 */
  semanticFactGraph: LinkGraphDocument | null;
  /** 工作区基线图（用作非事实图模式下的对比基准）。 */
  workspaceBaseGraph: LinkGraphDocument | null;
  /** 事实图视图文档。 */
  factGraphView: FactGraphViewDocument;
  /** 流程图视图文档。 */
  flowchartView: FlowchartViewDocument;
  /** 资源关系图视图文档。 */
  resourceRelationView: ResourceRelationViewDocument;
  /** 架构图视图文档。 */
  architectureGraphView: ArchitectureGraphViewDocument;
  /** 类图视图文档。 */
  classDiagramView: ClassDiagramViewDocument;
  /** 审查图视图文档。 */
  reviewGraphView: ReviewGraphViewDocument;
  /** 生成计划（助理给出的草稿实施计划）。 */
  generationPlan: GenerationPlan | null;
  /** 生成计划请求状态。 */
  generationPlanRequestState: AsyncRequestState;
  /** 生成计划对应的草稿版本号（用于判断是否过期）。 */
  generationPlanDraftVersion: number | null;
  /** 当前草稿版本号。 */
  draftVersion: number | null;
  /** 已生成的代码草稿列表。 */
  generatedCodeDrafts: GeneratedCodeDraft[];
  /** 已生成代码草稿对应的版本号。 */
  generatedCodeDraftVersion: number | null;
  /** 代码草稿请求状态。 */
  codeDraftRequestState: AsyncRequestState;
  /** 解析节点归属的方法/类型签名，用于按当前焦点过滤草稿条目。 */
  resolveNodeOwnerSignature: (node: LinkGraphNode | null | undefined) => string | null;
  /** 解析草稿条目归属的签名集合（条目可能跨多个 owner）。 */
  resolveEntryOwnerSignatures: (
    entry: DraftWorkbenchEntry | null,
    graph: LinkGraphDocument,
  ) => Set<string>;
  /** 解析草稿条目影响到的目标节点 ID 列表。 */
  resolveDraftEntryTargetNodeIds: (entry: DraftWorkbenchEntry | null) => string[];
  /** 给定节点 ID 与可见节点集合，返回真正应当展示的节点 ID（处理虚拟节点映射等）。 */
  resolveDisplayedNodeId: (nodeId: string | null | undefined, nodes: LinkGraphNode[]) => string | null;
  /** 把当前选中的草稿条目叠加到流程图视图上，返回叠加后的流程图视图。 */
  overlayDraftEntryOntoFlowchartView: (args: {
    view: FlowchartViewDocument;
    workingGraph: LinkGraphDocument | null;
    entry: DraftWorkbenchEntry | null;
    activeAssistantTarget: AssistantStageTarget;
    compareMode: "after" | "compare";
  }) => FlowchartViewDocument;
}

/**
 * 工作台派生状态计算 Hook。
 *
 * 把来自上游的原始状态（QA、讲解、草稿、各视图文档、生成计划、代码草稿等）以及
 * 一些纯函数解析器，组合 / 投影成 UI 直接消费的派生状态：当前可见图、当前锚点、
 * 当前方法签名、过滤后的草稿工作台、流程图叠加、讲解聚焦节点、QA 范围标签等。
 *
 * 内部大量使用 useMemo，依赖稳定以保证子组件不会重渲染。
 */
export function useWorkbenchDerivedState(args: UseWorkbenchDerivedStateArgs) {
  const {
    analysisDisplayMode,
    activeAssistantTarget,
    qaResult,
    qaRequestState,
    qaRequestRecoveryState,
    qaTargetNodeIds,
    qaTargetTitle,
    selectedQaChangeId,
    selectedQaThreadId,
    graphBeautificationResult,
    graphBeautificationRequestState,
    selectedExplanationStepId,
    selectedExplanationGranularity,
    hoveredExplanationStepId,
    explanationHistory,
    currentExplanationSessionLabel,
    draftWorkbenchState,
    selectedDraftEntryId,
    draftCompareMode,
    draftGraph,
    semanticFactGraph,
    workspaceBaseGraph,
    factGraphView,
    flowchartView,
    resourceRelationView,
    architectureGraphView,
    classDiagramView,
    reviewGraphView,
    generationPlan,
    generationPlanRequestState,
    generationPlanDraftVersion,
    draftVersion,
    generatedCodeDrafts,
    generatedCodeDraftVersion,
    codeDraftRequestState,
    resolveNodeOwnerSignature,
    resolveEntryOwnerSignatures,
    resolveDraftEntryTargetNodeIds,
    resolveDisplayedNodeId,
    overlayDraftEntryOntoFlowchartView,
  } = args;

  // 把六个视图文档打包，方便按展示模式统一选择
  const activeDisplayGraphDocuments = {
    factGraphView,
    flowchartView,
    resourceRelationView,
    architectureGraphView,
    classDiagramView,
    reviewGraphView,
  };
  // 当前展示模式下应当呈现的可见图（按模式从对应视图文档中取出）
  const activeViewGraph = activeVisibleGraphForDisplayMode(analysisDisplayMode, activeDisplayGraphDocuments);
  // 当前展示模式下的锚点节点 ID（用于节点聚焦、归属解析等）
  const activeAnchorNodeId = activeAnchorNodeIdForDisplayMode(analysisDisplayMode, activeDisplayGraphDocuments);

  // 当前锚点节点对应的方法 / 类型签名；若取不到则视为"未聚焦到具体 owner"
  const activeMethodSignature = useMemo(() => {
    const activeAnchorNode = activeViewGraph.nodes.find((node) => node.id === activeAnchorNodeId) ?? null;
    return resolveNodeOwnerSignature(activeAnchorNode);
  }, [activeAnchorNodeId, activeViewGraph.nodes, resolveNodeOwnerSignature]);

  // 按当前 owner 签名（或目标节点在可见图中的存在性）过滤草稿条目，
  // 只保留与当前聚焦方法 / 可见节点相关的变更与备注
  const filteredDraftWorkbenchState = useMemo(() => {
    if (!activeMethodSignature) {
      return draftWorkbenchState;
    }
    const filterEntries = (entries: DraftWorkbenchEntry[]) => entries.filter((entry) => {
      const ownerSignatures = resolveEntryOwnerSignatures(entry, draftGraph ?? { nodes: [], edges: [] });
      if (ownerSignatures.size > 0) {
        return ownerSignatures.has(activeMethodSignature);
      }
      return resolveDraftEntryTargetNodeIds(entry)
        .some((nodeId) => resolveDisplayedNodeId(nodeId, activeViewGraph.nodes) != null);
    });
    return {
      draftChanges: filterEntries(draftWorkbenchState.draftChanges),
      draftNotes: filterEntries(draftWorkbenchState.draftNotes),
    };
  }, [
    activeMethodSignature,
    activeViewGraph.nodes,
    draftGraph,
    draftWorkbenchState,
    resolveDisplayedNodeId,
    resolveDraftEntryTargetNodeIds,
    resolveEntryOwnerSignatures,
  ]);

  // 给 UI 用的草稿工作台视图状态：合并过滤后的草稿、对比模式、当前选中条目
  const draftState: DraftWorkbenchViewState = {
    draftState: filteredDraftWorkbenchState,
    compareMode: draftCompareMode,
    selectedEntryId: selectedDraftEntryId,
  };

  // 当前选中的草稿条目（基于过滤后的状态解析）
  const selectedDraftEntry = useMemo(
    () => selectDraftWorkbenchEntry(filteredDraftWorkbenchState, selectedDraftEntryId),
    [filteredDraftWorkbenchState, selectedDraftEntryId],
  );

  // 把选中的草稿条目叠加到流程图视图上：呈现给用户的最终流程图
  const presentedFlowchartView = useMemo(
    () => overlayDraftEntryOntoFlowchartView({
      view: flowchartView,
      workingGraph: draftGraph,
      entry: selectedDraftEntry,
      activeAssistantTarget,
      compareMode: draftCompareMode,
    }),
    [activeAssistantTarget, draftCompareMode, draftGraph, flowchartView, overlayDraftEntryOntoFlowchartView, selectedDraftEntry],
  );

  // 当前选中的讲解步骤（缺失时回退到第一步）
  const selectedExplanationStep = graphBeautificationResult?.steps.find((step) => step.stepId === selectedExplanationStepId)
    ?? graphBeautificationResult?.steps?.[0]
    ?? null;
  // 当前鼠标悬停的讲解步骤
  const hoveredExplanationStep = graphBeautificationResult?.steps.find((step) => step.stepId === hoveredExplanationStepId)
    ?? null;
  // 讲解模式下需要聚焦的节点：优先悬停步骤的主节点，再退到选中步骤的主节点；非讲解模式返回 null
  const explanationFocusNodeId = activeAssistantTarget === "explanation"
    ? hoveredExplanationStep?.primaryNodeId ?? selectedExplanationStep?.primaryNodeId ?? null
    : null;

  // 汇总所有草稿条目影响到的节点 ID（用于在画布上做变更高亮）
  const draftChangedNodeIds = useMemo(
    () => collectDraftChangedNodeIds(filteredDraftWorkbenchState, resolveDraftEntryTargetNodeIds),
    [filteredDraftWorkbenchState, resolveDraftEntryTargetNodeIds],
  );

  // 草稿对比投影：选定基准图（事实图模式用语义图，其他模式用工作区基线），
  // 把选中条目与可见图、工作图组装成对比视图数据
  const draftCompareProjection = useMemo(
    () => {
      const referenceGraph = analysisDisplayMode === "FACT_GRAPH"
        ? semanticFactGraph
        : workspaceBaseGraph;
      return buildDraftCompareProjection({
        compareMode: draftCompareMode,
        selectedEntry: selectedDraftEntry,
        visibleGraph: activeViewGraph,
        referenceGraph,
        workingGraph: draftGraph ?? { nodes: [], edges: [] },
      });
    },
    [activeViewGraph, analysisDisplayMode, draftCompareMode, draftGraph, semanticFactGraph, workspaceBaseGraph, selectedDraftEntry],
  );

  // 讲解视图状态：结果、请求状态、当前步骤、粒度，以及历史轨迹与上一条标签
  const explanationState: AssistantExplanationViewState = {
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

  // QA 视图状态：结果、请求状态、恢复策略、当前选中的变更 / 线程，以及范围标签
  const qaState: AssistantQaViewState = {
    result: qaResult,
    requestState: qaRequestState,
    qaRequestRecoveryState,
    selectedChangeId: selectedQaChangeId,
    selectedThreadId: selectedQaThreadId,
    scopeLabel: buildQaScopeLabel(qaTargetNodeIds, qaTargetTitle),
  };

  // 草稿实施建议状态：根据生成计划、版本号、请求状态推断当前建议是否新鲜 / 是否过期
  const draftImplementationSuggestionState = deriveDraftImplementationSuggestionState({
    generationPlan,
    generationPlanRequestState,
    generationPlanDraftVersion,
    draftVersion,
  });

  // 代码差异状态：根据代码草稿、版本号、请求状态推断差异展示的状态
  const codeDiffStatus: CodeDiffStatus = deriveCodeDiffStatus({
    generatedCodeDrafts,
    generatedCodeDraftVersion,
    draftVersion,
    codeDraftRequestState,
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

/**
 * 根据 QA 目标节点 ID 列表与目标标题，构造人类可读的范围标签。
 * - 空列表：整张链路；
 * - 单个节点：具体节点标题；
 * - 多个节点：节点数量概要。
 */
function buildQaScopeLabel(targetNodeIds: string[], targetTitle: string | null): string {
  if (targetNodeIds.length === 0) {
    return "当前范围：整张链路";
  }
  if (targetNodeIds.length === 1) {
    return `当前节点：${targetTitle ?? targetNodeIds[0]}`;
  }
  return `当前范围：${targetNodeIds.length} 个节点`;
}

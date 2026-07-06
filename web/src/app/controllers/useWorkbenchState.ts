import { useState } from "react";
import type { Dispatch, SetStateAction } from "react";
import type {
  AnalysisDisplayMode,
  ArchitectureGraphViewDocument,
  AssistantSessionState,
  AssistantResultStore,
  AsyncRequestState,
  ClassDiagramViewDocument,
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
  IndexedGraphRequestStates,
  IndexedGraphView,
  InvocationExpansionSceneState,
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
  ReviewGraphViewDocument,
  SourceNavigationState,
  StageEligibilityDecision,
  SyncPreviewItem,
} from "../types";
import type { RequestFailureNotice } from "./bridgeCommandTypes";

// 工作台启动时缺省采用流程图视角来呈现分析结果
const DEFAULT_ANALYSIS_DISPLAY_MODE: AnalysisDisplayMode = "FLOWCHART";
// 需要单独跟踪加载/请求状态的索引化图谱视图清单
const INDEXED_GRAPH_VIEWS: IndexedGraphView[] = ["ARCHITECTURE", "CLASS_DIAGRAM", "REVIEW"];

/**
 * 工作台画布层面的聚合状态：维护当前可见的节点/边、用户交互态（选中、锚点、当前场景）、
 * 以及工作区底图、语义事实图与多种分析视图（流程图、资源关系、架构图、类图、评审图）的快照，
 * 同时承载草稿图等用于支撑差异预览与回滚的中间产物。
 */
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
  architectureGraphView: ArchitectureGraphViewDocument;
  classDiagramView: ClassDiagramViewDocument;
  reviewGraphView: ReviewGraphViewDocument;
  draftGraph: LinkGraphDocument | null;
}

/**
 * 工作台业务投影层面的状态：聚合草稿变更、生成计划、问答、差异评审、Mermaid 校验、
 * 代码草稿、源码导航、操作反馈、助手会话等跨阶段产物，作为各阶段共享的"事实之窗"，
 * 让 UI 可以一致地展示从草稿到最终生成的全流程进度与结果。
 */
export interface WorkbenchProjectionState {
  detailNodeId: string | null;
  draftWorkbenchState: DraftWorkbenchState;
  designBaseline: LinkGraphDocument | null;
  draftPatchPreview: GraphPatch | null;
  lastAppliedDraftPatchPreview: GraphPatch | null;
  canUndoDraftPatchApply: boolean;
  lastAppliedDraftPatchSummary: string | null;
  qaResult: GraphPatchResult | null;
  qaRequestState: AsyncRequestState;
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
  indexedGraphRequestStates: IndexedGraphRequestStates;
  sourceNavigationState: SourceNavigationState;
  operationFeedback: OperationFeedback | null;
  lastMessageType: string | null;
  graphSurfaceExperiments: GraphSurfaceExperimentFlags | null;
  artifactContents: Record<string, string>;
  assistantSessionState: AssistantSessionState;
  assistantResultStore: AssistantResultStore;
}

/**
 * useWorkbenchState 的入参集合：除启动初始数据外，还包含一组由调用方注入的解析/规整函数，
 * 用于把引导态（BootstrapState）映射为画布初始快照、对节点做归一化布局、补齐异步请求状态等，
 * 让该 hook 专注于状态整合，而把数据来源与转换规则解耦到外部。
 */
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
  resolveArchitectureGraphView: (state: LinkGraphBootstrapState) => ArchitectureGraphViewDocument;
  resolveClassDiagramView: (state: LinkGraphBootstrapState) => ClassDiagramViewDocument;
  resolveReviewGraphView: (state: LinkGraphBootstrapState) => ReviewGraphViewDocument;
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

/**
 * 构造一个绑定到指定字段 key 的 setter：值未变化（Object.is）时直接复用原对象，
 * 避免无意义的新对象引用传播导致的重渲染；同时支持函数式更新语义。
 */
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

// 把可能为函数或直接值的 React setState 入参解析成实际取值，便于在自定义 setter 中复用
function resolveStateAction<T>(value: SetStateAction<T>, currentValue: T): T {
  return typeof value === "function"
    ? (value as (currentValue: T) => T)(currentValue)
    : value;
}

// 生成一个没有任何选中、锚点和布局信息的空白场景状态，作为缺失场景的兜底
function createEmptySceneState(): LinkGraphSceneState {
  return {
    selectedNodeId: null,
    anchorNodeId: null,
    layoutState: {
      positions: {},
    },
    layoutRevision: 0,
    collapsedNodeIds: [],
    invocationExpansionState: createEmptyInvocationExpansionSceneState(),
  };
}

function createEmptyInvocationExpansionSceneState(): InvocationExpansionSceneState {
  return {
    activeExpansionId: null,
    activeExpansionPath: [],
    collapsedExpansionIds: [],
    activeSiblingByParentContext: {},
    blockPositions: {},
    lastChildStateByExpansionId: {},
    contextMode: "ACTIVE_CHAIN",
  };
}

// 构造一份默认的助手会话状态：EXPLAIN_CODE 意图、上下文未锁定、空草稿，作为冷启动时的占位会话
function createDefaultAssistantSessionState(): AssistantSessionState {
  return {
    sessionId: "assistant-session",
    activeIntent: "EXPLAIN_CODE",
    contextLocked: false,
    context: {
      selectedNodeIds: [],
      selectedDiffItemIds: [],
      analysisDisplayMode: null,
      currentSceneId: null,
      selectedMethodSignature: null,
      scopeLabel: "",
    },
    composer: {
      draft: "",
      target: {
        kind: "NewTask",
      },
      qaMode: "AUTO",
    },
    nextResultSequence: 1,
    turns: [],
  };
}

/**
 * 针对所有索引化图谱视图（架构图、类图、评审图）统一规整请求状态：
 * 缺失或为空的状态会通过 resolveRequestState 兜底为统一的初始态，保证渲染层拿到的结构一致。
 */
export function resolveIndexedGraphRequestStates(
  state: IndexedGraphRequestStates | null | undefined,
  resolveRequestState: (requestState?: AsyncRequestState | null) => AsyncRequestState,
): IndexedGraphRequestStates {
  return Object.fromEntries(
    INDEXED_GRAPH_VIEWS.map((view) => [view, resolveRequestState(state?.[view])]),
  ) as IndexedGraphRequestStates;
}

// 按顺序比较两个节点 ID 数组是否完全一致，用于折叠节点等列表字段的等价判断以避免无效更新
function sameNodeIdList(left: string[] | undefined, right: string[] | undefined): boolean {
  const normalizedLeft = left ?? [];
  const normalizedRight = right ?? [];
  if (normalizedLeft.length !== normalizedRight.length) {
    return false;
  }
  return normalizedLeft.every((nodeId, index) => nodeId === normalizedRight[index]);
}

// 比较两份布局状态的位置映射是否逐节点坐标一致，用于判定场景布局是否真的发生变化
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

function sameInvocationExpansionSceneState(
  left: InvocationExpansionSceneState | null | undefined,
  right: InvocationExpansionSceneState | null | undefined,
): boolean {
  return JSON.stringify(left ?? createEmptyInvocationExpansionSceneState()) ===
    JSON.stringify(right ?? createEmptyInvocationExpansionSceneState());
}

// 按字段语义判断场景状态某一字段是否等价：折叠列表和布局使用专用比较，其他字段回退到引用相等
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
  if (key === "invocationExpansionState") {
    return sameInvocationExpansionSceneState(
      left as InvocationExpansionSceneState | null | undefined,
      right as InvocationExpansionSceneState | null | undefined,
    );
  }
  return Object.is(left, right);
}

/**
 * 生成一个仅更新"当前场景"指定字段的 setter，并可选地把同一值镜像到画布顶层的便捷字段
 * （例如 selectedNodeId/anchorNodeId），用于保证场景内状态与画布全局状态保持同步，
 * 仅在值确有变化时才产生新对象，避免不必要的级联渲染。
 */
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

/**
 * 专门用于更新当前场景中 selectedNodeId/anchorNodeId 字段的 setter：
 * 同步刷新场景状态与画布顶层镜像字段，并对空值与等值做短路判断以抑制无意义的重渲染。
 */
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

/**
 * 基于引导态和初始图谱，构造画布的初始快照：
 * 通过各 resolve* 函数从启动数据中抽取工作区底图、语义事实图与各分析视图，
 * 并对节点执行归一化布局，再结合场景状态推导初始的选中节点和锚点。
 */
function buildInitialCanvasState({
  initialState,
  initialGraph,
  initialAnchorNodeId,
  resolveWorkspaceBaseGraph,
  resolveSemanticFactGraph,
  resolveFactGraphView,
  resolveFlowchartView,
  resolveResourceRelationView,
  resolveArchitectureGraphView,
  resolveClassDiagramView,
  resolveReviewGraphView,
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
  | "resolveArchitectureGraphView"
  | "resolveClassDiagramView"
  | "resolveReviewGraphView"
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
    architectureGraphView: resolveArchitectureGraphView(initialState),
    classDiagramView: resolveClassDiagramView(initialState),
    reviewGraphView: resolveReviewGraphView(initialState),
    draftGraph: resolveWorkingGraph(initialState),
  };
}

/**
 * 基于引导态构造业务投影的初始快照：将草稿、问答、差异评审、生成计划、代码草稿、
 * 助手会话等跨阶段产物的初始值集中规整，并对所有异步请求状态走 resolveRequestState 兜底，
 * 让上层组件拿到一份字段完整、形态一致的投影初始值。
 */
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
    qaResult: initialState.qaResult ?? null,
    qaRequestState: resolveRequestState(initialState.qaRequestState),
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
    indexedGraphRequestStates: resolveIndexedGraphRequestStates(
      initialState.indexedGraphRequestStates,
      resolveRequestState,
    ),
    sourceNavigationState: resolveSourceNavigationState(initialState),
    operationFeedback: initialState.operationFeedback ?? null,
    lastMessageType: initialState.lastMessageType ?? null,
    graphSurfaceExperiments: initialState.graphSurfaceExperiments ?? null,
    artifactContents: initialState.artifactContents ?? {},
    assistantSessionState: initialState.assistantSessionState ?? createDefaultAssistantSessionState(),
    assistantResultStore: initialState.assistantResultStore ?? {},
  };
}

/**
 * 工作台状态整合 hook：将画布快照、业务投影、问答目标节点、选择分组、导入对话框、
 * Mermaid 草稿与差异目标项等多个维度的状态聚合成一份统一的对外接口，
 * 同时为每个字段生成等值短路的 setter，避免无谓的重渲染并向调用方屏蔽场景内部细节。
 */
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
  resolveArchitectureGraphView,
  resolveClassDiagramView,
  resolveReviewGraphView,
  resolveCurrentSceneState,
  resolveWorkingGraph,
  resolveDesignBaselineGraph,
  resolveSourceNavigationState,
  normalizeGraphNodes,
}: UseWorkbenchStateArgs) {
  // 画布状态：节点、边、选中/锚点、当前场景、各类分析视图快照
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
      resolveArchitectureGraphView,
      resolveClassDiagramView,
      resolveReviewGraphView,
      resolveCurrentSceneState,
      resolveWorkingGraph,
      normalizeGraphNodes,
    }),
  );
  // 投影状态：草稿、问答、差异、生成计划、代码草稿、助手会话等跨阶段产物
  const [projectionState, setProjectionState] = useState<WorkbenchProjectionState>(() =>
    buildInitialProjectionState(
      initialState,
      resolveRequestState,
      resolveDesignBaselineGraph,
      resolveSourceNavigationState,
    ),
  );
  // 当前问答请求的目标节点 ID 列表，用于把问答范围限定在用户选中的子图
  const [qaTargetNodeIds, setQaTargetNodeIds] = useState<string[]>([]);
  // 多选分组中保存的节点 ID 集合，支撑批量操作和分组高亮
  const [selectionGroupNodeIds, setSelectionGroupNodeIds] = useState<string[]>([]);
  // 最近一次异步请求失败时展示给用户的提示信息，由调用方按需清除
  const [requestFailureNotice, setRequestFailureNotice] = useState<RequestFailureNotice | null>(null);
  // 导入对话框的开关状态，控制 Mermaid 等导入入口的弹层显隐
  const [isImportDialogOpen, setImportDialogOpen] = useState(false);
  // Mermaid 导入对话框中用户正在编辑的源文本草稿
  const [mermaidDraft, setMermaidDraft] = useState("");
  // 差异对比视图中作为目标的差异项 ID 列表，用于聚焦特定变更
  const [diffTargetItemIds, setDiffTargetItemIds] = useState<string[]>([]);
  // 从当前场景派生的折叠节点 ID 列表，缺失时回退为空数组
  const collapsedNodeIds = canvasState.sceneStates[canvasState.currentSceneId]?.collapsedNodeIds ?? [];
  // 更新折叠节点 ID 列表的便捷 setter，写入时会同步落到当前场景状态
  const setCollapsedNodeIds = updateCurrentSceneStateField(setCanvasState, "collapsedNodeIds");
  const invocationExpansionState = canvasState.sceneStates[canvasState.currentSceneId]?.invocationExpansionState
    ?? createEmptyInvocationExpansionSceneState();
  const setInvocationExpansionState = updateCurrentSceneStateField(setCanvasState, "invocationExpansionState");

  // 画布状态各字段的等值短路 setter 集合，供调用方按字段名直接更新
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
    setArchitectureGraphView: updateStateField(setCanvasState, "architectureGraphView"),
    setClassDiagramView: updateStateField(setCanvasState, "classDiagramView"),
    setReviewGraphView: updateStateField(setCanvasState, "reviewGraphView"),
    setDraftGraph: updateStateField(setCanvasState, "draftGraph"),
    setSceneLayoutState: updateCurrentSceneStateField(setCanvasState, "layoutState"),
  };

  // 投影状态各字段的等值短路 setter 集合，统一对外暴露投影字段的更新入口
  const projectionSetters = {
    setDetailNodeId: updateStateField(setProjectionState, "detailNodeId"),
    setDraftWorkbenchState: updateStateField(setProjectionState, "draftWorkbenchState"),
    setDesignBaseline: updateStateField(setProjectionState, "designBaseline"),
    setDraftPatchPreview: updateStateField(setProjectionState, "draftPatchPreview"),
    setLastAppliedDraftPatchPreview: updateStateField(setProjectionState, "lastAppliedDraftPatchPreview"),
    setCanUndoDraftPatchApply: updateStateField(setProjectionState, "canUndoDraftPatchApply"),
    setLastAppliedDraftPatchSummary: updateStateField(setProjectionState, "lastAppliedDraftPatchSummary"),
    setQaResult: updateStateField(setProjectionState, "qaResult"),
    setQaRequestState: updateStateField(setProjectionState, "qaRequestState"),
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
    setIndexedGraphRequestStates: updateStateField(setProjectionState, "indexedGraphRequestStates"),
    setSourceNavigationState: updateStateField(setProjectionState, "sourceNavigationState"),
    setOperationFeedback: updateStateField(setProjectionState, "operationFeedback"),
    setLastMessageType: updateStateField(setProjectionState, "lastMessageType"),
    setGraphSurfaceExperiments: updateStateField(setProjectionState, "graphSurfaceExperiments"),
    setArtifactContents: updateStateField(setProjectionState, "artifactContents"),
    setAssistantSessionState: updateStateField(setProjectionState, "assistantSessionState"),
    setAssistantResultStore: updateStateField(setProjectionState, "assistantResultStore"),
  };

  return {
    canvasState,
    setCanvasState,
    canvasSetters,
    projectionState,
    setProjectionState,
    projectionSetters,
    qaTargetNodeIds,
    setQaTargetNodeIds,
    selectionGroupNodeIds,
    setSelectionGroupNodeIds,
    collapsedNodeIds,
    setCollapsedNodeIds,
    invocationExpansionState,
    setInvocationExpansionState,
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

// JCEF 前端与 IntelliJ 后端之间的桥接 API 层。
// 主要职责：
// - 暴露一组类型化的前端调用函数（导入导出、索引请求、助理任务、节点选择等）；
// - 通过 window.linkGraphBridge.sendCommand 把命令转发给后端；
// - 维护"前端就绪"和"快照确认"两类生命周期的握手，未发出请求会在桥接就绪后自动补发；
// - 桥接不可用时给出友好的错误信息，并通过埋点记录失败原因。
import type {
  AnalysisDisplayMode,
  BindingStatus,
  Certainty,
  DraftPatchPreviewSource,
  DiffStatus,
  GraphEditRequest,
  IndexedClassDiagramOptions,
  IndexedClassUsageOptions,
  IndexedReviewGraphOptions,
  IndexedGraphViewportOptions,
  GraphPosition,
  GraphSourceTag,
  LinkGraphBootstrapState,
  LinkGraphEdge,
  LinkGraphNode,
  LinkGraphSnapshotEnvelope,
  NodeType,
  RiskResolutionStatus,
} from "./types";
import { assistantTaskPayload, type AssistantTaskRequest } from "./assistant/assistantBridgeApi";
import { summarizeGraph, traceLinkGraph } from "./debug";
import {
  acknowledgeSnapshot as acknowledgeSnapshotTransport,
  announceFrontendReady as announceFrontendReadyTransport,
  subscribeBootstrap as subscribeBootstrapTransport,
} from "./editorTransport";
export type { AssistantTaskRequest } from "./assistant/assistantBridgeApi";

/** 前端就绪上报给后端的载荷结构。 */
type FrontendReadyPayload = { lastAppliedRevision: number | null };
/** 快照确认上报给后端的载荷结构。 */
type SnapshotAckPayload = { revision: number };
/**
 * 索引图请求的预设参数联合。preset 字段决定预设类型（架构、包依赖、类图、审查），
 * 其余字段按预设类型有条件使用，例如 packageName 仅在 PACKAGE_DEPENDENCY 下有意义。
 */
type IndexedGraphPresetRequest = {
  preset: "ARCHITECTURE" | "PACKAGE_DEPENDENCY" | "CLASS_DIAGRAM" | "REVIEW";
  packageName?: string;
  scopeNodeId?: string | null;
  selectedDiffItemIds?: string[];
  includeExternalLibraries?: boolean;
  includeJdk?: boolean;
  viewport?: IndexedGraphViewportOptions | null;
  classDiagram?: Partial<IndexedClassDiagramOptions> | null;
  usage?: Partial<IndexedClassUsageOptions> | null;
  review?: Partial<IndexedReviewGraphOptions> | null;
};

/** 所有可派发给后端的桥接命令类型字符串。 */
export type BridgeCommandType =
  | "importMermaid"
  | "exportMermaid"
  | "showDiffMode"
  | "requestSyncPreview"
  | "requestAssistantTask"
  | "retryLastQaRequest"
  | "confirmQaCandidateChange"
  | "unconfirmQaCandidateChange"
  | "resolveInvestigationThread"
  | "applyDraftPatchPreview"
  | "clearDraftPatchPreview"
  | "restoreDraftPatchPreview"
  | "undoLastDraftPatchApply"
  | "requestCodeDrafts"
  | "requestCurrentEditorContextGraph"
  | "requestAnalysisDisplayMode"
  | "requestIndexedGraph"
  | "requestOpenSettings"
  | "applyCodeDrafts"
  | "applySingleCodeDraft"
  | "openCodeDraftNativeDiff"
  | "requestDraftNavigation"
  | "requestArtifact"
  | "frontendReady"
  | "snapshotAck"
  | "nodeSelected"
  | "layoutChanged"
  | "requestSourceNavigation"
  | "requestExpandOverflowNode"
  | "requestExpandInvocation"
  | "requestRemoveInvocationExpansion"
  | "applyGraphEditScript";

/** 桥接命令信封：固定 schemaVersion + type + 弱类型 payload。 */
export type BridgeCommandEnvelope = {
  schemaVersion: 1;
  type: BridgeCommandType;
  payload: Record<string, unknown>;
};

/** 桥接调用的统一返回类型：成功为 ok=true；失败附带面向用户的 message 与详细 detailMessage。 */
export type BridgeInvocationResult =
  | { ok: true }
  | {
      ok: false;
      message: string;
      detailMessage: string;
    };

/** 桥接生命周期中尚未发出的握手请求暂存。 */
type PendingBridgeLifecycleState = {
  /** 待发的"前端就绪"载荷；桥接就绪后会自动补发。 */
  frontendReady: FrontendReadyPayload | null;
  /** 待发的"快照确认"载荷；桥接就绪后会自动补发。 */
  snapshotAck: SnapshotAckPayload | null;
};

// 模块级暂存状态：在桥接尚未注入时保存握手请求，避免丢失初始化信息
const pendingBridgeLifecycleState: PendingBridgeLifecycleState = {
  frontendReady: null,
  snapshotAck: null,
};

/** 标记是否已注册"桥接就绪"事件监听，避免重复注册。 */
let bridgeReadyListenerInstalled = false;
/** 桥接尚未就绪时的统一提示文案，强调"请求未发出"而非"失败"。 */
const BRIDGE_UNAVAILABLE_MESSAGE = "IDE bridge 尚未就绪，本次请求没有发出。";
/** 桥接协议未对齐（缺少 sendCommand 方法）时的提示文案。 */
const BRIDGE_PROTOCOL_MISMATCH_MESSAGE = "IDE bridge 协议未对齐，本次请求没有发出。";
/** 桥接不可用的详细解释，提示用户等待页面初始化完成。 */
const BRIDGE_UNAVAILABLE_DETAIL_MESSAGE = "JCEF 页面与 IDEA 后端连接尚未建立，请等待页面初始化完成后重试。";

// JCEF 页面的唯一后端入口：读取 bootstrap，并把前端交互重新发布给 IDEA bridge。
declare global {
  /** 把项目自定义事件加入 WindowEventMap，获得类型补全。 */
  interface WindowEventMap {
    "link-graph-bootstrap": CustomEvent<
      LinkGraphSnapshotEnvelope | import("./types").LinkGraphIncrementalTransportEnvelope
    >;
    "link-graph-bridge-ready": Event;
  }

  /** 在 window 上挂载桥接对象与最新引导态，供前端任意位置读取。 */
  interface Window {
    linkGraphBridge?: {
      /** 后端注入的命令派发函数；缺失代表协议未对齐。 */
      sendCommand?: (command: BridgeCommandEnvelope) => void;
    };
    /** 后端同步写入的最新引导态快照，可作为初始化数据源。 */
    linkGraphBootstrap?: LinkGraphBootstrapState;
  }
}

/** 注册"桥接就绪"事件监听，触发暂存状态尝试补发。 */
function ensureBridgeReadyListener(): void {
  if (bridgeReadyListenerInstalled || typeof window === "undefined") {
    return;
  }
  window.addEventListener("link-graph-bridge-ready", flushPendingBridgeState);
  bridgeReadyListenerInstalled = true;
}

/** 事件回调：桥接就绪时尝试把暂存的握手请求补发出去。 */
function flushPendingBridgeState(): void {
  flushPendingBridgeLifecycleState();
}

/**
 * 把暂存的生命周期请求依次尝试补发。
 * 仅当 linkGraphBridge 已经注入时才会真正派发；成功发出后从暂存中清空。
 */
function flushPendingBridgeLifecycleState(): void {
  if (!window.linkGraphBridge) {
    return;
  }
  const frontendReadyPayload = pendingBridgeLifecycleState.frontendReady;
  if (
    frontendReadyPayload &&
    dispatchBridgeCommandIfAvailable("frontendReady", frontendReadyPayload)
  ) {
    pendingBridgeLifecycleState.frontendReady = null;
  }
  const snapshotAckPayload = pendingBridgeLifecycleState.snapshotAck;
  if (
    snapshotAckPayload &&
    dispatchBridgeCommandIfAvailable("snapshotAck", snapshotAckPayload)
  ) {
    pendingBridgeLifecycleState.snapshotAck = null;
  }
}

/**
 * 上报"前端就绪"。先尝试立即派发；若桥接尚未就绪则暂存，等待就绪事件触发后补发。
 */
function dispatchFrontendReady(payload: FrontendReadyPayload): void {
  ensureBridgeReadyListener();
  if (dispatchBridgeCommandIfAvailable("frontendReady", payload)) {
    pendingBridgeLifecycleState.frontendReady = null;
    return;
  }
  pendingBridgeLifecycleState.frontendReady = payload;
}

/**
 * 上报"快照确认"。先尝试立即派发；若桥接尚未就绪则暂存。
 */
function dispatchSnapshotAck(payload: SnapshotAckPayload): void {
  ensureBridgeReadyListener();
  if (dispatchBridgeCommandIfAvailable("snapshotAck", payload)) {
    pendingBridgeLifecycleState.snapshotAck = null;
    return;
  }
  pendingBridgeLifecycleState.snapshotAck = payload;
}

/**
 * 把命令通过桥接派发给后端。返回是否真正发出：
 * - 桥接未注入或未暴露 sendCommand 时返回 false；
 * - 否则调用 sendCommand 并返回 true。
 */
function dispatchBridgeCommandIfAvailable(
  commandType: BridgeCommandType,
  commandPayload: Record<string, unknown>,
): boolean {
  const bridge = window.linkGraphBridge;
  if (typeof bridge?.sendCommand !== "function") {
    return false;
  }
  bridge.sendCommand({
    schemaVersion: 1,
    type: commandType,
    payload: commandPayload,
  });
  return true;
}

/**
 * 派发任意桥接命令的统一入口。
 *
 * 流程：
 * 1) 桥接未注入：写埋点后返回"未就绪"错误；
 * 2) 桥接已注入：派发命令；成功返回 ok=true；
 * 3) 桥接已注入但缺少 sendCommand：返回"协议未对齐"错误。
 *
 * 通过 tracePayload 与 commandPayload 双参数，让埋点载荷与实际派发载荷可以分别构造。
 */
function invokeBridgeAction(
  commandType: BridgeCommandType,
  tracePayload?: unknown,
  commandPayload: Record<string, unknown> = {},
): BridgeInvocationResult {
  // 桥接完全不可用：构造用户可读错误，避免直接抛异常
  if (!window.linkGraphBridge) {
    traceLinkGraph("api.bridgePending", {
      actionName: commandType,
      hasBridge: false,
    });
    return {
      ok: false,
      message: BRIDGE_UNAVAILABLE_MESSAGE,
      detailMessage: BRIDGE_UNAVAILABLE_DETAIL_MESSAGE,
    };
  }
  if (tracePayload !== undefined) {
    traceLinkGraph(`api.${commandType}`, tracePayload);
  }
  if (dispatchBridgeCommandIfAvailable(commandType, commandPayload)) {
    return { ok: true };
  }
  // 桥接对象存在但缺少统一入口，提示协议未对齐
  const detailMessage = "IDE bridge 已注入，但当前未暴露统一 sendCommand 方法，本次请求没有发出。";
  traceLinkGraph("api.bridgeProtocolMismatch", {
    actionName: commandType,
    hasBridge: true,
  });
  return {
    ok: false,
    message: BRIDGE_PROTOCOL_MISMATCH_MESSAGE,
    detailMessage,
  };
}

/** 读取 window.linkGraphBootstrap 上的最新引导态快照，未注入时返回 null。 */
export function readBootstrapState(): LinkGraphBootstrapState | null {
  return window.linkGraphBootstrap ?? null;
}

/**
 * 订阅引导态更新。委托给 editorTransport 的同名函数，
 * 本文件只做透出，避免上层重复依赖 editorTransport 模块。
 */
export function subscribeBootstrap(
  listener: Parameters<typeof subscribeBootstrapTransport>[0],
): ReturnType<typeof subscribeBootstrapTransport> {
  return subscribeBootstrapTransport(listener);
}

/** 声明前端就绪并上报已应用到的最后 revision，由 editorTransport 转交后端。 */
export function announceFrontendReady(lastAppliedRevision?: number): void {
  announceFrontendReadyTransport(lastAppliedRevision, (payload) => {
    dispatchFrontendReady(payload);
  });
}

/** 确认某 revision 的快照已被前端处理，由 editorTransport 转交后端。 */
export function acknowledgeSnapshot(revision: number): void {
  acknowledgeSnapshotTransport(revision, (nextRevision) => {
    dispatchSnapshotAck({
      revision: nextRevision,
    });
  });
}

/** 测试辅助：重置暂存的生命周期请求，避免跨用例污染。 */
export function resetApiBridgeLifecycleStateForTest(): void {
  pendingBridgeLifecycleState.frontendReady = null;
  pendingBridgeLifecycleState.snapshotAck = null;
}

/** 把一段 Mermaid 文本导入为图文档。 */
export function importMermaid(mermaid: string): BridgeInvocationResult {
  return invokeBridgeAction("importMermaid", undefined, { mermaid });
}

/** 把当前图文档导出为 Mermaid 文本。 */
export function exportMermaid(): BridgeInvocationResult {
  return invokeBridgeAction("exportMermaid");
}

/** 切换到差异比对模式。 */
export function showDiffMode(): BridgeInvocationResult {
  return invokeBridgeAction("showDiffMode");
}

/** 请求后端加载指定的产物内容（按产物 ID 列表）。 */
export function requestArtifactContent(artifactIds: string[]): BridgeInvocationResult {
  return invokeBridgeAction("requestArtifact", { artifactIds }, { artifactIds });
}

/** 请求后端生成图与代码之间的同步预览。 */
export function requestSyncPreview(): BridgeInvocationResult {
  return invokeBridgeAction("requestSyncPreview");
}

/** 提交一个助理任务（解释、问答、生成等），载荷由 assistantBridgeApi 组装。 */
export function requestAssistantTask(request: AssistantTaskRequest): BridgeInvocationResult {
  const payload = assistantTaskPayload(request);
  return invokeBridgeAction("requestAssistantTask", payload, payload);
}

/** 重试上一次 QA 请求（异步，无返回值）。 */
export function retryLastQaRequestAsync(): BridgeInvocationResult {
  return invokeBridgeAction("retryLastQaRequest");
}

/** 确认某个 QA 候选变更（用户采纳）。 */
export function confirmQaCandidateChange(changeId: string): BridgeInvocationResult {
  return invokeBridgeAction("confirmQaCandidateChange", { changeId }, { changeId });
}

/** 取消确认某个 QA 候选变更（用户撤销采纳）。 */
export function unconfirmQaCandidateChange(changeId: string): BridgeInvocationResult {
  return invokeBridgeAction("unconfirmQaCandidateChange", { changeId }, { changeId });
}

/**
 * 化解（或标记）一条调查线程，附带用户填写的备注。
 * 调查线程代表尚未解决的代码风险点，调用本函数后该线程会被标记为已化解。
 */
export function resolveInvestigationThread(
  threadId: string,
  resolutionStatus: RiskResolutionStatus,
  note = "",
): BridgeInvocationResult {
  return invokeBridgeAction("resolveInvestigationThread", {
    threadId,
    resolutionStatus,
    note,
  }, {
    threadId,
    resolutionStatus,
    note,
  });
}

/**
 * 应用草稿补丁预览。operationIds 为空数组或省略时表示应用全部操作；
 * 否则只应用指定 ID 的操作。
 */
export function applyDraftPatchPreview(operationIds?: string[]): BridgeInvocationResult {
  return invokeBridgeAction("applyDraftPatchPreview", undefined, { operationIds: operationIds ?? [] });
}

/** 清空当前的草稿补丁预览，不应用任何变更。 */
export function clearDraftPatchPreview(): BridgeInvocationResult {
  return invokeBridgeAction("clearDraftPatchPreview");
}

/** 从指定来源恢复草稿补丁预览（例如从历史记录回溯）。 */
export function restoreDraftPatchPreview(source: DraftPatchPreviewSource): BridgeInvocationResult {
  return invokeBridgeAction("restoreDraftPatchPreview", undefined, { source });
}

/** 撤销最近一次应用的草稿补丁。 */
export function undoLastDraftPatchApply(): BridgeInvocationResult {
  return invokeBridgeAction("undoLastDraftPatchApply");
}

/** 异步请求生成代码草稿（diff）。 */
export function requestCodeDraftsAsync(): BridgeInvocationResult {
  return invokeBridgeAction("requestCodeDrafts");
}

// 兼容别名：让历史调用代码继续使用同步命名风格
export const retryLastQaRequest = retryLastQaRequestAsync;
export const requestCodeDrafts = requestCodeDraftsAsync;

/** 请求加载当前活动编辑器对应的上下文图。 */
export function requestCurrentEditorContextGraph(): BridgeInvocationResult {
  traceLinkGraph("api.requestCurrentEditorContextGraph");
  return invokeBridgeAction("requestCurrentEditorContextGraph");
}

/** 切换到指定的分析展示模式（事实/流程/架构/类图/审查等）。 */
export function requestAnalysisDisplayMode(displayMode: AnalysisDisplayMode): BridgeInvocationResult {
  return invokeBridgeAction("requestAnalysisDisplayMode", undefined, { displayMode });
}

/** 通用入口：根据预设参数请求加载索引图（架构/包依赖/类图/审查）。 */
export function requestIndexedGraph(request: IndexedGraphPresetRequest): BridgeInvocationResult {
  return invokeBridgeAction("requestIndexedGraph", { request }, request);
}

/** 架构图请求的便捷封装：把可选项合并为 ARCHITECTURE 预设。 */
export function requestArchitectureGraph(options: {
  includeExternalLibraries?: boolean;
  includeJdk?: boolean;
  viewport?: IndexedGraphViewportOptions | null;
} = {}): BridgeInvocationResult {
  return requestIndexedGraph(definedPayload({
    preset: "ARCHITECTURE",
    includeExternalLibraries: options.includeExternalLibraries,
    includeJdk: options.includeJdk,
    viewport: options.viewport ?? undefined,
  }));
}

/** 包依赖图请求的便捷封装：基于指定包名生成依赖图。 */
export function requestPackageDependencyGraph(packageName?: string | null, options: {
  includeExternalLibraries?: boolean;
  includeJdk?: boolean;
  viewport?: IndexedGraphViewportOptions | null;
} = {}): BridgeInvocationResult {
  return requestIndexedGraph(definedPayload({
    preset: "PACKAGE_DEPENDENCY",
    packageName: (packageName ?? "").trim(),
    includeExternalLibraries: options.includeExternalLibraries,
    includeJdk: options.includeJdk,
    viewport: options.viewport ?? undefined,
  }));
}

/** 类图请求的便捷封装：以某个节点为锚点生成类图，可配置邻域与成员上限。 */
export function requestClassDiagram(scopeNodeId?: string | null, options: {
  classDiagram?: Partial<IndexedClassDiagramOptions> | null;
  viewport?: IndexedGraphViewportOptions | null;
} = {}): BridgeInvocationResult {
  // 空白字符串与 null 统一化为 null，便于后端判定"无锚点"
  const normalizedScopeNodeId = scopeNodeId?.trim() || null;
  return requestIndexedGraph(definedPayload({
    preset: "CLASS_DIAGRAM",
    scopeNodeId: normalizedScopeNodeId,
    viewport: options.viewport ?? undefined,
    classDiagram: definedPayload({
      neighborhoodLimit: options.classDiagram?.neighborhoodLimit,
      memberLimit: options.classDiagram?.memberLimit,
      relationDetail: options.classDiagram?.relationDetail,
    }),
  }));
}

/** 类使用关系图请求：基于某个目标类节点，展示其被使用的位置集合。 */
export function requestClassUsages(targetNodeId: string, options: {
  scopeNodeId?: string | null;
  targetQualifiedName?: string | null;
  sourceVirtualFileUrl?: string | null;
  sourcePath?: string | null;
  maxUsageGroups?: number | null;
  maxUsageEntries?: number | null;
  includeImports?: boolean | null;
  viewport?: IndexedGraphViewportOptions | null;
} = {}): BridgeInvocationResult {
  // 节点 ID 去除前后空白，避免粘贴时引入空格导致定位失败
  const normalizedTargetNodeId = targetNodeId.trim();
  return requestIndexedGraph(definedPayload({
    preset: "CLASS_DIAGRAM",
    scopeNodeId: options.scopeNodeId?.trim() || undefined,
    viewport: options.viewport ?? undefined,
    usage: definedPayload({
      enabled: true,
      targetNodeId: normalizedTargetNodeId,
      targetQualifiedName: options.targetQualifiedName ?? undefined,
      sourceVirtualFileUrl: options.sourceVirtualFileUrl ?? undefined,
      sourcePath: options.sourcePath ?? undefined,
      maxUsageGroups: options.maxUsageGroups ?? undefined,
      maxUsageEntries: options.maxUsageEntries ?? undefined,
      includeImports: options.includeImports ?? undefined,
    }),
  }));
}

/** 审查图请求：基于一组差异项生成用于代码审查的视图。 */
export function requestReviewGraph(selectedDiffItemIds: string[] = [], options: {
  review?: Partial<IndexedReviewGraphOptions> | null;
  viewport?: IndexedGraphViewportOptions | null;
} = {}): BridgeInvocationResult {
  return requestIndexedGraph(definedPayload({
    preset: "REVIEW",
    selectedDiffItemIds,
    viewport: options.viewport ?? undefined,
    review: definedPayload({
      maxChangedNodes: options.review?.maxChangedNodes,
      maxRelatedTestNodes: options.review?.maxRelatedTestNodes,
      maxUpstreamNodes: options.review?.maxUpstreamNodes,
      maxDownstreamNodes: options.review?.maxDownstreamNodes,
    }),
  }));
}

/** 打开插件设置面板。 */
export function requestOpenSettings(): BridgeInvocationResult {
  return invokeBridgeAction("requestOpenSettings");
}

/** 应用当前所有的代码草稿（批量写入）。 */
export function applyCodeDrafts(): BridgeInvocationResult {
  return invokeBridgeAction("applyCodeDrafts");
}

/** 应用单条代码草稿（按草稿 ID）。 */
export function applySingleCodeDraft(draftId: string): BridgeInvocationResult {
  return invokeBridgeAction("applySingleCodeDraft", undefined, { draftId });
}

/** 在 IDE 原生 diff 视图中打开某条代码草稿。 */
export function openCodeDraftNativeDiff(draftId: string): BridgeInvocationResult {
  return invokeBridgeAction("openCodeDraftNativeDiff", undefined, { draftId });
}

/** 上报节点被选中事件，让后端同步选中状态、相关面板联动。 */
export function publishNodeSelected(nodeId: string): BridgeInvocationResult {
  traceLinkGraph("api.publishNodeSelected", { nodeId });
  return invokeBridgeAction("nodeSelected", undefined, { nodeId });
}

/** 请求跳转到指定节点的源码位置。 */
export function requestSourceNavigation(nodeId: string): BridgeInvocationResult {
  traceLinkGraph("api.requestSourceNavigation", { nodeId });
  return invokeBridgeAction("requestSourceNavigation", undefined, { nodeId });
}

/** 请求跳转到指定路径的代码草稿。 */
export function requestDraftNavigation(targetPath: string): BridgeInvocationResult {
  return invokeBridgeAction("requestDraftNavigation", undefined, { targetPath });
}

/** 请求展开某个被聚合（折叠）的节点，让其中合并的子节点重新可见。 */
export function requestExpandOverflowNode(nodeId: string): BridgeInvocationResult {
  return invokeBridgeAction("requestExpandOverflowNode", undefined, { nodeId });
}

/** 请求展开某节点的调用展开（展开其内部调用结构）。 */
export function requestExpandInvocation(nodeId: string): BridgeInvocationResult {
  return invokeBridgeAction("requestExpandInvocation", { nodeId }, { nodeId });
}

/** 请求移除某次调用展开（按展开 ID 撤销）。 */
export function requestRemoveInvocationExpansion(expansionId: string): BridgeInvocationResult {
  return invokeBridgeAction("requestRemoveInvocationExpansion", { expansionId }, { expansionId });
}

/**
 * 提交图编辑脚本。把图编辑请求按桥接协议重新打包为后端可识别的结构，
 * 字段名做相应转换（source→fromNodeId、target→toNodeId 等），
 * 并对节点元数据做过滤（剔除 ui./layout. 前缀的纯前端字段）。
 */
export function publishGraphEditRequest(request: GraphEditRequest): BridgeInvocationResult {
  const payload = {
    sceneId: request.sceneId,
    baseWorkspaceRevision: request.baseWorkspaceRevision,
    source: request.source,
    operations: request.operations.map((operation) => {
      switch (operation.type) {
        case "UPSERT_NODE":
          // 节点 upsert：把节点字段按协议展开，并过滤纯前端元数据
          return {
            type: operation.type,
            node: {
              id: operation.node.id,
              type: operation.node.type,
              title: operation.node.title,
              location: operation.node.location,
              signature: operation.node.signature,
              inputs: operation.node.inputs ?? [],
              outputs: operation.node.outputs ?? [],
              doc: operation.node.doc,
              certainty: operation.node.certainty,
              bindingStatus: operation.node.bindingStatus,
              metadata: buildNodeMetadata(operation.node),
              sourceTag: operation.node.sourceTag,
            },
          };
        case "REMOVE_NODE":
          return {
            type: operation.type,
            nodeId: operation.nodeId,
          };
        case "UPSERT_EDGE":
          // 边 upsert：把 source/target 重命名为后端协议的 fromNodeId/toNodeId
          return {
            type: operation.type,
            edge: {
              id: operation.edge.id,
              type: operation.edge.type,
              fromNodeId: operation.edge.source,
              toNodeId: operation.edge.target,
              label: operation.edge.label,
              metadata: operation.edge.metadata,
              sourceTag: operation.edge.sourceTag,
            },
          };
        case "REMOVE_EDGE":
          return {
            type: operation.type,
            edgeId: operation.edgeId,
          };
      }
    }),
  };
  traceLinkGraph("api.publishGraphEditRequest", {
    sceneId: request.sceneId,
    baseWorkspaceRevision: request.baseWorkspaceRevision,
    source: request.source,
    operationCount: request.operations.length,
  });
  return invokeBridgeAction("applyGraphEditScript", payload, payload);
}

/**
 * 上报节点布局变化。把若干节点的最新坐标同步给后端，触发持久化与协同更新。
 */
export function publishLayoutChange(
  positions: Array<{
    nodeId: string;
    x: number;
    y: number;
  }>,
): void {
  traceLinkGraph("api.publishLayoutChange", {
    nodeIds: positions.map((position) => position.nodeId),
  });
  invokeBridgeAction("layoutChanged", undefined, { positions });
}

/**
 * 过滤节点元数据：剔除 ui./layout. 前缀的字段，
 * 因为这些是前端专用字段（如临时高亮、布局缓存），不应进入后端持久化。
 */
function buildNodeMetadata(node: LinkGraphNode): Record<string, string> {
  return Object.fromEntries(
    Object.entries(node.metadata ?? {}).filter(([key]) => !key.startsWith("ui.") && !key.startsWith("layout.")),
  );
}

/**
 * 工具函数：从任意载荷对象中剔除"无效"字段。
 * 无效定义：undefined、null，或内容为空的非数组对象。空数组与基本类型保留。
 * 用于在派发给后端前精简载荷，避免无意义字段干扰协议解析。
 */
function definedPayload<T extends Record<string, unknown>>(payload: T): T {
  return Object.fromEntries(
    Object.entries(payload).filter(([, value]) => {
      if (value === undefined || value === null) {
        return false;
      }
      // 空对象视为无效（避免传 {} 进后端），空数组保留（语义可能是"无选中"）
      return !(typeof value === "object" && !Array.isArray(value) && Object.keys(value).length === 0);
    }),
  ) as T;
}

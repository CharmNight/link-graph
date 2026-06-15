import type {
  AnalysisDisplayMode,
  BindingStatus,
  Certainty,
  DraftPatchPreviewSource,
  DiffStatus,
  GraphEditScript,
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

type FrontendReadyPayload = { lastAppliedRevision: number | null };
type SnapshotAckPayload = { revision: number };
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
export type BridgeCommandEnvelope = {
  schemaVersion: 1;
  type: BridgeCommandType;
  payload: Record<string, unknown>;
};

export type BridgeInvocationResult =
  | { ok: true }
  | {
      ok: false;
      message: string;
      detailMessage: string;
    };

type PendingBridgeLifecycleState = {
  frontendReady: FrontendReadyPayload | null;
  snapshotAck: SnapshotAckPayload | null;
};

const pendingBridgeLifecycleState: PendingBridgeLifecycleState = {
  frontendReady: null,
  snapshotAck: null,
};

let bridgeReadyListenerInstalled = false;
const BRIDGE_UNAVAILABLE_MESSAGE = "IDE bridge 尚未就绪，本次请求没有发出。";
const BRIDGE_PROTOCOL_MISMATCH_MESSAGE = "IDE bridge 协议未对齐，本次请求没有发出。";
const BRIDGE_UNAVAILABLE_DETAIL_MESSAGE = "JCEF 页面与 IDEA 后端连接尚未建立，请等待页面初始化完成后重试。";

// JCEF 页面的唯一后端入口：读取 bootstrap，并把前端交互重新发布给 IDEA bridge。
declare global {
  interface WindowEventMap {
    "link-graph-bootstrap": CustomEvent<
      LinkGraphSnapshotEnvelope | import("./types").LinkGraphIncrementalTransportEnvelope
    >;
    "link-graph-bridge-ready": Event;
  }

  interface Window {
    linkGraphBridge?: {
      sendCommand?: (command: BridgeCommandEnvelope) => void;
    };
    linkGraphBootstrap?: LinkGraphBootstrapState;
  }
}

function ensureBridgeReadyListener(): void {
  if (bridgeReadyListenerInstalled || typeof window === "undefined") {
    return;
  }
  window.addEventListener("link-graph-bridge-ready", flushPendingBridgeState);
  bridgeReadyListenerInstalled = true;
}

function flushPendingBridgeState(): void {
  flushPendingBridgeLifecycleState();
}

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

function dispatchFrontendReady(payload: FrontendReadyPayload): void {
  ensureBridgeReadyListener();
  if (dispatchBridgeCommandIfAvailable("frontendReady", payload)) {
    pendingBridgeLifecycleState.frontendReady = null;
    return;
  }
  pendingBridgeLifecycleState.frontendReady = payload;
}

function dispatchSnapshotAck(payload: SnapshotAckPayload): void {
  ensureBridgeReadyListener();
  if (dispatchBridgeCommandIfAvailable("snapshotAck", payload)) {
    pendingBridgeLifecycleState.snapshotAck = null;
    return;
  }
  pendingBridgeLifecycleState.snapshotAck = payload;
}

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

function invokeBridgeAction(
  commandType: BridgeCommandType,
  tracePayload?: unknown,
  commandPayload: Record<string, unknown> = {},
): BridgeInvocationResult {
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

export function readBootstrapState(): LinkGraphBootstrapState | null {
  return window.linkGraphBootstrap ?? null;
}

export function subscribeBootstrap(
  listener: Parameters<typeof subscribeBootstrapTransport>[0],
): ReturnType<typeof subscribeBootstrapTransport> {
  return subscribeBootstrapTransport(listener);
}

export function announceFrontendReady(lastAppliedRevision?: number): void {
  announceFrontendReadyTransport(lastAppliedRevision, (payload) => {
    dispatchFrontendReady(payload);
  });
}

export function acknowledgeSnapshot(revision: number): void {
  acknowledgeSnapshotTransport(revision, (nextRevision) => {
    dispatchSnapshotAck({
      revision: nextRevision,
    });
  });
}

export function resetApiBridgeLifecycleStateForTest(): void {
  pendingBridgeLifecycleState.frontendReady = null;
  pendingBridgeLifecycleState.snapshotAck = null;
}

export function importMermaid(mermaid: string): BridgeInvocationResult {
  return invokeBridgeAction("importMermaid", undefined, { mermaid });
}

export function exportMermaid(): BridgeInvocationResult {
  return invokeBridgeAction("exportMermaid");
}

export function showDiffMode(): BridgeInvocationResult {
  return invokeBridgeAction("showDiffMode");
}

export function requestArtifactContent(artifactIds: string[]): BridgeInvocationResult {
  return invokeBridgeAction("requestArtifact", { artifactIds }, { artifactIds });
}

export function requestSyncPreview(): BridgeInvocationResult {
  return invokeBridgeAction("requestSyncPreview");
}

export function requestAssistantTask(request: AssistantTaskRequest): BridgeInvocationResult {
  const payload = assistantTaskPayload(request);
  return invokeBridgeAction("requestAssistantTask", payload, payload);
}

export function retryLastQaRequestAsync(): BridgeInvocationResult {
  return invokeBridgeAction("retryLastQaRequest");
}

export function confirmQaCandidateChange(changeId: string): BridgeInvocationResult {
  return invokeBridgeAction("confirmQaCandidateChange", { changeId }, { changeId });
}

export function unconfirmQaCandidateChange(changeId: string): BridgeInvocationResult {
  return invokeBridgeAction("unconfirmQaCandidateChange", { changeId }, { changeId });
}

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

export function applyDraftPatchPreview(operationIds?: string[]): BridgeInvocationResult {
  return invokeBridgeAction("applyDraftPatchPreview", undefined, { operationIds: operationIds ?? [] });
}

export function clearDraftPatchPreview(): BridgeInvocationResult {
  return invokeBridgeAction("clearDraftPatchPreview");
}

export function restoreDraftPatchPreview(source: DraftPatchPreviewSource): BridgeInvocationResult {
  return invokeBridgeAction("restoreDraftPatchPreview", undefined, { source });
}

export function undoLastDraftPatchApply(): BridgeInvocationResult {
  return invokeBridgeAction("undoLastDraftPatchApply");
}

export function requestCodeDraftsAsync(): BridgeInvocationResult {
  return invokeBridgeAction("requestCodeDrafts");
}

export const retryLastQaRequest = retryLastQaRequestAsync;
export const requestCodeDrafts = requestCodeDraftsAsync;

export function requestCurrentEditorContextGraph(): BridgeInvocationResult {
  traceLinkGraph("api.requestCurrentEditorContextGraph");
  return invokeBridgeAction("requestCurrentEditorContextGraph");
}

export function requestAnalysisDisplayMode(displayMode: AnalysisDisplayMode): BridgeInvocationResult {
  return invokeBridgeAction("requestAnalysisDisplayMode", undefined, { displayMode });
}

export function requestIndexedGraph(request: IndexedGraphPresetRequest): BridgeInvocationResult {
  return invokeBridgeAction("requestIndexedGraph", { request }, request);
}

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

export function requestClassDiagram(scopeNodeId?: string | null, options: {
  classDiagram?: Partial<IndexedClassDiagramOptions> | null;
  viewport?: IndexedGraphViewportOptions | null;
} = {}): BridgeInvocationResult {
  const normalizedScopeNodeId = scopeNodeId?.trim() || null;
  return requestIndexedGraph(definedPayload({
    preset: "CLASS_DIAGRAM",
    scopeNodeId: normalizedScopeNodeId,
    viewport: options.viewport ?? undefined,
    classDiagram: definedPayload({
      neighborhoodLimit: options.classDiagram?.neighborhoodLimit,
      memberLimit: options.classDiagram?.memberLimit,
    }),
  }));
}

export function requestClassUsages(targetNodeId: string, options: {
  targetQualifiedName?: string | null;
  sourceVirtualFileUrl?: string | null;
  sourcePath?: string | null;
  maxUsageGroups?: number | null;
  maxUsageEntries?: number | null;
  includeImports?: boolean | null;
  viewport?: IndexedGraphViewportOptions | null;
} = {}): BridgeInvocationResult {
  const normalizedTargetNodeId = targetNodeId.trim();
  return requestIndexedGraph(definedPayload({
    preset: "CLASS_DIAGRAM",
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

export function requestOpenSettings(): BridgeInvocationResult {
  return invokeBridgeAction("requestOpenSettings");
}

export function applyCodeDrafts(): BridgeInvocationResult {
  return invokeBridgeAction("applyCodeDrafts");
}

export function applySingleCodeDraft(draftId: string): BridgeInvocationResult {
  return invokeBridgeAction("applySingleCodeDraft", undefined, { draftId });
}

export function openCodeDraftNativeDiff(draftId: string): BridgeInvocationResult {
  return invokeBridgeAction("openCodeDraftNativeDiff", undefined, { draftId });
}

export function publishNodeSelected(nodeId: string): BridgeInvocationResult {
  traceLinkGraph("api.publishNodeSelected", { nodeId });
  return invokeBridgeAction("nodeSelected", undefined, { nodeId });
}

export function requestSourceNavigation(nodeId: string): BridgeInvocationResult {
  traceLinkGraph("api.requestSourceNavigation", { nodeId });
  return invokeBridgeAction("requestSourceNavigation", undefined, { nodeId });
}

export function requestDraftNavigation(targetPath: string): BridgeInvocationResult {
  return invokeBridgeAction("requestDraftNavigation", undefined, { targetPath });
}

export function requestExpandOverflowNode(nodeId: string): BridgeInvocationResult {
  return invokeBridgeAction("requestExpandOverflowNode", undefined, { nodeId });
}

export function requestExpandInvocation(nodeId: string): BridgeInvocationResult {
  return invokeBridgeAction("requestExpandInvocation", { nodeId }, { nodeId });
}

export function requestRemoveInvocationExpansion(expansionId: string): BridgeInvocationResult {
  return invokeBridgeAction("requestRemoveInvocationExpansion", { expansionId }, { expansionId });
}

export function publishGraphEditScript(script: GraphEditScript): BridgeInvocationResult {
  const payload = {
    sceneId: script.sceneId,
    baseWorkspaceRevision: script.baseWorkspaceRevision,
    operations: script.operations.map((operation) => {
      switch (operation.type) {
        case "UPSERT_NODE":
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
  traceLinkGraph("api.publishGraphEditScript", {
    sceneId: script.sceneId,
    baseWorkspaceRevision: script.baseWorkspaceRevision,
    operationCount: script.operations.length,
  });
  return invokeBridgeAction("applyGraphEditScript", payload, payload);
}

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

function buildNodeMetadata(node: LinkGraphNode): Record<string, string> {
  return Object.fromEntries(
    Object.entries(node.metadata ?? {}).filter(([key]) => !key.startsWith("ui.") && !key.startsWith("layout.")),
  );
}

function definedPayload<T extends Record<string, unknown>>(payload: T): T {
  return Object.fromEntries(
    Object.entries(payload).filter(([, value]) => {
      if (value === undefined || value === null) {
        return false;
      }
      return !(typeof value === "object" && !Array.isArray(value) && Object.keys(value).length === 0);
    }),
  ) as T;
}

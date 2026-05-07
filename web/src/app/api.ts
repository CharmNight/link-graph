import type {
  AnalysisDisplayMode,
  BindingStatus,
  Certainty,
  DraftPatchPreviewSource,
  DiffStatus,
  GraphEditScript,
  GraphBeautificationRequest,
  GraphPosition,
  GraphSourceTag,
  LinkGraphBootstrapState,
  LinkGraphEdge,
  LinkGraphNode,
  LinkGraphSnapshotEnvelope,
  NodeType,
  QaMode,
  RiskResolutionStatus,
  StepGranularity,
} from "./types";
import { summarizeGraph, traceLinkGraph } from "./debug";
import {
  acknowledgeSnapshot as acknowledgeSnapshotTransport,
  announceFrontendReady as announceFrontendReadyTransport,
  subscribeBootstrap as subscribeBootstrapTransport,
} from "./editorTransport";

type FrontendReadyPayload = { lastAppliedRevision: number | null };
type SnapshotAckPayload = { revision: number };
type Bridge = NonNullable<Window["linkGraphBridge"]>;
type BridgeMethodName = keyof Bridge;

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
      importMermaid?: (mermaid: string) => void;
      exportMermaid?: () => void;
      showDiffMode?: () => void;
      requestSyncPreview?: () => void;
      requestAudit?: (question: string, selectedNodeIds?: string[], sourceThreadId?: string | null, mode?: QaMode) => void;
      retryLastAuditRequest?: () => void;
      confirmAuditCandidateChange?: (changeId: string) => void;
      unconfirmAuditCandidateChange?: (changeId: string) => void;
      resolveInvestigationThread?: (threadId: string, resolutionStatus: RiskResolutionStatus, note?: string) => void;
      requestDiffReview?: (question: string, selectedDiffItemIds?: string[]) => void;
      requestGraphBeautification?: (
        goal?: string,
        preferredStyle?: string | null,
        explanationFocus?: string | null,
        granularity?: StepGranularity,
        followUpStepId?: string,
        followUpStepTitle?: string,
        followUpQuestion?: string,
      ) => void;
      applyDraftPatchPreview?: (operationIds?: string[]) => void;
      clearDraftPatchPreview?: () => void;
      restoreDraftPatchPreview?: (source: DraftPatchPreviewSource) => void;
      undoLastDraftPatchApply?: () => void;
      requestGenerationPlan?: () => void;
      requestGenerationPlanDiscussion?: (question: string, focusItemId?: string | null) => void;
      requestCodeDrafts?: () => void;
      requestCurrentEditorContextGraph?: () => void;
      requestAnalysisDisplayMode?: (displayMode: AnalysisDisplayMode) => void;
      updateWorkbenchSectionPreference?: (sectionId: string, expanded: boolean) => void;
      requestOpenSettings?: () => void;
      applyCodeDrafts?: () => void;
      applySingleCodeDraft?: (draftId: string) => void;
      openCodeDraftNativeDiff?: (draftId: string) => void;
      requestArtifact?: (artifactIds: string[]) => void;
      applyGraphEditScript?: (payload: unknown) => void;
      frontendReady?: (payload: { lastAppliedRevision: number | null }) => void;
      snapshotAck?: (payload: { revision: number }) => void;
      layoutChanged?: (payload: {
        positions: Array<{
          nodeId: string;
          x: number;
          y: number;
        }>;
      }) => void;
      nodeSelected?: (nodeId: string) => void;
      requestSourceNavigation?: (nodeId: string) => void;
      requestDraftNavigation?: (targetPath: string) => void;
      requestExpandOverflowNode?: (nodeId: string) => void;
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
  const bridge = window.linkGraphBridge;
  if (!bridge) {
    return;
  }
  if (pendingBridgeLifecycleState.frontendReady) {
    bridge.frontendReady?.(pendingBridgeLifecycleState.frontendReady);
    pendingBridgeLifecycleState.frontendReady = null;
  }
  if (pendingBridgeLifecycleState.snapshotAck) {
    bridge.snapshotAck?.(pendingBridgeLifecycleState.snapshotAck);
    pendingBridgeLifecycleState.snapshotAck = null;
  }
}

function dispatchFrontendReady(payload: FrontendReadyPayload): void {
  ensureBridgeReadyListener();
  if (window.linkGraphBridge?.frontendReady) {
    window.linkGraphBridge.frontendReady(payload);
    pendingBridgeLifecycleState.frontendReady = null;
    return;
  }
  pendingBridgeLifecycleState.frontendReady = payload;
}

function dispatchSnapshotAck(payload: SnapshotAckPayload): void {
  ensureBridgeReadyListener();
  if (window.linkGraphBridge?.snapshotAck) {
    window.linkGraphBridge.snapshotAck(payload);
    pendingBridgeLifecycleState.snapshotAck = null;
    return;
  }
  pendingBridgeLifecycleState.snapshotAck = payload;
}

function invokeBridgeAction(
  actionName: BridgeMethodName,
  invoke: (bridge: Bridge) => void,
  tracePayload?: unknown,
): BridgeInvocationResult {
  const bridge = window.linkGraphBridge;
  if (!bridge) {
    traceLinkGraph("api.bridgePending", {
      actionName,
      hasBridge: false,
    });
    return {
      ok: false,
      message: BRIDGE_UNAVAILABLE_MESSAGE,
      detailMessage: BRIDGE_UNAVAILABLE_DETAIL_MESSAGE,
    };
  }
  const bridgeAction = bridge[actionName];
  if (typeof bridgeAction !== "function") {
    const detailMessage = `IDE bridge 已注入，但当前未暴露 ${String(actionName)} 方法，本次请求没有发出。`;
    traceLinkGraph("api.bridgeProtocolMismatch", {
      actionName,
      hasBridge: true,
    });
    return {
      ok: false,
      message: BRIDGE_PROTOCOL_MISMATCH_MESSAGE,
      detailMessage,
    };
  }
  if (tracePayload !== undefined) {
    traceLinkGraph(`api.${String(actionName)}`, tracePayload);
  }
  invoke(bridge);
  return { ok: true };
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
  return invokeBridgeAction("importMermaid", (bridge) => {
    bridge.importMermaid?.(mermaid);
  });
}

export function exportMermaid(): BridgeInvocationResult {
  return invokeBridgeAction("exportMermaid", (bridge) => {
    bridge.exportMermaid?.();
  });
}

export function showDiffMode(): BridgeInvocationResult {
  return invokeBridgeAction("showDiffMode", (bridge) => {
    bridge.showDiffMode?.();
  });
}

export function requestArtifactContent(artifactIds: string[]): BridgeInvocationResult {
  return invokeBridgeAction("requestArtifact", (bridge) => {
    bridge.requestArtifact?.(artifactIds);
  }, {
    artifactIds,
  });
}

export function requestSyncPreview(): BridgeInvocationResult {
  return invokeBridgeAction("requestSyncPreview", (bridge) => {
    bridge.requestSyncPreview?.();
  });
}

export function requestAuditAsync(
  question: string,
  selectedNodeIds: string[] = [],
  sourceThreadId: string | null = null,
  mode: QaMode = "AUTO",
): BridgeInvocationResult {
  return invokeBridgeAction("requestAudit", (bridge) => {
    bridge.requestAudit?.(question, selectedNodeIds, sourceThreadId, mode);
  }, {
    question,
    selectedNodeIds,
    sourceThreadId,
    mode,
  });
}

export function retryLastAuditRequestAsync(): BridgeInvocationResult {
  return invokeBridgeAction("retryLastAuditRequest", (bridge) => {
    bridge.retryLastAuditRequest?.();
  });
}

export function confirmAuditCandidateChange(changeId: string): BridgeInvocationResult {
  return invokeBridgeAction("confirmAuditCandidateChange", (bridge) => {
    bridge.confirmAuditCandidateChange?.(changeId);
  }, {
    changeId,
  });
}

export function unconfirmAuditCandidateChange(changeId: string): BridgeInvocationResult {
  return invokeBridgeAction("unconfirmAuditCandidateChange", (bridge) => {
    bridge.unconfirmAuditCandidateChange?.(changeId);
  }, {
    changeId,
  });
}

export function resolveInvestigationThread(
  threadId: string,
  resolutionStatus: RiskResolutionStatus,
  note = "",
): BridgeInvocationResult {
  return invokeBridgeAction("resolveInvestigationThread", (bridge) => {
    bridge.resolveInvestigationThread?.(threadId, resolutionStatus, note);
  }, {
    threadId,
    resolutionStatus,
    note,
  });
}

export function requestDiffReviewAsync(question: string, selectedDiffItemIds: string[] = []): BridgeInvocationResult {
  return invokeBridgeAction("requestDiffReview", (bridge) => {
    bridge.requestDiffReview?.(question, selectedDiffItemIds);
  });
}

export function requestGraphBeautificationAsync(
  request: GraphBeautificationRequest = {},
): BridgeInvocationResult {
  const {
    goal = "",
    preferredStyle,
    explanationFocus,
    granularity = "BUSINESS",
    followUp,
  } = request;
  return invokeBridgeAction("requestGraphBeautification", (bridge) => {
    bridge.requestGraphBeautification?.(
      goal,
      preferredStyle,
      explanationFocus,
      granularity,
      followUp?.stepId,
      followUp?.stepTitle,
      followUp?.question,
    );
  }, {
    goal,
    preferredStyle: preferredStyle ?? null,
    explanationFocus: explanationFocus ?? null,
    granularity,
    followUp: followUp ?? null,
  });
}

export function applyDraftPatchPreview(operationIds?: string[]): BridgeInvocationResult {
  return invokeBridgeAction("applyDraftPatchPreview", (bridge) => {
    bridge.applyDraftPatchPreview?.(operationIds);
  });
}

export function clearDraftPatchPreview(): BridgeInvocationResult {
  return invokeBridgeAction("clearDraftPatchPreview", (bridge) => {
    bridge.clearDraftPatchPreview?.();
  });
}

export function restoreDraftPatchPreview(source: DraftPatchPreviewSource): BridgeInvocationResult {
  return invokeBridgeAction("restoreDraftPatchPreview", (bridge) => {
    bridge.restoreDraftPatchPreview?.(source);
  });
}

export function undoLastDraftPatchApply(): BridgeInvocationResult {
  return invokeBridgeAction("undoLastDraftPatchApply", (bridge) => {
    bridge.undoLastDraftPatchApply?.();
  });
}

export function requestGenerationPlanAsync(): BridgeInvocationResult {
  return invokeBridgeAction("requestGenerationPlan", (bridge) => {
    bridge.requestGenerationPlan?.();
  }, {
    action: "requestGenerationPlan",
  });
}

export function requestGenerationPlanDiscussionAsync(
  question: string,
  focusItemId: string | null = null,
): BridgeInvocationResult {
  return invokeBridgeAction("requestGenerationPlanDiscussion", (bridge) => {
    bridge.requestGenerationPlanDiscussion?.(question, focusItemId);
  }, {
    action: "requestGenerationPlanDiscussion",
    question,
    focusItemId,
  });
}

export function requestCodeDraftsAsync(): BridgeInvocationResult {
  return invokeBridgeAction("requestCodeDrafts", (bridge) => {
    bridge.requestCodeDrafts?.();
  });
}

export const requestAudit = requestAuditAsync;
export const retryLastAuditRequest = retryLastAuditRequestAsync;
export const requestDiffReview = requestDiffReviewAsync;
export const requestGraphBeautification = requestGraphBeautificationAsync;
export const requestGenerationPlan = requestGenerationPlanAsync;
export const requestGenerationPlanDiscussion = requestGenerationPlanDiscussionAsync;
export const requestCodeDrafts = requestCodeDraftsAsync;

export function requestCurrentEditorContextGraph(): BridgeInvocationResult {
  traceLinkGraph("api.requestCurrentEditorContextGraph");
  return invokeBridgeAction("requestCurrentEditorContextGraph", (bridge) => {
    bridge.requestCurrentEditorContextGraph?.();
  });
}

export function requestAnalysisDisplayMode(displayMode: AnalysisDisplayMode): BridgeInvocationResult {
  return invokeBridgeAction("requestAnalysisDisplayMode", (bridge) => {
    bridge.requestAnalysisDisplayMode?.(displayMode);
  });
}

export function updateWorkbenchSectionPreference(sectionId: string, expanded: boolean): BridgeInvocationResult {
  return invokeBridgeAction("updateWorkbenchSectionPreference", (bridge) => {
    bridge.updateWorkbenchSectionPreference?.(sectionId, expanded);
  }, {
    sectionId,
    expanded,
  });
}

export function requestOpenSettings(): BridgeInvocationResult {
  return invokeBridgeAction("requestOpenSettings", (bridge) => {
    bridge.requestOpenSettings?.();
  });
}

export function applyCodeDrafts(): BridgeInvocationResult {
  return invokeBridgeAction("applyCodeDrafts", (bridge) => {
    bridge.applyCodeDrafts?.();
  });
}

export function applySingleCodeDraft(draftId: string): BridgeInvocationResult {
  return invokeBridgeAction("applySingleCodeDraft", (bridge) => {
    bridge.applySingleCodeDraft?.(draftId);
  });
}

export function openCodeDraftNativeDiff(draftId: string): BridgeInvocationResult {
  return invokeBridgeAction("openCodeDraftNativeDiff", (bridge) => {
    bridge.openCodeDraftNativeDiff?.(draftId);
  });
}

export function publishNodeSelected(nodeId: string): BridgeInvocationResult {
  traceLinkGraph("api.publishNodeSelected", { nodeId });
  return invokeBridgeAction("nodeSelected", (bridge) => {
    bridge.nodeSelected?.(nodeId);
  });
}

export function requestSourceNavigation(nodeId: string): BridgeInvocationResult {
  traceLinkGraph("api.requestSourceNavigation", { nodeId });
  return invokeBridgeAction("requestSourceNavigation", (bridge) => {
    bridge.requestSourceNavigation?.(nodeId);
  });
}

export function requestDraftNavigation(targetPath: string): BridgeInvocationResult {
  return invokeBridgeAction("requestDraftNavigation", (bridge) => {
    bridge.requestDraftNavigation?.(targetPath);
  });
}

export function requestExpandOverflowNode(nodeId: string): BridgeInvocationResult {
  return invokeBridgeAction("requestExpandOverflowNode", (bridge) => {
    bridge.requestExpandOverflowNode?.(nodeId);
  });
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
  return invokeBridgeAction("applyGraphEditScript", (bridge) => {
    bridge.applyGraphEditScript?.(payload);
  }, payload);
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
  invokeBridgeAction("layoutChanged", (bridge) => {
    bridge.layoutChanged?.({
      positions,
    });
  });
}

function buildNodeMetadata(node: LinkGraphNode): Record<string, string> {
  return Object.fromEntries(
    Object.entries(node.metadata ?? {}).filter(([key]) => !key.startsWith("ui.") && !key.startsWith("layout.")),
  );
}

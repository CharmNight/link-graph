import type {
  AnalysisDisplayMode,
  BindingStatus,
  Certainty,
  DraftPatchPreviewSource,
  DiffStatus,
  GraphBeautificationRequest,
  GraphPosition,
  GraphSourceTag,
  LinkGraphBootstrapState,
  LinkGraphEdge,
  LinkGraphNode,
  LinkGraphSnapshotEnvelope,
  MermaidIssue,
  NodeType,
  RiskResolutionStatus,
  StepGranularity,
  SyncPreviewItem,
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

type PendingBridgeAction = {
  actionName: BridgeMethodName;
  invoke: (bridge: Bridge) => void;
  tracePayload?: unknown;
};

const pendingBridgeLifecycleState: PendingBridgeLifecycleState = {
  frontendReady: null,
  snapshotAck: null,
};
const pendingBridgeActions: PendingBridgeAction[] = [];

let bridgeReadyListenerInstalled = false;
const BRIDGE_UNAVAILABLE_MESSAGE = "IDE bridge 尚未就绪，本次请求没有发出。";
const BRIDGE_PROTOCOL_MISMATCH_MESSAGE = "IDE bridge 协议未对齐，本次请求没有发出。";

// JCEF 页面的唯一后端入口：读取 bootstrap，并把前端交互重新发布给 IDEA bridge。
interface BackendGraphNode {
  id: string;
  type: NodeType;
  title: string;
  location?: string;
  signature?: string;
  inputs: string[];
  outputs: string[];
  doc?: string;
  certainty: Certainty;
  bindingStatus: BindingStatus;
  diff: {
    status: DiffStatus;
  };
  position?: GraphPosition;
  metadata?: Record<string, string>;
  sourceTag?: GraphSourceTag;
}

interface BackendGraphEdge {
  id: string;
  type: string;
  fromNodeId: string;
  toNodeId: string;
  label?: string;
  metadata?: Record<string, string>;
  sourceTag?: GraphSourceTag;
}

interface BackendGraphDocument {
  nodes: BackendGraphNode[];
  edges: BackendGraphEdge[];
}

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
      requestAudit?: (question: string, selectedNodeIds?: string[], sourceThreadId?: string | null) => void;
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
      requestCodeDrafts?: () => void;
      requestCurrentEditorContextGraph?: () => void;
      requestAnalysisDisplayMode?: (displayMode: AnalysisDisplayMode) => void;
      updateWorkbenchSectionPreference?: (sectionId: string, expanded: boolean) => void;
      requestOpenSettings?: () => void;
      applyCodeDrafts?: () => void;
      applySingleCodeDraft?: (draftId: string) => void;
      requestArtifact?: (artifactIds: string[]) => void;
      graphChanged?: (payload: BackendGraphDocument) => void;
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
  flushPendingBridgeActions();
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

function flushPendingBridgeActions(): void {
  const bridge = window.linkGraphBridge;
  if (!bridge || pendingBridgeActions.length === 0) {
    return;
  }
  const queuedActions = pendingBridgeActions.splice(0, pendingBridgeActions.length);
  queuedActions.forEach(({ actionName, invoke, tracePayload }) => {
    const bridgeAction = bridge[actionName];
    if (typeof bridgeAction !== "function") {
      traceLinkGraph("api.bridgeQueuedActionDropped", {
        actionName,
        hasBridge: true,
      });
      return;
    }
    if (tracePayload !== undefined) {
      traceLinkGraph(`api.${String(actionName)}`, {
        ...((typeof tracePayload === "object" && tracePayload !== null) ? tracePayload : { value: tracePayload }),
        queuedUntilBridgeReady: true,
      });
    }
    invoke(bridge);
  });
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
    ensureBridgeReadyListener();
    pendingBridgeActions.push({
      actionName,
      invoke,
      tracePayload,
    });
    traceLinkGraph("api.bridgePending", {
      actionName,
      hasBridge: false,
      queuedActionCount: pendingBridgeActions.length,
    });
    return { ok: true };
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
  pendingBridgeActions.splice(0, pendingBridgeActions.length);
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
): BridgeInvocationResult {
  return invokeBridgeAction("requestAudit", (bridge) => {
    bridge.requestAudit?.(question, selectedNodeIds, sourceThreadId);
  }, {
    question,
    selectedNodeIds,
    sourceThreadId,
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

export function publishGraphChange(nodes: LinkGraphNode[], edges: LinkGraphEdge[]): BridgeInvocationResult {
  traceLinkGraph("api.publishGraphChange", {
    graph: summarizeGraph({ nodes, edges }),
  });
  return invokeBridgeAction("graphChanged", (bridge) => {
    bridge.graphChanged?.({
      nodes: nodes.map((node) => ({
        id: node.id,
        type: node.type,
        title: node.title,
        location: node.location,
        signature: node.signature,
        inputs: node.inputs ?? [],
        outputs: node.outputs ?? [],
        doc: node.doc,
        certainty: node.certainty,
        bindingStatus: node.bindingStatus,
        metadata: buildNodeMetadata(node),
        sourceTag: node.sourceTag,
        diff: {
          status: node.diffStatus ?? "MATCHED",
        },
      })),
      edges: edges.map((edge) => ({
        id: edge.id,
        type: edge.type,
        fromNodeId: edge.source,
        toNodeId: edge.target,
        label: edge.label,
        metadata: edge.metadata,
        sourceTag: edge.sourceTag,
      })),
    });
  });
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

export function getSampleSyncPreview(): SyncPreviewItem[] {
  return [
    {
      id: "create-order-draft",
      title: "新增 DTO",
      description: "生成 OrderDraftDto.java",
      risk: "LOW",
    },
    {
      id: "wire-place-draft",
      title: "补齐服务调用",
      description: "把 controller 流程接到 placeDraft 服务",
      risk: "MEDIUM",
    },
  ];
}

function buildNodeMetadata(node: LinkGraphNode): Record<string, string> {
  return Object.fromEntries(
    Object.entries(node.metadata ?? {}).filter(([key]) => !key.startsWith("ui.") && !key.startsWith("layout.")),
  );
}

export function getSampleMermaidIssues(): MermaidIssue[] {
  return [
    {
      category: "SEMANTIC",
      code: "missing-method-signature",
      message: "方法节点 'design:submit-order' 缺少 signature 元数据。",
      line: 3,
      nodeId: "design:submit-order",
    },
  ];
}

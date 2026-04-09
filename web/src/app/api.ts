import type {
  AnalysisDisplayMode,
  BindingStatus,
  Certainty,
  DraftPatchPreviewSource,
  DiffStatus,
  GraphPosition,
  GraphSourceTag,
  LinkGraphBootstrapState,
  LinkGraphEdge,
  LinkGraphNode,
  LinkGraphSnapshotEnvelope,
  MermaidIssue,
  NodeType,
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

const pendingBridgeLifecycleState: PendingBridgeLifecycleState = {
  frontendReady: null,
  snapshotAck: null,
};

let bridgeReadyListenerInstalled = false;
const BRIDGE_UNAVAILABLE_MESSAGE = "IDE bridge 尚未就绪，本次请求没有发出。";

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
    "link-graph-bootstrap": CustomEvent<LinkGraphBootstrapState | LinkGraphSnapshotEnvelope>;
    "link-graph-bridge-ready": Event;
  }

  interface Window {
    linkGraphBridge?: {
      importMermaid?: (mermaid: string) => void;
      exportMermaid?: () => void;
      showDiffMode?: () => void;
      requestSyncPreview?: () => void;
      requestAudit?: (question: string, selectedNodeIds?: string[]) => void;
      requestDiffReview?: (question: string, selectedDiffItemIds?: string[]) => void;
      requestGraphBeautification?: (
        goal?: string,
        preferredStyle?: string | null,
        explanationFocus?: string | null,
      ) => void;
      applyDraftPatchPreview?: (operationIds?: string[]) => void;
      clearDraftPatchPreview?: () => void;
      restoreDraftPatchPreview?: (source: DraftPatchPreviewSource) => void;
      undoLastDraftPatchApply?: () => void;
      requestGenerationPlan?: () => void;
      requestCodeDrafts?: () => void;
      requestCurrentMethodGraph?: () => void;
      requestAnalysisDisplayMode?: (displayMode: AnalysisDisplayMode) => void;
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
  window.addEventListener("link-graph-bridge-ready", flushPendingBridgeLifecycleState);
  bridgeReadyListenerInstalled = true;
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
    traceLinkGraph("api.bridgeUnavailable", {
      actionName,
      hasBridge: false,
    });
    return {
      ok: false,
      message: BRIDGE_UNAVAILABLE_MESSAGE,
      detailMessage: "JCEF 页面与 IDEA 后端连接尚未建立，请等待页面初始化完成后重试。",
    };
  }
  const bridgeAction = bridge[actionName];
  if (typeof bridgeAction !== "function") {
    const detailMessage = bridge
      ? `IDE bridge 已注入，但当前未暴露 ${String(actionName)} 方法，本次请求没有发出。`
      : "JCEF 页面与 IDEA 后端连接尚未建立，请等待页面初始化完成后重试。";
    traceLinkGraph("api.bridgeUnavailable", {
      actionName,
      hasBridge: true,
    });
    return {
      ok: false,
      message: BRIDGE_UNAVAILABLE_MESSAGE,
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

export function requestAuditAsync(question: string, selectedNodeIds: string[] = []): BridgeInvocationResult {
  return invokeBridgeAction("requestAudit", (bridge) => {
    bridge.requestAudit?.(question, selectedNodeIds);
  }, {
    question,
    selectedNodeIds,
  });
}

export function requestDiffReviewAsync(question: string, selectedDiffItemIds: string[] = []): BridgeInvocationResult {
  return invokeBridgeAction("requestDiffReview", (bridge) => {
    bridge.requestDiffReview?.(question, selectedDiffItemIds);
  });
}

export function requestGraphBeautificationAsync(
  goal = "",
  preferredStyle?: string | null,
  explanationFocus?: string | null,
): BridgeInvocationResult {
  return invokeBridgeAction("requestGraphBeautification", (bridge) => {
    bridge.requestGraphBeautification?.(goal, preferredStyle, explanationFocus);
  }, {
    goal,
    preferredStyle: preferredStyle ?? null,
    explanationFocus: explanationFocus ?? null,
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
  });
}

export function requestCodeDraftsAsync(): BridgeInvocationResult {
  return invokeBridgeAction("requestCodeDrafts", (bridge) => {
    bridge.requestCodeDrafts?.();
  });
}

export const requestAudit = requestAuditAsync;
export const requestDiffReview = requestDiffReviewAsync;
export const requestGraphBeautification = requestGraphBeautificationAsync;
export const requestGenerationPlan = requestGenerationPlanAsync;
export const requestCodeDrafts = requestCodeDraftsAsync;

export function requestCurrentMethodGraph(): BridgeInvocationResult {
  traceLinkGraph("api.requestCurrentMethodGraph");
  return invokeBridgeAction("requestCurrentMethodGraph", (bridge) => {
    bridge.requestCurrentMethodGraph?.();
  });
}

export function requestAnalysisDisplayMode(displayMode: AnalysisDisplayMode): BridgeInvocationResult {
  return invokeBridgeAction("requestAnalysisDisplayMode", (bridge) => {
    bridge.requestAnalysisDisplayMode?.(displayMode);
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

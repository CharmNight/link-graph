import { afterEach, describe, expect, it, vi } from "vitest";
import {
  acknowledgeSnapshot,
  announceFrontendReady,
  publishGraphEditScript,
  readBootstrapState,
  requestAssistantTask,
  requestAnalysisDisplayMode,
  requestCurrentEditorContextGraph,
  requestExpandInvocation,
  requestRemoveInvocationExpansion,
  requestArchitectureGraph,
  requestClassDiagram,
  requestClassUsages,
  requestPackageDependencyGraph,
  requestReviewGraph,
  resolveInvestigationThread,
  retryLastQaRequestAsync,
  resetApiBridgeLifecycleStateForTest,
} from "../../app/api";
import { resetEditorTransportForTest } from "../../app/editorTransport";
import { EMPTY_STATE } from "../../app/sampleState";
import type { LinkGraphEdge, LinkGraphNode } from "../../app/types";

function methodNode(id: string, title: string): LinkGraphNode {
  return {
    id,
    type: "METHOD",
    title,
    signature: `com.example.${title}():void`,
    inputs: [],
    outputs: ["void"],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    sourceTag: "FACT",
  };
}

function installBridgeCommandSpy() {
  const sendCommand = vi.fn();
  window.linkGraphBridge = {
    sendCommand,
  };
  return sendCommand;
}

function commandPayload<TPayload extends Record<string, unknown> = Record<string, unknown>>(
  sendCommand: ReturnType<typeof installBridgeCommandSpy>,
  callIndex = 0,
): TPayload | undefined {
  return sendCommand.mock.calls[callIndex]?.[0]?.payload as TPayload | undefined;
}

function expectCommand(
  sendCommand: ReturnType<typeof installBridgeCommandSpy>,
  callIndex: number,
  type: string,
  payload: Record<string, unknown>,
) {
  expect(sendCommand).toHaveBeenNthCalledWith(callIndex + 1, {
    schemaVersion: 1,
    type,
    payload,
  });
}

describe("publishGraphEditScript", () => {
  afterEach(() => {
    resetEditorTransportForTest();
    resetApiBridgeLifecycleStateForTest();
    window.linkGraphBridge = undefined;
    window.linkGraphDebugTrace = undefined;
    window.linkGraphBootstrap = undefined;
  });

  it("keeps edge metadata when syncing canonical graph edits back to the IDE bridge", () => {
    const sendCommand = installBridgeCommandSpy();

    const nodes: LinkGraphNode[] = [
      methodNode("anchor", "OrderService.place"),
      methodNode("callee", "OrderMapper.insert"),
    ];
    const edges: LinkGraphEdge[] = [
      {
        id: "edge-call",
        type: "CALL",
        source: "anchor",
        target: "callee",
        metadata: {
          callOrder: "0",
        },
      },
    ];

    publishGraphEditScript({
      sceneId: "WORKSPACE_FLOWCHART",
      baseWorkspaceRevision: 7,
      operations: [
        {
          type: "UPSERT_NODE",
          node: nodes[0]!,
        },
        {
          type: "UPSERT_NODE",
          node: nodes[1]!,
        },
        {
          type: "UPSERT_EDGE",
          edge: edges[0]!,
        },
      ],
    });

    expectCommand(sendCommand, 0, "applyGraphEditScript", {
      sceneId: "WORKSPACE_FLOWCHART",
      baseWorkspaceRevision: 7,
      operations: [
        expect.objectContaining({
          type: "UPSERT_NODE",
          node: expect.objectContaining({
            id: "anchor",
          }),
        }),
        expect.objectContaining({
          type: "UPSERT_NODE",
          node: expect.objectContaining({
            id: "callee",
          }),
        }),
        expect.objectContaining({
          type: "UPSERT_EDGE",
          edge: expect.objectContaining({
            id: "edge-call",
            fromNodeId: "anchor",
            toNodeId: "callee",
            metadata: {
              callOrder: "0",
            },
          }),
        }),
      ],
    });
  });

  it("does not copy canvas positions into semantic node metadata", () => {
    const sendCommand = installBridgeCommandSpy();

    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("anchor", "OrderService.place"),
        position: { x: 640, y: 320 },
        metadata: {
          "linkGraph.manual": "true",
        },
      },
    ];

    publishGraphEditScript({
      sceneId: "WORKSPACE_FACT",
      baseWorkspaceRevision: 3,
      operations: [
        {
          type: "UPSERT_NODE",
          node: nodes[0]!,
        },
      ],
    });

    expect(commandPayload<{ operations?: Array<{ node?: LinkGraphNode }> }>(sendCommand)?.operations?.[0]?.node?.metadata).toEqual({
      "linkGraph.manual": "true",
    });
  });

  it("strips legacy ui position keys from semantic node metadata", () => {
    const sendCommand = installBridgeCommandSpy();

    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("anchor", "OrderService.place"),
        position: { x: 640, y: 320 },
        metadata: {
          "ui.x": "640",
          "ui.y": "320",
          "layout.row": "2",
          "linkGraph.manual": "true",
        },
      },
    ];

    publishGraphEditScript({
      sceneId: "WORKSPACE_RESOURCE_RELATION",
      baseWorkspaceRevision: 5,
      operations: [
        {
          type: "UPSERT_NODE",
          node: nodes[0]!,
        },
      ],
    });

    expect(commandPayload<{ operations?: Array<{ node?: LinkGraphNode }> }>(sendCommand)?.operations?.[0]?.node?.metadata).toEqual({
      "linkGraph.manual": "true",
    });
  });

  it("notifies the IDE bridge when the frontend transport becomes ready", () => {
    const sendCommand = installBridgeCommandSpy();

    announceFrontendReady(7);

    expectCommand(sendCommand, 0, "frontendReady", {
      lastAppliedRevision: 7,
    });
  });

  it("acknowledges only newer snapshot revisions back to the IDE bridge", () => {
    const sendCommand = installBridgeCommandSpy();

    acknowledgeSnapshot(3);
    acknowledgeSnapshot(3);
    acknowledgeSnapshot(4);

    expect(sendCommand).toHaveBeenCalledTimes(2);
    expectCommand(sendCommand, 0, "snapshotAck", {
      revision: 3,
    });
    expectCommand(sendCommand, 1, "snapshotAck", {
      revision: 4,
    });
  });

  it("replays frontend ready once the IDE bridge is injected after app mount", () => {
    const sendCommand = vi.fn();
    window.linkGraphBridge = undefined;

    announceFrontendReady(7);

    expect(sendCommand).not.toHaveBeenCalled();

    window.linkGraphBridge = {
      sendCommand,
    };
    window.dispatchEvent(new Event("link-graph-bridge-ready"));

    expectCommand(sendCommand, 0, "frontendReady", {
      lastAppliedRevision: 7,
    });
  });

  it("replays pending lifecycle commands through sendCommand once the unified IDE bridge is injected", () => {
    const sendCommand = vi.fn();
    window.linkGraphBridge = undefined;

    announceFrontendReady(7);
    acknowledgeSnapshot(8);

    expect(sendCommand).not.toHaveBeenCalled();

    window.linkGraphBridge = {
      sendCommand,
    };
    window.dispatchEvent(new Event("link-graph-bridge-ready"));

    expect(sendCommand).toHaveBeenNthCalledWith(1, {
      schemaVersion: 1,
      type: "frontendReady",
      payload: {
        lastAppliedRevision: 7,
      },
    });
    expect(sendCommand).toHaveBeenNthCalledWith(2, {
      schemaVersion: 1,
      type: "snapshotAck",
      payload: {
        revision: 8,
      },
    });
  });

  it("replays the latest pending snapshot acknowledgement after the IDE bridge is injected", () => {
    const sendCommand = vi.fn();
    window.linkGraphBridge = undefined;

    acknowledgeSnapshot(3);
    acknowledgeSnapshot(4);

    expect(sendCommand).not.toHaveBeenCalled();

    window.linkGraphBridge = {
      sendCommand,
    };
    window.dispatchEvent(new Event("link-graph-bridge-ready"));

    expect(sendCommand).toHaveBeenCalledTimes(1);
    expectCommand(sendCommand, 0, "snapshotAck", {
      revision: 4,
    });
  });

  it("从 bootstrap 读取当前展示模式", () => {
    window.linkGraphBootstrap = {
      analysisDisplayMode: "FACT_GRAPH",
      currentSceneId: "WORKSPACE_FACT",
      sceneStates: {
        WORKSPACE_FACT: {
          selectedNodeId: null,
          anchorNodeId: null,
          layoutState: { positions: {} },
          layoutRevision: 0,
          collapsedNodeIds: [],
        },
        WORKSPACE_FLOWCHART: {
          selectedNodeId: null,
          anchorNodeId: null,
          layoutState: { positions: {} },
          layoutRevision: 0,
          collapsedNodeIds: [],
        },
        WORKSPACE_RESOURCE_RELATION: {
          selectedNodeId: null,
          anchorNodeId: null,
          layoutState: { positions: {} },
          layoutRevision: 0,
          collapsedNodeIds: [],
        },
        WORKSPACE_ARCHITECTURE_GRAPH: {
          selectedNodeId: null,
          anchorNodeId: null,
          layoutState: { positions: {} },
          layoutRevision: 0,
          collapsedNodeIds: [],
        },
        WORKSPACE_CLASS_DIAGRAM: {
          selectedNodeId: null,
          anchorNodeId: null,
          layoutState: { positions: {} },
          layoutRevision: 0,
          collapsedNodeIds: [],
        },
        WORKSPACE_REVIEW_GRAPH: {
          selectedNodeId: null,
          anchorNodeId: null,
          layoutState: { positions: {} },
          layoutRevision: 0,
          collapsedNodeIds: [],
        },
        DIFF: {
          selectedNodeId: null,
          anchorNodeId: null,
          layoutState: { positions: {} },
          layoutRevision: 0,
          collapsedNodeIds: [],
        },
      },
      workspaceGraph: {
        nodes: [],
        edges: [],
      },
      workspaceBaseGraph: {
        nodes: [],
        edges: [],
      },
      semanticFactGraph: {
        nodes: [],
        edges: [],
      },
      mermaidIssues: [],
      diffItems: [],
      syncPreviewItems: [],
    };

    expect(readBootstrapState()?.analysisDisplayMode).toBe("FACT_GRAPH");
  });

  it("readBootstrapState keeps bootstrap sources unchanged", () => {
    window.linkGraphBootstrap = {
      ...EMPTY_STATE,
      generatedCodeDraftSource: "LOCAL_RULE",
    };

    expect(readBootstrapState()?.generatedCodeDraftSource).toBe("LOCAL_RULE");
  });

  it("把展示模式切换请求转发给 IDE bridge", () => {
    const sendCommand = installBridgeCommandSpy();

    requestAnalysisDisplayMode("FLOWCHART");

    expectCommand(sendCommand, 0, "requestAnalysisDisplayMode", {
      displayMode: "FLOWCHART",
    });
  });

  it("indexed graph wrappers send backend-owned presets instead of duplicated defaults", () => {
    const sendCommand = installBridgeCommandSpy();

    requestArchitectureGraph({ viewport: { maxVisibleNodes: 80 } });
    requestPackageDependencyGraph("com.example.orders", { includeJdk: false });
    requestClassDiagram("component:orders", { classDiagram: { neighborhoodLimit: 48 } });
    requestReviewGraph(["diff:1"], { review: { maxChangedNodes: 160 } });

    expectCommand(sendCommand, 0, "requestIndexedGraph", {
      preset: "ARCHITECTURE",
      viewport: { maxVisibleNodes: 80 },
    });
    expectCommand(sendCommand, 1, "requestIndexedGraph", {
      preset: "PACKAGE_DEPENDENCY",
      packageName: "com.example.orders",
      includeJdk: false,
    });
    expectCommand(sendCommand, 2, "requestIndexedGraph", {
      preset: "CLASS_DIAGRAM",
      scopeNodeId: "component:orders",
      classDiagram: { neighborhoodLimit: 48 },
    });
    expectCommand(sendCommand, 3, "requestIndexedGraph", {
      preset: "REVIEW",
      selectedDiffItemIds: ["diff:1"],
      review: { maxChangedNodes: 160 },
    });
  });

  it("sends class usage requests as standalone class diagram usage lookups", () => {
    const sendCommand = installBridgeCommandSpy();

    requestClassUsages("jvm:class:com-example-order-service", {
      targetQualifiedName: "com.example.OrderService",
      sourceVirtualFileUrl: "file:///project/src/main/java/com/example/OrderService.java",
      sourcePath: "src/main/java/com/example/OrderService.java",
      maxUsageGroups: 12,
      maxUsageEntries: 40,
      includeImports: true,
    });

    expectCommand(sendCommand, 0, "requestIndexedGraph", {
      preset: "CLASS_DIAGRAM",
      usage: {
        enabled: true,
        targetNodeId: "jvm:class:com-example-order-service",
        targetQualifiedName: "com.example.OrderService",
        sourceVirtualFileUrl: "file:///project/src/main/java/com/example/OrderService.java",
        sourcePath: "src/main/java/com/example/OrderService.java",
        maxUsageGroups: 12,
        maxUsageEntries: 40,
        includeImports: true,
      },
    });
  });

  it("rejects injected bridges that do not expose the unified command entrypoint", () => {
    window.linkGraphBridge = {};

    const result = requestAssistantTask({
      actionId: "CHECK_CHANGE",
      sceneId: "WORKSPACE_REVIEW_GRAPH",
      intent: "CHECK_CHANGE",
      prompt: "检查这次改动",
      selectedNodeIds: ["method:submit-order"],
      selectedDiffItemIds: ["diff:OrderController.kt"],
      target: {
        kind: "RiskInvestigation",
        threadId: "risk-thread:1",
        targetNodeIds: ["method:submit-order"],
      },
      explanationGranularity: "CODE_SEMANTIC",
    });

    expect(result).toEqual({
      ok: false,
      message: "IDE bridge 协议未对齐，本次请求没有发出。",
      detailMessage: "IDE bridge 已注入，但当前未暴露统一 sendCommand 方法，本次请求没有发出。",
    });
  });

  it("uses sendCommand when the injected IDE bridge only exposes the unified envelope entrypoint", () => {
    const sendCommand = vi.fn();
    window.linkGraphBridge = {
      sendCommand,
    };

    const result = requestAssistantTask({
      actionId: "CHECK_CHANGE",
      sceneId: "WORKSPACE_REVIEW_GRAPH",
      intent: "CHECK_CHANGE",
      prompt: "检查这次改动",
      selectedNodeIds: ["method:submit-order"],
      selectedDiffItemIds: ["diff:OrderController.kt"],
      target: {
        kind: "RiskInvestigation",
        threadId: "risk-thread:1",
        targetNodeIds: ["method:submit-order"],
      },
      explanationGranularity: "CODE_SEMANTIC",
    });

    expect(result).toEqual({ ok: true });
    expect(sendCommand).toHaveBeenCalledWith({
      schemaVersion: 1,
      type: "requestAssistantTask",
      payload: {
        actionId: "CHECK_CHANGE",
        sceneId: "WORKSPACE_REVIEW_GRAPH",
        intent: "CHECK_CHANGE",
        prompt: "检查这次改动",
        selectedNodeIds: ["method:submit-order"],
        selectedDiffItemIds: ["diff:OrderController.kt"],
        target: {
          kind: "RiskInvestigation",
          threadId: "risk-thread:1",
          targetNodeIds: ["method:submit-order"],
        },
        explanationGranularity: "CODE_SEMANTIC",
      },
    });
  });

  it("uses sendCommand envelopes for lifecycle and graph-edit commands across the bridge", () => {
    const sendCommand = vi.fn();
    window.linkGraphBridge = {
      sendCommand,
    };

    announceFrontendReady(12);
    acknowledgeSnapshot(13);
    publishGraphEditScript({
      sceneId: "WORKSPACE_FLOWCHART",
      baseWorkspaceRevision: 7,
      operations: [
        {
          type: "UPSERT_NODE",
          node: methodNode("anchor", "OrderService.place"),
        },
      ],
    });

    expect(sendCommand).toHaveBeenNthCalledWith(1, {
      schemaVersion: 1,
      type: "frontendReady",
      payload: {
        lastAppliedRevision: 12,
      },
    });
    expect(sendCommand).toHaveBeenNthCalledWith(2, {
      schemaVersion: 1,
      type: "snapshotAck",
      payload: {
        revision: 13,
      },
    });
    expect(sendCommand).toHaveBeenNthCalledWith(3, {
      schemaVersion: 1,
      type: "applyGraphEditScript",
      payload: {
        sceneId: "WORKSPACE_FLOWCHART",
        baseWorkspaceRevision: 7,
        operations: [
          expect.objectContaining({
            type: "UPSERT_NODE",
            node: expect.objectContaining({
              id: "anchor",
            }),
          }),
        ],
      },
    });
  });

  it("把当前编辑器上下文加载请求转发给 IDE bridge", () => {
    const sendCommand = installBridgeCommandSpy();

    requestCurrentEditorContextGraph();

    expectCommand(sendCommand, 0, "requestCurrentEditorContextGraph", {});
  });

  it("把调用方法展开请求转发给 IDE bridge", () => {
    const sendCommand = installBridgeCommandSpy();

    requestExpandInvocation("invoke:create-info");

    expectCommand(sendCommand, 0, "requestExpandInvocation", {
      nodeId: "invoke:create-info",
    });
  });

  it("把移除调用展开请求转发给 IDE bridge", () => {
    const sendCommand = installBridgeCommandSpy();

    requestRemoveInvocationExpansion("invocation:expansion-1");

    expectCommand(sendCommand, 0, "requestRemoveInvocationExpansion", {
      expansionId: "invocation:expansion-1",
    });
  });

  it("把风险决策请求转发给 IDE bridge", () => {
    const sendCommand = installBridgeCommandSpy();

    resolveInvestigationThread("thread-risk-1", "ACCEPTED_RISK");

    expectCommand(sendCommand, 0, "resolveInvestigationThread", {
      threadId: "thread-risk-1",
      resolutionStatus: "ACCEPTED_RISK",
      note: "",
    });
  });

  it("把问答重试请求转发给 IDE bridge", () => {
    const sendCommand = installBridgeCommandSpy();

    retryLastQaRequestAsync();

    expectCommand(sendCommand, 0, "retryLastQaRequest", {});
  });

  it("在 bridge 已注入但缺少方法时返回协议未对齐错误", () => {
    window.linkGraphBridge = {};

    const result = resolveInvestigationThread("thread-risk-1", "DEFERRED");

    expect(result).toEqual({
      ok: false,
      message: "IDE bridge 协议未对齐，本次请求没有发出。",
      detailMessage: "IDE bridge 已注入，但当前未暴露统一 sendCommand 方法，本次请求没有发出。",
    });
  });

  it("在 bridge 未注入时直接返回失败，而不是把用户命令伪装成已接受", () => {
    const sendCommand = vi.fn();
    window.linkGraphBridge = undefined;

    const result = requestAssistantTask({
      actionId: "ASK_CONTEXT",
      sceneId: "WORKSPACE_FLOWCHART",
      intent: "ASK_CODE",
      prompt: "请围绕当前链路进行问答",
      selectedNodeIds: ["method:place-order", "sql:insert-order"],
      target: {
        kind: "RiskInvestigation",
        threadId: "thread-risk-1",
        targetNodeIds: ["method:place-order", "sql:insert-order"],
      },
    });

    expect(result).toEqual({
      ok: false,
      message: "IDE bridge 尚未就绪，本次请求没有发出。",
      detailMessage: "JCEF 页面与 IDEA 后端连接尚未建立，请等待页面初始化完成后重试。",
    });
    expect(sendCommand).not.toHaveBeenCalled();

    window.linkGraphBridge = {
      sendCommand,
    };
    window.dispatchEvent(new Event("link-graph-bridge-ready"));

    expect(sendCommand).not.toHaveBeenCalled();
  });
});

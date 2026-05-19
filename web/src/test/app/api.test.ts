import { afterEach, describe, expect, it, vi } from "vitest";
import {
  acknowledgeSnapshot,
  announceFrontendReady,
  publishGraphEditScript,
  readBootstrapState,
  requestQaAsync,
  requestAnalysisDisplayMode,
  requestCurrentEditorContextGraph,
  requestExpandInvocation,
  requestRemoveInvocationExpansion,
  requestGraphBeautificationAsync,
  resolveInvestigationThread,
  retryLastQaRequestAsync,
  updateWorkbenchSectionPreference,
  resetApiBridgeLifecycleStateForTest,
} from "../../app/api";
import { resetEditorTransportForTest } from "../../app/editorTransport";
import { EMPTY_STATE } from "../../app/sampleState";
import type { LinkGraphEdge, LinkGraphNode, RiskResolutionStatus } from "../../app/types";

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

describe("publishGraphEditScript", () => {
  afterEach(() => {
    resetEditorTransportForTest();
    resetApiBridgeLifecycleStateForTest();
    window.linkGraphBridge = undefined;
    window.linkGraphDebugTrace = undefined;
    window.linkGraphBootstrap = undefined;
  });

  it("keeps edge metadata when syncing canonical graph edits back to the IDE bridge", () => {
    const applyGraphEditScript = vi.fn();
    window.linkGraphBridge = {
      applyGraphEditScript,
    };

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

    expect(applyGraphEditScript).toHaveBeenCalledWith({
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
    const applyGraphEditScript = vi.fn();
    window.linkGraphBridge = {
      applyGraphEditScript,
    };

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

    expect(applyGraphEditScript.mock.calls[0]?.[0]?.operations?.[0]?.node?.metadata).toEqual({
      "linkGraph.manual": "true",
    });
  });

  it("strips legacy ui position keys from semantic node metadata", () => {
    const applyGraphEditScript = vi.fn();
    window.linkGraphBridge = {
      applyGraphEditScript,
    };

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

    expect(applyGraphEditScript.mock.calls[0]?.[0]?.operations?.[0]?.node?.metadata).toEqual({
      "linkGraph.manual": "true",
    });
  });

  it("notifies the IDE bridge when the frontend transport becomes ready", () => {
    const frontendReady = vi.fn();
    window.linkGraphBridge = {
      frontendReady,
    };

    announceFrontendReady(7);

    expect(frontendReady).toHaveBeenCalledWith({
      lastAppliedRevision: 7,
    });
  });

  it("acknowledges only newer snapshot revisions back to the IDE bridge", () => {
    const snapshotAck = vi.fn();
    window.linkGraphBridge = {
      snapshotAck,
    };

    acknowledgeSnapshot(3);
    acknowledgeSnapshot(3);
    acknowledgeSnapshot(4);

    expect(snapshotAck).toHaveBeenCalledTimes(2);
    expect(snapshotAck).toHaveBeenNthCalledWith(1, {
      revision: 3,
    });
    expect(snapshotAck).toHaveBeenNthCalledWith(2, {
      revision: 4,
    });
  });

  it("replays frontend ready once the IDE bridge is injected after app mount", () => {
    const frontendReady = vi.fn();
    window.linkGraphBridge = undefined;

    announceFrontendReady(7);

    expect(frontendReady).not.toHaveBeenCalled();

    window.linkGraphBridge = {
      frontendReady,
    };
    window.dispatchEvent(new Event("link-graph-bridge-ready"));

    expect(frontendReady).toHaveBeenCalledWith({
      lastAppliedRevision: 7,
    });
  });

  it("replays the latest pending snapshot acknowledgement after the IDE bridge is injected", () => {
    const snapshotAck = vi.fn();
    window.linkGraphBridge = undefined;

    acknowledgeSnapshot(3);
    acknowledgeSnapshot(4);

    expect(snapshotAck).not.toHaveBeenCalled();

    window.linkGraphBridge = {
      snapshotAck,
    };
    window.dispatchEvent(new Event("link-graph-bridge-ready"));

    expect(snapshotAck).toHaveBeenCalledTimes(1);
    expect(snapshotAck).toHaveBeenCalledWith({
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
    const requestAnalysisDisplayModeBridge = vi.fn();
    window.linkGraphBridge = {
      requestAnalysisDisplayMode: requestAnalysisDisplayModeBridge,
    };

    requestAnalysisDisplayMode("FLOWCHART");

    expect(requestAnalysisDisplayModeBridge).toHaveBeenCalledWith("FLOWCHART");
  });

  it("把工作台折叠偏好更新转发给 IDE bridge", () => {
    const updateWorkbenchSectionPreferenceBridge = vi.fn();
    window.linkGraphBridge = {
      updateWorkbenchSectionPreference: updateWorkbenchSectionPreferenceBridge,
    };

    updateWorkbenchSectionPreference("qa.candidate-changes", true);

    expect(updateWorkbenchSectionPreferenceBridge).toHaveBeenCalledWith("qa.candidate-changes", true);
  });

  it("把当前编辑器上下文加载请求转发给 IDE bridge", () => {
    const requestCurrentEditorContextGraphBridge = vi.fn();
    window.linkGraphBridge = {
      requestCurrentEditorContextGraph: requestCurrentEditorContextGraphBridge,
    };

    requestCurrentEditorContextGraph();

    expect(requestCurrentEditorContextGraphBridge).toHaveBeenCalledTimes(1);
  });

  it("把调用方法展开请求转发给 IDE bridge", () => {
    const requestExpandInvocationBridge = vi.fn();
    window.linkGraphBridge = {
      requestExpandInvocation: requestExpandInvocationBridge,
    };

    requestExpandInvocation("invoke:create-info");

    expect(requestExpandInvocationBridge).toHaveBeenCalledWith("invoke:create-info");
  });

  it("把移除调用展开请求转发给 IDE bridge", () => {
    const requestRemoveInvocationExpansionBridge = vi.fn();
    window.linkGraphBridge = {
      requestRemoveInvocationExpansion: requestRemoveInvocationExpansionBridge,
    };

    requestRemoveInvocationExpansion("invocation:expansion-1");

    expect(requestRemoveInvocationExpansionBridge).toHaveBeenCalledWith("invocation:expansion-1");
  });

  it("记录问答请求参数到前端调试 trace", () => {
    const requestQaBridge = vi.fn();
    const traceSink = vi.fn();
    window.linkGraphBridge = {
      requestQa: requestQaBridge,
    };
    window.linkGraphDebugTrace = traceSink;

    requestQaAsync("请围绕当前链路进行问答", ["method:place-order", "sql:insert-order"], "thread-risk-1", "INVESTIGATE");

    expect(requestQaBridge).toHaveBeenCalledWith(
      "请围绕当前链路进行问答",
      ["method:place-order", "sql:insert-order"],
      "thread-risk-1",
      "INVESTIGATE",
    );
    const tracePayload = String(traceSink.mock.calls[0]?.[0] ?? "");
    expect(tracePayload).toContain("\"event\":\"api.requestQa\"");
    expect(tracePayload).toContain("\"question\":\"请围绕当前链路进行问答\"");
    expect(tracePayload).toContain("\"selectedNodeIds\":[\"method:place-order\",\"sql:insert-order\"]");
    expect(tracePayload).toContain("\"sourceThreadId\":\"thread-risk-1\"");
    expect(tracePayload).toContain("\"mode\":\"INVESTIGATE\"");
  });

  it("defaults qa requests to AUTO mode for legacy callers", () => {
    const requestQaBridge = vi.fn();
    window.linkGraphBridge = {
      requestQa: requestQaBridge,
    };

    requestQaAsync("这个方法是如何触发的？");

    expect(requestQaBridge).toHaveBeenCalledWith("这个方法是如何触发的？", [], null, "AUTO");
  });

  it("把风险决策请求转发给 IDE bridge", () => {
    const resolveInvestigationThreadBridge = vi.fn();
    window.linkGraphBridge = {
      resolveInvestigationThread: resolveInvestigationThreadBridge,
    };

    resolveInvestigationThread("thread-risk-1", "ACCEPTED_RISK");

    expect(resolveInvestigationThreadBridge).toHaveBeenCalledWith("thread-risk-1", "ACCEPTED_RISK", "");
  });

  it("把问答重试请求转发给 IDE bridge", () => {
    const retryLastQaRequestBridge = vi.fn();
    window.linkGraphBridge = {
      retryLastQaRequest: retryLastQaRequestBridge,
    };

    retryLastQaRequestAsync();

    expect(retryLastQaRequestBridge).toHaveBeenCalledTimes(1);
  });

  it("在 bridge 已注入但缺少方法时返回协议未对齐错误", () => {
    window.linkGraphBridge = {
      requestQa: vi.fn(),
    };

    const result = resolveInvestigationThread("thread-risk-1", "DEFERRED" satisfies RiskResolutionStatus);

    expect(result).toEqual({
      ok: false,
      message: "IDE bridge 协议未对齐，本次请求没有发出。",
      detailMessage: "IDE bridge 已注入，但当前未暴露 resolveInvestigationThread 方法，本次请求没有发出。",
    });
  });

  it("在 bridge 未注入时直接返回失败，而不是把用户命令伪装成已接受", () => {
    const requestQaBridge = vi.fn();
    window.linkGraphBridge = undefined;

    const result = requestQaAsync(
      "请围绕当前链路进行问答",
      ["method:place-order", "sql:insert-order"],
      "thread-risk-1",
    );

    expect(result).toEqual({
      ok: false,
      message: "IDE bridge 尚未就绪，本次请求没有发出。",
      detailMessage: "JCEF 页面与 IDEA 后端连接尚未建立，请等待页面初始化完成后重试。",
    });
    expect(requestQaBridge).not.toHaveBeenCalled();

    window.linkGraphBridge = {
      requestQa: requestQaBridge,
    };
    window.dispatchEvent(new Event("link-graph-bridge-ready"));

    expect(requestQaBridge).not.toHaveBeenCalled();
  });

  it("记录链路讲解请求参数到前端调试 trace", () => {
    const requestBeautificationBridge = vi.fn();
    const traceSink = vi.fn();
    window.linkGraphBridge = {
      requestGraphBeautification: requestBeautificationBridge,
    };
    window.linkGraphDebugTrace = traceSink;

    requestGraphBeautificationAsync({
      goal: "",
      preferredStyle: "汇报版",
      explanationFocus: "请重点讲解 placeOrder 节点",
      focusNodeId: "method:place-order",
      granularity: "METHOD_CALL",
      followUp: {
        stepId: "step-place-order",
        stepTitle: "Step 1 提交订单",
        question: "订单失败时怎么处理？",
      },
    });

    expect(requestBeautificationBridge).toHaveBeenCalledWith(
      "",
      "汇报版",
      "请重点讲解 placeOrder 节点",
      "METHOD_CALL",
      "step-place-order",
      "Step 1 提交订单",
      "订单失败时怎么处理？",
      "method:place-order",
    );
    const tracePayload = String(traceSink.mock.calls[0]?.[0] ?? "");
    expect(tracePayload).toContain("\"event\":\"api.requestGraphBeautification\"");
    expect(tracePayload).toContain("\"preferredStyle\":\"汇报版\"");
    expect(tracePayload).toContain("\"explanationFocus\":\"请重点讲解 placeOrder 节点\"");
    expect(tracePayload).toContain("\"focusNodeId\":\"method:place-order\"");
    expect(tracePayload).toContain("\"granularity\":\"METHOD_CALL\"");
    expect(tracePayload).toContain("\"followUp\":{\"stepId\":\"step-place-order\",\"stepTitle\":\"Step 1 提交订单\",\"question\":\"订单失败时怎么处理？\"}");
  });
});

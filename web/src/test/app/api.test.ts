import { afterEach, describe, expect, it, vi } from "vitest";
import {
  acknowledgeSnapshot,
  announceFrontendReady,
  publishGraphChange,
  readBootstrapState,
  requestAuditAsync,
  requestAnalysisDisplayMode,
  requestCurrentEditorContextGraph,
  requestGraphBeautificationAsync,
  resolveInvestigationThread,
  retryLastAuditRequestAsync,
  updateWorkbenchSectionPreference,
  resetApiBridgeLifecycleStateForTest,
} from "../../app/api";
import { resetEditorTransportForTest } from "../../app/editorTransport";
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

describe("publishGraphChange", () => {
  afterEach(() => {
    resetEditorTransportForTest();
    resetApiBridgeLifecycleStateForTest();
    window.linkGraphBridge = undefined;
    window.linkGraphDebugTrace = undefined;
  });

  it("keeps edge metadata when syncing the graph back to the IDE bridge", () => {
    const graphChanged = vi.fn();
    window.linkGraphBridge = {
      graphChanged,
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

    publishGraphChange(nodes, edges);

    expect(graphChanged).toHaveBeenCalledWith({
      nodes: [
        expect.objectContaining({
          id: "anchor",
        }),
        expect.objectContaining({
          id: "callee",
        }),
      ],
      edges: [
        expect.objectContaining({
          id: "edge-call",
          fromNodeId: "anchor",
          toNodeId: "callee",
          metadata: {
            callOrder: "0",
          },
        }),
      ],
    });
  });

  it("does not copy canvas positions into semantic node metadata", () => {
    const graphChanged = vi.fn();
    window.linkGraphBridge = {
      graphChanged,
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

    publishGraphChange(nodes, []);

    expect(graphChanged.mock.calls[0]?.[0]?.nodes?.[0]?.metadata).toEqual({
      "linkGraph.manual": "true",
    });
  });

  it("strips legacy ui position keys from semantic node metadata", () => {
    const graphChanged = vi.fn();
    window.linkGraphBridge = {
      graphChanged,
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

    publishGraphChange(nodes, []);

    expect(graphChanged.mock.calls[0]?.[0]?.nodes?.[0]?.metadata).toEqual({
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
      visibleGraph: {
        nodes: [],
        edges: [],
      },
      workingGraph: {
        nodes: [],
        edges: [],
      },
      mermaidIssues: [],
      diffItems: [],
      syncPreviewItems: [],
    };

    expect(readBootstrapState()?.analysisDisplayMode).toBe("FACT_GRAPH");
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

    updateWorkbenchSectionPreference("audit.candidate-changes", true);

    expect(updateWorkbenchSectionPreferenceBridge).toHaveBeenCalledWith("audit.candidate-changes", true);
  });

  it("把当前编辑器上下文加载请求转发给 IDE bridge", () => {
    const requestCurrentEditorContextGraphBridge = vi.fn();
    window.linkGraphBridge = {
      requestCurrentEditorContextGraph: requestCurrentEditorContextGraphBridge,
    };

    requestCurrentEditorContextGraph();

    expect(requestCurrentEditorContextGraphBridge).toHaveBeenCalledTimes(1);
  });

  it("记录问答请求参数到前端调试 trace", () => {
    const requestAuditBridge = vi.fn();
    const traceSink = vi.fn();
    window.linkGraphBridge = {
      requestAudit: requestAuditBridge,
    };
    window.linkGraphDebugTrace = traceSink;

    requestAuditAsync("请围绕当前链路进行问答", ["method:place-order", "sql:insert-order"], "thread-risk-1");

    expect(requestAuditBridge).toHaveBeenCalledWith(
      "请围绕当前链路进行问答",
      ["method:place-order", "sql:insert-order"],
      "thread-risk-1",
    );
    const tracePayload = String(traceSink.mock.calls[0]?.[0] ?? "");
    expect(tracePayload).toContain("\"event\":\"api.requestAudit\"");
    expect(tracePayload).toContain("\"question\":\"请围绕当前链路进行问答\"");
    expect(tracePayload).toContain("\"selectedNodeIds\":[\"method:place-order\",\"sql:insert-order\"]");
    expect(tracePayload).toContain("\"sourceThreadId\":\"thread-risk-1\"");
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
    const retryLastAuditRequestBridge = vi.fn();
    window.linkGraphBridge = {
      retryLastAuditRequest: retryLastAuditRequestBridge,
    };

    retryLastAuditRequestAsync();

    expect(retryLastAuditRequestBridge).toHaveBeenCalledTimes(1);
  });

  it("在 bridge 已注入但缺少方法时返回协议未对齐错误", () => {
    window.linkGraphBridge = {
      requestAudit: vi.fn(),
    };

    const result = resolveInvestigationThread("thread-risk-1", "DEFERRED" satisfies RiskResolutionStatus);

    expect(result).toEqual({
      ok: false,
      message: "IDE bridge 协议未对齐，本次请求没有发出。",
      detailMessage: "IDE bridge 已注入，但当前未暴露 resolveInvestigationThread 方法，本次请求没有发出。",
    });
  });

  it("在 bridge 稍后注入时补发问答请求，而不是直接丢弃", () => {
    const requestAuditBridge = vi.fn();
    window.linkGraphBridge = undefined;

    const result = requestAuditAsync(
      "请围绕当前链路进行问答",
      ["method:place-order", "sql:insert-order"],
      "thread-risk-1",
    );

    expect(result).toEqual({ ok: true });
    expect(requestAuditBridge).not.toHaveBeenCalled();

    window.linkGraphBridge = {
      requestAudit: requestAuditBridge,
    };
    window.dispatchEvent(new Event("link-graph-bridge-ready"));

    expect(requestAuditBridge).toHaveBeenCalledTimes(1);
    expect(requestAuditBridge).toHaveBeenCalledWith(
      "请围绕当前链路进行问答",
      ["method:place-order", "sql:insert-order"],
      "thread-risk-1",
    );
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
    );
    const tracePayload = String(traceSink.mock.calls[0]?.[0] ?? "");
    expect(tracePayload).toContain("\"event\":\"api.requestGraphBeautification\"");
    expect(tracePayload).toContain("\"preferredStyle\":\"汇报版\"");
    expect(tracePayload).toContain("\"explanationFocus\":\"请重点讲解 placeOrder 节点\"");
    expect(tracePayload).toContain("\"granularity\":\"METHOD_CALL\"");
    expect(tracePayload).toContain("\"followUp\":{\"stepId\":\"step-place-order\",\"stepTitle\":\"Step 1 提交订单\",\"question\":\"订单失败时怎么处理？\"}");
  });
});

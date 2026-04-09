import { act, render, screen, within } from "@testing-library/react";
import { fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App, resolveAuditTargetNodeIds } from "./App";
import { dispatchBootstrapForTest, resetEditorTransportForTest } from "./editorTransport";
import { materializeThreeViewDocuments } from "./testBootstrapState";
import type { LinkGraphBootstrapState } from "./types";

const draftPatchPreview = {
  summary: "建议补一条失败补偿链路。",
  operations: [
    {
      id: "patch-add-compensate-node",
      action: "ADD_NODE",
      elementKind: "NODE",
      elementId: "method:submit-order-compensate",
      title: "新增失败补偿节点",
      summary: "补充订单提交后的失败补偿方法。",
      node: {
        id: "method:submit-order-compensate",
        type: "METHOD",
        title: "OrderService.compensateSubmit",
        signature: "com.example.OrderService.compensateSubmit(com.example.SubmitRequest):void",
        inputs: ["com.example.SubmitRequest"],
        outputs: ["void"],
        doc: "订单提交失败后的补偿处理。",
        certainty: "LLM_SUGGESTED",
        bindingStatus: "DESIGN_ONLY",
        sourceTag: "DRAFT_AI",
      },
      metadata: {},
    },
  ],
  addedNodeIds: ["method:submit-order-compensate"],
  removedNodeIds: [],
  addedEdgeIds: [],
  removedEdgeIds: [],
};

const bootstrapState = materializeThreeViewDocuments({
  visibleGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        location: "src/main/java/com/example/OrderController.java:8:1",
        signature: "com.example.OrderController.submit():void",
        inputs: ["java.lang.String", "com.example.SubmitRequest"],
        outputs: ["com.example.SubmitResult"],
        doc: "Submit order entry.",
        certainty: "PROVEN",
        bindingStatus: "BOUND",
        sourceTag: "FACT",
      },
      {
        id: "class:order-draft-dto",
        type: "CLASS",
        title: "OrderDraftDto",
        inputs: [],
        outputs: [],
        certainty: "LLM_SUGGESTED",
        bindingStatus: "DESIGN_ONLY",
        diffStatus: "ONLY_IN_MERMAID",
        sourceTag: "DRAFT_AI",
      },
    ],
    edges: [
      {
        id: "call:submit-order->order-draft-dto",
        type: "CALL",
        source: "method:submit-order",
        target: "class:order-draft-dto",
        sourceTag: "DRAFT_AI",
      },
    ],
  },
  referenceFactGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        location: "src/main/java/com/example/OrderController.java:8:1",
        signature: "com.example.OrderController.submit():void",
        inputs: ["java.lang.String", "com.example.SubmitRequest"],
        outputs: ["com.example.SubmitResult"],
        doc: "Submit order entry.",
        certainty: "PROVEN",
        bindingStatus: "BOUND",
        sourceTag: "FACT",
      },
    ],
    edges: [],
  },
  workingGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        location: "src/main/java/com/example/OrderController.java:8:1",
        signature: "com.example.OrderController.submit():void",
        inputs: ["java.lang.String", "com.example.SubmitRequest"],
        outputs: ["com.example.SubmitResult"],
        doc: "Submit order entry.",
        certainty: "PROVEN",
        bindingStatus: "BOUND",
        sourceTag: "FACT",
      },
      {
        id: "class:order-draft-dto",
        type: "CLASS",
        title: "OrderDraftDto",
        inputs: [],
        outputs: [],
        certainty: "LLM_SUGGESTED",
        bindingStatus: "DESIGN_ONLY",
        diffStatus: "ONLY_IN_MERMAID",
        sourceTag: "DRAFT_AI",
      },
    ],
    edges: [
      {
        id: "call:submit-order->order-draft-dto",
        type: "CALL",
        source: "method:submit-order",
        target: "class:order-draft-dto",
        sourceTag: "DRAFT_AI",
      },
    ],
  },
  designBaselineGraph: {
    nodes: [
      {
        id: "class:order-draft-dto",
        type: "CLASS",
        title: "OrderDraftDto",
        inputs: [],
        outputs: [],
        certainty: "PROVEN",
        bindingStatus: "DESIGN_ONLY",
        sourceTag: "DESIGN_BASELINE",
      },
    ],
    edges: [],
  },
  draftPatchPreview,
  auditResult: {
    source: "MOCK",
    question: "这个方法是否遗漏补偿链路？",
    answer: "建议补一条失败补偿链路。",
    promptPreview: "audit prompt preview",
    patch: draftPatchPreview,
    warnings: ["当前结果来自本地规则分析。"],
  },
  diffReviewResult: {
    source: "MOCK",
    question: "为什么设计节点还没落地？",
    answer: "设计基线包含 OrderDraftDto，但代码事实层还没有对应实现。",
    promptPreview: "diff prompt preview",
    patch: draftPatchPreview,
    warnings: [],
  },
  diffItems: [
    {
      id: "class:order-draft-dto",
      title: "OrderDraftDto",
      status: "ONLY_IN_MERMAID",
      description: "设计节点在代码中缺失。",
    },
  ],
  syncPreviewItems: [
    {
      id: "create-order-draft",
      title: "新增 OrderDraftDto",
      description: "根据 Mermaid 节点生成 DTO 类",
      risk: "LOW",
    },
  ],
  mermaidIssues: [
    {
      category: "SEMANTIC",
      code: "missing-method-signature",
      message: "方法节点 'draft:create-order' 缺少 signature 元数据。",
      line: 3,
      nodeId: "draft:create-order",
    },
  ],
  generationPlan: {
    source: "MOCK",
    summary: "新增 DTO，并对齐服务接线。",
    warnings: ["写入代码前请先检查 mapper 绑定。"],
    promptPreview: "提示词预览",
    items: [
      {
        id: "gen-1",
        title: "新增 OrderDraftDto",
        description: "生成 DTO 类骨架。",
        risk: "LOW",
        targetPath: "src/main/java/com/example/OrderDraftDto.java",
      },
    ],
  },
  generatedCodeDrafts: [
    {
      id: "draft-1",
      sourceNodeId: "class:order-draft-dto",
      title: "OrderDraftDto.java",
      targetPath: "src/main/java/com/example/OrderDraftDto.java",
      content: "package com.example;\nclass OrderDraftDto {}",
      warnings: [],
    },
  ],
  generatedCodeDraftWriteReport: {
    writtenFiles: ["src/main/java/com/example/OrderDraftDto.java"],
    skippedFiles: [],
    warnings: [],
  },
  lastDraftPatchApplyResult: null,
  graphBeautificationResult: {
    source: "MOCK",
    summaryTitle: "当前方法讲解",
    summary: "当前方法先处理输入参数，再进入 DTO 补齐相关分支。",
    sections: [
      {
        id: "current-method",
        title: "当前方法内部",
        content: "先进入 OrderController.submit，再触发下游关键动作。",
      },
      {
        id: "cross-method",
        title: "跨方法扩展",
        content: "当前可见下游节点仍偏少，后续可以继续展开。",
      },
    ],
    promptPreview: "beautification prompt preview",
    warnings: ["当前讲解结果来自占位实现。"],
  },
  selectedNodeId: "method:submit-order",
  sourceNavigationState: {
    phase: "IDLE",
    nodeId: null,
    result: null,
    targetPath: null,
    line: null,
    column: null,
    errorMessage: null,
  },
  operationFeedback: {
    level: "SUCCESS",
    message: "已加载当前方法链路：OrderController.submit",
  },
  analysisDisplayMode: "FACT_GRAPH",
  auditRequestState: {
    phase: "SUCCEEDED",
    errorMessage: null,
  },
  diffReviewRequestState: {
    phase: "SUCCEEDED",
    errorMessage: null,
  },
  generationPlanRequestState: {
    phase: "SUCCEEDED",
    errorMessage: null,
  },
  graphBeautificationRequestState: {
    phase: "SUCCEEDED",
    errorMessage: null,
  },
  codeDraftRequestState: {
    phase: "SUCCEEDED",
    errorMessage: null,
  },
  canUndoDraftPatchApply: false,
  lastAppliedDraftPatchSummary: null,
} as unknown as LinkGraphBootstrapState);

function canvasQueries() {
  return within(screen.getByTestId("graph-canvas-shell"));
}

describe("App", () => {
  beforeEach(() => {
    resetEditorTransportForTest();
    window.linkGraphBootstrap = structuredClone(bootstrapState);
    window.linkGraphBridge = {
      exportMermaid: vi.fn(),
      importMermaid: vi.fn(),
      showDiffMode: vi.fn(),
      requestSyncPreview: vi.fn(),
      requestAudit: vi.fn(),
      requestDiffReview: vi.fn(),
      requestGraphBeautification: vi.fn(),
      applyDraftPatchPreview: vi.fn(),
      clearDraftPatchPreview: vi.fn(),
      restoreDraftPatchPreview: vi.fn(),
      undoLastDraftPatchApply: vi.fn(),
      requestGenerationPlan: vi.fn(),
      requestCodeDrafts: vi.fn(),
      requestCurrentMethodGraph: vi.fn(),
      requestAnalysisDisplayMode: vi.fn(),
      requestOpenSettings: vi.fn(),
      applyCodeDrafts: vi.fn(),
      applySingleCodeDraft: vi.fn(),
      requestDraftNavigation: vi.fn(),
      graphChanged: vi.fn(),
      layoutChanged: vi.fn(),
      nodeSelected: vi.fn(),
      requestSourceNavigation: vi.fn(),
      requestExpandOverflowNode: vi.fn(),
    };
  });

  it("replays a DOM bootstrap event that fires before the app renders", async () => {
    window.linkGraphBootstrap = undefined;

    window.dispatchEvent(
      new CustomEvent("link-graph-bootstrap", {
        detail: {
          ...structuredClone(bootstrapState),
          workingGraph: structuredClone(bootstrapState.workingGraph),
          snapshotRevision: 7,
        },
      }),
    );

    render(<App />);

    expect(await screen.findByText("链路图画布")).toBeInTheDocument();
    expect(screen.getByText("OrderController")).toBeInTheDocument();
    expect(screen.getAllByText("SubmitResult submit(String, SubmitRequest)").length).toBeGreaterThan(0);
  });

  it("replays buffered bootstrap state that arrives before the app subscribes", async () => {
    window.linkGraphBootstrap = undefined;
    dispatchBootstrapForTest({
      sessionId: "session-1",
      revision: 1,
      state: {
        ...structuredClone(bootstrapState),
        workingGraph: structuredClone(bootstrapState.workingGraph),
        snapshotRevision: 1,
      },
    });

    render(<App />);

    expect(await screen.findByText("链路图画布")).toBeInTheDocument();
    expect(screen.getByText("OrderController")).toBeInTheDocument();
    expect(screen.getAllByText("SubmitResult submit(String, SubmitRequest)").length).toBeGreaterThan(0);
  });

  it("uses visibleGraph as the canvas graph during initial bootstrap", async () => {
    window.linkGraphBootstrap = materializeThreeViewDocuments({
      ...structuredClone(bootstrapState),
      visibleGraph: {
        nodes: [],
        edges: [],
      },
      workingGraph: structuredClone(bootstrapState.workingGraph),
    });

    render(<App />);

    expect(await screen.findByText("链路图画布")).toBeInTheDocument();
    expect(screen.getByText("画布里还没有节点")).toBeInTheDocument();
    expect(screen.queryByText("OrderController")).not.toBeInTheDocument();
  });

  it("uses referenceFactGraph when the legacy factGraph field is absent", async () => {
    const user = userEvent.setup();
    const firstReferenceFactNode = bootstrapState.referenceFactGraph?.nodes[0];
    if (!firstReferenceFactNode) {
      throw new Error("bootstrapState.referenceFactGraph must provide at least one node for this test.");
    }
    window.linkGraphBootstrap = materializeThreeViewDocuments({
      ...structuredClone(bootstrapState),
      referenceFactGraph: {
        nodes: [
          structuredClone(firstReferenceFactNode),
          {
            id: "class:order-service",
            type: "CLASS",
            title: "OrderService",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
          },
          {
            id: "sql:insert-order",
            type: "SQL",
            title: "insert into orders",
            inputs: [],
            outputs: [],
            certainty: "RULE_INFERRED",
            bindingStatus: "PARTIALLY_SYNCED",
            sourceTag: "FACT",
          },
        ],
        edges: [],
      },
    });

    render(<App />);

    fireEvent.contextMenu(screen.getByTestId("graph-canvas-shell"), { clientX: 220, clientY: 180 });
    await user.click(screen.getByRole("menuitem", { name: "审计当前范围" }));

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    expect(within(dialog).getByText("3 节点 / 0 连线")).toBeInTheDocument();
    expect(within(dialog).queryByText("尚未生成事实图")).not.toBeInTheDocument();
  });

  it("renders visibleGraph as the canvas graph even when workingGraph differs", async () => {
    window.linkGraphBootstrap = materializeThreeViewDocuments({
      visibleGraph: {
        nodes: [
          {
            id: "method:visible-submit",
            type: "METHOD",
            title: "VisibleController.submit",
            signature: "com.example.VisibleController.submit():void",
            inputs: ["java.lang.String"],
            outputs: ["void"],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
          },
        ],
        edges: [],
      },
      workingGraph: {
        nodes: [
          {
            id: "method:working-submit",
            type: "METHOD",
            title: "WorkingController.submit",
            signature: "com.example.WorkingController.submit():void",
            inputs: ["java.lang.String"],
            outputs: ["void"],
            certainty: "LLM_SUGGESTED",
            bindingStatus: "DESIGN_ONLY",
            sourceTag: "DRAFT_AI",
          },
        ],
        edges: [],
      },
      referenceFactGraph: null,
      designBaselineGraph: null,
      draftPatchPreview: null,
      auditResult: null,
      diffReviewResult: null,
      diffItems: [],
      syncPreviewItems: [],
      mermaidIssues: [],
      generationPlan: null,
      generatedCodeDrafts: [],
      generatedCodeDraftWarnings: [],
      generatedCodeDraftWriteReport: null,
      lastDraftPatchApplyResult: null,
      graphBeautificationResult: null,
      selectedNodeId: "method:visible-submit",
      operationFeedback: null,
      auditRequestState: { phase: "IDLE", errorMessage: null },
      diffReviewRequestState: { phase: "IDLE", errorMessage: null },
      generationPlanRequestState: { phase: "IDLE", errorMessage: null },
      graphBeautificationRequestState: { phase: "IDLE", errorMessage: null },
      codeDraftRequestState: { phase: "IDLE", errorMessage: null },
      canUndoDraftPatchApply: false,
      lastAppliedDraftPatchSummary: null,
    } as unknown as LinkGraphBootstrapState);

    render(<App />);

    expect(await screen.findByText("链路图画布")).toBeInTheDocument();
    expect(screen.getAllByText("VisibleController.submit").length).toBeGreaterThan(0);
    expect(screen.queryByText("WorkingController.submit")).not.toBeInTheDocument();
  });

  it("renders an empty workspace instead of demo sample data when bootstrap is absent", () => {
    window.linkGraphBootstrap = undefined;
    window.linkGraphBridge = undefined;

    render(<App />);

    expect(screen.getByText("画布里还没有节点")).toBeInTheDocument();
    expect(screen.queryByText("OrderService.place")).not.toBeInTheDocument();
    expect(screen.queryByText("insert into orders")).not.toBeInTheDocument();
    expect(screen.queryByText("创建订单并触发持久化处理。")).not.toBeInTheDocument();
  });

  it("prefers grouped selection for audit scope and falls back to whole graph when there is no group", () => {
    expect(resolveAuditTargetNodeIds(undefined, ["node-a", "node-b"])).toEqual(["node-a", "node-b"]);
    expect(resolveAuditTargetNodeIds(undefined, ["node-a"])).toEqual([]);
    expect(resolveAuditTargetNodeIds("node-z", ["node-a", "node-b"])).toEqual(["node-z"]);
  });

  it("renders a compact Chinese workspace and points method loading back to code right-click", () => {
    render(<App />);

    expect(screen.getByText("链路图画布")).toBeInTheDocument();
    expect(screen.getByText("在代码中右键方法，可直接查看完整链路或追加为节点。")).toBeInTheDocument();
    expect(screen.getByText("已加载当前方法链路：OrderController.submit")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "更多操作" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "选择工具" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "审计当前范围" })).not.toBeInTheDocument();
    expect(screen.queryByText("Link Graph")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /加载当前方法/i })).not.toBeInTheDocument();
    expect(screen.getByText("当前选中")).toBeInTheDocument();
    expect(screen.queryByText("节点 2")).not.toBeInTheDocument();
    expect(screen.queryByText("连线 1")).not.toBeInTheDocument();
    expect(screen.queryByText("事实层已加载")).not.toBeInTheDocument();
    expect(screen.queryByText("草稿层就绪")).not.toBeInTheDocument();
    expect(screen.queryByText("设计基线已导入")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "导入 Mermaid" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "导出 Mermaid" })).not.toBeInTheDocument();
  });

  it("keeps the canvas primary while mirroring selected-node actions in the summary panel", async () => {
    render(<App />);

    expect(canvasQueries().getByText("Submit order entry.")).toBeInTheDocument();
    expect(canvasQueries().getByText("OrderController")).toBeInTheDocument();
    expect(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "查看详情" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "打开源码" })).toBeInTheDocument();
  });

  it("keeps the current-method anchor and fact direction markers when the backend only updates the selected node on the same graph", async () => {
    render(<App />);

    const initialMarkers = await screen.findByLabelText("链路列标记");
    expect(within(initialMarkers).getByText("上游")).toBeInTheDocument();
    expect(within(initialMarkers).getByText("当前")).toBeInTheDocument();
    expect(within(initialMarkers).getByText("下游")).toBeInTheDocument();

    act(() => {
      window.dispatchEvent(
        new CustomEvent("link-graph-bootstrap", {
          detail: {
            ...structuredClone(bootstrapState),
            selectedNodeId: "class:order-draft-dto",
            lastMessageType: "nodeSelected",
            operationFeedback: {
              level: "INFO",
              message: "已打开源码：OrderDraftDto",
            },
          },
        }),
      );
    });

    const summary = screen.getByLabelText("图阅读摘要");
    const markers = await screen.findByLabelText("链路列标记");
    expect(within(summary).getByText("当前方法")).toBeInTheDocument();
    expect(within(summary).getByText("OrderController.submit")).toBeInTheDocument();
    expect(within(summary).getByText("当前选中")).toBeInTheDocument();
    expect(within(summary).getByText("OrderDraftDto")).toBeInTheDocument();
    expect(screen.getByText("当前选中只是你正在看的节点，整张图仍围绕“当前方法”展开。")).toBeInTheDocument();
    expect(within(markers).getByText("上游")).toBeInTheDocument();
    expect(within(markers).getByText("当前")).toBeInTheDocument();
    expect(within(markers).getByText("下游")).toBeInTheDocument();
  });

  it("keeps collapsed downstream state when bootstrap only changes the selected node", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "折叠整个下游子树" }));

    expect(canvasQueries().queryByText("OrderDraftDto")).not.toBeInTheDocument();

    act(() => {
      window.dispatchEvent(
        new CustomEvent("link-graph-bootstrap", {
          detail: {
            ...structuredClone(bootstrapState),
            selectedNodeId: "class:order-draft-dto",
            lastMessageType: "nodeSelected",
            operationFeedback: {
              level: "INFO",
              message: "只是同步选中节点，不应该重置折叠态",
            },
          },
        }),
      );
    });

    expect(canvasQueries().queryByText("OrderDraftDto")).not.toBeInTheDocument();
    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    expect(screen.getByRole("menuitem", { name: "展开整个下游子树" })).toBeInTheDocument();
  });

  it("opens node details from the context menu even when the selected summary stays mounted", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "查看详情" }));

    expect(screen.getByRole("dialog", { name: "节点详情" })).toBeInTheDocument();
    expect(screen.getByText("当前选中")).toBeInTheDocument();
  });

  it("opens node details from the canvas context menu and sends graph updates back through the bridge", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "查看详情" }));

    expect(screen.getByRole("heading", { name: "节点详情" })).toBeInTheDocument();
    expect(screen.getByDisplayValue("OrderController.submit")).toBeInTheDocument();

    await user.clear(screen.getByLabelText("标题"));
    await user.type(screen.getByLabelText("标题"), "OrderController.submitDraft");
    await user.clear(screen.getByLabelText("输入"));
    await user.type(screen.getByLabelText("输入"), "java.lang.String, com.example.DraftRequest");
    await user.clear(screen.getByLabelText("输出"));
    await user.type(screen.getByLabelText("输出"), "com.example.DraftResult");
    await user.click(screen.getByRole("button", { name: "保存修改" }));

    expect(window.linkGraphBridge?.graphChanged).toHaveBeenCalledTimes(1);
    expect(window.linkGraphBridge?.graphChanged).toHaveBeenCalledWith({
      nodes: expect.arrayContaining([
        expect.objectContaining({
          id: "method:submit-order",
          title: "OrderController.submitDraft",
          type: "METHOD",
          inputs: ["java.lang.String", "com.example.DraftRequest"],
          outputs: ["com.example.DraftResult"],
        }),
      ]),
      edges: [
        expect.objectContaining({
          id: "call:submit-order->order-draft-dto",
          fromNodeId: "method:submit-order",
          toNodeId: "class:order-draft-dto",
        }),
      ],
    });
  });

  it("routes toolbar actions through the IDE bridge", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: /导入 mermaid/i }));
    expect(screen.getByRole("dialog", { name: "导入 Mermaid" })).toBeInTheDocument();
    await user.type(screen.getByLabelText("Mermaid 内容"), "graph TD\nA-->B");
    await user.click(screen.getByRole("button", { name: "确认导入" }));
    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: /导出 mermaid/i }));
    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: /对比代码/i }));
    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: /同步预览/i }));
    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: /生成计划/i }));
    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: /链路讲解/i }));
    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: /生成草稿/i }));
    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: /设置/i }));
    await user.click(screen.getByRole("button", { name: /写入全部草稿/i }));

    expect(window.linkGraphBridge?.importMermaid).toHaveBeenCalledWith("graph TD\nA-->B");
    expect(window.linkGraphBridge?.exportMermaid).toHaveBeenCalledTimes(1);
    expect(window.linkGraphBridge?.showDiffMode).toHaveBeenCalledTimes(1);
    expect(window.linkGraphBridge?.requestSyncPreview).toHaveBeenCalledTimes(1);
    expect(window.linkGraphBridge?.requestGenerationPlan).toHaveBeenCalledTimes(1);
    expect(window.linkGraphBridge?.requestGraphBeautification).toHaveBeenCalledTimes(1);
    expect(window.linkGraphBridge?.requestCodeDrafts).toHaveBeenCalledTimes(1);
    expect(window.linkGraphBridge?.requestOpenSettings).toHaveBeenCalledTimes(1);
    expect(window.linkGraphBridge?.applyCodeDrafts).toHaveBeenCalledTimes(1);
  });

  it("does not report false success for toolbar commands when the IDE bridge is unavailable", async () => {
    const user = userEvent.setup();
    window.linkGraphBridge = undefined;
    render(<App />);

    const commands = [
      { label: /导出 mermaid/i, optimisticMessage: "已请求导出 Mermaid。" },
      { label: /对比代码/i, optimisticMessage: "已打开代码对比。" },
      { label: /同步预览/i, optimisticMessage: "已打开同步预览。" },
      { label: /设置/i, optimisticMessage: "正在打开 IDE 设置..." },
    ];

    for (const command of commands) {
      await user.click(screen.getByRole("button", { name: "更多操作" }));
      await user.click(screen.getByRole("menuitem", { name: command.label }));

      expect(screen.queryByText(command.optimisticMessage)).not.toBeInTheDocument();
      const noticeDialog = await screen.findByRole("dialog", { name: "请求状态通知" });
      expect(within(noticeDialog).getByText("IDE bridge 尚未就绪，本次请求没有发出。")).toBeInTheDocument();
      await user.click(within(noticeDialog).getByRole("button", { name: "我知道了" }));
    }
  });

  it("renders the current analysis display mode and routes mode switches through the IDE bridge", async () => {
    const user = userEvent.setup();
    render(<App />);

    expect(screen.getByRole("button", { name: "事实链路" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "流程图" })).toHaveAttribute("aria-pressed", "false");

    await user.click(screen.getByRole("button", { name: "流程图" }));
    expect(window.linkGraphBridge?.requestAnalysisDisplayMode).toHaveBeenCalledWith("FLOWCHART");
    expect(screen.getByRole("button", { name: "事实链路" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "流程图" })).toHaveAttribute("aria-pressed", "false");

    act(() => {
      dispatchBootstrapForTest({
        sessionId: "session-mode-1",
        revision: 2,
        state: materializeThreeViewDocuments({
          ...structuredClone(bootstrapState),
          analysisDisplayMode: "FLOWCHART",
          visibleGraph: {
            nodes: [
              {
                id: "terminal:return",
                type: "TERMINAL",
                title: "返回",
                inputs: [],
                outputs: [],
                certainty: "PROVEN",
                bindingStatus: "BOUND",
                metadata: {
                  "terminal.kind": "RETURN",
                },
              },
            ],
            edges: [],
          },
          workingGraph: {
            nodes: [
              {
                id: "terminal:return",
                type: "TERMINAL",
                title: "返回",
                inputs: [],
                outputs: [],
                certainty: "PROVEN",
                bindingStatus: "BOUND",
                metadata: {
                  "terminal.kind": "RETURN",
                },
              },
            ],
            edges: [],
          },
        }),
      });
    });

    expect(await screen.findByRole("button", { name: "流程图" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "事实链路" })).toHaveAttribute("aria-pressed", "false");
    expect(screen.getAllByText("流程终止").length).toBeGreaterThan(0);
  });

  it("keeps the flowchart canvas context menu editable after switching away from fact-graph mode", () => {
    render(<App />);

    act(() => {
      dispatchBootstrapForTest({
        sessionId: "session-readonly-menu",
        revision: 3,
        state: materializeThreeViewDocuments({
          ...structuredClone(bootstrapState),
          analysisDisplayMode: "FLOWCHART",
          visibleGraph: {
            nodes: [
              {
                id: "terminal:return",
                type: "TERMINAL",
                title: "返回",
                inputs: [],
                outputs: [],
                certainty: "PROVEN",
                bindingStatus: "BOUND",
                metadata: {
                  "terminal.kind": "RETURN",
                  "flowchart.kind": "TERMINAL",
                },
              },
            ],
            edges: [],
          },
          workingGraph: {
            nodes: [
              {
                id: "terminal:return",
                type: "TERMINAL",
                title: "返回",
                inputs: [],
                outputs: [],
                certainty: "PROVEN",
                bindingStatus: "BOUND",
                metadata: {
                  "terminal.kind": "RETURN",
                  "flowchart.kind": "TERMINAL",
                },
              },
            ],
            edges: [],
          },
          referenceFactGraph: {
            nodes: [],
            edges: [],
          },
        }),
      });
    });

    fireEvent.contextMenu(screen.getByTestId("graph-canvas-shell"), { clientX: 240, clientY: 200 });
    expect(screen.getByRole("menuitem", { name: "新增方法节点" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "导入 Mermaid" })).toBeInTheDocument();

    fireEvent.contextMenu(canvasQueries().getByText("返回"));
    expect(screen.getByRole("menuitem", { name: "查看详情" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "删除节点" })).toBeInTheDocument();
  });

  it("opens compare and generation results inside a visible overlay panel", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "对比代码" }));

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    expect(dialog).toBeInTheDocument();
    expect(within(dialog).getByText("代码与 Mermaid")).toBeInTheDocument();
    expect(within(dialog).getByText("设计节点在代码中缺失。")).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "计划" }));
    expect(within(dialog).getByText("Mermaid 到代码")).toBeInTheDocument();
  });

  it("opens beautification results inside the overlay panel and requests explanation from the IDE bridge", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "链路讲解" }));

    expect(window.linkGraphBridge?.requestGraphBeautification).toHaveBeenCalledWith("", undefined, undefined);

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    expect(within(dialog).getByRole("button", { name: "讲解" })).toBeInTheDocument();
    expect(within(dialog).getByRole("heading", { name: "链路讲解" })).toBeInTheDocument();
    expect(within(dialog).getByText("正在生成链路讲解，请稍候。")).toBeInTheDocument();
    expect(within(dialog).getByText("讲解会优先读取当前画布与当前方法视图，不会继续沿用上一轮结果。")).toBeInTheDocument();
    expect(within(dialog).queryByText("beautification prompt preview")).not.toBeInTheDocument();

    act(() => {
      window.dispatchEvent(
        new CustomEvent("link-graph-bootstrap", {
          detail: {
            ...structuredClone(bootstrapState),
            lastMessageType: "graphBeautificationResult",
          },
        }),
      );
    });

    expect(await within(dialog).findByText("当前方法讲解")).toBeInTheDocument();
    expect(within(dialog).getByText("当前方法先处理输入参数，再进入 DTO 补齐相关分支。")).toBeInTheDocument();
    expect(within(dialog).getByText("当前方法内部")).toBeInTheDocument();
    expect(within(dialog).getByText("先进入 OrderController.submit，再触发下游关键动作。")).toBeInTheDocument();
    expect(within(dialog).getByText("当前讲解结果来自占位实现。")).toBeInTheDocument();

    expect(within(dialog).getByText("真实性边界")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "查看调试用提示词" }));
    expect(within(dialog).getByText("beautification prompt preview")).toBeInTheDocument();
  });

  it("routes the selected-node explain action through the IDE bridge and opens the beautification panel", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "讲解当前链路" }));

    expect(window.linkGraphBridge?.requestGraphBeautification).toHaveBeenCalledWith(
      "",
      undefined,
      expect.stringContaining("OrderController.submit"),
    );
    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    expect(within(dialog).getByRole("heading", { name: "链路讲解" })).toBeInTheDocument();
  });

  it("routes the selected-node audit action through the IDE bridge and opens the audit panel", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "审计当前节点" }));

    expect(window.linkGraphBridge?.requestAudit).toHaveBeenCalledWith(
      expect.stringContaining("OrderController.submit"),
      ["method:submit-order"],
    );
    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    expect(within(dialog).getByRole("heading", { name: "链路审计" })).toBeInTheDocument();
    expect(within(dialog).getByDisplayValue(/OrderController\.submit/)).toBeInTheDocument();
  });

  it("routes node context-menu explain through the IDE bridge", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "讲解当前链路" }));

    expect(window.linkGraphBridge?.requestGraphBeautification).toHaveBeenCalledWith(
      "",
      undefined,
      expect.stringContaining("OrderController.submit"),
    );
    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    expect(within(dialog).getByRole("heading", { name: "链路讲解" })).toBeInTheDocument();
  });

  it("当 IDE bridge 未就绪时 链路讲解不能伪装成已提交状态", async () => {
    const user = userEvent.setup();
    window.linkGraphBridge = undefined;
    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "链路讲解" }));

    expect(screen.queryByText("已提交链路讲解请求")).not.toBeInTheDocument();
    const noticeDialog = await screen.findByRole("dialog", { name: "请求状态通知" });
    expect(within(noticeDialog).getByText("IDE bridge 尚未就绪，本次请求没有发出。")).toBeInTheDocument();
    expect(within(noticeDialog).getByText("JCEF 页面与 IDEA 后端连接尚未建立，请等待页面初始化完成后重试。")).toBeInTheDocument();
  });

  it("keeps the local submission placeholder generic until backend telemetry confirms execution mode", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "链路讲解" }));

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    expect(within(dialog).getByText("已提交链路讲解请求")).toBeInTheDocument();
    expect(within(dialog).getByText("等待后端确认执行方式与执行阶段。")).toBeInTheDocument();
    expect(within(dialog).queryByText(/如果当前走远程模型/)).not.toBeInTheDocument();

    act(() => {
      dispatchBootstrapForTest({
        sessionId: "session-request-telemetry-1",
        revision: 3,
        state: {
          ...structuredClone(bootstrapState),
          graphBeautificationResult: null,
          graphBeautificationRequestState: {
            phase: "RUNNING",
            errorMessage: null,
            statusMessage: "正在执行链路讲解本地规则",
            detailMessage: "当前直接使用本地规则或模板，不会发起远程 LLM 请求。",
            startedAtEpochMillis: 1_710_000_000_000,
            finishedAtEpochMillis: null,
            streaming: false,
            fallbackUsed: false,
            requestId: 52,
            scene: "链路讲解",
            executionMode: "LOCAL_RULE",
            providerLabel: "OpenAI Compatible",
            model: "gpt-5.4",
            endpointSummary: "example.com/v1/chat/completions",
            promptPreviewAvailable: true,
          } as never,
        },
      });
    });

    expect(await within(dialog).findByText("正在执行链路讲解本地规则")).toBeInTheDocument();
    expect(within(dialog).getByText("#52")).toBeInTheDocument();
    expect(within(dialog).getByText("本地规则")).toBeInTheDocument();
    expect(within(dialog).getByText("OpenAI Compatible")).toBeInTheDocument();
    expect(within(dialog).getByText("gpt-5.4")).toBeInTheDocument();
    expect(within(dialog).getByText("example.com/v1/chat/completions")).toBeInTheDocument();
    expect(within(dialog).getByText("可查看")).toBeInTheDocument();
  });

  it("keeps the result panel open when the user clicks the backdrop", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "对比代码" }));

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    fireEvent.click(dialog.parentElement as HTMLElement);

    expect(screen.getByRole("dialog", { name: "结果面板" })).toBeInTheDocument();
  });

  it("opens audit workflow, shows dual-layer summaries, and routes audit questions through the IDE bridge", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(screen.getByTestId("graph-canvas-shell"), { clientX: 220, clientY: 180 });
    await user.click(screen.getByRole("menuitem", { name: "审计当前范围" }));

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    expect(within(dialog).getByText("事实层")).toBeInTheDocument();
    expect(within(dialog).getByText("草稿层")).toBeInTheDocument();
    expect(within(dialog).getByText("设计基线")).toBeInTheDocument();

    await user.clear(within(dialog).getByLabelText("审计问题"));
    await user.type(within(dialog).getByLabelText("审计问题"), "这个方法是否遗漏补偿链路？");
    await user.click(within(dialog).getByRole("button", { name: "开始审计" }));

    expect(window.linkGraphBridge?.requestAudit).toHaveBeenCalledWith(
      "这个方法是否遗漏补偿链路？",
      [],
    );
  });

  it("当 IDE bridge 未就绪时 审计请求不能伪装成已提交状态", async () => {
    const user = userEvent.setup();
    window.linkGraphBridge = undefined;
    render(<App />);

    fireEvent.contextMenu(screen.getByTestId("graph-canvas-shell"), { clientX: 220, clientY: 180 });
    await user.click(screen.getByRole("menuitem", { name: "审计当前范围" }));

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    await user.type(within(dialog).getByLabelText("审计问题"), "请检查这条链路是否存在绕过校验");
    await user.click(within(dialog).getByRole("button", { name: "开始审计" }));

    expect(within(dialog).queryByText("已提交审计请求")).not.toBeInTheDocument();
    const noticeDialog = await screen.findByRole("dialog", { name: "请求状态通知" });
    expect(within(noticeDialog).getByText("IDE bridge 尚未就绪，本次请求没有发出。")).toBeInTheDocument();
    expect(within(noticeDialog).getByText("JCEF 页面与 IDEA 后端连接尚未建立，请等待页面初始化完成后重试。")).toBeInTheDocument();
  });

  it("shows audit answer and applies selected patch operations from preview", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "设为审计范围起点" }));

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    expect(within(dialog).getAllByText("建议补一条失败补偿链路。")).not.toHaveLength(0);
    expect(within(dialog).getByText("当前结果来自本地规则分析。")).toBeInTheDocument();
    expect(within(dialog).getByText("来源 本地规则")).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "查看并写入审计草稿" }));
    expect(within(dialog).getByText("新增失败补偿节点")).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "应用选中变更" }));
    expect(window.linkGraphBridge?.applyDraftPatchPreview).toHaveBeenCalledWith(["patch-add-compensate-node"]);
  });

  it("shows the applied patch result after the backend confirms the draft patch write-back", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "设为审计范围起点" }));

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    await user.click(within(dialog).getByRole("button", { name: "查看并写入审计草稿" }));
    await user.click(within(dialog).getByRole("button", { name: "应用选中变更" }));

    act(() => {
      window.dispatchEvent(
        new CustomEvent("link-graph-bootstrap", {
          detail: materializeThreeViewDocuments({
            ...structuredClone(bootstrapState),
            visibleGraph: {
              nodes: [
                ...structuredClone(bootstrapState.visibleGraph.nodes),
                {
                  id: "method:submit-order-compensate",
                  type: "METHOD",
                  title: "OrderService.compensateSubmit",
                  signature: "com.example.OrderService.compensateSubmit(com.example.SubmitRequest):void",
                  inputs: ["com.example.SubmitRequest"],
                  outputs: ["void"],
                  doc: "订单提交失败后的补偿处理。",
                  certainty: "LLM_SUGGESTED",
                  bindingStatus: "DESIGN_ONLY",
                  sourceTag: "DRAFT_AI",
                },
              ],
              edges: structuredClone(bootstrapState.visibleGraph.edges),
            },
            workingGraph: {
              nodes: [
                ...structuredClone(bootstrapState.workingGraph.nodes),
                {
                  id: "method:submit-order-compensate",
                  type: "METHOD",
                  title: "OrderService.compensateSubmit",
                  signature: "com.example.OrderService.compensateSubmit(com.example.SubmitRequest):void",
                  inputs: ["com.example.SubmitRequest"],
                  outputs: ["void"],
                  doc: "订单提交失败后的补偿处理。",
                  certainty: "LLM_SUGGESTED",
                  bindingStatus: "DESIGN_ONLY",
                  sourceTag: "DRAFT_AI",
                },
              ],
              edges: structuredClone(bootstrapState.workingGraph.edges),
            },
            draftPatchPreview: null,
            canUndoDraftPatchApply: true,
            lastAppliedDraftPatchSummary: "建议补一条失败补偿链路。",
            lastDraftPatchApplyResult: {
              summary: "已应用 1 条草稿图变更。",
              appliedOperationCount: 1,
              appliedNodeIds: ["method:submit-order-compensate"],
              appliedEdgeIds: [],
              focusNodeId: "method:submit-order-compensate",
              appliedTargets: ["OrderService.compensateSubmit"],
            },
            lastMessageType: "draftPatchApplied",
            operationFeedback: {
              level: "SUCCESS",
              message: "已将草稿 patch 应用到当前工作图。",
            },
          }),
        }),
      );
    });

    expect(await within(dialog).findByText("最近一次应用结果")).toBeInTheDocument();
    expect(within(dialog).getByText("已应用 1 条草稿图变更。")).toBeInTheDocument();
    expect(within(dialog).getByText("OrderService.compensateSubmit")).toBeInTheDocument();
    expect(canvasQueries().getByText("void compensateSubmit(SubmitRequest)")).toBeInTheDocument();
  });

  it("shows an in-panel retry action when beautification generation fails", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "链路讲解" }));

    act(() => {
      window.dispatchEvent(
        new CustomEvent("link-graph-bootstrap", {
          detail: {
            ...structuredClone(bootstrapState),
            graphBeautificationResult: null,
            graphBeautificationRequestState: {
              phase: "FAILED",
              errorMessage: "生成链路讲解失败：HTTP 503",
            },
          },
        }),
      );
    });

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    expect(await within(dialog).findByText("生成链路讲解失败：HTTP 503")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "重试生成讲解" }));

    expect(window.linkGraphBridge?.requestGraphBeautification).toHaveBeenCalledTimes(2);
  });

  it("opens a failure dialog with timeout details when an async LLM request stalls too long", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "链路讲解" }));

    act(() => {
      window.dispatchEvent(
        new CustomEvent("link-graph-bootstrap", {
          detail: {
            ...structuredClone(bootstrapState),
            graphBeautificationResult: null,
            graphBeautificationRequestState: {
              phase: "TIMED_OUT",
              statusMessage: "链路讲解请求已超时",
              errorMessage: "远程 LLM 在等待窗口内未返回结果。",
              detailMessage: "当前远程模型不是流式输出，只有完整结果返回后才会展示内容。",
              streaming: false,
              fallbackUsed: false,
            },
          },
        }),
      );
    });

    const noticeDialog = await screen.findByRole("dialog", { name: "请求状态通知" });
    expect(within(noticeDialog).getByText("链路讲解请求已超时")).toBeInTheDocument();
    expect(within(noticeDialog).getByText("远程 LLM 在等待窗口内未返回结果。")).toBeInTheDocument();
    expect(
      within(noticeDialog).getByText("当前远程模型不是流式输出，只有完整结果返回后才会展示内容。"),
    ).toBeInTheDocument();

    await user.click(within(noticeDialog).getByRole("button", { name: "关闭提示" }));
    expect(screen.queryByRole("dialog", { name: "请求状态通知" })).not.toBeInTheDocument();
  });

  it("clears the previous audit answer immediately when starting a new audit request", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "设为审计范围起点" }));

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    expect(within(dialog).getAllByText("建议补一条失败补偿链路。")).not.toHaveLength(0);

    await user.clear(within(dialog).getByLabelText("审计问题"));
    await user.type(within(dialog).getByLabelText("审计问题"), "请基于新节点重新补全链路");
    await user.click(within(dialog).getByRole("button", { name: "开始审计" }));

    expect(within(dialog).queryByText("建议补一条失败补偿链路。")).not.toBeInTheDocument();
    expect(within(dialog).getByText("这里会展示审计回答，并把新增/修订节点先生成到草稿预览，不会直接改动事实图。")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "审计中..." })).toBeDisabled();
  });

  it("supports clearing the current patch preview and restoring it from the audit result", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "设为审计范围起点" }));
    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    await user.click(within(dialog).getByRole("button", { name: "查看并写入审计草稿" }));
    await user.click(within(dialog).getByRole("button", { name: "清空预览" }));

    expect(window.linkGraphBridge?.clearDraftPatchPreview).toHaveBeenCalledTimes(1);
    expect(within(dialog).getByText("尚未生成 patch")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "恢复审计草稿" })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "恢复差异草稿" })).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "恢复审计草稿" }));
    expect(window.linkGraphBridge?.restoreDraftPatchPreview).toHaveBeenCalledWith("AUDIT");
  });

  it("supports undoing the last applied patch from the patch preview panel", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = {
      ...structuredClone(bootstrapState),
      canUndoDraftPatchApply: true,
      lastAppliedDraftPatchSummary: "上次已应用 1 条草稿图变更。",
    };

    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "设为审计范围起点" }));
    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    await user.click(within(dialog).getByRole("button", { name: "查看并写入审计草稿" }));
    await user.click(within(dialog).getByRole("button", { name: "撤销上次应用" }));

    expect(window.linkGraphBridge?.undoLastDraftPatchApply).toHaveBeenCalledTimes(1);
  });

  it("shows restore buttons for diff and last-applied previews after the preview is cleared", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = {
      ...structuredClone(bootstrapState),
      canUndoDraftPatchApply: true,
      lastAppliedDraftPatchSummary: "上次已应用 1 条草稿图变更。",
    };

    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "设为审计范围起点" }));
    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    await user.click(within(dialog).getByRole("button", { name: "查看并写入审计草稿" }));
    await user.click(within(dialog).getByRole("button", { name: "清空预览" }));
    await user.click(within(dialog).getByRole("button", { name: "恢复差异草稿" }));
    await user.click(within(dialog).getByRole("button", { name: "恢复上次应用前预览" }));

    expect(window.linkGraphBridge?.restoreDraftPatchPreview).toHaveBeenNthCalledWith(1, "DIFF_REVIEW");
    expect(window.linkGraphBridge?.restoreDraftPatchPreview).toHaveBeenNthCalledWith(2, "LAST_APPLIED");
  });

  it("supports diff Q&A and opens patch preview from the compare panel", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "对比代码" }));

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    await user.click(within(dialog).getByRole("button", { name: /定位 class:order-draft-dto/i }));
    await user.type(within(dialog).getByLabelText("差异问题"), "为什么设计节点还没落地？");
    await user.click(within(dialog).getByRole("button", { name: "继续差异问答" }));

    expect(window.linkGraphBridge?.requestDiffReview).toHaveBeenCalledWith("为什么设计节点还没落地？", ["class:order-draft-dto"]);
    act(() => {
      window.dispatchEvent(
        new CustomEvent("link-graph-bootstrap", {
          detail: {
            ...structuredClone(bootstrapState),
            diffReviewRequestState: {
              phase: "SUCCEEDED",
              errorMessage: null,
            },
          },
        }),
      );
    });

    expect(within(dialog).getByText("设计基线包含 OrderDraftDto，但代码事实层还没有对应实现。")).toBeInTheDocument();
    expect(within(dialog).getByText("当前对比对象：设计基线 Mermaid vs 代码事实链路")).toBeInTheDocument();
    expect(within(dialog).getByText("来源 本地规则")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "查看并写入修订草稿" }));
    expect(within(dialog).getByText("新增失败补偿节点")).toBeInTheDocument();
  });

  it("shows diff loading state from explicit request state", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = {
      ...structuredClone(bootstrapState),
      diffReviewResult: null,
      diffReviewRequestState: {
        phase: "RUNNING",
        errorMessage: null,
      },
      operationFeedback: {
        level: "INFO",
        message: "这条消息不应该决定差异请求态",
      },
    };

    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "对比代码" }));

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    expect(within(dialog).getByText("正在生成差异解释，请稍候。")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "差异问答中..." })).toBeDisabled();
  });

  it("routes node context actions and per-draft actions through the IDE bridge", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "打开源码" }));
    expect(window.linkGraphBridge?.requestSourceNavigation).toHaveBeenCalledWith("method:submit-order");
    expect(screen.getByText("正在定位源码：OrderController.submit")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: /生成草稿/i }));

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    expect(within(dialog).getByText("正在生成代码草稿，请稍候。")).toBeInTheDocument();

    act(() => {
      window.dispatchEvent(
        new CustomEvent("link-graph-bootstrap", {
          detail: {
            ...structuredClone(bootstrapState),
            lastMessageType: "requestCodeDrafts",
          },
        }),
      );
    });

    await user.click(await within(dialog).findByRole("button", { name: /写入 orderdraftdto\.java/i }));
    await user.click(within(dialog).getByRole("button", { name: /打开 orderdraftdto\.java/i }));

    expect(window.linkGraphBridge?.requestCodeDrafts).toHaveBeenCalledTimes(1);
    expect(window.linkGraphBridge?.applySingleCodeDraft).toHaveBeenCalledWith("draft-1");
    expect(window.linkGraphBridge?.requestDraftNavigation).toHaveBeenCalledWith(
      "src/main/java/com/example/OrderDraftDto.java",
    );
  });

  it("does not enter running source-navigation state when the IDE bridge is unavailable", async () => {
    const user = userEvent.setup();
    window.linkGraphBridge = undefined;
    render(<App />);

    await user.click(screen.getByRole("button", { name: "打开源码" }));

    expect(screen.queryByText("正在定位源码：OrderController.submit")).not.toBeInTheDocument();
    const noticeDialog = await screen.findByRole("dialog", { name: "请求状态通知" });
    expect(within(noticeDialog).getByText("IDE bridge 尚未就绪，本次请求没有发出。")).toBeInTheDocument();
    expect(within(noticeDialog).getByText("JCEF 页面与 IDEA 后端连接尚未建立，请等待页面初始化完成后重试。")).toBeInTheDocument();
  });

  it("deletes a node together with its downstream subtree from the canvas context menu", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "删除节点及子节点" }));

    expect(window.linkGraphBridge?.graphChanged).toHaveBeenCalledWith({
      nodes: [],
      edges: [],
    });
    expect(screen.getByText("画布里还没有节点")).toBeInTheDocument();
  });

  it("clears stale audit content after the graph changes locally", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "设为审计范围起点" }));

    const dialog = screen.getByRole("dialog", { name: "结果面板" });
    expect(within(dialog).getByText("建议补一条失败补偿链路。")).toBeInTheDocument();

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "删除节点及子节点" }));

    expect(within(dialog).queryByText("建议补一条失败补偿链路。")).not.toBeInTheDocument();
    expect(
      within(dialog).getByText("这里会展示审计回答，并把新增/修订节点先生成到草稿预览，不会直接改动事实图。"),
    ).toBeInTheDocument();
  });

  it("allows source navigation from a signature-only node", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = materializeThreeViewDocuments({
      ...structuredClone(bootstrapState),
      visibleGraph: {
        nodes: [
          {
            id: "method:signature-only",
            type: "METHOD",
            title: "OrderController.replay",
            signature: "com.example.OrderController.replay(java.lang.String):void",
            inputs: ["java.lang.String"],
            outputs: ["void"],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
          },
        ],
        edges: [],
      },
      selectedNodeId: "method:signature-only",
    });

    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("void replay(String)"));
    await user.click(screen.getByRole("menuitem", { name: "打开源码" }));

    expect(window.linkGraphBridge?.requestSourceNavigation).toHaveBeenCalledWith("method:signature-only");
    expect(screen.getByText("正在定位源码：OrderController.replay")).toBeInTheDocument();
  });

  it("routes overflow-node expansion back through the IDE bridge", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = materializeThreeViewDocuments({
      ...structuredClone(bootstrapState),
      visibleGraph: {
        nodes: [
          structuredClone(bootstrapState.visibleGraph.nodes[0]),
          {
            id: "overflow:downstream",
            type: "UNCERTAIN_LINK",
            title: "下游调用过多",
            signature: "当前为了避免卡顿，只展示了核心分支",
            inputs: [],
            outputs: [],
            doc: "这是提图阶段限流节点，可以继续展开。",
            certainty: "RULE_INFERRED",
            bindingStatus: "PARTIALLY_SYNCED",
            sourceTag: "UNCERTAIN_FACT",
            metadata: {
              "linkGraph.overflow.hiddenMethodCount": "8",
              "linkGraph.overflow.titlePrefix": "下游调用过多",
            },
          },
        ],
        edges: [],
      },
      selectedNodeId: "overflow:downstream",
    });

    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("下游调用过多"));
    await user.click(screen.getByRole("menuitem", { name: "继续展开此分支" }));

    expect(window.linkGraphBridge?.requestExpandOverflowNode).toHaveBeenCalledWith("overflow:downstream");
  });

  it("opens a node editor dialog after adding a node from blank space and allows immediate editing", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(screen.getByTestId("graph-canvas-shell"), { clientX: 260, clientY: 220 });
    await user.click(screen.getByRole("menuitem", { name: "新增方法节点" }));

    const dialog = screen.getByRole("dialog", { name: "节点详情" });
    expect(dialog).toBeInTheDocument();
    const titleInput = within(dialog).getByLabelText("标题");
    expect(titleInput).toHaveValue("新方法3");

    await user.clear(titleInput);
    await user.type(titleInput, "草稿节点");
    await user.click(within(dialog).getByRole("button", { name: "保存修改" }));

    expect(window.linkGraphBridge?.graphChanged).toHaveBeenCalledWith({
      nodes: expect.arrayContaining([
        expect.objectContaining({
          id: "design:3",
          title: "草稿节点",
          type: "METHOD",
          certainty: "PROVEN",
        }),
      ]),
      edges: [
        expect.objectContaining({
          id: "call:submit-order->order-draft-dto",
          fromNodeId: "method:submit-order",
          toNodeId: "class:order-draft-dto",
        }),
      ],
    });
  });

  it("allocates a fresh manual method id after deleting a previously added node", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(screen.getByTestId("graph-canvas-shell"), { clientX: 260, clientY: 220 });
    await user.click(screen.getByRole("menuitem", { name: "新增方法节点" }));

    let dialog = screen.getByRole("dialog", { name: "节点详情" });
    let titleInput = within(dialog).getByLabelText("标题");
    await user.clear(titleInput);
    await user.type(titleInput, "草稿节点");
    await user.click(within(dialog).getByRole("button", { name: "保存修改" }));

    fireEvent.contextMenu(canvasQueries().getByText("草稿节点"));
    await user.click(screen.getByRole("menuitem", { name: "删除节点" }));

    fireEvent.contextMenu(screen.getByTestId("graph-canvas-shell"), { clientX: 320, clientY: 260 });
    await user.click(screen.getByRole("menuitem", { name: "新增方法节点" }));

    dialog = screen.getByRole("dialog", { name: "节点详情" });
    titleInput = within(dialog).getByLabelText("标题");
    expect(titleInput).toHaveValue("新方法4");
  });

  it("supports adding a note node from blank space and opens it for immediate editing", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(screen.getByTestId("graph-canvas-shell"), { clientX: 260, clientY: 220 });
    await user.click(screen.getByRole("menuitem", { name: "新增说明节点" }));

    const dialog = screen.getByRole("dialog", { name: "节点详情" });
    const titleInput = within(dialog).getByLabelText("标题");
    expect(titleInput).toHaveValue("说明3");
    expect(within(dialog).getByLabelText("注释")).toHaveValue("请填写业务说明");

    await user.clear(titleInput);
    await user.type(titleInput, "人工审计说明");
    await user.click(within(dialog).getByRole("button", { name: "保存修改" }));

    expect(window.linkGraphBridge?.graphChanged).toHaveBeenCalledWith({
      nodes: expect.arrayContaining([
        expect.objectContaining({
          id: "design-note:3",
          title: "人工审计说明",
          type: "DOC_PAGE",
          sourceTag: "DRAFT_MANUAL",
        }),
      ]),
      edges: [
        expect.objectContaining({
          id: "call:submit-order->order-draft-dto",
          fromNodeId: "method:submit-order",
          toNodeId: "class:order-draft-dto",
        }),
      ],
    });
  });

  it("keeps collapsed downstream state when bootstrap only refreshes feedback", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "折叠整个下游子树" }));

    expect(screen.queryByText("OrderDraftDto")).not.toBeInTheDocument();

    act(() => {
      window.dispatchEvent(
        new CustomEvent("link-graph-bootstrap", {
          detail: {
            ...structuredClone(bootstrapState),
            operationFeedback: {
              level: "INFO",
              message: "只是刷新反馈，不应该重置画布折叠态",
            },
          },
        }),
      );
    });

    expect(screen.queryByText("OrderDraftDto")).not.toBeInTheDocument();
    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    expect(screen.getByRole("menuitem", { name: "展开整个下游子树" })).toBeInTheDocument();
  });

  it("keeps collapsed downstream state when bootstrap only changes layout revision", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = {
      ...structuredClone(bootstrapState),
      semanticRevision: 3,
      layoutRevision: 1,
      snapshotRevision: 4,
    };

    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "折叠整个下游子树" }));

    expect(screen.queryByText("OrderDraftDto")).not.toBeInTheDocument();

    act(() => {
      window.dispatchEvent(
        new CustomEvent("link-graph-bootstrap", {
          detail: {
            ...structuredClone(bootstrapState),
            visibleGraph: {
              nodes: [
                {
                  ...structuredClone(bootstrapState.visibleGraph.nodes[0]),
                  position: { x: 640, y: 320 },
                },
                {
                  ...structuredClone(bootstrapState.visibleGraph.nodes[1]),
                  position: { x: 940, y: 320 },
                },
              ],
              edges: structuredClone(bootstrapState.visibleGraph.edges),
            },
            semanticRevision: 3,
            layoutRevision: 2,
            snapshotRevision: 5,
          },
        }),
      );
    });

    expect(screen.queryByText("OrderDraftDto")).not.toBeInTheDocument();
    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    expect(screen.getByRole("menuitem", { name: "展开整个下游子树" })).toBeInTheDocument();
  });

  it("counts overflow-hidden descendants when collapsing a subtree, instead of only counting the visible summary node", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = materializeThreeViewDocuments({
      ...structuredClone(bootstrapState),
      visibleGraph: {
        nodes: [
          structuredClone(bootstrapState.visibleGraph.nodes[0]),
          {
            id: "overflow:downstream",
            type: "UNCERTAIN_LINK",
            title: "下游调用过多",
            signature: "当前为了避免卡顿，只展示了核心分支",
            inputs: [],
            outputs: [],
            doc: "这是提图阶段限流节点，可以继续展开。",
            certainty: "RULE_INFERRED",
            bindingStatus: "PARTIALLY_SYNCED",
            sourceTag: "UNCERTAIN_FACT",
            metadata: {
              "linkGraph.overflow.hiddenMethodCount": "8",
              "linkGraph.overflow.titlePrefix": "下游调用过多",
            },
          },
        ],
        edges: [
          {
            id: "edge-overflow",
            type: "CALL",
            source: "method:submit-order",
            target: "overflow:downstream",
          },
        ],
      },
      selectedNodeId: "method:submit-order",
    });

    render(<App />);

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "折叠整个下游子树" }));

    expect(screen.getByText("已折叠下游 9 个节点，右键可重新展开")).toBeInTheDocument();
  });

  it("does not reset the updated canvas graph when a later bootstrap only changes feedback", async () => {
    const user = userEvent.setup();
    render(<App />);

    act(() => {
      window.dispatchEvent(
        new CustomEvent("link-graph-bootstrap", {
          detail: materializeThreeViewDocuments({
            ...structuredClone(bootstrapState),
            visibleGraph: {
              nodes: [
                ...structuredClone(bootstrapState.visibleGraph.nodes),
                {
                  id: "doc:detached-note",
                  type: "DOC_PAGE",
                  title: "人工补充说明",
                  inputs: [],
                  outputs: [],
                  doc: "补充业务黑逻辑说明。",
                  certainty: "PROVEN",
                  bindingStatus: "DESIGN_ONLY",
                  sourceTag: "DRAFT_MANUAL",
                },
              ],
              edges: structuredClone(bootstrapState.visibleGraph.edges),
            },
          }),
        }),
      );
    });

    fireEvent.contextMenu(canvasQueries().getByText("SubmitResult submit(String, SubmitRequest)"));
    await user.click(screen.getByRole("menuitem", { name: "折叠整个下游子树" }));
    expect(screen.queryByText("OrderDraftDto")).not.toBeInTheDocument();
    expect(screen.getByText("人工补充说明")).toBeInTheDocument();

    act(() => {
      window.dispatchEvent(
        new CustomEvent("link-graph-bootstrap", {
          detail: materializeThreeViewDocuments({
            ...structuredClone(bootstrapState),
            visibleGraph: {
              nodes: [
                ...structuredClone(bootstrapState.visibleGraph.nodes),
                {
                  id: "doc:detached-note",
                  type: "DOC_PAGE",
                  title: "人工补充说明",
                  inputs: [],
                  outputs: [],
                  doc: "补充业务黑逻辑说明。",
                  certainty: "PROVEN",
                  bindingStatus: "DESIGN_ONLY",
                  sourceTag: "DRAFT_MANUAL",
                },
              ],
              edges: structuredClone(bootstrapState.visibleGraph.edges),
            },
            operationFeedback: {
              level: "INFO",
              message: "只刷新反馈，不应该回退到旧图。",
            },
          }),
        }),
      );
    });

    expect(screen.queryByText("OrderDraftDto")).not.toBeInTheDocument();
    expect(screen.getByText("人工补充说明")).toBeInTheDocument();
  });
});

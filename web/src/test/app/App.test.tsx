import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEventLib, { PointerEventsCheckLevel } from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.unmock("@xyflow/react");
vi.unmock("../../app/api");
vi.unmock("../../app/reactflow/GraphFlowSurface");
vi.unmock("../../app/reactflow/useMeasuredLayout");
vi.unmock("../../app/views/fact/FactGraphView");
vi.unmock("../../app/views/fact/factGraphNodes");
vi.unmock("../../app/views/flowchart/FlowchartView");
vi.unmock("../../app/views/flowchart/flowchartNodes");
vi.unmock("../../app/views/resource/ResourceRelationView");
vi.unmock("../../app/views/resource/resourceRelationNodes");
vi.unmock("../../app/components/graph/nodes/FactGraphNodeCard");
vi.unmock("../../app/components/graph/nodes/FlowchartNodeCard");
vi.unmock("../../app/components/graph/nodes/ResourceRelationNodeCard");

import { App, resolveAuditTargetNodeIds } from "../../app/App";
import { dispatchBootstrapForTest, resetEditorTransportForTest } from "../../app/editorTransport";
import { materializeThreeViewDocuments, type TestBootstrapState } from "../../app/testBootstrapState";
import type {
  CandidateDraftChange,
  GraphBeautificationStep,
} from "../../app/types";

const userEvent = {
  setup(options?: Parameters<typeof userEventLib.setup>[0]) {
    const user = userEventLib.setup({
      pointerEventsCheck: PointerEventsCheckLevel.Never,
      ...options,
    });
    return {
      ...user,
      click(element: Parameters<typeof fireEvent.click>[0]) {
        fireEvent.click(element);
        return Promise.resolve();
      },
    };
  },
};

function candidateChangeFixture(): CandidateDraftChange {
  return {
    changeId: "change-compensate",
    status: "PENDING_CONFIRMATION",
    title: "补充失败补偿说明",
    targetStepIds: ["step-submit-order"],
    targetNodeIds: ["method:submit-order"],
    beforeState: "当前没有失败补偿说明",
    afterState: "补充失败补偿逻辑说明",
    reason: "当前链路缺少失败补偿语义。",
    impactSummary: "影响订单提交失败后的处理理解。",
    claimType: "CODE_FACT",
    evidence: [
      {
        id: "finding-compensate",
        claim: "当前源码里直接能看到失败补偿缺失。",
        evidenceLevel: "DIRECT_SOURCE",
        references: [{ nodeId: "method:submit-order" }],
      },
    ],
    editScopes: [
      {
        scopeId: "scope-change-compensate",
        targetNodeId: "method:submit-order",
        filePath: "/project/src/main/java/com/example/OrderController.java",
        language: "JAVA",
        symbolKind: "METHOD",
        symbolSignature: "com.example.OrderController.submit():void",
        startLine: 8,
        endLine: 16,
        allowedChangeKinds: ["REPLACE_METHOD_BODY"],
        supportingFindingIds: ["finding-compensate"],
      },
    ],
  };
}

function explanationStepFixture(): GraphBeautificationStep {
  return {
    stepId: "step-submit-order",
    title: "Step 1 提交订单请求",
    granularity: "BUSINESS",
    kind: "BUSINESS_ACTION",
    description: "当前方法负责接收入参并把请求交给下游服务。",
    primaryNodeId: "method:submit-order",
    codeSnippet: "orderService.submit(request);",
    evidence: [
      {
        id: "step-submit-order-source",
        claim: "这里直接命中了提交订单入口方法。",
        evidenceLevel: "DIRECT_SOURCE",
        references: [
          {
            nodeId: "method:submit-order",
            filePath: "/project/src/main/java/com/example/OrderController.java",
            startLine: 8,
            endLine: 16,
          },
        ],
      },
    ],
    followUpQuestions: ["订单校验失败时怎么处理？"],
    downstreamTargets: ["method:order-service"],
  };
}

function bootstrapStateFixture(): TestBootstrapState {
  const candidate = candidateChangeFixture();
  const step = explanationStepFixture();
  return materializeThreeViewDocuments({
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
      ],
      edges: [],
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
    draftPatchPreview: null,
    draftWorkbenchState: {
      draftChanges: [
        {
          entryId: "draft-change-existing",
          kind: "CHANGE",
          title: "已有草稿变更",
          sourceChangeId: "change-existing",
          targetStepIds: ["step-submit-order"],
          targetNodeIds: ["method:submit-order"],
          beforeState: "旧逻辑",
          afterState: "新逻辑",
          reason: "已有确认项",
          impactSummary: "影响订单提交流程",
          claimType: "CODE_FACT",
          evidence: [],
        },
      ],
      draftNotes: [],
    },
    auditResult: {
      source: "LOCAL_RULE",
      question: "这个方法是否遗漏补偿链路？",
      answer: "建议补一条失败补偿链路。",
      promptPreview: "audit prompt preview",
      patch: null,
      findings: [],
      candidateChanges: [candidate],
      newCandidateChanges: [candidate],
      investigationThreads: [],
      auditSession: {
        sessionId: "audit-method-submit",
        scopeKey: "method:submit-order",
        focusTargetId: candidate.changeId,
        candidateChanges: [candidate],
        messages: [
          {
            messageId: "audit-user-1",
            role: "USER",
            content: "这个方法是否遗漏补偿链路？",
          },
          {
            messageId: "audit-assistant-1",
            role: "ASSISTANT",
            content: "建议补一条失败补偿链路。",
          },
        ],
      },
      warnings: ["当前结果来自本地规则分析。"],
    },
    diffReviewResult: null,
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
    mermaidIssues: [],
    generationPlan: null,
    generatedCodeDrafts: [],
    generatedCodeDraftWarnings: [],
    generatedCodeDraftWriteReport: null,
    lastDraftPatchApplyResult: null,
    graphBeautificationResult: {
      source: "LOCAL_RULE",
      granularity: "BUSINESS",
      steps: [step],
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
      message: "已加载当前编辑器上下文链路：OrderController.submit",
    },
    analysisDisplayMode: "FACT_GRAPH",
    auditRequestState: {
      phase: "SUCCEEDED",
      errorMessage: null,
    },
    diffReviewRequestState: {
      phase: "IDLE",
      errorMessage: null,
    },
    generationPlanRequestState: {
      phase: "IDLE",
      errorMessage: null,
    },
    graphBeautificationRequestState: {
      phase: "SUCCEEDED",
      errorMessage: null,
    },
    codeDraftRequestState: {
      phase: "IDLE",
      errorMessage: null,
    },
    canUndoDraftPatchApply: false,
    lastAppliedDraftPatchSummary: null,
  });
}

async function waitForGraphNode(container: HTMLElement, nodeId: string) {
  await waitFor(() => {
    expect(findGraphNodeElement(container, nodeId)).not.toBeNull();
  });
}

function findGraphNodeElement(container: HTMLElement, nodeId: string): HTMLElement | null {
  return Array.from(container.querySelectorAll<HTMLElement>("[data-node-id]"))
    .find((element) => element.getAttribute("data-node-id") === nodeId)
    ?? null;
}

function requireGraphNodeElement(container: HTMLElement, nodeId: string): HTMLElement {
  const element = findGraphNodeElement(container, nodeId);
  expect(element).not.toBeNull();
  return element!;
}

function findGraphNodeWrapper(container: HTMLElement, nodeId: string): HTMLElement | null {
  return findGraphNodeElement(container, nodeId)?.closest(".react-flow__node") as HTMLElement | null;
}

async function flushAsyncUiTurn() {
  await Promise.resolve();
  await act(async () => {
    await Promise.resolve();
  });
  await Promise.resolve();
}

async function applyBootstrapEnvelope(envelope: Parameters<typeof dispatchBootstrapForTest>[0]) {
  // The transport updates React via startTransition; forcing act() around the dispatch
  // can deadlock tests that intentionally replay older snapshots over newer local UI state.
  await withSuppressedActWarnings(async () => {
    dispatchBootstrapForTest(envelope);
    await flushAsyncUiTurn();
  });
}

function runWithSuppressedActWarnings<T>(callback: () => T): T {
  const restore = suppressActWarnings();
  try {
    return callback();
  } finally {
    restore();
  }
}

function suppressActWarnings() {
  const originalError = console.error;
  const errorSpy = vi.spyOn(console, "error").mockImplementation((message?: unknown, ...args: unknown[]) => {
    const text = String(message ?? "");
    if (text.includes("not wrapped in act")) {
      return;
    }
    originalError(message, ...args);
  });
  return () => {
    errorSpy.mockRestore();
  };
}

async function withSuppressedActWarnings<T>(callback: () => Promise<T> | T): Promise<T> {
  const restore = suppressActWarnings();
  try {
    return await callback();
  } finally {
    restore();
  }
}

function dispatchMouseEvent(element: Element, type: string, init?: MouseEventInit) {
  element.dispatchEvent(new MouseEvent(type, {
    bubbles: true,
    cancelable: true,
    relatedTarget: document.body,
    ...init,
  }));
}

async function dispatchHoverEvent(element: Element, type: "mouseenter" | "mouseleave") {
  await withSuppressedActWarnings(async () => {
    dispatchMouseEvent(element, type === "mouseenter" ? "mouseover" : "mouseout");
    await Promise.resolve();
  });
}

async function openCanvasContextMenu(element: Element, position: { clientX: number; clientY: number }) {
  await withSuppressedActWarnings(async () => {
    dispatchMouseEvent(element, "contextmenu", position);
    await waitFor(() => {
      expect(screen.getByRole("menu")).toBeInTheDocument();
    });
  });
}

async function dispatchClickEvent(element: Element) {
  await withSuppressedActWarnings(async () => {
    dispatchMouseEvent(element, "click");
    await Promise.resolve();
  });
}

async function setTextboxValue(element: HTMLElement, value: string) {
  await withSuppressedActWarnings(async () => {
    const valueSetter = Object.getOwnPropertyDescriptor(
      element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype,
      "value",
    )?.set;
    valueSetter?.call(element, value);
    element.dispatchEvent(new Event("input", {
      bubbles: true,
      cancelable: true,
    }));
    element.dispatchEvent(new Event("change", {
      bubbles: true,
      cancelable: true,
    }));
    await Promise.resolve();
  });
}

describe.sequential("App", () => {
  beforeEach(() => {
    vi.useRealTimers();
    resetEditorTransportForTest();
    window.linkGraphBootstrap = structuredClone(bootstrapStateFixture());
    const bridge = {
      exportMermaid: vi.fn(),
      importMermaid: vi.fn(),
      showDiffMode: vi.fn(),
      requestSyncPreview: vi.fn(),
      requestAudit: vi.fn(),
      retryLastAuditRequest: vi.fn(),
      confirmAuditCandidateChange: vi.fn(),
      unconfirmAuditCandidateChange: vi.fn(),
      resolveInvestigationThread: vi.fn(),
      requestDiffReview: vi.fn(),
      requestGraphBeautification: vi.fn(),
      applyDraftPatchPreview: vi.fn(),
      clearDraftPatchPreview: vi.fn(),
      restoreDraftPatchPreview: vi.fn(),
      undoLastDraftPatchApply: vi.fn(),
      requestGenerationPlan: vi.fn(),
      requestCodeDrafts: vi.fn(),
      requestCurrentEditorContextGraph: vi.fn(),
      requestAnalysisDisplayMode: vi.fn(),
      requestOpenSettings: vi.fn(),
      applyCodeDrafts: vi.fn(),
      applySingleCodeDraft: vi.fn(),
      requestDraftNavigation: vi.fn(),
      requestArtifact: vi.fn(),
      graphChanged: vi.fn(),
      layoutChanged: vi.fn(),
      nodeSelected: vi.fn(),
      requestSourceNavigation: vi.fn(),
      requestExpandOverflowNode: vi.fn(),
    };
    (bridge as typeof bridge & { updateWorkbenchSectionPreference: ReturnType<typeof vi.fn> }).updateWorkbenchSectionPreference = vi.fn();
    window.linkGraphBridge = bridge;
  });

  afterEach(() => {
    cleanup();
    resetEditorTransportForTest();
    vi.useRealTimers();
  });

  it("renders explanation, qa, draft, and code tabs from the draft-first workbench state", () => {
    render(<App />);

    expect(screen.getByRole("tab", { name: "讲解" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "问答" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "草稿" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "代码" })).toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "计划" })).not.toBeInTheDocument();
    expect(screen.getByRole("tabpanel", { name: "讲解" })).toBeInTheDocument();
    expect(screen.queryByText("结果面板")).not.toBeInTheDocument();
  });

  it("defaults to the flowchart stage when bootstrap state does not provide a display mode", () => {
    const state = structuredClone(bootstrapStateFixture());
    delete (state as Partial<TestBootstrapState>).analysisDisplayMode;
    window.linkGraphBootstrap = state;

    render(<App />);

    expect(screen.getByRole("button", { name: "流程图" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("flowchart-view")).toBeInTheDocument();
    expect(screen.getByLabelText("流程图摘要")).toBeInTheDocument();
  });

  it("switches to the code tab when requesting implementation suggestions from the toolbar without synthesizing a local running state", async () => {
    const user = userEvent.setup();

    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "生成实现建议" }));

    expect(screen.getByRole("tab", { name: "代码" })).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByText("正在生成实现建议，请稍候。")).not.toBeInTheDocument();
    expect(screen.getAllByText("实现建议会基于当前草稿快照生成。").length).toBeGreaterThan(0);
    expect(window.linkGraphBridge?.requestGenerationPlan).toHaveBeenCalledTimes(1);
  });

  it("still forwards plan generation requests to the IDE bridge when no confirmed draft changes exist", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      draftWorkbenchState: {
        draftChanges: [],
        draftNotes: [],
      },
    });

    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "生成实现建议" }));

    expect(screen.getByRole("tab", { name: "代码" })).toHaveAttribute("aria-selected", "true");
    expect(window.linkGraphBridge?.requestGenerationPlan).toHaveBeenCalledTimes(1);
  });

  it("still forwards draft generation requests to the IDE bridge when no confirmed draft changes exist", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      draftWorkbenchState: {
        draftChanges: [],
        draftNotes: [],
      },
    });

    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "生成代码 diff" }));

    expect(screen.getByRole("tab", { name: "代码" })).toHaveAttribute("aria-selected", "true");
    expect(window.linkGraphBridge?.requestCodeDrafts).toHaveBeenCalledTimes(1);
  });

  it("does not locally clear the current code diff or synthesize a running request before the backend snapshot arrives", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      generatedCodeDrafts: [
        {
          id: "draft-file-download",
          sourceNodeId: "method:file-download",
          title: "CommonController.java",
          targetPath: "src/main/java/com/example/CommonController.java",
          content: null,
          editOperations: [
            {
              operationId: "op-replace-delete-if-block",
              filePath: "src/main/java/com/example/CommonController.java",
              scopeId: "scope-change-update-delete-condition",
              kind: "REPLACE_METHOD_BLOCK",
              payload: "if (delete == true) { FileUtils.deleteFile(filePath); }",
              warnings: [],
            },
          ],
          editScopes: [],
          warnings: [],
        },
      ],
      generatedCodeDraftSource: "LOCAL_RULE",
      generatedCodeDraftWarnings: [],
      generatedCodeDraftWriteReport: null,
      codeDraftRequestState: {
        phase: "IDLE",
        errorMessage: null,
      },
      codeEligibilityDecision: {
        target: "CODE",
        stageLabel: "代码草稿",
        allowed: true,
        message: "当前可以继续生成代码 diff。",
        detailMessage: "风险线程已完成人工决策。",
        blockingThreadIds: [],
        unresolvedThreadIds: [],
      },
    });

    render(<App />);

    await user.click(screen.getByRole("tab", { name: "代码" }));
    expect(screen.getAllByText("CommonController.java")).toHaveLength(2);

    await user.click(screen.getByRole("button", { name: "重新生成 diff" }));

    expect(window.linkGraphBridge?.requestCodeDrafts).toHaveBeenCalledTimes(1);
    expect(screen.getAllByText("CommonController.java")).toHaveLength(2);
    expect(screen.queryByText("正在生成代码 diff，请稍候。")).not.toBeInTheDocument();
    expect(screen.queryByText(/代码 diff 生成：已提交代码草稿请求/)).not.toBeInTheDocument();
  });

  it("renders migrated implementation analysis above code diff results from bootstrap state", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      generationPlan: {
        source: "LOCAL_RULE",
        summary: "修改 CommonController.fileDownload 并保留现有正常路径逻辑。",
        warnings: ["当前结果来自本地规则分析。"],
        promptPreview: null,
        items: [
          {
            id: "plan-file-download",
            title: "修改 fileDownload 的路径判定",
            description: "对 /usr 与 C:/ 路径分别增加重写与报错分支。",
            risk: "MEDIUM",
            targetPath: "src/main/java/com/example/CommonController.java",
          },
        ],
      },
      draftVersion: 2,
      generationPlanDraftVersion: 2,
      generatedCodeDrafts: [
        {
          id: "draft-file-download",
          sourceNodeId: "method:file-download",
          title: "CommonController.java",
          targetPath: "src/main/java/com/example/CommonController.java",
          content: "public class CommonController {}",
          warnings: [],
        },
      ],
      generatedCodeDraftVersion: 2,
      generatedCodeDraftSource: "REMOTE",
      generatedCodeDraftWarnings: ["请复核异常类型。"],
      generatedCodeDraftPromptPreview: null,
    });

    render(<App />);

    await user.click(screen.getByRole("tab", { name: "代码" }));
    const codePanel = screen.getByRole("tabpanel", { name: "代码" });
    const codeText = codePanel.textContent ?? "";
    expect(codeText.indexOf("实现建议")).toBeGreaterThanOrEqual(0);
    expect(codeText.indexOf("实现建议")).toBeLessThan(codeText.indexOf("代码 diff 工作台"));
    expect(screen.getAllByText("基于草稿 v2 生成").length).toBeGreaterThan(0);
    expect(screen.getByText("修改 CommonController.fileDownload 并保留现有正常路径逻辑。")).toBeInTheDocument();
    expect(screen.getByText("任务：修改 fileDownload 的路径判定")).toBeInTheDocument();

    expect(screen.getByRole("heading", { name: "代码 diff 工作台" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "选择代码 diff 文件：CommonController.java" })).toBeInTheDocument();
    expect(screen.getByText("请复核异常类型。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "写入当前文件" })).toBeInTheDocument();
  });

  it("prefers real async request status in the toolbar over stale operation feedback", () => {
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      operationFeedback: {
        level: "INFO",
        message: "已发起远程 LLM 问答请求，当前采用流式输出。",
      },
      auditRequestState: {
        phase: "SUCCEEDED",
        scene: "问答",
        statusMessage: "问答完成，已生成待确认变更。",
        detailMessage: "远程 LLM 已完成流式输出，并已落地最终结构化结果。",
        finishedAtEpochMillis: 300,
      },
      lastMessageType: "auditResult",
    });

    render(<App />);

    expect(screen.getByText("问答：问答完成，已生成待确认变更。")).toBeInTheDocument();
    expect(screen.queryByText("已发起远程 LLM 问答请求，当前采用流式输出。")).not.toBeInTheDocument();
  });

  it("keeps code diff async failures inside the code workbench instead of reopening a global failure dialog", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      codeDraftRequestState: {
        phase: "FAILED",
        scene: "代码 diff 生成",
        statusMessage: "代码 diff 失败",
        errorMessage: "未生成任何可用代码 diff：远程 LLM 代码生成失败。",
        detailMessage: "返回内容未通过结构化校验，自动修复重试仍失败。",
        startedAtEpochMillis: 120,
        finishedAtEpochMillis: 180,
      },
      generatedCodeDrafts: [],
      generatedCodeDraftWarnings: [],
      generatedCodeDraftWriteReport: null,
    });

    render(<App />);

    await user.click(screen.getByRole("tab", { name: "代码" }));

    const codeTabPanel = screen.getByRole("tabpanel", { name: "代码" });
    expect(within(codeTabPanel).getByText("当前请求失败，请查看上方状态并按需重试。")).toBeInTheDocument();
    expect(within(codeTabPanel).getByText("返回内容未通过结构化校验，自动修复重试仍失败。")).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "请求状态通知" })).not.toBeInTheDocument();
    });
  });

  it("shows a completed explanation success hint in the toolbar even when the backend only leaves a generic operation feedback marker", () => {
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      lastMessageType: "graphBeautificationResult",
      operationFeedback: {
        level: "SUCCESS",
        message: "已加载当前编辑器上下文链路：OrderController.submit",
      },
      graphBeautificationRequestState: {
        phase: "SUCCEEDED",
        scene: "链路讲解",
        finishedAtEpochMillis: 420,
        errorMessage: null,
      },
    });

    render(<App />);

    expect(screen.getByText("链路讲解：链路讲解完成，已更新步骤列表")).toBeInTheDocument();
  });

  it("hydrates project-scoped workbench preferences from bootstrap and syncs toggles back to the bridge", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      workbenchSectionPreferences: {
        "audit.request-status": true,
      },
    });

    render(<App />);

    await user.click(screen.getByRole("tab", { name: "问答" }));

    expect(screen.getByRole("button", { name: "收起当前页面" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "待确认变更" })).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "待确认变更" }));

    expect(
      (
        window.linkGraphBridge as typeof window.linkGraphBridge & {
          updateWorkbenchSectionPreference: ReturnType<typeof vi.fn>;
        }
      )?.updateWorkbenchSectionPreference,
    ).toHaveBeenCalledWith("audit.candidate-changes", true);
    expect(
      (
        window.linkGraphBridge as typeof window.linkGraphBridge & {
          updateWorkbenchSectionPreference: ReturnType<typeof vi.fn>;
        }
      )?.updateWorkbenchSectionPreference,
    ).toHaveBeenCalledWith("audit.request-status", false);
  });

  it("applies newer workbench section preferences from bootstrap envelopes after local interactions", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      workbenchSectionPreferences: {
        "audit.request-status": true,
      },
    });

    render(<App />);

    await user.click(screen.getByRole("tab", { name: "问答" }));
    expect(screen.getByRole("tab", { name: "请求" })).toHaveAttribute("aria-selected", "true");

    await applyBootstrapEnvelope({
      sessionId: "session-1",
      revision: 2,
      state: {
        ...structuredClone(bootstrapStateFixture()),
        snapshotRevision: 2,
        workbenchSectionPreferences: {
          "audit.thread": true,
        },
      },
    });

    await waitFor(() => {
      expect(screen.getByRole("tab", { name: "问答会话" })).toHaveAttribute("aria-selected", "true");
    });
  });

  it("keeps blocked code generation focused on the code tab validation analysis", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      draftValidationState: {
        status: "REVIEW_REQUIRED",
        message: "当前草稿仍有待验证风险。",
        detailMessage: "请先确认这些风险是继续取证、接受、排除，还是回退对应草稿变更。",
        unresolvedThreadIds: ["thread-risk-1"],
        unresolvedThreads: [
          {
            threadId: "thread-risk-1",
            status: "OPEN",
            title: "删除分支仍缺少异常处理证据",
            targetStepIds: ["step-submit-order"],
            targetNodeIds: ["method:submit-order"],
            summary: "当前还不能直接进入代码阶段。",
            evidenceGap: "删除失败后的兜底链路证据仍未补齐。",
            recommendedQuestion: "删除失败时的兜底链路是否已经补齐？",
            evidence: [],
            resolution: null,
          },
        ],
      },
      codeEligibilityDecision: {
        target: "CODE",
        stageLabel: "代码草稿",
        allowed: false,
        message: "生成代码 diff 前请先处理仍会阻塞代码阶段的风险线程。",
        detailMessage: "当前仍有未处理或证据未穷尽的风险线程，代码阶段不能直接继续生成。",
        blockingThreadIds: ["thread-risk-1"],
        unresolvedThreadIds: ["thread-risk-1"],
      },
    });

    render(<App />);

    await user.click(screen.getByRole("tab", { name: "代码" }));
    await user.click(screen.getByRole("button", { name: "处理阻塞风险" }));

    expect(screen.getByRole("tab", { name: "代码" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("当前草稿仍有待验证风险。")).toBeInTheDocument();
    expect(window.linkGraphBridge?.updateWorkbenchSectionPreference).not.toHaveBeenCalled();
  });

  it("turns an investigation thread into a concrete continue-investigation path", async () => {
    const user = userEvent.setup();
    const candidate = candidateChangeFixture();
    const thread = {
      threadId: "thread-compensate",
      status: "OPEN" as const,
      title: "失败补偿可能缺失",
      targetStepIds: ["step-submit-order"],
      targetNodeIds: ["method:submit-order"],
      summary: "当前只看到提交订单入口，没有看到失败补偿实现。",
      evidenceGap: "还没有看到失败分支或补偿调用。",
      recommendedQuestion: "请继续取证：定位订单提交失败时的补偿分支，确认是否真的缺失失败补偿。",
      claimType: "RISK_HINT" as const,
      evidence: [
        {
          id: "finding-callsite-only",
          claim: "当前只看到订单提交入口方法。",
          evidenceLevel: "CALLSITE_ONLY" as const,
          references: [{ nodeId: "method:submit-order" }],
        },
      ],
    };
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      auditResult: {
        ...bootstrapStateFixture().auditResult!,
        candidateChanges: [candidate],
        newCandidateChanges: [candidate],
        investigationThreads: [
          {
            threadId: "thread-compensate",
            status: "OPEN",
            title: thread.title,
            targetStepIds: thread.targetStepIds,
            targetNodeIds: thread.targetNodeIds,
            summary: thread.summary,
            evidenceGap: thread.evidenceGap,
            recommendedQuestion: thread.recommendedQuestion,
            claimType: thread.claimType,
            evidence: thread.evidence,
            latestTurnOutcomeId: null,
          },
        ],
        auditSession: {
          ...bootstrapStateFixture().auditResult!.auditSession!,
          investigationThreads: [
            {
              threadId: "thread-compensate",
              status: "OPEN",
              title: thread.title,
              targetStepIds: thread.targetStepIds,
              targetNodeIds: thread.targetNodeIds,
              summary: thread.summary,
              evidenceGap: thread.evidenceGap,
              recommendedQuestion: thread.recommendedQuestion,
              claimType: thread.claimType,
              evidence: thread.evidence,
              latestTurnOutcomeId: null,
            },
          ],
        },
      },
    });

    render(<App />);

    await user.click(screen.getByRole("tab", { name: "问答" }));
    await user.click(screen.getByRole("tab", { name: "风险线程" }));
    await applyBootstrapEnvelope({
      sessionId: "session-1",
      revision: 2,
      state: {
        ...structuredClone(window.linkGraphBootstrap!),
        snapshotRevision: 2,
        workbenchSectionPreferences: {
          "audit.investigation-threads": true,
        },
      },
    });
    await waitFor(() => {
      expect(screen.getByRole("tab", { name: "风险线程" })).toHaveAttribute("aria-selected", "true");
    });
    await dispatchClickEvent(await screen.findByRole("button", { name: "继续取证" }));
    await applyBootstrapEnvelope({
      sessionId: "session-1",
      revision: 3,
      state: {
        ...structuredClone(window.linkGraphBootstrap!),
        snapshotRevision: 3,
        workbenchSectionPreferences: {
          "audit.composer": true,
        },
        auditRequestState: {
          phase: "RUNNING",
          requestId: 1,
          scene: "问答",
          statusMessage: "已提交继续取证请求",
        },
        auditResult: null,
      },
    });

    expect(window.linkGraphBridge?.requestAudit).toHaveBeenCalledWith(
      thread.recommendedQuestion,
      ["method:submit-order"],
      "thread-compensate",
      "INVESTIGATE",
    );
    expect(
      (
        window.linkGraphBridge as typeof window.linkGraphBridge & {
          updateWorkbenchSectionPreference: ReturnType<typeof vi.fn>;
        }
      )?.updateWorkbenchSectionPreference,
    ).toHaveBeenCalledWith("audit.composer", true);
    expect(screen.getByRole("tab", { name: "请求" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText(thread.recommendedQuestion)).toBeInTheDocument();
  });

  it("submits continue-investigation from the selected risk thread instead of losing the original thread context", async () => {
    const user = userEvent.setup();
    const thread = {
      threadId: "thread-compensate",
      status: "OPEN" as const,
      title: "失败补偿可能缺失",
      targetStepIds: ["step-submit-order"],
      targetNodeIds: ["method:submit-order"],
      summary: "当前只看到提交订单入口，没有看到失败补偿实现。",
      evidenceGap: "还没有看到失败分支或补偿调用。",
      recommendedQuestion: "请继续取证：定位订单提交失败时的补偿分支，确认是否真的缺失失败补偿。",
      claimType: "RISK_HINT" as const,
      evidence: [
        {
          id: "finding-callsite-only",
          claim: "当前只看到订单提交入口方法。",
          evidenceLevel: "CALLSITE_ONLY" as const,
          references: [{ nodeId: "method:submit-order" }],
        },
      ],
    };
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      auditResult: {
        ...bootstrapStateFixture().auditResult!,
        investigationThreads: [
          {
            threadId: "thread-compensate",
            status: "OPEN",
            title: thread.title,
            targetStepIds: thread.targetStepIds,
            targetNodeIds: thread.targetNodeIds,
            summary: thread.summary,
            evidenceGap: thread.evidenceGap,
            recommendedQuestion: thread.recommendedQuestion,
            claimType: thread.claimType,
            evidence: thread.evidence,
            latestTurnOutcomeId: null,
          },
        ],
        auditSession: {
          ...bootstrapStateFixture().auditResult!.auditSession!,
          investigationThreads: [
            {
              threadId: "thread-compensate",
              status: "OPEN",
              title: thread.title,
              targetStepIds: thread.targetStepIds,
              targetNodeIds: thread.targetNodeIds,
              summary: thread.summary,
              evidenceGap: thread.evidenceGap,
              recommendedQuestion: thread.recommendedQuestion,
              claimType: thread.claimType,
              evidence: thread.evidence,
              latestTurnOutcomeId: null,
            },
          ],
        },
      },
    });

    render(<App />);

    await user.click(screen.getByRole("tab", { name: "问答" }));
    await user.click(screen.getByRole("tab", { name: "风险线程" }));
    await applyBootstrapEnvelope({
      sessionId: "session-1",
      revision: 2,
      state: {
        ...structuredClone(window.linkGraphBootstrap!),
        snapshotRevision: 2,
        workbenchSectionPreferences: {
          "audit.investigation-threads": true,
        },
      },
    });
    await waitFor(() => {
      expect(screen.getByRole("tab", { name: "风险线程" })).toHaveAttribute("aria-selected", "true");
    });
    await dispatchClickEvent(await screen.findByRole("button", { name: "继续取证" }));

    expect(window.linkGraphBridge?.requestAudit).toHaveBeenCalledWith(
      thread.recommendedQuestion,
      ["method:submit-order"],
      "thread-compensate",
      "INVESTIGATE",
    );
    expect(window.linkGraphBridge?.requestAudit).toHaveBeenCalledTimes(1);
  });

  it("replays a DOM bootstrap event that fires before the app renders", async () => {
    const state = structuredClone(bootstrapStateFixture());
    window.linkGraphBootstrap = undefined;
    window.dispatchEvent(
      new CustomEvent("link-graph-bootstrap", {
        detail: {
          sessionId: "session-1",
          revision: 7,
          state: {
            ...state,
            snapshotRevision: 7,
          },
        },
      }),
    );

    render(<App />);

    expect(await screen.findByText("链路图画布")).toBeInTheDocument();
    expect((await screen.findAllByText("OrderController.submit")).length).toBeGreaterThan(0);
  });

  it("replays buffered bootstrap state that arrives before the app subscribes", async () => {
    const state = structuredClone(bootstrapStateFixture());
    window.linkGraphBootstrap = undefined;
    dispatchBootstrapForTest({
      sessionId: "session-1",
      revision: 1,
      state: {
        ...state,
        snapshotRevision: 1,
      },
    });

    render(<App />);

    expect(await screen.findByText("链路图画布")).toBeInTheDocument();
    expect((await screen.findAllByText("OrderController.submit")).length).toBeGreaterThan(0);
  });

  it("uses visibleGraph as the canvas graph during initial bootstrap", async () => {
    const state = bootstrapStateFixture();
    window.linkGraphBootstrap = materializeThreeViewDocuments({
      ...structuredClone(state),
      visibleGraph: {
        nodes: [],
        edges: [],
      },
      workingGraph: structuredClone(state.workingGraph),
    });

    render(<App />);

    expect(await screen.findByText("链路图画布")).toBeInTheDocument();
    expect(screen.getByText("画布里还没有节点")).toBeInTheDocument();
    expect(screen.queryByText("OrderController.submit")).not.toBeInTheDocument();
  });

  it("prefers grouped selection for audit scope and falls back to whole graph when there is no group", () => {
    expect(resolveAuditTargetNodeIds(undefined, ["node-a", "node-b"])).toEqual(["node-a", "node-b"]);
    expect(resolveAuditTargetNodeIds(undefined, ["node-a"])).toEqual([]);
    expect(resolveAuditTargetNodeIds("node-z", ["node-a", "node-b"])).toEqual(["node-z"]);
  });

  it("renders a compact Chinese workspace and keeps the graph as primary", () => {
    render(<App />);

    expect(screen.getByText("链路图画布")).toBeInTheDocument();
    expect(screen.getByText("在代码中右键方法，可直接查看完整链路或追加为节点。")).toBeInTheDocument();
    expect(screen.getByText("已加载当前编辑器上下文链路：OrderController.submit")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "更多操作" })).toBeInTheDocument();
    expect(screen.getByRole("complementary", { name: "工作台" })).toBeInTheDocument();
  });

  it("keeps the explanation tab active and asks for a focused explanation when following up on the current step", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "围绕这一步继续讲解" }));

    expect(screen.getByRole("tab", { name: "讲解" })).toHaveAttribute("aria-selected", "true");
    expect(window.linkGraphBridge?.requestGraphBeautification).toHaveBeenCalledWith(
      "",
      undefined,
      undefined,
      "BUSINESS",
      "step-submit-order",
      "Step 1 提交订单请求",
      "订单校验失败时怎么处理？",
    );
  });

  it("restores the previous explanation snapshot after returning from a follow-up explanation", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "围绕这一步继续讲解" }));

    const nextState = structuredClone(bootstrapStateFixture());
    nextState.snapshotRevision = 2;
    nextState.graphBeautificationResult = {
      ...nextState.graphBeautificationResult!,
      steps: [
        {
          ...explanationStepFixture(),
          stepId: "step-submit-order-follow-up",
          title: "Step 1.1 继续分析订单校验失败",
          description: "这里继续展开订单校验失败后的分支处理。",
          followUpQuestions: ["失败后是否有补偿逻辑？"],
        },
      ],
    };
    nextState.graphBeautificationRequestState = {
      phase: "SUCCEEDED",
      errorMessage: null,
    };
    nextState.lastMessageType = "graphBeautificationResult";

    await applyBootstrapEnvelope({
      sessionId: "session-1",
      revision: 2,
      state: nextState,
    });

    expect((await screen.findAllByText("Step 1.1 继续分析订单校验失败")).length).toBeGreaterThan(0);
    const returnButton = await screen.findByRole("button", { name: "返回上一讲解：当前链路讲解" });

    await dispatchClickEvent(returnButton);

    await waitFor(() => {
      expect(screen.getAllByText("Step 1 提交订单请求").length).toBeGreaterThan(0);
      expect(screen.getByText("当前方法负责接收入参并把请求交给下游服务。")).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "返回上一讲解：当前链路讲解" })).not.toBeInTheDocument();
    });
  });

  it("opens the edit dialog from the explanation reference card without jumping source immediately", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: /OrderController\.java:8-16/ }));

    expect(screen.getByRole("dialog", { name: "编辑节点" })).toBeInTheDocument();
    expect(window.linkGraphBridge?.requestSourceNavigation).not.toHaveBeenCalled();
  });

  it("synchronizes step selection with the graph summary focus", async () => {
    const user = userEvent.setup();
    const state = bootstrapStateFixture();
    state.graphBeautificationResult = {
      ...state.graphBeautificationResult!,
      steps: [
        explanationStepFixture(),
        {
          stepId: "step-validate-order",
          title: "Step 2 校验订单参数",
          granularity: "BUSINESS",
          kind: "BUSINESS_ACTION",
          description: "这里校验订单请求参数。",
          primaryNodeId: "class:order-draft-dto",
          codeSnippet: "validator.validate(request);",
          evidence: [
            {
              id: "step-validate-order-source",
              claim: "这里命中了参数校验逻辑。",
              evidenceLevel: "DIRECT_SOURCE",
              references: [
                {
                  nodeId: "class:order-draft-dto",
                  filePath: "/project/src/main/java/com/example/OrderDraftDto.java",
                  startLine: 4,
                  endLine: 9,
                },
              ],
            },
          ],
          followUpQuestions: ["校验失败会怎么处理？"],
          downstreamTargets: [],
        },
      ],
    };
    window.linkGraphBootstrap = state;

    const { container } = render(<App />);

    await user.click(screen.getByRole("button", { name: /Step 2 校验订单参数/ }));

    expect(screen.queryByRole("dialog", { name: "编辑节点" })).not.toBeInTheDocument();
    await waitFor(() => {
      expect(findGraphNodeElement(container, "class:order-draft-dto")).toHaveClass("is-selected");
    });
  });

  it("opens step actions on right click so locating a node does not force-open the edit dialog", async () => {
    const user = userEvent.setup();
    const state = bootstrapStateFixture();
    state.graphBeautificationResult = {
      ...state.graphBeautificationResult!,
      steps: [
        explanationStepFixture(),
        {
          stepId: "step-validate-order",
          title: "Step 2 校验订单参数",
          granularity: "BUSINESS",
          kind: "BUSINESS_ACTION",
          description: "这里校验订单请求参数。",
          primaryNodeId: "class:order-draft-dto",
          codeSnippet: "validator.validate(request);",
          evidence: [
            {
              id: "step-validate-order-source",
              claim: "这里命中了参数校验逻辑。",
              evidenceLevel: "DIRECT_SOURCE",
              references: [
                {
                  nodeId: "class:order-draft-dto",
                  filePath: "/project/src/main/java/com/example/OrderDraftDto.java",
                  startLine: 4,
                  endLine: 9,
                },
              ],
            },
          ],
          followUpQuestions: ["校验失败会怎么处理？"],
          downstreamTargets: [],
        },
      ],
    };
    window.linkGraphBootstrap = state;

    const { container } = render(<App />);

    fireEvent.contextMenu(screen.getByRole("button", { name: /Step 2 校验订单参数/ }), {
      clientX: 320,
      clientY: 240,
    });

    const menu = screen.getByRole("menu");
    expect(within(menu).getByRole("menuitem", { name: "定位到图中节点" })).toBeInTheDocument();
    expect(within(menu).getByRole("menuitem", { name: "编辑节点" })).toBeInTheDocument();

    await user.click(within(menu).getByRole("menuitem", { name: "定位到图中节点" }));

    expect(screen.queryByRole("dialog", { name: "编辑节点" })).not.toBeInTheDocument();
    await waitFor(() => {
      expect(findGraphNodeElement(container, "class:order-draft-dto")).toHaveClass("is-selected");
    });
  });

  it("opens the edit dialog only from the step right-click action", async () => {
    const user = userEvent.setup();
    render(<App />);

    fireEvent.contextMenu(screen.getByRole("button", { name: /Step 1 提交订单请求/ }), {
      clientX: 280,
      clientY: 220,
    });

    await user.click(screen.getByRole("menuitem", { name: "编辑节点" }));

    expect(screen.getByRole("dialog", { name: "编辑节点" })).toBeInTheDocument();
  });

  it("temporarily previews the hovered explanation step on the graph and restores the selected focus after hover ends", async () => {
    const state = bootstrapStateFixture();
    state.graphBeautificationResult = {
      ...state.graphBeautificationResult!,
      steps: [
        explanationStepFixture(),
        {
          stepId: "step-validate-order",
          title: "Step 2 校验订单参数",
          granularity: "BUSINESS",
          kind: "BUSINESS_ACTION",
          description: "这里校验订单请求参数。",
          primaryNodeId: "class:order-draft-dto",
          codeSnippet: "validator.validate(request);",
          evidence: [],
          followUpQuestions: [],
          downstreamTargets: [],
        },
      ],
    };
    window.linkGraphBootstrap = state;

    const { container } = render(<App />);
    const stepButton = screen.getByRole("button", { name: /Step 2 校验订单参数/ });
    await waitForGraphNode(container, "method:submit-order");
    expect(findGraphNodeElement(container, "class:order-draft-dto")).not.toBeNull();

    expect(findGraphNodeWrapper(container, "method:submit-order")).toHaveClass("is-explanation-focus");

    await dispatchHoverEvent(stepButton, "mouseenter");
    await waitFor(() => {
      expect(findGraphNodeWrapper(container, "class:order-draft-dto")).toHaveClass("is-explanation-focus");
    });

    await dispatchHoverEvent(stepButton, "mouseleave");
    await waitFor(() => {
      expect(findGraphNodeWrapper(container, "method:submit-order")).toHaveClass("is-explanation-focus");
    });
  });

  it("adds the current explanation step to the draft tab as a note", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "记为草稿备注" }));

    expect(screen.getByRole("tab", { name: "草稿" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getAllByText("Step 1 提交订单请求").length).toBeGreaterThan(0);
    expect(screen.getByText("当前方法负责接收入参并把请求交给下游服务。")).toBeInTheDocument();
    expect(screen.getByText("草稿说明详情")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "打开草稿说明：Step 1 提交订单请求" })).toBeInTheDocument();
  });

  it("reopens the explanation context when clicking a draft note", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "记为草稿备注" }));
    await user.click(screen.getByRole("button", { name: "打开草稿说明：Step 1 提交订单请求" }));

    expect(screen.getByRole("tab", { name: "讲解" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("heading", { name: "Step 1 提交订单请求" })).toBeInTheDocument();
  });

  it("locates the graph node from a draft note without opening the edit dialog", async () => {
    const user = userEvent.setup();
    const { container } = render(<App />);

    await user.click(screen.getByRole("button", { name: "记为草稿备注" }));
    await user.click(screen.getByRole("button", { name: "定位草稿说明对应节点：Step 1 提交订单请求" }));

    expect(screen.queryByRole("dialog", { name: "编辑节点" })).not.toBeInTheDocument();
    await waitFor(() => {
      expect(findGraphNodeElement(container, "method:submit-order")).toHaveClass("is-selected");
    });
  });

  it("opens the fixed qa workbench from the canvas context menu and pre-fills the scope question", async () => {
    const { container } = render(<App />);
    await waitForGraphNode(container, "method:submit-order");

    await openCanvasContextMenu(screen.getByTestId("graph-canvas-shell"), { clientX: 220, clientY: 180 });
    await dispatchClickEvent(screen.getByRole("menuitem", { name: "问答当前范围" }));
    await waitFor(() => {
      expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    });
    await dispatchClickEvent(screen.getByRole("tab", { name: "提问" }));
    await waitFor(() => {
      expect(screen.getByRole("textbox", { name: "问答输入框" })).toBeInTheDocument();
    });

    expect(screen.getByRole("tab", { name: "问答" })).toHaveAttribute("aria-selected", "true");
    expect(window.linkGraphBridge?.requestAudit).not.toHaveBeenCalled();
    expect(screen.getByRole("textbox", { name: "问答输入框" })).toHaveValue(
      "请围绕当前整张链路图进行问答，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。",
    );
  });

  it("allows editing the qa question draft once the qa composer is open", async () => {
    render(<App />);

    await dispatchClickEvent(screen.getByRole("tab", { name: "问答" }));
    await dispatchClickEvent(screen.getByRole("tab", { name: "提问" }));
    await waitFor(() => {
      expect(screen.getByRole("textbox", { name: "问答输入框" })).toBeInTheDocument();
    });

    const input = screen.getByRole("textbox", { name: "问答输入框" });
    await setTextboxValue(input, "介绍这个方法");

    expect(input).toHaveValue("介绍这个方法");
  });

  it("submits the qa question from the qa tab without synthesizing a local running banner before the backend snapshot arrives", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("tab", { name: "问答" }));
    await user.click(screen.getByRole("tab", { name: "提问" }));
    const input = screen.getByRole("textbox", { name: "问答输入框" });

    await setTextboxValue(input, "介绍这里有什么安全问题");
    await user.click(screen.getByRole("button", { name: "发送" }));

    expect(window.linkGraphBridge?.requestAudit).toHaveBeenCalledWith("介绍这里有什么安全问题", [], null, "AUTO");
    expect(screen.getByRole("button", { name: "收起当前页面" })).toBeInTheDocument();
    expect(screen.queryByText("已提交问答请求")).not.toBeInTheDocument();
    expect(screen.queryByText("等待后端确认执行方式与执行阶段。")).not.toBeInTheDocument();
    expect(screen.getByText("已发起问答请求，范围为整个链路。")).toBeInTheDocument();
  });

  it("retries the last failed qa request directly from the request status page", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      auditRequestState: {
        phase: "FAILED",
        scene: "问答",
        statusMessage: "问答失败",
        errorMessage: "上游超时",
        detailMessage: "连接上游超时",
      },
      qaRequestRecoveryState: {
        lastSubmittedRequest: {
          requestId: "qa-1",
          kind: "ASK",
          question: "这个方法是否遗漏补偿链路？",
          selectedNodeIds: ["method:submit-order"],
          sourceThreadId: null,
          baseSessionId: "audit-method-submit",
        },
        lastFailedRequest: {
          requestId: "qa-1",
          kind: "ASK",
          question: "这个方法是否遗漏补偿链路？",
          selectedNodeIds: ["method:submit-order"],
          sourceThreadId: null,
          baseSessionId: "audit-method-submit",
        },
      },
      workbenchSectionPreferences: {
        "audit.request-status": true,
      },
    });
    render(<App />);

    await user.click(screen.getByRole("tab", { name: "问答" }));
    await user.click(screen.getByRole("button", { name: "直接重试" }));

    expect(window.linkGraphBridge?.retryLastAuditRequest).toHaveBeenCalledTimes(1);
  });

  it("submits deferred risk resolution from the audit risk page", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      auditResult: {
        ...bootstrapStateFixture().auditResult!,
        investigationThreads: [
          {
            threadId: "thread-path-risk",
            status: "OPEN",
            title: "补充路径风险说明",
            targetStepIds: [],
            targetNodeIds: ["method:submit-order"],
            summary: "当前只有调用点证据。",
            evidenceGap: "还没有看到上传工具内部路径校验实现。",
            recommendedQuestion: "请继续取证：展开 FileUploadUtils.upload，确认是否存在路径规范化或目录校验。",
            claimType: "RISK_HINT",
            evidence: [],
            latestTurnOutcomeId: null,
            resolution: {
              threadId: "thread-path-risk",
              status: "UNRESOLVED",
              note: "",
            },
          },
        ],
      },
      workbenchSectionPreferences: {
        "audit.investigation-threads": true,
      },
    });
    render(<App />);

    await user.click(screen.getByRole("tab", { name: "问答" }));
    await user.click(screen.getByRole("button", { name: "暂挂风险" }));

    expect(window.linkGraphBridge?.resolveInvestigationThread).toHaveBeenCalledWith(
      "thread-path-risk",
      "DEFERRED",
      "",
    );
  });

  it("updates the visible risk decision immediately after accepting a risk", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      auditResult: {
        ...bootstrapStateFixture().auditResult!,
        investigationThreads: [
          {
            threadId: "thread-path-risk",
            status: "OPEN",
            title: "补充路径风险说明",
            targetStepIds: [],
            targetNodeIds: ["method:submit-order"],
            summary: "当前只有调用点证据。",
            evidenceGap: "还没有看到上传工具内部路径校验实现。",
            recommendedQuestion: "请继续取证：展开 FileUploadUtils.upload，确认是否存在路径规范化或目录校验。",
            claimType: "RISK_HINT",
            evidence: [],
            latestTurnOutcomeId: null,
            resolution: {
              threadId: "thread-path-risk",
              status: "UNRESOLVED",
              note: "",
            },
          },
        ],
      },
      workbenchSectionPreferences: {
        "audit.investigation-threads": true,
      },
    });
    render(<App />);

    await user.click(screen.getByRole("tab", { name: "问答" }));
    expect(screen.getByText("未决")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "接受风险" }));

    expect(window.linkGraphBridge?.resolveInvestigationThread).toHaveBeenCalledWith(
      "thread-path-risk",
      "ACCEPTED_RISK",
      "",
    );
    expect(screen.getAllByText("接受风险").length).toBeGreaterThan(1);
    expect(screen.queryByText("未决")).not.toBeInTheDocument();
  });

  it("keeps qa open after confirming later candidates and selects the draft entry for manual review", async () => {
    const user = userEvent.setup();
    const firstCandidate = candidateChangeFixture();
    const secondCandidate: CandidateDraftChange = {
      ...candidateChangeFixture(),
      changeId: "change-path-guard",
      title: "补充路径规范化校验",
      beforeState: "删除前直接使用 filePath",
      afterState: "删除前先规范化并校验 filePath",
      reason: "删除文件前需要约束到允许目录。",
      impactSummary: "降低路径穿越误删风险。",
      evidence: [
        {
          id: "finding-path-guard",
          claim: "当前源码中删除文件前缺少路径规范化校验。",
          evidenceLevel: "DIRECT_SOURCE",
          references: [{ nodeId: "method:submit-order" }],
        },
      ],
      editScopes: [
        {
          ...candidateChangeFixture().editScopes![0],
          scopeId: "scope-path-guard",
          supportingFindingIds: ["finding-path-guard"],
        },
      ],
    };
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      auditResult: {
        ...bootstrapStateFixture().auditResult!,
        candidateChanges: [firstCandidate, secondCandidate],
        newCandidateChanges: [firstCandidate, secondCandidate],
        auditSession: {
          ...bootstrapStateFixture().auditResult!.auditSession!,
          candidateChanges: [firstCandidate, secondCandidate],
        },
      },
      workbenchSectionPreferences: {
        "audit.candidate-changes": true,
      },
    });
    render(<App />);

    await user.click(screen.getByRole("tab", { name: "问答" }));
    await user.click(screen.getByRole("button", { name: "待确认变更：补充路径规范化校验" }));
    await user.click(screen.getByRole("button", { name: "确认这条变更" }));

    expect(window.linkGraphBridge?.confirmAuditCandidateChange).toHaveBeenCalledWith("change-path-guard");
    expect(screen.getByRole("tab", { name: "问答" })).toHaveAttribute("aria-selected", "true");
    await user.click(screen.getByRole("tab", { name: "草稿" }));
    expect(screen.getByRole("tab", { name: "草稿" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getAllByText("补充路径规范化校验").length).toBeGreaterThan(0);
    expect(screen.getAllByText("删除前先规范化并校验 filePath").length).toBeGreaterThan(0);
    expect(screen.queryByText("补充失败补偿逻辑说明")).not.toBeInTheDocument();
  });

  it("surfaces a bridge-unavailable failure instead of pretending the qa request was accepted", async () => {
    const user = userEvent.setup();
    const requestAudit = vi.fn();
    window.linkGraphBridge = undefined;
    render(<App />);

    await user.click(screen.getByRole("tab", { name: "问答" }));
    await user.click(screen.getByRole("tab", { name: "提问" }));
    const input = screen.getByRole("textbox", { name: "问答输入框" });

    await setTextboxValue(input, "介绍这里有什么安全问题");
    await user.click(screen.getByRole("button", { name: "发送" }));

    const failureDialog = screen.getByRole("dialog", { name: "请求状态通知" });
    expect(failureDialog).toBeInTheDocument();
    expect(screen.queryByText("问答：已提交问答请求")).not.toBeInTheDocument();
    expect(failureDialog).toHaveTextContent("IDE bridge 尚未就绪，本次请求没有发出。");
    expect(requestAudit).not.toHaveBeenCalled();

    window.linkGraphBridge = {
      requestAudit,
    };
    window.dispatchEvent(new Event("link-graph-bridge-ready"));

    expect(requestAudit).not.toHaveBeenCalled();
  });

  it("preserves the current selected node while qa request state updates stream back", async () => {
    const state = bootstrapStateFixture();
    state.graphBeautificationResult = {
      ...state.graphBeautificationResult!,
      steps: [
        explanationStepFixture(),
        {
          stepId: "step-validate-order",
          title: "Step 2 校验订单参数",
          granularity: "BUSINESS",
          kind: "BUSINESS_ACTION",
          description: "这里校验订单请求参数。",
          primaryNodeId: "class:order-draft-dto",
          codeSnippet: "validator.validate(request);",
          evidence: [
            {
              id: "step-validate-order-source",
              claim: "这里命中了参数校验逻辑。",
              evidenceLevel: "DIRECT_SOURCE",
              references: [
                {
                  nodeId: "class:order-draft-dto",
                  filePath: "/project/src/main/java/com/example/OrderDraftDto.java",
                  startLine: 4,
                  endLine: 9,
                },
              ],
            },
          ],
          followUpQuestions: ["校验失败会怎么处理？"],
          downstreamTargets: [],
        },
      ],
    };
    window.linkGraphBootstrap = state;

    const { container } = render(<App />);
    await openCanvasContextMenu(screen.getByRole("button", { name: /Step 2 校验订单参数/ }), {
      clientX: 320,
      clientY: 240,
    });
    await dispatchClickEvent(screen.getByRole("menuitem", { name: "定位到图中节点" }));
    await waitFor(() => {
      expect(findGraphNodeElement(container, "class:order-draft-dto")).toHaveClass("is-selected");
    });

    await applyBootstrapEnvelope({
      sessionId: "session-1",
      revision: 2,
      state: {
        ...structuredClone(state),
        snapshotRevision: 2,
        lastMessageType: "requestAudit",
        auditRequestState: {
          phase: "RUNNING",
          requestId: 2,
          scene: "问答",
          streaming: true,
          statusMessage: "正在问答",
        },
      },
    });

    await waitFor(() => {
      expect(findGraphNodeElement(container, "class:order-draft-dto")).toHaveClass("is-selected");
    });
  });

  it("routes explanation requests through the node context menu action", async () => {
    const { container } = render(<App />);
    await waitForGraphNode(container, "method:submit-order");

    await openCanvasContextMenu(requireGraphNodeElement(container, "method:submit-order"), {
      clientX: 260,
      clientY: 180,
    });
    await dispatchClickEvent(screen.getByRole("menuitem", { name: "讲解当前链路" }));

    expect(window.linkGraphBridge?.requestGraphBeautification).toHaveBeenCalledWith(
      "",
      undefined,
      "请重点讲解节点“OrderController.submit”在当前链路中的作用、上下游关系与关键分支。",
      "BUSINESS",
      undefined,
      undefined,
      undefined,
    );
    expect(screen.getByRole("tab", { name: "讲解" })).toHaveAttribute("aria-selected", "true");
  });

  it("switches explanation granularity and requests a fresh projection with the chosen level", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "方法调用级" }));

    expect(window.linkGraphBridge?.requestGraphBeautification).toHaveBeenCalledWith(
      "",
      undefined,
      undefined,
      "METHOD_CALL",
      undefined,
      undefined,
      undefined,
    );
  });

  it("clears explanation return history when switching granularity", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "围绕这一步继续讲解" }));

    const nextState = structuredClone(bootstrapStateFixture());
    nextState.snapshotRevision = 2;
    nextState.graphBeautificationResult = {
      ...nextState.graphBeautificationResult!,
      steps: [
        {
          ...explanationStepFixture(),
          stepId: "step-submit-order-follow-up",
          title: "Step 1.1 继续分析订单校验失败",
          description: "这里继续展开订单校验失败后的分支处理。",
        },
      ],
    };
    nextState.graphBeautificationRequestState = {
      phase: "SUCCEEDED",
      errorMessage: null,
    };
    nextState.lastMessageType = "graphBeautificationResult";

    await applyBootstrapEnvelope({
      sessionId: "session-1",
      revision: 2,
      state: nextState,
    });

    expect(await screen.findByRole("button", { name: "返回上一讲解：当前链路讲解" })).toBeInTheDocument();

    await dispatchClickEvent(screen.getByRole("button", { name: "方法调用级" }));

    const methodCallState = structuredClone(bootstrapStateFixture());
    methodCallState.snapshotRevision = 3;
    methodCallState.graphBeautificationResult = {
      ...methodCallState.graphBeautificationResult!,
      granularity: "METHOD_CALL",
      steps: [
        {
          ...explanationStepFixture(),
          stepId: "step-submit-order-method-call",
          granularity: "METHOD_CALL",
          title: "Step 1 方法调用级讲解",
          description: "这里切换到方法调用级重新组织步骤。",
        },
      ],
    };
    methodCallState.graphBeautificationRequestState = {
      phase: "SUCCEEDED",
      errorMessage: null,
    };
    methodCallState.lastMessageType = "graphBeautificationResult";

    await applyBootstrapEnvelope({
      sessionId: "session-1",
      revision: 3,
      state: methodCallState,
    });

    await waitFor(() => {
      expect(screen.queryByRole("button", { name: "返回上一讲解：当前链路讲解" })).not.toBeInTheDocument();
    });
  });

  it("shows explanation history breadcrumbs and lets the reader jump back to an earlier explanation snapshot", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "围绕这一步继续讲解" }));

    const followUpStateOne = structuredClone(bootstrapStateFixture());
    followUpStateOne.snapshotRevision = 2;
    followUpStateOne.graphBeautificationResult = {
      ...followUpStateOne.graphBeautificationResult!,
      steps: [
        {
          ...explanationStepFixture(),
          stepId: "step-submit-order-follow-up-1",
          title: "Step 1.1 继续分析订单校验失败",
          description: "这里继续展开订单校验失败后的分支处理。",
          followUpQuestions: ["失败后是否有补偿逻辑？"],
        },
      ],
    };
    followUpStateOne.lastMessageType = "graphBeautificationResult";

    await applyBootstrapEnvelope({
      sessionId: "session-1",
      revision: 2,
      state: followUpStateOne,
    });

    await screen.findByRole("button", { name: "返回上一讲解：当前链路讲解" });
    await dispatchClickEvent(screen.getByRole("button", { name: "围绕这一步继续讲解" }));

    const followUpStateTwo = structuredClone(bootstrapStateFixture());
    followUpStateTwo.snapshotRevision = 3;
    followUpStateTwo.graphBeautificationResult = {
      ...followUpStateTwo.graphBeautificationResult!,
      steps: [
        {
          ...explanationStepFixture(),
          stepId: "step-submit-order-follow-up-2",
          title: "Step 1.2 继续分析失败补偿",
          description: "这里继续展开失败补偿是否存在。",
          followUpQuestions: ["补偿逻辑最终在哪里落地？"],
        },
      ],
    };
    followUpStateTwo.lastMessageType = "graphBeautificationResult";

    await applyBootstrapEnvelope({
      sessionId: "session-1",
      revision: 3,
      state: followUpStateTwo,
    });

    expect(await screen.findByRole("button", { name: "讲解历史：当前链路讲解" })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "讲解历史：围绕 Step 1 提交订单请求 继续讲解" })).toBeInTheDocument();
    expect(screen.getAllByText("Step 1.2 继续分析失败补偿").length).toBeGreaterThan(0);

    await dispatchClickEvent(screen.getByRole("button", { name: "讲解历史：当前链路讲解" }));

    expect(screen.getAllByText("Step 1 提交订单请求").length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: "讲解历史：围绕 Step 1 提交订单请求 继续讲解" })).not.toBeInTheDocument();
  });

  it("routes candidate confirmation through the IDE bridge without switching away from qa", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("tab", { name: "问答" }));
    await user.click(screen.getByRole("tab", { name: "待确认变更" }));
    await applyBootstrapEnvelope({
      sessionId: "session-1",
      revision: 2,
      state: {
        ...structuredClone(window.linkGraphBootstrap!),
        snapshotRevision: 2,
        workbenchSectionPreferences: {
          "audit.candidate-changes": true,
        },
      },
    });
    await user.click(screen.getByRole("button", { name: "确认这条变更" }));

    expect(window.linkGraphBridge?.confirmAuditCandidateChange).toHaveBeenCalledWith("change-compensate");
    expect(screen.getByRole("tab", { name: "问答" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("已确认候选变更并写入草稿层，可切到草稿查看。")).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "草稿" }));
    expect(screen.getByRole("tab", { name: "草稿" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getAllByText("补充失败补偿说明")).toHaveLength(2);
    expect(screen.getByText("当前链路缺少失败补偿语义。")).toBeInTheDocument();
    expect(screen.getByText("修改后")).toBeInTheDocument();
    expect(screen.queryByText("修改前")).not.toBeInTheDocument();
    expect(screen.getByText("已授权 1 个精确写回范围。")).toBeInTheDocument();
    expect(screen.getByText("/project/src/main/java/com/example/OrderController.java:8-16")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "查看流程变化" }));

    expect(screen.getByText("修改前")).toBeInTheDocument();
    expect(screen.getByText("当前没有失败补偿说明")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "问答" }));

    expect(screen.queryByRole("tab", { name: "待确认变更" })).not.toBeInTheDocument();
  });

  it("focuses the real patched node in draft mode instead of staying on the stale candidate target", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = materializeThreeViewDocuments({
      visibleGraph: {
        nodes: [
          {
            id: "method:file-download",
            type: "METHOD",
            title: "CommonController.fileDownload",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "flow-scope:delete-guard",
            type: "FLOW_SCOPE",
            title: "if (delete == true)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "DRAFT_AI",
            metadata: {
              "flowchart.kind": "DECISION",
            },
          },
        ],
        edges: [
          {
            id: "control:file-download->delete-guard",
            type: "CONTROL_FLOW",
            source: "method:file-download",
            target: "flow-scope:delete-guard",
            sourceTag: "FACT",
          },
        ],
      },
      referenceFactGraph: {
        nodes: [
          {
            id: "method:file-download",
            type: "METHOD",
            title: "CommonController.fileDownload",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "flow-scope:delete-guard",
            type: "FLOW_SCOPE",
            title: "if (delete)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "DECISION",
            },
          },
        ],
        edges: [
          {
            id: "control:file-download->delete-guard",
            type: "CONTROL_FLOW",
            source: "method:file-download",
            target: "flow-scope:delete-guard",
            sourceTag: "FACT",
          },
        ],
      },
      workingGraph: {
        nodes: [
          {
            id: "method:file-download",
            type: "METHOD",
            title: "CommonController.fileDownload",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "flow-scope:delete-guard",
            type: "FLOW_SCOPE",
            title: "if (delete == true)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "DRAFT_AI",
            metadata: {
              "flowchart.kind": "DECISION",
            },
          },
        ],
        edges: [
          {
            id: "control:file-download->delete-guard",
            type: "CONTROL_FLOW",
            source: "method:file-download",
            target: "flow-scope:delete-guard",
            sourceTag: "FACT",
          },
        ],
      },
      draftWorkbenchState: {
        draftChanges: [
          {
            entryId: "draft-change-delete-guard",
            kind: "CHANGE",
            title: "将删除条件从 if (delete) 改为 if (delete == true)",
            sourceChangeId: "change-delete-guard",
            targetStepIds: [],
            targetNodeIds: ["method:file-download"],
            beforeState: "if (delete)",
            afterState: "if (delete == true)",
            reason: "需要显式判断布尔值。",
            impactSummary: "影响删除分支。",
            claimType: "CODE_FACT",
            evidence: [
              {
                id: "finding-delete-guard",
                claim: "当前源码里直接能看到删除判断条件。",
                evidenceLevel: "DIRECT_SOURCE",
                references: [{ nodeId: "flow-scope:delete-guard" }],
              },
            ],
            graphPatch: {
              summary: "调整删除判断",
              operations: [
                {
                  id: "patch-op-delete-guard",
                  action: "UPDATE_NODE",
                  elementKind: "NODE",
                  elementId: "flow-scope:delete-guard",
                  node: {
                    id: "flow-scope:delete-guard",
                    type: "FLOW_SCOPE",
                    title: "if (delete == true)",
                    inputs: [],
                    outputs: [],
                    certainty: "LLM_SUGGESTED",
                    bindingStatus: "BOUND",
                    metadata: {
                      "flowchart.kind": "DECISION",
                    },
                  },
                },
              ],
              addedNodeIds: [],
              removedNodeIds: [],
              addedEdgeIds: [],
              removedEdgeIds: [],
            },
          },
        ],
        draftNotes: [],
      },
      selectedNodeId: "method:file-download",
      analysisDisplayMode: "FLOWCHART",
    });

    const { container } = render(<App />);

    await waitForGraphNode(container, "flow-scope:delete-guard");
    await user.click(screen.getByRole("tab", { name: "草稿" }));

    await waitFor(() => {
      expect(findGraphNodeElement(container, "flow-scope:delete-guard")).toHaveClass("is-selected");
    });
    const flowchartSummary = screen.getByLabelText("流程图摘要");
    const currentSelectionCard = within(flowchartSummary)
      .getByText("当前选中")
      .closest("article");
    expect(currentSelectionCard).not.toBeNull();
    expect(within(currentSelectionCard as HTMLElement).getByText("if (delete == true)")).toBeInTheDocument();
  });

  it("overlays the stale flowchart visible node with the working-graph decision title while keeping hidden full-graph nodes out of the stage", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = materializeThreeViewDocuments({
      visibleGraph: {
        nodes: [
          {
            id: "method:file-download",
            type: "METHOD",
            title: "CommonController.fileDownload",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "scope:file-download-if",
            type: "FLOW_SCOPE",
            title: "if (delete)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "DECISION",
              "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String):void",
            },
          },
        ],
        edges: [
          {
            id: "edge:file-download->if-delete",
            type: "CONTROL_FLOW",
            source: "method:file-download",
            target: "scope:file-download-if",
            sourceTag: "FACT",
          },
        ],
      },
      workingGraph: {
        nodes: [
          {
            id: "method:file-download",
            type: "METHOD",
            title: "CommonController.fileDownload",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "action:delete-condition",
            type: "FLOW_ACTION",
            title: "FileUtils.checkAllowDownload(fileName)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "PROCESS",
              "flow.kind": "CONDITION",
              "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String):void",
            },
          },
          {
            id: "scope:file-download-if",
            type: "FLOW_SCOPE",
            title: "if (delete == true)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "DRAFT_AI",
            metadata: {
              "flowchart.kind": "DECISION",
              "flowchart.projectedFromNodeIds": "action:delete-condition",
              "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String):void",
            },
          },
        ],
        edges: [
          {
            id: "edge:file-download->if-delete",
            type: "CONTROL_FLOW",
            source: "method:file-download",
            target: "scope:file-download-if",
            sourceTag: "FACT",
          },
        ],
      },
      draftWorkbenchState: {
        draftChanges: [
          {
            entryId: "draft-change-delete-guard",
            kind: "CHANGE",
            title: "将删除条件收紧为显式 true 判断",
            sourceChangeId: "change-delete-guard",
            targetStepIds: [],
            targetNodeIds: ["scope:file-download-if"],
            beforeState: "if (delete)",
            afterState: "if (delete == true)",
            reason: "需要显式判断布尔值。",
            impactSummary: "影响删除分支。",
            claimType: "CODE_FACT",
            evidence: [
              {
                id: "finding-delete-guard",
                claim: "当前源码里直接能看到删除判断条件。",
                evidenceLevel: "DIRECT_SOURCE",
                references: [{ nodeId: "scope:file-download-if" }],
              },
            ],
            graphPatch: {
              summary: "调整删除判断",
              operations: [
                {
                  id: "patch-op-delete-guard",
                  action: "UPDATE_NODE",
                  elementKind: "NODE",
                  elementId: "scope:file-download-if",
                  node: {
                    id: "scope:file-download-if",
                    type: "FLOW_SCOPE",
                    title: "if (delete == true)",
                    inputs: [],
                    outputs: [],
                    certainty: "LLM_SUGGESTED",
                    bindingStatus: "BOUND",
                    metadata: {
                      "flowchart.kind": "DECISION",
                    },
                  },
                },
              ],
              addedNodeIds: [],
              removedNodeIds: [],
              addedEdgeIds: [],
              removedEdgeIds: [],
            },
          },
        ],
        draftNotes: [],
      },
      selectedNodeId: "scope:file-download-if",
      analysisDisplayMode: "FLOWCHART",
    });

    const { container } = render(<App />);

    await waitForGraphNode(container, "scope:file-download-if");
    await user.click(screen.getByRole("tab", { name: "草稿" }));

    await waitFor(() => {
      const flowchartSummary = screen.getByLabelText("流程图摘要");
      const currentSelectionCard = within(flowchartSummary)
        .getByText("当前选中")
        .closest("article");
      expect(currentSelectionCard).not.toBeNull();
      expect(within(currentSelectionCard as HTMLElement).getByText("if (delete == true)")).toBeInTheDocument();
    });
    expect(screen.queryByText("FileUtils.checkAllowDownload(fileName)")).not.toBeInTheDocument();
  });

  it("keeps flowchart node positions stable when draft after-mode only changes presentation text", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = materializeThreeViewDocuments({
      visibleGraph: {
        nodes: [
          {
            id: "method:file-download",
            type: "METHOD",
            title: "CommonController.fileDownload",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "flow-scope:delete-guard",
            type: "FLOW_SCOPE",
            title: "if (delete)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "DECISION",
              "flow.kind": "IF",
              "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String):void",
            },
          },
        ],
        edges: [
          {
            id: "edge:file-download->delete-guard",
            type: "CONTROL_FLOW",
            source: "method:file-download",
            target: "flow-scope:delete-guard",
            sourceTag: "FACT",
          },
        ],
      },
      workingGraph: {
        nodes: [
          {
            id: "method:file-download",
            type: "METHOD",
            title: "CommonController.fileDownload",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "flow-scope:delete-guard",
            type: "FLOW_SCOPE",
            title: "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "DRAFT_AI",
            metadata: {
              "flowchart.kind": "DECISION",
              "flow.kind": "IF",
              "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String):void",
            },
          },
        ],
        edges: [
          {
            id: "edge:file-download->delete-guard",
            type: "CONTROL_FLOW",
            source: "method:file-download",
            target: "flow-scope:delete-guard",
            sourceTag: "FACT",
          },
        ],
      },
      draftWorkbenchState: {
        draftChanges: [
          {
            entryId: "draft-delete-guard",
            kind: "CHANGE",
            title: "收紧删除条件",
            sourceChangeId: "change-delete-guard",
            targetStepIds: [],
            targetNodeIds: ["flow-scope:delete-guard"],
            beforeState: "if (delete)",
            afterState: "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
            reason: "避免 Boolean 拆箱并增加文件存在性校验。",
            impactSummary: "影响删除分支。",
            claimType: "CODE_FACT",
            evidence: [
              {
                id: "finding-delete-guard",
                claim: "当前源码里直接能看到删除判断条件。",
                evidenceLevel: "DIRECT_SOURCE",
                references: [{ nodeId: "flow-scope:delete-guard" }],
              },
            ],
            graphPatch: {
              summary: "调整删除判断",
              operations: [
                {
                  id: "patch-op-delete-guard",
                  action: "UPDATE_NODE",
                  elementKind: "NODE",
                  elementId: "flow-scope:delete-guard",
                  node: {
                    id: "flow-scope:delete-guard",
                    type: "FLOW_SCOPE",
                    title: "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
                    inputs: [],
                    outputs: [],
                    certainty: "LLM_SUGGESTED",
                    bindingStatus: "BOUND",
                    metadata: {
                      "flowchart.kind": "DECISION",
                      "flow.kind": "IF",
                    },
                  },
                },
              ],
              addedNodeIds: [],
              removedNodeIds: [],
              addedEdgeIds: [],
              removedEdgeIds: [],
            },
          },
        ],
        draftNotes: [],
      },
      selectedNodeId: "method:file-download",
      analysisDisplayMode: "FLOWCHART",
    });

    const { container } = render(<App />);

    await waitForGraphNode(container, "flow-scope:delete-guard");
    const beforeWrapper = findGraphNodeWrapper(container, "flow-scope:delete-guard");
    expect(beforeWrapper).not.toBeNull();
    const beforeTransform = (beforeWrapper as HTMLElement).style.transform;
    expect(beforeTransform).toContain("translate(");

    await user.click(screen.getByRole("tab", { name: "草稿" }));

    await waitFor(() => {
      const flowchartSummary = screen.getByLabelText("流程图摘要");
      const currentSelectionCard = within(flowchartSummary).getByText("当前选中").closest("article");
      expect(currentSelectionCard).not.toBeNull();
      expect(within(currentSelectionCard as HTMLElement).getByText("if (Boolean.TRUE.equals(delete) && fileExists(filePath))")).toBeInTheDocument();
    });

    const afterWrapper = findGraphNodeWrapper(container, "flow-scope:delete-guard");
    expect(afterWrapper).not.toBeNull();
    expect((afterWrapper as HTMLElement).style.transform).toBe(beforeTransform);
  });

  it("does not relayout child nodes when draft after-mode only retitles the anchor method", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = materializeThreeViewDocuments({
      visibleGraph: {
        nodes: [
          {
            id: "method:file-download",
            type: "METHOD",
            title: "CommonController.fileDownload",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "scope:file-download-if",
            type: "FLOW_SCOPE",
            title: "if (delete)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "DECISION",
              "flow.kind": "IF",
              "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String):void",
            },
          },
        ],
        edges: [
          {
            id: "edge:file-download->if-delete",
            type: "CONTROL_FLOW",
            source: "method:file-download",
            target: "scope:file-download-if",
            sourceTag: "FACT",
          },
        ],
      },
      workingGraph: {
        nodes: [
          {
            id: "method:file-download",
            type: "METHOD",
            title: "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "DRAFT_AI",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "scope:file-download-if",
            type: "FLOW_SCOPE",
            title: "if (delete)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "DECISION",
              "flow.kind": "IF",
              "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String):void",
            },
          },
        ],
        edges: [
          {
            id: "edge:file-download->if-delete",
            type: "CONTROL_FLOW",
            source: "method:file-download",
            target: "scope:file-download-if",
            sourceTag: "FACT",
          },
        ],
      },
      draftWorkbenchState: {
        draftChanges: [
          {
            entryId: "draft-method-title",
            kind: "CHANGE",
            title: "收紧删除条件",
            sourceChangeId: "change-method-title",
            targetStepIds: [],
            targetNodeIds: ["method:file-download"],
            beforeState: "CommonController.fileDownload",
            afterState: "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
            reason: "复现草稿 after 模式只变展示文本的场景。",
            impactSummary: "不应触发布局重算。",
            claimType: "CODE_FACT",
            evidence: [
              {
                id: "finding-method-title",
                claim: "当前方法节点在草稿层会被投影为 after title。",
                evidenceLevel: "DIRECT_SOURCE",
                references: [{ nodeId: "method:file-download" }],
              },
            ],
            graphPatch: {
              summary: "调整方法展示标题",
              operations: [
                {
                  id: "patch-op-method-title",
                  action: "UPDATE_NODE",
                  elementKind: "NODE",
                  elementId: "method:file-download",
                  node: {
                    id: "method:file-download",
                    type: "METHOD",
                    title: "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
                    inputs: [],
                    outputs: [],
                    certainty: "LLM_SUGGESTED",
                    bindingStatus: "BOUND",
                    metadata: {
                      "flowchart.kind": "ENTRY",
                    },
                  },
                },
              ],
              addedNodeIds: [],
              removedNodeIds: [],
              addedEdgeIds: [],
              removedEdgeIds: [],
            },
          },
        ],
        draftNotes: [],
      },
      selectedNodeId: "method:file-download",
      analysisDisplayMode: "FLOWCHART",
    });

    const { container } = render(<App />);

    await waitForGraphNode(container, "scope:file-download-if");
    const beforeWrapper = findGraphNodeWrapper(container, "scope:file-download-if");
    expect(beforeWrapper).not.toBeNull();
    const beforeTransform = (beforeWrapper as HTMLElement).style.transform;
    expect(beforeTransform).toContain("translate(");

    await user.click(screen.getByRole("tab", { name: "草稿" }));

    await waitFor(() => {
      const flowchartSummary = screen.getByLabelText("流程图摘要");
      const currentSelectionCard = within(flowchartSummary).getByText("当前选中").closest("article");
      expect(currentSelectionCard).not.toBeNull();
      expect(within(currentSelectionCard as HTMLElement).getByText("if (Boolean.TRUE.equals(delete) && fileExists(filePath))")).toBeInTheDocument();
    });

    const afterWrapper = findGraphNodeWrapper(container, "scope:file-download-if");
    expect(afterWrapper).not.toBeNull();
    expect((afterWrapper as HTMLElement).style.transform).toBe(beforeTransform);
  });

  it("prefers the confirmed draft graphPatch over a stale working graph when presenting flowchart after-state", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = materializeThreeViewDocuments({
      visibleGraph: {
        nodes: [
          {
            id: "method:file-download",
            type: "METHOD",
            title: "CommonController.fileDownload",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "scope:file-download-if",
            type: "FLOW_SCOPE",
            title: "if (delete)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flow.kind": "IF",
              "flowchart.kind": "DECISION",
              "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
            },
          },
        ],
        edges: [
          {
            id: "edge:file-download->if-delete",
            type: "CONTROL_FLOW",
            source: "method:file-download",
            target: "scope:file-download-if",
            sourceTag: "FACT",
          },
        ],
      },
      workingGraph: {
        nodes: [
          {
            id: "method:file-download",
            type: "METHOD",
            title: "CommonController.fileDownload",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "scope:file-download-if",
            type: "FLOW_SCOPE",
            title: "if (delete)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flow.kind": "IF",
              "flowchart.kind": "DECISION",
              "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
            },
          },
        ],
        edges: [
          {
            id: "edge:file-download->if-delete",
            type: "CONTROL_FLOW",
            source: "method:file-download",
            target: "scope:file-download-if",
            sourceTag: "FACT",
          },
        ],
      },
      draftWorkbenchState: {
        draftChanges: [
          {
            entryId: "draft-change-delete-guard",
            kind: "CHANGE",
            title: "将删除条件收紧为显式 true 判断",
            sourceChangeId: "change-delete-guard",
            targetStepIds: [],
            targetNodeIds: ["scope:file-download-if"],
            beforeState: "if (delete)",
            afterState: "if (Boolean.TRUE.equals(delete))",
            reason: "需要规避 delete 为 null 时的误删与 NPE 风险。",
            impactSummary: "影响删除分支。",
            claimType: "CODE_FACT",
            evidence: [
              {
                id: "finding-delete-guard",
                claim: "当前源码里直接能看到删除判断条件。",
                evidenceLevel: "DIRECT_SOURCE",
                references: [{ nodeId: "scope:file-download-if" }],
              },
            ],
            graphPatch: {
              summary: "调整删除判断",
              operations: [
                {
                  id: "patch-op-delete-guard",
                  action: "UPDATE_NODE",
                  elementKind: "NODE",
                  elementId: "scope:file-download-if",
                  node: {
                    id: "scope:file-download-if",
                    type: "FLOW_SCOPE",
                    title: "if (Boolean.TRUE.equals(delete))",
                    inputs: [],
                    outputs: [],
                    certainty: "LLM_SUGGESTED",
                    bindingStatus: "BOUND",
                    sourceTag: "DRAFT_AI",
                    metadata: {
                      "flow.kind": "IF",
                      "flowchart.kind": "DECISION",
                    },
                  },
                },
              ],
              addedNodeIds: [],
              removedNodeIds: [],
              addedEdgeIds: [],
              removedEdgeIds: [],
            },
          },
        ],
        draftNotes: [],
      },
      selectedNodeId: "scope:file-download-if",
      analysisDisplayMode: "FLOWCHART",
    });

    const { container } = render(<App />);

    await waitForGraphNode(container, "scope:file-download-if");
    await user.click(screen.getByRole("tab", { name: "草稿" }));

    await waitFor(() => {
      const flowchartSummary = screen.getByLabelText("流程图摘要");
      const currentSelectionCard = within(flowchartSummary)
        .getByText("当前选中")
        .closest("article");
      expect(currentSelectionCard).not.toBeNull();
      expect(within(currentSelectionCard as HTMLElement).getByText("if (Boolean.TRUE.equals(delete))")).toBeInTheDocument();
    });
  });

  it("scopes flowchart canvas and draft panel to the current method instead of showing another method's delete draft", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = materializeThreeViewDocuments({
      visibleGraph: {
        nodes: [
          {
            id: "method:upload-file",
            type: "METHOD",
            title: "CommonController.uploadFile",
            signature: "com.example.CommonController.uploadFile(java.lang.String):void",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "flow-action:upload-prepare",
            type: "FLOW_ACTION",
            title: "准备上传目录",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "PROCESS",
              "flow.ownerMethod": "com.example.CommonController.uploadFile(java.lang.String):void",
            },
          },
          {
            id: "method:file-download",
            type: "METHOD",
            title: "CommonController.fileDownload",
            signature: "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "flow-scope:delete-guard",
            type: "FLOW_SCOPE",
            title: "if (delete)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "DECISION",
              "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
            },
          },
        ],
        edges: [
          {
            id: "control:upload-file->upload-prepare",
            type: "CONTROL_FLOW",
            source: "method:upload-file",
            target: "flow-action:upload-prepare",
            sourceTag: "FACT",
          },
          {
            id: "control:file-download->delete-guard",
            type: "CONTROL_FLOW",
            source: "method:file-download",
            target: "flow-scope:delete-guard",
            sourceTag: "FACT",
          },
        ],
      },
      workingGraph: {
        nodes: [
          {
            id: "method:upload-file",
            type: "METHOD",
            title: "CommonController.uploadFile",
            signature: "com.example.CommonController.uploadFile(java.lang.String):void",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "flow-action:upload-prepare",
            type: "FLOW_ACTION",
            title: "准备上传目录",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "PROCESS",
              "flow.ownerMethod": "com.example.CommonController.uploadFile(java.lang.String):void",
            },
          },
          {
            id: "method:file-download",
            type: "METHOD",
            title: "CommonController.fileDownload",
            signature: "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "ENTRY",
            },
          },
          {
            id: "flow-scope:delete-guard",
            type: "FLOW_SCOPE",
            title: "if (delete)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            sourceTag: "FACT",
            metadata: {
              "flowchart.kind": "DECISION",
              "flow.ownerMethod": "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
            },
          },
        ],
        edges: [
          {
            id: "control:upload-file->upload-prepare",
            type: "CONTROL_FLOW",
            source: "method:upload-file",
            target: "flow-action:upload-prepare",
            sourceTag: "FACT",
          },
          {
            id: "control:file-download->delete-guard",
            type: "CONTROL_FLOW",
            source: "method:file-download",
            target: "flow-scope:delete-guard",
            sourceTag: "FACT",
          },
        ],
      },
      referenceFactGraph: {
        nodes: [],
        edges: [],
      },
      draftWorkbenchState: {
        draftChanges: [
          {
            entryId: "draft-change-delete-guard",
            kind: "CHANGE",
            title: "需要先定位用户提到的 if delete 分支",
            sourceChangeId: "change-delete-guard",
            targetStepIds: [],
            targetNodeIds: ["flow-scope:delete-guard"],
            beforeState: "if (delete)",
            afterState: "需要先定位用户提到的 if delete 分支",
            reason: "当前还没拿到精确控制流节点。",
            impactSummary: "需要先补证据。",
            claimType: "STRUCTURAL_SUGGESTION",
            evidence: [
              {
                id: "finding-delete-guard",
                claim: "当前提到的 if delete 位于 fileDownload。",
                evidenceLevel: "DIRECT_SOURCE",
                references: [{ nodeId: "flow-scope:delete-guard" }],
              },
            ],
            graphPatch: null,
          },
        ],
        draftNotes: [],
      },
      analysisDisplayMode: "FLOWCHART",
      selectedNodeId: "method:upload-file",
    });

    const { container } = render(<App />);

    await waitForGraphNode(container, "method:upload-file");
    expect(findGraphNodeElement(container, "method:file-download")).toBeNull();
    expect(findGraphNodeElement(container, "flow-scope:delete-guard")).toBeNull();

    await user.click(screen.getByRole("tab", { name: "草稿" }));

    expect(screen.queryByText("需要先定位用户提到的 if delete 分支")).not.toBeInTheDocument();
    expect(screen.getByText("当前还没有草稿条目")).toBeInTheDocument();
  });

  it("allows canceling a confirmed draft change and returns it to pending qa changes", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("tab", { name: "问答" }));
    await user.click(screen.getByRole("tab", { name: "待确认变更" }));
    await applyBootstrapEnvelope({
      sessionId: "session-1",
      revision: 2,
      state: {
        ...structuredClone(window.linkGraphBootstrap!),
        snapshotRevision: 2,
        workbenchSectionPreferences: {
          "audit.candidate-changes": true,
        },
      },
    });
    await user.click(screen.getByRole("button", { name: "确认这条变更" }));
    expect(screen.getByRole("tab", { name: "问答" })).toHaveAttribute("aria-selected", "true");

    await user.click(screen.getByRole("tab", { name: "草稿" }));
    await user.click(screen.getByRole("button", { name: "取消确认：补充失败补偿说明" }));

    expect(window.linkGraphBridge?.unconfirmAuditCandidateChange).toHaveBeenCalledWith("change-compensate");
    expect(screen.getByRole("tab", { name: "草稿" })).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByText("补充失败补偿逻辑说明")).not.toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "问答" }));

    expect(screen.getByRole("tab", { name: "待确认变更" })).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "待确认变更" }));
    await applyBootstrapEnvelope({
      sessionId: "session-1",
      revision: 3,
      state: {
        ...structuredClone(window.linkGraphBootstrap!),
        snapshotRevision: 3,
        workbenchSectionPreferences: {
          "audit.candidate-changes": true,
        },
      },
    });
    expect(screen.getByRole("button", { name: "待确认变更：补充失败补偿说明" })).toBeInTheDocument();
  });

  it("opens the property drawer from the node context menu edit action", async () => {
    const user = userEvent.setup();
    const { container } = render(<App />);
    await waitForGraphNode(container, "method:submit-order");

    fireEvent.contextMenu(requireGraphNodeElement(container, "method:submit-order"), {
      clientX: 260,
      clientY: 180,
    });

    await user.click(screen.getByRole("menuitem", { name: "编辑节点" }));

    expect(screen.getByRole("dialog", { name: "编辑节点" })).toBeInTheDocument();
    expect(screen.getByDisplayValue("OrderController.submit")).toBeInTheDocument();
  });

  it("opens the Mermaid import dialog from the toolbar menu", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "导入 Mermaid" }));

    expect(screen.getByRole("dialog", { name: "导入 Mermaid" })).toBeInTheDocument();
  });
});

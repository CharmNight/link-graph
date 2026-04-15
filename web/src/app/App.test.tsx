import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App, resolveAuditTargetNodeIds } from "./App";
import { dispatchBootstrapForTest, resetEditorTransportForTest } from "./editorTransport";
import { materializeThreeViewDocuments } from "./testBootstrapState";
import type {
  CandidateDraftChange,
  GraphBeautificationStep,
  LinkGraphBootstrapState,
} from "./types";

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

function bootstrapStateFixture(): LinkGraphBootstrapState {
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
      source: "MOCK",
      question: "这个方法是否遗漏补偿链路？",
      answer: "建议补一条失败补偿链路。",
      promptPreview: "audit prompt preview",
      patch: null,
      findings: [],
      candidateChanges: [candidate],
      newCandidateChanges: [candidate],
      investigationLeads: [],
      newInvestigationLeads: [],
      auditSession: {
        sessionId: "audit-method-submit",
        scopeKey: "method:submit-order",
        focusTargetId: candidate.changeId,
        candidateChanges: [candidate],
        investigationLeads: [],
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
      source: "MOCK",
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
  } as LinkGraphBootstrapState);
}

async function waitForGraphNode(container: HTMLElement, nodeId: string) {
  await waitFor(() => {
    expect(container.querySelector(`[data-node-id="${nodeId}"]`)).not.toBeNull();
  });
}

describe("App", () => {
  beforeEach(() => {
    resetEditorTransportForTest();
    window.linkGraphBootstrap = structuredClone(bootstrapStateFixture());
    const bridge = {
      exportMermaid: vi.fn(),
      importMermaid: vi.fn(),
      showDiffMode: vi.fn(),
      requestSyncPreview: vi.fn(),
      requestAudit: vi.fn(),
      confirmAuditCandidateChange: vi.fn(),
      unconfirmAuditCandidateChange: vi.fn(),
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

  it("renders explanation, audit, draft, plan, and code tabs from the new workbench state", () => {
    render(<App />);

    expect(screen.getByRole("tab", { name: "讲解" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "审计" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "草稿" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "计划" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "代码" })).toBeInTheDocument();
    expect(screen.getByRole("tabpanel", { name: "讲解" })).toBeInTheDocument();
    expect(screen.queryByText("结果面板")).not.toBeInTheDocument();
  });

  it("defaults to the flowchart stage when bootstrap state does not provide a display mode", () => {
    const state = structuredClone(bootstrapStateFixture());
    delete (state as Partial<LinkGraphBootstrapState>).analysisDisplayMode;
    window.linkGraphBootstrap = state;

    render(<App />);

    expect(screen.getByRole("button", { name: "流程图" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("flowchart-view")).toBeInTheDocument();
    expect(screen.getByLabelText("流程图摘要")).toBeInTheDocument();
  });

  it("switches to the generation plan tab when requesting a plan from the toolbar", async () => {
    const user = userEvent.setup();

    render(<App />);

    await user.click(screen.getByRole("button", { name: "更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "生成计划" }));

    expect(screen.getByRole("tab", { name: "计划" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("正在生成计划，请稍候。")).toBeInTheDocument();
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
    await user.click(screen.getByRole("menuitem", { name: "生成计划" }));

    expect(screen.getByRole("tab", { name: "计划" })).toHaveAttribute("aria-selected", "true");
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
    await user.click(screen.getByRole("menuitem", { name: "生成草稿" }));

    expect(screen.getByRole("tab", { name: "代码" })).toHaveAttribute("aria-selected", "true");
    expect(window.linkGraphBridge?.requestCodeDrafts).toHaveBeenCalledTimes(1);
  });

  it("renders generation plan and code draft panels from bootstrap state", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      generationPlan: {
        source: "MOCK",
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
      generatedCodeDraftSource: "REMOTE",
      generatedCodeDraftWarnings: ["请复核异常类型。"],
      generatedCodeDraftPromptPreview: null,
    } satisfies LinkGraphBootstrapState);

    render(<App />);

    await user.click(screen.getByRole("tab", { name: "计划" }));
    expect(screen.getByText("修改 CommonController.fileDownload 并保留现有正常路径逻辑。")).toBeInTheDocument();
    expect(screen.getByText("任务：修改 fileDownload 的路径判定")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "代码" }));
    expect(screen.getByText("CommonController.java")).toBeInTheDocument();
    expect(screen.getByText("请复核异常类型。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "写入 CommonController.java" })).toBeInTheDocument();
  });

  it("prefers real async request status in the toolbar over stale operation feedback", () => {
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapStateFixture(),
      operationFeedback: {
        level: "INFO",
        message: "已发起远程 LLM 审计请求，当前采用流式输出。",
      },
      auditRequestState: {
        phase: "SUCCEEDED",
        scene: "审计",
        statusMessage: "审计完成，已生成待确认变更。",
        detailMessage: "远程 LLM 已完成流式输出，并已落地最终结构化结果。",
        finishedAtEpochMillis: 300,
      },
      lastMessageType: "auditResult",
    });

    render(<App />);

    expect(screen.getByText("审计：审计完成，已生成待确认变更。")).toBeInTheDocument();
    expect(screen.queryByText("已发起远程 LLM 审计请求，当前采用流式输出。")).not.toBeInTheDocument();
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

    await user.click(screen.getByRole("tab", { name: "审计" }));

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

    await user.click(screen.getByRole("tab", { name: "审计" }));
    expect(screen.getByRole("tab", { name: "请求状态" })).toHaveAttribute("aria-selected", "true");

    await act(async () => {
      dispatchBootstrapForTest({
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
    });

    await waitFor(() => {
      expect(screen.getByRole("tab", { name: "审计会话" })).toHaveAttribute("aria-selected", "true");
    });
  });

  it("turns an investigation lead into a concrete continue-investigation path", async () => {
    const user = userEvent.setup();
    const candidate = candidateChangeFixture();
    const lead = {
      leadId: "lead-compensate",
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
        investigationLeads: [lead],
        newInvestigationLeads: [lead],
        auditSession: {
          ...bootstrapStateFixture().auditResult!.auditSession!,
          investigationLeads: [lead],
        },
      },
    });

    render(<App />);

    await user.click(screen.getByRole("tab", { name: "审计" }));
    await user.click(screen.getByRole("tab", { name: "风险线索" }));
    await act(async () => {
      dispatchBootstrapForTest({
        sessionId: "session-1",
        revision: 2,
        state: {
          ...structuredClone(window.linkGraphBootstrap!),
          snapshotRevision: 2,
          workbenchSectionPreferences: {
            "audit.investigation-leads": true,
          },
        },
      });
    });
    await user.click(screen.getByRole("button", { name: "继续取证" }));
    await act(async () => {
      dispatchBootstrapForTest({
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
            scene: "审计",
            statusMessage: "已提交继续取证请求",
          },
          auditResult: null,
        },
      });
    });

    expect(window.linkGraphBridge?.requestAudit).toHaveBeenCalledWith(
      lead.recommendedQuestion,
      ["method:submit-order"],
      "lead-compensate",
    );
    expect(
      (
        window.linkGraphBridge as typeof window.linkGraphBridge & {
          updateWorkbenchSectionPreference: ReturnType<typeof vi.fn>;
        }
      )?.updateWorkbenchSectionPreference,
    ).toHaveBeenCalledWith("audit.composer", true);
    expect(screen.getByRole("tab", { name: "请求状态" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText(lead.recommendedQuestion)).toBeInTheDocument();
  });

  it("submits continue-investigation from the selected risk thread instead of losing the original lead context", async () => {
    const user = userEvent.setup();
    const lead = {
      leadId: "lead-compensate",
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
        investigationLeads: [lead],
        newInvestigationLeads: [lead],
        auditSession: {
          ...bootstrapStateFixture().auditResult!.auditSession!,
          investigationLeads: [lead],
        },
      },
    });

    render(<App />);

    await user.click(screen.getByRole("tab", { name: "审计" }));
    await user.click(screen.getByRole("tab", { name: "风险线索" }));
    await act(async () => {
      dispatchBootstrapForTest({
        sessionId: "session-1",
        revision: 2,
        state: {
          ...structuredClone(window.linkGraphBootstrap!),
          snapshotRevision: 2,
          workbenchSectionPreferences: {
            "audit.investigation-leads": true,
          },
        },
      });
    });
    await user.click(screen.getByRole("button", { name: "继续取证" }));

    expect(window.linkGraphBridge?.requestAudit).toHaveBeenCalledWith(
      lead.recommendedQuestion,
      ["method:submit-order"],
      "lead-compensate",
    );
    expect(window.linkGraphBridge?.requestAudit).toHaveBeenCalledTimes(1);
  });

  it("replays a DOM bootstrap event that fires before the app renders", async () => {
    const state = structuredClone(bootstrapStateFixture());
    window.linkGraphBootstrap = undefined;
    window.dispatchEvent(
      new CustomEvent("link-graph-bootstrap", {
        detail: {
          ...state,
          workingGraph: structuredClone(state.workingGraph),
          snapshotRevision: 7,
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
        workingGraph: structuredClone(state.workingGraph),
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

    await act(async () => {
      dispatchBootstrapForTest({
        sessionId: "session-1",
        revision: 2,
        state: nextState,
      });
    });

    expect((await screen.findAllByText("Step 1.1 继续分析订单校验失败")).length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "返回上一讲解：当前链路讲解" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "返回上一讲解：当前链路讲解" }));

    expect(screen.getAllByText("Step 1 提交订单请求").length).toBeGreaterThan(0);
    expect(screen.getByText("当前方法负责接收入参并把请求交给下游服务。")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "返回上一讲解：当前链路讲解" })).not.toBeInTheDocument();
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
      expect(container.querySelector('[data-node-id="class:order-draft-dto"]')).toHaveClass("is-selected");
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
      expect(container.querySelector('[data-node-id="class:order-draft-dto"]')).toHaveClass("is-selected");
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
    await waitForGraphNode(container, "class:order-draft-dto");

    expect(container.querySelector('.react-flow__node.is-explanation-focus [data-node-id="method:submit-order"]')).not.toBeNull();

    fireEvent.mouseEnter(stepButton);
    expect(container.querySelector('.react-flow__node.is-explanation-focus [data-node-id="class:order-draft-dto"]')).not.toBeNull();

    fireEvent.mouseLeave(stepButton);
    expect(container.querySelector('.react-flow__node.is-explanation-focus [data-node-id="method:submit-order"]')).not.toBeNull();
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
      expect(container.querySelector('[data-node-id="method:submit-order"]')).toHaveClass("is-selected");
    });
  });

  it("opens the fixed audit workbench from the canvas context menu and pre-fills the scope question", async () => {
    const user = userEvent.setup();
    const { container } = render(<App />);
    await waitForGraphNode(container, "method:submit-order");

    fireEvent.contextMenu(screen.getByTestId("graph-canvas-shell"), { clientX: 220, clientY: 180 });
    await user.click(screen.getByRole("menuitem", { name: "审计当前范围" }));
    await user.click(screen.getByRole("tab", { name: "继续提问" }));

    expect(screen.getByRole("tab", { name: "审计" })).toHaveAttribute("aria-selected", "true");
    expect(window.linkGraphBridge?.requestAudit).not.toHaveBeenCalled();
    expect(screen.getByRole("textbox", { name: "审计输入框" })).toHaveValue(
      "请审计当前整张链路图，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。",
    );
  });

  it("allows editing the audit question draft after opening the audit workbench", async () => {
    const user = userEvent.setup();
    const { container } = render(<App />);
    await waitForGraphNode(container, "method:submit-order");

    fireEvent.contextMenu(screen.getByTestId("graph-canvas-shell"), { clientX: 220, clientY: 180 });
    await user.click(screen.getByRole("menuitem", { name: "审计当前范围" }));
    await user.click(screen.getByRole("tab", { name: "继续提问" }));

    const input = screen.getByRole("textbox", { name: "审计输入框" });
    await user.clear(input);
    await user.type(input, "介绍这个方法");

    expect(input).toHaveValue("介绍这个方法");
  });

  it("submits the audit question from the audit tab and shows an immediate running banner", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("tab", { name: "审计" }));
    await user.click(screen.getByRole("tab", { name: "继续提问" }));
    const input = screen.getByRole("textbox", { name: "审计输入框" });

    await user.clear(input);
    await user.type(input, "介绍这里有什么安全问题");
    await user.click(screen.getByRole("button", { name: "发送" }));

    expect(window.linkGraphBridge?.requestAudit).toHaveBeenCalledWith("介绍这里有什么安全问题", [], null);
    expect(screen.getByRole("button", { name: "收起当前页面" })).toBeInTheDocument();
    expect(screen.getByText("已提交审计请求")).toBeInTheDocument();
    expect(screen.getByText("等待后端确认执行方式与执行阶段。")).toBeInTheDocument();
  });

  it("shows a visible failure dialog when the audit bridge is unavailable instead of failing silently", async () => {
    const user = userEvent.setup();
    window.linkGraphBridge = undefined;
    render(<App />);

    await user.click(screen.getByRole("tab", { name: "审计" }));
    await user.click(screen.getByRole("tab", { name: "继续提问" }));
    const input = screen.getByRole("textbox", { name: "审计输入框" });

    await user.clear(input);
    await user.type(input, "介绍这里有什么安全问题");
    await user.click(screen.getByRole("button", { name: "发送" }));

    const dialog = screen.getByRole("dialog", { name: "请求状态通知" });
    expect(dialog).toBeInTheDocument();
    expect(within(dialog).getByRole("heading", { name: "审计请求未发出" })).toBeInTheDocument();
    expect(within(dialog).getByText("IDE bridge 尚未就绪，本次请求没有发出。")).toBeInTheDocument();
  });

  it("preserves the locally selected node while audit request state updates stream back", async () => {
    const state = bootstrapStateFixture();
    state.selectedNodeId = "method:submit-order";
    window.linkGraphBootstrap = state;
    const { container } = render(<App />);
    await waitForGraphNode(container, "class:order-draft-dto");

    fireEvent.click(container.querySelector('[data-node-id="class:order-draft-dto"]') as HTMLElement);
    await waitFor(() => {
      expect(container.querySelector('[data-node-id="class:order-draft-dto"]')).toHaveClass("is-selected");
    });

    await act(async () => {
      dispatchBootstrapForTest({
        sessionId: "session-1",
        revision: 2,
        state: {
          ...structuredClone(state),
          snapshotRevision: 2,
          selectedNodeId: "method:submit-order",
          lastMessageType: "requestAudit",
          auditRequestState: {
            phase: "RUNNING",
            requestId: 2,
            scene: "审计",
            streaming: true,
            statusMessage: "正在审计",
          },
        },
      });
    });

    await waitFor(() => {
      expect(container.querySelector('[data-node-id="class:order-draft-dto"]')).toHaveClass("is-selected");
    });
  });

  it("routes explanation requests through the node context menu action", async () => {
    const user = userEvent.setup();
    const { container } = render(<App />);
    await waitForGraphNode(container, "method:submit-order");

    fireEvent.contextMenu(container.querySelector('[data-node-id="method:submit-order"]') as HTMLElement, {
      clientX: 260,
      clientY: 180,
    });

    await user.click(screen.getByRole("menuitem", { name: "讲解当前链路" }));

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

    await act(async () => {
      dispatchBootstrapForTest({
        sessionId: "session-1",
        revision: 2,
        state: nextState,
      });
    });

    expect(await screen.findByRole("button", { name: "返回上一讲解：当前链路讲解" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "方法调用级" }));

    expect(screen.queryByRole("button", { name: "返回上一讲解：当前链路讲解" })).not.toBeInTheDocument();
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

    await act(async () => {
      dispatchBootstrapForTest({
        sessionId: "session-1",
        revision: 2,
        state: followUpStateOne,
      });
    });

    await user.click(screen.getByRole("button", { name: "围绕这一步继续讲解" }));

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

    await act(async () => {
      dispatchBootstrapForTest({
        sessionId: "session-1",
        revision: 3,
        state: followUpStateTwo,
      });
    });

    expect(screen.getByRole("button", { name: "讲解历史：当前链路讲解" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "讲解历史：围绕 Step 1 提交订单请求 继续讲解" })).toBeInTheDocument();
    expect(screen.getAllByText("Step 1.2 继续分析失败补偿").length).toBeGreaterThan(0);

    await user.click(screen.getByRole("button", { name: "讲解历史：当前链路讲解" }));

    expect(screen.getAllByText("Step 1 提交订单请求").length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: "讲解历史：围绕 Step 1 提交订单请求 继续讲解" })).not.toBeInTheDocument();
  });

  it("routes candidate confirmation through the IDE bridge and switches to the draft tab", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("tab", { name: "审计" }));
    await user.click(screen.getByRole("tab", { name: "待确认变更" }));
    await act(async () => {
      dispatchBootstrapForTest({
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
    });
    await user.click(screen.getByRole("button", { name: "确认这条变更" }));

    expect(window.linkGraphBridge?.confirmAuditCandidateChange).toHaveBeenCalledWith("change-compensate");
    expect(screen.getByRole("tab", { name: "草稿" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getAllByText("补充失败补偿说明")).toHaveLength(2);
    expect(screen.getByText("当前链路缺少失败补偿语义。")).toBeInTheDocument();
    expect(screen.getByText("修改后")).toBeInTheDocument();
    expect(screen.queryByText("修改前")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "一键对比前后" }));

    expect(screen.getByText("修改前")).toBeInTheDocument();
    expect(screen.getByText("当前没有失败补偿说明")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "审计" }));

    expect(screen.queryByRole("tab", { name: "待确认变更" })).not.toBeInTheDocument();
  });

  it("allows canceling a confirmed draft change and returns it to pending audit changes", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("tab", { name: "审计" }));
    await user.click(screen.getByRole("tab", { name: "待确认变更" }));
    await act(async () => {
      dispatchBootstrapForTest({
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
    });
    await user.click(screen.getByRole("button", { name: "确认这条变更" }));
    await user.click(screen.getByRole("button", { name: "取消确认：补充失败补偿说明" }));

    expect(window.linkGraphBridge?.unconfirmAuditCandidateChange).toHaveBeenCalledWith("change-compensate");
    expect(screen.getByRole("tab", { name: "草稿" })).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByText("补充失败补偿逻辑说明")).not.toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "审计" }));

    expect(screen.getByRole("tab", { name: "待确认变更" })).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "待确认变更" }));
    await act(async () => {
      dispatchBootstrapForTest({
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
    });
    expect(screen.getByRole("button", { name: "待确认变更：补充失败补偿说明" })).toBeInTheDocument();
  });

  it("opens the property drawer from the node context menu edit action", async () => {
    const user = userEvent.setup();
    const { container } = render(<App />);
    await waitForGraphNode(container, "method:submit-order");

    fireEvent.contextMenu(container.querySelector('[data-node-id="method:submit-order"]') as HTMLElement, {
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

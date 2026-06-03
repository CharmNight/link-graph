import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { EvidenceStagePanel } from "../../../app/components/EvidenceStagePanel";
import type { EvidencePanelState } from "../../../app/components/hybridDerivations";
import type { LinkGraphNode } from "../../../app/types";

const selectedNode: LinkGraphNode = {
  id: "method:submit",
  type: "METHOD",
  title: "OrderController.submit",
  location: "src/main/java/OrderController.java:8",
  signature: "com.example.OrderController.submit():void",
  inputs: ["SubmitRequest"],
  outputs: ["SubmitResult"],
  certainty: "PROVEN",
  bindingStatus: "BOUND",
  sourceTag: "FACT",
};

const state: EvidencePanelState = {
  selectedNode,
  selectedNodeEvidence: [
    {
      id: "finding-source",
      label: "提交订单入口直接来自源码。",
      level: "DIRECT_SOURCE",
      references: [
        {
          nodeId: "method:submit",
          filePath: "src/main/java/OrderController.java",
          startLine: 8,
          endLine: 16,
        },
      ],
    },
    {
      id: "finding-callsite",
      label: "只看到调用点，还没看到失败补偿。",
      level: "CALLSITE_ONLY",
      references: [{ nodeId: "method:submit" }],
    },
  ],
  relationEvidence: [
    {
      id: "edge-reflect",
      label: "反射：OrderController.submit -> PaymentProvider",
      kind: "REFLECTS_TO",
      confidence: "PROVEN",
      source: "PSI",
      resolverId: "jvm.reflection",
      count: "1",
      references: [
        {
          nodeId: "method:submit",
          filePath: "src/main/java/OrderController.java",
          startLine: 10,
          endLine: 10,
        },
      ],
    },
  ],
  evidenceGaps: [
    {
      id: "gap-compensation",
      title: "失败补偿可能缺失",
      detail: "没有观察到订单提交失败后的补偿链路。",
      targetNodeIds: ["method:submit"],
      severity: "danger",
    },
  ],
  sourceSnippets: [
    {
      nodeId: "method:submit",
      filePath: "src/main/java/OrderController.java",
      startLine: 8,
      endLine: 16,
      snippet: "orderService.submit(request);",
    },
  ],
  evidenceTrace: [
    {
      nodeId: "method:submit",
      resolvedNodeId: "method:submit",
      filePath: "src/main/java/OrderController.java",
      reason: "作为当前问答范围加入 prompt。",
      startLine: 8,
      endLine: 16,
      includedInPrompt: true,
    },
  ],
  openRiskThreads: [
    {
      threadId: "thread-compensation",
      status: "OPEN",
      title: "失败补偿可能缺失",
      targetStepIds: [],
      targetNodeIds: ["method:submit"],
      summary: "当前只看到提交入口。",
      evidenceGap: "缺失败分支。",
      recommendedQuestion: "继续取证失败分支。",
      evidence: [],
    },
  ],
  pendingCandidateChanges: [
    {
      changeId: "change-compensation",
      status: "PENDING_CONFIRMATION",
      title: "补充失败补偿说明",
      targetStepIds: [],
      targetNodeIds: ["method:submit"],
      reason: "当前链路缺少失败补偿说明。",
      impactSummary: "影响失败路径理解。",
      evidence: [],
    },
  ],
};

describe("EvidenceStagePanel", () => {
  it("shows an empty state when no node is selected", () => {
    render(
      <EvidenceStagePanel
        state={{ ...state, selectedNode: null, selectedNodeEvidence: [], relationEvidence: [], evidenceGaps: [], sourceSnippets: [], evidenceTrace: [] }}
        isLoading={false}
        errorMessage={null}
        onOpenQa={vi.fn()}
        onSelectThread={vi.fn()}
        onSelectCandidateChange={vi.fn()}
        onRequestSourceNavigation={vi.fn()}
      />,
    );

    expect(screen.getByText("选择图谱节点后查看它的证据、风险和源码片段。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "去问答" })).toBeEnabled();
  });

  it("renders selected node evidence, snippets, trace, risks and pending changes", () => {
    render(
      <EvidenceStagePanel
        state={state}
        isLoading={false}
        errorMessage={null}
        onOpenQa={vi.fn()}
        onSelectThread={vi.fn()}
        onSelectCandidateChange={vi.fn()}
        onRequestSourceNavigation={vi.fn()}
      />,
    );

    expect(screen.getByRole("heading", { name: "OrderController.submit" })).toBeInTheDocument();
    expect(screen.getByText("已确认")).toBeInTheDocument();
    expect(screen.getByText("已绑定")).toBeInTheDocument();
    expect(screen.getByText("直接源码 1")).toBeInTheDocument();
    expect(screen.getByText("仅调用点 1")).toBeInTheDocument();
    expect(screen.getByText("提交订单入口直接来自源码。")).toBeInTheDocument();
    expect(screen.getByText("反射：OrderController.submit -> PaymentProvider")).toBeInTheDocument();
    expect(screen.getByText("静态确认")).toBeInTheDocument();
    expect(screen.getByText("jvm.reflection")).toBeInTheDocument();
    expect(screen.getAllByText("失败补偿可能缺失").length).toBeGreaterThan(0);
    expect(screen.getByText("orderService.submit(request);")).toBeInTheDocument();
    expect(screen.getByText("作为当前问答范围加入 prompt。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "选择风险线程：失败补偿可能缺失" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "选择候选变更：补充失败补偿说明" })).toBeInTheDocument();
  });

  it("delegates qa, risk, candidate and source navigation actions", async () => {
    const user = userEvent.setup();
    const onOpenQa = vi.fn();
    const onSelectThread = vi.fn();
    const onSelectCandidateChange = vi.fn();
    const onRequestSourceNavigation = vi.fn();

    render(
      <EvidenceStagePanel
        state={state}
        isLoading={false}
        errorMessage={null}
        onOpenQa={onOpenQa}
        onSelectThread={onSelectThread}
        onSelectCandidateChange={onSelectCandidateChange}
        onRequestSourceNavigation={onRequestSourceNavigation}
      />,
    );

    await user.click(screen.getByRole("button", { name: "去问答" }));
    await user.click(screen.getByRole("button", { name: "选择风险线程：失败补偿可能缺失" }));
    await user.click(screen.getByRole("button", { name: "选择候选变更：补充失败补偿说明" }));
    await user.click(screen.getByRole("button", { name: "跳源码：OrderController.submit" }));

    expect(onOpenQa).toHaveBeenCalledTimes(1);
    expect(onSelectThread).toHaveBeenCalledWith("thread-compensation");
    expect(onSelectCandidateChange).toHaveBeenCalledWith("change-compensation");
    expect(onRequestSourceNavigation).toHaveBeenCalledWith("method:submit");
  });

  it("shows loading and failure states from real request state", () => {
    const { rerender } = render(
      <EvidenceStagePanel
        state={state}
        isLoading
        errorMessage={null}
        onOpenQa={vi.fn()}
        onSelectThread={vi.fn()}
        onSelectCandidateChange={vi.fn()}
        onRequestSourceNavigation={vi.fn()}
      />,
    );

    expect(screen.getByText("正在整理证据，请稍候。")).toBeInTheDocument();

    rerender(
      <EvidenceStagePanel
        state={state}
        isLoading={false}
        errorMessage="远程问答失败"
        onOpenQa={vi.fn()}
        onSelectThread={vi.fn()}
        onSelectCandidateChange={vi.fn()}
        onRequestSourceNavigation={vi.fn()}
      />,
    );

    expect(screen.getByText("远程问答失败")).toBeInTheDocument();
  });
});

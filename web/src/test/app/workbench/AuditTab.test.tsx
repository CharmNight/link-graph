import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AuditTab } from "../../../app/workbench/AuditTab";
import type { AuditWorkbenchState } from "../../../app/types";
import themeCss from "../../../app/theme.css?raw";

function auditStateFixture(): AuditWorkbenchState {
  return {
    requestState: { phase: "SUCCEEDED" },
    selectedChangeId: "change-condition",
    selectedThreadId: null,
    questionDraft: "",
    scopeLabel: "当前节点：上传方法",
    result: {
      source: "MOCK",
      question: "这里是不是有问题？",
      answer: "建议修改条件判断。",
      promptPreview: "prompt",
      patch: null,
      findings: [],
      warnings: [],
      newCandidateChanges: [],
      candidateChanges: [
        {
          changeId: "change-condition",
          status: "PENDING_CONFIRMATION",
          title: "建议 1 修改条件判断",
          targetStepIds: [],
          targetNodeIds: ["flow-action:condition"],
          beforeState: "if (a > 10)",
          afterState: "if (a < 100)",
          reason: "业务条件写反了。",
          impactSummary: "影响主流程分支。",
          claimType: "CODE_FACT",
          evidence: [
            {
              id: "finding-condition",
              claim: "当前源码里直接能看到这个条件判断。",
              evidenceLevel: "DIRECT_SOURCE",
              references: [{ nodeId: "flow-action:condition" }],
            },
          ],
        },
      ],
      auditSession: {
        sessionId: "audit-method-submit",
        scopeKey: "method:submit",
        focusTargetId: "change-condition",
        candidateChanges: [],
        messages: [
          {
            messageId: "m-1",
            role: "USER",
            content: "这里是不是有问题？",
          },
          {
            messageId: "m-2",
            role: "ASSISTANT",
            content: "建议修改条件判断。",
          },
        ],
      },
      investigationThreads: [],
    },
  };
}

describe("AuditTab", () => {

  it("keeps the tab root on the CSS grid contract instead of Uno display or overflow utilities", () => {
    const { container } = render(
      <AuditTab
        state={auditStateFixture()}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
      />,
    );

    const tab = container.querySelector(".workbench-tab");
    expect(tab).not.toBeNull();
    expect(tab).not.toHaveClass("block");
    expect(tab).not.toHaveClass("overflow-auto");
  });
  it("shows the scope label and keeps the composer page active by default", () => {
    render(
      <AuditTab
        state={auditStateFixture()}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
      />,
    );

    expect(screen.getByText("当前节点：上传方法")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "提问" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("heading", { level: 3, name: "提问" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "问答输入框" })).toBeInTheDocument();
    expect(screen.queryByText("这里是不是有问题？")).not.toBeInTheDocument();
  });

  it("renders audit secondary tabs in the requested order", () => {
    render(
      <AuditTab
        state={{
          ...auditStateFixture(),
          result: {
            ...auditStateFixture().result!,
            investigationThreads: [
              {
                threadId: "thread-path-risk",
                status: "OPEN",
                title: "补充路径风险说明",
                targetStepIds: [],
                targetNodeIds: ["method:upload-file"],
                summary: "当前只有调用点证据。",
                evidenceGap: "还没有看到上传工具内部路径校验实现。",
                recommendedQuestion: "请继续取证：展开 FileUploadUtils.upload，确认是否存在路径规范化或目录校验。",
                claimType: "RISK_HINT",
                evidence: [],
                latestTurnOutcomeId: null,
              },
            ],
          },
        }}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
        sectionPreferences={{ "audit.thread": true }}
      />,
    );

    expect(screen.getAllByRole("tab").map((tab) => tab.getAttribute("aria-label"))).toEqual([
      "提问",
      "请求",
      "问答会话",
      "待确认变更",
      "风险线程",
    ]);
  });

  it("does not render the risk-thread page from legacy investigation leads alone", () => {
    render(
      <AuditTab
        state={{
          ...auditStateFixture(),
          result: {
            ...auditStateFixture().result!,
            investigationThreads: [],
          },
        }}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
      />,
    );

    expect(screen.queryByRole("tab", { name: "风险线程" })).not.toBeInTheDocument();
  });

  it("shows an explicit empty state on the conversation page and hides the candidate tab when there are no pending changes", () => {
    render(
      <AuditTab
        state={{
          ...auditStateFixture(),
          result: {
            ...auditStateFixture().result!,
            auditSession: {
              ...auditStateFixture().result!.auditSession!,
              messages: [],
            },
            candidateChanges: [],
          },
        }}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
        sectionPreferences={{ "audit.thread": true }}
      />,
    );

    expect(screen.getByText("当前还没有问答消息。")).toBeInTheDocument();
    expect(screen.getByText("可先输入你的问题，或者围绕当前范围继续追问。")).toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "待确认变更" })).not.toBeInTheDocument();
  });

  it("hides the pending change page when every candidate is already confirmed", () => {
    render(
      <AuditTab
        state={{
          ...auditStateFixture(),
          result: {
            ...auditStateFixture().result!,
            candidateChanges: [
              {
                ...auditStateFixture().result!.candidateChanges[0],
                status: "CONFIRMED",
              },
            ],
          },
        }}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
      />,
    );

    expect(screen.queryByRole("tab", { name: "待确认变更" })).not.toBeInTheDocument();
  });

  it("switches full audit pages by clicking the secondary tabs", async () => {
    const user = userEvent.setup();
    render(
      <AuditTab
        state={auditStateFixture()}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("tab", { name: "提问" }));

    expect(screen.getByRole("tab", { name: "提问" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("heading", { level: 3, name: "提问" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "问答输入框" })).toBeInTheDocument();
    expect(screen.queryByText("这里是不是有问题？")).not.toBeInTheDocument();
  });

  it("auto-opens the request status page while a remote audit request is running", () => {
    render(
      <AuditTab
        state={{
          ...auditStateFixture(),
          requestState: {
            phase: "RUNNING",
            statusMessage: "正在等待远程 LLM 问答响应",
            detailMessage: "当前采用流式输出，界面会持续追加预览。",
            errorMessage: null,
            streaming: true,
            fallbackUsed: false,
            requestId: 12,
            scene: "问答",
            executionMode: "REMOTE_READY",
            providerLabel: "通用 OpenAI Responses",
            model: "gpt-5.4",
            endpointSummary: "example.com/v1/responses",
            promptPreviewAvailable: true,
            previewText: "正在分析 uploadFiles 方法中的路径拼接...",
          },
          result: null,
        }}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
      />,
    );

    expect(screen.getByRole("tab", { name: "请求" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("正在等待远程 LLM 问答响应")).toBeInTheDocument();
    expect(screen.getByText("当前采用流式输出，界面会持续追加预览。")).toBeInTheDocument();
  });

  it("shows the submitted question and returned evidence snapshot on the request status page", () => {
    render(
      <AuditTab
        state={{
          ...auditStateFixture(),
          questionDraft: "请继续取证：定位默认兜底分支。",
          requestState: {
            phase: "RUNNING",
            statusMessage: "已提交继续取证请求",
          },
          result: {
            ...auditStateFixture().result!,
            sourceContext: [
              {
                nodeId: "method:submit-order",
                filePath: "src/main/java/com/example/OrderController.java",
                startLine: 18,
                endLine: 30,
                snippet: "if (channel == null) { return defaultChannel(); }",
              },
            ],
            evidenceTrace: [
              {
                nodeId: "method:submit-order",
                filePath: "src/main/java/com/example/OrderController.java",
                reason: "从当前风险线索目标节点取证",
                startLine: 18,
                endLine: 30,
                includedInPrompt: true,
              },
            ],
          },
        }}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
      />,
    );

    expect(screen.getByRole("tab", { name: "请求" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("请继续取证：定位默认兜底分支。")).toBeInTheDocument();
    expect(screen.getAllByText("src/main/java/com/example/OrderController.java:18-30")).toHaveLength(2);
    expect(screen.getByText("从当前风险线索目标节点取证")).toBeInTheDocument();
  });

  it("can collapse the current page and reopen a different page from the tab list", async () => {
    const user = userEvent.setup();
    const { container } = render(
      <AuditTab
        state={auditStateFixture()}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "收起当前页面" }));

    expect(container.querySelector(".audit-page-panel")).toBeNull();
    expect(screen.getByText("当前页面已收起，点击上方标签继续查看。")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "提问" }));

    expect(screen.queryByText("当前页面已收起，点击上方标签继续查看。")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 3, name: "提问" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "问答输入框" })).toBeInTheDocument();
  });

  it("shows pending changes inside a dedicated full-page candidate view", async () => {
    const user = userEvent.setup();
    const onSelectChange = vi.fn();

    const { container } = render(
      <AuditTab
        state={{
          ...auditStateFixture(),
          result: {
            ...auditStateFixture().result!,
            candidateChanges: [
              auditStateFixture().result!.candidateChanges[0],
              {
                changeId: "change-exception",
                status: "PENDING_CONFIRMATION",
                title: "建议 2 明确异常分支",
                targetStepIds: [],
                targetNodeIds: ["flow-action:exception"],
                beforeState: "catch (Exception e) { return null; }",
                afterState: "catch (Exception e) { throw new BusinessException(...); }",
                reason: "当前异常吞掉后续语义不清晰。",
                impactSummary: "影响失败链路的可理解性。",
                claimType: "CODE_FACT",
                evidence: [
                  {
                    id: "finding-exception",
                    claim: "当前源码里直接能看到异常吞掉返回 null。",
                    evidenceLevel: "DIRECT_SOURCE",
                    references: [{ nodeId: "flow-action:exception" }],
                  },
                ],
              },
            ],
          },
        }}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={onSelectChange}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("tab", { name: "待确认变更" }));

    expect(screen.getByRole("heading", { level: 3, name: "待确认变更" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "待确认变更：建议 1 修改条件判断" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "待确认变更：建议 2 明确异常分支" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "确认这条变更" })).toBeInTheDocument();
    expect(container.querySelector(".workbench-candidate-content")).not.toBeNull();

    await user.click(screen.getByRole("button", { name: "待确认变更：建议 2 明确异常分支" }));

    expect(onSelectChange).toHaveBeenCalledWith("change-exception");
  });

  it("renders weak-evidence outputs inside investigation threads and exposes continue-investigation action", async () => {
    const user = userEvent.setup();
    const onInvestigateThread = vi.fn();

    render(
      <AuditTab
        state={{
          ...auditStateFixture(),
          selectedChangeId: null,
          selectedThreadId: "thread-path-risk",
          result: {
            ...auditStateFixture().result!,
            candidateChanges: [],
            latestTurnOutcome: {
              outcomeId: "turn-1",
              threadId: "thread-path-risk",
              status: "OPEN_WITH_PROGRESS",
              summary: "补充路径风险说明",
              detail: "本轮补充了上传工具调用点，但还没有拿到内部路径校验实现。",
              candidateChangeId: null,
              blockedReason: null,
              evidenceDelta: {
                addedNodeIds: ["method:upload-file"],
                addedFilePaths: ["CommonController.java"],
                previousStrongestEvidenceLevel: "NOT_OBSERVED",
                currentStrongestEvidenceLevel: "CALLSITE_ONLY",
                hitRecommendedQuestion: true,
              },
              observedNodeIds: ["method:upload-file"],
              observedFilePaths: ["CommonController.java"],
              strongestEvidenceLevel: "CALLSITE_ONLY",
            },
            recentTurnOutcomes: [],
            investigationThreads: [
              {
                threadId: "thread-path-risk",
                status: "OPEN",
                title: "补充路径风险说明",
                targetStepIds: [],
                targetNodeIds: ["method:upload-file"],
                summary: "当前只有调用点证据。",
                evidenceGap: "还没有看到上传工具内部路径校验实现。",
                recommendedQuestion: "请继续取证：展开 FileUploadUtils.upload，确认是否存在路径规范化或目录校验。",
                claimType: "RISK_HINT",
                evidence: [
                  {
                    id: "finding-callsite-only",
                    claim: "这里只看到 MultipartFile 被传给上传工具。",
                    evidenceLevel: "CALLSITE_ONLY",
                    references: [{ nodeId: "method:upload-file" }],
                  },
                ],
                latestTurnOutcomeId: null,
              },
            ],
          },
        }}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={onInvestigateThread}
      />,
    );

    await user.click(screen.getByRole("tab", { name: "风险线程" }));

    expect(screen.getByText("线程会持续保留，本轮是否推进以“本轮结果”为准。")).toBeInTheDocument();
    expect(screen.getByText("本轮补充了上传工具调用点，但还没有拿到内部路径校验实现。")).toBeInTheDocument();
    expect(screen.getByText("有推进")).toBeInTheDocument();
    expect(screen.getByText("仅调用点")).toBeInTheDocument();
    expect(screen.getByText("这里只看到 MultipartFile 被传给上传工具。")).toBeInTheDocument();
    expect(screen.getByText("还没有看到上传工具内部路径校验实现。")).toBeInTheDocument();
    const investigateButton = screen.getByRole("button", { name: "继续取证" });

    await user.click(investigateButton);

    expect(onInvestigateThread).toHaveBeenCalledWith("thread-path-risk");
  });

  it("shows the per-turn investigation outcome alongside the assistant message", async () => {
    const user = userEvent.setup();

    render(
      <AuditTab
        state={{
          ...auditStateFixture(),
          result: {
            ...auditStateFixture().result!,
            auditSession: {
              ...auditStateFixture().result!.auditSession!,
              messages: [
                {
                  messageId: "m-1",
                  role: "USER",
                  content: "请继续取证上传链路。",
                },
                {
                  messageId: "m-2",
                  role: "ASSISTANT",
                  content: "这轮还没有拿到上传工具内部实现。",
                  focusTargetId: "thread-path-risk",
                  turnOutcomeId: "turn-2",
                },
              ],
              investigationThreads: [
                {
                  threadId: "thread-path-risk",
                  status: "OPEN",
                  title: "补充路径风险说明",
                  targetStepIds: [],
                  targetNodeIds: ["method:upload-file"],
                  summary: "当前只有调用点证据。",
                  evidenceGap: "还没有看到上传工具内部路径校验实现。",
                  recommendedQuestion: "请继续取证：展开 FileUploadUtils.upload，确认是否存在路径规范化或目录校验。",
                  claimType: "RISK_HINT",
                  evidence: [],
                  latestTurnOutcomeId: "turn-2",
                },
              ],
              turnOutcomes: [
                {
                  outcomeId: "turn-2",
                  threadId: "thread-path-risk",
                  status: "OPEN_NO_PROGRESS",
                  summary: "补充路径风险说明",
                  detail: "本轮仍停留在原有风险线程上，没有获得可升级的新证据。",
                  candidateChangeId: null,
                  blockedReason: null,
                  evidenceDelta: {
                    addedNodeIds: [],
                    addedFilePaths: [],
                    previousStrongestEvidenceLevel: "CALLSITE_ONLY",
                    currentStrongestEvidenceLevel: "CALLSITE_ONLY",
                    hitRecommendedQuestion: false,
                  },
                  observedNodeIds: ["method:upload-file"],
                  observedFilePaths: ["CommonController.java"],
                  strongestEvidenceLevel: "CALLSITE_ONLY",
                },
              ],
            },
            investigationThreads: [
              {
                threadId: "thread-path-risk",
                status: "OPEN",
                title: "补充路径风险说明",
                targetStepIds: [],
                targetNodeIds: ["method:upload-file"],
                summary: "当前只有调用点证据。",
                evidenceGap: "还没有看到上传工具内部路径校验实现。",
                recommendedQuestion: "请继续取证：展开 FileUploadUtils.upload，确认是否存在路径规范化或目录校验。",
                claimType: "RISK_HINT",
                evidence: [],
                latestTurnOutcomeId: "turn-2",
              },
            ],
            recentTurnOutcomes: [
              {
                outcomeId: "turn-2",
                threadId: "thread-path-risk",
                status: "OPEN_NO_PROGRESS",
                summary: "补充路径风险说明",
                detail: "本轮仍停留在原有风险线程上，没有获得可升级的新证据。",
                candidateChangeId: null,
                blockedReason: null,
                evidenceDelta: {
                  addedNodeIds: [],
                  addedFilePaths: [],
                  previousStrongestEvidenceLevel: "CALLSITE_ONLY",
                  currentStrongestEvidenceLevel: "CALLSITE_ONLY",
                  hitRecommendedQuestion: false,
                },
                observedNodeIds: ["method:upload-file"],
                observedFilePaths: ["CommonController.java"],
                strongestEvidenceLevel: "CALLSITE_ONLY",
              },
            ],
            candidateChanges: [],
          },
        }}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("tab", { name: "问答会话" }));

    expect(screen.getByText("补充路径风险说明")).toBeInTheDocument();
    expect(screen.getByText("无推进")).toBeInTheDocument();
    expect(screen.getByText("本轮仍停留在原有风险线程上，没有获得可升级的新证据。")).toBeInTheDocument();
    expect(screen.getByText("证据等级：仅调用点 -> 仅调用点")).toBeInTheDocument();
  });

  it("stops boundary pointer handlers from stealing focus when interacting with the audit composer page", async () => {
    const user = userEvent.setup();
    const onBoundaryPointerDown = vi.fn();
    const onBoundaryMouseDown = vi.fn();

    render(
      <div onPointerDown={onBoundaryPointerDown} onMouseDown={onBoundaryMouseDown}>
        <AuditTab
          state={auditStateFixture()}
          onQuestionDraftChange={vi.fn()}
          onSubmitQuestion={vi.fn()}
          onSelectChange={vi.fn()}
          onConfirmChange={vi.fn()}
          onSelectThread={vi.fn()}
          onInvestigateThread={vi.fn()}
        />
      </div>,
    );

    await user.click(screen.getByRole("tab", { name: "提问" }));
    onBoundaryPointerDown.mockClear();
    onBoundaryMouseDown.mockClear();
    const input = screen.getByRole("textbox", { name: "问答输入框" });
    await user.click(input);

    expect(onBoundaryPointerDown).not.toHaveBeenCalled();
    expect(onBoundaryMouseDown).not.toHaveBeenCalled();
  });

  it("keeps pending LLM preview out of the empty chat thread while the request is still running", () => {
    render(
      <AuditTab
        state={{
          ...auditStateFixture(),
          requestState: {
            phase: "RUNNING",
            scene: "问答",
            statusMessage: "正在等待远程 LLM 问答响应",
            detailMessage: "当前采用流式输出，界面会持续追加预览。",
            previewText: "{\"summary\":\"这个方法负责文件上传\",\"changes\":[]}",
            errorMessage: null,
            streaming: true,
          },
          result: {
            ...auditStateFixture().result!,
            auditSession: {
              ...auditStateFixture().result!.auditSession!,
              messages: [],
            },
          },
        }}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
        sectionPreferences={{ "audit.thread": true }}
      />,
    );

    expect(screen.getByText(/正在接收问答回答/)).toBeInTheDocument();
    expect(screen.getByText(/流式内容会先在“请求”里持续更新/)).toBeInTheDocument();
    expect(screen.queryByText(/这个方法负责文件上传/)).not.toBeInTheDocument();
  });

  it("renders assistant answers as readable sections instead of one raw paragraph", async () => {
    const user = userEvent.setup();
    const { container } = render(
      <AuditTab
        state={{
          ...auditStateFixture(),
          result: {
            ...auditStateFixture().result!,
            auditSession: {
              ...auditStateFixture().result!.auditSession!,
              messages: [
                {
                  messageId: "m-1",
                  role: "ASSISTANT",
                  content: "### 链路判断\n这个方法负责文件上传。\n\n1) 先读取上传目录\n2) 再写入文件\n3) 最后返回访问地址\n\n- 风险点：异常分支没有展开\n- 建议：补充业务异常和失败返回",
                },
              ],
            },
            candidateChanges: [],
          },
        }}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
        sectionPreferences={{ "audit.thread": true }}
      />,
    );

    expect(screen.getByText("结论")).toBeInTheDocument();
    expect(screen.getByText("链路判断")).toBeInTheDocument();
    expect(screen.getByText("这个方法负责文件上传。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "展开详细说明" })).toBeInTheDocument();
    expect(container.querySelector(".audit-rich-text.is-collapsed")).not.toBeNull();

    await user.click(screen.getByRole("button", { name: "展开详细说明" }));

    expect(screen.getByText("执行步骤")).toBeInTheDocument();
    expect(screen.getByText("风险提醒")).toBeInTheDocument();
    expect(screen.getByText("建议动作")).toBeInTheDocument();
    expect(screen.getByRole("list", { name: "执行步骤" })).toBeInTheDocument();
    expect(container.querySelector(".audit-rich-list.timeline")).not.toBeNull();
  });

  it("collapses long assistant answers into a summary first and lets the reader expand details", async () => {
    const user = userEvent.setup();
    const { container } = render(
      <AuditTab
        state={{
          ...auditStateFixture(),
          result: {
            ...auditStateFixture().result!,
            auditSession: {
              ...auditStateFixture().result!.auditSession!,
              messages: [
                {
                  messageId: "m-1",
                  role: "ASSISTANT",
                  content: "### 上传链路结论\n这个方法先拿上传目录，再落盘文件，最后组装可访问地址。后续如果出现异常，当前返回语义还不够明确。\n\n1) 读取上传目录\n2) 调用工具类写入文件\n3) 拼接访问地址\n\n- 风险点：异常时只返回 message\n- 建议：统一失败响应结构\n\n补充说明：当前回答基于右侧图范围，不等于完整项目事实。",
                },
              ],
            },
            candidateChanges: [],
          },
        }}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
        sectionPreferences={{ "audit.thread": true }}
      />,
    );

    expect(screen.getByText("上传链路结论")).toBeInTheDocument();
    expect(screen.getByText("这个方法先拿上传目录，再落盘文件，最后组装可访问地址。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "展开详细说明" })).toBeInTheDocument();
    expect(container.querySelector(".audit-rich-text.is-collapsed")).not.toBeNull();
    expect(container.querySelector(".audit-rich-scroll-shell")).toBeNull();

    await user.click(screen.getByRole("button", { name: "展开详细说明" }));

    expect(container.querySelector(".audit-rich-text.is-expanded")).not.toBeNull();
    expect(container.querySelector(".audit-rich-scroll-shell")).not.toBeNull();
    expect(screen.getByText("执行步骤")).toBeInTheDocument();
    expect(screen.getByText("风险提醒")).toBeInTheDocument();
    expect(screen.getByText("建议动作")).toBeInTheDocument();
    expect(screen.getByText("补充说明")).toBeInTheDocument();
  });

  it("does not show an expand action when the assistant answer only contains a heading and one summary paragraph", () => {
    render(
      <AuditTab
        state={{
          ...auditStateFixture(),
          result: {
            ...auditStateFixture().result!,
            auditSession: {
              ...auditStateFixture().result!.auditSession!,
              messages: [
                {
                  messageId: "m-1",
                  role: "ASSISTANT",
                  content: "### 当前结论\n这里只需要一句总结。",
                },
              ],
            },
            candidateChanges: [],
          },
        }}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
        sectionPreferences={{ "audit.thread": true }}
      />,
    );

    expect(screen.getByText("当前结论")).toBeInTheDocument();
    expect(screen.getByText("这里只需要一句总结。")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "展开详细说明" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "收起详细说明" })).not.toBeInTheDocument();
  });

  it("keeps supplementary drill-down details attached to the explanatory lead-in instead of misclassifying them as risks", async () => {
    const user = userEvent.setup();

    render(
      <AuditTab
        state={{
          ...auditStateFixture(),
          result: {
            ...auditStateFixture().result!,
            auditSession: {
              ...auditStateFixture().result!.auditSession!,
              messages: [
                {
                  messageId: "m-1",
                  role: "ASSISTANT",
                  content: "### 路径结论\n当前图里只能确认这个方法把 MultipartFile 直接交给了上传工具。\n\n补充说明：就“是否有路径穿越”而言，当前证据还不够。\n\n更具体地说：\n- filePath 来自配置，不是当前方法里的用户输入\n- newFileName 来自上传工具返回结果\n- 是否存在目录逃逸，要继续看 FileUploadUtils.upload 的实现",
                },
              ],
            },
            candidateChanges: [],
          },
        }}
        onQuestionDraftChange={vi.fn()}
        onSubmitQuestion={vi.fn()}
        onSelectChange={vi.fn()}
        onConfirmChange={vi.fn()}
        onSelectThread={vi.fn()}
        onInvestigateThread={vi.fn()}
        sectionPreferences={{ "audit.thread": true }}
      />,
    );

    await user.click(screen.getByRole("button", { name: "展开详细说明" }));

    const supplement = screen.getByText("更具体地说：").closest(".audit-rich-section");
    expect(supplement).not.toBeNull();
    expect(supplement).toHaveTextContent("filePath 来自配置，不是当前方法里的用户输入");
    expect(supplement).toHaveTextContent("newFileName 来自上传工具返回结果");
    expect(supplement).toHaveTextContent("是否存在目录逃逸，要继续看 FileUploadUtils.upload 的实现");
    expect(screen.queryByText("风险提醒")).not.toBeInTheDocument();
  });

  it("keeps tall audit page cards inside a scrollable page body instead of clipping lower actions", () => {
    expect(themeCss).toMatch(/\.audit-tab\s*\{[^}]*grid-template-rows:\s*auto\s+auto\s+minmax\(0,\s*1fr\);[^}]*align-content:\s*stretch;/s);
    expect(themeCss).toMatch(/\.audit-tab-panel\s*\{[^}]*height:\s*100%;[^}]*min-height:\s*0;[^}]*display:\s*grid;[^}]*overflow:\s*hidden;/s);
    expect(themeCss).toMatch(/\.audit-page-panel\s*\{[^}]*height:\s*100%;[^}]*min-height:\s*0;[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\);[^}]*overflow:\s*hidden;/s);
    expect(themeCss).toMatch(/\.audit-page-body\s*\{[^}]*grid-template-rows:\s*auto;[^}]*overflow-y:\s*auto;[^}]*overflow-x:\s*hidden;[^}]*scrollbar-gutter:\s*stable;/s);
    expect(themeCss).toMatch(/\.audit-page-body\s*>\s*\.workbench-audit-thread,\s*\.audit-page-body\s*>\s*\.workbench-candidate-list\s*\{[^}]*height:\s*auto;[^}]*min-height:\s*100%;/s);
    expect(themeCss).toMatch(/\.request-status-section-body\s*>\s*\*\s*\{[^}]*flex:\s*0\s+0\s+auto;/s);
    expect(themeCss).toMatch(/\.workbench-chat-stream\s*\{[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/\.workbench-chat-message\s*\{[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/\.audit-rich-scroll-shell\s*\{[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/\.workbench-candidate-card\s*\{[^}]*height:\s*auto;[^}]*min-height:\s*100%;/s);
    expect(themeCss).toMatch(/\.request-state-banner-details\s*\{[^}]*overflow:\s*auto;/s);
  });

  it("keeps the audit composer anchored instead of stretching the form out of view on short screens", () => {
    expect(themeCss).toMatch(/\.audit-page-body\s*>\s*\.workbench-chat-input\s*\{[^}]*width:\s*100%;[^}]*min-height:\s*0;[^}]*height:\s*100%;/s);
    expect(themeCss).toMatch(/\.workbench-chat-input\s*\{[^}]*display:\s*grid;[^}]*align-content:\s*start;[^}]*min-height:\s*0;/s);
  });

  it("allows long candidate detail content to wrap within the right pane", () => {
    expect(themeCss).toMatch(/\.workbench-candidate-detail-pane\s*\{[^}]*min-width:\s*0;/s);
    expect(themeCss).toMatch(/\.workbench-candidate-card\s*\{[^}]*min-width:\s*0;/s);
    expect(themeCss).toMatch(/\.workbench-candidate-evidence-item\s*\{[^}]*overflow-wrap:\s*anywhere;/s);
    expect(themeCss).toMatch(/\.workbench-candidate-evidence-item\s*>\s*strong,\s*\.workbench-candidate-evidence-item\s*>\s*span\s*\{[^}]*overflow-wrap:\s*anywhere;/s);
  });

  it("stacks candidate selector and detail by workbench container width, not only viewport width", () => {
    expect(themeCss).toMatch(/@container\s*\(max-width:\s*620px\)\s*\{[\s\S]*\.workbench-candidate-content\s*\{[\s\S]*grid-template-columns:\s*1fr;[\s\S]*grid-template-rows:\s*auto\s+auto;[\s\S]*align-items:\s*stretch;/);
    expect(themeCss).toMatch(/@container\s*\(max-width:\s*620px\)\s*\{[\s\S]*\.workbench-candidate-selector,\s*\.workbench-candidate-detail-pane,\s*\.workbench-candidate-card\s*\{[\s\S]*min-height:\s*auto;/);
    expect(themeCss).toMatch(/@container\s*\(max-width:\s*620px\)\s*\{[\s\S]*\.workbench-candidate-selector\s*\{[\s\S]*max-height:\s*none;[\s\S]*overflow:\s*visible;/);
    expect(themeCss).toMatch(/\.workbench-candidate-detail-pane,\s*\.workbench-candidate-card\s*\{[^}]*overflow-wrap:\s*anywhere;[^}]*word-break:\s*break-word;/s);
  });

});

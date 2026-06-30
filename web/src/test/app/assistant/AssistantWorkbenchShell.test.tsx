import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { AssistantWorkbenchShell } from "../../../app/assistant/AssistantWorkbenchShell";
import type { AssistantActionId, AssistantIntent } from "../../../app/assistant/assistantTypes";
import type {
  AnalysisDisplayMode,
  AssistantSessionState,
  AssistantTurn,
} from "../../../app/types";
import themeCss from "../../../app/theme.css?raw";

function sceneIdForDisplayMode(analysisDisplayMode: AnalysisDisplayMode): string {
  switch (analysisDisplayMode) {
    case "ARCHITECTURE_GRAPH":
      return "WORKSPACE_ARCHITECTURE_GRAPH";
    case "CLASS_DIAGRAM":
      return "WORKSPACE_CLASS_DIAGRAM";
    case "REVIEW_GRAPH":
      return "WORKSPACE_REVIEW_GRAPH";
    case "RESOURCE_RELATION_VIEW":
      return "WORKSPACE_RESOURCE_RELATION";
    case "FACT_GRAPH":
      return "WORKSPACE_FACT";
    case "FLOWCHART":
    default:
      return "WORKSPACE_FLOWCHART";
  }
}

function sessionState(
  intent: AssistantIntent,
  analysisDisplayMode: AnalysisDisplayMode = "FLOWCHART",
  scopeLabel = "OrderController.submit",
  actionId?: AssistantActionId | null,
): AssistantSessionState {
  return {
    sessionId: "assistant-session-test",
    activeIntent: intent,
    activeActionId: actionId,
    contextLocked: false,
    context: {
      selectedNodeIds: ["method:submit-order"],
      selectedDiffItemIds: [],
      analysisDisplayMode,
      currentSceneId: sceneIdForDisplayMode(analysisDisplayMode),
      selectedMethodSignature: "com.example.OrderController.submit():void",
      scopeLabel,
    },
    turns: [
      {
        turnId: "turn-qa",
        kind: "QA",
        sourceMessageType: "qaResult",
        resultId: "qa:test",
        createdAtEpochMillis: 1,
        context: {
          selectedNodeIds: ["method:submit-order"],
          selectedDiffItemIds: [],
          analysisDisplayMode,
          currentSceneId: "WORKSPACE_FLOWCHART",
          selectedMethodSignature: "com.example.OrderController.submit():void",
          scopeLabel: "OrderController.submit",
        },
      },
    ],
  };
}

function qaTurn(): AssistantTurn {
  return {
    turnId: "turn-qa",
    kind: "QA",
    createdAtEpochMillis: 1,
    context: {
      selectedNodeIds: ["method:submit-order"],
      selectedDiffItemIds: [],
      analysisDisplayMode: "FLOWCHART",
      currentSceneId: "WORKSPACE_FLOWCHART",
      selectedMethodSignature: "com.example.OrderController.submit():void",
      scopeLabel: "OrderController.submit",
    },
    qa: {
      source: "LOCAL_RULE",
      question: "这个方法会影响哪里？",
      answer: "会影响订单提交流程。",
      promptPreview: null,
      promptPreviewArtifactId: "assistant-qa-prompt:turn-qa",
      findings: [],
      candidateChanges: [],
      newCandidateChanges: [],
      warnings: [],
    },
  };
}

function classDiagramQaTurn(): AssistantTurn {
  return {
    ...qaTurn(),
    turnId: "turn-class-diagram-qa",
    context: {
      selectedNodeIds: ["class:quota-manager"],
      selectedDiffItemIds: [],
      analysisDisplayMode: "CLASS_DIAGRAM",
      currentSceneId: "WORKSPACE_CLASS_DIAGRAM",
      selectedMethodSignature: null,
      scopeLabel: "ClientRequestQuotaManager",
    },
    qa: {
      ...qaTurn().qa!,
      question: "metrics 是字段关系还是调用关系？",
      answer: "这是类图里的字段关系。",
    },
  };
}

function generationPlanTurn(): AssistantTurn {
  return {
    turnId: "turn-generation-plan",
    kind: "GENERATION_PLAN",
    createdAtEpochMillis: 2,
    context: {
      selectedNodeIds: ["method:submit-order"],
      selectedDiffItemIds: [],
      analysisDisplayMode: "FLOWCHART",
      currentSceneId: "WORKSPACE_FLOWCHART",
      selectedMethodSignature: "com.example.OrderController.submit():void",
      scopeLabel: "OrderController.submit",
    },
    generationPlan: {
      source: "LOCAL_RULE",
      summary: "补齐失败补偿路径",
      warnings: [],
      promptPreview: null,
      promptPreviewArtifactId: "assistant-generation-prompt:turn-generation-plan",
      items: [
        {
          id: "plan-item-compensation",
          title: "补充失败补偿逻辑",
          description: "在订单提交失败时记录补偿任务。",
          risk: "LOW",
          targetPath: "src/main/java/com/example/OrderController.java",
        },
      ],
    },
  };
}

function checkTurn(): AssistantTurn {
  return {
    turnId: "turn-check",
    kind: "CHECK_RESULT",
    createdAtEpochMillis: 3,
    context: {
      selectedNodeIds: ["method:submit-order"],
      selectedDiffItemIds: ["diff:submit-order"],
      analysisDisplayMode: "REVIEW_GRAPH",
      currentSceneId: "WORKSPACE_REVIEW_GRAPH",
      selectedMethodSignature: "com.example.OrderController.submit():void",
      scopeLabel: "OrderController.submit",
    },
    check: {
      source: "LOCAL_RULE",
      question: "当前改动是否需要补测试？",
      answer: "需要补充订单提交失败分支的测试。",
      promptPreview: null,
      findings: [],
      candidateChanges: [],
      newCandidateChanges: [],
      warnings: [],
    },
  };
}

function codeDraftTurn(): AssistantTurn {
  return {
    turnId: "turn-code-draft",
    kind: "CODE_DRAFT",
    createdAtEpochMillis: 5,
    context: {
      selectedNodeIds: ["method:submit-order"],
      selectedDiffItemIds: [],
      analysisDisplayMode: "FLOWCHART",
      currentSceneId: "WORKSPACE_FLOWCHART",
      selectedMethodSignature: "com.example.OrderController.submit():void",
      scopeLabel: "OrderController.submit",
    },
    codeDrafts: [
      {
        id: "draft-submit-order-test",
        sourceNodeId: "method:submit-order",
        title: "补充订单提交失败测试",
        targetPath: "src/test/java/com/example/OrderControllerTest.java",
        content: "class OrderControllerTest {}",
        warnings: [],
      },
    ],
    codeDraftWarnings: [],
  };
}

function failedQaTurn(): AssistantTurn {
  return {
    turnId: "turn-qa-failed",
    kind: "QA",
    createdAtEpochMillis: 1,
    context: {
      selectedNodeIds: ["method:submit-order"],
      selectedDiffItemIds: [],
      analysisDisplayMode: "FLOWCHART",
      currentSceneId: "WORKSPACE_FLOWCHART",
      selectedMethodSignature: "com.example.OrderController.submit():void",
      scopeLabel: "OrderController.submit",
    },
    qa: null,
    failure: {
      resultId: "qa-failure:41",
      message: "上游超时",
      detailMessage: "HTTP 504 from qa provider",
      phase: "FAILED",
      requestId: 41,
      sourceMessageType: "requestAssistantTask",
    },
  };
}

function longExplanationTurn(): AssistantTurn {
  const longPath =
    "fixtures/kafka-4.1.0-src/core/src/main/scala/kafka/server/ClientRequestQuotaManager.scala";
  return {
    turnId: "turn-explanation-long",
    kind: "EXPLANATION",
    createdAtEpochMillis: 2,
    context: {
      selectedNodeIds: ["method:quota-manager"],
      selectedDiffItemIds: [],
      analysisDisplayMode: "FLOWCHART",
      currentSceneId: "WORKSPACE_FLOWCHART",
      selectedMethodSignature:
        "kafka.server.ClientRequestQuotaManager.ClientRequestQuotaManager(org.apache.kafka.server.config.ClientQuotaManagerConfig,org.apache.kafka.common.metrics.Metrics,org.apache.kafka.common.utils.Time,java.lang.String,java.util.Optional):kafka.server.ClientRequestQuotaManager",
      scopeLabel: "ClientRequestQuotaManager.ClientRequestQuotaManager",
    },
    explanation: {
      source: "LOCAL_RULE",
      granularity: "BUSINESS",
      promptPreview: null,
      warnings: [],
      steps: [
        {
          stepId: "step-metrics",
          title: "当前步骤的源码语句是 this.metrics = metrics",
          granularity: "BUSINESS",
          kind: "METHOD_CALL",
          description:
            "承担的是把外部传入的 Metrics 对象保留下来，供当前对象后续继续使用。就当前已展示链路看，下一步骤接着使用同一个 metrics 参数创建 exemptMetricName，但图中没有展示 this.metrics 字段在后续方法中的读取位置，所以不能把字段的后续用途断言为已展示事实。",
          primaryNodeId: "method:quota-manager",
          codeSnippet: "this.metrics = metrics",
          evidence: [
            {
              id: "evidence-metrics-assignment",
              claim:
                "当前步骤的源码语句是 `this.metrics = metrics`，表示将构造函数入参保存到当前对象字段 this.metrics。",
              evidenceLevel: "DIRECT_SOURCE",
              references: [
                {
                  nodeId: "method:quota-manager",
                  filePath: longPath,
                  startLine: 327,
                  endLine: 334,
                },
              ],
            },
          ],
          followUpQuestions: [],
          downstreamTargets: [],
        },
      ],
    },
  };
}

function ShellHarness() {
  const [intent, setIntent] = useState<AssistantIntent>("ASK_CODE");
  const [actionId, setActionId] = useState<AssistantActionId>("ASK_CONTEXT");
  function handleActionChange(nextActionId: AssistantActionId) {
    setActionId(nextActionId);
    switch (nextActionId) {
      case "DESCRIBE_CLASS":
        setIntent("DESCRIBE_CLASS");
        break;
      case "ASK_CONTEXT":
        setIntent("ASK_CODE");
        break;
      case "GENERATE_IMPLEMENTATION":
        setIntent("GENERATE_CODE");
        break;
      case "CHECK_CHANGE":
        setIntent("CHECK_CHANGE");
        break;
      case "EXPLAIN_FLOW":
      case "EXPLAIN_STRUCTURE":
        setIntent("EXPLAIN_CODE");
        break;
    }
  }
  return (
    <AssistantWorkbenchShell
      assistantSessionState={sessionState(intent, "FLOWCHART", "OrderController.submit", actionId)}
      turns={[qaTurn()]}
      activeIntent={intent}
      activeActionId={actionId}
      requestRunning={false}
      onActionChange={handleActionChange}
      onSubmit={vi.fn()}
      onRetryLastQaRequest={vi.fn()}
      onEditFailedQaRequest={vi.fn()}
      onPrimeGenerationPlan={vi.fn()}
      onRequestCodeDrafts={vi.fn()}
      onWriteCodeDrafts={vi.fn()}
      onRevealReference={vi.fn()}
    />
  );
}

describe("AssistantWorkbenchShell", () => {
  it("keeps the sticky composer visible", () => {
    render(<ShellHarness />);

    expect(screen.getByRole("complementary", { name: "AI 代码工作台" })).toBeInTheDocument();
    expect(screen.getByTestId("assistant-composer")).toHaveClass("assistant-composer-sticky");
    expect(themeCss).toContain(".assistant-composer-sticky");
  });

  it("renders the send type selector inside the composer instead of the middle rail", () => {
    render(<ShellHarness />);

    const composer = screen.getByTestId("assistant-composer");
    const selector = screen.getByLabelText("发送动作");

    expect(composer).toContainElement(selector);
    expect(screen.queryByLabelText("AI 工作台 intent")).not.toBeInTheDocument();
    expect(within(selector).getByRole("button", { name: "追问代码" })).toHaveAttribute("aria-pressed", "true");
    // P1："发送为"眉标和辅助文案已移除；激活标签仍表达当前发送动作。
    expect(within(composer).getByText("追问代码", { selector: ".tag.active" })).toBeInTheDocument();
    expect(themeCss).toContain("grid-template-columns: repeat(4, minmax(0, 1fr));");
  });

  it("surfaces qa mode choices for code follow-up submissions", () => {
    render(<ShellHarness />);

    const composer = screen.getByTestId("assistant-composer");
    const qaMode = within(composer).getByRole("combobox", { name: "问答模式" });

    expect(qaMode).toHaveValue("AUTO");
    expect(within(qaMode).getByRole("option", { name: "Auto" })).toBeInTheDocument();
    expect(within(qaMode).getByRole("option", { name: "只回答" })).toBeInTheDocument();
    expect(within(qaMode).getByRole("option", { name: "风险复核" })).toBeInTheDocument();
    expect(within(qaMode).getByRole("option", { name: "代码调整" })).toBeInTheDocument();
  });

  it("surfaces explanation granularity before the first explanation result", () => {
    render(
      <AssistantWorkbenchShell
        assistantSessionState={sessionState("EXPLAIN_CODE", "FLOWCHART", "OrderController.submit", "EXPLAIN_FLOW")}
        turns={[]}
        activeIntent="EXPLAIN_CODE"
        activeActionId="EXPLAIN_FLOW"
        requestRunning={false}
        selectedExplanationGranularity="BUSINESS"
        onActionChange={vi.fn()}
        onSubmit={vi.fn()}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    const composer = screen.getByTestId("assistant-composer");
    const granularity = within(composer).getByRole("group", { name: "解释粒度" });

    expect(within(granularity).getByRole("button", { name: "业务级" })).toHaveAttribute("aria-pressed", "true");
    expect(within(granularity).getByRole("button", { name: "方法调用级" })).toBeInTheDocument();
    expect(within(granularity).getByRole("button", { name: "代码语义级" })).toBeInTheDocument();
  });

  it("uses class-diagram send labels and hides change-review send type", () => {
    render(
      <AssistantWorkbenchShell
        assistantSessionState={sessionState("ASK_CODE", "CLASS_DIAGRAM", "ClientRequestQuotaManager")}
        turns={[]}
        activeIntent="ASK_CODE"
        activeActionId="ASK_CONTEXT"
        requestRunning={false}
        onActionChange={vi.fn()}
        onSubmit={vi.fn()}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    const composer = screen.getByTestId("assistant-composer");
    const selector = screen.getByLabelText("发送动作");

    // P1："发送动作"眉标文案已移除；选择器仍可通过 aria-label 访问。
    expect(within(composer).queryByText("Send As")).not.toBeInTheDocument();
    expect(within(composer).getByText("追问类图", { selector: ".tag.active" })).toBeInTheDocument();
    expect(within(selector).getByRole("button", { name: "介绍这个类" })).toBeInTheDocument();
    expect(within(selector).getByRole("button", { name: "追问类图" })).toHaveAttribute("aria-pressed", "true");
    expect(within(selector).getByRole("button", { name: "解释关系" })).toBeInTheDocument();
    expect(within(selector).queryByRole("button", { name: "检查当前改动" })).not.toBeInTheDocument();
    expect(screen.getByRole("complementary", { name: "AI 类图工作台" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "当前类图上下文" })).not.toBeInTheDocument();
    expect(screen.getByText("类图节点 1")).toBeInTheDocument();
    expect(screen.queryByText("改动 0")).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "AI 类图工作台输入框" })).toHaveAttribute(
      "placeholder",
      "输入关于当前类、字段、构造参数或类型关系的问题",
    );
    expect(screen.getByRole("button", { name: "发送到 AI 类图工作台" })).toBeDisabled();
  });

  it("frames every assistant result type with chronology, latest state, and stable turn context", () => {
    render(
      <AssistantWorkbenchShell
        assistantSessionState={sessionState("ASK_CODE", "CLASS_DIAGRAM", "ClientRequestQuotaManager")}
        turns={[qaTurn(), checkTurn(), longExplanationTurn(), generationPlanTurn(), codeDraftTurn()]}
        activeIntent="ASK_CODE"
        requestRunning={false}
        onActionChange={vi.fn()}
        onSubmit={vi.fn()}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    // P1：常驻的"历史回答"顺序提示横幅已移除；轮次顺序仍由每轮标签表达。
    expect(screen.getByText("第 1 轮")).toBeInTheDocument();
    expect(screen.getByText("第 2 轮")).toBeInTheDocument();
    expect(screen.getByText("第 3 轮")).toBeInTheDocument();
    expect(screen.getByText("第 4 轮")).toBeInTheDocument();
    expect(screen.getByText("第 5 轮")).toBeInTheDocument();
    expect(screen.getByText("最新")).toBeInTheDocument();

    expect(screen.getAllByText("你问")).toHaveLength(2);
    expect(screen.getByText("这个方法会影响哪里？")).toBeInTheDocument();
    expect(screen.getAllByText("AI 答")).toHaveLength(2);
    expect(screen.getByText("会影响订单提交流程。")).toBeInTheDocument();
    expect(screen.getByText("当前改动是否需要补测试？")).toBeInTheDocument();
    expect(screen.getByText("需要补充订单提交失败分支的测试。")).toBeInTheDocument();

    expect(screen.getAllByText("历史上下文").length).toBeGreaterThanOrEqual(5);
    expect(screen.getAllByText("代码草稿").length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("生成实现建议").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("解释当前节点")).toBeInTheDocument();
  });

  it("labels class-diagram qa results as class-diagram follow-ups", () => {
    render(
      <AssistantWorkbenchShell
        assistantSessionState={sessionState("ASK_CODE", "CLASS_DIAGRAM", "ClientRequestQuotaManager")}
        turns={[classDiagramQaTurn()]}
        activeIntent="ASK_CODE"
        requestRunning={false}
        onActionChange={vi.fn()}
        onSubmit={vi.fn()}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("追问类图", { selector: ".assistant-turn-kind" })).toBeInTheDocument();
    expect(screen.queryByText("追问代码", { selector: ".assistant-turn-kind" })).not.toBeInTheDocument();
    expect(screen.getByText("你问")).toBeInTheDocument();
    expect(screen.getByText("AI 答")).toBeInTheDocument();
    expect(screen.getByText("这是类图里的字段关系。")).toBeInTheDocument();
  });

  it("does not clear composer input when the intent changes", async () => {
    const user = userEvent.setup();
    render(<ShellHarness />);

    await user.type(screen.getByRole("textbox", { name: "AI 工作台输入框" }), "解释失败分支");
    await user.click(screen.getByRole("button", { name: "生成实现建议" }));

    expect(screen.getByRole("textbox", { name: "AI 工作台输入框" })).toHaveValue("解释失败分支");
  });

  it("does not change returned assistant results when the send type changes", async () => {
    const user = userEvent.setup();
    render(<ShellHarness />);

    expect(screen.getByText("会影响订单提交流程。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "生成实现建议" }));

    expect(screen.getByText("会影响订单提交流程。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "生成实现建议" })).toHaveAttribute("aria-pressed", "true");
  });

  it("submits the selected send type as the assistant intent payload", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();

    function SubmitHarness() {
      const [intent, setIntent] = useState<AssistantIntent>("EXPLAIN_CODE");
      const [actionId, setActionId] = useState<AssistantActionId>("EXPLAIN_FLOW");
      function handleActionChange(nextActionId: AssistantActionId) {
        setActionId(nextActionId);
        if (nextActionId === "ASK_CONTEXT") {
          setIntent("ASK_CODE");
        } else if (nextActionId === "GENERATE_IMPLEMENTATION") {
          setIntent("GENERATE_CODE");
        } else if (nextActionId === "CHECK_CHANGE") {
          setIntent("CHECK_CHANGE");
        } else if (nextActionId === "DESCRIBE_CLASS") {
          setIntent("DESCRIBE_CLASS");
        } else {
          setIntent("EXPLAIN_CODE");
        }
      }
      return (
        <AssistantWorkbenchShell
          assistantSessionState={sessionState(intent, "FLOWCHART", "OrderController.submit", actionId)}
          turns={[]}
          activeIntent={intent}
          activeActionId={actionId}
          requestRunning={false}
          onActionChange={handleActionChange}
          onSubmit={onSubmit}
          onRetryLastQaRequest={vi.fn()}
          onEditFailedQaRequest={vi.fn()}
          onPrimeGenerationPlan={vi.fn()}
          onRequestCodeDrafts={vi.fn()}
          onWriteCodeDrafts={vi.fn()}
          onRevealReference={vi.fn()}
        />
      );
    }

    render(<SubmitHarness />);

    await user.click(screen.getByRole("button", { name: "生成实现建议" }));
    await user.type(screen.getByRole("textbox", { name: "AI 工作台输入框" }), "补齐补偿链路");
    await user.click(screen.getByRole("button", { name: "发送到 AI 代码工作台" }));

    expect(onSubmit).toHaveBeenCalledWith("GENERATE_CODE", "补齐补偿链路");

    await user.clear(screen.getByRole("textbox", { name: "AI 工作台输入框" }));
    await user.click(screen.getByRole("button", { name: "追问代码" }));
    await user.type(screen.getByRole("textbox", { name: "AI 工作台输入框" }), "为什么这里会失败？");
    await user.click(screen.getByRole("button", { name: "发送到 AI 代码工作台" }));

    expect(onSubmit).toHaveBeenLastCalledWith("ASK_CODE", "为什么这里会失败？");
  });

  it("allows empty check-change submissions but blocks other empty send types", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();

    function EmptySubmitHarness() {
      const [intent, setIntent] = useState<AssistantIntent>("ASK_CODE");
      const [actionId, setActionId] = useState<AssistantActionId>("ASK_CONTEXT");
      function handleActionChange(nextActionId: AssistantActionId) {
        setActionId(nextActionId);
        if (nextActionId === "CHECK_CHANGE") {
          setIntent("CHECK_CHANGE");
        } else if (nextActionId === "ASK_CONTEXT") {
          setIntent("ASK_CODE");
        } else if (nextActionId === "GENERATE_IMPLEMENTATION") {
          setIntent("GENERATE_CODE");
        } else if (nextActionId === "DESCRIBE_CLASS") {
          setIntent("DESCRIBE_CLASS");
        } else {
          setIntent("EXPLAIN_CODE");
        }
      }
      return (
        <AssistantWorkbenchShell
          assistantSessionState={sessionState(intent, "FLOWCHART", "OrderController.submit", actionId)}
          turns={[]}
          activeIntent={intent}
          activeActionId={actionId}
          requestRunning={false}
          onActionChange={handleActionChange}
          onSubmit={onSubmit}
          onRetryLastQaRequest={vi.fn()}
          onEditFailedQaRequest={vi.fn()}
          onPrimeGenerationPlan={vi.fn()}
          onRequestCodeDrafts={vi.fn()}
          onWriteCodeDrafts={vi.fn()}
          onRevealReference={vi.fn()}
        />
      );
    }

    render(<EmptySubmitHarness />);

    expect(screen.getByRole("button", { name: "发送到 AI 代码工作台" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "检查当前改动" }));
    await user.click(screen.getByRole("button", { name: "发送到 AI 代码工作台" }));

    expect(onSubmit).toHaveBeenCalledWith("CHECK_CHANGE", "");

    await user.click(screen.getByRole("button", { name: "追问代码" }));

    expect(screen.getByRole("button", { name: "发送到 AI 代码工作台" })).toBeDisabled();
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it("keeps code diff as a generation result action instead of a composer send type", () => {
    render(
      <AssistantWorkbenchShell
        assistantSessionState={sessionState("GENERATE_CODE")}
        turns={[generationPlanTurn()]}
        activeIntent="GENERATE_CODE"
        requestRunning={false}
        onActionChange={vi.fn()}
        onSubmit={vi.fn()}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    const composer = screen.getByTestId("assistant-composer");
    expect(within(composer).getByRole("button", { name: "生成实现建议" })).toBeInTheDocument();
    expect(within(composer).queryByRole("button", { name: "生成代码 diff" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "生成代码 diff" })).toBeInTheDocument();
  });

  it("keeps assistant thread content when the graph view changes", async () => {
    function GraphViewHarness() {
      const [displayMode, setDisplayMode] = useState<AnalysisDisplayMode>("FLOWCHART");
      return (
        <>
          <button type="button" onClick={() => setDisplayMode("REVIEW_GRAPH")}>切换图视图</button>
          <AssistantWorkbenchShell
            assistantSessionState={sessionState("ASK_CODE", displayMode)}
            turns={[qaTurn()]}
            activeIntent="ASK_CODE"
            requestRunning={false}
            onActionChange={vi.fn()}
            onSubmit={vi.fn()}
            onRetryLastQaRequest={vi.fn()}
            onEditFailedQaRequest={vi.fn()}
            onPrimeGenerationPlan={vi.fn()}
            onRequestCodeDrafts={vi.fn()}
            onWriteCodeDrafts={vi.fn()}
            onRevealReference={vi.fn()}
          />
        </>
      );
    }

    const user = userEvent.setup();
    render(<GraphViewHarness />);

    expect(screen.getByText("会影响订单提交流程。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "切换图视图" }));

    expect(screen.getByText("会影响订单提交流程。")).toBeInTheDocument();
    expect(screen.getByText("Review Graph")).toBeInTheDocument();
    expect(screen.queryByText("REVIEW_GRAPH")).not.toBeInTheDocument();
  });

  it("requests and renders historical prompt artifacts from assistant turns", async () => {
    const user = userEvent.setup();
    const onRequestArtifact = vi.fn();

    render(
      <AssistantWorkbenchShell
        assistantSessionState={sessionState("ASK_CODE")}
        turns={[qaTurn()]}
        activeIntent="ASK_CODE"
        requestRunning={false}
        onActionChange={vi.fn()}
        onSubmit={vi.fn()}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
        onRevealReference={vi.fn()}
        resolveArtifactText={(artifactId) =>
          artifactId === "assistant-qa-prompt:turn-qa" ? "历史问答完整提示词" : null
        }
        onRequestArtifact={onRequestArtifact}
      />,
    );

    await user.click(screen.getByRole("button", { name: "查看提示词" }));

    expect(onRequestArtifact).not.toHaveBeenCalled();
    expect(screen.getByText("历史问答完整提示词")).toBeInTheDocument();
  });

  it("keeps long code context out of the assistant header title", () => {
    const longScope =
      "kafka.server.ClientRequestQuotaManager.ClientRequestQuotaManager(org.apache.kafka.server.config.ClientQuotaManagerConfig,org.apache.kafka.common.metrics.Metrics,org.apache.kafka.common.utils.Time,java.lang.String,java.util.Optional):kafka.server.ClientRequestQuotaManager";
    render(
      <AssistantWorkbenchShell
        assistantSessionState={sessionState("EXPLAIN_CODE", "FLOWCHART", longScope)}
        turns={[]}
        activeIntent="EXPLAIN_CODE"
        requestRunning={false}
        onActionChange={vi.fn()}
        onSubmit={vi.fn()}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    // P1："下一次发送上下文"标题和冗长空态帮助已移除；
    // 长范围仍通过上下文标签展示，而不是标题。
    expect(screen.queryByText(/intent/)).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: longScope })).not.toBeInTheDocument();
    expect(screen.getByTitle(longScope)).toHaveClass("assistant-context-chip");
  });

  it("renders long explanation text and evidence references as wrapping content", () => {
    render(
      <AssistantWorkbenchShell
        assistantSessionState={sessionState("EXPLAIN_CODE")}
        turns={[longExplanationTurn()]}
        activeIntent="EXPLAIN_CODE"
        requestRunning={false}
        onActionChange={vi.fn()}
        onSubmit={vi.fn()}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText(/承担的是把外部传入的 Metrics 对象保留下来/)).toHaveClass("assistant-result-text");
    for (const resultText of screen.getAllByText(/当前步骤的源码语句是/)) {
      expect(resultText).toHaveClass("assistant-result-text");
    }
    expect(screen.getByRole("button", { name: /ClientRequestQuotaManager\.scala:327-334/ })).toHaveClass(
      "assistant-wrap-token",
    );
  });

  it("presents explanation granularity as a rerun action that appends a new answer", async () => {
    const user = userEvent.setup();
    const onChangeExplanationGranularity = vi.fn();

    render(
      <AssistantWorkbenchShell
        assistantSessionState={sessionState("EXPLAIN_CODE")}
        turns={[longExplanationTurn()]}
        activeIntent="EXPLAIN_CODE"
        requestRunning={false}
        selectedExplanationGranularity="BUSINESS"
        onChangeExplanationGranularity={onChangeExplanationGranularity}
        onActionChange={vi.fn()}
        onSubmit={vi.fn()}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("重新解释粒度")).toBeInTheDocument();
    expect(screen.getByText("选择后会重新生成一轮解释，并追加到底部；不会改写当前回答。")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "按方法调用级重新解释" }));

    expect(onChangeExplanationGranularity).toHaveBeenCalledWith("METHOD_CALL");
  });

  it("disables explanation rerun granularity actions while an assistant request is running", async () => {
    const user = userEvent.setup();
    const onChangeExplanationGranularity = vi.fn();

    render(
      <AssistantWorkbenchShell
        assistantSessionState={sessionState("EXPLAIN_CODE")}
        turns={[longExplanationTurn()]}
        activeIntent="EXPLAIN_CODE"
        requestRunning={true}
        selectedExplanationGranularity="BUSINESS"
        onChangeExplanationGranularity={onChangeExplanationGranularity}
        onActionChange={vi.fn()}
        onSubmit={vi.fn()}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    const methodCallRerun = screen.getByRole("button", { name: "按方法调用级重新解释" });
    expect(screen.getByText("正在生成回答，完成后可重新选择粒度。")).toBeInTheDocument();
    expect(methodCallRerun).toBeDisabled();

    await user.click(methodCallRerun);

    expect(onChangeExplanationGranularity).not.toHaveBeenCalled();
  });

  it("renders failure details from failed assistant result store turns", () => {
    render(
      <AssistantWorkbenchShell
        assistantSessionState={sessionState("ASK_CODE")}
        turns={[failedQaTurn()]}
        activeIntent="ASK_CODE"
        requestRunning={false}
        onActionChange={vi.fn()}
        onSubmit={vi.fn()}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("请求失败")).toBeInTheDocument();
    expect(screen.getByText("上游超时")).toBeInTheDocument();
    expect(screen.getByText("HTTP 504 from qa provider")).toBeInTheDocument();
    expect(screen.queryByText("问答结果尚未返回。")).not.toBeInTheDocument();
  });
});

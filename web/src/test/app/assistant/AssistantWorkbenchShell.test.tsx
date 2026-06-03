import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { AssistantWorkbenchShell } from "../../../app/assistant/AssistantWorkbenchShell";
import type { AnalysisDisplayMode, AssistantIntent, AssistantSessionState, AssistantTurn } from "../../../app/types";
import themeCss from "../../../app/theme.css?raw";

function sessionState(
  intent: AssistantIntent,
  analysisDisplayMode: AnalysisDisplayMode = "FLOWCHART",
): AssistantSessionState {
  return {
    sessionId: "assistant-session-test",
    activeIntent: intent,
    contextLocked: false,
    context: {
      selectedNodeIds: ["method:submit-order"],
      selectedDiffItemIds: [],
      analysisDisplayMode,
      currentSceneId: analysisDisplayMode === "REVIEW_GRAPH" ? "WORKSPACE_REVIEW_GRAPH" : "WORKSPACE_FLOWCHART",
      selectedMethodSignature: "com.example.OrderController.submit():void",
      scopeLabel: "OrderController.submit",
    },
    turns: [
      {
        turnId: "turn-qa",
        kind: "QA",
        sourceMessageType: "qaResult",
        resultId: null,
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
      findings: [],
      candidateChanges: [],
      newCandidateChanges: [],
      warnings: [],
    },
  };
}

function ShellHarness() {
  const [intent, setIntent] = useState<AssistantIntent>("ASK_CODE");
  return (
    <AssistantWorkbenchShell
      assistantSessionState={sessionState(intent)}
      turns={[qaTurn()]}
      activeIntent={intent}
      requestRunning={false}
      onIntentChange={setIntent}
      onSubmit={vi.fn()}
      onRetryLastQaRequest={vi.fn()}
      onEditFailedQaRequest={vi.fn()}
      onRequestGenerationPlan={vi.fn()}
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

  it("does not clear composer input when the intent changes", async () => {
    const user = userEvent.setup();
    render(<ShellHarness />);

    await user.type(screen.getByRole("textbox", { name: "AI 工作台输入框" }), "解释失败分支");
    await user.click(screen.getByRole("button", { name: "生成代码" }));

    expect(screen.getByRole("textbox", { name: "AI 工作台输入框" })).toHaveValue("解释失败分支");
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
            onIntentChange={vi.fn()}
            onSubmit={vi.fn()}
            onRetryLastQaRequest={vi.fn()}
            onEditFailedQaRequest={vi.fn()}
            onRequestGenerationPlan={vi.fn()}
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
    expect(screen.getByText("REVIEW_GRAPH")).toBeInTheDocument();
  });
});

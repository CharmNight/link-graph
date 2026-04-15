import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { StepDetail } from "./StepDetail";
import type { GraphBeautificationStep } from "../types";

function stepFixture(): GraphBeautificationStep {
  return {
    stepId: "step-validate-order",
    title: "Step 2 校验订单参数",
    granularity: "BUSINESS",
    kind: "BUSINESS_ACTION",
    description: "先校验订单参数，再决定是否继续执行。",
    primaryNodeId: "method:validate-order",
    codeSnippet: [
      "if (request == null) {",
      "  throw new IllegalArgumentException(\"request is null\");",
      "}",
      "if (request.getAmount() == null) {",
      "  throw new IllegalArgumentException(\"amount is null\");",
      "}",
      "validator.validate(request);",
      "return request;",
    ].join("\n"),
    evidence: [
      {
        id: "evidence-1",
        claim: "这里命中了方法内部动作。",
        evidenceLevel: "DIRECT_SOURCE",
        references: [
          {
            nodeId: "action:com-example-order-controller-validate-order",
            filePath: "/project/src/main/java/com/example/OrderController.java",
            startLine: 18,
            endLine: 20,
          },
        ],
      },
    ],
    followUpQuestions: [],
    downstreamTargets: [],
  };
}

describe("StepDetail", () => {
  it("collapses long code snippets by default and lets the reader expand them on demand", async () => {
    const user = userEvent.setup();

    const { container } = render(
      <StepDetail
        step={stepFixture()}
        onAddToDraft={vi.fn()}
        onDrillDown={vi.fn()}
        onFollowUp={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    const collapsedSnippet = container.querySelector(".workbench-code-snippet");
    expect(collapsedSnippet).not.toBeNull();
    expect(collapsedSnippet?.textContent).toContain("if (request == null) {");
    expect(collapsedSnippet?.textContent).not.toContain("return request;");
    expect(screen.getByRole("button", { name: "展开全部 8 行" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "展开全部 8 行" }));

    expect(container.querySelector(".workbench-code-snippet")?.textContent).toContain("return request;");
    expect(screen.getByRole("button", { name: "收起代码片段" })).toBeInTheDocument();
  });

  it("shows readable code references without leaking raw internal node ids", () => {
    render(
      <StepDetail
        step={stepFixture()}
        onAddToDraft={vi.fn()}
        onDrillDown={vi.fn()}
        onFollowUp={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByRole("button", { name: /OrderController\.java:18-20/ })).toBeInTheDocument();
    expect(screen.getByText("方法内动作")).toBeInTheDocument();
    expect(screen.queryByText("action:com-example-order-controller-validate-order")).not.toBeInTheDocument();
  });

  it("restores a real follow-up composer so the reader can ask a custom question for the current step", async () => {
    const user = userEvent.setup();
    const onFollowUp = vi.fn();

    render(
      <StepDetail
        step={{
          ...stepFixture(),
          followUpQuestions: ["金额为空时会走哪条分支？"],
        }}
        onAddToDraft={vi.fn()}
        onDrillDown={vi.fn()}
        onFollowUp={onFollowUp}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByRole("textbox", { name: "追问这一步输入框" })).toHaveValue("金额为空时会走哪条分支？");

    await user.clear(screen.getByRole("textbox", { name: "追问这一步输入框" }));
    await user.type(screen.getByRole("textbox", { name: "追问这一步输入框" }), "这里为什么直接抛 IllegalArgumentException？");
    await user.click(screen.getByRole("button", { name: "围绕这一步继续讲解" }));

    expect(onFollowUp).toHaveBeenCalledWith("step-validate-order", "这里为什么直接抛 IllegalArgumentException？");
  });
});

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { StepDetail } from "../../../app/workbench/StepDetail";
import type { GraphBeautificationStep } from "../../../app/types";

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

function structureStepFixture(): GraphBeautificationStep {
  return {
    stepId: "structure-application",
    title: "结构概览：application",
    granularity: "BUSINESS",
    kind: "STRUCTURE_OVERVIEW",
    description: "结构概览：application 是 COMPONENT 节点。",
    primaryNodeId: "arch:component:com.example.application",
    codeSnippet: null,
    evidence: [
      {
        id: "structure-graph",
        claim: "当前结构概览直接关联图节点。",
        evidenceLevel: "DIRECT_GRAPH",
        references: [
          {
            nodeId: "arch:component:com.example.application",
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
        onLocateStepNode={vi.fn()}
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
        onLocateStepNode={vi.fn()}
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
        onLocateStepNode={vi.fn()}
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

  it("uses structure wording and locates the current graph node for structure overview steps", async () => {
    const user = userEvent.setup();
    const onLocateStepNode = vi.fn();
    const onDrillDown = vi.fn();

    render(
      <StepDetail
        step={structureStepFixture()}
        onAddToDraft={vi.fn()}
        onLocateStepNode={onLocateStepNode}
        onDrillDown={onDrillDown}
        onFollowUp={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("结构概览")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "定位被调方法" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "定位图节点" }));

    expect(onLocateStepNode).toHaveBeenCalledWith("structure-application");
    expect(onDrillDown).not.toHaveBeenCalled();
  });

  it("keeps method-call drilldown wording only when a method-like downstream target exists", async () => {
    const user = userEvent.setup();
    const onLocateStepNode = vi.fn();
    const onDrillDown = vi.fn();

    render(
      <StepDetail
        step={{
          ...stepFixture(),
          downstreamTargets: ["method:charge-order"],
        }}
        onAddToDraft={vi.fn()}
        onLocateStepNode={onLocateStepNode}
        onDrillDown={onDrillDown}
        onFollowUp={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "定位被调方法" }));

    expect(onDrillDown).toHaveBeenCalledWith("step-validate-order");
    expect(onLocateStepNode).not.toHaveBeenCalled();
  });
});

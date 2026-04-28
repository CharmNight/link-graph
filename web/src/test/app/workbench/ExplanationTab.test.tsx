import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ExplanationTab } from "../../../app/workbench/ExplanationTab";
import type { ExplanationWorkbenchState } from "../../../app/types";
import themeCss from "../../../app/theme.css?raw";

function explanationStateFixture(): ExplanationWorkbenchState {
  return {
    granularity: "BUSINESS",
    selectedStepId: "step-read-upload-dir",
    requestState: { phase: "SUCCEEDED" },
    historyDepth: 0,
    canReturnToPrevious: false,
    historyTrail: ["当前链路讲解"],
    currentSessionLabel: "当前链路讲解",
    previousSessionLabel: null,
    result: {
      source: "MOCK",
      granularity: "BUSINESS",
      promptPreview: "prompt",
      warnings: [],
      steps: [
        {
          stepId: "step-read-upload-dir",
          title: "Step 1 读取上传目录",
          granularity: "BUSINESS",
          kind: "BUSINESS_ACTION",
          description: "这一步读取上传目录供后续保存文件使用",
          primaryNodeId: "method:upload-file",
          codeSnippet: "String uploadPath = RuoYiConfig.getUploadPath();",
          evidence: [
            {
              id: "evidence-1",
              claim: "这里直接读取上传目录配置。",
              evidenceLevel: "DIRECT_SOURCE",
              references: [
                {
                  nodeId: "method:upload-file",
                  filePath: "/project/src/main/java/com/example/CommonController.java",
                  startLine: 72,
                  endLine: 75,
                },
              ],
            },
          ],
          followUpQuestions: ["上传目录配置从哪里来？"],
          downstreamTargets: ["method:resolve-upload-dir"],
        },
      ],
    },
  };
}

describe("ExplanationTab", () => {

  it("keeps the tab root on the CSS grid contract instead of Uno display or overflow utilities", () => {
    const { container } = render(
      <ExplanationTab
        state={explanationStateFixture()}
        onSelectStep={vi.fn()}
        onLocateStepNode={vi.fn()}
        onInspectStepNode={vi.fn()}
        onGranularityChange={vi.fn()}
        onAddToDraft={vi.fn()}
        onDrillDown={vi.fn()}
        onFollowUp={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    const tab = container.querySelector(".workbench-tab");
    const body = container.querySelector(".workbench-tab-body");
    expect(tab).not.toBeNull();
    expect(tab).not.toHaveClass("block");
    expect(tab).not.toHaveClass("overflow-auto");
    expect(body).not.toBeNull();
    expect(body).not.toHaveClass("block");
    expect(body).not.toHaveClass("overflow-auto");
  });
  it("expands the core explanation modules by default on first use", () => {
    render(
      <ExplanationTab
        state={explanationStateFixture()}
        onSelectStep={vi.fn()}
        onLocateStepNode={vi.fn()}
        onInspectStepNode={vi.fn()}
        onGranularityChange={vi.fn()}
        onAddToDraft={vi.fn()}
        onDrillDown={vi.fn()}
        onFollowUp={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByRole("button", { name: "收起步骤列表" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "收起步骤详情" })).toBeInTheDocument();
    expect(screen.getByText("步骤列表")).toBeInTheDocument();
    expect(screen.getByText("步骤详情")).toBeInTheDocument();
  });

  it("renders step list on the left and step detail on the right", () => {
    const { container } = render(
      <ExplanationTab
        state={explanationStateFixture()}
        onSelectStep={vi.fn()}
        onLocateStepNode={vi.fn()}
        onInspectStepNode={vi.fn()}
        onGranularityChange={vi.fn()}
        onAddToDraft={vi.fn()}
        onDrillDown={vi.fn()}
        onFollowUp={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getAllByText("Step 1 读取上传目录").length).toBeGreaterThan(0);
    expect(screen.getByText("当前链路讲解")).toBeInTheDocument();
    expect(screen.getByText("这一步读取上传目录供后续保存文件使用")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "记为草稿备注" })).toBeInTheDocument();
    expect(screen.getByText("CommonController.java:72-75")).toBeInTheDocument();
    expect(screen.getByText("String uploadPath = RuoYiConfig.getUploadPath();")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "围绕这一步继续讲解" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "定位被调方法" })).toBeInTheDocument();
    expect(container.querySelector(".workbench-tab-body.explanation-layout")).not.toBeNull();
  });

  it("shows a return action when the reader is inside a follow-up explanation session", async () => {
    const user = userEvent.setup();
    const onReturnToPrevious = vi.fn();
    const onOpenHistory = vi.fn();

    render(
      <ExplanationTab
        state={{
          ...explanationStateFixture(),
          historyDepth: 1,
          canReturnToPrevious: true,
          historyTrail: ["当前链路讲解", "围绕 Step 1 读取上传目录 继续讲解"],
          currentSessionLabel: "围绕 Step 1 读取上传目录 继续讲解",
          previousSessionLabel: "当前链路讲解",
        }}
        onSelectStep={vi.fn()}
        onLocateStepNode={vi.fn()}
        onInspectStepNode={vi.fn()}
        onGranularityChange={vi.fn()}
        onAddToDraft={vi.fn()}
        onDrillDown={vi.fn()}
        onFollowUp={vi.fn()}
        onRevealReference={vi.fn()}
        onReturnToPrevious={onReturnToPrevious}
        onOpenHistory={onOpenHistory}
      />,
    );

    expect(screen.getByRole("button", { name: "讲解历史：当前链路讲解" })).toBeInTheDocument();
    expect(screen.getByText("围绕 Step 1 读取上传目录 继续讲解")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "返回上一讲解：当前链路讲解" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "讲解历史：当前链路讲解" }));

    expect(onOpenHistory).toHaveBeenCalledWith(0);

    await user.click(screen.getByRole("button", { name: "返回上一讲解：当前链路讲解" }));

    expect(onReturnToPrevious).toHaveBeenCalledTimes(1);
  });

  it("forwards hover and leave events for step-to-graph preview linkage", () => {
    const onHoverStep = vi.fn();
    const onLeaveStep = vi.fn();

    render(
      <ExplanationTab
        state={explanationStateFixture()}
        onSelectStep={vi.fn()}
        onLocateStepNode={vi.fn()}
        onInspectStepNode={vi.fn()}
        onGranularityChange={vi.fn()}
        onHoverStep={onHoverStep}
        onLeaveStep={onLeaveStep}
        onAddToDraft={vi.fn()}
        onDrillDown={vi.fn()}
        onFollowUp={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    const stepButton = screen.getByRole("button", { name: /Step 1 读取上传目录/ });
    fireEvent.mouseEnter(stepButton);
    fireEvent.mouseLeave(stepButton);

    expect(onHoverStep).toHaveBeenCalledWith("step-read-upload-dir");
    expect(onLeaveStep).toHaveBeenCalledTimes(1);
  });

  it("renders granularity tabs and forwards granularity switching", async () => {
    const user = userEvent.setup();
    const onGranularityChange = vi.fn();

    render(
      <ExplanationTab
        state={explanationStateFixture()}
        onSelectStep={vi.fn()}
        onLocateStepNode={vi.fn()}
        onInspectStepNode={vi.fn()}
        onGranularityChange={onGranularityChange}
        onHoverStep={vi.fn()}
        onLeaveStep={vi.fn()}
        onAddToDraft={vi.fn()}
        onDrillDown={vi.fn()}
        onFollowUp={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "方法调用级" }));

    expect(screen.getByRole("button", { name: "业务级" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "代码语义级" })).toBeInTheDocument();
    expect(onGranularityChange).toHaveBeenCalledWith("METHOD_CALL");
  });

  it("opens step context actions on right click and forwards locate requests", async () => {
    const user = userEvent.setup();
    const onLocateStepNode = vi.fn();

    render(
      <ExplanationTab
        state={explanationStateFixture()}
        onSelectStep={vi.fn()}
        onLocateStepNode={onLocateStepNode}
        onInspectStepNode={vi.fn()}
        onGranularityChange={vi.fn()}
        onHoverStep={vi.fn()}
        onLeaveStep={vi.fn()}
        onAddToDraft={vi.fn()}
        onDrillDown={vi.fn()}
        onFollowUp={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    fireEvent.contextMenu(screen.getByRole("button", { name: /Step 1 读取上传目录/ }), {
      clientX: 260,
      clientY: 180,
    });

    const menu = screen.getByRole("menu");
    expect(within(menu).getByRole("menuitem", { name: "定位到图中节点" })).toBeInTheDocument();
    expect(within(menu).getByRole("menuitem", { name: "编辑节点" })).toBeInTheDocument();

    await user.click(within(menu).getByRole("menuitem", { name: "定位到图中节点" }));

    expect(onLocateStepNode).toHaveBeenCalledWith("step-read-upload-dir");
  });

  it("lets the workbench tab own explanation scrolling instead of nested cards", () => {
    expect(themeCss).toMatch(/\.workbench-shell\s*\{[^}]*overflow:\s*hidden;/s);
    expect(themeCss).toMatch(/\.workbench-panel-body\s*\{[^}]*overflow-y:\s*auto;[^}]*overflow-x:\s*hidden;/s);
    expect(themeCss).toMatch(/\.workbench-tab\s*\{[^}]*height:\s*auto;[^}]*min-height:\s*0;[^}]*grid-template-rows:\s*auto\s+auto;[^}]*overflow:\s*visible;/s);
    expect(themeCss).not.toMatch(/\.workbench-tab\s*\{[^}]*overflow-y:\s*auto;/s);
    expect(themeCss).toMatch(/\.explanation-layout\s*\{[^}]*grid-template-columns:\s*320px\s+minmax\(0,\s*1fr\);[^}]*grid-template-rows:\s*auto;[^}]*align-items:\s*start;[^}]*align-content:\s*start;/s);
    expect(themeCss).not.toMatch(/\.explanation-layout\s*\{[^}]*height:\s*100%;/s);
    expect(themeCss).not.toMatch(/\.explanation-tab\s*\{[^}]*overflow-y:/s);
    expect(themeCss).toMatch(/\.explanation-layout\s+\.workbench-section-card-body\s*\{[^}]*grid-template-rows:\s*auto;[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/\.explanation-layout\s+\.workbench-section-card-body\s*>\s*\.workbench-step-list,\s*\.explanation-layout\s+\.workbench-section-card-body\s*>\s*\.workbench-step-detail\s*\{[^}]*height:\s*auto;[^}]*min-height:\s*0;[^}]*overflow:\s*visible;/s);
  });

  it("uses a wider fixed step-list column and prevents horizontal scrolling in explanation panes", () => {
    expect(themeCss).toMatch(/\.explanation-layout\s*\{[^}]*grid-template-columns:\s*320px\s+minmax\(0,\s*1fr\);/s);
    expect(themeCss).toMatch(/\.workbench-step-list,\s*\.workbench-step-detail[^{]*\{[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/\.workbench-step-item\s*\{[^}]*overflow:\s*hidden;/s);
    expect(themeCss).toMatch(/\.workbench-step-snippet,\s*\.workbench-step-meta,\s*\.workbench-step-ref\s*\{[^}]*overflow-wrap:\s*anywhere;[^}]*word-break:\s*break-word;/s);
  });

  it("lets narrow explanation content wrap without hiding action controls", () => {
    expect(themeCss).toMatch(/\.workbench-step-detail,\s*\.workbench-step-section,\s*\.workbench-code-snippet-shell,\s*\.workbench-reference-card\s*\{[^}]*min-width:\s*0;[^}]*max-width:\s*100%;/s);
    expect(themeCss).toMatch(/@container\s*\(max-width:\s*620px\)\s*\{[\s\S]*\.workbench-step-detail-head \.panel-actions\s*\{[\s\S]*grid-template-columns:\s*1fr;/);
  });

});

import { createEvent, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { HybridWorkbenchLayout } from "../../../app/components/HybridWorkbenchLayout";

describe("HybridWorkbenchLayout", () => {
  it("renders outline, graph stage and assistant workbench slots in the hybrid body", () => {
    render(
      <HybridWorkbenchLayout
        outline={<div data-testid="outline-slot">outline</div>}
        graphStage={<div data-testid="graph-stage-slot">graph stage</div>}
        assistantWorkbench={<div data-testid="assistant-workbench-slot">workbench</div>}
      />,
    );

    const layout = screen.getByTestId("hybrid-workbench-layout");
    expect(within(layout).getByTestId("outline-slot")).toBeInTheDocument();
    expect(within(layout).getByTestId("graph-stage-slot")).toBeInTheDocument();
    expect(within(layout).getByTestId("assistant-workbench-slot")).toBeInTheDocument();
  });

  it("collapses the outline into a rail and keeps the graph stage visible", async () => {
    const user = userEvent.setup();
    const onOutlineCollapsedChange = vi.fn();

    render(
      <HybridWorkbenchLayout
        outline={<div data-testid="outline-slot">outline</div>}
        graphStage={<div data-testid="graph-stage-slot">graph stage</div>}
        assistantWorkbench={<div data-testid="assistant-workbench-slot">workbench</div>}
        outlineCollapsed={false}
        onOutlineCollapsedChange={onOutlineCollapsedChange}
      />,
    );

    await user.click(screen.getByRole("button", { name: "折叠链路大纲" }));

    expect(onOutlineCollapsedChange).toHaveBeenCalledWith(true);
    expect(screen.getByTestId("hybrid-workbench-layout")).toHaveStyle({
      "--outline-width": "320px",
    });
    expect(screen.getByTestId("graph-stage-slot")).toBeInTheDocument();
  });

  it("renders a collapsed outline rail with an expand action", async () => {
    const user = userEvent.setup();
    const onOutlineCollapsedChange = vi.fn();

    render(
      <HybridWorkbenchLayout
        outline={<div data-testid="outline-slot">outline</div>}
        graphStage={<div data-testid="graph-stage-slot">graph stage</div>}
        assistantWorkbench={<div data-testid="assistant-workbench-slot">workbench</div>}
        outlineCollapsed
        onOutlineCollapsedChange={onOutlineCollapsedChange}
      />,
    );

    const layout = screen.getByTestId("hybrid-workbench-layout");
    expect(layout).toHaveClass("outline-collapsed");
    expect(layout).toHaveStyle({ "--outline-width": "52px" });
    expect(screen.queryByTestId("outline-slot")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "展开链路大纲" })).toHaveTextContent(">");
    expect(screen.getByRole("button", { name: "展开链路大纲" })).not.toHaveTextContent("OUT");

    await user.click(screen.getByRole("button", { name: "展开链路大纲" }));

    expect(onOutlineCollapsedChange).toHaveBeenCalledWith(false);
  });

  it("resizes the assistant workbench by dragging the separator", () => {
    const onWorkbenchWidthChange = vi.fn();

    render(
      <HybridWorkbenchLayout
        outline={<div data-testid="outline-slot">outline</div>}
        graphStage={<div data-testid="graph-stage-slot">graph stage</div>}
        assistantWorkbench={<div data-testid="assistant-workbench-slot">workbench</div>}
        workbenchWidth={420}
        onWorkbenchWidthChange={onWorkbenchWidthChange}
      />,
    );

    const resizer = screen.getByRole("separator", { name: "调整 AI 工作台宽度" });
    const downEvent = createEvent.pointerDown(resizer);
    Object.defineProperty(downEvent, "clientX", { value: 1200 });
    Object.defineProperty(downEvent, "pointerId", { value: 1 });
    fireEvent(resizer, downEvent);

    const moveEvent = createEvent.pointerMove(window);
    Object.defineProperty(moveEvent, "clientX", { value: 1100 });
    Object.defineProperty(moveEvent, "pointerId", { value: 1 });
    fireEvent(window, moveEvent);

    const upEvent = createEvent.pointerUp(window);
    Object.defineProperty(upEvent, "clientX", { value: 1100 });
    Object.defineProperty(upEvent, "pointerId", { value: 1 });
    fireEvent(window, upEvent);

    expect(onWorkbenchWidthChange).toHaveBeenCalledWith(520);
  });

  it("always keeps the right assistant workbench mounted while the outline may collapse for graph-focused views", () => {
    render(
      <HybridWorkbenchLayout
        outline={<div data-testid="outline-slot">outline</div>}
        graphStage={<div data-testid="graph-stage-slot">graph stage</div>}
        assistantWorkbench={<div data-testid="assistant-workbench-slot">workbench</div>}
        outlineCollapsed
      />,
    );

    const layout = screen.getByTestId("hybrid-workbench-layout");
    expect(layout).toHaveClass("outline-collapsed");
    expect(layout).not.toHaveClass("workbench-collapsed");
    expect(layout).toHaveStyle({ "--workbench-width": "420px" });
    expect(screen.getByTestId("graph-stage-slot")).toBeInTheDocument();
    expect(screen.getByTestId("assistant-workbench-slot")).toBeInTheDocument();
    expect(screen.getByRole("separator", { name: "调整 AI 工作台宽度" })).toBeInTheDocument();
  });
});

import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { GraphWorkbench } from "../../../app/workbench/GraphWorkbench";

describe("GraphWorkbench", () => {
  it("owns the toolbar legend canvas property drawer and workbench slots in one stable page skeleton", () => {
    render(
      <GraphWorkbench
        toolbar={<div data-testid="toolbar-slot">toolbar</div>}
        legend={<div data-testid="legend-slot">legend</div>}
        dialogs={<div data-testid="dialogs-slot">dialogs</div>}
        stage={<div data-testid="stage-slot">stage</div>}
        workbench={<div data-testid="workbench-slot">workbench</div>}
        propertyDrawer={<div data-testid="property-slot">property</div>}
      />,
    );

    const shell = screen.getByTestId("graph-workbench");
    expect(within(shell).getByTestId("toolbar-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("legend-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("dialogs-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("stage-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("workbench-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("property-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("stage-slot").closest("main")).not.toBeNull();
    expect(within(shell).getByTestId("stage-slot").closest(".workspace-stage-content")).not.toBeNull();
  });

  it("supports dragging the divider to resize the right workbench panel", () => {
    render(
      <GraphWorkbench
        toolbar={<div>toolbar</div>}
        stage={<div data-testid="stage-slot">stage</div>}
        workbench={<div data-testid="workbench-slot">workbench</div>}
      />,
    );

    const shell = screen.getByTestId("graph-workbench");
    const layout = shell.querySelector(".workbench-layout") as HTMLElement;
    const panel = screen.getByLabelText("工作台").closest(".workbench-panel") as HTMLElement;
    const separator = screen.getByRole("separator", { name: "调整工作台宽度" });

    layout.getBoundingClientRect = () => ({
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      right: 1200,
      bottom: 800,
      width: 1200,
      height: 800,
      toJSON: () => ({}),
    });

    fireEvent.mouseDown(separator, { clientX: 760 });
    fireEvent.mouseMove(window, { clientX: 700 });
    fireEvent.mouseUp(window);

    expect(panel.style.width).toBe("500px");
  });

  it("does not impose a fixed maximum width smaller than the remaining layout space", () => {
    render(
      <GraphWorkbench
        toolbar={<div>toolbar</div>}
        stage={<div data-testid="stage-slot">stage</div>}
        workbench={<div data-testid="workbench-slot">workbench</div>}
      />,
    );

    const shell = screen.getByTestId("graph-workbench");
    const layout = shell.querySelector(".workbench-layout") as HTMLElement;
    const panel = screen.getByLabelText("工作台").closest(".workbench-panel") as HTMLElement;
    const separator = screen.getByRole("separator", { name: "调整工作台宽度" });

    layout.getBoundingClientRect = () => ({
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      right: 1400,
      bottom: 800,
      width: 1400,
      height: 800,
      toJSON: () => ({}),
    });

    fireEvent.mouseDown(separator, { clientX: 920 });
    fireEvent.mouseMove(window, { clientX: 520 });
    fireEvent.mouseUp(window);

    expect(panel.style.width).toBe("880px");
  });
});

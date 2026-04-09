import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { GraphWorkbench } from "./GraphWorkbench";

describe("GraphWorkbench", () => {
  it("owns the toolbar legend summary canvas property drawer and dock slots in one stable page skeleton", () => {
    render(
      <GraphWorkbench
        toolbar={<div data-testid="toolbar-slot">toolbar</div>}
        legend={<div data-testid="legend-slot">legend</div>}
        summary={<div data-testid="summary-slot">summary</div>}
        dialogs={<div data-testid="dialogs-slot">dialogs</div>}
        stage={<div data-testid="stage-slot">stage</div>}
        propertyDrawer={<div data-testid="property-slot">property</div>}
        dock={<div data-testid="dock-slot">dock</div>}
      />,
    );

    const shell = screen.getByTestId("graph-workbench");
    expect(within(shell).getByTestId("toolbar-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("legend-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("summary-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("dialogs-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("stage-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("property-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("dock-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("stage-slot").closest("main")).not.toBeNull();
    expect(within(shell).getByTestId("stage-slot").closest(".workspace-stage-content")).not.toBeNull();
  });
});

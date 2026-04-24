import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { GraphWorkbench } from "../../../app/workbench/GraphWorkbench";
import themeCss from "../../../app/theme.css?raw";

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
    const stage = shell.querySelector(".workspace-stage-content") as HTMLElement;
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
    expect(stage.style.width).toBe("676px");
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

  it("uses the right workbench body as the scroll owner instead of clipping nested tab content", () => {
    expect(themeCss).toMatch(/\.workbench-panel-body\s*\{[^}]*overflow-y:\s*auto;[^}]*overflow-x:\s*hidden;/s);
    expect(themeCss).toMatch(/\.workbench-tab\s*\{[^}]*height:\s*auto;[^}]*min-height:\s*100%;[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\);[^}]*align-content:\s*stretch;[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/\.workbench-tab-body\s*\{[^}]*align-content:\s*start;[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/\.workbench-section-card-body\s*\{[^}]*grid-template-rows:\s*auto;[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/\.workbench-section-card-body\s*>\s*\*\s*\{[^}]*height:\s*auto;[^}]*min-height:\s*100%;/s);
  });

  it("lets the active workbench tab fill unused vertical panel space", () => {
    expect(themeCss).toMatch(/\.workbench-shell\s*\{[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\);/s);
    expect(themeCss).toMatch(/\.workbench-panel-body\s*\{[^}]*align-items:\s*stretch;/s);
    expect(themeCss).toMatch(/\.workbench-tab\s*\{[^}]*align-content:\s*stretch;/s);
    expect(themeCss).toMatch(/\.audit-tab\s*\{[^}]*grid-template-rows:\s*auto\s+auto\s+minmax\(0,\s*1fr\);[^}]*align-content:\s*stretch;/s);
    expect(themeCss).toMatch(/\.audit-tab-panel\s*\{[^}]*height:\s*auto;[^}]*min-height:\s*100%;/s);
    expect(themeCss).toMatch(/\.audit-page-panel\s*\{[^}]*height:\s*auto;[^}]*min-height:\s*100%;[^}]*grid-template-rows:\s*auto\s+auto;/s);
  });

  it("lets desktop workbench layouts grow vertically instead of being hidden by the stage shell", () => {
    expect(themeCss).toMatch(/(?:^|\n)\.app-shell\s*\{[^}]*overflow-x:\s*hidden;[^}]*overflow-y:\s*auto;/s);
    expect(themeCss).toMatch(/(?:^|\n)\.workspace-stage\s*\{[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/(?:^|\n)\.workbench-layout\s*\{[^}]*overflow:\s*visible;/s);
  });

  it("lets narrow screens scroll vertically instead of clipping the stage header", () => {
    expect(themeCss).toMatch(
      /@media\s*\(max-width:\s*780px\)\s*\{[\s\S]*?\.app-shell\s*\{[\s\S]*?height:\s*auto;[\s\S]*?overflow-y:\s*auto;[\s\S]*?\}[\s\S]*?\.workspace-stage\s*\{[\s\S]*?overflow:\s*visible;[\s\S]*?\}[\s\S]*?\.graph-canvas-panel\s*\{[\s\S]*?min-height:\s*420px;[\s\S]*?\}[\s\S]*?\}/s,
    );
  });
});

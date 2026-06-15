import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { GraphWorkbench } from "../../../app/workbench/GraphWorkbench";
import themeCss from "../../../app/theme.css?raw";

describe("GraphWorkbench", () => {
  it("owns the taskbar body tray dialogs and property drawer slots in one stable page skeleton", () => {
    render(
      <GraphWorkbench
        taskbar={<div data-testid="taskbar-slot">taskbar</div>}
        dialogs={<div data-testid="dialogs-slot">dialogs</div>}
        body={<div data-testid="body-slot">body</div>}
        tray={<div data-testid="tray-slot">tray</div>}
        propertyDrawer={<div data-testid="property-slot">property</div>}
      />,
    );

    const shell = screen.getByTestId("graph-workbench");
    expect(within(shell).getByTestId("taskbar-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("dialogs-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("body-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("tray-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("property-slot")).toBeInTheDocument();
    expect(within(shell).getByTestId("body-slot").closest("main")).not.toBeNull();
    expect(within(shell).getByTestId("body-slot").closest(".hybrid-workbench-body-slot")).not.toBeNull();
  });

  it("renders the new shell without the removed resize separator", () => {
    render(
      <GraphWorkbench
        taskbar={<div>taskbar</div>}
        body={<div data-testid="body-slot">body</div>}
      />,
    );

    expect(screen.queryByRole("separator", { name: "调整工作台宽度" })).not.toBeInTheDocument();
    expect(screen.getByTestId("body-slot").closest(".hybrid-workbench-body-slot")).not.toBeNull();
  });

  it("uses one assistant right-rail scroll contract with a fixed bottom composer", () => {
    const removedOuterShellClass = ["workbench", "shell"].join("-");
    expect(themeCss).not.toContain(`.${removedOuterShellClass}`);
    expect(themeCss).toMatch(/\.assistant-workbench-shell\s*\{[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\)\s+auto;[^}]*overflow:\s*hidden;/s);
    expect(themeCss).toMatch(/\.assistant-thread\s*\{[^}]*overflow-y:\s*auto;[^}]*scrollbar-gutter:\s*stable;[^}]*overscroll-behavior:\s*contain;/s);
    expect(themeCss).toMatch(/\.assistant-composer-sticky\s*\{[^}]*position:\s*sticky;[^}]*bottom:\s*0;/s);
    expect(themeCss).toMatch(/\.assistant-composer\s+\.assistant-send-type-selector\s*\{[^}]*grid-template-columns:\s*repeat\(4,\s*minmax\(0,\s*1fr\)\);/s);
    expect(themeCss).toMatch(/\.assistant-composer\s+textarea\s*\{(?=[^}]*min-height:\s*96px;)(?=[^}]*max-height:\s*132px;)(?=[^}]*resize:\s*none;)[^}]*\}/s);
  });

  it("keeps assistant card groups self-contained inside the thread", () => {
    const removedStagePanelClass = ["stage", "workbench", "panel"].join("-");
    expect(themeCss).not.toContain(`.${removedStagePanelClass}`);
    expect(themeCss).toMatch(/\.assistant-turn-group\s*\{[^}]*display:\s*grid;[^}]*gap:\s*12px;/s);
    expect(themeCss).toMatch(/\.assistant-explanation-tools\s*\{[^}]*display:\s*flex;[^}]*flex-wrap:\s*wrap;/s);
    expect(themeCss).toMatch(/\.assistant-step-selector\s*\{[^}]*display:\s*flex;[^}]*background:\s*transparent;/s);
  });

  it("lets assistant send type buttons and composer actions wrap on narrow right rails", () => {
    expect(themeCss).toMatch(/@container\s*\(max-width:\s*520px\)\s*\{[\s\S]*?\.assistant-send-type-selector\s*\{[\s\S]*?grid-template-columns:\s*1fr\s+1fr;[\s\S]*?\}/s);
    expect(themeCss).toMatch(/@container\s*\(max-width:\s*520px\)\s*\{[\s\S]*?\.assistant-composer-actions\s*\{[\s\S]*?display:\s*grid;[\s\S]*?grid-template-columns:\s*1fr;[\s\S]*?\}/s);
  });

  it("keeps the graph workbench shell vertically bounded on desktop", () => {
    expect(themeCss).toMatch(/(?:^|\n)\.app-shell\s*\{[^}]*overflow-x:\s*hidden;[^}]*overflow-y:\s*auto;/s);
    expect(themeCss).toMatch(/\.graph-workbench\s*\{[^}]*height:\s*100dvh;[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\)\s+auto;[^}]*overflow:\s*hidden;/s);
    expect(themeCss).toMatch(/\.hybrid-workbench-body-slot\s*\{[^}]*min-height:\s*0;[^}]*overflow:\s*hidden;/s);
  });

  it("lets narrow screens stack the graph and assistant rail without horizontal overflow", () => {
    expect(themeCss).toMatch(
      /@media\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.app-shell\.graph-workbench\s*\{[\s\S]*?width:\s*100%;[\s\S]*?overflow-x:\s*hidden;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@media\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.hybrid-workbench-layout,\s*\.hybrid-workbench-outline-slot,\s*\.hybrid-workbench-graph-slot,\s*\.hybrid-workbench-assistant-slot\s*\{[\s\S]*?width:\s*100%;[\s\S]*?max-width:\s*100%;[\s\S]*?\}/s,
    );
  });

  it("keeps the graph workbench constrained to the visible height on short desktop windows", () => {
    expect(themeCss).toMatch(
      /@media\s*\(max-height:\s*820px\)\s*\{[\s\S]*?\.app-shell\s*\{[\s\S]*?height:\s*100dvh;[\s\S]*?min-height:\s*0;[\s\S]*?overflow:\s*hidden;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@media\s*\(max-height:\s*820px\)\s*\{[\s\S]*?\.graph-workbench\s*\{[\s\S]*?height:\s*100dvh;[\s\S]*?min-height:\s*0;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@media\s*\(max-height:\s*820px\)\s*\{[\s\S]*?\.graph-canvas-panel\s*\{[\s\S]*?min-height:\s*0;[\s\S]*?\}/s,
    );
  });
});

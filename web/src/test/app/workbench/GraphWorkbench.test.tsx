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

  it("renders the new shell without the legacy resize separator", () => {
    render(
      <GraphWorkbench
        taskbar={<div>taskbar</div>}
        body={<div data-testid="body-slot">body</div>}
      />,
    );

    expect(screen.queryByRole("separator", { name: "调整工作台宽度" })).not.toBeInTheDocument();
    expect(screen.getByTestId("body-slot").closest(".hybrid-workbench-body-slot")).not.toBeNull();
  });

  it("uses one reusable outer scroll contract for all right workbench content", () => {
    expect(themeCss).toMatch(/\.workbench-shell\s*\{[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\);[^}]*overflow:\s*hidden;/s);
    expect(themeCss).toMatch(/\.workbench-tab-nav\s*\{[^}]*grid-template-columns:\s*repeat\(4,\s*minmax\(0,\s*1fr\)\);/s);
    expect(themeCss).toMatch(/\.workbench-panel-body\s*\{[^}]*display:\s*grid;[^}]*grid-template-rows:\s*auto;[^}]*overflow-y:\s*auto;[^}]*overflow-x:\s*hidden;[^}]*scrollbar-gutter:\s*stable;[^}]*overscroll-behavior:\s*contain;/s);
    expect(themeCss).toMatch(/\.workbench-panel-body>\*\s*\{[^}]*height:\s*auto;[^}]*align-self:\s*start;[^}]*min-height:\s*0;/s);
    expect(themeCss).toMatch(/\.workbench-tab\s*\{[^}]*height:\s*auto;[^}]*min-height:\s*0;[^}]*grid-template-rows:\s*auto\s+auto;[^}]*align-content:\s*start;[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/\.workbench-page-flow\s*\{[^}]*height:\s*auto;[^}]*align-content:\s*start;[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/\.workbench-card-flow\s*\{[^}]*height:\s*auto;[^}]*min-height:\s*0;[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/\.workbench-section-card\s*\{[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/\.workbench-section-card\.expanded\s*\{[^}]*flex:\s*0\s+0\s+auto;[^}]*min-height:\s*auto;/s);
    expect(themeCss).toMatch(/\.side-panel-scroll-body\s*\{[^}]*overflow:\s*visible;/s);
    expect(themeCss).not.toMatch(/\.workbench-tab\s*\{[^}]*overflow-y:\s*auto;/s);
    expect(themeCss).not.toMatch(/\.workbench-section-card\s*\{[^}]*overflow:\s*hidden;/s);
    expect(themeCss).not.toMatch(/\.side-panel-scroll-body\s*\{[^}]*overflow:\s*auto;/s);
  });

  it("keeps the stage workbench frame fixed while inner panes own vertical scrolling", () => {
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s*\{[^}]*grid-template-rows:\s*minmax\(0,\s*1fr\);[^}]*overflow:\s*hidden;/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.workbench-panel-body\s*\{[^}]*overflow:\s*hidden;/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.explanation-layout\s*\{[^}]*align-items:\s*stretch;[^}]*overflow:\s*hidden;/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.explanation-step-list-section\.expanded,\s*\.stage-workbench-panel\s+\.explanation-step-detail-section\.expanded\s*\{[^}]*display:\s*grid;[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\);/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.explanation-step-list-section\s+\.workbench-section-card-body,\s*\.stage-workbench-panel\s+\.explanation-step-detail-section\s+\.workbench-section-card-body\s*\{[^}]*height:\s*auto;[^}]*overflow:\s*hidden;/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.explanation-step-list-section\s+\.workbench-step-list,\s*\.stage-workbench-panel\s+\.explanation-step-detail-section\s+\.workbench-step-detail\s*\{[^}]*height:\s*auto;[^}]*overflow-y:\s*auto;/s,
    );
    expect(themeCss).not.toMatch(/@container\s*\(max-width:\s*620px\)\s*\{[\s\S]*?\.stage-workbench-panel\s+\.workbench-panel-body\s*\{[^}]*overflow-y:\s*auto;/s);
    expect(themeCss).not.toMatch(/@media\s*\(max-height:\s*760px\)\s+and\s+\(min-width:\s*1061px\)\s*\{[\s\S]*?\.stage-workbench-panel,[\s\S]*?overflow:\s*auto;/s);
  });

  it("lets the active workbench tab fill unused vertical panel space", () => {
    expect(themeCss).toMatch(/\.workbench-shell\s*\{[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\);/s);
    expect(themeCss).toMatch(/\.workbench-panel-body>\*\s*\{[^}]*height:\s*auto;[^}]*align-self:\s*start;[^}]*min-height:\s*0;/s);
    expect(themeCss).toMatch(/\.workbench-tab\s*\{[^}]*height:\s*auto;[^}]*align-content:\s*start;/s);
    expect(themeCss).toMatch(/\.qa-tab\s*\{[^}]*grid-template-rows:\s*auto\s+auto\s+auto;[^}]*align-content:\s*start;/s);
    expect(themeCss).toMatch(/\.qa-tab-nav\s*\{[^}]*position:\s*sticky;[^}]*top:\s*0;/s);
    expect(themeCss).toMatch(/\.qa-tab-panel\s*\{[^}]*min-height:\s*0;/s);
    expect(themeCss).not.toMatch(/\.qa-tab-panel\s*\{[^}]*height:\s*100%;/s);
    expect(themeCss).toMatch(/\.qa-page-panel\s*\{[^}]*min-height:\s*0;[^}]*grid-template-rows:\s*auto\s+auto;/s);
    expect(themeCss).toMatch(/\.qa-page-panel\s*\{[^}]*overflow:\s*visible;/s);
    expect(themeCss).not.toMatch(/(?<!stage-workbench-panel\s)\.qa-page-panel\s*\{[^}]*height:\s*100%;/s);
    expect(themeCss).toMatch(/\.stage-workbench-panel\s+\.qa-page-panel\s*\{[^}]*height:\s*100%;[^}]*overflow:\s*hidden;/s);
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

  it("keeps the workbench constrained to the visible height on short desktop windows", () => {
    expect(themeCss).toMatch(
      /@media\s*\(max-height:\s*820px\)\s*\{[\s\S]*?\.app-shell\s*\{[\s\S]*?height:\s*100dvh;[\s\S]*?min-height:\s*0;[\s\S]*?overflow:\s*hidden;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@media\s*\(max-height:\s*820px\)\s*\{[\s\S]*?\.workspace-stage\s*\{[\s\S]*?overflow:\s*hidden;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@media\s*\(max-height:\s*820px\)\s*\{[\s\S]*?\.graph-canvas-panel\s*\{[\s\S]*?min-height:\s*0;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@media\s*\(max-height:\s*820px\)\s*\{[\s\S]*?\.workbench-panel\s*\{[\s\S]*?overflow:\s*hidden;[\s\S]*?\}/s,
    );
  });
});

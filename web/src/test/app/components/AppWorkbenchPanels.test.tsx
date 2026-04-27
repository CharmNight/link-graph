import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import {
  AppWorkbenchPanels,
  type WorkbenchTab,
} from "../../../app/components/AppWorkbenchPanels";
import themeCss from "../../../app/theme.css?raw";

vi.mock("../../../app/components/CodeDraftPanel", () => ({
  CodeDraftPanel: () => <div data-testid="code-panel">code-panel</div>,
}));

vi.mock("../../../app/workbench/AuditTab", () => ({
  AuditTab: () => <div data-testid="audit-panel">audit-panel</div>,
}));

vi.mock("../../../app/workbench/DraftTab", () => ({
  DraftTab: () => <div data-testid="draft-panel">draft-panel</div>,
}));

vi.mock("../../../app/workbench/ExplanationTab", () => ({
  ExplanationTab: () => <div data-testid="explanation-panel">explanation-panel</div>,
}));

function buildProps(activeWorkbenchTab: WorkbenchTab = "explanation") {
  return {
    activeWorkbenchTab,
    onTabChange: vi.fn(),
    codePanelProps: {} as never,
    auditTabProps: {} as never,
    draftTabProps: {} as never,
    explanationTabProps: {} as never,
  };
}

describe("AppWorkbenchPanels", () => {
  it("renders the active workbench panel and delegates tab switching", async () => {
    const user = userEvent.setup();
    const props = buildProps("draft");
    render(<AppWorkbenchPanels {...props} />);

    expect(screen.getByTestId("draft-panel")).toBeInTheDocument();
    expect(screen.queryByTestId("code-panel")).not.toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "代码" }));

    expect(props.onTabChange).toHaveBeenCalledWith("code");
  });

  it("keeps workbench tabs fixed while the shared panel body scrolls", () => {
    render(<AppWorkbenchPanels {...buildProps("code")} />);

    expect(screen.getByRole("tabpanel", { name: "代码" })).toBeInTheDocument();
    expect(themeCss).toMatch(
      /\.workbench-shell\s*\{[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\);[^}]*overflow:\s*hidden;/s,
    );
    expect(themeCss).toMatch(
      /\.workbench-panel-body\s*\{[^}]*display:\s*grid;[^}]*grid-template-rows:\s*auto;[^}]*overflow-y:\s*auto;[^}]*overflow-x:\s*hidden;[^}]*scrollbar-gutter:\s*stable;[^}]*overscroll-behavior:\s*contain;/s,
    );
    expect(themeCss).toMatch(/\.workbench-tab-nav\s*\{[^}]*grid-template-columns:\s*repeat\(4,\s*minmax\(0,\s*1fr\)\);/s);
    expect(themeCss).toMatch(
      /\.workbench-panel-body>\*\s*\{[^}]*height:\s*auto;[^}]*align-self:\s*start;[^}]*min-height:\s*0;/s,
    );
    expect(themeCss).toMatch(/\.workbench-tab\s*\{[^}]*height:\s*auto;[^}]*overflow:\s*visible;/s);
    expect(themeCss).not.toMatch(/\.workbench-tab\s*\{[^}]*overflow-y:\s*auto;/s);
    expect(themeCss).toMatch(/\.code-draft-panel\s*\{[^}]*grid-template-rows:\s*auto\s+auto\s+auto;[^}]*align-content:\s*start;/s);
    expect(themeCss).not.toMatch(/\.code-draft-panel\s*\{[^}]*overflow-y:\s*auto;/s);
  });

  it("resets the shared right-side scroll when switching workbench tabs", async () => {
    const { container, rerender } = render(<AppWorkbenchPanels {...buildProps("audit")} />);
    const shell = container.querySelector(".workbench-shell") as HTMLElement;
    const panelBody = container.querySelector(".workbench-panel-body") as HTMLElement;
    shell.scrollTop = 120;
    panelBody.scrollTop = 240;

    rerender(<AppWorkbenchPanels {...buildProps("code")} />);

    await waitFor(() => expect(panelBody.scrollTop).toBe(0));
    expect(shell.scrollTop).toBe(120);
  });
});

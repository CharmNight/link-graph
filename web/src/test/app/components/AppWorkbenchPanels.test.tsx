import { render, screen } from "@testing-library/react";
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

  it("lets the active workbench tab fill the shared panel instead of leaving a bottom gap", () => {
    render(<AppWorkbenchPanels {...buildProps("code")} />);

    expect(screen.getByRole("tabpanel", { name: "代码" })).toBeInTheDocument();
    expect(themeCss).toMatch(
      /\.workbench-panel-body\s*\{[^}]*display:\s*grid;[^}]*grid-template-rows:\s*minmax\(0,\s*1fr\);[^}]*overflow:\s*hidden;/s,
    );
    expect(themeCss).toMatch(
      /\.workbench-panel-body>\*\s*\{[^}]*height:\s*100%;[^}]*min-height:\s*0;/s,
    );
    expect(themeCss).toMatch(
      /\.workbench-tab\s*\{[^}]*height:\s*100%;[^}]*min-height:\s*0;[^}]*overflow-y:\s*auto;[^}]*overflow-x:\s*hidden;/s,
    );
    expect(themeCss).toMatch(
      /\.code-draft-panel\s*\{[^}]*height:\s*100%;[^}]*min-height:\s*0;[^}]*overflow-y:\s*auto;[^}]*overflow-x:\s*hidden;/s,
    );
  });
});

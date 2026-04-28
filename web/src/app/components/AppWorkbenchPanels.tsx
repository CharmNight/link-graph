import { useEffect, useRef, type ComponentProps } from "react";
import { CodeDraftPanel } from "./CodeDraftPanel";
import { AuditTab } from "../workbench/AuditTab";
import { DraftTab } from "../workbench/DraftTab";
import { ExplanationTab } from "../workbench/ExplanationTab";

export type WorkbenchTab = "explanation" | "audit" | "draft" | "code";

const WORKBENCH_TABS: Array<{ id: WorkbenchTab; label: string }> = [
  { id: "explanation", label: "讲解" },
  { id: "audit", label: "问答" },
  { id: "draft", label: "草稿" },
  { id: "code", label: "代码" },
];

interface AppWorkbenchPanelsProps {
  activeWorkbenchTab: WorkbenchTab;
  onTabChange: (tab: WorkbenchTab) => void;
  codePanelProps: ComponentProps<typeof CodeDraftPanel>;
  auditTabProps: ComponentProps<typeof AuditTab>;
  draftTabProps: ComponentProps<typeof DraftTab>;
  explanationTabProps: ComponentProps<typeof ExplanationTab>;
}

export function AppWorkbenchPanels({
  activeWorkbenchTab,
  onTabChange,
  codePanelProps,
  auditTabProps,
  draftTabProps,
  explanationTabProps,
}: AppWorkbenchPanelsProps) {
  const panelBodyRef = useRef<HTMLDivElement | null>(null);
  let panel = <ExplanationTab {...explanationTabProps} />;
  if (activeWorkbenchTab === "code") {
    panel = <CodeDraftPanel {...codePanelProps} />;
  } else if (activeWorkbenchTab === "audit") {
    panel = <AuditTab {...auditTabProps} />;
  } else if (activeWorkbenchTab === "draft") {
    panel = <DraftTab {...draftTabProps} />;
  }

  useEffect(() => {
    if (panelBodyRef.current) {
      panelBodyRef.current.scrollTop = 0;
    }
  }, [activeWorkbenchTab]);

  return (
    <section className="workbench-shell">
      <div className="workbench-tab-nav" role="tablist" aria-label="工作台切换">
        {WORKBENCH_TABS.map((tab) => (
          <button
            key={tab.id}
            id={`workbench-tab-${tab.id}`}
            type="button"
            role="tab"
            aria-selected={activeWorkbenchTab === tab.id}
            aria-controls={`workbench-panel-${tab.id}`}
            className={activeWorkbenchTab === tab.id ? "workbench-tab-button active" : "workbench-tab-button"}
            onClick={() => onTabChange(tab.id)}
          >
            {tab.label}
          </button>
        ))}
      </div>
      <div
        ref={panelBodyRef}
        id={`workbench-panel-${activeWorkbenchTab}`}
        role="tabpanel"
        aria-labelledby={`workbench-tab-${activeWorkbenchTab}`}
        className="workbench-panel-body m-scrollbar"
      >
        {panel}
      </div>
    </section>
  );
}

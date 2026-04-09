import { useEffect, useId, type ReactNode } from "react";

interface ResultPanelOverlayTab {
  id: string;
  label: string;
}

interface ResultPanelOverlayProps {
  activeTab: string;
  tabs: ResultPanelOverlayTab[];
  onSelectTab: (tabId: string) => void;
  onClose: () => void;
  children: ReactNode;
}

export function ResultPanelOverlay({
  activeTab,
  tabs,
  onSelectTab,
  onClose,
  children,
}: ResultPanelOverlayProps) {
  const titleId = useId();

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  return (
    <div className="workspace-dock-backdrop">
      <section
        className="workspace-dock-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        <div className="dock-header">
          <div>
            <p className="eyebrow">结果面板</p>
            <h2 id={titleId}>结果面板</h2>
          </div>
          <button type="button" className="ghost-button" onClick={onClose}>
            收起结果面板
          </button>
        </div>
        <div className="dock-tabs">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              type="button"
              className={activeTab === tab.id ? "dock-tab active" : "dock-tab"}
              onClick={() => onSelectTab(tab.id)}
            >
              {tab.label}
            </button>
          ))}
        </div>
        <div className="dock-body">{children}</div>
      </section>
    </div>
  );
}

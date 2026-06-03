import { useEffect, useRef, useState } from "react";
import { analysisDisplayModeLabel } from "../labels";
import type { AnalysisDisplayMode, OperationFeedback } from "../types";

const DISPLAY_MODES: AnalysisDisplayMode[] = [
  "FACT_GRAPH",
  "FLOWCHART",
  "RESOURCE_RELATION_VIEW",
  "ARCHITECTURE_GRAPH",
  "CLASS_DIAGRAM",
  "REVIEW_GRAPH",
];

interface ToolbarProps {
  analysisDisplayMode: AnalysisDisplayMode;
  operationFeedback?: OperationFeedback | null;
  onRequestAnalysisDisplayMode: (displayMode: AnalysisDisplayMode) => void;
  onImportMermaid: () => void;
  onExportMermaid: () => void;
  onShowDiff: () => void;
  onRequestSync: () => void;
  onRequestGenerationPlan: () => void;
  onRequestGraphBeautification: () => void;
  onRequestCodeDrafts: () => void;
  onOpenSettings: () => void;
}

export function Toolbar({
  analysisDisplayMode,
  operationFeedback,
  onRequestAnalysisDisplayMode,
  onImportMermaid,
  onExportMermaid,
  onShowDiff,
  onRequestSync,
  onRequestGenerationPlan,
  onRequestGraphBeautification,
  onRequestCodeDrafts,
  onOpenSettings,
}: ToolbarProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!menuOpen) {
      return undefined;
    }
    const handlePointerDown = (event: MouseEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setMenuOpen(false);
      }
    };
    window.addEventListener("mousedown", handlePointerDown);
    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.removeEventListener("mousedown", handlePointerDown);
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [menuOpen]);

  function runAndClose(action: () => void) {
    action();
    setMenuOpen(false);
  }

  return (
    <header className="workspace-toolbar">
      <div className="toolbar-heading">
        <p className="eyebrow">问答画布</p>
        <div className="toolbar-title-row compact">
          <h1>链路图画布</h1>
          <p className="toolbar-hint">在代码中右键方法，可直接查看完整链路或追加为节点。</p>
        </div>
        {operationFeedback ? (
          <div className="toolbar-status">
            <span className={`feedback-pill feedback-${operationFeedback.level.toLowerCase()}`}>
              {operationFeedback.message}
            </span>
          </div>
        ) : null}
      </div>

      <div className="toolbar-actions" ref={menuRef}>
        <div className="toolbar-mode-switch" role="group" aria-label="展示模式">
          {DISPLAY_MODES.map((displayMode) => (
            <button
              key={displayMode}
              type="button"
              className={`toolbar-mode-button ${analysisDisplayMode === displayMode ? "is-active" : ""}`}
              aria-pressed={analysisDisplayMode === displayMode}
              onClick={() => onRequestAnalysisDisplayMode(displayMode)}
            >
              {analysisDisplayModeLabel(displayMode)}
            </button>
          ))}
        </div>
        <button
          type="button"
          className="ghost-button"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((open) => !open)}
        >
          更多操作
        </button>
        {menuOpen ? (
          <div role="menu" className="toolbar-menu">
            <button type="button" role="menuitem" onClick={() => runAndClose(onImportMermaid)}>
              导入 Mermaid
            </button>
            <button type="button" role="menuitem" onClick={() => runAndClose(onExportMermaid)}>
              导出 Mermaid
            </button>
            <button type="button" role="menuitem" onClick={() => runAndClose(onShowDiff)}>
              对比代码
            </button>
            <button type="button" role="menuitem" onClick={() => runAndClose(onRequestSync)}>
              同步预览
            </button>
            <button type="button" role="menuitem" onClick={() => runAndClose(onRequestGenerationPlan)}>
              生成实现建议
            </button>
            <button type="button" role="menuitem" onClick={() => runAndClose(onRequestGraphBeautification)}>
              链路讲解
            </button>
            <button type="button" role="menuitem" onClick={() => runAndClose(onRequestCodeDrafts)}>
              生成代码 diff
            </button>
            <button type="button" role="menuitem" onClick={() => runAndClose(onOpenSettings)}>
              设置
            </button>
          </div>
        ) : null}
      </div>
    </header>
  );
}

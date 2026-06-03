import { analysisDisplayModeLabel } from "../labels";
import type { AnalysisDisplayMode, AssistantContextSnapshot } from "../types";

interface AssistantContextBarProps {
  context: AssistantContextSnapshot;
}

function displayModeLabel(mode?: string | null): string | null {
  if (!mode) {
    return null;
  }
  return analysisDisplayModeLabel(mode as AnalysisDisplayMode);
}

export function AssistantContextBar({ context }: AssistantContextBarProps) {
  const modeLabel = displayModeLabel(context.analysisDisplayMode);
  return (
    <section className="assistant-context-bar" aria-label="当前上下文">
      <div>
        <p className="eyebrow">AI 代码工作台</p>
        <h2>{context.scopeLabel?.trim() || "当前代码上下文"}</h2>
      </div>
      <div className="assistant-context-meta">
        {context.analysisDisplayMode ? (
          <span className="toolbar-chip" title={modeLabel ?? context.analysisDisplayMode}>
            {context.analysisDisplayMode}
          </span>
        ) : null}
        {context.selectedMethodSignature ? (
          <span className="toolbar-chip">{context.selectedMethodSignature}</span>
        ) : null}
        <span className="toolbar-chip">节点 {context.selectedNodeIds.length}</span>
        <span className="toolbar-chip">改动 {context.selectedDiffItemIds.length}</span>
      </div>
    </section>
  );
}

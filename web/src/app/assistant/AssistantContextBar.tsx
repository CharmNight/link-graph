import { analysisDisplayModeLabel } from "../labels";
import { Chip } from "../components/Chip";
import type { AnalysisDisplayMode, AssistantContextSnapshot } from "../types";
import { isClassDiagramAssistantContext } from "./assistantModels";

interface AssistantContextBarProps {
  context: AssistantContextSnapshot;
}

function displayModeLabel(mode?: string | null): string | null {
  if (!mode) {
    return null;
  }
  return analysisDisplayModeLabel(mode as AnalysisDisplayMode);
}

function compactMethodSignature(signature: string): string {
  const trimmed = signature.trim();
  const methodPath = trimmed.includes("(") ? trimmed.slice(0, trimmed.indexOf("(")) : trimmed;
  const pathParts = methodPath.split(".").filter(Boolean);
  if (pathParts.length >= 2) {
    return pathParts.slice(-2).join(".");
  }
  return methodPath || trimmed;
}

function contextScopeLabel(scopeLabel?: string | null, methodSignature?: string | null): string | null {
  const trimmedScope = scopeLabel?.trim();
  const trimmedSignature = methodSignature?.trim();
  if (!trimmedScope && trimmedSignature) {
    return compactMethodSignature(trimmedSignature);
  }
  if (!trimmedScope) {
    return null;
  }
  if (trimmedSignature && trimmedScope === trimmedSignature) {
    return compactMethodSignature(trimmedSignature);
  }
  if (trimmedScope.includes("(")) {
    return compactMethodSignature(trimmedScope);
  }
  if (trimmedScope.split(".").filter(Boolean).length > 2) {
    return compactMethodSignature(trimmedScope);
  }
  return trimmedScope;
}

export function AssistantContextBar({ context }: AssistantContextBarProps) {
  const isClassDiagram = isClassDiagramAssistantContext(context);
  const modeLabel = displayModeLabel(context.analysisDisplayMode);
  const methodSignature = context.selectedMethodSignature?.trim() || null;
  const scopeLabel = contextScopeLabel(context.scopeLabel, methodSignature);
  const rawScopeLabel = context.scopeLabel?.trim() || methodSignature || scopeLabel;
  const contextLabel = isClassDiagram ? "下一次类图发送上下文" : "下一次发送上下文";
  const nodeCountLabel = isClassDiagram
    ? `类图节点 ${context.selectedNodeIds.length}`
    : `节点 ${context.selectedNodeIds.length}`;
  return (
    <section className="assistant-context-bar" aria-label={contextLabel}>
      <div className="assistant-context-meta">
        {context.analysisDisplayMode ? (
          <Chip variant="toolbar-chip" className="assistant-context-chip" title={modeLabel ?? context.analysisDisplayMode}>
            {modeLabel ?? context.analysisDisplayMode}
          </Chip>
        ) : null}
        {scopeLabel ? (
          <Chip variant="toolbar-chip" className="assistant-context-chip" title={rawScopeLabel ?? scopeLabel}>{scopeLabel}</Chip>
        ) : null}
        <Chip variant="toolbar-chip" className="assistant-context-chip">{nodeCountLabel}</Chip>
        {isClassDiagram ? null : (
          <Chip variant="toolbar-chip" className="assistant-context-chip">改动 {context.selectedDiffItemIds.length}</Chip>
        )}
      </div>
      {methodSignature ? (
        <details className="assistant-context-details">
          <summary>查看完整方法签名</summary>
          <code className="assistant-context-signature">{methodSignature}</code>
        </details>
      ) : null}
    </section>
  );
}

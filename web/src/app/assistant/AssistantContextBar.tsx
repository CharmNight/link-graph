// 助理上下文栏：展示"下一次发送上下文"的概要信息。
// 告诉用户：下一次向助理发送请求时，会带上哪些上下文（展示模式、作用域、节点数、改动数）。
// 同时提供可展开的完整方法签名。
import { analysisDisplayModeLabel } from "../labels";
import { Chip } from "../components/Chip";
import type { AnalysisDisplayMode, AssistantContextSnapshot } from "../types";
import { isClassDiagramAssistantContext } from "./assistantModels";

/** AssistantContextBar 组件的入参。 */
interface AssistantContextBarProps {
  /** 当前上下文快照。 */
  context: AssistantContextSnapshot;
}

/** 把展示模式字符串转为中文标签。 */
function displayModeLabel(mode?: string | null): string | null {
  if (!mode) {
    return null;
  }
  return analysisDisplayModeLabel(mode as AnalysisDisplayMode);
}

/** 把方法签名压缩为"Owner.method"形式（取最后两段）。 */
function compactMethodSignature(signature: string): string {
  const trimmed = signature.trim();
  const methodPath = trimmed.includes("(") ? trimmed.slice(0, trimmed.indexOf("(")) : trimmed;
  const pathParts = methodPath.split(".").filter(Boolean);
  if (pathParts.length >= 2) {
    return pathParts.slice(-2).join(".");
  }
  return methodPath || trimmed;
}

/** 从 scopeLabel 与 methodSignature 中提取可读的作用域标签。 */
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

/**
 * 助理上下文栏组件。
 *
 * 展示 chips：展示模式 + 作用域 + 节点数 + 改动数（类图模式不展示改动数）。
 * 可选展开完整方法签名（用于调试或确认上下文准确）。
 */
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
        {/* 类图模式不展示改动数 */}
        {isClassDiagram ? null : (
          <Chip variant="toolbar-chip" className="assistant-context-chip">改动 {context.selectedDiffItemIds.length}</Chip>
        )}
      </div>
      {/* 可展开的完整方法签名 */}
      {methodSignature ? (
        <details className="assistant-context-details">
          <summary>查看完整方法签名</summary>
          <code className="assistant-context-signature">{methodSignature}</code>
        </details>
      ) : null}
    </section>
  );
}

// 助理输入框组件：动作选择器 + 可选 QA 模式 / 解释粒度 + 文本输入 + 提交按钮。
// 这是用户与助理交互的主要入口——用户在这里选择动作、输入提示词、点击发送。
import type { AssistantActionId, AssistantIntent } from "./assistantTypes";
import type { AssistantContextSnapshot, QaMode, StepGranularity } from "../types";
import { Button } from "../components/Button";
import { qaModeLabel, stepGranularityLabel } from "../labels";
import { AssistantActionSelector } from "./AssistantActionSelector";
import { assistantComposerPlaceholder, isClassDiagramAssistantContext } from "./assistantModels";
import { assistantActionLabel } from "./assistantActionRegistry";

/** AssistantComposer 组件的入参。 */
interface AssistantComposerProps {
  /** 当前激活的意图。 */
  activeIntent: AssistantIntent;
  /** 当前激活的动作 ID。 */
  activeActionId: AssistantActionId;
  /** 当前上下文快照。 */
  context?: AssistantContextSnapshot | null;
  /** 输入框草稿文本。 */
  draft: string;
  /** 选中的 QA 模式。 */
  selectedQaMode?: QaMode | null;
  /** 选中的解释粒度。 */
  selectedExplanationGranularity?: StepGranularity;
  /** 是否可提交。 */
  canSubmit: boolean;
  /** 请求是否正在运行。 */
  requestRunning: boolean;
  /** 动作切换回调。 */
  onActionChange: (actionId: AssistantActionId) => void;
  /** QA 模式切换回调。 */
  onQaModeChange?: (mode: QaMode) => void;
  /** 解释粒度切换回调。 */
  onExplanationGranularityChange?: (granularity: StepGranularity) => void;
  /** 草稿变化回调。 */
  onDraftChange: (value: string) => void;
  /** 提交回调。 */
  onSubmit: () => void;
}

/** QA 模式可选值。 */
const QA_MODE_OPTIONS: QaMode[] = ["AUTO", "ANSWER", "REVIEW", "CHANGE"];
/** 解释粒度可选值。 */
const EXPLANATION_GRANULARITY_OPTIONS: StepGranularity[] = ["BUSINESS", "METHOD_CALL", "CODE_SEMANTIC"];

/**
 * 助理输入框组件。
 *
 * 布局（从上到下）：
 * - 当前动作标签；
 * - 动作选择器（按钮组）；
 * - QA 模式下拉框（仅 ASK_CONTEXT 动作时显示）；
 * - 解释粒度按钮组（仅 EXPLAIN_FLOW / EXPLAIN_STRUCTURE 时显示）；
 * - 文本输入区；
 * - 上下文提示 + 发送按钮。
 *
 * 类图模式下的文案有差异化（"AI 类图工作台" vs "AI 代码工作台"）。
 * 粘性定位（assistant-composer-sticky）让输入框始终可见。
 */
export function AssistantComposer({
  activeIntent,
  activeActionId,
  context,
  draft,
  selectedQaMode = "AUTO",
  selectedExplanationGranularity = "BUSINESS",
  canSubmit,
  requestRunning,
  onActionChange,
  onQaModeChange,
  onExplanationGranularityChange,
  onDraftChange,
  onSubmit,
}: AssistantComposerProps) {
  const activeActionLabel = assistantActionLabel(activeActionId, context);
  const isClassDiagram = isClassDiagramAssistantContext(context);
  // 文案按模式区分
  const inputLabel = isClassDiagram ? "AI 类图工作台输入框" : "AI 工作台输入框";
  const submitLabel = requestRunning
    ? isClassDiagram ? "AI 类图工作台处理中" : "AI 代码工作台处理中"
    : isClassDiagram ? "发送到 AI 类图工作台" : "发送到 AI 代码工作台";
  // 条件渲染：QA 模式仅在问答动作时显示
  const showQaMode = activeActionId === "ASK_CONTEXT";
  // 条件渲染：解释粒度仅在讲解动作时显示
  const showExplanationGranularity = activeActionId === "EXPLAIN_FLOW" || activeActionId === "EXPLAIN_STRUCTURE";

  return (
    <form
      className="assistant-composer assistant-composer-sticky"
      data-testid="assistant-composer"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
    >
      {/* 当前动作标签 */}
      <div className="assistant-send-type-head">
        <span className="tag active">{activeActionLabel}</span>
      </div>
      {/* 动作选择器 */}
      <AssistantActionSelector activeActionId={activeActionId} context={context} onActionChange={onActionChange} />
      {/* QA 模式下拉框 */}
      {showQaMode ? (
        <div className="assistant-composer-mode-row">
          <label className="assistant-mode-label" htmlFor="assistant-qa-mode">
            问答模式
          </label>
          <select
            id="assistant-qa-mode"
            aria-label="问答模式"
            value={selectedQaMode ?? "AUTO"}
            onChange={(event) => onQaModeChange?.(event.target.value as QaMode)}
          >
            {QA_MODE_OPTIONS.map((mode) => (
              <option key={mode} value={mode}>{qaModeLabel(mode)}</option>
            ))}
          </select>
        </div>
      ) : null}
      {/* 解释粒度按钮组 */}
      {showExplanationGranularity ? (
        <div className="assistant-composer-mode-row assistant-composer-granularity" role="group" aria-label="解释粒度">
          <span className="assistant-mode-label">解释粒度</span>
          <div className="workbench-granularity-group">
            {EXPLANATION_GRANULARITY_OPTIONS.map((granularity) => {
              const label = stepGranularityLabel(granularity);
              const isCurrent = selectedExplanationGranularity === granularity;
              return (
                <button
                  key={granularity}
                  type="button"
                  className={isCurrent ? "workbench-granularity active" : "workbench-granularity"}
                  aria-pressed={isCurrent}
                  disabled={requestRunning}
                  onClick={() => onExplanationGranularityChange?.(granularity)}
                >
                  {label}
                </button>
              );
            })}
          </div>
        </div>
      ) : null}
      {/* 文本输入区 */}
      <label className="sr-only" htmlFor="assistant-composer-input">{inputLabel}</label>
      <textarea
        id="assistant-composer-input"
        aria-label={inputLabel}
        rows={3}
        value={draft}
        placeholder={assistantComposerPlaceholder(activeIntent, context)}
        onChange={(event) => onDraftChange(event.target.value)}
      />
      {/* 上下文提示 + 发送按钮 */}
      <div className="assistant-composer-actions">
        <span className="muted">
          {isClassDiagram ? "下一次类图发送上下文随图谱选择更新" : "下一次发送上下文随图谱选择更新"}
        </span>
        <Button
          type="submit"
          variant="primary"
          disabled={!canSubmit}
          aria-label={submitLabel}
        >
          {requestRunning ? "处理中" : "发送"}
        </Button>
      </div>
    </form>
  );
}

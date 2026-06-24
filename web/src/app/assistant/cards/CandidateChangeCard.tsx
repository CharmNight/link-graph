import type { CandidateDraftChange } from "../../types";
import { Button } from "../../components/Button";

/** CandidateChangeCard 组件的入参。 */
interface CandidateChangeCardProps {
  /** 卡片标题；默认"候选草稿变更"。 */
  title?: string;
  /** 候选变更列表。 */
  changes: CandidateDraftChange[];
  /** 确认单个候选变更的回调。为空时不渲染确认按钮。 */
  onConfirmCandidateChange?: (changeId: string) => void;
}

/**
 * 候选草稿变更卡片。
 *
 * 在助理面板中展示 QA 生成的候选变更列表：
 * 每条变更显示标题 + 影响/原因摘要 + 可选"确认进草稿"按钮。
 *
 * 空列表不渲染任何内容。
 */
export function CandidateChangeCard({
  title = "候选草稿变更",
  changes,
  onConfirmCandidateChange,
}: CandidateChangeCardProps) {
  if (changes.length === 0) {
    return null;
  }
  return (
    <article className="assistant-turn-card assistant-turn-candidate-change">
      <div className="assistant-turn-head">
        <span className="assistant-turn-kind">{title}</span>
        <span className="muted">{changes.length} 项</span>
      </div>
      <div className="assistant-card-flow">
        {changes.map((change) => (
          <div key={change.changeId} className="assistant-evidence-item">
            <strong className="assistant-result-text">{change.title}</strong>
            {/* 影响摘要优先；缺失时用 reason */}
            <p className="muted assistant-result-text">{change.impactSummary || change.reason}</p>
            {onConfirmCandidateChange ? (
              <Button
                compact
                className="assistant-wrap-token"
                aria-label={`确认候选变更：${change.title}`}
                onClick={() => onConfirmCandidateChange(change.changeId)}
              >
                确认进草稿
              </Button>
            ) : null}
          </div>
        ))}
      </div>
    </article>
  );
}

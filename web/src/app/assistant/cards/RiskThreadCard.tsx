import type { InvestigationThread, RiskResolutionStatus } from "../../types";
import { Button } from "../../components/Button";

/** RiskThreadCard 组件的入参。 */
interface RiskThreadCardProps {
  /** 风险线程列表。 */
  threads: InvestigationThread[];
  /** 继续取证回调（按 threadId）。 */
  onInvestigateThread?: (threadId: string) => void;
  /** 化解风险回调（按 threadId + 状态）。 */
  onResolveThread?: (threadId: string, status: RiskResolutionStatus) => void;
}

/**
 * 风险线程卡片。
 *
 * 在助理面板中展示所有未化解的风险线程：
 * 每条线程显示标题、摘要、证据缺口，并提供操作按钮：
 * - 继续取证：对该线程发起 INVESTIGATE 模式的 QA 请求；
 * - 暂挂：标记为 DEFERRED（暂不处理）；
 * - 接受风险：标记为 ACCEPTED_RISK（已知风险，可接受）；
 * - 排除：标记为 DISMISSED（误报或不需要处理）。
 *
 * 空列表不渲染任何内容。
 */
export function RiskThreadCard({
  threads,
  onInvestigateThread,
  onResolveThread,
}: RiskThreadCardProps) {
  if (threads.length === 0) {
    return null;
  }
  return (
    <article className="assistant-turn-card assistant-turn-risk-thread">
      <div className="assistant-turn-head">
        <span className="assistant-turn-kind">风险线程</span>
        <span className="muted">{threads.length} 项</span>
      </div>
      <div className="assistant-card-flow">
        {threads.map((thread) => (
          <div key={thread.threadId} className="assistant-evidence-item">
            <strong className="assistant-result-text">{thread.title}</strong>
            <p className="assistant-result-text">{thread.summary}</p>
            <p className="muted assistant-result-text">{thread.evidenceGap}</p>
            <div className="panel-actions">
              {onInvestigateThread ? (
                <Button
                  compact
                  className="assistant-wrap-token"
                  aria-label={`继续取证：${thread.title}`}
                  onClick={() => onInvestigateThread(thread.threadId)}
                >
                  继续取证
                </Button>
              ) : null}
              {onResolveThread ? (
                <>
                  <Button
                    compact
                    className="assistant-wrap-token"
                    aria-label={`暂挂风险：${thread.title}`}
                    onClick={() => onResolveThread(thread.threadId, "DEFERRED")}
                  >
                    暂挂
                  </Button>
                  <Button
                    compact
                    className="assistant-wrap-token"
                    aria-label={`接受风险：${thread.title}`}
                    onClick={() => onResolveThread(thread.threadId, "ACCEPTED_RISK")}
                  >
                    接受风险
                  </Button>
                  <Button
                    compact
                    className="assistant-wrap-token"
                    aria-label={`排除风险：${thread.title}`}
                    onClick={() => onResolveThread(thread.threadId, "DISMISSED")}
                  >
                    排除
                  </Button>
                </>
              ) : null}
            </div>
          </div>
        ))}
      </div>
    </article>
  );
}

import type { InvestigationThread, RiskResolutionStatus } from "../../types";
import { Button } from "../../components/Button";

interface RiskThreadCardProps {
  threads: InvestigationThread[];
  onInvestigateThread?: (threadId: string) => void;
  onResolveThread?: (threadId: string, status: RiskResolutionStatus) => void;
}

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

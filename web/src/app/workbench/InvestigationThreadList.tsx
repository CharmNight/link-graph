import type { CSSProperties } from "react";
import {
  draftClaimTypeDescription,
  draftClaimTypeLabel,
  formatResultEvidenceReference,
  investigationThreadStatusLabel,
  investigationTurnOutcomeStatusLabel,
  riskResolutionStatusLabel,
  resultEvidenceLevelLabel,
} from "../labels";
import type { InvestigationThread, InvestigationTurnOutcome } from "../types";

interface InvestigationThreadListProps {
  threads: InvestigationThread[];
  latestTurnOutcome?: InvestigationTurnOutcome | null;
  recentTurnOutcomes?: InvestigationTurnOutcome[];
  selectedThreadId?: string | null;
  onSelectThread: (threadId: string) => void;
  onInvestigateThread: (threadId: string) => void;
  onDeferRisk: (threadId: string) => void;
  onAcceptRisk: (threadId: string) => void;
  onDismissRisk: (threadId: string) => void;
  compact?: boolean;
  style?: CSSProperties;
  showTitle?: boolean;
}

function resolveSelectedOutcome(
  threadId: string,
  latestTurnOutcome?: InvestigationTurnOutcome | null,
  recentTurnOutcomes: InvestigationTurnOutcome[] = [],
): InvestigationTurnOutcome | null {
  if (latestTurnOutcome?.threadId === threadId) {
    return latestTurnOutcome;
  }
  return [...recentTurnOutcomes].reverse().find((outcome) => outcome.threadId === threadId) ?? null;
}

export function InvestigationThreadList({
  threads,
  latestTurnOutcome,
  recentTurnOutcomes = [],
  selectedThreadId,
  onSelectThread,
  onInvestigateThread,
  onDeferRisk,
  onAcceptRisk,
  onDismissRisk,
  compact = false,
  style,
  showTitle = true,
}: InvestigationThreadListProps) {
  const selectedThread = threads.find((thread) => thread.threadId === selectedThreadId) ?? threads[0] ?? null;
  const selectedOutcome = selectedThread
    ? resolveSelectedOutcome(selectedThread.threadId, latestTurnOutcome, recentTurnOutcomes)
    : null;

  return (
    <section
      className={
        threads.length > 0
          ? compact ? "workbench-candidate-list compact" : "workbench-candidate-list"
          : "workbench-candidate-list is-empty"
      }
      style={style}
    >
      {showTitle ? (
        <div className="workbench-section-title-row">
          <h3>风险线程</h3>
          <span className="badge">{threads.length}</span>
        </div>
      ) : null}
      {threads.length > 0 ? (
        <div className={compact ? "workbench-candidate-content compact" : "workbench-candidate-content"}>
          <div className="workbench-candidate-selector" aria-label="风险线程列表">
            {threads.map((thread) => (
              <button
                key={thread.threadId}
                type="button"
                aria-pressed={selectedThread?.threadId === thread.threadId}
                className={selectedThread?.threadId === thread.threadId ? "workbench-candidate-tab active" : "workbench-candidate-tab"}
                onClick={() => onSelectThread(thread.threadId)}
                title={thread.title}
                aria-label={`风险线程：${thread.title}`}
              >
                <span className="workbench-candidate-tab-title">{thread.title}</span>
                <span className="workbench-status-pill">{investigationThreadStatusLabel(thread.status)}</span>
              </button>
            ))}
          </div>
          <div className="workbench-candidate-detail-pane">
            {selectedThread ? (
              <article className="workbench-candidate-card active">
                <div className="workbench-candidate-head">
                  <strong>{selectedThread.title}</strong>
                  <span className="workbench-status-pill">{investigationThreadStatusLabel(selectedThread.status)}</span>
                </div>
                <div className="workbench-candidate-meta">
                  <span className="toolbar-chip" title={draftClaimTypeDescription(selectedThread.claimType)}>
                    {draftClaimTypeLabel(selectedThread.claimType)}
                  </span>
                  <span className="workbench-status-pill" title="当前人工决策">
                    {riskResolutionStatusLabel(selectedThread.resolution?.status ?? "UNRESOLVED")}
                  </span>
                  <span className="muted">线程会持续保留，本轮是否推进以“本轮结果”为准。</span>
                </div>
                {selectedThread.resolution?.note?.trim() ? (
                  <p className="muted">备注：{selectedThread.resolution.note.trim()}</p>
                ) : null}
                {selectedOutcome ? (
                  <section className="workbench-candidate-evidence">
                    <strong>本轮结果</strong>
                    <div className="workbench-candidate-head">
                      <span>{selectedOutcome.summary || selectedThread.title}</span>
                      <span className="workbench-status-pill">
                        {investigationTurnOutcomeStatusLabel(selectedOutcome.status)}
                      </span>
                    </div>
                    <p>{selectedOutcome.detail || "当前没有额外说明。"}</p>
                    <dl className="workbench-before-after">
                      <div>
                        <dt>新增节点</dt>
                        <dd>{selectedOutcome.evidenceDelta.addedNodeIds.join("、") || "无"}</dd>
                      </div>
                      <div>
                        <dt>新增文件</dt>
                        <dd>{selectedOutcome.evidenceDelta.addedFilePaths.join("、") || "无"}</dd>
                      </div>
                      <div>
                        <dt>证据等级</dt>
                        <dd>
                          {selectedOutcome.evidenceDelta.previousStrongestEvidenceLevel ?? "无"}
                          {" -> "}
                          {selectedOutcome.evidenceDelta.currentStrongestEvidenceLevel ?? "无"}
                        </dd>
                      </div>
                    </dl>
                  </section>
                ) : null}
                <p>{selectedThread.summary || "当前没有补充说明。"}</p>
                <section className="workbench-candidate-evidence">
                  <strong>已观察到的证据</strong>
                  {selectedThread.evidence.length > 0 ? (
                    <ul className="answer-list">
                      {selectedThread.evidence.map((finding) => (
                        <li key={finding.id} className="workbench-candidate-evidence-item">
                          <strong>{resultEvidenceLevelLabel(finding.evidenceLevel)}</strong>
                          <span>{finding.claim}</span>
                          {finding.references.length > 0 ? (
                            <span className="muted">
                              {finding.references.map((reference) => formatResultEvidenceReference(reference)).join(" · ")}
                            </span>
                          ) : null}
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="muted">当前没有附带可追溯证据。</p>
                  )}
                </section>
                <dl className="workbench-before-after">
                  <div>
                    <dt>证据缺口</dt>
                    <dd>{selectedThread.evidenceGap || "未标注"}</dd>
                  </div>
                  <div>
                    <dt>建议下一问</dt>
                    <dd>{selectedThread.recommendedQuestion || "未标注"}</dd>
                  </div>
                </dl>
                <button
                  type="button"
                  className="primary-button"
                  onClick={() => onInvestigateThread(selectedThread.threadId)}
                >
                  继续取证
                </button>
                <div className="panel-actions">
                  <button type="button" className="ghost-button" onClick={() => onDeferRisk(selectedThread.threadId)}>
                    暂挂风险
                  </button>
                  <button type="button" className="ghost-button" onClick={() => onAcceptRisk(selectedThread.threadId)}>
                    接受风险
                  </button>
                  <button type="button" className="ghost-button" onClick={() => onDismissRisk(selectedThread.threadId)}>
                    排除风险
                  </button>
                </div>
              </article>
            ) : null}
          </div>
        </div>
      ) : (
        <div className="workbench-empty-card">
          <strong>当前没有风险线程</strong>
          <p className="muted">如果证据不足但值得继续追问，结果会在这里显示为持续取证线程。</p>
        </div>
      )}
    </section>
  );
}

import {
  bindingStatusLabel,
  certaintyLabel,
  formatResultEvidenceReference,
  investigationThreadStatusLabel,
  relationConfidenceLabel,
  resultEvidenceLevelLabel,
} from "../labels";
import type { ResultEvidenceLevel } from "../types";
import type { EvidencePanelState } from "./hybridDerivations";

interface EvidenceStagePanelProps {
  state: EvidencePanelState;
  isLoading: boolean;
  errorMessage?: string | null;
  onOpenQa: () => void;
  onSelectThread: (threadId: string) => void;
  onSelectCandidateChange: (changeId: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
}

const EVIDENCE_LEVELS: ResultEvidenceLevel[] = [
  "DIRECT_SOURCE",
  "DIRECT_GRAPH",
  "CALLSITE_ONLY",
  "NOT_OBSERVED",
];

export function EvidenceStagePanel({
  state,
  isLoading,
  errorMessage = null,
  onOpenQa,
  onSelectThread,
  onSelectCandidateChange,
  onRequestSourceNavigation,
}: EvidenceStagePanelProps) {
  const selectedNode = state.selectedNode;
  const evidenceCounts = EVIDENCE_LEVELS.map((level) => ({
    level,
    count: state.selectedNodeEvidence.filter((item) => item.level === level).length,
  }));

  return (
    <section className="evidence-stage">
      <header className="evidence-stage-head">
        <div>
          <p className="eyebrow">Evidence</p>
          <h2>核验证据</h2>
          <p className="muted">围绕选中节点核对证据强度、源码片段、风险线程和候选变更。</p>
        </div>
        <button type="button" className="primary-button" onClick={onOpenQa}>去问答</button>
      </header>

      {isLoading ? (
        <div className="evidence-stage-banner status-pill">正在整理证据，请稍候。</div>
      ) : null}
      {errorMessage ? (
        <div className="evidence-stage-banner risk-pill">{errorMessage}</div>
      ) : null}

      {!selectedNode ? (
        <div className="evidence-stage-empty">
          <strong>选择图谱节点后查看它的证据、风险和源码片段。</strong>
          <p className="muted">也可以直接去问答，围绕整张链路继续取证。</p>
        </div>
      ) : (
        <>
          <section className="evidence-stage-card evidence-stage-node-summary">
            <div className="evidence-stage-card-head">
              <div>
                <p className="eyebrow">{selectedNode.type}</p>
                <h3>{selectedNode.title}</h3>
              </div>
              <button
                type="button"
                className="ghost-button compact"
                onClick={() => onRequestSourceNavigation(selectedNode.id)}
              >
                跳源码：{selectedNode.title}
              </button>
            </div>
            <dl className="evidence-stage-node-grid">
              <div>
                <dt>确定性</dt>
                <dd>{certaintyLabel(selectedNode.certainty)}</dd>
              </div>
              <div>
                <dt>绑定</dt>
                <dd>{bindingStatusLabel(selectedNode.bindingStatus)}</dd>
              </div>
              <div>
                <dt>位置</dt>
                <dd>{selectedNode.location || "未提供"}</dd>
              </div>
              <div>
                <dt>签名</dt>
                <dd>{selectedNode.signature || "未提供"}</dd>
              </div>
            </dl>
          </section>

          <section className="evidence-stage-card">
            <div className="evidence-stage-card-head">
              <h3>证据强度</h3>
            </div>
            <div className="evidence-stage-levels">
              {evidenceCounts.map((item) => (
                <span key={item.level} className="status-pill">
                  {resultEvidenceLevelLabel(item.level)} {item.count}
                </span>
              ))}
            </div>
            {state.selectedNodeEvidence.length > 0 ? (
              <ul className="evidence-stage-list">
                {state.selectedNodeEvidence.map((item) => (
                  <li key={item.id}>
                    <strong>{item.label}</strong>
                    <span className="muted">{resultEvidenceLevelLabel(item.level)}</span>
                    {item.references.length > 0 ? (
                      <span className="muted">
                        {item.references.map((reference) => formatResultEvidenceReference(reference)).join(" · ")}
                      </span>
                    ) : null}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">当前没有可展示证据。可以先运行链路讲解或发起风险问答。</p>
            )}
          </section>

          <section className="evidence-stage-card">
            <div className="evidence-stage-card-head">
              <h3>架构索引关系</h3>
              <span className="app-pill">{state.relationEvidence.length}</span>
            </div>
            {state.relationEvidence.length > 0 ? (
              <ul className="evidence-stage-list">
                {state.relationEvidence.map((item) => (
                  <li key={item.id}>
                    <strong>{item.label}</strong>
                    <div className="evidence-stage-relation-meta">
                      {item.confidence ? (
                        <span className={`status-pill confidence-${item.confidence.toLowerCase()}`}>
                          {relationConfidenceLabel(item.confidence)}
                        </span>
                      ) : null}
                      {item.source ? <span className="status-pill">{item.source}</span> : null}
                      {item.resolverId ? <span className="status-pill">{item.resolverId}</span> : null}
                      {item.count ? <span className="app-pill">count {item.count}</span> : null}
                    </div>
                    {item.references.length > 0 ? (
                      <span className="muted">
                        {item.references.map((reference) => formatResultEvidenceReference(reference)).join(" · ")}
                      </span>
                    ) : null}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">当前选中节点没有架构索引关系证据。</p>
            )}
          </section>
        </>
      )}

      <section className="evidence-stage-card">
        <div className="evidence-stage-card-head">
          <h3>证据缺口</h3>
          <span className="app-pill">{state.evidenceGaps.length}</span>
        </div>
        {state.evidenceGaps.length > 0 ? (
          <ul className="evidence-stage-list">
            {state.evidenceGaps.map((gap) => (
              <li key={gap.id} className={`evidence-stage-gap evidence-stage-gap-${gap.severity}`}>
                <strong>{gap.title}</strong>
                <span>{gap.detail}</span>
                <span className="muted">{gap.targetNodeIds.join("、") || "未绑定节点"}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted">当前未发现阻塞证据缺口，可进入草稿确认。</p>
        )}
      </section>

      <section className="evidence-stage-card">
        <div className="evidence-stage-card-head">
          <h3>来源片段</h3>
          <span className="app-pill">{state.sourceSnippets.length}</span>
        </div>
        {state.sourceSnippets.length > 0 ? (
          <ul className="evidence-stage-list">
            {state.sourceSnippets.map((snippet) => (
              <li key={`${snippet.nodeId}:${snippet.filePath}:${snippet.startLine ?? ""}`}>
                <strong>{formatSnippetLocation(snippet.filePath, snippet.startLine, snippet.endLine)}</strong>
                {snippet.snippet ? <code>{snippet.snippet}</code> : <span className="muted">没有片段预览。</span>}
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted">当前没有源码片段上下文。</p>
        )}
      </section>

      <section className="evidence-stage-card">
        <div className="evidence-stage-card-head">
          <h3>证据 Trace</h3>
          <span className="app-pill">{state.evidenceTrace.length}</span>
        </div>
        {state.evidenceTrace.length > 0 ? (
          <ul className="evidence-stage-list">
            {state.evidenceTrace.map((trace) => (
              <li key={`${trace.nodeId}:${trace.filePath}:${trace.reason}`}>
                <strong>{trace.reason}</strong>
                <span className="muted">
                  {formatSnippetLocation(trace.filePath, trace.startLine, trace.endLine)}
                  {trace.includedInPrompt ? " · 已进入 prompt" : " · 未进入 prompt"}
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted">当前没有证据 Trace。</p>
        )}
      </section>

      <section className="evidence-stage-card">
        <div className="evidence-stage-card-head">
          <h3>风险线程</h3>
          <span className="risk-pill">{state.openRiskThreads.length}</span>
        </div>
        {state.openRiskThreads.length > 0 ? (
          <ul className="evidence-stage-list">
            {state.openRiskThreads.map((thread) => (
              <li key={thread.threadId}>
                <button
                  type="button"
                  className="evidence-stage-action-row"
                  aria-label={`选择风险线程：${thread.title}`}
                  onClick={() => onSelectThread(thread.threadId)}
                >
                  <strong>{thread.title}</strong>
                  <span className="status-pill">{investigationThreadStatusLabel(thread.status)}</span>
                </button>
                <span className="muted">{thread.evidenceGap || thread.summary}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted">当前没有打开的风险线程。</p>
        )}
      </section>

      <section className="evidence-stage-card">
        <div className="evidence-stage-card-head">
          <h3>候选变更</h3>
          <span className="app-pill">{state.pendingCandidateChanges.length}</span>
        </div>
        {state.pendingCandidateChanges.length > 0 ? (
          <ul className="evidence-stage-list">
            {state.pendingCandidateChanges.map((change) => (
              <li key={change.changeId}>
                <button
                  type="button"
                  className="evidence-stage-action-row"
                  aria-label={`选择候选变更：${change.title}`}
                  onClick={() => onSelectCandidateChange(change.changeId)}
                >
                  <strong>{change.title}</strong>
                  <span className="status-pill">{change.status}</span>
                </button>
                <span className="muted">{change.impactSummary || change.reason}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted">当前没有待确认候选变更。</p>
        )}
      </section>
    </section>
  );
}

function formatSnippetLocation(filePath: string, startLine?: number | null, endLine?: number | null): string {
  if (startLine == null) {
    return filePath;
  }
  if (endLine != null && endLine !== startLine) {
    return `${filePath}:${startLine}-${endLine}`;
  }
  return `${filePath}:${startLine}`;
}

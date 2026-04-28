import type { CSSProperties } from "react";
import {
  candidateChangeStatusLabel,
  draftClaimTypeDescription,
  draftClaimTypeLabel,
  formatResultEvidenceReference,
  resultEvidenceLevelLabel,
} from "../labels";
import type { CandidateDraftChange } from "../types";
import { candidateCanConfirm, candidateEvidence } from "./candidateChangeSupport";

interface CandidateChangeListProps {
  changes: CandidateDraftChange[];
  selectedChangeId?: string | null;
  onSelectChange: (changeId: string) => void;
  onConfirmChange: (changeId: string) => void;
  compact?: boolean;
  style?: CSSProperties;
  showTitle?: boolean;
}

export function CandidateChangeList({
  changes,
  selectedChangeId,
  onSelectChange,
  onConfirmChange,
  compact = false,
  style,
  showTitle = true,
}: CandidateChangeListProps) {
  const selectedChange = changes.find((change) => change.changeId === selectedChangeId) ?? changes[0] ?? null;
  const selectedChangeEvidence = selectedChange ? candidateEvidence(selectedChange) : [];
  const selectedChangeCanConfirm = selectedChange ? candidateCanConfirm(selectedChange) : false;
  const selectedChangeHasWriteScope = (selectedChange?.editScopes?.length ?? 0) > 0;
  const selectedChangeBeforeState = normalizeOptionalText(selectedChange?.beforeState);
  const selectedChangeAfterState = normalizeOptionalText(selectedChange?.afterState);
  const selectedChangeHasComparableState = selectedChangeBeforeState != null && selectedChangeAfterState != null;
  const selectedChangeHasSingleState = selectedChangeAfterState != null || selectedChangeBeforeState != null;

  return (
    <section
      className={
        changes.length > 0
          ? compact ? "workbench-candidate-list compact" : "workbench-candidate-list"
          : "workbench-candidate-list is-empty"
      }
      style={style}
    >
      {showTitle ? (
        <div className="workbench-section-title-row">
          <h3>待确认变更</h3>
          <span className="badge">{changes.length}</span>
        </div>
      ) : null}
      {changes.length > 0 ? (
        <>
          <div className={compact ? "workbench-candidate-content compact" : "workbench-candidate-content"}>
            <div className="workbench-candidate-selector" aria-label="待确认变更列表">
              {changes.map((change) => (
                <button
                  key={change.changeId}
                  type="button"
                  aria-pressed={selectedChange?.changeId === change.changeId}
                  className={selectedChange?.changeId === change.changeId ? "workbench-candidate-tab active" : "workbench-candidate-tab"}
                  onClick={() => onSelectChange(change.changeId)}
                  title={change.title}
                  aria-label={`待确认变更：${change.title}`}
                >
                  <span className="workbench-candidate-tab-title">{change.title}</span>
                  <span className="workbench-status-pill">{candidateChangeStatusLabel(change.status)}</span>
                </button>
              ))}
            </div>
            <div className="workbench-candidate-detail-pane">
              {selectedChange ? (
                <article className="workbench-candidate-card active">
                  <div className="workbench-candidate-head">
                    <strong>{selectedChange.title}</strong>
                    <span className="workbench-status-pill">{candidateChangeStatusLabel(selectedChange.status)}</span>
                  </div>
                  <div className="workbench-candidate-meta">
                    <span className="toolbar-chip" title={draftClaimTypeDescription(selectedChange.claimType)}>
                      {draftClaimTypeLabel(selectedChange.claimType)}
                    </span>
                    <span className="muted">
                      {selectedChangeCanConfirm
                        ? selectedChangeHasWriteScope
                          ? "已具备直接证据和精确写回范围，可确认进草稿。"
                          : "已具备直接证据，可确认进草稿；但尚未授权现有文件精确写回。"
                        : "当前只有弱证据，暂不能直接确认。"}
                    </span>
                  </div>
                  <p>{selectedChange.reason}</p>
                  {selectedChange.impactSummary ? <p className="muted">影响范围：{selectedChange.impactSummary}</p> : null}
                  <section className="workbench-candidate-evidence">
                    <strong>支撑证据</strong>
                    {selectedChangeEvidence.length > 0 ? (
                      <ul className="answer-list">
                        {selectedChangeEvidence.map((finding) => (
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
                      <p className="muted">当前没有附带可追溯证据，不能直接确认。</p>
                    )}
                  </section>
                  {selectedChangeHasComparableState ? (
                    <dl className="workbench-before-after">
                      <div>
                        <dt>修改前</dt>
                        <dd>{selectedChangeBeforeState}</dd>
                      </div>
                      <div>
                        <dt>修改后</dt>
                        <dd>{selectedChangeAfterState}</dd>
                      </div>
                    </dl>
                  ) : selectedChangeHasSingleState ? (
                    <section className="workbench-step-section">
                      <h4>{selectedChangeAfterState != null ? "修改后" : "当前定位源码"}</h4>
                      <div className="workbench-draft-single-state">{selectedChangeAfterState ?? selectedChangeBeforeState}</div>
                    </section>
                  ) : (
                    <section className="workbench-step-section">
                      <h4>变更意图</h4>
                      <div className="workbench-draft-intent-card">
                        <strong>当前阶段已锁定源码位置，但还没有生成具体代码 diff。</strong>
                        <p className="muted">确认后会进入草稿层，后续再基于写回边界生成精确 diff。</p>
                      </div>
                    </section>
                  )}
                  <section className="workbench-candidate-evidence">
                    <strong>代码写回边界</strong>
                    {selectedChangeHasWriteScope ? (
                      <ul className="answer-list">
                        {selectedChange.editScopes?.map((scope) => (
                          <li key={scope.scopeId} className="workbench-candidate-evidence-item">
                            <strong>{formatEditScopeLocation(scope.filePath, scope.startLine, scope.endLine)}</strong>
                            {scope.symbolSignature ? <span className="workbench-edit-scope-signature">{scope.symbolSignature}</span> : null}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p className="muted">当前只有读证据，尚未授权现有文件精确写回。</p>
                    )}
                  </section>
                  <button
                    type="button"
                    className="primary-button"
                    aria-label={selectedChangeCanConfirm ? "确认这条变更" : "证据不足，暂不能确认这条变更"}
                    disabled={!selectedChangeCanConfirm}
                    onClick={() => {
                      if (selectedChangeCanConfirm) {
                        onConfirmChange(selectedChange.changeId);
                      }
                    }}
                  >
                    {selectedChangeCanConfirm ? "确认这条变更" : "证据不足，暂不能确认"}
                  </button>
                </article>
              ) : null}
            </div>
          </div>
        </>
      ) : (
        <div className="workbench-empty-card">
          <strong>当前没有待确认变更</strong>
          <p className="muted">当前没有待确认变更；如果本轮只是解释链路，这里会保持为空。</p>
        </div>
      )}
    </section>
  );
}

function normalizeOptionalText(value?: string | null): string | null {
  const normalized = value?.trim();
  return normalized ? normalized : null;
}

function formatEditScopeLocation(
  filePath: string,
  startLine?: number | null,
  endLine?: number | null,
): string {
  if (startLine == null) {
    return filePath;
  }
  if (endLine != null && endLine !== startLine) {
    return `${filePath}:${startLine}-${endLine}`;
  }
  return `${filePath}:${startLine}`;
}

import type { CodeDiffStatus } from "./hybridDerivations";

interface ChangeTrayProps {
  pendingCandidateCount: number;
  confirmedDraftCount: number;
  blockingRiskCount: number;
  syncStatusLabel: string;
  codeDiffStatus: CodeDiffStatus;
  canApply: boolean;
  canRevert: boolean;
  onOpenDraft: () => void;
  onOpenDraftCompare: () => void;
  onOpenCode: () => void;
  onApply: () => void;
  onRevert: () => void;
}

export function ChangeTray({
  pendingCandidateCount,
  confirmedDraftCount,
  blockingRiskCount,
  syncStatusLabel,
  codeDiffStatus,
  canApply,
  canRevert,
  onOpenDraft,
  onOpenDraftCompare,
  onOpenCode,
  onApply,
  onRevert,
}: ChangeTrayProps) {
  return (
    <footer className="change-tray" role="contentinfo" aria-label="变更托盘">
      <div className="change-tray-summary">
        <span className="app-pill">候选 {pendingCandidateCount}</span>
        <span className="app-pill">草稿 {confirmedDraftCount}</span>
        <span className={blockingRiskCount > 0 ? "risk-pill" : "status-pill"}>阻塞 {blockingRiskCount}</span>
        <span className="status-pill">{codeDiffStatusLabel(codeDiffStatus)}</span>
        <span className="change-tray-sync">{syncStatusLabel}</span>
      </div>
      <div className="change-tray-actions">
        <button type="button" className="ghost-button" onClick={onOpenDraft}>查看草稿</button>
        <button type="button" className="ghost-button" onClick={onOpenDraftCompare}>查看流程变化</button>
        <button type="button" className="ghost-button" onClick={onOpenCode}>进入代码</button>
        <button type="button" className="primary-button" disabled={!canApply} onClick={onApply}>应用全部</button>
        <button type="button" className="danger-button" disabled={!canRevert} onClick={onRevert}>回退</button>
      </div>
    </footer>
  );
}

function codeDiffStatusLabel(status: CodeDiffStatus): string {
  switch (status) {
    case "FRESH":
      return "Diff 已就绪";
    case "RUNNING":
      return "Diff 生成中";
    case "STALE":
      return "Diff 已过期";
    case "FAILED":
      return "Diff 失败";
    case "MISSING":
    default:
      return "Diff 未生成";
  }
}

import type { CodeDiffStatus } from "./hybridDerivations";
import { Button } from "./Button";
import { Chip } from "./Chip";

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
        <Chip variant="app-pill">候选 {pendingCandidateCount}</Chip>
        <Chip variant="app-pill">草稿 {confirmedDraftCount}</Chip>
        <Chip variant={blockingRiskCount > 0 ? "risk-pill" : "status-pill"}>阻塞 {blockingRiskCount}</Chip>
        <Chip variant="status-pill">{codeDiffStatusLabel(codeDiffStatus)}</Chip>
        <span className="change-tray-sync">{syncStatusLabel}</span>
      </div>
      <div className="change-tray-actions">
        <Button onClick={onOpenDraft}>查看草稿</Button>
        <Button onClick={onOpenDraftCompare}>查看流程变化</Button>
        <Button onClick={onOpenCode}>进入代码</Button>
        <Button variant="danger" disabled={!canRevert} onClick={onRevert}>回退</Button>
        <Button variant="primary" disabled={!canApply} onClick={onApply}>应用全部</Button>
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

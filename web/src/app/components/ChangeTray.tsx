import type { CodeDiffStatus } from "./hybridDerivations";
import { Button } from "./Button";
import { Chip } from "./Chip";

/** ChangeTray 组件的入参。 */
interface ChangeTrayProps {
  /** 待确认候选数。 */
  pendingCandidateCount: number;
  /** 已确认草稿数。 */
  confirmedDraftCount: number;
  /** 阻塞风险数。 */
  blockingRiskCount: number;
  /** 同步状态文案。 */
  syncStatusLabel: string;
  /** 代码 diff 状态。 */
  codeDiffStatus: CodeDiffStatus;
  /** 是否允许写入工程。 */
  canApply: boolean;
  /** 是否允许回退。 */
  canRevert: boolean;
  /** 打开改动列表回调。 */
  onOpenDraft: () => void;
  /** 打开流程变化对比回调。 */
  onOpenDraftCompare: () => void;
  /** 进入代码视图回调。 */
  onOpenCode: () => void;
  /** 写入工程回调。 */
  onApply: () => void;
  /** 回退回调。 */
  onRevert: () => void;
}

/**
 * 变更托盘：图谱工作台底部的状态栏 + 操作栏。
 *
 * 左侧用 Chip 列表展示当前各种状态指标（候选/草稿/阻塞/diff/同步）；
 * 右侧提供主要操作按钮（查看列表、对比、写代码、回退、写入工程）。
 *
 * 阻塞风险数大于 0 时改用 risk-pill 视觉强调。
 */
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
        {/* 阻塞数 > 0 时用 risk-pill 红色强调 */}
        <Chip variant={blockingRiskCount > 0 ? "risk-pill" : "status-pill"}>阻塞 {blockingRiskCount}</Chip>
        <Chip variant="status-pill">{codeDiffStatusLabel(codeDiffStatus)}</Chip>
        <span className="change-tray-sync">{syncStatusLabel}</span>
      </div>
      <div className="change-tray-actions">
        <Button onClick={onOpenDraft}>改动列表</Button>
        <Button onClick={onOpenDraftCompare}>查看流程变化</Button>
        <Button onClick={onOpenCode}>进入代码</Button>
        {/* 回退按钮：禁用态由 canRevert 控制 */}
        <Button variant="danger" disabled={!canRevert} onClick={onRevert}>回退</Button>
        {/* 主操作：写入工程，禁用态由 canApply 控制 */}
        <Button variant="primary" disabled={!canApply} onClick={onApply}>写入工程</Button>
      </div>
    </footer>
  );
}

/** 把代码 diff 状态枚举转为中文标签。 */
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

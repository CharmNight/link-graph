import {
  analysisDisplayModeLabel,
  certaintyLabel,
  draftCompareStatusLabel,
  sourceTagLabel,
} from "../labels";
import type {
  AnalysisDisplayMode,
  DraftCompareProjection,
  GraphSourceTag,
  LinkGraphDocument,
} from "../types";
import type { CodeDiffStatus } from "./hybridDerivations";

interface GraphStageFooterProps {
  analysisDisplayMode: AnalysisDisplayMode;
  activeViewGraph: LinkGraphDocument;
  fullNodeCount: number;
  hasExplanationFocus: boolean;
  draftChangedNodeCount: number;
  draftCompareProjection?: DraftCompareProjection | null;
  codeDiffStatus: CodeDiffStatus;
}

export function GraphStageFooter({
  analysisDisplayMode,
  activeViewGraph,
  fullNodeCount,
  hasExplanationFocus,
  draftChangedNodeCount,
  draftCompareProjection = null,
  codeDiffStatus,
}: GraphStageFooterProps) {
  const sourceTags = Array.from(new Set(
    activeViewGraph.nodes
      .map((node) => node.sourceTag)
      .filter((tag): tag is GraphSourceTag => tag != null),
  ));
  const compareStatuses = draftCompareProjection == null
    ? []
    : Array.from(new Set([
      ...Object.values(draftCompareProjection.nodeStatuses),
      ...Object.values(draftCompareProjection.edgeStatuses),
    ]));
  const visibleNodeCount = activeViewGraph.nodeCount ?? activeViewGraph.nodes.length;
  const resolvedFullNodeCount = Math.max(fullNodeCount, visibleNodeCount);

  return (
    <footer className="graph-stage-footer" aria-label="图谱图例">
      <span className="status-pill">{analysisDisplayModeLabel(analysisDisplayMode)}</span>
      <span className="status-pill">{certaintyLabel("PROVEN")}</span>
      <span className="status-pill">{certaintyLabel("RULE_INFERRED")}</span>
      <span className="status-pill">{certaintyLabel("LLM_SUGGESTED")}</span>
      {sourceTags.map((tag) => (
        <span key={tag} className="status-pill">{sourceTagLabel(tag)}</span>
      ))}
      {hasExplanationFocus ? <span className="app-pill">讲解焦点</span> : null}
      {draftChangedNodeCount > 0 ? <span className="app-pill">草稿变更 {draftChangedNodeCount}</span> : null}
      {compareStatuses.map((status) => (
        <span key={status} className="app-pill">{draftCompareStatusLabel(status)}</span>
      ))}
      <span className="status-pill">节点 {visibleNodeCount} / {resolvedFullNodeCount}</span>
      <span className="status-pill">代码 diff {codeDiffStatusLabel(codeDiffStatus)}</span>
    </footer>
  );
}

function codeDiffStatusLabel(status: CodeDiffStatus): string {
  switch (status) {
    case "RUNNING":
      return "生成中";
    case "FRESH":
      return "已生成";
    case "STALE":
      return "已过期";
    case "FAILED":
      return "失败";
    case "MISSING":
    default:
      return "未生成";
  }
}

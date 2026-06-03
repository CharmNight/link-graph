import {
  analysisDisplayModeLabel,
  certaintyLabel,
  diffStatusLabel,
  draftCompareStatusLabel,
  edgeTypeLabel,
  nodeTypeLabel,
} from "../labels";
import type { AnalysisDisplayMode, DraftCompareProjection } from "../types";
import { CLASS_DIAGRAM_RELATION_LEGEND_ITEMS } from "../views/class-diagram/classDiagramRelations";

interface LegendProps {
  analysisDisplayMode: AnalysisDisplayMode;
  hasExplanationFocus?: boolean;
  hasDraftChanges?: boolean;
  draftCompareProjection?: DraftCompareProjection | null;
}

export function Legend({
  analysisDisplayMode,
  hasExplanationFocus = false,
  hasDraftChanges = false,
  draftCompareProjection = null,
}: LegendProps) {
  const modeBadges = (() => {
    switch (analysisDisplayMode) {
      case "FLOWCHART":
        return [
          nodeTypeLabel("FLOW_ACTION"),
          nodeTypeLabel("TERMINAL"),
          nodeTypeLabel("MERGE"),
          edgeTypeLabel("CONTROL_FLOW"),
        ];
      case "RESOURCE_RELATION_VIEW":
        return [
          nodeTypeLabel("DOC_PAGE"),
          nodeTypeLabel("CONFIG_ITEM"),
          nodeTypeLabel("XML_RESOURCE"),
          edgeTypeLabel("BINDS_CONFIG"),
          edgeTypeLabel("LINKS_DOC"),
        ];
      case "ARCHITECTURE_GRAPH":
        return [
          nodeTypeLabel("MODULE"),
          nodeTypeLabel("COMPONENT"),
          nodeTypeLabel("SERVICE"),
          nodeTypeLabel("LAYER"),
          nodeTypeLabel("RESOURCE"),
        ];
      case "CLASS_DIAGRAM":
        return [
          nodeTypeLabel("CLASS"),
          nodeTypeLabel("INTERFACE"),
          nodeTypeLabel("ENUM"),
          "抽象类",
        ];
      case "REVIEW_GRAPH":
        return [
          nodeTypeLabel("CLASS"),
          nodeTypeLabel("METHOD"),
          edgeTypeLabel("USES_TYPE"),
          edgeTypeLabel("REFLECTS_TO"),
          edgeTypeLabel("SPI_RESOLVES_TO"),
        ];
      case "FACT_GRAPH":
      default:
        return [
          nodeTypeLabel("METHOD"),
          nodeTypeLabel("FLOW_ACTION"),
          nodeTypeLabel("DOC_PAGE"),
          edgeTypeLabel("CALL"),
        ];
    }
  })();
  const compareStatuses = draftCompareProjection == null
    ? []
    : Array.from(new Set([
      ...Object.values(draftCompareProjection.nodeStatuses),
      ...Object.values(draftCompareProjection.edgeStatuses),
    ]));
  const relationBadges = analysisDisplayMode === "CLASS_DIAGRAM"
    ? CLASS_DIAGRAM_RELATION_LEGEND_ITEMS
    : [];

  return (
    <section className="legend-panel" aria-label="图例">
      <span className="badge legend-mode-badge">{analysisDisplayModeLabel(analysisDisplayMode)}</span>
      <span className="badge certainty-proven">{certaintyLabel("PROVEN")}</span>
      <span className="badge certainty-rule_inferred">{certaintyLabel("RULE_INFERRED")}</span>
      <span className="badge certainty-llm_suggested">{certaintyLabel("LLM_SUGGESTED")}</span>
      <span className="badge diff-modified">{diffStatusLabel("MODIFIED")}</span>
      {hasExplanationFocus ? <span className="badge legend-highlight-badge explanation-focus">蓝环：当前讲解步骤</span> : null}
      {hasDraftChanges ? <span className="badge legend-highlight-badge draft-change">橙环：草稿变更节点</span> : null}
      {draftCompareProjection ? <span className="badge legend-highlight-badge draft-compare">单图草稿对比</span> : null}
      {compareStatuses.map((status) => (
        <span key={status} className={`badge draft-compare-${status.toLowerCase()}`}>{draftCompareStatusLabel(status)}</span>
      ))}
      {modeBadges.map((badge) => (
        <span key={badge} className="badge legend-structure-badge">{badge}</span>
      ))}
      {relationBadges.map((item) => (
        <span key={item.id} className={`badge legend-relation-badge legend-relation-${item.role}`}>{item.label}</span>
      ))}
    </section>
  );
}

import { analysisDisplayModeLabel, certaintyLabel, diffStatusLabel, edgeTypeLabel, nodeTypeLabel } from "../labels";
import type { AnalysisDisplayMode } from "../types";

interface LegendProps {
  analysisDisplayMode: AnalysisDisplayMode;
  hasExplanationFocus?: boolean;
  hasDraftChanges?: boolean;
}

export function Legend({ analysisDisplayMode, hasExplanationFocus = false, hasDraftChanges = false }: LegendProps) {
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

  return (
    <section className="legend-panel" aria-label="图例">
      <span className="badge legend-mode-badge">{analysisDisplayModeLabel(analysisDisplayMode)}</span>
      <span className="badge certainty-proven">{certaintyLabel("PROVEN")}</span>
      <span className="badge certainty-rule_inferred">{certaintyLabel("RULE_INFERRED")}</span>
      <span className="badge certainty-llm_suggested">{certaintyLabel("LLM_SUGGESTED")}</span>
      <span className="badge diff-modified">{diffStatusLabel("MODIFIED")}</span>
      {hasExplanationFocus ? <span className="badge legend-highlight-badge explanation-focus">蓝环：当前讲解步骤</span> : null}
      {hasDraftChanges ? <span className="badge legend-highlight-badge draft-change">橙环：草稿变更节点</span> : null}
      {modeBadges.map((badge) => (
        <span key={badge} className="badge legend-structure-badge">{badge}</span>
      ))}
    </section>
  );
}

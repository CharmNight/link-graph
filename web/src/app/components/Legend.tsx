export function Legend() {
  return (
    <section className="legend-panel">
      <span className="badge certainty-proven">PROVEN</span>
      <span className="badge certainty-rule_inferred">RULE_INFERRED</span>
      <span className="badge certainty-llm_suggested">LLM_SUGGESTED</span>
      <span className="badge diff-modified">MODIFIED</span>
    </section>
  );
}

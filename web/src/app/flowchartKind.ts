import type { LinkGraphNode } from "./types";

const DECISION_FLOW_SCOPE_KINDS = new Set([
  "IF",
  "SWITCH",
  "FOREACH",
  "FOR",
  "WHILE",
  "DO_WHILE",
]);

function normalizedFlowKind(node: Pick<LinkGraphNode, "metadata">): string {
  return node.metadata?.["flow.kind"]?.trim().toUpperCase() ?? "";
}

export function resolveFlowchartKind(node?: Pick<LinkGraphNode, "type" | "metadata"> | null): string {
  if (!node) {
    return "PROCESS";
  }
  if (node.type === "FLOW_SCOPE" && DECISION_FLOW_SCOPE_KINDS.has(normalizedFlowKind(node))) {
    return "DECISION";
  }
  if (node.type === "MERGE") {
    return "MERGE";
  }
  if (node.type === "TERMINAL") {
    return "TERMINAL";
  }
  return node.metadata?.["flowchart.kind"] ?? "PROCESS";
}

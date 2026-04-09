import type { LinkGraphNode } from "./types";

export const DEFAULT_NODE_CARD_WIDTH = 408;
export const FLOW_ACTION_NODE_CARD_WIDTH = 324;
export const FLOW_DECISION_NODE_CARD_WIDTH = 368;

export const FLOWCHART_PROCESS_WIDTH = 324;
export const FLOWCHART_DECISION_WIDTH = 272;
export const FLOWCHART_DECISION_MIN_HEIGHT = 228;
export const FLOWCHART_ENTRY_WIDTH = 324;
export const FLOWCHART_TERMINAL_WIDTH = 280;
export const FLOWCHART_MERGE_WIDTH = 132;

export function nodeCardWidth(node: Pick<LinkGraphNode, "type" | "metadata">): number {
  if (node.type === "FLOW_ACTION") {
    return FLOW_ACTION_NODE_CARD_WIDTH;
  }
  if (node.type === "FLOW_SCOPE" && node.metadata?.["flow.kind"] === "IF") {
    return FLOW_DECISION_NODE_CARD_WIDTH;
  }
  return DEFAULT_NODE_CARD_WIDTH;
}

export function flowchartNodeCardWidth(node: Pick<LinkGraphNode, "type" | "metadata">): number {
  const kind = node.metadata?.["flowchart.kind"] ?? "PROCESS";
  switch (kind) {
    case "DECISION":
      return FLOWCHART_DECISION_WIDTH;
    case "MERGE":
      return FLOWCHART_MERGE_WIDTH;
    case "TERMINAL":
      return FLOWCHART_TERMINAL_WIDTH;
    case "ENTRY":
      return FLOWCHART_ENTRY_WIDTH;
    default:
      return FLOWCHART_PROCESS_WIDTH;
  }
}

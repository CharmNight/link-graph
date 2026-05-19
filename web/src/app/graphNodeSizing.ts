import { resolveFlowchartKind } from "./flowchartKind";
import type { LinkGraphNode } from "./types";

export const DEFAULT_NODE_CARD_WIDTH = 408;
export const CLASS_DIAGRAM_NODE_CARD_WIDTH = 360;
export const FLOW_ACTION_NODE_CARD_WIDTH = 324;
export const FLOW_DECISION_NODE_CARD_WIDTH = 368;

export const FLOWCHART_PROCESS_WIDTH = 324;
export const FLOWCHART_DECISION_WIDTH = 272;
export const FLOWCHART_DECISION_MIN_HEIGHT = 228;
export const FLOWCHART_ENTRY_WIDTH = 324;
export const FLOWCHART_TERMINAL_WIDTH = 280;
export const FLOWCHART_MERGE_WIDTH = 132;

function isLongCodeLikeTitle(title?: string | null): boolean {
  const normalizedTitle = title?.trim() ?? "";
  if (normalizedTitle.length < 28) {
    return false;
  }
  return normalizedTitle.includes("(")
    || normalizedTitle.includes(")")
    || normalizedTitle.includes(".")
    || normalizedTitle.includes("==")
    || /[a-z][A-Z]/.test(normalizedTitle);
}

function extraFlowchartNodeWidth(node: Pick<LinkGraphNode, "type" | "metadata" | "title">): number {
  if (!isLongCodeLikeTitle(node.title)) {
    return 0;
  }
  const kind = resolveFlowchartKind(node);
  if (kind === "DECISION") {
    return 88;
  }
  if (kind === "PROCESS" || kind === "SUBROUTINE") {
    return 56;
  }
  return 0;
}

export function nodeCardWidth(node: Pick<LinkGraphNode, "type" | "metadata" | "title">): number {
  if (node.type === "FLOW_ACTION") {
    return FLOW_ACTION_NODE_CARD_WIDTH;
  }
  if (node.type === "FLOW_SCOPE" && node.metadata?.["flow.kind"] === "IF") {
    return FLOW_DECISION_NODE_CARD_WIDTH;
  }
  return DEFAULT_NODE_CARD_WIDTH;
}

export function classDiagramNodeCardWidth(): number {
  return CLASS_DIAGRAM_NODE_CARD_WIDTH;
}

export function flowchartNodeCardWidth(node: Pick<LinkGraphNode, "type" | "metadata" | "title">): number {
  const kind = resolveFlowchartKind(node);
  switch (kind) {
    case "DECISION":
      return FLOWCHART_DECISION_WIDTH + extraFlowchartNodeWidth(node);
    case "MERGE":
      return FLOWCHART_MERGE_WIDTH;
    case "TERMINAL":
      return FLOWCHART_TERMINAL_WIDTH;
    case "ENTRY":
      return FLOWCHART_ENTRY_WIDTH;
    default:
      return FLOWCHART_PROCESS_WIDTH + extraFlowchartNodeWidth(node);
  }
}

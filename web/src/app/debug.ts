import type { LinkGraphBootstrapState, LinkGraphDocument, LinkGraphNode } from "./types";
import { resolveCurrentSceneState, resolveSemanticFactGraph, resolveWorkingGraph } from "./sampleState";

declare global {
  interface Window {
    __linkGraphTraceBuffer?: string[];
    __linkGraphTraceHistory?: string[];
    __linkGraphLastTrace?: string;
    __linkGraphDebugEnabled?: boolean;
    __linkGraphInteractionProbe?: boolean;
    linkGraphDebugTrace?: (payload: string) => void;
  }
}

const TRACE_HISTORY_LIMIT = 24;

function nowValue(): number {
  return typeof performance !== "undefined" ? performance.now() : Date.now();
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}

function sampleNodeIds(nodes: LinkGraphNode[]): string[] {
  return nodes.slice(0, 6).map((node) => node.id);
}

export function summarizeReuse(totalCount: number, reusedCount: number) {
  return {
    totalCount,
    reusedCount,
    rebuiltCount: Math.max(totalCount - reusedCount, 0),
    reuseRatio: totalCount === 0 ? 1 : round(reusedCount / totalCount),
  };
}

export function measureStart(): number {
  return nowValue();
}

export function measureDuration(startedAt: number): number {
  return round(nowValue() - startedAt);
}

export function summarizeGraph(document?: LinkGraphDocument | null) {
  if (!document) {
    return {
      nodes: 0,
      edges: 0,
      truncated: false,
      sampleNodeIds: [],
    };
  }
  return {
    nodes: document.nodes.length,
    edges: document.edges.length,
    truncated: document.truncated ?? false,
    nodeCountHint: document.nodeCount ?? null,
    edgeCountHint: document.edgeCount ?? null,
    sampleNodeIds: sampleNodeIds(document.nodes),
  };
}

export function summarizeBootstrapState(state: LinkGraphBootstrapState) {
  const currentSceneState = resolveCurrentSceneState(state);
  return {
    currentSceneId: state.currentSceneId,
    workspaceGraph: summarizeGraph(resolveWorkingGraph(state)),
    workspaceBaseGraph: summarizeGraph(state.workspaceBaseGraph),
    semanticFactGraph: summarizeGraph(resolveSemanticFactGraph(state)),
    designBaselineGraph: summarizeGraph(state.designBaselineGraph),
    sourceNavigationState: state.sourceNavigationState?.phase ?? "IDLE",
    selectedNodeId: currentSceneState.selectedNodeId ?? null,
    layoutRevision: currentSceneState.layoutRevision,
    operationFeedback: state.operationFeedback?.message ?? null,
    lastMessageType: state.lastMessageType ?? null,
    lastGraphSource: state.lastGraphSource ?? null,
  };
}

export function traceLinkGraph(event: string, payload?: unknown): void {
  const tracingEnabled = window.__linkGraphDebugEnabled === true || typeof window.linkGraphDebugTrace === "function";
  if (!tracingEnabled) {
    return;
  }
  const message = {
    time: new Date().toISOString(),
    event,
    payload: payload ?? null,
  };
  try {
    const serialized = JSON.stringify(message);
    window.__linkGraphLastTrace = serialized;
    window.__linkGraphTraceHistory = window.__linkGraphTraceHistory ?? [];
    window.__linkGraphTraceHistory.push(serialized);
    if (window.__linkGraphTraceHistory.length > TRACE_HISTORY_LIMIT) {
      window.__linkGraphTraceHistory.splice(0, window.__linkGraphTraceHistory.length - TRACE_HISTORY_LIMIT);
    }
    if (window.linkGraphDebugTrace) {
      window.linkGraphDebugTrace(serialized);
      return;
    }
    window.__linkGraphTraceBuffer = window.__linkGraphTraceBuffer ?? [];
    window.__linkGraphTraceBuffer.push(serialized);
  } catch (error) {
    const fallbackMessage = JSON.stringify({
      time: new Date().toISOString(),
      event: "trace.stringifyFailed",
      payload: {
        sourceEvent: event,
        error: String(error),
      },
    });
    window.__linkGraphLastTrace = fallbackMessage;
    window.__linkGraphTraceHistory = window.__linkGraphTraceHistory ?? [];
    window.__linkGraphTraceHistory.push(fallbackMessage);
    if (window.__linkGraphTraceHistory.length > TRACE_HISTORY_LIMIT) {
      window.__linkGraphTraceHistory.splice(0, window.__linkGraphTraceHistory.length - TRACE_HISTORY_LIMIT);
    }
    if (window.linkGraphDebugTrace) {
      window.linkGraphDebugTrace(fallbackMessage);
      return;
    }
    window.__linkGraphTraceBuffer = window.__linkGraphTraceBuffer ?? [];
    window.__linkGraphTraceBuffer.push(fallbackMessage);
  }
}

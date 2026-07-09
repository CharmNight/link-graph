// 调试埋点与状态摘要工具：把链路图谱运行时的关键事件、状态快照序列化并暂存到 window 上，
// 便于开发期通过浏览器控制台或桥接通道回放、调试。
import type { LinkGraphBootstrapState, LinkGraphDocument, LinkGraphNode } from "./types";
import { resolveCurrentSceneState, resolveSemanticFactGraph, resolveWorkingGraph } from "./sampleState";

/**
 * 把调试相关字段挂到 window 上，避免污染全局类型定义。
 * 这些字段都属于开发期工具使用的内部 API，不应在生产代码中读取。
 */
declare global {
  interface Window {
    /** 序列化后的最新一条埋点消息，便于控制台快速查看。 */
    __linkGraphTraceBuffer?: string[];
    /** 环形保留的最近若干条埋点历史，用于回放与排查。 */
    __linkGraphTraceHistory?: string[];
    /** 最近一次埋点的序列化字符串，单值便于断点查看。 */
    __linkGraphLastTrace?: string;
    /** 是否开启详细调试埋点。仅当为 true 时 traceLinkGraph 才真正记录。 */
    __linkGraphDebugEnabled?: boolean;
    /** 是否开启交互探针（记录用户在图谱上的操作序列）。 */
    __linkGraphInteractionProbe?: boolean;
    /** 外部注入的埋点接收函数；存在时埋点会同时通过该回调上报。 */
    linkGraphDebugTrace?: (payload: string) => void;
  }
}

/** 埋点历史的环形上限，避免内存无限增长。 */
const TRACE_HISTORY_LIMIT = 24;
/** 单条埋点序列化后允许的最大字符数，超出将被截断为占位结构，避免桥接通道被巨型 payload 阻塞。 */
const TRACE_BRIDGE_PAYLOAD_MAX_CHARS = 60 * 1024;
/** 噪声较大的前端埋点事件名集合。这些事件触发频率高、信息密度低，记录后会污染历史，故直接忽略。 */
const NOISY_FRONTEND_TRACE_EVENTS = new Set([
  "flowchartView.runtimeHandles",
  "flowchartView.renderState",
  "graphFlowSurface.viewport.graphEffect",
  "routedEdge.render",
]);

/**
 * 取当前高精度时间戳，用于埋点耗时统计。
 * 优先使用 performance.now()（更高精度），降级到 Date.now()。
 */
function nowValue(): number {
  return typeof performance !== "undefined" ? performance.now() : Date.now();
}

/** 把数值四舍五入到两位小数，避免埋点中出现长尾精度噪声。 */
function round(value: number): number {
  return Math.round(value * 100) / 100;
}

/**
 * 抽取节点列表的前 6 个 ID 作为样本。
 * 用于在摘要中给出"代表性的几个节点"，避免在控制台中打印完整列表。
 */
function sampleNodeIds(nodes: LinkGraphNode[]): string[] {
  return nodes.slice(0, 6).map((node) => node.id);
}

/**
 * 汇总复用率指标。totalCount 表示总量，reusedCount 表示其中复用了缓存的数目，
 * 其余字段派生（重建数量、复用比例）。空集合视为完全复用，避免误报为低复用率。
 */
export function summarizeReuse(totalCount: number, reusedCount: number) {
  return {
    totalCount,
    reusedCount,
    rebuiltCount: Math.max(totalCount - reusedCount, 0),
    reuseRatio: totalCount === 0 ? 1 : round(reusedCount / totalCount),
  };
}

/** 计时起点工具，与 [measureDuration] 配合用于埋点耗时记录。 */
export function measureStart(): number {
  return nowValue();
}

/** 计算从给定起点到现在的耗时，结果四舍五入到两位小数。 */
export function measureDuration(startedAt: number): number {
  return round(nowValue() - startedAt);
}

/**
 * 把图文档压缩为可读的摘要对象（节点/边数、截断标记、样本节点 ID 等），
 * 便于在埋点和控制台输出中快速识别图谱规模。
 */
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
    // 后端可能给出大于本地持有数量的提示值（未全量加载），单独透出便于诊断
    nodeCountHint: document.nodeCount ?? null,
    edgeCountHint: document.edgeCount ?? null,
    sampleNodeIds: sampleNodeIds(document.nodes),
  };
}

/**
 * 把整份引导态压缩成精简摘要：当前场景、各类图谱规模、选中节点、最近消息等。
 * 主要用于一次性打印整体状态，例如启动失败时把快照写到日志。
 */
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

/**
 * 记录一条链路图谱埋点。仅在调试开启或外部接收器存在时才真正写入。
 * 流程：
 * 1) 噪声事件直接丢弃；
 * 2) 序列化消息（超长时自动截断）；
 * 3) 同步写入 lastTrace 与环形历史；
 * 4) 若有外部接收器则转发；否则累积到 buffer。
 * 任何序列化异常会被兜底捕获并改写为"序列化失败"埋点，避免埋点本身把页面打挂。
 */
export function traceLinkGraph(event: string, payload?: unknown): void {
  // 高频噪声事件忽略，避免污染历史
  if (NOISY_FRONTEND_TRACE_EVENTS.has(event)) {
    return;
  }
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
    const serialized = serializeTraceMessage(message);
    // 维护最近一条与环形历史，便于事后排查
    window.__linkGraphLastTrace = serialized;
    window.__linkGraphTraceHistory = window.__linkGraphTraceHistory ?? [];
    window.__linkGraphTraceHistory.push(serialized);
    // 超过历史容量时从头裁剪（保持最近 TRACE_HISTORY_LIMIT 条）
    if (window.__linkGraphTraceHistory.length > TRACE_HISTORY_LIMIT) {
      window.__linkGraphTraceHistory.splice(0, window.__linkGraphTraceHistory.length - TRACE_HISTORY_LIMIT);
    }
    // 优先转发给外部接收器
    if (window.linkGraphDebugTrace) {
      window.linkGraphDebugTrace(serialized);
      return;
    }
    // 否则累计到 buffer，等待外部接收器取走
    window.__linkGraphTraceBuffer = window.__linkGraphTraceBuffer ?? [];
    window.__linkGraphTraceBuffer.push(serialized);
  } catch (error) {
    // 序列化失败时构造占位埋点，让"序列化失败"这件事本身可被观察到
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

/**
 * 把埋点消息序列化为字符串。
 * 若结果超过桥接通道允许的最大长度，则改写为"已截断"占位结构，
 * 保留原始长度信息便于事后判断截断程度。
 */
function serializeTraceMessage(message: { time: string; event: string; payload: unknown }): string {
  const serialized = JSON.stringify(message);
  if (serialized.length <= TRACE_BRIDGE_PAYLOAD_MAX_CHARS) {
    return serialized;
  }
  return JSON.stringify({
    time: message.time,
    event: message.event,
    payload: {
      truncated: true,
      originalLength: serialized.length,
    },
  });
}

/**
 * 启动期专用埋点：除了常规记录外，会同步向 console.warn 输出，
 * 便于在浏览器控制台即时看到启动阶段的关键事件，加快冷启动问题定位。
 */
export function traceLinkGraphStartup(event: string, payload?: unknown): void {
  traceLinkGraph(event, payload);
  if (window.__linkGraphDebugEnabled !== true || typeof console === "undefined") {
    return;
  }
  const lastTrace = window.__linkGraphLastTrace;
  if (!lastTrace) {
    return;
  }
  console.warn("link-graph startup trace", lastTrace);
}

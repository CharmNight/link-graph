// 编辑器传输层：在前端与 IntelliJ 后端之间维护引导态的快照/增量消息分发。
// 主要职责是：
// - 监听后端通过 window 事件投递的快照或切片；
// - 把增量切片合并到当前基线状态，形成新的快照；
// - 在前端就绪后把最新快照派发给所有订阅者；
// - 维护 revision 单调递增的去重逻辑，保证消息不重复处理。
import type { LinkGraphIncrementalTransportEnvelope, LinkGraphSnapshotEnvelope } from "./types";
import { measureDuration, measureStart, traceLinkGraph } from "./debug";
import { isLinkGraphIncrementalTransportType } from "./transportProtocol";

/** 引导态订阅者回调：收到新的快照信封时被调用。 */
type BootstrapListener = (envelope: LinkGraphSnapshotEnvelope) => void;
/** 快照确认回调：通知后端某 revision 已经被前端处理。 */
type SnapshotAcknowledger = (revision: number) => void;
/** 前端就绪通知回调：把"已应用到的 revision"上报给后端。 */
type FrontendReadyNotifier = (payload: { lastAppliedRevision: number | null }) => void;

// 模块级单例状态：所有订阅者共享同一个最新信封与已派发/已确认水位。
/** 是否已经初始化（注册了 window 事件监听）。 */
let initialized = false;
/** 前端是否已声明就绪。未就绪前不会真正派发快照，避免订阅者错过早期事件。 */
let frontendReady = false;
/** 当前最新的快照信封；订阅者新增时可直接补发。 */
let latestEnvelope: LinkGraphSnapshotEnvelope | null = null;
/** 已经派发给订阅者的最高 revision，用于去重。 */
let lastDeliveredRevision = Number.NEGATIVE_INFINITY;
/** 已经向后端确认过的最高 revision，用于去重确认。 */
let lastAcknowledgedRevision = Number.NEGATIVE_INFINITY;
/** 当前注册的订阅者集合，使用 Set 保证唯一性。 */
const listeners = new Set<BootstrapListener>();

/**
 * 类型守卫：判断任意值是否为快照信封结构。
 * 只做关键字存在性检查，字段具体合法性由后续逻辑保证。
 */
function isSnapshotEnvelope(value: unknown): value is LinkGraphSnapshotEnvelope {
  if (!value || typeof value !== "object") {
    return false;
  }
  return "sessionId" in value && "revision" in value && "state" in value;
}

/**
 * 类型守卫：判断任意值是否为增量传输信封。
 * 比快照多一项 type 校验，且 type 必须是协议定义的合法增量类型。
 */
function isIncrementalTransportEnvelope(value: unknown): value is LinkGraphIncrementalTransportEnvelope {
  if (!value || typeof value !== "object") {
    return false;
  }
  return "type" in value
    && isLinkGraphIncrementalTransportType(value.type)
    && "sessionId" in value
    && "revision" in value
    && "state" in value;
}

/**
 * 类型守卫：判断当前最新信封是否携带了受支持的增量切片类型。
 * 用于决定派发时是否需要强制立即下发（增量往往携带用户关心的最新结果）。
 */
function isSupportedIncrementalSlice(
  envelope: LinkGraphSnapshotEnvelope | null,
): envelope is LinkGraphSnapshotEnvelope & { transportType: LinkGraphIncrementalTransportEnvelope["type"] } {
  return isLinkGraphIncrementalTransportType(envelope?.transportType);
}

/**
 * 把当前最新信封派发给所有订阅者。
 *
 * 派发条件：
 * - 前端已就绪且存在最新信封；
 * - 非强制模式下 revision 必须严格大于已派发水位。
 *
 * 派发后更新水位，并写一条耗时埋点便于性能追踪。
 */
function flushLatestEnvelope(forceDispatch = false): void {
  const startedAt = measureStart();
  if (!frontendReady || !latestEnvelope) {
    return;
  }
  // 非强制模式下跳过已经派发过的 revision
  if (!forceDispatch && latestEnvelope.revision <= lastDeliveredRevision) {
    return;
  }
  // 推进已派发水位（仅在 revision 真的更大时）
  if (latestEnvelope.revision > lastDeliveredRevision) {
    lastDeliveredRevision = latestEnvelope.revision;
  }
  const listenerCount = listeners.size;
  listeners.forEach((listener) => listener(latestEnvelope as LinkGraphSnapshotEnvelope));
  traceLinkGraph("editorTransport.flushLatestEnvelope", {
    revision: latestEnvelope.revision,
    forceDispatch,
    listenerCount,
    durationMs: measureDuration(startedAt),
  });
}

/**
 * 处理一条来自后端的快照或增量信封。
 * 先归一化为统一的快照信封（增量需要合并到基线），再更新模块状态并尝试派发。
 */
function handleBootstrapEnvelope(
  detail: LinkGraphSnapshotEnvelope | LinkGraphIncrementalTransportEnvelope,
): void {
  const normalizedEnvelope = normalizeTransportEnvelope(detail);
  // 归一化失败（例如增量缺失基线）则直接丢弃
  if (!normalizedEnvelope) {
    return;
  }
  // 仅当新信封 revision 不落后于当前最新时才更新，保证单调
  if (!latestEnvelope || normalizedEnvelope.revision >= latestEnvelope.revision) {
    latestEnvelope = normalizedEnvelope;
    // 同步写入 window.linkGraphBootstrap，便于非订阅者直接读取最新状态
    window.linkGraphBootstrap = normalizedEnvelope.state;
  }
  // 增量切片触发强制派发（绕过去重），让用户立即看到新结果
  flushLatestEnvelope(isSupportedIncrementalSlice(normalizedEnvelope));
}

/** window 事件回调：从 CustomEvent 中取出 detail 转交给通用处理函数。 */
function handleBootstrapEvent(event: Event): void {
  const customEvent = event as CustomEvent<
    LinkGraphSnapshotEnvelope | LinkGraphIncrementalTransportEnvelope
  >;
  handleBootstrapEnvelope(customEvent.detail);
}

/**
 * 注册 window 事件监听，仅注册一次。
 * 在非浏览器环境下跳过，避免 SSR 或测试环境报错。
 */
function ensureInitialized(): void {
  if (initialized || typeof window === "undefined") {
    return;
  }
  window.addEventListener("link-graph-bootstrap", handleBootstrapEvent as EventListener);
  initialized = true;
}

/**
 * 订阅引导态快照更新。
 *
 * 订阅时会立刻补发一次当前最新信封（若已有），让新订阅者拿到当前状态而不需要等待下次更新。
 * 返回取消订阅函数，调用方应在卸载时调用以避免泄漏。
 */
export function subscribeBootstrap(listener: BootstrapListener): () => void {
  ensureInitialized();
  listeners.add(listener);
  // 立刻尝试派发一次，把当前状态补给新订阅者
  flushLatestEnvelope();
  return () => {
    listeners.delete(listener);
  };
}

/**
 * 声明前端已就绪，此后收到的快照才会真正派发。
 * 同时通过 onReady 回调把"已应用到的最后 revision"上报给后端，便于后端做初始化握手。
 */
export function announceFrontendReady(
  lastAppliedRevision?: number,
  onReady?: FrontendReadyNotifier,
): void {
  ensureInitialized();
  frontendReady = true;
  onReady?.({
    // 仅在传入有限数时才上报，避免把 Infinity/NaN 透传到后端
    lastAppliedRevision: Number.isFinite(lastAppliedRevision) ? lastAppliedRevision ?? null : null,
  });
  flushLatestEnvelope();
}

/**
 * 向后端确认某 revision 已被前端处理。
 * 仅当 revision 严格大于已确认水位时才触发回调，避免重复确认。
 */
export function acknowledgeSnapshot(
  revision: number,
  onAcknowledge?: SnapshotAcknowledger,
): void {
  if (revision <= lastAcknowledgedRevision) {
    return;
  }
  lastAcknowledgedRevision = revision;
  onAcknowledge?.(revision);
}

/**
 * 测试辅助函数：直接派发一个信封到处理管线，
 * 用于在不依赖 window 事件的前提下驱动状态机。
 */
export function dispatchBootstrapForTest(
  envelope: LinkGraphSnapshotEnvelope | LinkGraphIncrementalTransportEnvelope,
): void {
  handleBootstrapEnvelope(envelope);
}

/** 测试辅助函数：把模块级状态重置为初始值，避免测试之间相互污染。 */
export function resetEditorTransportForTest(): void {
  frontendReady = false;
  latestEnvelope = null;
  lastDeliveredRevision = Number.NEGATIVE_INFINITY;
  lastAcknowledgedRevision = Number.NEGATIVE_INFINITY;
  listeners.clear();
}

// 模块加载时立即尝试初始化，让早期发出的 window 事件也能被捕获
ensureInitialized();

/**
 * 把外部传入的信封归一化为统一的快照信封。
 *
 * 增量信封需要：
 * 1) 取当前最新状态（latestEnvelope 或 window.linkGraphBootstrap）作为基线；
 * 2) 把增量的 state 字段浅合并到基线；
 * 3) 对 artifactContents 做特殊深合并（按 key 合并而非整体替换），保留旧产物；
 * 4) 同时记录耗时与产物数量埋点。
 *
 * 基线缺失时返回 null（无法独立理解增量），由调用方丢弃。
 */
function normalizeTransportEnvelope(
  detail: LinkGraphSnapshotEnvelope | LinkGraphIncrementalTransportEnvelope,
): LinkGraphSnapshotEnvelope | null {
  if (isIncrementalTransportEnvelope(detail)) {
    // 增量必须有基线才能合并；基线来源优先取内存中的最新信封
    const baseState = latestEnvelope?.state ?? window.linkGraphBootstrap ?? null;
    if (!baseState) {
      return null;
    }
    const startedAt = measureStart();
    // 记录基线与增量中各自的产物数量，便于事后排查合并是否丢失产物
    const baseArtifactCount = Object.keys(baseState.artifactContents ?? {}).length;
    const incomingArtifactCount = Object.keys(detail.state.artifactContents ?? {}).length;
    // 顶层字段浅合并
    const state = {
      ...baseState,
      ...detail.state,
      // 产物按 key 合并：新 key 覆盖同 key 旧值，未在增量中出现的旧 key 保留
      artifactContents: {
        ...(baseState.artifactContents ?? {}),
        ...(detail.state.artifactContents ?? {}),
      },
    };
    traceLinkGraph("editorTransport.incrementalSlice.merge", {
      type: detail.type,
      revision: detail.revision,
      baseArtifactCount,
      incomingArtifactCount,
      mergedArtifactCount: Object.keys(state.artifactContents ?? {}).length,
      durationMs: measureDuration(startedAt),
    });

    return {
      sessionId: detail.sessionId,
      revision: detail.revision,
      state,
      transportType: detail.type,
    };
  }

  // 非增量则要求是合法的快照信封，否则丢弃
  if (!isSnapshotEnvelope(detail)) {
    return null;
  }
  return detail;
}

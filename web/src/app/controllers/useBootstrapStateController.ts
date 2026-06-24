// 引导态控制器 Hook。
// 负责：
// - 订阅后端推送的引导态快照/增量消息；
// - 过滤过期 revision（避免旧消息覆盖新状态）；
// - 把通过过滤的消息应用到本地状态（通过 startTransition 低优先级更新）；
// - 向后端确认已处理 revision（ARTIFACT_SLICE 除外，它由前端按需确认）；
// - 在 Hook 初始化时宣布"前端已就绪"并上报最后应用到的 revision。
import { startTransition, useEffect, useRef } from "react";
import { acknowledgeSnapshot, announceFrontendReady, subscribeBootstrap } from "../api";
import { summarizeBootstrapState, traceLinkGraph } from "../debug";
import type { LinkGraphBootstrapState, LinkGraphIncrementalTransportEnvelope } from "../types";
import {
  LINK_GRAPH_ARTIFACT_SLICE_TRANSPORT_TYPE,
  isLinkGraphIncrementalTransportType,
} from "../transportProtocol";

/** useBootstrapStateController 的入参。 */
interface UseBootstrapStateControllerArgs {
  /** 初始 revision（来自上次加载的快照）。 */
  initialRevision: number;
  /** 把通过过滤的引导态应用到本地状态的回调。 */
  applyBootstrapState: (nextState: LinkGraphBootstrapState) => void;
}

/**
 * 判断传输类型是否为增量切片。
 * 增量切片允许与当前 revision 相同（表示在同一版本上追加增量）。
 */
function isIncrementalSlice(
  transportType: LinkGraphIncrementalTransportEnvelope["type"] | undefined,
): boolean {
  return isLinkGraphIncrementalTransportType(transportType);
}

/**
 * 引导态控制器 Hook。
 *
 * 工作流程：
 * 1) 订阅 [subscribeBootstrap] 接收后端推送的快照/增量；
 * 2) 过滤过期消息：
 *    - revision 小于当前 → 丢弃；
 *    - revision 等于当前且非增量切片 → 丢弃；
 * 3) 通过过滤的消息在 startTransition 中应用到本地状态（低优先级，不阻塞 UI）；
 * 4) 推进已应用 revision；
 * 5) 向后端确认（ARTIFACT_SLICE 不自动确认，由按需加载流程单独确认）；
 * 6) 初始化时宣布前端就绪 + 上报最后 revision。
 */
export function useBootstrapStateController({
  initialRevision,
  applyBootstrapState,
}: UseBootstrapStateControllerArgs) {
  // 最后成功应用的 revision（用于过滤过期消息）
  const lastAppliedSnapshotRevisionRef = useRef(initialRevision);
  // applyBootstrapState 的 ref（避免 effect 依赖变化导致重新订阅）
  const applyBootstrapStateRef = useRef(applyBootstrapState);

  // 保持 ref 最新
  useEffect(() => {
    applyBootstrapStateRef.current = applyBootstrapState;
  }, [applyBootstrapState]);

  // 订阅引导态消息 + 宣布就绪
  useEffect(() => {
    const unsubscribe = subscribeBootstrap((envelope) => {
      // 过滤过期消息
      if (
        envelope.revision < lastAppliedSnapshotRevisionRef.current
        || (
          envelope.revision === lastAppliedSnapshotRevisionRef.current
          && !isIncrementalSlice(envelope.transportType)
        )
      ) {
        return;
      }
      // 埋点：记录接收到的引导态摘要
      traceLinkGraph("app.bootstrapEvent.received", summarizeBootstrapState(envelope.state));
      // 在低优先级 transition 中应用状态
      startTransition(() => {
        applyBootstrapStateRef.current(envelope.state);
      });
      // 推进已应用 revision
      if (envelope.revision > lastAppliedSnapshotRevisionRef.current) {
        lastAppliedSnapshotRevisionRef.current = envelope.revision;
      }
      // 确认：非 ARTIFACT_SLICE 自动确认；ARTIFACT_SLICE 由按需加载单独确认
      if (envelope.transportType !== LINK_GRAPH_ARTIFACT_SLICE_TRANSPORT_TYPE) {
        acknowledgeSnapshot(envelope.revision);
      }
    });
    // 宣布前端就绪，上报最后 revision 用于握手
    announceFrontendReady(
      Number.isFinite(lastAppliedSnapshotRevisionRef.current)
        ? lastAppliedSnapshotRevisionRef.current
        : undefined,
    );
    return unsubscribe;
  }, []);

  return {
    lastAppliedSnapshotRevisionRef,
  };
}

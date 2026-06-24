import type { AsyncRequestState } from "../types";

/**
 * 索引图空态文案集合。
 *
 * 索引图视图在不同请求状态下需要展示不同空态文案（空闲/运行中/失败/成功），
 * 调用方按场景填入这套文案，本模块按请求状态选择合适的条目。
 */
export interface IndexedGraphEmptyStateCopy {
  /** 空闲态标题（尚未发起请求）。 */
  idleTitle: string;
  /** 空闲态详细说明。 */
  idleDetail: string;
  /** 运行中标题。 */
  runningTitle: string;
  /** 运行中详细说明（用作 fallback）。 */
  runningDetail: string;
  /** 失败标题。 */
  failedTitle: string;
  /** 失败详细说明（用作 fallback）。 */
  failedDetail: string;
  /** 成功标题。 */
  succeededTitle: string;
  /** 成功详细说明。 */
  succeededDetail: string;
}

/**
 * 按请求状态解析索引图空态文案。
 *
 * @param requestState 当前异步请求状态；为空时按 IDLE 处理
 * @param copy 文案集合
 * @return 当前应展示的标题与详细说明
 */
export function resolveIndexedGraphEmptyState(
  requestState: AsyncRequestState | null | undefined,
  copy: IndexedGraphEmptyStateCopy,
): { title: string; detail: string } {
  switch (requestState?.phase ?? "IDLE") {
    case "RUNNING":
      return {
        title: copy.runningTitle,
        // 运行中：优先使用请求自带的进度消息
        detail: requestState?.statusMessage ?? copy.runningDetail,
      };
    case "FAILED":
    case "TIMED_OUT":
      return {
        title: copy.failedTitle,
        // 失败：优先使用错误消息，再退回到状态消息，最后用默认文案
        detail: requestState?.errorMessage ?? requestState?.statusMessage ?? copy.failedDetail,
      };
    case "SUCCEEDED":
      return {
        title: copy.succeededTitle,
        detail: copy.succeededDetail,
      };
    case "IDLE":
    default:
      return {
        title: copy.idleTitle,
        detail: copy.idleDetail,
      };
  }
}

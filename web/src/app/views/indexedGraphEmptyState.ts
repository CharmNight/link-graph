import type { AsyncRequestState } from "../types";

export interface IndexedGraphEmptyStateCopy {
  idleTitle: string;
  idleDetail: string;
  runningTitle: string;
  runningDetail: string;
  failedTitle: string;
  failedDetail: string;
  succeededTitle: string;
  succeededDetail: string;
}

export function resolveIndexedGraphEmptyState(
  requestState: AsyncRequestState | null | undefined,
  copy: IndexedGraphEmptyStateCopy,
): { title: string; detail: string } {
  switch (requestState?.phase ?? "IDLE") {
    case "RUNNING":
      return {
        title: copy.runningTitle,
        detail: requestState?.statusMessage ?? copy.runningDetail,
      };
    case "FAILED":
    case "TIMED_OUT":
      return {
        title: copy.failedTitle,
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

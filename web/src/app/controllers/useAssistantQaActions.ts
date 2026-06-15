import { useEffect, type Dispatch, type SetStateAction } from "react";
import {
  confirmQaCandidateChange,
  resolveInvestigationThread,
  retryLastQaRequestAsync,
} from "../api";
import { deriveInvestigationThreads } from "../investigationThreads";
import type {
  CandidateDraftChange,
  GraphPatchResult,
  LinkGraphNode,
  OperationFeedback,
  QaRequestRecoveryState,
} from "../types";
import { candidateCanConfirm } from "../workbench/candidateChangeSupport";
import type { useBridgeCommandController } from "./useBridgeCommandController";

interface UseAssistantQaActionsArgs {
  qaResult: GraphPatchResult | null;
  qaRequestRecoveryState: QaRequestRecoveryState;
  nodes: LinkGraphNode[];
  bridgeCommands: Pick<
    ReturnType<typeof useBridgeCommandController>,
    "runBridgeCommand" | "submitAsyncBridgeCommand"
  >;
  setOperationFeedback: Dispatch<SetStateAction<OperationFeedback | null>>;
  setSelectedQaChangeId: Dispatch<SetStateAction<string | null>>;
  setSelectedQaThreadId: Dispatch<SetStateAction<string | null>>;
  selectExplanationTargetNode: (nodeId: string, options?: { focusViewport?: boolean }) => void;
  resolveDraftEntryTargetNodeIds: (entry: CandidateDraftChange | null) => string[];
  resolveDisplayedNodeId: (nodeId: string | null | undefined, nodes: LinkGraphNode[]) => string | null;
  resolveEvidenceTargetNodeId: (
    targetNodeIds: string[],
    evidence?: Array<{ references: Array<{ nodeId?: string | null }> }>,
  ) => string | null;
}

export function useAssistantQaActions(args: UseAssistantQaActionsArgs) {
  function handleConfirmCandidateChange(changeId: string) {
    const candidate = args.qaResult?.candidateChanges.find((item) => item.changeId === changeId) ?? null;
    if (candidate && !candidateCanConfirm(candidate)) {
      args.setOperationFeedback({
        level: "WARNING",
        message: "当前候选变更缺少直接证据，不能直接确认进草稿。",
      });
      return;
    }
    args.bridgeCommands.runBridgeCommand("确认候选变更", () => confirmQaCandidateChange(changeId), {
      onAccepted: () => {
        if (candidate) {
          const targetNodeId = args.resolveDraftEntryTargetNodeIds(candidate)
            .map((nodeId) => args.resolveDisplayedNodeId(nodeId, args.nodes))
            .find(Boolean)
            ?? null;
          if (targetNodeId) {
            args.selectExplanationTargetNode(targetNodeId, { focusViewport: true });
          }
        }
      },
      successFeedback: {
        level: "SUCCESS",
        message: "已提交候选变更确认，等待后端刷新草稿层。",
      },
    });
  }

  function handleRetryLastQaRequest() {
    const failedRequest = args.qaRequestRecoveryState.lastFailedRequest;
    if (!failedRequest) {
      return;
    }
    args.bridgeCommands.submitAsyncBridgeCommand("问答", () => retryLastQaRequestAsync(), {
      successFeedback: {
        level: "INFO",
        message: "已提交失败问答的直接重试请求。",
      },
    });
  }

  function handleSelectQaChange(changeId: string) {
    args.setSelectedQaChangeId(changeId);
    const change = args.qaResult?.candidateChanges.find((item) => item.changeId === changeId) ?? null;
    const targetNodeId = change
      ? args.resolveDisplayedNodeId(args.resolveEvidenceTargetNodeId(change.targetNodeIds, change.evidence), args.nodes)
      : null;
    if (!targetNodeId) {
      return;
    }
    args.selectExplanationTargetNode(targetNodeId, { focusViewport: true });
  }

  function handleSelectQaThread(threadId: string) {
    args.setSelectedQaThreadId(threadId);
    const thread = deriveInvestigationThreads(args.qaResult).find((item) => item.threadId === threadId) ?? null;
    const targetNodeId = thread
      ? args.resolveDisplayedNodeId(args.resolveEvidenceTargetNodeId(thread.targetNodeIds, thread.evidence), args.nodes)
      : null;
    if (!targetNodeId) {
      return;
    }
    args.selectExplanationTargetNode(targetNodeId, { focusViewport: true });
  }

  function handleResolveQaThread(
    threadId: string,
    resolutionStatus: "DEFERRED" | "ACCEPTED_RISK" | "DISMISSED",
  ) {
    args.setSelectedQaThreadId(threadId);
    args.bridgeCommands.runBridgeCommand("风险决策", () => resolveInvestigationThread(threadId, resolutionStatus), {
      successFeedback: {
        level: "INFO",
        message:
          resolutionStatus === "DEFERRED"
            ? "已提交风险暂挂决策。"
            : resolutionStatus === "ACCEPTED_RISK"
              ? "已提交接受风险决策。"
              : "已提交排除风险决策。",
      },
    });
  }

  useEffect(() => {
    const pendingChanges = args.qaResult?.candidateChanges.filter((change) => change.status === "PENDING_CONFIRMATION") ?? [];
    const firstChangeId = pendingChanges[0]?.changeId ?? null;
    args.setSelectedQaChangeId((current) => {
      if (!pendingChanges.length) {
        return null;
      }
      return pendingChanges.some((change) => change.changeId === current) ? current : firstChangeId;
    });
  }, [args.qaResult, args.setSelectedQaChangeId]);

  useEffect(() => {
    const investigationThreads = deriveInvestigationThreads(args.qaResult);
    const firstLeadId = investigationThreads[0]?.threadId ?? null;
    args.setSelectedQaThreadId((current) => {
      if (!investigationThreads.length) {
        return null;
      }
      return investigationThreads.some((thread) => thread.threadId === current) ? current : firstLeadId;
    });
  }, [args.qaResult, args.setSelectedQaThreadId]);

  return {
    handleConfirmCandidateChange,
    handleRetryLastQaRequest,
    handleSelectQaChange,
    handleSelectQaThread,
    handleResolveQaThread,
  };
}

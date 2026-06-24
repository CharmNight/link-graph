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

/** useAssistantQaActions 的入参；承载问答结果、节点集合、各类状态更新器与目标节点解析器。 */
interface UseAssistantQaActionsArgs {
  /** 最近一次问答（含候选变更、证据、相关线索）。 */
  qaResult: GraphPatchResult | null;
  /** 问答请求失败重试相关的恢复状态。 */
  qaRequestRecoveryState: QaRequestRecoveryState;
  /** 当前画布节点集合，用于将证据/变更目标节点解析到可视节点上。 */
  nodes: LinkGraphNode[];
  /** 桥接命令执行器，用于把动作派发到 IntelliJ 后端。 */
  bridgeCommands: Pick<
    ReturnType<typeof useBridgeCommandController>,
    "runBridgeCommand" | "submitAsyncBridgeCommand"
  >;
  /** 操作反馈状态更新器（INFO/WARNING/SUCCESS/ERROR 等提示）。 */
  setOperationFeedback: Dispatch<SetStateAction<OperationFeedback | null>>;
  /** 当前选中候选变更 ID 的更新器。 */
  setSelectedQaChangeId: Dispatch<SetStateAction<string | null>>;
  /** 当前选中线索 ID 的更新器。 */
  setSelectedQaThreadId: Dispatch<SetStateAction<string | null>>;
  /** 选中讲解目标节点（并可选地让视口聚焦到该节点）。 */
  selectExplanationTargetNode: (nodeId: string, options?: { focusViewport?: boolean }) => void;
  /** 解析候选变更条目对应的目标节点 ID 列表。 */
  resolveDraftEntryTargetNodeIds: (entry: CandidateDraftChange | null) => string[];
  /** 将任意节点 ID 解析为当前画布上实际可见的节点 ID。 */
  resolveDisplayedNodeId: (nodeId: string | null | undefined, nodes: LinkGraphNode[]) => string | null;
  /** 从证据引用列表中选取最能代表证据落点的节点 ID。 */
  resolveEvidenceTargetNodeId: (
    targetNodeIds: string[],
    evidence?: Array<{ references: Array<{ nodeId?: string | null }> }>,
  ) => string | null;
}

/**
 * 助手问答动作 Hook。
 *
 * 集中处理与一次问答结果相关的用户交互：
 * - 确认/选中候选变更并联动画布；
 * - 重试最近一次失败的问答；
 * - 选中调查线索、提交风险决策；
 * - 根据问答结果自动维护默认选中的变更/线索。
 */
export function useAssistantQaActions(args: UseAssistantQaActionsArgs) {
  /**
   * 确认一条候选变更进入草稿层。
   * 缺少直接证据时拒绝确认并给出 WARNING；确认成功后联动选中其目标节点。
   */
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

  /** 重试最近一次失败的问答请求；没有可重试的请求时直接返回。 */
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

  /**
   * 选中一条候选变更；若存在证据目标节点，则联动画布选中并聚焦视口。
   */
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

  /**
   * 选中一条调查线索；推导线索对应目标节点并联动选中 + 视口聚焦。
   */
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

  /**
   * 提交一条调查线索的风险决策（暂挂 / 接受风险 / 排除风险）。
   * 同步把该线索设为当前选中，并按决策类型给出对应反馈文案。
   */
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

  // 自动维护"当前选中候选变更"：
  // 1) 没有 PENDING_CONFIRMATION 候选时清空选中；
  // 2) 当前选中已不在待确认列表中时，回退到第一个候选。
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

  // 自动维护"当前选中调查线索"：规则与上面候选变更同步逻辑一致。
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

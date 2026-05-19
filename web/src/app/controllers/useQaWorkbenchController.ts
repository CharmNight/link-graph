import { useEffect, type Dispatch, type SetStateAction } from "react";
import {
  confirmQaCandidateChange,
  requestQaAsync,
  resolveInvestigationThread,
  retryLastQaRequestAsync,
  unconfirmQaCandidateChange,
  updateWorkbenchSectionPreference,
} from "../api";
import { deriveInvestigationThreads } from "../investigationThreads";
import type {
  CandidateDraftChange,
  DraftWorkbenchEntry,
  GraphPatchResult,
  LinkGraphNode,
  OperationFeedback,
  QaMode,
  QaRequestRecoveryState,
  RiskResolutionStatus,
  WorkbenchSectionId,
} from "../types";
import { candidateCanConfirm } from "../workbench/candidateChangeSupport";
import { QA_WORKBENCH_SECTION_IDS } from "../workbench/workbenchSections";
import type { useBridgeCommandController } from "./useBridgeCommandController";

type WorkbenchTab = "explanation" | "qa" | "draft" | "code";

interface UseQaWorkbenchControllerArgs {
  qaResult: GraphPatchResult | null;
  qaRequestRecoveryState: QaRequestRecoveryState;
  qaSourceThreadId: string | null;
  qaQuestionMode: QaMode;
  qaTargetNodeIds: string[];
  selectedQaChangeId: string | null;
  selectedQaThreadId: string | null;
  nodes: LinkGraphNode[];
  draftWorkbenchState: { draftChanges: DraftWorkbenchEntry[]; draftNotes: DraftWorkbenchEntry[] };
  bridgeCommands: Pick<
    ReturnType<typeof useBridgeCommandController>,
    "runBridgeCommand" | "submitAsyncBridgeCommand"
  >;
  setQaQuestionDraft: Dispatch<SetStateAction<string>>;
  setQaQuestionMode: Dispatch<SetStateAction<QaMode>>;
  setQaTargetNodeIds: Dispatch<SetStateAction<string[]>>;
  setQaSourceThreadId: Dispatch<SetStateAction<string | null>>;
  setActiveWorkbenchTab: (tab: WorkbenchTab) => void;
  setOperationFeedback: Dispatch<SetStateAction<OperationFeedback | null>>;
  setDraftWorkbenchState: Dispatch<SetStateAction<{ draftChanges: DraftWorkbenchEntry[]; draftNotes: DraftWorkbenchEntry[] }>>;
  setSelectedDraftEntryId: Dispatch<SetStateAction<string | null>>;
  setQaResult: Dispatch<SetStateAction<GraphPatchResult | null>>;
  setSelectedQaChangeId: Dispatch<SetStateAction<string | null>>;
  setSelectedQaThreadId: Dispatch<SetStateAction<string | null>>;
  selectExplanationTargetNode: (nodeId: string, options?: { focusViewport?: boolean }) => void;
  toDraftWorkbenchEntry: (change: CandidateDraftChange) => DraftWorkbenchEntry;
  updateGraphPatchResultCandidateStatus: (
    result: GraphPatchResult | null,
    changeId: string,
    status: CandidateDraftChange["status"],
  ) => GraphPatchResult | null;
  updateGraphPatchResultThreadResolution: (
    result: GraphPatchResult | null,
    threadId: string,
    status: RiskResolutionStatus,
  ) => GraphPatchResult | null;
  resolveDraftEntryTargetNodeIds: (entry: DraftWorkbenchEntry | CandidateDraftChange | null) => string[];
  resolveDisplayedNodeId: (nodeId: string | null | undefined, nodes: LinkGraphNode[]) => string | null;
  resolveEvidenceTargetNodeId: (
    targetNodeIds: string[],
    evidence?: Array<{ references: Array<{ nodeId?: string | null }> }>,
  ) => string | null;
}

export function useQaWorkbenchController(args: UseQaWorkbenchControllerArgs) {
  function activateQaSection(sectionId: WorkbenchSectionId) {
    if (!QA_WORKBENCH_SECTION_IDS.includes(sectionId)) {
      return;
    }
    QA_WORKBENCH_SECTION_IDS.forEach((qaSectionId) => {
      updateWorkbenchSectionPreference(qaSectionId, qaSectionId === sectionId);
    });
  }

  function handleRequestQa(
    question: string,
    targetNodeIds: string[] = args.qaTargetNodeIds,
    mode: QaMode = args.qaQuestionMode,
  ) {
    const normalizedQuestion = question.trim();
    if (normalizedQuestion.length === 0) {
      return;
    }
    args.setQaQuestionDraft(normalizedQuestion);
    args.setQaQuestionMode(mode);
    args.setQaTargetNodeIds(targetNodeIds);
    args.bridgeCommands.submitAsyncBridgeCommand("问答", () => requestQaAsync(normalizedQuestion, targetNodeIds, args.qaSourceThreadId, mode), {
      onAccepted: () => {
        args.setActiveWorkbenchTab("qa");
      },
      successFeedback: {
        level: "INFO",
        message: `已发起问答请求${targetNodeIds.length > 0 ? "，范围为当前节点。" : "，范围为整个链路。"}`,
      },
    });
  }

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
          const nextEntry = args.toDraftWorkbenchEntry(candidate);
          args.setDraftWorkbenchState((current) => ({
            ...current,
            draftChanges: current.draftChanges
              .filter((entry) => entry.sourceChangeId !== candidate.changeId)
              .concat(nextEntry),
          }));
          args.setSelectedDraftEntryId(nextEntry.entryId);
          args.setQaResult((current) => args.updateGraphPatchResultCandidateStatus(current, changeId, "CONFIRMED"));
          const targetNodeId = args.resolveDraftEntryTargetNodeIds(nextEntry)
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
        message: "已确认候选变更并写入草稿层，可切到草稿查看。",
      },
    });
  }

  function handleRetryLastQaRequest() {
    const failedRequest = args.qaRequestRecoveryState.lastFailedRequest;
    if (!failedRequest) {
      return;
    }
    args.setQaQuestionDraft(failedRequest.question);
    args.setQaQuestionMode(failedRequest.mode ?? "AUTO");
    args.setQaTargetNodeIds(failedRequest.selectedNodeIds);
    args.setQaSourceThreadId(failedRequest.sourceThreadId ?? null);
    activateQaSection("qa.request-status");
    args.setActiveWorkbenchTab("qa");
    args.bridgeCommands.submitAsyncBridgeCommand("问答", () => retryLastQaRequestAsync(), {
      successFeedback: {
        level: "INFO",
        message: "已提交失败问答的直接重试请求。",
      },
    });
  }

  function handleEditFailedQaRequest() {
    const failedRequest = args.qaRequestRecoveryState.lastFailedRequest;
    if (!failedRequest) {
      return;
    }
    args.setQaQuestionDraft(failedRequest.question);
    args.setQaQuestionMode(failedRequest.mode ?? "AUTO");
    args.setQaTargetNodeIds(failedRequest.selectedNodeIds);
    args.setQaSourceThreadId(failedRequest.sourceThreadId ?? null);
    activateQaSection("qa.composer");
    args.setActiveWorkbenchTab("qa");
    args.setOperationFeedback({
      level: "INFO",
      message: "已把失败问答回填到输入区，可修改后重新提交。",
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

  function handleInvestigateQaThread(threadId: string) {
    const thread = deriveInvestigationThreads(args.qaResult).find((item) => item.threadId === threadId) ?? null;
    if (!thread) {
      return;
    }
    args.setSelectedQaThreadId(threadId);
    args.setQaSourceThreadId(threadId);
    const nextQuestion = thread.recommendedQuestion.trim()
      || `请继续取证：核对“${thread.title}”对应的直接源码证据。`;
    args.setQaQuestionDraft(nextQuestion);
    args.setQaTargetNodeIds(thread.targetNodeIds);
    const targetNodeId = args.resolveDisplayedNodeId(
      args.resolveEvidenceTargetNodeId(thread.targetNodeIds, thread.evidence),
      args.nodes,
    );
    if (targetNodeId) {
      args.selectExplanationTargetNode(targetNodeId, { focusViewport: true });
    }
    activateQaSection("qa.composer");
    args.setActiveWorkbenchTab("qa");
    args.bridgeCommands.submitAsyncBridgeCommand("问答", () => requestQaAsync(nextQuestion, thread.targetNodeIds, threadId, "INVESTIGATE"), {
      successFeedback: {
        level: "INFO",
        message: `已围绕风险线程“${thread.title}”自动发起继续取证。`,
      },
    });
  }

  function handleResolveQaThread(
    threadId: string,
    resolutionStatus: "DEFERRED" | "ACCEPTED_RISK" | "DISMISSED",
  ) {
    args.setSelectedQaThreadId(threadId);
    activateQaSection("qa.investigation-threads");
    args.setActiveWorkbenchTab("qa");
    args.bridgeCommands.runBridgeCommand("风险决策", () => resolveInvestigationThread(threadId, resolutionStatus), {
      onAccepted: () => {
        args.setQaResult((current) => args.updateGraphPatchResultThreadResolution(current, threadId, resolutionStatus));
      },
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

  function handleUnconfirmDraftChange(entryId: string) {
    const entry = args.draftWorkbenchState.draftChanges.find((item) => item.entryId === entryId) ?? null;
    const changeId = entry?.sourceChangeId ?? null;
    if (!entry || !changeId) {
      return;
    }
    args.bridgeCommands.runBridgeCommand("取消确认候选变更", () => unconfirmQaCandidateChange(changeId), {
      onAccepted: () => {
        args.setDraftWorkbenchState((current) => ({
          ...current,
          draftChanges: current.draftChanges.filter((item) => item.entryId !== entryId),
        }));
        args.setQaResult((current) => args.updateGraphPatchResultCandidateStatus(current, changeId, "PENDING_CONFIRMATION"));
      },
      successFeedback: {
        level: "INFO",
        message: "已取消确认该候选变更，并从草稿层移除。",
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
    handleRequestQa,
    handleConfirmCandidateChange,
    handleRetryLastQaRequest,
    handleEditFailedQaRequest,
    handleSelectQaChange,
    handleSelectQaThread,
    handleInvestigateQaThread,
    handleResolveQaThread,
    handleUnconfirmDraftChange,
  };
}

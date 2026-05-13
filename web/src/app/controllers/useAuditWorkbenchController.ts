import { useEffect, type Dispatch, type SetStateAction } from "react";
import {
  confirmAuditCandidateChange,
  requestAuditAsync,
  resolveInvestigationThread,
  retryLastAuditRequestAsync,
  unconfirmAuditCandidateChange,
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
import { AUDIT_WORKBENCH_SECTION_IDS } from "../workbench/workbenchSections";
import type { useBridgeCommandController } from "./useBridgeCommandController";

type WorkbenchTab = "explanation" | "audit" | "draft" | "code";

interface UseAuditWorkbenchControllerArgs {
  auditResult: GraphPatchResult | null;
  qaRequestRecoveryState: QaRequestRecoveryState;
  auditSourceThreadId: string | null;
  auditQuestionMode: QaMode;
  auditTargetNodeIds: string[];
  selectedAuditChangeId: string | null;
  selectedAuditThreadId: string | null;
  nodes: LinkGraphNode[];
  draftWorkbenchState: { draftChanges: DraftWorkbenchEntry[]; draftNotes: DraftWorkbenchEntry[] };
  bridgeCommands: Pick<
    ReturnType<typeof useBridgeCommandController>,
    "runBridgeCommand" | "submitAsyncBridgeCommand"
  >;
  setAuditQuestionDraft: Dispatch<SetStateAction<string>>;
  setAuditQuestionMode: Dispatch<SetStateAction<QaMode>>;
  setAuditTargetNodeIds: Dispatch<SetStateAction<string[]>>;
  setAuditSourceThreadId: Dispatch<SetStateAction<string | null>>;
  setActiveWorkbenchTab: (tab: WorkbenchTab) => void;
  setOperationFeedback: Dispatch<SetStateAction<OperationFeedback | null>>;
  setDraftWorkbenchState: Dispatch<SetStateAction<{ draftChanges: DraftWorkbenchEntry[]; draftNotes: DraftWorkbenchEntry[] }>>;
  setSelectedDraftEntryId: Dispatch<SetStateAction<string | null>>;
  setAuditResult: Dispatch<SetStateAction<GraphPatchResult | null>>;
  setSelectedAuditChangeId: Dispatch<SetStateAction<string | null>>;
  setSelectedAuditThreadId: Dispatch<SetStateAction<string | null>>;
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

export function useAuditWorkbenchController(args: UseAuditWorkbenchControllerArgs) {
  function activateAuditSection(sectionId: WorkbenchSectionId) {
    if (!AUDIT_WORKBENCH_SECTION_IDS.includes(sectionId)) {
      return;
    }
    AUDIT_WORKBENCH_SECTION_IDS.forEach((auditSectionId) => {
      updateWorkbenchSectionPreference(auditSectionId, auditSectionId === sectionId);
    });
  }

  function handleRequestAudit(
    question: string,
    targetNodeIds: string[] = args.auditTargetNodeIds,
    mode: QaMode = args.auditQuestionMode,
  ) {
    const normalizedQuestion = question.trim();
    if (normalizedQuestion.length === 0) {
      return;
    }
    args.setAuditQuestionDraft(normalizedQuestion);
    args.setAuditQuestionMode(mode);
    args.setAuditTargetNodeIds(targetNodeIds);
    args.bridgeCommands.submitAsyncBridgeCommand("问答", () => requestAuditAsync(normalizedQuestion, targetNodeIds, args.auditSourceThreadId, mode), {
      onAccepted: () => {
        args.setActiveWorkbenchTab("audit");
      },
      successFeedback: {
        level: "INFO",
        message: `已发起问答请求${targetNodeIds.length > 0 ? "，范围为当前节点。" : "，范围为整个链路。"}`,
      },
    });
  }

  function handleConfirmCandidateChange(changeId: string) {
    const candidate = args.auditResult?.candidateChanges.find((item) => item.changeId === changeId) ?? null;
    if (candidate && !candidateCanConfirm(candidate)) {
      args.setOperationFeedback({
        level: "WARNING",
        message: "当前候选变更缺少直接证据，不能直接确认进草稿。",
      });
      return;
    }
    args.bridgeCommands.runBridgeCommand("确认候选变更", () => confirmAuditCandidateChange(changeId), {
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
          args.setAuditResult((current) => args.updateGraphPatchResultCandidateStatus(current, changeId, "CONFIRMED"));
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

  function handleRetryLastAuditRequest() {
    const failedRequest = args.qaRequestRecoveryState.lastFailedRequest;
    if (!failedRequest) {
      return;
    }
    args.setAuditQuestionDraft(failedRequest.question);
    args.setAuditQuestionMode(failedRequest.mode ?? "AUTO");
    args.setAuditTargetNodeIds(failedRequest.selectedNodeIds);
    args.setAuditSourceThreadId(failedRequest.sourceThreadId ?? null);
    activateAuditSection("audit.request-status");
    args.setActiveWorkbenchTab("audit");
    args.bridgeCommands.submitAsyncBridgeCommand("问答", () => retryLastAuditRequestAsync(), {
      successFeedback: {
        level: "INFO",
        message: "已提交失败问答的直接重试请求。",
      },
    });
  }

  function handleEditFailedAuditRequest() {
    const failedRequest = args.qaRequestRecoveryState.lastFailedRequest;
    if (!failedRequest) {
      return;
    }
    args.setAuditQuestionDraft(failedRequest.question);
    args.setAuditQuestionMode(failedRequest.mode ?? "AUTO");
    args.setAuditTargetNodeIds(failedRequest.selectedNodeIds);
    args.setAuditSourceThreadId(failedRequest.sourceThreadId ?? null);
    activateAuditSection("audit.composer");
    args.setActiveWorkbenchTab("audit");
    args.setOperationFeedback({
      level: "INFO",
      message: "已把失败问答回填到输入区，可修改后重新提交。",
    });
  }

  function handleSelectAuditChange(changeId: string) {
    args.setSelectedAuditChangeId(changeId);
    const change = args.auditResult?.candidateChanges.find((item) => item.changeId === changeId) ?? null;
    const targetNodeId = change
      ? args.resolveDisplayedNodeId(args.resolveEvidenceTargetNodeId(change.targetNodeIds, change.evidence), args.nodes)
      : null;
    if (!targetNodeId) {
      return;
    }
    args.selectExplanationTargetNode(targetNodeId, { focusViewport: true });
  }

  function handleSelectAuditThread(threadId: string) {
    args.setSelectedAuditThreadId(threadId);
    const thread = deriveInvestigationThreads(args.auditResult).find((item) => item.threadId === threadId) ?? null;
    const targetNodeId = thread
      ? args.resolveDisplayedNodeId(args.resolveEvidenceTargetNodeId(thread.targetNodeIds, thread.evidence), args.nodes)
      : null;
    if (!targetNodeId) {
      return;
    }
    args.selectExplanationTargetNode(targetNodeId, { focusViewport: true });
  }

  function handleInvestigateAuditThread(threadId: string) {
    const thread = deriveInvestigationThreads(args.auditResult).find((item) => item.threadId === threadId) ?? null;
    if (!thread) {
      return;
    }
    args.setSelectedAuditThreadId(threadId);
    args.setAuditSourceThreadId(threadId);
    const nextQuestion = thread.recommendedQuestion.trim()
      || `请继续取证：核对“${thread.title}”对应的直接源码证据。`;
    args.setAuditQuestionDraft(nextQuestion);
    args.setAuditTargetNodeIds(thread.targetNodeIds);
    const targetNodeId = args.resolveDisplayedNodeId(
      args.resolveEvidenceTargetNodeId(thread.targetNodeIds, thread.evidence),
      args.nodes,
    );
    if (targetNodeId) {
      args.selectExplanationTargetNode(targetNodeId, { focusViewport: true });
    }
    activateAuditSection("audit.composer");
    args.setActiveWorkbenchTab("audit");
    args.bridgeCommands.submitAsyncBridgeCommand("问答", () => requestAuditAsync(nextQuestion, thread.targetNodeIds, threadId, "INVESTIGATE"), {
      successFeedback: {
        level: "INFO",
        message: `已围绕风险线程“${thread.title}”自动发起继续取证。`,
      },
    });
  }

  function handleResolveAuditThread(
    threadId: string,
    resolutionStatus: "DEFERRED" | "ACCEPTED_RISK" | "DISMISSED",
  ) {
    args.setSelectedAuditThreadId(threadId);
    activateAuditSection("audit.investigation-threads");
    args.setActiveWorkbenchTab("audit");
    args.bridgeCommands.runBridgeCommand("风险决策", () => resolveInvestigationThread(threadId, resolutionStatus), {
      onAccepted: () => {
        args.setAuditResult((current) => args.updateGraphPatchResultThreadResolution(current, threadId, resolutionStatus));
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
    args.bridgeCommands.runBridgeCommand("取消确认候选变更", () => unconfirmAuditCandidateChange(changeId), {
      onAccepted: () => {
        args.setDraftWorkbenchState((current) => ({
          ...current,
          draftChanges: current.draftChanges.filter((item) => item.entryId !== entryId),
        }));
        args.setAuditResult((current) => args.updateGraphPatchResultCandidateStatus(current, changeId, "PENDING_CONFIRMATION"));
      },
      successFeedback: {
        level: "INFO",
        message: "已取消确认该候选变更，并从草稿层移除。",
      },
    });
  }

  useEffect(() => {
    const pendingChanges = args.auditResult?.candidateChanges.filter((change) => change.status === "PENDING_CONFIRMATION") ?? [];
    const firstChangeId = pendingChanges[0]?.changeId ?? null;
    args.setSelectedAuditChangeId((current) => {
      if (!pendingChanges.length) {
        return null;
      }
      return pendingChanges.some((change) => change.changeId === current) ? current : firstChangeId;
    });
  }, [args.auditResult, args.setSelectedAuditChangeId]);

  useEffect(() => {
    const investigationThreads = deriveInvestigationThreads(args.auditResult);
    const firstLeadId = investigationThreads[0]?.threadId ?? null;
    args.setSelectedAuditThreadId((current) => {
      if (!investigationThreads.length) {
        return null;
      }
      return investigationThreads.some((thread) => thread.threadId === current) ? current : firstLeadId;
    });
  }, [args.auditResult, args.setSelectedAuditThreadId]);

  return {
    handleRequestAudit,
    handleConfirmCandidateChange,
    handleRetryLastAuditRequest,
    handleEditFailedAuditRequest,
    handleSelectAuditChange,
    handleSelectAuditThread,
    handleInvestigateAuditThread,
    handleResolveAuditThread,
    handleUnconfirmDraftChange,
  };
}

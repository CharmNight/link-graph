import { useEffect, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { requestGraphBeautificationAsync } from "../api";
import { traceLinkGraph } from "../debug";
import type {
  AsyncRequestState,
  GraphBeautificationResult,
  LinkGraphNode,
  OperationFeedback,
  ResultEvidenceReference,
  StepGranularity,
} from "../types";
import type { useBridgeCommandController } from "./useBridgeCommandController";

type WorkbenchTab = "explanation" | "qa" | "draft" | "code";
type ExplanationRequestMode = "fresh" | "follow_up";

interface ExplanationHistoryEntry {
  result: GraphBeautificationResult;
  requestState: AsyncRequestState;
  selectedStepId: string | null;
  granularity: StepGranularity;
  sessionLabel: string;
}

interface UseExplanationWorkbenchControllerArgs {
  nodes: LinkGraphNode[];
  graphBeautificationResult: GraphBeautificationResult | null;
  graphBeautificationRequestState: AsyncRequestState;
  selectedExplanationGranularity: StepGranularity;
  selectedExplanationStepId: string | null;
  selectedNodeId: string | null;
  currentExplanationSessionLabel: string;
  explanationHistory: ExplanationHistoryEntry[];
  explanationLocalOverrideRef: MutableRefObject<boolean>;
  pendingExplanationRequestModeRef: MutableRefObject<ExplanationRequestMode | null>;
  pendingExplanationSessionLabelRef: MutableRefObject<string | null>;
  pendingExplanationHistoryEntryRef: MutableRefObject<ExplanationHistoryEntry | null>;
  pendingExplanationDrillTargetRef: MutableRefObject<string | null>;
  bridgeCommands: Pick<
    ReturnType<typeof useBridgeCommandController>,
    "submitAsyncBridgeCommand"
  >;
  setActiveWorkbenchTab: (tab: WorkbenchTab) => void;
  setSelectedExplanationStepId: Dispatch<SetStateAction<string | null>>;
  setHoveredExplanationStepId: Dispatch<SetStateAction<string | null>>;
  setSelectedExplanationGranularity: Dispatch<SetStateAction<StepGranularity>>;
  setCurrentExplanationSessionLabel: Dispatch<SetStateAction<string>>;
  setExplanationHistory: Dispatch<SetStateAction<ExplanationHistoryEntry[]>>;
  setGraphBeautificationResult: Dispatch<SetStateAction<GraphBeautificationResult | null>>;
  setGraphBeautificationRequestState: Dispatch<SetStateAction<AsyncRequestState>>;
  setSelectedNodeId: Dispatch<SetStateAction<string | null>>;
  setDetailNodeId: Dispatch<SetStateAction<string | null>>;
  setOperationFeedback: Dispatch<SetStateAction<OperationFeedback | null>>;
  selectExplanationTargetNode: (nodeId: string, options?: { focusViewport?: boolean }) => void;
  requestViewportFocus: (nodeId: string) => void;
  handleInspectNode: (nodeId: string) => void;
  handleExpandOverflowNode: (nodeId: string) => void;
  resolveDisplayedNodeId: (nodeId: string | null | undefined, nodes: LinkGraphNode[]) => string | null;
  defaultSessionLabel: string;
}

export function useExplanationWorkbenchController(args: UseExplanationWorkbenchControllerArgs) {
  function resetPendingExplanationRequest() {
    args.pendingExplanationRequestModeRef.current = null;
    args.pendingExplanationSessionLabelRef.current = null;
    args.pendingExplanationHistoryEntryRef.current = null;
  }

  function resolveExplanationStepRawNodeId(stepId: string) {
    const step = args.graphBeautificationResult?.steps.find((item) => item.stepId === stepId);
    return step?.primaryNodeId
      ?? step?.evidence.flatMap((finding) => finding.references).find((reference) => reference.nodeId)?.nodeId
      ?? null;
  }

  function resolveExplanationStepTargetNodeId(stepId: string) {
    return args.resolveDisplayedNodeId(
      resolveExplanationStepRawNodeId(stepId),
      args.nodes,
    );
  }

  function resolveExplanationStepFocusNodeId(stepId: string) {
    const rawNodeId = resolveExplanationStepRawNodeId(stepId)?.trim();
    if (!rawNodeId) {
      return null;
    }
    return args.resolveDisplayedNodeId(rawNodeId, args.nodes) ?? rawNodeId;
  }

  function resolveRequestFocusNodeId(nodeId: string | null | undefined) {
    const normalizedNodeId = nodeId?.trim();
    if (!normalizedNodeId) {
      return null;
    }
    return args.resolveDisplayedNodeId(normalizedNodeId, args.nodes) ?? normalizedNodeId;
  }

  function resolveCurrentExplanationFocusNodeId() {
    const selectedNodeFocusId = resolveRequestFocusNodeId(args.selectedNodeId);
    if (selectedNodeFocusId) {
      return selectedNodeFocusId;
    }
    if (args.selectedExplanationStepId) {
      const stepFocusNodeId = resolveExplanationStepFocusNodeId(args.selectedExplanationStepId);
      if (stepFocusNodeId) {
        return stepFocusNodeId;
      }
    }
    return resolveRequestFocusNodeId(args.selectedNodeId);
  }

  function handleRequestGraphBeautification(focusNodeId?: string) {
    const requestedFocusNodeId = resolveRequestFocusNodeId(focusNodeId ?? args.selectedNodeId);
    const focusNode = requestedFocusNodeId ? args.nodes.find((node) => node.id === requestedFocusNodeId) ?? null : null;
    const explanationFocus = focusNode
      ? `请重点讲解节点“${focusNode.title}”在当前链路中的作用、上下游关系与关键分支。`
      : undefined;
    traceLinkGraph("explanation.requestGraphBeautification.intent", {
      focusNodeId: requestedFocusNodeId,
      focusNodeTitle: focusNode?.title ?? null,
      selectedGranularity: args.selectedExplanationGranularity,
      nodeCount: args.nodes.length,
    });
    args.explanationLocalOverrideRef.current = false;
    args.pendingExplanationRequestModeRef.current = "fresh";
    args.bridgeCommands.submitAsyncBridgeCommand(
      "链路讲解",
      () => requestGraphBeautificationAsync({
        goal: "",
        preferredStyle: undefined,
        explanationFocus,
        focusNodeId: requestedFocusNodeId,
        granularity: args.selectedExplanationGranularity,
      }),
      {
        onRejected: resetPendingExplanationRequest,
        onAccepted: () => {
          args.pendingExplanationSessionLabelRef.current = args.defaultSessionLabel;
          args.pendingExplanationHistoryEntryRef.current = null;
          args.setActiveWorkbenchTab("explanation");
        },
        successFeedback: {
          level: "INFO",
          message: focusNode ? `已请求讲解节点：${focusNode.title}` : "已请求生成链路讲解。",
        },
      },
    );
  }

  function handleChangeExplanationGranularity(granularity: StepGranularity) {
    const requestedFocusNodeId = resolveCurrentExplanationFocusNodeId();
    traceLinkGraph("explanation.changeGranularity.intent", {
      focusNodeId: requestedFocusNodeId,
      selectedStepId: args.selectedExplanationStepId,
      selectedNodeId: args.selectedNodeId,
      granularity,
      nodeCount: args.nodes.length,
    });
    args.setSelectedExplanationGranularity(granularity);
    args.explanationLocalOverrideRef.current = false;
    args.pendingExplanationRequestModeRef.current = "fresh";
    args.bridgeCommands.submitAsyncBridgeCommand(
      "链路讲解",
      () => requestGraphBeautificationAsync({
        goal: "",
        preferredStyle: undefined,
        explanationFocus: undefined,
        focusNodeId: requestedFocusNodeId,
        granularity,
      }),
      {
        onRejected: resetPendingExplanationRequest,
        onAccepted: () => {
          args.pendingExplanationSessionLabelRef.current = args.defaultSessionLabel;
          args.pendingExplanationHistoryEntryRef.current = null;
          args.setSelectedExplanationStepId(null);
          args.setActiveWorkbenchTab("explanation");
        },
        successFeedback: {
          level: "INFO",
          message: `已切换讲解维度：${granularity === "BUSINESS" ? "业务级" : granularity === "METHOD_CALL" ? "方法调用级" : "代码语义级"}`,
        },
      },
    );
  }

  function handleHoverExplanationStep(stepId: string) {
    args.setHoveredExplanationStepId(stepId);
  }

  function handleLeaveExplanationStep() {
    args.setHoveredExplanationStepId(null);
  }

  function handleFollowUpExplanationStep(stepId: string, customQuestion?: string) {
    const step = args.graphBeautificationResult?.steps.find((item) => item.stepId === stepId);
    if (!step) {
      return;
    }
    const followUpQuestion = customQuestion?.trim()
      || step.followUpQuestions[0]
      || "请继续解释这一步的关键输入、条件和输出。";
    const requestedFocusNodeId = resolveExplanationStepFocusNodeId(stepId)
      ?? resolveRequestFocusNodeId(args.selectedNodeId);
    traceLinkGraph("explanation.followUp.intent", {
      focusNodeId: requestedFocusNodeId,
      stepId,
      selectedNodeId: args.selectedNodeId,
      granularity: args.selectedExplanationGranularity,
      nodeCount: args.nodes.length,
    });
    const nextSessionLabel = `围绕 ${step.title} 继续讲解`;
    args.explanationLocalOverrideRef.current = false;
    args.pendingExplanationRequestModeRef.current = "follow_up";
    args.bridgeCommands.submitAsyncBridgeCommand(
      "链路讲解追问",
      () => requestGraphBeautificationAsync({
        goal: "",
        preferredStyle: undefined,
        explanationFocus: undefined,
        focusNodeId: requestedFocusNodeId,
        granularity: args.selectedExplanationGranularity,
        followUp: {
          stepId: step.stepId,
          stepTitle: step.title,
          question: followUpQuestion,
        },
      }),
      {
        onRejected: resetPendingExplanationRequest,
        onAccepted: () => {
          args.pendingExplanationHistoryEntryRef.current = args.graphBeautificationResult
            ? {
                result: args.graphBeautificationResult,
                requestState: args.graphBeautificationRequestState,
                selectedStepId: args.selectedExplanationStepId,
                granularity: args.selectedExplanationGranularity,
                sessionLabel: args.currentExplanationSessionLabel,
              }
            : null;
          args.pendingExplanationSessionLabelRef.current = nextSessionLabel;
          args.setSelectedExplanationStepId(step.stepId);
          args.setActiveWorkbenchTab("explanation");
        },
        successFeedback: {
          level: "INFO",
          message: `已围绕步骤“${step.title}”继续请求讲解。`,
        },
      },
    );
  }

  function handleReturnToPreviousExplanation() {
    handleOpenExplanationHistory(args.explanationHistory.length - 1);
  }

  function handleOpenExplanationHistory(historyIndex: number) {
    args.setExplanationHistory((current) => {
      const snapshot = current[historyIndex];
      if (!snapshot) {
        return current;
      }
      args.explanationLocalOverrideRef.current = true;
      resetPendingExplanationRequest();
      args.setGraphBeautificationResult(snapshot.result);
      args.setGraphBeautificationRequestState(snapshot.requestState);
      args.setSelectedExplanationStepId(snapshot.selectedStepId);
      args.setSelectedExplanationGranularity(snapshot.granularity);
      args.setCurrentExplanationSessionLabel(snapshot.sessionLabel);
      args.setHoveredExplanationStepId(null);
      return current.slice(0, historyIndex);
    });
  }

  function handleSelectExplanationStep(stepId: string) {
    args.setSelectedExplanationStepId(stepId);
    const targetNodeId = resolveExplanationStepTargetNodeId(stepId);
    if (!targetNodeId) {
      return;
    }
    args.selectExplanationTargetNode(targetNodeId);
  }

  function handleLocateExplanationStepNode(stepId: string) {
    args.setSelectedExplanationStepId(stepId);
    const targetNodeId = resolveExplanationStepTargetNodeId(stepId);
    if (!targetNodeId) {
      args.setOperationFeedback({
        level: "WARNING",
        message: "当前步骤没有可定位的图节点。",
      });
      return;
    }
    args.selectExplanationTargetNode(targetNodeId, { focusViewport: true });
    const targetNode = args.nodes.find((node) => node.id === targetNodeId) ?? null;
    args.setOperationFeedback({
      level: "INFO",
      message: `已定位到图中节点：${targetNode?.title ?? targetNodeId}`,
    });
  }

  function handleInspectExplanationStepNode(stepId: string) {
    args.setSelectedExplanationStepId(stepId);
    const targetNodeId = resolveExplanationStepTargetNodeId(stepId);
    if (!targetNodeId) {
      args.setOperationFeedback({
        level: "WARNING",
        message: "当前步骤没有可编辑的图节点。",
      });
      return;
    }
    args.handleInspectNode(targetNodeId);
  }

  function isMethodLikeNodeId(nodeId: string | null | undefined): boolean {
    return Boolean(
      nodeId?.startsWith("method:") ||
      nodeId?.startsWith("invoke:"),
    );
  }

  function drillTargetLabel(nodeId: string | null | undefined): string {
    return isMethodLikeNodeId(nodeId) ? "被调方法" : "下钻节点";
  }

  function handleDrillDownExplanationStep(stepId: string) {
    const step = args.graphBeautificationResult?.steps.find((item) => item.stepId === stepId);
    const targetNodeId = step?.downstreamTargets[0] ?? null;
    if (!targetNodeId) {
      args.setOperationFeedback({
        level: "WARNING",
        message: "当前步骤没有可继续下钻的图节点。",
      });
      return;
    }
    const targetLabel = drillTargetLabel(targetNodeId);
    const targetNode = args.nodes.find((node) => node.id === targetNodeId) ?? null;
    if (!targetNode) {
      const downstreamOverflowNode = args.nodes.find(
        (node) => node.metadata?.["linkGraph.overflow.direction"] === "DOWNSTREAM",
      );
      if (!downstreamOverflowNode) {
        args.setOperationFeedback({
          level: "WARNING",
          message: `当前图中还没有展示这个${targetLabel}，请先扩展链路范围。`,
        });
        return;
      }
      args.pendingExplanationDrillTargetRef.current = targetNodeId;
      args.handleExpandOverflowNode(downstreamOverflowNode.id);
      args.setOperationFeedback({
        level: "INFO",
        message: `当前图中未展示该${targetLabel}，已尝试自动展开下游链路。`,
      });
      return;
    }
    args.setSelectedNodeId(targetNodeId);
    args.requestViewportFocus(targetNodeId);
    args.setDetailNodeId(targetNodeId);
    args.setOperationFeedback({
      level: "INFO",
      message: `已定位到${targetLabel}：${targetNode.title}`,
    });
  }

  function handleRevealExplanationReference(reference: ResultEvidenceReference) {
    if (reference.nodeId) {
      args.setDetailNodeId(reference.nodeId);
      return;
    }
    args.setOperationFeedback({
      level: "WARNING",
      message: "当前引用没有可直接跳转的节点标识。",
    });
  }

  useEffect(() => {
    if (args.graphBeautificationRequestState.phase !== "FAILED" && args.graphBeautificationRequestState.phase !== "TIMED_OUT") {
      return;
    }
    resetPendingExplanationRequest();
  }, [args.graphBeautificationRequestState.phase]);

  useEffect(() => {
    if (args.graphBeautificationRequestState.phase !== "SUCCEEDED" || args.graphBeautificationResult == null) {
      return;
    }
    const pendingMode = args.pendingExplanationRequestModeRef.current;
    const pendingSessionLabel = args.pendingExplanationSessionLabelRef.current;
    const pendingHistoryEntry = args.pendingExplanationHistoryEntryRef.current;

    if (pendingMode === "fresh") {
      args.setExplanationHistory([]);
    }
    if (pendingMode === "follow_up" && pendingHistoryEntry) {
      args.setExplanationHistory((current) => current.concat(pendingHistoryEntry));
    }
    if (pendingSessionLabel) {
      args.setCurrentExplanationSessionLabel(pendingSessionLabel);
    }

    resetPendingExplanationRequest();
  }, [args.graphBeautificationRequestState.phase, args.graphBeautificationResult]);

  useEffect(() => {
    const firstStepId = args.graphBeautificationResult?.steps?.[0]?.stepId ?? null;
    args.setSelectedExplanationStepId((current) => {
      if (!args.graphBeautificationResult?.steps?.length) {
        return null;
      }
      return args.graphBeautificationResult.steps.some((step) => step.stepId === current) ? current : firstStepId;
    });
    args.setHoveredExplanationStepId((current) =>
      args.graphBeautificationResult?.steps?.some((step) => step.stepId === current) ? current : null,
    );
  }, [args.graphBeautificationResult]);

  useEffect(() => {
    if (!args.graphBeautificationResult?.granularity) {
      return;
    }
    args.setSelectedExplanationGranularity(args.graphBeautificationResult.granularity);
  }, [args.graphBeautificationResult?.granularity]);

  useEffect(() => {
    const pendingTargetNodeId = args.pendingExplanationDrillTargetRef.current;
    if (!pendingTargetNodeId) {
      return;
    }
    const targetNode = args.nodes.find((node) => node.id === pendingTargetNodeId);
    if (!targetNode) {
      return;
    }
    args.pendingExplanationDrillTargetRef.current = null;
    args.setSelectedNodeId(targetNode.id);
    args.requestViewportFocus(targetNode.id);
    args.setDetailNodeId(targetNode.id);
    args.setOperationFeedback({
      level: "INFO",
      message: `已自动定位到展开后的${drillTargetLabel(targetNode.id)}：${targetNode.title}`,
    });
  }, [args.nodes]);

  return {
    handleRequestGraphBeautification,
    handleChangeExplanationGranularity,
    handleHoverExplanationStep,
    handleLeaveExplanationStep,
    handleFollowUpExplanationStep,
    handleReturnToPreviousExplanation,
    handleOpenExplanationHistory,
    handleSelectExplanationStep,
    handleLocateExplanationStepNode,
    handleInspectExplanationStepNode,
    handleDrillDownExplanationStep,
    handleRevealExplanationReference,
  };
}

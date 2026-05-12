import type { Dispatch, SetStateAction } from "react";
import type { WorkbenchTab } from "../components/AppWorkbenchPanels";
import { traceLinkGraph } from "../debug";
import type {
  AnalysisDisplayMode,
  AsyncRequestState,
  GenerationPlan,
  GenerationPlanDiscussionSession,
  LinkGraphNode,
  OperationFeedback,
  QaMode,
} from "../types";
import { resolveAuditTargetNodeIds } from "../appGraphSupport";
import type { useAppBridgeController } from "./useAppBridgeController";
import type { useAuditWorkbenchController } from "./useAuditWorkbenchController";
import type { useWorkbenchCommandController } from "./useWorkbenchCommandController";

interface UseAppWorkbenchShellControllerArgs {
  nodes: LinkGraphNode[];
  selectionGroupNodeIds: string[];
  mermaidDraft: string;
  activeWorkbenchTab: WorkbenchTab;
  selectedNodeId: string | null;
  analysisDisplayMode: AnalysisDisplayMode;
  generationPlan: GenerationPlan | null;
  generationPlanRequestState: AsyncRequestState;
  generationPlanDiscussionQuestionDraft: string;
  generationPlanDiscussionSession: GenerationPlanDiscussionSession | null;
  setAuditTargetNodeIds: Dispatch<SetStateAction<string[]>>;
  setAuditQuestionDraft: Dispatch<SetStateAction<string>>;
  setAuditQuestionMode: Dispatch<SetStateAction<QaMode>>;
  setAuditSourceThreadId: Dispatch<SetStateAction<string | null>>;
  setActiveWorkbenchTab: Dispatch<SetStateAction<WorkbenchTab>>;
  setOperationFeedback: Dispatch<SetStateAction<OperationFeedback | null>>;
  setDiffTargetItemIds: Dispatch<SetStateAction<string[]>>;
  handleRequestAudit: ReturnType<typeof useAuditWorkbenchController>["handleRequestAudit"];
  handleInspectNode: (nodeId: string) => void;
  bridgeCommands: Pick<
    ReturnType<typeof useAppBridgeController>,
    "handleConfirmImportMermaid"
  >;
  workbenchCommands: Pick<
    ReturnType<typeof useWorkbenchCommandController>,
    "handleRequestGenerationPlan" | "handleRequestGenerationPlanDiscussion" | "handleRequestCodeDrafts" | "handleExpandOverflowNode"
  >;
}

function buildDefaultAuditQuestion(targetNodeIds: string[], targetTitle: string | null): string {
  if (targetNodeIds.length === 0) {
    return "请围绕当前整张链路图进行问答，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。";
  }
  if (targetNodeIds.length === 1) {
    return `请围绕节点“${targetTitle ?? targetNodeIds[0]}”及其直接关联链路进行问答，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。`;
  }
  return `请围绕当前选中的 ${targetNodeIds.length} 个节点及其关联链路进行问答，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。`;
}

export function useAppWorkbenchShellController(args: UseAppWorkbenchShellControllerArgs) {
  function resolveAuditScope(targetNodeId?: string) {
    const nodeIds = resolveAuditTargetNodeIds(targetNodeId, args.selectionGroupNodeIds);
    return {
      nodeIds,
      title: nodeIds.length === 1
        ? args.nodes.find((node) => node.id === nodeIds[0])?.title ?? null
        : null,
    };
  }

  function handleOpenAudit(targetNodeId?: string) {
    const scope = resolveAuditScope(targetNodeId);
    args.setAuditTargetNodeIds(scope.nodeIds);
    args.setAuditQuestionDraft(buildDefaultAuditQuestion(scope.nodeIds, scope.title));
    args.setAuditQuestionMode("AUTO");
    args.setAuditSourceThreadId(null);
    args.setActiveWorkbenchTab("audit");
  }

  function handleOpenDraftValidation() {
    args.setActiveWorkbenchTab("code");
  }

  function handleRequestGenerationPlan() {
    traceLinkGraph("app.requestGenerationPlan.intent", {
      activeWorkbenchTab: args.activeWorkbenchTab,
      selectedNodeId: args.selectedNodeId,
      analysisDisplayMode: args.analysisDisplayMode,
      generationPlanRequestPhase: args.generationPlanRequestState.phase,
      hasGenerationPlan: args.generationPlan != null,
    });
    args.setActiveWorkbenchTab("code");
    args.workbenchCommands.handleRequestGenerationPlan();
  }

  function handleRequestCodeDrafts() {
    args.setActiveWorkbenchTab("code");
    args.workbenchCommands.handleRequestCodeDrafts();
  }

  function handleRequestGenerationPlanDiscussion() {
    const question = args.generationPlanDiscussionQuestionDraft.trim();
    if (!question) {
      args.setOperationFeedback({
        level: "WARNING",
        message: "请先输入你对实现建议的追问。",
      });
      return;
    }
    args.setActiveWorkbenchTab("code");
    args.workbenchCommands.handleRequestGenerationPlanDiscussion(
      question,
      args.generationPlanDiscussionSession?.focusItemId ?? null,
    );
  }

  function handleRequestScopedAudit(targetNodeId?: string) {
    const scope = resolveAuditScope(targetNodeId);
    const question = buildDefaultAuditQuestion(scope.nodeIds, scope.title);
    args.setAuditTargetNodeIds(scope.nodeIds);
    args.setAuditQuestionDraft(question);
    args.setAuditQuestionMode("AUTO");
    args.setAuditSourceThreadId(null);
    args.handleRequestAudit(question, scope.nodeIds, "AUTO");
  }

  function handleConfirmImportMermaidDraft() {
    const mermaid = args.mermaidDraft.trim();
    if (mermaid.length === 0) {
      args.setOperationFeedback({
        level: "WARNING",
        message: "请输入 Mermaid 内容后再导入。",
      });
      return;
    }
    args.bridgeCommands.handleConfirmImportMermaid(mermaid);
  }

  function handleExpandOverflowNode(nodeId: string) {
    const nodeTitle = args.nodes.find((node) => node.id === nodeId)?.title ?? nodeId;
    args.workbenchCommands.handleExpandOverflowNode(nodeId, nodeTitle);
  }

  function handleFocusDiffItem(itemId: string) {
    args.setDiffTargetItemIds([itemId]);
    if (args.nodes.some((node) => node.id === itemId)) {
      args.handleInspectNode(itemId);
      return;
    }
    args.setOperationFeedback({
      level: "INFO",
      message: `已聚焦差异项：${itemId}`,
    });
  }

  return {
    handleOpenAudit,
    handleOpenDraftValidation,
    handleRequestGenerationPlan,
    handleRequestCodeDrafts,
    handleRequestGenerationPlanDiscussion,
    handleRequestScopedAudit,
    handleConfirmImportMermaidDraft,
    handleExpandOverflowNode,
    handleFocusDiffItem,
  };
}

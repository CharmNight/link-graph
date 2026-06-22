import { buildDefaultExplanationPrompt } from "./assistant/assistantPromptDefaults";
import type { AnalysisDisplayMode, AsyncRequestState } from "./types";
import type { CodeDiffStatus } from "./workbenchStatusModel";
import type { WorkflowStage } from "./workflow/workflowStage";

export type PrimaryWorkflowCodeDiffStatus = CodeDiffStatus;

export interface PrimaryWorkflowActionState {
  activeWorkflowStage: WorkflowStage;
  analysisDisplayMode: AnalysisDisplayMode;
  graphBeautificationRequestState: AsyncRequestState;
  qaRequestState: AsyncRequestState;
  generationPlanRequestState: AsyncRequestState;
  codeDraftRequestState: AsyncRequestState;
  codeDiffStatus: PrimaryWorkflowCodeDiffStatus;
  generatedCodeDraftCount: number;
}

export type PrimaryWorkflowActionCommand =
  | { kind: "SUBMIT_ASSISTANT"; intent: "EXPLAIN_CODE" | "ASK_CODE"; prompt: string }
  | { kind: "OPEN_QA"; targetNodeId: string | null }
  | { kind: "PRIME_GENERATION_PLAN" }
  | { kind: "WRITE_DRAFTS" }
  | { kind: "REQUEST_CODE_DRAFTS" };

export interface PrimaryWorkflowActionCommandState extends PrimaryWorkflowActionState {
  assistantComposerDraft: string;
  selectedNodeId: string | null;
  selectedNodeTitle: string | null;
  assistantTargetTitle: string | null;
}

export function primaryWorkflowActionLabel(state: PrimaryWorkflowActionState): string {
  switch (state.activeWorkflowStage) {
    case "understand":
      if (state.analysisDisplayMode === "CLASS_DIAGRAM") {
        return state.graphBeautificationRequestState.phase === "RUNNING" ? "解释中" : "解释类关系";
      }
      return state.graphBeautificationRequestState.phase === "RUNNING" ? "讲解中" : "链路讲解";
    case "evidence":
      return "去问答";
    case "qa":
      if (state.analysisDisplayMode === "CLASS_DIAGRAM") {
        return state.qaRequestState.phase === "RUNNING" ? "问答中" : "类图问答";
      }
      return state.qaRequestState.phase === "RUNNING" ? "问答中" : "代码问答";
    case "draft":
      return state.generationPlanRequestState.phase === "RUNNING" ? "生成中" : "生成实现建议";
    case "code":
      return state.codeDiffStatus === "FRESH"
        ? "写入全部"
        : state.codeDraftRequestState.phase === "RUNNING" ? "生成中" : "生成代码 diff";
  }
}

export function primaryWorkflowActionDisabled(state: PrimaryWorkflowActionState): boolean {
  switch (state.activeWorkflowStage) {
    case "understand":
      return state.graphBeautificationRequestState.phase === "RUNNING";
    case "qa":
      return state.qaRequestState.phase === "RUNNING";
    case "draft":
      return state.generationPlanRequestState.phase === "RUNNING";
    case "code":
      return state.codeDraftRequestState.phase === "RUNNING"
        || (state.codeDiffStatus === "FRESH" ? state.generatedCodeDraftCount === 0 : false);
    case "evidence":
    default:
      return false;
  }
}

export function primaryWorkflowActionCommand(state: PrimaryWorkflowActionCommandState): PrimaryWorkflowActionCommand {
  switch (state.activeWorkflowStage) {
    case "understand": {
      const defaultPromptTitle = state.analysisDisplayMode === "CLASS_DIAGRAM"
        ? state.assistantTargetTitle
        : state.selectedNodeTitle;
      return {
        kind: "SUBMIT_ASSISTANT",
        intent: "EXPLAIN_CODE",
        prompt: state.assistantComposerDraft || buildDefaultExplanationPrompt(defaultPromptTitle, state.analysisDisplayMode),
      };
    }
    case "evidence":
      return {
        kind: "OPEN_QA",
        targetNodeId: state.selectedNodeId,
      };
    case "qa":
      if (state.assistantComposerDraft.trim()) {
        return {
          kind: "SUBMIT_ASSISTANT",
          intent: "ASK_CODE",
          prompt: state.assistantComposerDraft,
        };
      }
      return {
        kind: "OPEN_QA",
        targetNodeId: state.selectedNodeId,
      };
    case "draft":
      return { kind: "PRIME_GENERATION_PLAN" };
    case "code":
      return state.codeDiffStatus === "FRESH"
        ? { kind: "WRITE_DRAFTS" }
        : { kind: "REQUEST_CODE_DRAFTS" };
  }
}

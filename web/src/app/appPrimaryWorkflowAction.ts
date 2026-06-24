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
  blockingRiskCount: number;
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

/** 阶段位置文案：用于 subtitle 中"位置"段的展示。 */
const STAGE_POSITION_LABEL: Record<WorkflowStage, string> = {
  understand: "理解链路",
  evidence: "核验证据",
  qa: "风险问答",
  draft: "采纳改动",
  code: "写入代码",
};

/** 阶段序号（1-based），用于 subtitle 中"阶段 N / 5"段。 */
const STAGE_INDEX: Record<WorkflowStage, number> = {
  understand: 1,
  evidence: 2,
  qa: 3,
  draft: 4,
  code: 5,
};

const TOTAL_STAGES = 5;

function isAnyAsyncRunning(state: PrimaryWorkflowActionState): boolean {
  return (
    state.graphBeautificationRequestState.phase === "RUNNING"
    || state.qaRequestState.phase === "RUNNING"
    || state.generationPlanRequestState.phase === "RUNNING"
    || state.codeDraftRequestState.phase === "RUNNING"
  );
}

/** 主标题：固定"下一步"，进行中显示"处理中"。位置语义稳定，具体动作交给 actionLabel / subtitle。 */
export function primaryWorkflowActionLabel(state: PrimaryWorkflowActionState): string {
  if (isAnyAsyncRunning(state)) {
    return "处理中";
  }
  return "下一步";
}

/** 具体动作文案：根据阶段与展示模式给出该阶段的主操作动作描述。 */
export function primaryWorkflowActionActionLabel(state: PrimaryWorkflowActionState): string {
  switch (state.activeWorkflowStage) {
    case "understand":
      if (state.analysisDisplayMode === "CLASS_DIAGRAM") {
        return state.graphBeautificationRequestState.phase === "RUNNING" ? "解释中" : "解释类关系";
      }
      return state.graphBeautificationRequestState.phase === "RUNNING" ? "讲解中" : "生成链路讲解";
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
        ? "写入全部 diff"
        : state.codeDraftRequestState.phase === "RUNNING" ? "生成中" : "生成代码 diff";
  }
}

/** 副标题：组合"阶段 N / 总数 · 位置 · 具体动作"，让用户一眼看出当前进度与意图。 */
export function primaryWorkflowActionSubtitle(state: PrimaryWorkflowActionState): string {
  const index = STAGE_INDEX[state.activeWorkflowStage];
  const position = STAGE_POSITION_LABEL[state.activeWorkflowStage];
  const action = primaryWorkflowActionActionLabel(state);
  return `阶段 ${index} / ${TOTAL_STAGES} · ${position} · ${action}`;
}

/** 是否禁用主操作：异步进行中、fresh 但空草稿、或写入阶段存在阻塞风险时禁用。 */
export function primaryWorkflowActionDisabled(state: PrimaryWorkflowActionState): boolean {
  if (isAnyAsyncRunning(state)) {
    return true;
  }
  if (state.activeWorkflowStage === "code" && state.codeDiffStatus === "FRESH") {
    if (state.generatedCodeDraftCount === 0) {
      return true;
    }
    if (state.blockingRiskCount > 0) {
      return true;
    }
  }
  return false;
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

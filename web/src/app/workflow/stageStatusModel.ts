import type { AsyncRequestState } from "../types";
import type { CodeDiffStatus } from "../workbenchStatusModel";
import {
  WORKFLOW_STAGE_DEFINITIONS,
  type WorkflowStage,
} from "./workflowStage";

/**
 * 单个阶段的运行态：用于左侧阶段引导条渲染。
 *
 * - `done`    该阶段已产出可消费的结果（如讲解已生成、改动已采纳）
 * - `active`  当前所处阶段
 * - `blocked` 该阶段当前无法进入（如存在阻塞风险）
 * - `idle`    尚未触达，可点击进入
 */
export type StageRuntimeStatus = "done" | "active" | "blocked" | "idle";

export interface StageStatusEntry {
  stage: WorkflowStage;
  /** 顺序编号（1-based），与引导条上的圆点数字一致。 */
  index: number;
  label: string;
  status: StageRuntimeStatus;
  /** 引导条上的简短说明，例如「已生成 3 步讲解」「2 处待确认」。可为 null。 */
  meta: string | null;
}

export interface StageStatusInput {
  activeStage: WorkflowStage;
  graphBeautificationRequestState: AsyncRequestState;
  qaRequestState: AsyncRequestState;
  generationPlanRequestState: AsyncRequestState;
  codeDraftRequestState: AsyncRequestState;
  codeDiffStatus: CodeDiffStatus;
  /** 已采纳的改动数（draftWorkbenchState.draftChanges 长度）。 */
  confirmedDraftCount: number;
  /** 待确认的候选变更数。 */
  pendingCandidateCount: number;
  /** 阻塞风险数。 */
  blockingRiskCount: number;
  /** 已生成的代码 diff 条数。 */
  generatedCodeDraftCount: number;
}

function isSucceeded(state: AsyncRequestState): boolean {
  return state.phase === "SUCCEEDED";
}

/**
 * 派生全部 5 个阶段的运行态。
 *
 * 规则保持保守与可解释：
 * - `understand` 完成 ⇔ 讲解请求成功过一次
 * - `evidence` 不单独承载异步请求，跟随 understand（理解完即可核验证据），
 *   因此只区分 active / idle，不判 done
 * - `qa` 完成 ⇔ 问答请求成功过一次
 * - `draft` 完成 ⇔ 存在已采纳改动
 * - `code` 阻塞 ⇔ 存在阻塞风险（写盘前需先处理）；完成 ⇔ 已生成 diff
 *
 * 门禁只影响 `blocked` 状态的判定，真正的「能否进入」由
 * {@link ../stageGatePolicy} 决定；这里只负责给引导条一个可读的状态。
 */
export function deriveStageStatuses(input: StageStatusInput): StageStatusEntry[] {
  const {
    activeStage,
    graphBeautificationRequestState,
    qaRequestState,
    codeDiffStatus,
    confirmedDraftCount,
    pendingCandidateCount,
    blockingRiskCount,
    generatedCodeDraftCount,
  } = input;

  const understandDone = isSucceeded(graphBeautificationRequestState);
  const qaDone = isSucceeded(qaRequestState);
  const draftDone = confirmedDraftCount > 0;
  const codeDone = codeDiffStatus === "FRESH" && generatedCodeDraftCount > 0;
  const codeBlocked = blockingRiskCount > 0;

  function statusFor(stage: WorkflowStage): StageRuntimeStatus {
    if (stage === activeStage) {
      // 当前阶段即便存在阻塞风险，也按 active 渲染（用户就在这里）。
      return "active";
    }
    if (stage === "code" && codeBlocked) {
      return "blocked";
    }
    switch (stage) {
      case "understand":
        return understandDone ? "done" : "idle";
      case "evidence":
        return understandDone ? "done" : "idle";
      case "qa":
        return qaDone ? "done" : "idle";
      case "draft":
        return draftDone ? "done" : "idle";
      case "code":
        return codeDone ? "done" : "idle";
    }
  }

  function metaFor(stage: WorkflowStage): string | null {
    switch (stage) {
      case "understand":
        return understandDone ? "讲解已生成" : null;
      case "evidence":
        return null;
      case "qa":
        if (pendingCandidateCount > 0) {
          return `${pendingCandidateCount} 处待确认`;
        }
        return qaDone ? "已完成" : null;
      case "draft":
        return draftDone ? `已采纳 ${confirmedDraftCount} 项` : null;
      case "code":
        if (codeBlocked) {
          return `${blockingRiskCount} 处阻塞风险`;
        }
        return codeDone ? `已生成 ${generatedCodeDraftCount} 条 diff` : null;
    }
  }

  return WORKFLOW_STAGE_DEFINITIONS.map((definition, index) => ({
    stage: definition.id,
    index: index + 1,
    label: definition.label,
    status: statusFor(definition.id),
    meta: metaFor(definition.id),
  }));
}

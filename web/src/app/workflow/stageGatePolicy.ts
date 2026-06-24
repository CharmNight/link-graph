import type { WorkflowStage } from "./workflowStage";

/**
 * 阶段跳转门禁：当用户从左侧阶段引导条手动点击一个阶段时，决定是否放行。
 *
 * 设计边界（重要）：
 * - 门禁**只作用于手动点击**阶段引导条的场景。
 * - 意图驱动的自动切换（如点讲解意图自动切到 understand）**不走门禁**，
 *   因为那是正常流程推进，强行拦截会破坏现有交互。见
 *   {@link ../assistant/useAssistantActionController} 的 handleAssistantSubmit。
 *
 * 规则保持最小、可解释：
 * - 进入 `code`（写入代码）阶段时，要求 (1) 没有阻塞风险 (2) 至少确认一份草稿。
 *   写盘是破坏性动作，没有确认草稿就进入 code 阶段等于让用户面对空白编辑器胡乱写。
 * - 其余阶段之间自由跳转：工作流是阅读辅助，不是强制向导。
 */
export type StageGateDecision =
  | { ok: true }
  | { ok: false; reason: string };

export interface StageGateContext {
  /** 阻塞风险数（来自 deriveChangeTrayState.blockingRiskCount）。 */
  blockingRiskCount: number;
  /** 是否已确认至少一份草稿（来自 changeTrayState.confirmedDraftCount > 0）。 */
  hasConfirmedDraft: boolean;
}

export function canEnterStage(
  stage: WorkflowStage,
  ctx: StageGateContext,
): StageGateDecision {
  if (stage !== "code") {
    return { ok: true };
  }
  if (ctx.blockingRiskCount > 0) {
    return {
      ok: false,
      reason: `当前有 ${ctx.blockingRiskCount} 处阻塞风险，写盘前需先处理或显式接受风险。`,
    };
  }
  if (!ctx.hasConfirmedDraft) {
    return {
      ok: false,
      reason: "进入代码阶段前，请先确认一份草稿。",
    };
  }
  return { ok: true };
}

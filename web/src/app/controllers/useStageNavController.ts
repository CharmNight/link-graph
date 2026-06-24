import { useCallback, useMemo } from "react";
import type { Dispatch, SetStateAction } from "react";
import type { AsyncRequestState, OperationFeedback } from "../types";
import type { CodeDiffStatus } from "../workbenchStatusModel";
import { canEnterStage } from "../workflow/stageGatePolicy";
import {
  deriveStageStatuses,
  type StageStatusEntry,
  type StageStatusInput,
} from "../workflow/stageStatusModel";
import type { WorkflowStage } from "../workflow/workflowStage";

/**
 * 把阶段引导条需要的状态派生与门禁逻辑从 App.tsx 抽出，
 * 让顶层组装件保持在重构阈值以下（见 workbenchArchitecture 行数守护）。
 *
 * 返回：
 * - `stageStatusEntries`：5 个阶段的运行态，供 {@link WorkflowStageNav} 渲染
 * - `handleSelectStage`：用户点击阶段时的入口，内部走 {@link canEnterStage} 门禁
 */
/** 阶段导航控制器的入参：汇总上层 App 注入的当前阶段、阶段切换入口与各阶段所需异步/计数状态。 */
export interface UseStageNavControllerArgs {
  /** 当前激活阶段。 */
  activeStage: WorkflowStage;
  /** 切换激活阶段的 setter。 */
  setActiveStage: Dispatch<SetStateAction<WorkflowStage>>;
  /** 设置工具栏操作反馈（用于门禁拒绝时给出提示）。 */
  setOperationFeedback: (feedback: OperationFeedback | null) => void;
  /** 图谱美化请求态。 */
  graphBeautificationRequestState: AsyncRequestState;
  /** 问答请求态。 */
  qaRequestState: AsyncRequestState;
  /** 生成方案请求态。 */
  generationPlanRequestState: AsyncRequestState;
  /** 代码草稿请求态。 */
  codeDraftRequestState: AsyncRequestState;
  /** 代码 Diff 状态。 */
  codeDiffStatus: CodeDiffStatus;
  /** 已确认草稿数。 */
  confirmedDraftCount: number;
  /** 待处理候选数。 */
  pendingCandidateCount: number;
  /** 阻塞风险数（影响阶段门禁）。 */
  blockingRiskCount: number;
  /** 已生成代码草稿数。 */
  generatedCodeDraftCount: number;
}

/** 阶段导航控制器的返回值：暴露阶段运行态列表与点击处理回调。 */
export interface UseStageNavControllerResult {
  /** 5 个阶段的运行态列表，供阶段导航条渲染。 */
  stageStatusEntries: StageStatusEntry[];
  /** 用户点击某阶段时的入口：内部走门禁逻辑，不通过则提示，通过则切换阶段。 */
  handleSelectStage: (stage: WorkflowStage) => void;
}

export function useStageNavController(
  args: UseStageNavControllerArgs,
): UseStageNavControllerResult {
  const stageStatusInput: StageStatusInput = useMemo(() => ({
    activeStage: args.activeStage,
    graphBeautificationRequestState: args.graphBeautificationRequestState,
    qaRequestState: args.qaRequestState,
    generationPlanRequestState: args.generationPlanRequestState,
    codeDraftRequestState: args.codeDraftRequestState,
    codeDiffStatus: args.codeDiffStatus,
    confirmedDraftCount: args.confirmedDraftCount,
    pendingCandidateCount: args.pendingCandidateCount,
    blockingRiskCount: args.blockingRiskCount,
    generatedCodeDraftCount: args.generatedCodeDraftCount,
  }), [
    args.activeStage,
    args.graphBeautificationRequestState,
    args.qaRequestState,
    args.generationPlanRequestState,
    args.codeDraftRequestState,
    args.codeDiffStatus,
    args.confirmedDraftCount,
    args.pendingCandidateCount,
    args.blockingRiskCount,
    args.generatedCodeDraftCount,
  ]);

  const stageStatusEntries = useMemo(
    () => deriveStageStatuses(stageStatusInput),
    [stageStatusInput],
  );

  const handleSelectStage = useCallback((stage: WorkflowStage) => {
    const decision = canEnterStage(stage, {
      blockingRiskCount: args.blockingRiskCount,
    });
    if (!decision.ok) {
      args.setOperationFeedback({
        level: "WARNING",
        message: decision.reason,
      });
      return;
    }
    args.setActiveStage(stage);
  }, [
    args.blockingRiskCount,
    args.setOperationFeedback,
    args.setActiveStage,
  ]);

  return {
    stageStatusEntries,
    handleSelectStage,
  };
}

// 助理会话线程组件：按时间顺序渲染所有轮次。
// 每种轮次类型（EXPLANATION/QA/GENERATION_PLAN/CODE_DRAFT/CHECK_RESULT）
// 渲染为对应的卡片组件，QA 和 CHECK_RESULT 还会附带候选变更和风险线程卡片。
// 空线程时展示引导文案。
import type {
  AssistantTurn,
  QaRequestRecoveryState,
  ResultEvidenceReference,
  RiskResolutionStatus,
  StepGranularity,
} from "../types";
import type { AssistantArtifactAccess } from "./assistantArtifacts";
import { CandidateChangeCard } from "./cards/CandidateChangeCard";
import { CheckTurnCard } from "./cards/CheckTurnCard";
import { CodeDraftTurnCard } from "./cards/CodeDraftTurnCard";
import { ExplanationTurnCard } from "./cards/ExplanationTurnCard";
import { GenerationTurnCard } from "./cards/GenerationTurnCard";
import { QaTurnCard } from "./cards/QaTurnCard";
import { RiskThreadCard } from "./cards/RiskThreadCard";

/** AssistantThread 组件的入参（继承自产物访问能力接口）。 */
interface AssistantThreadProps extends AssistantArtifactAccess {
  /** 按时间排序的轮次列表。 */
  turns: AssistantTurn[];
  /** 请求是否正在运行。 */
  requestRunning: boolean;
  /** QA 请求恢复状态。 */
  qaRequestRecoveryState?: QaRequestRecoveryState | null;
  onRetryLastQaRequest: () => void;
  onEditFailedQaRequest: () => void;
  onPrimeGenerationPlan: () => void;
  onRequestCodeDrafts: () => void;
  onWriteCodeDrafts: () => void;
  onWriteSingleCodeDraft?: (draftId: string) => void;
  onOpenNativeDiff?: (draftId: string) => void;
  onOpenDraft?: (targetPath: string) => void;
  onRevealReference: (reference: ResultEvidenceReference) => void;
  // 讲解步骤相关 props（仅对最新讲解轮次生效）
  selectedExplanationStepId?: string | null;
  selectedExplanationGranularity?: StepGranularity;
  explanationHistoryTrail?: string[];
  previousExplanationSessionLabel?: string | null;
  onSelectExplanationStep?: (stepId: string) => void;
  onLocateExplanationStepNode?: (stepId: string) => void;
  onInspectExplanationStepNode?: (stepId: string) => void;
  onHoverExplanationStep?: (stepId: string) => void;
  onLeaveExplanationStep?: () => void;
  onChangeExplanationGranularity?: (granularity: StepGranularity) => void;
  onFollowUpExplanationStep?: (stepId: string, question?: string) => void;
  onReturnToPreviousExplanation?: () => void;
  onOpenExplanationHistory?: (historyIndex: number) => void;
  canReturnToPreviousExplanation?: boolean;
  onDiscussGenerationPlan?: (question?: string) => void;
  onConfirmCandidateChange?: (changeId: string) => void;
  onInvestigateThread?: (threadId: string) => void;
  onResolveThread?: (threadId: string, status: RiskResolutionStatus) => void;
}

/**
 * 助理会话线程组件。
 *
 * 按轮次类型分发到不同卡片：
 * - EXPLANATION → ExplanationTurnCard（含步骤导航、粒度切换、历史回溯）；
 * - QA → QaTurnCard + CandidateChangeCard + RiskThreadCard（问答结果 + 候选变更 + 风险线程）；
 * - GENERATION_PLAN → GenerationTurnCard（实现建议 + 讨论区）；
 * - CODE_DRAFT → 可选 GenerationTurnCard（嵌入展示）+ CodeDraftTurnCard（代码草稿）；
 * - CHECK_RESULT → CheckTurnCard + CandidateChangeCard + RiskThreadCard。
 *
 * 讲解步骤相关 props 仅对"最新讲解轮次"生效——历史轮次不响应步骤选择/粒度切换等交互。
 */
export function AssistantThread({
  turns,
  requestRunning,
  qaRequestRecoveryState,
  onRetryLastQaRequest,
  onEditFailedQaRequest,
  onPrimeGenerationPlan,
  onRequestCodeDrafts,
  onWriteCodeDrafts,
  onWriteSingleCodeDraft,
  onOpenNativeDiff,
  onOpenDraft,
  onRevealReference,
  selectedExplanationStepId,
  selectedExplanationGranularity,
  explanationHistoryTrail,
  previousExplanationSessionLabel,
  onSelectExplanationStep,
  onLocateExplanationStepNode,
  onInspectExplanationStepNode,
  onHoverExplanationStep,
  onLeaveExplanationStep,
  onChangeExplanationGranularity,
  onFollowUpExplanationStep,
  onReturnToPreviousExplanation,
  onOpenExplanationHistory,
  canReturnToPreviousExplanation = false,
  onDiscussGenerationPlan,
  onConfirmCandidateChange,
  onInvestigateThread,
  onResolveThread,
  resolveArtifactText,
  onRequestArtifact,
}: AssistantThreadProps) {
  // 找到最新的讲解轮次 ID（只有该轮次响应步骤交互）
  const latestExplanationTurnId = [...turns].reverse().find((turn) => turn.kind === "EXPLANATION")?.turnId ?? null;
  return (
    <div className="assistant-thread" aria-label="AI 工作台对话线程">
      {turns.length === 0 ? (
        // 空线程：引导文案
        <div className="assistant-thread-empty">
          <strong className="assistant-result-text">还没有 AI 结果</strong>
          <p className="muted assistant-result-text">在底部输入问题并选择发送动作即可开始。</p>
        </div>
      ) : (
        <>
          {turns.map((turn, turnIndex) => {
            // 判断是否为最新讲解轮次（用于决定是否传递交互 props）
            const isLatestExplanation = turn.turnId === latestExplanationTurnId;
            const isLatestTurn = turnIndex === turns.length - 1;
            switch (turn.kind) {
              case "EXPLANATION":
                return (
                  <ExplanationTurnCard
                    key={turn.turnId}
                    turn={turn}
                    turnIndex={turnIndex}
                    isLatest={isLatestTurn}
                    onRevealReference={onRevealReference}
                    // 仅最新讲解轮次传递步骤交互 props
                    selectedStepId={isLatestExplanation ? selectedExplanationStepId : undefined}
                    currentGranularity={isLatestExplanation ? selectedExplanationGranularity : undefined}
                    granularityRequestRunning={requestRunning}
                    historyTrail={isLatestExplanation ? explanationHistoryTrail : undefined}
                    previousSessionLabel={isLatestExplanation ? previousExplanationSessionLabel : null}
                    onSelectStep={onSelectExplanationStep}
                    onLocateStepNode={onLocateExplanationStepNode}
                    onInspectStepNode={onInspectExplanationStepNode}
                    onHoverStep={onHoverExplanationStep}
                    onLeaveStep={onLeaveExplanationStep}
                    onGranularityChange={isLatestExplanation ? onChangeExplanationGranularity : undefined}
                    onFollowUpStep={isLatestExplanation ? onFollowUpExplanationStep : undefined}
                    onReturnToPrevious={isLatestExplanation ? onReturnToPreviousExplanation : undefined}
                    onOpenHistory={isLatestExplanation ? onOpenExplanationHistory : undefined}
                    canReturnToPrevious={isLatestExplanation ? canReturnToPreviousExplanation : false}
                    resolveArtifactText={resolveArtifactText}
                    onRequestArtifact={onRequestArtifact}
                  />
                );
              case "QA":
                return (
                  // QA 轮次组：问答卡片 + 候选变更 + 风险线程
                  <div key={turn.turnId} className="assistant-turn-group">
                    <QaTurnCard
                      turn={turn}
                      turnIndex={turnIndex}
                      isLatest={isLatestTurn}
                      recoveryState={qaRequestRecoveryState}
                      onRetryLastQaRequest={onRetryLastQaRequest}
                      onEditFailedQaRequest={onEditFailedQaRequest}
                      onRevealReference={onRevealReference}
                      resolveArtifactText={resolveArtifactText}
                      onRequestArtifact={onRequestArtifact}
                    />
                    <CandidateChangeCard
                      changes={turn.qa?.candidateChanges ?? []}
                      onConfirmCandidateChange={onConfirmCandidateChange}
                    />
                    <RiskThreadCard
                      threads={turn.qa?.investigationThreads ?? []}
                      onInvestigateThread={onInvestigateThread}
                      onResolveThread={onResolveThread}
                    />
                  </div>
                );
              case "GENERATION_PLAN":
                return (
                  <GenerationTurnCard
                    key={turn.turnId}
                    turn={turn}
                    turnIndex={turnIndex}
                    isLatest={isLatestTurn}
                    onPrimeGenerationPlan={onPrimeGenerationPlan}
                    onRequestCodeDrafts={onRequestCodeDrafts}
                    onDiscussGenerationPlan={onDiscussGenerationPlan}
                    resolveArtifactText={resolveArtifactText}
                    onRequestArtifact={onRequestArtifact}
                  />
                );
              case "CODE_DRAFT":
                return (
                  // 代码草稿轮次组：可选嵌入展示上一轮的计划 + 代码草稿
                  <div key={turn.turnId} className="assistant-turn-group">
                    {turn.generationPlan || turn.generationDiscussionSession ? (
                      <GenerationTurnCard
                        turn={turn}
                        showTurnHeader={false}
                        onPrimeGenerationPlan={onPrimeGenerationPlan}
                        onRequestCodeDrafts={onRequestCodeDrafts}
                        onDiscussGenerationPlan={onDiscussGenerationPlan}
                        resolveArtifactText={resolveArtifactText}
                        onRequestArtifact={onRequestArtifact}
                      />
                    ) : null}
                    <CodeDraftTurnCard
                      turn={turn}
                      turnIndex={turnIndex}
                      isLatest={isLatestTurn}
                      onRequestCodeDrafts={onRequestCodeDrafts}
                      onWriteCodeDrafts={onWriteCodeDrafts}
                      onWriteSingleCodeDraft={onWriteSingleCodeDraft}
                      onOpenNativeDiff={onOpenNativeDiff}
                      onOpenDraft={onOpenDraft}
                    />
                  </div>
                );
              case "CHECK_RESULT":
                return (
                  // 检查结果轮次组：检查卡片 + 建议草稿 + 风险线程
                  <div key={turn.turnId} className="assistant-turn-group">
                    <CheckTurnCard
                      turn={turn}
                      turnIndex={turnIndex}
                      isLatest={isLatestTurn}
                      onRevealReference={onRevealReference}
                      resolveArtifactText={resolveArtifactText}
                      onRequestArtifact={onRequestArtifact}
                    />
                    <CandidateChangeCard
                      title="建议草稿项"
                      changes={turn.check?.candidateChanges ?? []}
                      onConfirmCandidateChange={onConfirmCandidateChange}
                    />
                    <RiskThreadCard
                      threads={turn.check?.investigationThreads ?? []}
                      onInvestigateThread={onInvestigateThread}
                      onResolveThread={onResolveThread}
                    />
                  </div>
                );
            }
          })}
        </>
      )}
    </div>
  );
}

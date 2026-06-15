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

interface AssistantThreadProps extends AssistantArtifactAccess {
  turns: AssistantTurn[];
  requestRunning: boolean;
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
  const latestExplanationTurnId = [...turns].reverse().find((turn) => turn.kind === "EXPLANATION")?.turnId ?? null;
  return (
    <div className="assistant-thread" aria-label="AI 工作台对话线程">
      {turns.length === 0 ? (
        <div className="assistant-thread-empty">
          <strong className="assistant-result-text">还没有 AI 结果</strong>
          <p className="muted assistant-result-text">
          在底部输入问题，并选择发送动作；下一次发送上下文随图谱选择更新。
          </p>
        </div>
      ) : (
        <>
          <div className="assistant-thread-order-note" role="note">
            历史回答：旧结果在上，最新回答追加到底部。
          </div>
          {turns.map((turn, turnIndex) => {
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

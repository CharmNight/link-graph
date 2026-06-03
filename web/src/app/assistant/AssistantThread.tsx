import type {
  AssistantTurn,
  QaRequestRecoveryState,
  ResultEvidenceReference,
  RiskResolutionStatus,
} from "../types";
import { CheckTurnCard } from "./cards/CheckTurnCard";
import { ExplanationTurnCard } from "./cards/ExplanationTurnCard";
import { GenerationTurnCard } from "./cards/GenerationTurnCard";
import { QaTurnCard } from "./cards/QaTurnCard";

interface AssistantThreadProps {
  turns: AssistantTurn[];
  qaRequestRecoveryState?: QaRequestRecoveryState | null;
  generationDiscussionQuestionDraft?: string;
  onGenerationDiscussionQuestionDraftChange?: (value: string) => void;
  onSubmitGenerationDiscussion?: () => void;
  onRetryLastQaRequest: () => void;
  onEditFailedQaRequest: () => void;
  onRequestGenerationPlan: () => void;
  onRequestCodeDrafts: () => void;
  onWriteCodeDrafts: () => void;
  onWriteSingleCodeDraft?: (draftId: string) => void;
  onOpenNativeDiff?: (draftId: string) => void;
  onOpenDraft?: (targetPath: string) => void;
  onRevealReference: (reference: ResultEvidenceReference) => void;
  onFollowUpExplanationStep?: (stepId: string, question?: string) => void;
  onReturnToPreviousExplanation?: () => void;
  canReturnToPreviousExplanation?: boolean;
  onConfirmCandidateChange?: (changeId: string) => void;
  onInvestigateThread?: (threadId: string) => void;
  onResolveThread?: (threadId: string, status: RiskResolutionStatus) => void;
}

export function AssistantThread({
  turns,
  qaRequestRecoveryState,
  generationDiscussionQuestionDraft = "",
  onGenerationDiscussionQuestionDraftChange,
  onSubmitGenerationDiscussion,
  onRetryLastQaRequest,
  onEditFailedQaRequest,
  onRequestGenerationPlan,
  onRequestCodeDrafts,
  onWriteCodeDrafts,
  onWriteSingleCodeDraft,
  onOpenNativeDiff,
  onOpenDraft,
  onRevealReference,
  onFollowUpExplanationStep,
  onReturnToPreviousExplanation,
  canReturnToPreviousExplanation = false,
  onConfirmCandidateChange,
  onInvestigateThread,
  onResolveThread,
}: AssistantThreadProps) {
  return (
    <div className="assistant-thread" aria-label="AI 工作台对话线程">
      {turns.length === 0 ? (
        <div className="assistant-thread-empty">
          <strong>还没有 AI 结果</strong>
          <p className="muted">选择 intent 后在底部输入问题，当前图谱选择只会更新上下文。</p>
        </div>
      ) : turns.map((turn) => {
        switch (turn.kind) {
          case "EXPLANATION":
            return (
              <ExplanationTurnCard
                key={turn.turnId}
                turn={turn}
                onRevealReference={onRevealReference}
                onFollowUpStep={onFollowUpExplanationStep}
                onReturnToPrevious={onReturnToPreviousExplanation}
                canReturnToPrevious={canReturnToPreviousExplanation}
              />
            );
          case "QA":
            return (
              <QaTurnCard
                key={turn.turnId}
                turn={turn}
                recoveryState={qaRequestRecoveryState}
                onRetryLastQaRequest={onRetryLastQaRequest}
                onEditFailedQaRequest={onEditFailedQaRequest}
                onRevealReference={onRevealReference}
                onConfirmCandidateChange={onConfirmCandidateChange}
                onInvestigateThread={onInvestigateThread}
                onResolveThread={onResolveThread}
              />
            );
          case "GENERATION_PLAN":
          case "CODE_DRAFT":
            return (
              <GenerationTurnCard
                key={turn.turnId}
                turn={turn}
                discussionQuestionDraft={generationDiscussionQuestionDraft}
                onDiscussionQuestionDraftChange={onGenerationDiscussionQuestionDraftChange}
                onSubmitDiscussion={onSubmitGenerationDiscussion}
                onRequestGenerationPlan={onRequestGenerationPlan}
                onRequestCodeDrafts={onRequestCodeDrafts}
                onWriteCodeDrafts={onWriteCodeDrafts}
                onWriteSingleCodeDraft={onWriteSingleCodeDraft}
                onOpenNativeDiff={onOpenNativeDiff}
                onOpenDraft={onOpenDraft}
              />
            );
          case "CHECK_RESULT":
            return (
              <CheckTurnCard
                key={turn.turnId}
                turn={turn}
                onRevealReference={onRevealReference}
                onConfirmCandidateChange={onConfirmCandidateChange}
                onInvestigateThread={onInvestigateThread}
                onResolveThread={onResolveThread}
              />
            );
        }
      })}
    </div>
  );
}

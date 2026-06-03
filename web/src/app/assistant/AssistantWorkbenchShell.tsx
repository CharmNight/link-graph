import type { ReactNode } from "react";
import type {
  AssistantIntent,
  AssistantSessionState,
  AssistantTurn,
  QaRequestRecoveryState,
  ResultEvidenceReference,
  RiskResolutionStatus,
} from "../types";
import { AssistantComposer } from "./AssistantComposer";
import { AssistantContextBar } from "./AssistantContextBar";
import { AssistantIntentSelector } from "./AssistantIntentSelector";
import { AssistantThread } from "./AssistantThread";
import { useAssistantWorkbenchController } from "./useAssistantWorkbenchController";

interface AssistantWorkbenchShellProps {
  assistantSessionState: AssistantSessionState;
  turns: AssistantTurn[];
  activeIntent: AssistantIntent;
  requestRunning: boolean;
  qaRequestRecoveryState?: QaRequestRecoveryState | null;
  composerDraft?: string;
  onComposerDraftChange?: (value: string) => void;
  generationDiscussionQuestionDraft?: string;
  onGenerationDiscussionQuestionDraftChange?: (value: string) => void;
  onSubmitGenerationDiscussion?: () => void;
  onIntentChange: (intent: AssistantIntent) => void;
  onSubmit: (intent: AssistantIntent, prompt: string) => void;
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
  legacyWorkbenchContent?: ReactNode;
}

export function AssistantWorkbenchShell({
  assistantSessionState,
  turns,
  activeIntent,
  requestRunning,
  qaRequestRecoveryState,
  composerDraft,
  onComposerDraftChange,
  generationDiscussionQuestionDraft = "",
  onGenerationDiscussionQuestionDraftChange,
  onSubmitGenerationDiscussion,
  onIntentChange,
  onSubmit,
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
  legacyWorkbenchContent = null,
}: AssistantWorkbenchShellProps) {
  const composer = useAssistantWorkbenchController({
    activeIntent,
    requestRunning,
    draft: composerDraft,
    onDraftChange: onComposerDraftChange,
    onSubmit,
  });

  return (
    <aside className="assistant-workbench-shell" role="complementary" aria-label="AI 代码工作台">
      <AssistantContextBar context={assistantSessionState.context} />
      <AssistantIntentSelector activeIntent={activeIntent} onIntentChange={onIntentChange} />
      <AssistantThread
        turns={turns}
        qaRequestRecoveryState={qaRequestRecoveryState}
        generationDiscussionQuestionDraft={generationDiscussionQuestionDraft}
        onGenerationDiscussionQuestionDraftChange={onGenerationDiscussionQuestionDraftChange}
        onSubmitGenerationDiscussion={onSubmitGenerationDiscussion}
        onRetryLastQaRequest={onRetryLastQaRequest}
        onEditFailedQaRequest={onEditFailedQaRequest}
        onRequestGenerationPlan={onRequestGenerationPlan}
        onRequestCodeDrafts={onRequestCodeDrafts}
        onWriteCodeDrafts={onWriteCodeDrafts}
        onWriteSingleCodeDraft={onWriteSingleCodeDraft}
        onOpenNativeDiff={onOpenNativeDiff}
        onOpenDraft={onOpenDraft}
        onRevealReference={onRevealReference}
        onFollowUpExplanationStep={onFollowUpExplanationStep}
        onReturnToPreviousExplanation={onReturnToPreviousExplanation}
        canReturnToPreviousExplanation={canReturnToPreviousExplanation}
        onConfirmCandidateChange={onConfirmCandidateChange}
        onInvestigateThread={onInvestigateThread}
        onResolveThread={onResolveThread}
      />
      {legacyWorkbenchContent ? (
        <div className="assistant-legacy-workbench">
          {legacyWorkbenchContent}
        </div>
      ) : null}
      <AssistantComposer
        activeIntent={activeIntent}
        draft={composer.draft}
        canSubmit={composer.canSubmit}
        requestRunning={requestRunning}
        onDraftChange={composer.setDraft}
        onSubmit={composer.submit}
      />
    </aside>
  );
}

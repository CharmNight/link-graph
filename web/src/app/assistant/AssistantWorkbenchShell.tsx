import type {
  AssistantActionId,
  AssistantIntent,
  AssistantSessionState,
  AssistantTurn,
  QaRequestRecoveryState,
  ResultEvidenceReference,
  RiskResolutionStatus,
  StepGranularity,
} from "../types";
import { AssistantComposer } from "./AssistantComposer";
import { AssistantContextBar } from "./AssistantContextBar";
import { AssistantThread } from "./AssistantThread";
import type { AssistantArtifactAccess } from "./assistantArtifacts";
import {
  isClassDiagramAssistantContext,
  resolveAssistantComposerActionId,
  resolveAssistantComposerIntent,
} from "./assistantModels";
import { analysisDisplayModeFromAssistantContext, assistantActionIdForIntent } from "./assistantActionRegistry";
import { useAssistantWorkbenchController } from "./useAssistantWorkbenchController";

interface AssistantWorkbenchShellProps extends AssistantArtifactAccess {
  assistantSessionState: AssistantSessionState;
  turns: AssistantTurn[];
  activeIntent: AssistantIntent;
  activeActionId?: AssistantActionId | null;
  requestRunning: boolean;
  qaRequestRecoveryState?: QaRequestRecoveryState | null;
  composerDraft?: string;
  onComposerDraftChange?: (value: string) => void;
  onActionChange: (actionId: AssistantActionId) => void;
  onSubmit: (intent: AssistantIntent, prompt: string) => void;
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

export function AssistantWorkbenchShell({
  assistantSessionState,
  turns,
  activeIntent,
  activeActionId,
  requestRunning,
  qaRequestRecoveryState,
  composerDraft,
  onComposerDraftChange,
  onActionChange,
  onSubmit,
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
}: AssistantWorkbenchShellProps) {
  const composerIntent = resolveAssistantComposerIntent(activeIntent, assistantSessionState.context);
  const composerActionId = resolveAssistantComposerActionId(
    activeActionId
      ?? assistantSessionState.activeActionId
      ?? assistantActionIdForIntent(composerIntent, analysisDisplayModeFromAssistantContext(assistantSessionState.context)),
    assistantSessionState.context,
  );
  const workbenchLabel = isClassDiagramAssistantContext(assistantSessionState.context)
    ? "AI 类图工作台"
    : "AI 代码工作台";
  const composer = useAssistantWorkbenchController({
    activeIntent: composerIntent,
    requestRunning,
    draft: composerDraft,
    onDraftChange: onComposerDraftChange,
    onSubmit,
  });

  return (
    <aside className="assistant-workbench-shell" role="complementary" aria-label={workbenchLabel}>
      <AssistantContextBar context={assistantSessionState.context} />
      <AssistantThread
        turns={turns}
        requestRunning={requestRunning}
        qaRequestRecoveryState={qaRequestRecoveryState}
        onRetryLastQaRequest={onRetryLastQaRequest}
        onEditFailedQaRequest={onEditFailedQaRequest}
        onPrimeGenerationPlan={onPrimeGenerationPlan}
        onRequestCodeDrafts={onRequestCodeDrafts}
        onWriteCodeDrafts={onWriteCodeDrafts}
        onWriteSingleCodeDraft={onWriteSingleCodeDraft}
        onOpenNativeDiff={onOpenNativeDiff}
        onOpenDraft={onOpenDraft}
        onRevealReference={onRevealReference}
        selectedExplanationStepId={selectedExplanationStepId}
        selectedExplanationGranularity={selectedExplanationGranularity}
        explanationHistoryTrail={explanationHistoryTrail}
        previousExplanationSessionLabel={previousExplanationSessionLabel}
        onSelectExplanationStep={onSelectExplanationStep}
        onLocateExplanationStepNode={onLocateExplanationStepNode}
        onInspectExplanationStepNode={onInspectExplanationStepNode}
        onHoverExplanationStep={onHoverExplanationStep}
        onLeaveExplanationStep={onLeaveExplanationStep}
        onChangeExplanationGranularity={onChangeExplanationGranularity}
        onFollowUpExplanationStep={onFollowUpExplanationStep}
        onReturnToPreviousExplanation={onReturnToPreviousExplanation}
        onOpenExplanationHistory={onOpenExplanationHistory}
        canReturnToPreviousExplanation={canReturnToPreviousExplanation}
        onDiscussGenerationPlan={onDiscussGenerationPlan}
        onConfirmCandidateChange={onConfirmCandidateChange}
        onInvestigateThread={onInvestigateThread}
        onResolveThread={onResolveThread}
        resolveArtifactText={resolveArtifactText}
        onRequestArtifact={onRequestArtifact}
      />
      <AssistantComposer
        activeIntent={composerIntent}
        activeActionId={composerActionId}
        context={assistantSessionState.context}
        draft={composer.draft}
        canSubmit={composer.canSubmit}
        requestRunning={requestRunning}
        onActionChange={onActionChange}
        onDraftChange={composer.setDraft}
        onSubmit={composer.submit}
      />
    </aside>
  );
}

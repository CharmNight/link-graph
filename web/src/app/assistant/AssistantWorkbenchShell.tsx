import type { AssistantActionId, AssistantIntent } from "./assistantTypes";
import type {
  AssistantSessionState,
  AssistantTurn,
  QaMode,
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

/**
 * 助手工作台外壳的属性集合。
 *
 * 外壳本身只负责布局与回调透传：把会话状态、轮次、当前意图、各种操作回调
 * 拼装成上下文条、对话流、输入器三段式结构，具体的业务逻辑由父组件通过
 * 这些回调上抛处理。
 */
interface AssistantWorkbenchShellProps extends AssistantArtifactAccess {
  /** 当前会话的完整状态，包含上下文、激活动作、QA 模式等元信息。 */
  assistantSessionState: AssistantSessionState;
  /** 当前会话中已存在的全部轮次，按时间顺序排列。 */
  turns: AssistantTurn[];
  /** 当前激活的助手意图（如分析、改写、答疑等）。 */
  activeIntent: AssistantIntent;
  /** 当前激活的动作 id，未指定时由上下文与意图推导。 */
  activeActionId?: AssistantActionId | null;
  /** 是否有请求正在执行，用于禁用提交按钮与切换。 */
  requestRunning: boolean;
  /** 最近一次 QA 请求的恢复态，用于在失败后提供重试/编辑入口。 */
  qaRequestRecoveryState?: QaRequestRecoveryState | null;
  /** 输入器中的草稿文本。 */
  composerDraft?: string;
  /** 草稿变化时触发。 */
  onComposerDraftChange?: (value: string) => void;
  /** 用户切换动作时触发。 */
  onActionChange: (actionId: AssistantActionId) => void;
  /** 用户提交输入时触发，携带意图与提示词。 */
  onSubmit: (intent: AssistantIntent, prompt: string) => void;
  /** 重试上一次失败的 QA 请求。 */
  onRetryLastQaRequest: () => void;
  /** 编辑上一次失败的 QA 请求（通常是把内容回填到输入器）。 */
  onEditFailedQaRequest: () => void;
  /** 触发生成方案的预填（prime）。 */
  onPrimeGenerationPlan: () => void;
  /** 请求生成代码草稿。 */
  onRequestCodeDrafts: () => void;
  /** 把代码草稿写入磁盘。 */
  onWriteCodeDrafts: () => void;
  /** 仅写入单个指定草稿。 */
  onWriteSingleCodeDraft?: (draftId: string) => void;
  /** 打开原生 diff 视图查看草稿。 */
  onOpenNativeDiff?: (draftId: string) => void;
  /** 打开某个文件路径对应的草稿。 */
  onOpenDraft?: (targetPath: string) => void;
  /** 用户点击证据引用时触发，跳转到对应代码位置。 */
  onRevealReference: (reference: ResultEvidenceReference) => void;
  /** 当前选中的讲解步骤 id。 */
  selectedExplanationStepId?: string | null;
  /** 当前选中的讲解粒度。 */
  selectedExplanationGranularity?: StepGranularity;
  /** 当前选中的 QA 模式。 */
  selectedQaMode?: QaMode | null;
  /** 讲解历史路径标签列表。 */
  explanationHistoryTrail?: string[];
  /** 上一讲解的展示文案。 */
  previousExplanationSessionLabel?: string | null;
  /** 选中讲解步骤时触发。 */
  onSelectExplanationStep?: (stepId: string) => void;
  /** 定位讲解步骤对应的图谱节点。 */
  onLocateExplanationStepNode?: (stepId: string) => void;
  /** 编辑讲解步骤对应的节点。 */
  onInspectExplanationStepNode?: (stepId: string) => void;
  /** 悬停讲解步骤时触发。 */
  onHoverExplanationStep?: (stepId: string) => void;
  /** 离开讲解步骤时触发。 */
  onLeaveExplanationStep?: () => void;
  /** 切换讲解粒度时触发。 */
  onChangeExplanationGranularity?: (granularity: StepGranularity) => void;
  /** 切换 QA 模式时触发。 */
  onQaModeChange?: (mode: QaMode) => void;
  /** 对讲解步骤发起追问。 */
  onFollowUpExplanationStep?: (stepId: string, question?: string) => void;
  /** 返回上一讲解。 */
  onReturnToPreviousExplanation?: () => void;
  /** 跳转到讲解历史中的某一项。 */
  onOpenExplanationHistory?: (historyIndex: number) => void;
  /** 是否允许返回上一讲解。 */
  canReturnToPreviousExplanation?: boolean;
  /** 对当前生成方案发起讨论（提问）。 */
  onDiscussGenerationPlan?: (question?: string) => void;
  /** 确认某个候选变更。 */
  onConfirmCandidateChange?: (changeId: string) => void;
  /** 进入某个风险讨论线程。 */
  onInvestigateThread?: (threadId: string) => void;
  /** 标记某个风险线程的解决状态。 */
  onResolveThread?: (threadId: string, status: RiskResolutionStatus) => void;
}

/**
 * 助手工作台外壳组件。
 *
 * 组合三块子组件：
 * - 上下文条：展示当前会话所处的上下文（类图/代码）；
 * - 对话流：展示历史轮次、恢复入口、各种操作按钮；
 * - 输入器：让用户输入提示词并选择意图/动作/粒度/QA 模式。
 *
 * 顶层 aside 同时根据上下文类型决定无障碍标签（类图工作台或代码工作台）。
 */
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
  selectedQaMode,
  explanationHistoryTrail,
  previousExplanationSessionLabel,
  onSelectExplanationStep,
  onLocateExplanationStepNode,
  onInspectExplanationStepNode,
  onHoverExplanationStep,
  onLeaveExplanationStep,
  onChangeExplanationGranularity,
  onQaModeChange,
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
  // 综合外部传入的意图与上下文，解析出输入器实际应展示的意图
  const composerIntent = resolveAssistantComposerIntent(activeIntent, assistantSessionState.context);
  // 解析输入器实际应展示的动作 id：优先外部传入，其次会话态，最后按意图+分析模式推导
  const composerActionId = resolveAssistantComposerActionId(
    activeActionId
      ?? assistantSessionState.activeActionId
      ?? assistantActionIdForIntent(composerIntent, analysisDisplayModeFromAssistantContext(assistantSessionState.context)),
    assistantSessionState.context,
  );
  // 根据上下文是类图还是代码，决定工作台的无障碍标签
  const workbenchLabel = isClassDiagramAssistantContext(assistantSessionState.context)
    ? "AI 类图工作台"
    : "AI 代码工作台";
  // 输入器控制器：统一管理草稿、提交、可提交性等本地状态
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
        selectedQaMode={selectedQaMode ?? assistantSessionState.composer?.qaMode ?? "AUTO"}
        selectedExplanationGranularity={selectedExplanationGranularity}
        canSubmit={composer.canSubmit}
        requestRunning={requestRunning}
        onActionChange={onActionChange}
        onQaModeChange={onQaModeChange}
        onExplanationGranularityChange={onChangeExplanationGranularity}
        onDraftChange={composer.setDraft}
        onSubmit={composer.submit}
      />
    </aside>
  );
}

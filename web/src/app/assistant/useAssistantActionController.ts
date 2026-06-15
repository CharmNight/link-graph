import { useEffect, type Dispatch, type SetStateAction } from "react";
import { requestAssistantTask, type BridgeInvocationResult } from "../api";
import type { SubmitAsyncBridgeCommandOptions } from "../controllers/bridgeCommandTypes";
import type { WorkflowStage } from "../workflow/workflowStage";
import type {
  AnalysisDisplayMode,
  AssistantActionId,
  AssistantComposerTarget,
  AssistantIntent,
  AssistantSessionState,
  DiffItem,
  LinkGraphDocument,
  LinkGraphSceneId,
  StepGranularity,
} from "../types";
import {
  assistantActionDefinition,
  assistantActionIdForIntent,
  resolveAssistantActionIdForDisplayMode,
} from "./assistantActionRegistry";
import {
  buildAssistantContextSnapshot,
  resolveAssistantNodeIds,
  resolveAssistantScopeLabel,
  sceneIdForAnalysisDisplayMode,
} from "./assistantContextResolver";
import { applyAssistantSessionPolicy } from "./assistantSessionPolicy";
import {
  assistantWorkbenchDisplayLabel,
  buildDefaultAssistantPrompt as buildDefaultAssistantPromptText,
  NEW_ASSISTANT_COMPOSER_TARGET,
} from "./assistantPromptDefaults";

interface AssistantBridgeCommands {
  submitAsyncBridgeCommand: (
    scene: string,
    invoke: () => BridgeInvocationResult,
    options?: SubmitAsyncBridgeCommandOptions,
  ) => BridgeInvocationResult;
}

export type AssistantDisplayModeDocuments = Record<
  AnalysisDisplayMode,
  {
    visibleGraph: LinkGraphDocument;
    anchorNodeId?: string | null;
  }
>;

interface ExplanationAcceptedEvent {
  actionId: AssistantActionId;
  intent: AssistantIntent;
  target: AssistantComposerTarget;
}

interface UseAssistantActionControllerArgs {
  analysisDisplayMode: AnalysisDisplayMode;
  currentSceneId: LinkGraphSceneId;
  selectedMethodSignature?: string | null;
  selectedNodeId: string | null;
  anchorNodeId: string | null;
  selectionGroupNodeIds: string[];
  diffTargetItemIds: string[];
  diffItems: DiffItem[];
  viewDocuments: AssistantDisplayModeDocuments;
  assistantSessionState: AssistantSessionState;
  setAssistantSessionState: Dispatch<SetStateAction<AssistantSessionState>>;
  selectedExplanationGranularity: StepGranularity;
  bridgeCommands: AssistantBridgeCommands;
  setActiveWorkflowStage: (stage: WorkflowStage) => void;
  onExplanationAccepted: (event: ExplanationAcceptedEvent) => void;
}

export function useAssistantActionController({
  analysisDisplayMode,
  currentSceneId,
  selectedMethodSignature = null,
  selectedNodeId,
  anchorNodeId,
  selectionGroupNodeIds,
  diffTargetItemIds,
  diffItems,
  viewDocuments,
  assistantSessionState,
  setAssistantSessionState,
  selectedExplanationGranularity,
  bridgeCommands,
  setActiveWorkflowStage,
  onExplanationAccepted,
}: UseAssistantActionControllerArgs) {
  const assistantComposerDraft = assistantSessionState.composer?.draft ?? "";
  const assistantComposerTarget = assistantSessionState.composer?.target ?? NEW_ASSISTANT_COMPOSER_TARGET;

  function assistantGraphForDisplayMode(mode: AnalysisDisplayMode): LinkGraphDocument {
    return viewDocuments[mode].visibleGraph;
  }

  function assistantAnchorNodeIdForDisplayMode(mode: AnalysisDisplayMode): string | null {
    return viewDocuments[mode].anchorNodeId ?? null;
  }

  function assistantSceneIdForDisplayMode(mode: AnalysisDisplayMode): LinkGraphSceneId {
    return mode === analysisDisplayMode ? currentSceneId : sceneIdForAnalysisDisplayMode(mode);
  }

  function updateAssistantAction(intent: AssistantIntent, actionId?: AssistantActionId | null) {
    const resolvedActionId = resolveAssistantActionIdForDisplayMode(
      actionId ?? assistantActionIdForIntent(intent, analysisDisplayMode),
      analysisDisplayMode,
    );
    const action = assistantActionDefinition(resolvedActionId, analysisDisplayMode);
    setAssistantSessionState((current) => ({
      ...current,
      activeIntent: action.intent,
      activeActionId: resolvedActionId,
    }));
  }

  function setAssistantComposerDraft(value: string) {
    setAssistantSessionState((current) => ({
      ...current,
      composer: {
        ...current.composer,
        draft: value,
        target: current.composer?.target ?? NEW_ASSISTANT_COMPOSER_TARGET,
        draftSource: "USER",
        actionId: current.activeActionId
          ?? current.composer?.actionId
          ?? assistantActionIdForIntent(current.activeIntent, analysisDisplayMode),
        sceneId: current.composer?.sceneId ?? currentSceneId,
      },
    }));
  }

  function setAssistantComposer(
    draft: string,
    target: AssistantComposerTarget = NEW_ASSISTANT_COMPOSER_TARGET,
    options: {
      draftSource?: "AUTO" | "USER" | null;
      actionId?: AssistantActionId | null;
      sceneId?: LinkGraphSceneId | null;
    } = {},
  ) {
    setAssistantSessionState((current) => ({
      ...current,
      composer: {
        draft,
        target,
        draftSource: options.draftSource ?? null,
        actionId: options.actionId
          ?? current.activeActionId
          ?? assistantActionIdForIntent(current.activeIntent, analysisDisplayMode),
        sceneId: options.sceneId ?? currentSceneId,
      },
    }));
  }

  function primeAssistantComposer(
    intent: AssistantIntent,
    draft: string,
    options: {
      target?: AssistantComposerTarget;
      stage?: WorkflowStage;
      actionId?: AssistantActionId;
      draftSource?: "AUTO" | "USER" | null;
    } = {},
  ) {
    const actionId = options.actionId ?? assistantActionIdForIntent(intent, analysisDisplayMode);
    updateAssistantAction(intent, actionId);
    setAssistantComposer(draft, options.target ?? NEW_ASSISTANT_COMPOSER_TARGET, {
      actionId,
      draftSource: options.draftSource ?? "AUTO",
    });
    if (options.stage) {
      setActiveWorkflowStage(options.stage);
    }
  }

  function selectedAssistantNodeIds(mode: AnalysisDisplayMode = analysisDisplayMode): string[] {
    return resolveAssistantNodeIds({
      graph: assistantGraphForDisplayMode(mode),
      selectionGroupNodeIds,
      selectedNodeId,
      anchorNodeId: assistantAnchorNodeIdForDisplayMode(mode) ?? anchorNodeId,
    });
  }

  function selectedAssistantDiffItemIds(): string[] {
    if (diffTargetItemIds.length > 0) {
      return diffTargetItemIds;
    }
    return selectedNodeId && diffItems.some((item) => item.id === selectedNodeId) ? [selectedNodeId] : [];
  }

  function selectedNodeIdsForComposerTarget(target: AssistantComposerTarget): string[] {
    switch (target.kind) {
      case "QaRecovery":
        return target.selectedNodeIds?.length ? target.selectedNodeIds : selectedAssistantNodeIds();
      case "RiskInvestigation":
        return target.targetNodeIds?.length ? target.targetNodeIds : selectedAssistantNodeIds();
      case "NewTask":
      case "ExplanationFollowUp":
      case "GenerationDiscussion":
        return selectedAssistantNodeIds();
    }
  }

  function assistantTargetTitle(
    targetNodeIds: string[],
    mode: AnalysisDisplayMode = analysisDisplayMode,
  ): string | null {
    if (targetNodeIds.length !== 1) {
      return null;
    }
    return assistantGraphForDisplayMode(mode).nodes.find((node) => node.id === targetNodeIds[0])?.title
      ?? targetNodeIds[0];
  }

  function buildDefaultAssistantPrompt(
    actionId: AssistantActionId,
    mode: AnalysisDisplayMode = analysisDisplayMode,
    targetNodeIds: string[] = selectedAssistantNodeIds(mode),
  ): string {
    return buildDefaultAssistantPromptText({
      actionId,
      analysisDisplayMode: mode,
      targetNodeIds,
      targetTitle: assistantTargetTitle(targetNodeIds, mode),
    });
  }

  function handleAssistantActionChange(actionId: AssistantActionId) {
    const resolvedActionId = resolveAssistantActionIdForDisplayMode(actionId, analysisDisplayMode);
    const action = assistantActionDefinition(resolvedActionId, analysisDisplayMode);
    updateAssistantAction(action.intent, resolvedActionId);
    const defaultPrompt = buildDefaultAssistantPrompt(resolvedActionId);
    if (defaultPrompt) {
      setAssistantComposer(defaultPrompt, NEW_ASSISTANT_COMPOSER_TARGET, {
        actionId: resolvedActionId,
        draftSource: "AUTO",
      });
    }
  }

  function handleAssistantSubmit(
    intent: AssistantIntent,
    prompt: string,
    options: {
      explanationGranularity?: StepGranularity;
      selectedNodeIds?: string[];
      target?: AssistantComposerTarget;
      actionId?: AssistantActionId;
    } = {},
  ) {
    const actionId = resolveAssistantActionIdForDisplayMode(
      options.actionId ?? assistantActionIdForIntent(intent, analysisDisplayMode),
      analysisDisplayMode,
    );
    const action = assistantActionDefinition(actionId, analysisDisplayMode);
    const resolvedIntent = action.intent;
    const normalizedPrompt = prompt.trim();
    if (!normalizedPrompt && resolvedIntent !== "CHECK_CHANGE") {
      return;
    }
    const target = options.target ?? assistantComposerTarget;
    updateAssistantAction(resolvedIntent, actionId);
    switch (resolvedIntent) {
      case "DESCRIBE_CLASS":
      case "EXPLAIN_CODE":
        setActiveWorkflowStage("understand");
        break;
      case "ASK_CODE":
        setActiveWorkflowStage("qa");
        break;
      case "GENERATE_CODE":
        setActiveWorkflowStage("code");
        break;
      case "CHECK_CHANGE":
        setActiveWorkflowStage("qa");
        break;
    }
    const requestSelectedNodeIds = options.selectedNodeIds ?? selectedNodeIdsForComposerTarget(target);
    const assistantWorkbenchLabel = assistantWorkbenchDisplayLabel(analysisDisplayMode);
    bridgeCommands.submitAsyncBridgeCommand(
      assistantWorkbenchLabel,
      () => requestAssistantTask({
        actionId,
        sceneId: assistantSceneIdForDisplayMode(analysisDisplayMode),
        intent: resolvedIntent,
        prompt: normalizedPrompt,
        selectedNodeIds: requestSelectedNodeIds,
        selectedDiffItemIds: selectedAssistantDiffItemIds(),
        target,
        explanationGranularity: options.explanationGranularity ?? selectedExplanationGranularity,
      }),
      {
        onAccepted: () => {
          if (resolvedIntent === "EXPLAIN_CODE" || resolvedIntent === "DESCRIBE_CLASS") {
            onExplanationAccepted({
              actionId,
              intent: resolvedIntent,
              target,
            });
          }
          setAssistantComposer("", NEW_ASSISTANT_COMPOSER_TARGET, {
            actionId,
            draftSource: null,
          });
        },
        successFeedback: {
          level: "INFO",
          message: `已提交 ${assistantWorkbenchLabel}请求。`,
        },
      },
    );
  }

  useEffect(() => {
    setAssistantSessionState((current) => {
      const actionId = resolveAssistantActionIdForDisplayMode(
        current.activeActionId
          ?? current.composer?.actionId
          ?? assistantActionIdForIntent(current.activeIntent, analysisDisplayMode),
        analysisDisplayMode,
      );
      const selectedNodeIds = selectedAssistantNodeIds(analysisDisplayMode);
      const graph = assistantGraphForDisplayMode(analysisDisplayMode);
      const context = buildAssistantContextSnapshot({
        selectedNodeIds,
        selectedDiffItemIds: selectedAssistantDiffItemIds(),
        analysisDisplayMode,
        currentSceneId,
        selectedMethodSignature: selectedMethodSignature ?? null,
        scopeLabel: resolveAssistantScopeLabel(selectedNodeIds, graph.nodes, analysisDisplayMode),
      });
      return applyAssistantSessionPolicy({
        session: current,
        context,
        defaultDraft: buildDefaultAssistantPrompt(actionId, analysisDisplayMode, selectedNodeIds),
        analysisDisplayMode,
        sceneId: currentSceneId,
      });
    });
  }, [
    analysisDisplayMode,
    currentSceneId,
    selectedMethodSignature,
    selectedNodeId,
    anchorNodeId,
    selectionGroupNodeIds,
    diffTargetItemIds,
    diffItems,
    viewDocuments,
    setAssistantSessionState,
  ]);

  return {
    assistantComposerDraft,
    assistantComposerTarget,
    buildDefaultAssistantPrompt,
    handleAssistantActionChange,
    handleAssistantSubmit,
    primeAssistantComposer,
    selectedAssistantNodeIds,
    assistantTargetTitle,
    setAssistantComposer,
    setAssistantComposerDraft,
  };
}

import type {
  AnalysisDisplayMode,
  AssistantContextSnapshot,
  AssistantSessionState,
  LinkGraphSceneId,
} from "../types";
import {
  analysisDisplayModeFromAssistantContext,
  assistantActionDefinition,
  assistantActionIdForIntent,
  resolveAssistantActionIdForDisplayMode,
} from "./assistantActionRegistry";

function sameStringArray(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((item, index) => item === right[index]);
}

function sameContext(left: AssistantContextSnapshot, right: AssistantContextSnapshot): boolean {
  return sameStringArray(left.selectedNodeIds, right.selectedNodeIds)
    && sameStringArray(left.selectedDiffItemIds, right.selectedDiffItemIds)
    && (left.analysisDisplayMode ?? null) === (right.analysisDisplayMode ?? null)
    && (left.currentSceneId ?? null) === (right.currentSceneId ?? null)
    && (left.selectedMethodSignature ?? null) === (right.selectedMethodSignature ?? null)
    && left.scopeLabel === right.scopeLabel;
}

function isLinkGraphSceneId(value: string | null | undefined): value is LinkGraphSceneId {
  switch (value) {
    case "WORKSPACE_FACT":
    case "WORKSPACE_FLOWCHART":
    case "WORKSPACE_RESOURCE_RELATION":
    case "WORKSPACE_ARCHITECTURE_GRAPH":
    case "WORKSPACE_CLASS_DIAGRAM":
    case "WORKSPACE_REVIEW_GRAPH":
    case "DIFF":
      return true;
    default:
      return false;
  }
}

export function applyAssistantSessionPolicy(args: {
  session: AssistantSessionState;
  context: AssistantContextSnapshot;
  defaultDraft: string;
  analysisDisplayMode?: AnalysisDisplayMode;
  sceneId?: LinkGraphSceneId | null;
}): AssistantSessionState {
  const analysisDisplayMode = args.analysisDisplayMode
    ?? analysisDisplayModeFromAssistantContext(args.context);
  const sceneId: LinkGraphSceneId | null = args.sceneId
    ?? (isLinkGraphSceneId(args.context.currentSceneId) ? args.context.currentSceneId : null);
  const previousMode = analysisDisplayModeFromAssistantContext(args.session.context, analysisDisplayMode);
  const requestedActionId = args.session.activeActionId
    ?? args.session.composer?.actionId
    ?? assistantActionIdForIntent(args.session.activeIntent, previousMode);
  const actionId = resolveAssistantActionIdForDisplayMode(requestedActionId, analysisDisplayMode);
  const action = assistantActionDefinition(actionId, analysisDisplayMode);
  const composer = args.session.composer ?? {
    draft: "",
    target: { kind: "NewTask" as const },
  };
  const staleActionDraft = Boolean(composer.actionId && composer.actionId !== actionId);
  const staleSceneDraft = Boolean(composer.sceneId && sceneId && composer.sceneId !== sceneId);
  const shouldRewriteDraft = Boolean(composer.draft.trim()) && (staleActionDraft || staleSceneDraft);
  const nextComposer = {
    ...composer,
    draft: shouldRewriteDraft ? args.defaultDraft : composer.draft,
    draftSource: shouldRewriteDraft ? "AUTO" as const : composer.draftSource ?? null,
    actionId: shouldRewriteDraft || composer.actionId ? actionId : composer.actionId ?? null,
    sceneId: shouldRewriteDraft || composer.sceneId ? sceneId : composer.sceneId ?? null,
  };
  const activeChanged = args.session.activeIntent !== action.intent
    || args.session.activeActionId !== actionId;
  const contextChanged = !sameContext(args.session.context, args.context);
  const composerChanged = nextComposer.draft !== composer.draft
    || nextComposer.draftSource !== composer.draftSource
    || nextComposer.actionId !== composer.actionId
    || nextComposer.sceneId !== composer.sceneId;

  if (!activeChanged && !contextChanged && !composerChanged) {
    return args.session;
  }

  return {
    ...args.session,
    activeIntent: action.intent,
    activeActionId: actionId,
    context: args.context,
    composer: nextComposer,
  };
}

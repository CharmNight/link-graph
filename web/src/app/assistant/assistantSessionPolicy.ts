// 助理会话策略：根据当前上下文（场景、选中节点等）调整助理会话状态。
// 主要场景：用户切换场景或选中节点后，旧的输入草稿可能不再适用，
// 本策略负责判断何时清空草稿、何时调整动作 ID。
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

/** 顺序敏感地比较两个字符串数组。 */
function sameStringArray(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((item, index) => item === right[index]);
}

/** 判断两个上下文快照是否完全一致（用于决定是否需要更新会话）。 */
function sameContext(left: AssistantContextSnapshot, right: AssistantContextSnapshot): boolean {
  return sameStringArray(left.selectedNodeIds, right.selectedNodeIds)
    && sameStringArray(left.selectedDiffItemIds, right.selectedDiffItemIds)
    && (left.analysisDisplayMode ?? null) === (right.analysisDisplayMode ?? null)
    && (left.currentSceneId ?? null) === (right.currentSceneId ?? null)
    && (left.selectedMethodSignature ?? null) === (right.selectedMethodSignature ?? null)
    && left.scopeLabel === right.scopeLabel;
}

/** 类型守卫：判断字符串是否为合法的场景 ID。 */
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

/**
 * 把会话策略应用到给定会话上。
 *
 * 决策流程：
 * 1) 解析当前展示模式与场景 ID；
 * 2) 决定应该使用的动作 ID（按当前模式校正）；
 * 3) 判断当前输入草稿是否已"过期"（动作或场景变了），过期则用默认草稿覆盖；
 * 4) 计算 active / context / composer 三层是否任一发生变化；
 * 5) 都没变化时原样返回；任一变化时返回更新后的会话。
 *
 * @return 新会话状态；无变化时返回原会话
 */
export function applyAssistantSessionPolicy(args: {
  session: AssistantSessionState;
  context: AssistantContextSnapshot;
  defaultDraft: string;
  analysisDisplayMode?: AnalysisDisplayMode;
  sceneId?: LinkGraphSceneId | null;
}): AssistantSessionState {
  // 解析展示模式：显式参数 > 上下文 > 会话上下文
  const analysisDisplayMode = args.analysisDisplayMode
    ?? analysisDisplayModeFromAssistantContext(args.context);
  // 解析场景 ID：显式参数 > 上下文 currentSceneId（若是合法场景）
  const sceneId: LinkGraphSceneId | null = args.sceneId
    ?? (isLinkGraphSceneId(args.context.currentSceneId) ? args.context.currentSceneId : null);
  const previousMode = analysisDisplayModeFromAssistantContext(args.session.context, analysisDisplayMode);
  // 解析应使用的动作 ID：会话显式 > 输入框显式 > 按意图推断
  const requestedActionId = args.session.activeActionId
    ?? args.session.composer?.actionId
    ?? assistantActionIdForIntent(args.session.activeIntent, previousMode);
  // 按当前模式校正动作 ID（不同模式支持的动作集合不同）
  const actionId = resolveAssistantActionIdForDisplayMode(requestedActionId, analysisDisplayMode);
  const action = assistantActionDefinition(actionId, analysisDisplayMode);
  // 输入框默认值：缺失时给一个空草稿
  const composer = args.session.composer ?? {
    draft: "",
    target: { kind: "NewTask" as const },
  };
  // 判断当前草稿是否需要重写：动作变了 或 场景变了 + 草稿非空
  const staleActionDraft = Boolean(composer.actionId && composer.actionId !== actionId);
  const staleSceneDraft = Boolean(composer.sceneId && sceneId && composer.sceneId !== sceneId);
  const shouldRewriteDraft = Boolean(composer.draft.trim()) && (staleActionDraft || staleSceneDraft);
  const nextComposer = {
    ...composer,
    // 需要重写时用默认草稿覆盖
    draft: shouldRewriteDraft ? args.defaultDraft : composer.draft,
    draftSource: shouldRewriteDraft ? "AUTO" as const : composer.draftSource ?? null,
    // 动作/场景：仅在用户已选或需要重写时才更新
    actionId: shouldRewriteDraft || composer.actionId ? actionId : composer.actionId ?? null,
    sceneId: shouldRewriteDraft || composer.sceneId ? sceneId : composer.sceneId ?? null,
  };
  // 三层变化判断
  const activeChanged = args.session.activeIntent !== action.intent
    || args.session.activeActionId !== actionId;
  const contextChanged = !sameContext(args.session.context, args.context);
  const composerChanged = nextComposer.draft !== composer.draft
    || nextComposer.draftSource !== composer.draftSource
    || nextComposer.actionId !== composer.actionId
    || nextComposer.sceneId !== composer.sceneId;

  // 完全没变化：原样返回（避免无意义的引用变化触发渲染）
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

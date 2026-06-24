import type {
  AsyncRequestState,
  DraftImplementationSuggestionState,
  GeneratedCodeDraft,
  GenerationPlan,
} from "./types";

/**
 * 代码差异（Code Diff）的整体状态：
 * - MISSING：尚无任何代码草稿；
 * - RUNNING：正在请求生成代码草稿；
 * - FRESH：草稿新鲜，与最新图文档版本匹配；
 * - STALE：草稿过期，对应图文档已发生更新；
 * - FAILED：上次生成失败或超时。
 */
export type CodeDiffStatus = "MISSING" | "RUNNING" | "FRESH" | "STALE" | "FAILED";

/**
 * 从多个相关输入推导"实现建议"面板应处的状态。
 *
 * 决策依据：
 * 1) 若生成计划已带 summary：再判断草稿版本是否已超过计划版本，决定 FRESH/STALE；
 * 2) 若生成计划为空但请求失败：标记 FAILED；
 * 3) 若请求仍在运行：标记 RUNNING；
 * 4) 其余情况标记 MISSING，提示用户尚未生成。
 *
 * 同时把生成计划中的展示字段（summary、items、warnings、prompt 等）一并拷贝到结果，
 * 让 UI 拿到一个完整可渲染的状态对象。
 */
export function deriveDraftImplementationSuggestionState(args: {
  generationPlan: GenerationPlan | null;
  generationPlanRequestState: AsyncRequestState;
  generationPlanDraftVersion: number | null;
  draftVersion: number | null;
}): DraftImplementationSuggestionState {
  return {
    status: args.generationPlan?.summary
      ? (
        // 草稿版本领先于计划版本，说明计划需要重新生成
        args.draftVersion != null
        && args.generationPlanDraftVersion != null
        && args.generationPlanDraftVersion < args.draftVersion
          ? "STALE"
          : "FRESH"
      )
      : requestFailed(args.generationPlanRequestState)
        ? "FAILED"
        : args.generationPlanRequestState.phase === "RUNNING"
          ? "RUNNING"
          : "MISSING",
    summary: args.generationPlan?.summary ?? null,
    items: args.generationPlan?.items ?? [],
    source: args.generationPlan?.source ?? null,
    warnings: args.generationPlan?.warnings ?? [],
    promptPreview: args.generationPlan?.promptPreview ?? null,
    promptPreviewArtifactId: args.generationPlan?.promptPreviewArtifactId ?? null,
    draftVersion: args.draftVersion,
    generationPlanDraftVersion: args.generationPlanDraftVersion,
  };
}

/**
 * 推导代码 diff 面板应处的状态。
 * 与实现建议的推导逻辑相似，但只关注是否有草稿与新鲜度，不带额外展示字段。
 */
export function deriveCodeDiffStatus(args: {
  generatedCodeDrafts: GeneratedCodeDraft[];
  generatedCodeDraftVersion: number | null;
  draftVersion: number | null;
  codeDraftRequestState: AsyncRequestState;
}): CodeDiffStatus {
  // 已有草稿：判断是否过期
  if (args.generatedCodeDrafts.length > 0) {
    return args.draftVersion != null
      && args.generatedCodeDraftVersion != null
      && args.generatedCodeDraftVersion < args.draftVersion
      ? "STALE"
      : "FRESH";
  }
  // 无草稿：根据请求状态决定具体相位
  if (args.codeDraftRequestState.phase === "RUNNING") {
    return "RUNNING";
  }
  if (requestFailed(args.codeDraftRequestState)) {
    return "FAILED";
  }
  return "MISSING";
}

/**
 * 判断请求是否以失败告终，统一 FAILED 与 TIMED_OUT 两种相位。
 * 多处需要"是否失败"的判定，故抽出公共函数避免重复。
 */
function requestFailed(requestState: AsyncRequestState): boolean {
  return requestState.phase === "FAILED" || requestState.phase === "TIMED_OUT";
}

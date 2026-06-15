import type {
  AsyncRequestState,
  DraftImplementationSuggestionState,
  GeneratedCodeDraft,
  GenerationPlan,
} from "./types";

export type CodeDiffStatus = "MISSING" | "RUNNING" | "FRESH" | "STALE" | "FAILED";

export function deriveDraftImplementationSuggestionState(args: {
  generationPlan: GenerationPlan | null;
  generationPlanRequestState: AsyncRequestState;
  generationPlanDraftVersion: number | null;
  draftVersion: number | null;
}): DraftImplementationSuggestionState {
  return {
    status: args.generationPlan?.summary
      ? (
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

export function deriveCodeDiffStatus(args: {
  generatedCodeDrafts: GeneratedCodeDraft[];
  generatedCodeDraftVersion: number | null;
  draftVersion: number | null;
  codeDraftRequestState: AsyncRequestState;
}): CodeDiffStatus {
  if (args.generatedCodeDrafts.length > 0) {
    return args.draftVersion != null
      && args.generatedCodeDraftVersion != null
      && args.generatedCodeDraftVersion < args.draftVersion
      ? "STALE"
      : "FRESH";
  }
  if (args.codeDraftRequestState.phase === "RUNNING") {
    return "RUNNING";
  }
  if (requestFailed(args.codeDraftRequestState)) {
    return "FAILED";
  }
  return "MISSING";
}

function requestFailed(requestState: AsyncRequestState): boolean {
  return requestState.phase === "FAILED" || requestState.phase === "TIMED_OUT";
}

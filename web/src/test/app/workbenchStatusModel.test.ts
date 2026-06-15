import { describe, expect, it } from "vitest";
import {
  deriveCodeDiffStatus,
  deriveDraftImplementationSuggestionState,
} from "../../app/workbenchStatusModel";
import type { AsyncRequestState, GeneratedCodeDraft, GenerationPlan } from "../../app/types";

const idle: AsyncRequestState = { phase: "IDLE" };
const running: AsyncRequestState = { phase: "RUNNING" };
const failed: AsyncRequestState = { phase: "FAILED", message: "failed" };
const timedOut: AsyncRequestState = { phase: "TIMED_OUT", message: "timed out" };

const plan: GenerationPlan = {
  source: "LOCAL_RULE",
  summary: "Draft plan",
  warnings: ["Check generated code manually."],
  promptPreview: "prompt",
  promptPreviewArtifactId: "artifact:plan",
  items: [{
    id: "item:1",
    title: "Update service",
    description: "Apply the accepted change.",
    risk: "LOW",
  }],
};

const codeDraft: GeneratedCodeDraft = {
  id: "draft:1",
  sourceNodeId: "node:1",
  title: "Service edit",
  targetPath: "src/Service.kt",
  content: "updated code",
  warnings: [],
};

describe("workbenchStatusModel", () => {
  it("derives draft implementation suggestion status from plan freshness and request state", () => {
    expect(deriveDraftImplementationSuggestionState({
      generationPlan: plan,
      generationPlanRequestState: idle,
      generationPlanDraftVersion: 2,
      draftVersion: 2,
    })).toMatchObject({
      status: "FRESH",
      summary: "Draft plan",
      items: plan.items,
      source: "LOCAL_RULE",
      warnings: plan.warnings,
      promptPreview: "prompt",
      promptPreviewArtifactId: "artifact:plan",
      draftVersion: 2,
      generationPlanDraftVersion: 2,
    });

    expect(deriveDraftImplementationSuggestionState({
      generationPlan: plan,
      generationPlanRequestState: idle,
      generationPlanDraftVersion: 1,
      draftVersion: 2,
    }).status).toBe("STALE");
    expect(deriveDraftImplementationSuggestionState({
      generationPlan: null,
      generationPlanRequestState: running,
      generationPlanDraftVersion: null,
      draftVersion: 2,
    }).status).toBe("RUNNING");
    expect(deriveDraftImplementationSuggestionState({
      generationPlan: null,
      generationPlanRequestState: failed,
      generationPlanDraftVersion: null,
      draftVersion: 2,
    }).status).toBe("FAILED");
    expect(deriveDraftImplementationSuggestionState({
      generationPlan: null,
      generationPlanRequestState: idle,
      generationPlanDraftVersion: null,
      draftVersion: 2,
    }).status).toBe("MISSING");
  });

  it("treats timed out generation plan requests as failed when no plan is available", () => {
    expect(deriveDraftImplementationSuggestionState({
      generationPlan: null,
      generationPlanRequestState: timedOut,
      generationPlanDraftVersion: null,
      draftVersion: null,
    }).status).toBe("FAILED");
  });

  it("derives code diff status from draft freshness and request state", () => {
    expect(deriveCodeDiffStatus({
      generatedCodeDrafts: [codeDraft],
      generatedCodeDraftVersion: 2,
      draftVersion: 2,
      codeDraftRequestState: idle,
    })).toBe("FRESH");
    expect(deriveCodeDiffStatus({
      generatedCodeDrafts: [codeDraft],
      generatedCodeDraftVersion: 1,
      draftVersion: 2,
      codeDraftRequestState: idle,
    })).toBe("STALE");
    expect(deriveCodeDiffStatus({
      generatedCodeDrafts: [],
      generatedCodeDraftVersion: null,
      draftVersion: 2,
      codeDraftRequestState: running,
    })).toBe("RUNNING");
    expect(deriveCodeDiffStatus({
      generatedCodeDrafts: [],
      generatedCodeDraftVersion: null,
      draftVersion: 2,
      codeDraftRequestState: failed,
    })).toBe("FAILED");
    expect(deriveCodeDiffStatus({
      generatedCodeDrafts: [],
      generatedCodeDraftVersion: null,
      draftVersion: 2,
      codeDraftRequestState: idle,
    })).toBe("MISSING");
  });
});

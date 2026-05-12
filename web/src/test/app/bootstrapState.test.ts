import { describe, expect, it } from "vitest";
import { readBootstrapState } from "../../app/api";
import { EMPTY_STATE } from "../../app/sampleState";

describe("bootstrap state", () => {
  it("keeps generation plan source from bootstrap state unchanged", () => {
    window.linkGraphBootstrap = {
      ...EMPTY_STATE,
      generationPlan: {
        source: "LOCAL_RULE",
        summary: "当前计划",
        warnings: [],
        promptPreview: null,
        items: [],
      },
    };

    expect(readBootstrapState()?.generationPlan?.source).toBe("LOCAL_RULE");
  });
});

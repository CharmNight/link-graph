import { describe, expect, it, vi } from "vitest";
import {
  requestCodeDraftsAsync,
  requestGenerationPlanAsync,
} from "./api";

describe("api async naming", () => {
  it("uses async-named helper for generation-plan requests", () => {
    const requestGenerationPlan = vi.fn();
    window.linkGraphBridge = {
      requestGenerationPlan,
    };

    requestGenerationPlanAsync();

    expect(requestGenerationPlan).toHaveBeenCalledTimes(1);
  });

  it("uses async-named helper for code-draft requests", () => {
    const requestCodeDrafts = vi.fn();
    window.linkGraphBridge = {
      requestCodeDrafts,
    };

    requestCodeDraftsAsync();

    expect(requestCodeDrafts).toHaveBeenCalledTimes(1);
  });
});

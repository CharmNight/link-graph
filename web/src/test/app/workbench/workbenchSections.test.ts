import { describe, expect, it } from "vitest";
import {
  defaultWorkbenchSectionPreferences,
  resolveEffectiveWorkbenchSectionPreferences,
} from "../../../app/workbench/workbenchSections";

describe("workbenchSections", () => {
  it("includes investigation leads in the default section preferences", () => {
    expect(defaultWorkbenchSectionPreferences()).toMatchObject({
      "qa.investigation-threads": false,
    });
  });

  it("preserves explicit investigation lead preferences in the effective qa layout", () => {
    const effective = resolveEffectiveWorkbenchSectionPreferences({
      tab: "qa",
      preferences: {
        "qa.investigation-threads": true,
      },
    });

    expect(effective["qa.investigation-threads"]).toBe(true);
  });
});

import { describe, expect, it } from "vitest";
import {
  defaultWorkbenchSectionPreferences,
  resolveEffectiveWorkbenchSectionPreferences,
} from "../../../app/workbench/workbenchSections";

describe("workbenchSections", () => {
  it("includes investigation leads in the default section preferences", () => {
    expect(defaultWorkbenchSectionPreferences()).toMatchObject({
      "audit.investigation-threads": false,
    });
  });

  it("preserves explicit investigation lead preferences in the effective audit layout", () => {
    const effective = resolveEffectiveWorkbenchSectionPreferences({
      tab: "audit",
      preferences: {
        "audit.investigation-threads": true,
      },
    });

    expect(effective["audit.investigation-threads"]).toBe(true);
  });
});

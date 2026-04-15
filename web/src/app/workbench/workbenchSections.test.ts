import { describe, expect, it } from "vitest";
import {
  defaultWorkbenchSectionPreferences,
  resolveEffectiveWorkbenchSectionPreferences,
} from "./workbenchSections";

describe("workbenchSections", () => {
  it("includes investigation leads in the default section preferences", () => {
    expect(defaultWorkbenchSectionPreferences()).toMatchObject({
      "audit.investigation-leads": false,
    });
  });

  it("preserves explicit investigation lead preferences in the effective audit layout", () => {
    const effective = resolveEffectiveWorkbenchSectionPreferences({
      tab: "audit",
      preferences: {
        "audit.investigation-leads": true,
      },
    });

    expect(effective["audit.investigation-leads"]).toBe(true);
  });
});

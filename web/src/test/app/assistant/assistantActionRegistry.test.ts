import { describe, expect, it } from "vitest";
import {
  assistantActionsForDisplayMode,
  defaultAssistantActionIdForDisplayMode,
  resolveAssistantActionIdForDisplayMode,
} from "../../../app/assistant/assistantActionRegistry";
import type { AnalysisDisplayMode } from "../../../app/types";

describe("assistantActionRegistry", () => {
  it("defines a default assistant action for every graph display mode", () => {
    const modes: AnalysisDisplayMode[] = [
      "FACT_GRAPH",
      "FLOWCHART",
      "RESOURCE_RELATION_VIEW",
      "ARCHITECTURE_GRAPH",
      "CLASS_DIAGRAM",
      "REVIEW_GRAPH",
    ];

    modes.forEach((mode) => {
      const actions = assistantActionsForDisplayMode(mode);
      const defaultActionId = defaultAssistantActionIdForDisplayMode(mode);

      expect(actions.map((action) => action.id)).toContain(defaultActionId);
    });
  });

  it("resets unsupported class diagram actions when the display mode changes", () => {
    expect(resolveAssistantActionIdForDisplayMode("DESCRIBE_CLASS", "FLOWCHART")).toBe("EXPLAIN_FLOW");
    expect(resolveAssistantActionIdForDisplayMode("DESCRIBE_CLASS", "RESOURCE_RELATION_VIEW")).toBe("EXPLAIN_STRUCTURE");
    expect(resolveAssistantActionIdForDisplayMode("DESCRIBE_CLASS", "REVIEW_GRAPH")).toBe("CHECK_CHANGE");
  });
});

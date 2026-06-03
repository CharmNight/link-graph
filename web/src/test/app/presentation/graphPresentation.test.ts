import { describe, expect, it } from "vitest";
import type { GraphViewPresentation } from "../../../app/types";
import {
  formatHiddenBuckets,
  sortedPresentationLanes,
  visibleGraphCountLabel,
} from "../../../app/presentation/graphPresentation";

describe("graphPresentation", () => {
  it("formats non-empty hidden buckets as compact labels", () => {
    expect(formatHiddenBuckets([
      { id: "external", label: "三方", count: 12, nodeIds: [], edgeIds: [] },
      { id: "jdk", label: "JDK", count: 0, nodeIds: [], edgeIds: [] },
    ])).toEqual(["三方 12"]);
  });

  it("sorts lanes by order and then id", () => {
    expect(sortedPresentationLanes([
      { id: "domain", label: "领域层", axis: "ROW", order: 30, role: "DOMAIN" },
      { id: "entry", label: "入口层", axis: "ROW", order: 10, role: "ENTRY" },
      { id: "application", label: "应用层", axis: "ROW", order: 20, role: "APPLICATION" },
    ]).map((lane) => lane.id)).toEqual(["entry", "application", "domain"]);
  });

  it("labels visible and total node counts", () => {
    const presentation: GraphViewPresentation = {
      target: { nodeId: "class:OrderService", title: "OrderService", subtitle: "service" },
      lanes: [],
      hiddenBuckets: [],
      controls: { primaryScope: "", availableScopes: [], searchable: true, expandable: true },
    };

    expect(visibleGraphCountLabel(presentation, 8, 21)).toBe("8 / 21");
    expect(visibleGraphCountLabel(presentation, 8, 0)).toBe("8 / 8");
  });
});

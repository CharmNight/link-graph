import { describe, expect, it } from "vitest";
import { buildRenderedFlowEdges } from "../../../app/reactflow/graphFlowDragEdges";
import type { RoutedEdgeData } from "../../../app/reactflow/RoutedEdge";

function routedEdge(id: string, source: string, target: string) {
  return {
    id,
    source,
    target,
    type: "routedEdge",
    data: {
      route: {
        sections: [{
          startPoint: { x: 100, y: 120 },
          endPoint: { x: 320, y: 120 },
        }],
      },
    } satisfies RoutedEdgeData,
  };
}

describe("graphFlowDragEdges", () => {
  it("drops stored route geometry only for edges attached to live-dragged nodes", () => {
    const incidentEdge = routedEdge("edge:anchor->tail", "anchor", "tail");
    const stableEdge = routedEdge("edge:stable->other", "stable", "other");

    const rendered = buildRenderedFlowEdges(
      [incidentEdge, stableEdge],
      { anchor: { x: 420, y: 240 } },
      "edge:stable->other",
    );

    expect(rendered[0]?.data?.route).toBeUndefined();
    expect(rendered[1]?.data?.route).toEqual(stableEdge.data.route);
    expect(rendered[0]?.selected).toBeUndefined();
    expect(rendered[1]?.selected).toBe(true);
    expect(incidentEdge.data.route).toBeDefined();
  });
});

import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { GraphCanvasLanes } from "../../../app/presentation/GraphCanvasLanes";
import type { GraphPresentationLane, LinkGraphNode } from "../../../app/types";

function node(
  id: string,
  x: number,
  y: number,
  laneId: string,
  role: string,
): LinkGraphNode {
  return {
    id,
    type: "CLASS",
    title: id,
    inputs: [],
    outputs: [],
    confidence: "VERIFIED",
    binding: "CODE_BOUND",
    position: { x, y },
    metadata: {
      "presentation.laneId": laneId,
      "presentation.role": role,
    },
  };
}

describe("GraphCanvasLanes", () => {
  it("renders sorted fact graph lanes from matching node lane metadata", () => {
    const lanes: GraphPresentationLane[] = [
      { id: "downstream", label: "下游事实", axis: "COLUMN", order: 30, role: "DOWNSTREAM" },
      { id: "upstream", label: "上游事实", axis: "COLUMN", order: 10, role: "UPSTREAM" },
    ];

    render(
      <GraphCanvasLanes
        lanes={lanes}
        nodes={[
          node("controller", 120, 80, "upstream", "UPSTREAM"),
          node("repository", 560, 80, "downstream", "DOWNSTREAM"),
        ]}
      />,
    );

    expect(screen.getByTestId("graph-canvas-lanes")).toHaveAttribute("data-lane-count", "2");
    expect(screen.getAllByTestId("graph-canvas-lane").map((lane) => lane.getAttribute("data-lane-id"))).toEqual([
      "upstream",
      "downstream",
    ]);
    expect(screen.getByText("上游事实")).toBeInTheDocument();
  });

  it("does not render a lane when it would wrap every visible node", () => {
    render(
      <GraphCanvasLanes
        lanes={[
          { id: "upstream", label: "上游事实", axis: "COLUMN", order: 10, role: "UPSTREAM" },
        ]}
        nodes={[
          node("controller", 120, 80, "upstream", "UPSTREAM"),
          node("validator", 360, 80, "upstream", "UPSTREAM"),
        ]}
      />,
    );

    expect(screen.getByTestId("graph-canvas-lanes")).toHaveAttribute("data-lane-count", "0");
    expect(screen.queryByText("上游事实")).not.toBeInTheDocument();
  });
});

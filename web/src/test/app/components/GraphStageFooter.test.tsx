import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { GraphStageFooter } from "../../../app/components/GraphStageFooter";

describe("GraphStageFooter", () => {
  it("uses serialized node counts and business labels instead of raw status codes", () => {
    render(
      <GraphStageFooter
        analysisDisplayMode="REVIEW_GRAPH"
        activeViewGraph={{ nodes: [], edges: [], nodeCount: 9061 }}
        fullNodeCount={0}
        hasExplanationFocus={false}
        draftChangedNodeCount={0}
        codeDiffStatus="MISSING"
      />,
    );

    expect(screen.getByText("节点 9061 / 9061")).toBeInTheDocument();
    expect(screen.getByText("代码 diff 未生成")).toBeInTheDocument();
  });
});

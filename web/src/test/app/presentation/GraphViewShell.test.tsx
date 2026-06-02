import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { GraphViewShell } from "../../../app/presentation/GraphViewShell";
import type { GraphViewPresentation } from "../../../app/types";

const presentation: GraphViewPresentation = {
  target: { nodeId: "method:anchor", title: "OrderService.place", subtitle: "当前方法" },
  lanes: [],
  hiddenBuckets: [],
  controls: { primaryScope: "主链", availableScopes: ["主链"], searchable: true, expandable: true },
};

describe("GraphViewShell", () => {
  it("keeps graph content inside a single body row below the title and toolbar", () => {
    const { container } = render(
      <GraphViewShell
        presentation={presentation}
        visibleNodeCount={3}
        fullNodeCount={8}
        query=""
        scope="主链"
        onQueryChange={vi.fn()}
        onScopeChange={vi.fn()}
        onLocateTarget={vi.fn()}
        onExpand={vi.fn()}
      >
        <section data-testid="draft-summary">draft</section>
        <section data-testid="graph-surface">graph</section>
      </GraphViewShell>,
    );

    const body = container.querySelector(".graph-view-body");
    expect(body).toContainElement(screen.getByTestId("draft-summary"));
    expect(body).toContainElement(screen.getByTestId("graph-surface"));
  });
});

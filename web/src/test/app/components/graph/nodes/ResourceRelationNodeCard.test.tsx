import { render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ResourceRelationNodeCard } from "../../../../../app/components/graph/nodes/ResourceRelationNodeCard";
import type { LinkGraphNode } from "../../../../../app/types";

function resourceNode(): LinkGraphNode {
  return {
    id: "resource:http",
    type: "HTTP_ENDPOINT",
    title: "GET /common/download",
    signature: "GET /common/download",
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
  };
}

const originalOffsetWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetWidth");
const originalOffsetHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetHeight");

describe("ResourceRelationNodeCard", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    if (originalOffsetWidth) {
      Object.defineProperty(HTMLElement.prototype, "offsetWidth", originalOffsetWidth);
    } else {
      Reflect.deleteProperty(HTMLElement.prototype, "offsetWidth");
    }
    if (originalOffsetHeight) {
      Object.defineProperty(HTMLElement.prototype, "offsetHeight", originalOffsetHeight);
    } else {
      Reflect.deleteProperty(HTMLElement.prototype, "offsetHeight");
    }
  });

  it("renders readable badges for explanation focus and draft changes", () => {
    const { container } = render(
      <ResourceRelationNodeCard
        node={resourceNode()}
        selected={false}
        explanationFocused
        draftChanged
      />,
    );

    expect(container.querySelector(".flow-node-state-badges")).not.toBeNull();
    expect(container.textContent).toContain("讲解中");
    expect(container.textContent).toContain("已改草稿");
  });

  it("renders a draft compare badge for resource nodes", () => {
    const { container } = render(
      <ResourceRelationNodeCard
        node={resourceNode()}
        selected={false}
        draftCompareStatus="MODIFIED"
      />,
    );

    expect(container.textContent).toContain("草稿修改");
  });
});

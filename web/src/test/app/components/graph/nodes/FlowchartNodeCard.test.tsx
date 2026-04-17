import { render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FlowchartNodeCard } from "../../../../../app/components/graph/nodes/FlowchartNodeCard";
import type { LinkGraphNode } from "../../../../../app/types";

function flowNode(): LinkGraphNode {
  return {
    id: "flow:validate",
    type: "FLOW_ACTION",
    title: "validate(request)",
    signature: "validate(request)",
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    metadata: {
      "flowchart.kind": "PROCESS",
    },
  };
}

const originalOffsetWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetWidth");
const originalOffsetHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetHeight");

describe("FlowchartNodeCard", () => {
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
      <FlowchartNodeCard
        node={flowNode()}
        selected={false}
        explanationFocused
        draftChanged
      />,
    );

    expect(container.querySelector(".flow-node-state-badges")).not.toBeNull();
    expect(container.textContent).toContain("讲解中");
    expect(container.textContent).toContain("已改草稿");
  });
});

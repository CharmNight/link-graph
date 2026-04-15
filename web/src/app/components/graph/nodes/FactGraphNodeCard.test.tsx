import { render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FactGraphNodeCard } from "./FactGraphNodeCard";
import type { LinkGraphNode } from "../../../types";

function factNode(): LinkGraphNode {
  return {
    id: "method:submit-order",
    type: "METHOD",
    title: "OrderService.submit",
    signature: "com.example.OrderService.submit(java.lang.String):void",
    inputs: ["java.lang.String"],
    outputs: ["void"],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
  };
}

const originalOffsetWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetWidth");
const originalOffsetHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetHeight");

describe("FactGraphNodeCard", () => {
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

  it("measures the intrinsic card box instead of the zoomed viewport bounding rect", () => {
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({
      x: 0,
      y: 0,
      width: 326,
      height: 125,
      top: 0,
      right: 326,
      bottom: 125,
      left: 0,
      toJSON: () => undefined,
    } as DOMRect);
    Object.defineProperty(HTMLElement.prototype, "offsetWidth", {
      configurable: true,
      get() {
        return 408;
      },
    });
    Object.defineProperty(HTMLElement.prototype, "offsetHeight", {
      configurable: true,
      get() {
        return 156;
      },
    });

    const onMeasure = vi.fn();

    render(
      <FactGraphNodeCard
        node={factNode()}
        selected={false}
        collapsed={false}
        onMeasure={onMeasure}
      />,
    );

    expect(onMeasure).toHaveBeenCalledWith({ width: 408, height: 156 });
  });

  it("does not re-measure or expand the card when selection changes", () => {
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({
      x: 0,
      y: 0,
      width: 408,
      height: 156,
      top: 0,
      right: 408,
      bottom: 156,
      left: 0,
      toJSON: () => undefined,
    } as DOMRect);

    const onMeasure = vi.fn();
    const node = factNode();
    const { rerender, container } = render(
      <FactGraphNodeCard
        node={node}
        selected={false}
        collapsed={false}
        onMeasure={onMeasure}
      />,
    );

    expect(onMeasure).toHaveBeenCalledTimes(1);
    expect(container.querySelector(`[data-node-id="${node.id}"]`)).not.toHaveClass("is-expanded");

    rerender(
      <FactGraphNodeCard
        node={node}
        selected
        collapsed={false}
        onMeasure={onMeasure}
      />,
    );

    expect(onMeasure).toHaveBeenCalledTimes(1);
    expect(container.querySelector(`[data-node-id="${node.id}"]`)).toHaveClass("is-selected");
    expect(container.querySelector(`[data-node-id="${node.id}"]`)).not.toHaveClass("is-expanded");
    expect(container.textContent).toContain("当前选中");
  });

  it("renders readable badges for explanation focus and draft changes", () => {
    const { container } = render(
      <FactGraphNodeCard
        node={factNode()}
        selected={false}
        collapsed={false}
        explanationFocused
        draftChanged
      />,
    );

    expect(container.querySelector(".flow-node-state-badges")).not.toBeNull();
    expect(container.textContent).toContain("讲解中");
    expect(container.textContent).toContain("已改草稿");
  });
});

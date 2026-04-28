import { describe, expect, it } from "vitest";
import { createNodeSizeRegistry } from "../../../app/graph/nodeSizeRegistry";

describe("nodeSizeRegistry", () => {
  it("stores measured node sizes locally without mutating graph nodes or metadata", () => {
    const registry = createNodeSizeRegistry();
    const node = {
      id: "method:submit-order",
      type: "METHOD",
      title: "OrderController.submit",
      inputs: ["java.lang.String"],
      outputs: ["void"],
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      metadata: {
        "ui.x": "120",
        "ui.y": "96",
      },
    } as const;

    const changed = registry.set(node.id, { width: 560, height: 228 });

    expect(changed).toBe(true);
    expect(registry.get(node.id)).toEqual({ width: 560, height: 228 });
    expect((node as Record<string, unknown>).width).toBeUndefined();
    expect((node as Record<string, unknown>).height).toBeUndefined();
    expect(node.metadata).toEqual({
      "ui.x": "120",
      "ui.y": "96",
    });
  });

  it("ignores duplicate measurements and supports clearing cached entries", () => {
    const registry = createNodeSizeRegistry();

    expect(registry.set("node:a", { width: 408, height: 156 })).toBe(true);
    expect(registry.set("node:a", { width: 408, height: 156 })).toBe(false);
    expect(registry.snapshot().get("node:a")).toEqual({ width: 408, height: 156 });

    registry.clear("node:a");
    expect(registry.get("node:a")).toBeUndefined();
  });

  it("reuses a stable measurement reporter per node so visual-only rerenders do not re-trigger layout collection", () => {
    const registry = createNodeSizeRegistry();

    const nodeAReporter = registry.reporter("node:a");
    const nodeAReporterAgain = registry.reporter("node:a");
    const nodeBReporter = registry.reporter("node:b");

    expect(nodeAReporter).toBe(nodeAReporterAgain);
    expect(nodeAReporter).not.toBe(nodeBReporter);

    nodeAReporter({ width: 480, height: 180 });

    expect(registry.get("node:a")).toEqual({ width: 480, height: 180 });
  });
});

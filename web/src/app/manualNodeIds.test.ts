import { describe, expect, it } from "vitest";
import { createManualNodeIdAllocator } from "./manualNodeIds";

describe("createManualNodeIdAllocator", () => {
  it("allocates monotonic ids even after holes exist", () => {
    const allocate = createManualNodeIdAllocator([
      { id: "design:1" },
      { id: "design:3" },
      { id: "design-note:2" },
    ]);

    expect(allocate("METHOD")).toBe("design:4");
    expect(allocate("METHOD")).toBe("design:5");
    expect(allocate("DOC_PAGE")).toBe("design-note:6");
  });

  it("uses the current node count as the floor when seeding the counter", () => {
    const allocate = createManualNodeIdAllocator([
      { id: "method:submit-order" },
      { id: "doc:note" },
      { id: "design:9" },
    ]);

    expect(allocate("DOC_PAGE")).toBe("design-note:10");
    expect(allocate("METHOD")).toBe("design:11");
  });
});

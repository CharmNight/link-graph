import { afterEach, describe, expect, it, vi } from "vitest";
import { traceLinkGraph } from "../../app/debug";

describe("traceLinkGraph", () => {
  const consoleLogSpy = vi.spyOn(console, "log").mockImplementation(() => undefined);

  afterEach(() => {
    window.__linkGraphTraceBuffer = [];
    window.__linkGraphTraceHistory = [];
    window.__linkGraphLastTrace = undefined;
    window.__linkGraphDebugEnabled = undefined;
    window.linkGraphDebugTrace = undefined;
    consoleLogSpy.mockClear();
  });

  it("does not buffer traces by default when debug bridge is absent", () => {
    traceLinkGraph("graph.rendered", { nodes: 2 });

    expect(window.__linkGraphTraceBuffer ?? []).toHaveLength(0);
    expect(consoleLogSpy).not.toHaveBeenCalled();
  });

  it("sends traces directly to bridge when bridge is available", () => {
    const traceSink = vi.fn();
    window.linkGraphDebugTrace = traceSink;

    traceLinkGraph("graph.rendered", { nodes: 3 });

    expect(traceSink).toHaveBeenCalledTimes(1);
    expect(window.__linkGraphTraceBuffer ?? []).toHaveLength(0);
    expect(consoleLogSpy).not.toHaveBeenCalled();
  });

  it("keeps recent trace history even when only the in-page debug flag is enabled", () => {
    window.__linkGraphDebugEnabled = true;

    traceLinkGraph("graph.viewport.apply", { branch: "flowchartAnchor" });

    expect(window.__linkGraphTraceBuffer ?? []).toHaveLength(1);
    expect(window.__linkGraphTraceHistory ?? []).toHaveLength(1);
    expect(window.__linkGraphLastTrace).toContain("\"event\":\"graph.viewport.apply\"");
  });

  it("keeps recent trace history when traces are sent directly to the bridge", () => {
    const traceSink = vi.fn();
    window.linkGraphDebugTrace = traceSink;

    traceLinkGraph("graph.viewport.apply", { branch: "fitView" });

    expect(traceSink).toHaveBeenCalledTimes(1);
    expect(window.__linkGraphTraceHistory ?? []).toHaveLength(1);
    expect(window.__linkGraphLastTrace).toContain("\"branch\":\"fitView\"");
  });
});
